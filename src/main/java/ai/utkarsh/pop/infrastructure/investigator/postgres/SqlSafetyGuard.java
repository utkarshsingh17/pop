package ai.utkarsh.pop.infrastructure.investigator.postgres;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a SQL string is provably read-only before it is allowed anywhere near the
 * database under observation.
 *
 * <p>This exists because the agent can be asked to analyse SQL that arrives from a prompt, and
 * a language model can be talked into proposing destructive statements. The guard is the last
 * deterministic checkpoint before execution.
 *
 * <p>It is <em>defence in depth</em>, not the only defence. The target connection also uses a
 * role with no write privileges, runs in a read-only transaction, and carries a statement
 * timeout. Any one of those failing should not be enough to cause damage.
 *
 * <p>Design stance: <strong>reject anything not obviously safe.</strong> A false rejection is a
 * mild inconvenience; a false acceptance can drop a table. Literals and comments are masked
 * before analysis so that neither {@code SELECT 'DROP TABLE x'} is wrongly rejected nor
 * {@code SELECT 1 /* }{@code * / ; DROP TABLE x} wrongly accepted.
 */
@Component
public class SqlSafetyGuard {

    private static final int MAX_LENGTH = 20_000;

    /** Only these may begin a statement. */
    private static final Set<String> ALLOWED_LEADING = Set.of("SELECT", "WITH", "EXPLAIN", "VALUES", "TABLE");

    /**
     * Rejected anywhere outside a literal. Covers writes, DDL, privilege changes, session
     * state, transaction control, and the file/network-reaching functions that turn a plain
     * SELECT into an exfiltration primitive.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "TRUNCATE",
            "DROP", "ALTER", "CREATE", "RENAME", "COMMENT",
            "GRANT", "REVOKE", "REASSIGN", "SECURITY",
            "COPY", "VACUUM", "ANALYSE", "ANALYZE", "REINDEX", "CLUSTER", "REFRESH",
            "SET", "RESET", "LOCK", "NOTIFY", "LISTEN", "UNLISTEN",
            "PREPARE", "EXECUTE", "DEALLOCATE", "DECLARE", "FETCH", "MOVE", "CLOSE",
            "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "ABORT", "END",
            "CALL", "DO", "IMPORT", "INTO",
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_STAT_FILE",
            "LO_IMPORT", "LO_EXPORT", "DBLINK", "PG_SLEEP", "PG_TERMINATE_BACKEND",
            "PG_CANCEL_BACKEND", "PG_RELOAD_CONF", "PG_ROTATE_LOGFILE", "SETVAL", "NEXTVAL");

    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "\\b(" + String.join("|", FORBIDDEN) + ")\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LEADING_WORD = Pattern.compile("^\\s*([A-Za-z]+)");

    /** Rejects {@code EXPLAIN (ANALYZE ...)}, which executes the statement. */
    private static final Pattern EXPLAIN_ANALYZE = Pattern.compile(
            "^\\s*EXPLAIN\\b[^(]*(\\(([^)]*)\\))?", Pattern.CASE_INSENSITIVE);

    /**
     * @param sql                 the statement to check
     * @param allowExplainAnalyze whether {@code EXPLAIN ANALYZE} — which actually runs the
     *                            query — is permitted for this call
     * @return the statement, trimmed and with any trailing semicolon removed
     * @throws UnsafeSqlException if the statement is not provably read-only
     */
    public String requireReadOnly(String sql, boolean allowExplainAnalyze) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("SQL must not be blank");
        }
        if (sql.length() > MAX_LENGTH) {
            throw new UnsafeSqlException("SQL exceeds the maximum length of " + MAX_LENGTH + " characters");
        }

        String trimmed = sql.strip();
        String masked = maskLiteralsAndComments(trimmed);

        // A trailing semicolon is fine; anything after it is a second statement.
        String maskedBody = stripTrailingSemicolon(masked);
        if (maskedBody.contains(";")) {
            throw new UnsafeSqlException("Only a single statement may be analysed; found a ';' separator");
        }

        var leading = LEADING_WORD.matcher(maskedBody);
        if (!leading.find()) {
            throw new UnsafeSqlException("SQL must begin with a keyword");
        }
        String keyword = leading.group(1).toUpperCase(Locale.ROOT);
        if (!ALLOWED_LEADING.contains(keyword)) {
            throw new UnsafeSqlException(
                    "Only read-only statements may be analysed; found a statement beginning with " + keyword);
        }

        var forbidden = FORBIDDEN_PATTERN.matcher(maskedBody);
        if (forbidden.find()) {
            // `EXPLAIN ANALYZE` is handled separately below; every other hit is fatal.
            String hit = forbidden.group(1).toUpperCase(Locale.ROOT);
            boolean isExplainAnalyze = keyword.equals("EXPLAIN")
                    && (hit.equals("ANALYZE") || hit.equals("ANALYSE"));
            if (!isExplainAnalyze) {
                throw new UnsafeSqlException("SQL contains the disallowed keyword " + hit);
            }
            if (!allowExplainAnalyze) {
                throw new UnsafeSqlException(
                        "EXPLAIN ANALYZE executes the statement and is disabled; "
                                + "use plain EXPLAIN or enable pop.investigation.allow-explain-analyze");
            }
            // Guard against a second forbidden keyword hiding after the ANALYZE.
            if (forbidden.find()) {
                throw new UnsafeSqlException(
                        "SQL contains the disallowed keyword " + forbidden.group(1).toUpperCase(Locale.ROOT));
            }
        }

        return stripTrailingSemicolon(trimmed).strip();
    }

    /** Convenience for the common case where EXPLAIN ANALYZE is not permitted. */
    public String requireReadOnly(String sql) {
        return requireReadOnly(sql, false);
    }

    private static String stripTrailingSemicolon(String sql) {
        String result = sql.stripTrailing();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).stripTrailing();
        }
        return result;
    }

    /**
     * Replaces every string literal, quoted identifier, dollar-quoted block and comment with an
     * inert placeholder, so keyword and separator detection sees only executable structure.
     *
     * <p>Written as an explicit scanner rather than a chain of regexes because the constructs
     * nest: a {@code --} inside a literal is not a comment, and a quote inside a comment does
     * not open a literal. Regex-per-construct gets that wrong in exactly the cases an attacker
     * would reach for.
     */
    static String maskLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }

            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;                       // Postgres block comments nest
                i += 2;
                while (i < n && depth > 0) {
                    if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (i + 1 < n && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append(' ');
                continue;
            }

            if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
                out.append("''");
                continue;
            }

            if (c == '"') {
                i = skipQuoted(sql, i, '"');
                out.append("\"x\"");                 // keep it looking like an identifier
                continue;
            }

            if (c == '$') {
                int tagEnd = sql.indexOf('$', i + 1);
                if (tagEnd > i) {
                    String tag = sql.substring(i, tagEnd + 1);
                    if (tag.substring(1, tag.length() - 1).matches("[A-Za-z_][A-Za-z_0-9]*|")) {
                        int close = sql.indexOf(tag, tagEnd + 1);
                        i = (close < 0) ? n : close + tag.length();
                        out.append("''");
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Advances past a quoted run starting at {@code start}, honouring doubled-quote escapes. */
    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;                          // '' or "" is an escaped quote
                    continue;
                }
                return i + 1;
            }
            if (sql.charAt(i) == '\\' && quote == '\'' && i + 1 < n) {
                i += 2;                              // backslash escape in E'' strings
                continue;
            }
            i++;
        }
        return n;                                    // unterminated — consume the rest
    }
}
