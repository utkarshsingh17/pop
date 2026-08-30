package ai.utkarsh.pop.infrastructure.investigator.postgres;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyGuardTest {

    private final SqlSafetyGuard guard = new SqlSafetyGuard();

    @Nested
    class Accepts {

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT 1",
                "select id, total_cents from orders where customer_id = 42",
                "SELECT * FROM orders ORDER BY created_at DESC LIMIT 10",
                "WITH recent AS (SELECT * FROM orders LIMIT 5) SELECT count(*) FROM recent",
                "EXPLAIN SELECT * FROM orders WHERE customer_id = 1",
                "EXPLAIN (FORMAT JSON, BUFFERS FALSE) SELECT * FROM orders",
                "VALUES (1), (2)",
                "SELECT count(*) FROM orders;",
                "SELECT count(*) FROM orders;;  ",
                "  \n SELECT 1 \n ",
        })
        void shouldAcceptReadOnlyStatements(String sql) {
            assertThatCode(() -> guard.requireReadOnly(sql)).doesNotThrowAnyException();
        }

        @Test
        void shouldAcceptForbiddenKeywordsInsideStringLiterals() {
            // The literal is data, not executable structure.
            assertThatCode(() -> guard.requireReadOnly(
                    "SELECT * FROM audit WHERE action = 'DROP TABLE orders'"))
                    .doesNotThrowAnyException();
        }

        @Test
        void shouldAcceptSemicolonInsideStringLiteral() {
            assertThatCode(() -> guard.requireReadOnly("SELECT 'a;b' AS x"))
                    .doesNotThrowAnyException();
        }

        @Test
        void shouldAcceptDoubledQuoteEscapeInsideLiteral() {
            assertThatCode(() -> guard.requireReadOnly("SELECT 'it''s fine' AS x"))
                    .doesNotThrowAnyException();
        }

        @Test
        void shouldStripTrailingSemicolonFromReturnedSql() {
            assertThat(guard.requireReadOnly("SELECT 1;  ")).isEqualTo("SELECT 1");
        }

        @Test
        void shouldPreserveOriginalSqlOtherwise() {
            String sql = "SELECT a, b FROM t WHERE x = 'y'";
            assertThat(guard.requireReadOnly(sql)).isEqualTo(sql);
        }
    }

    @Nested
    class RejectsWrites {

        @ParameterizedTest
        @ValueSource(strings = {
                "DROP TABLE orders",
                "drop table orders",
                "DELETE FROM orders",
                "UPDATE orders SET status = 'PAID'",
                "INSERT INTO orders (id) VALUES (1)",
                "TRUNCATE orders",
                "ALTER TABLE orders ADD COLUMN x INT",
                "CREATE INDEX idx ON orders (customer_id)",
                "GRANT ALL ON orders TO public",
                "COPY orders TO '/tmp/out.csv'",
                "VACUUM FULL orders",
                "CALL some_procedure()",
                "DO $$ BEGIN PERFORM 1; END $$",
        })
        void shouldRejectNonReadOnlyStatements(String sql) {
            assertThatThrownBy(() -> guard.requireReadOnly(sql))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectDataModifyingCte() {
            // The classic bypass: a write hidden inside a statement that starts with WITH.
            assertThatThrownBy(() -> guard.requireReadOnly(
                    "WITH gone AS (DELETE FROM orders RETURNING id) SELECT count(*) FROM gone"))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("DELETE");
        }

        @Test
        void shouldRejectUpdateReturningInsideCte() {
            assertThatThrownBy(() -> guard.requireReadOnly(
                    "WITH x AS (UPDATE orders SET status='X' RETURNING *) SELECT * FROM x"))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("UPDATE");
        }

        @Test
        void shouldRejectSelectInto() {
            // SELECT ... INTO creates a new table.
            assertThatThrownBy(() -> guard.requireReadOnly("SELECT * INTO copy_of_orders FROM orders"))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("INTO");
        }
    }

    @Nested
    class RejectsStatementChaining {

        @Test
        void shouldRejectStackedStatements() {
            assertThatThrownBy(() -> guard.requireReadOnly("SELECT 1; DROP TABLE orders"))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("single statement");
        }

        @Test
        void shouldRejectChainingHiddenBehindLineComment() {
            assertThatThrownBy(() -> guard.requireReadOnly("SELECT 1 -- harmless\n; DROP TABLE orders"))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectChainingHiddenInsideBlockComment() {
            assertThatThrownBy(() -> guard.requireReadOnly("SELECT 1 /* note */ ; DROP TABLE orders"))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectKeywordSplitAcrossNestedBlockComments() {
            // Nested comments are legal in Postgres; a non-nesting stripper would leave
            // the DROP exposed or mangle the statement.
            assertThatThrownBy(() -> guard.requireReadOnly(
                    "SELECT 1 /* outer /* inner */ still comment */ ; DROP TABLE orders"))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectStatementAfterDollarQuotedBlock() {
            assertThatThrownBy(() -> guard.requireReadOnly("SELECT $$a;b$$ ; DROP TABLE orders"))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("single statement");
        }

        @Test
        void shouldRejectTaggedDollarQuoteEscape() {
            assertThatThrownBy(() -> guard.requireReadOnly("SELECT $tag$x;y$tag$; DELETE FROM orders"))
                    .isInstanceOf(UnsafeSqlException.class);
        }
    }

    @Nested
    class RejectsSessionAndFileAccess {

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT pg_read_file('/etc/passwd')",
                "SELECT pg_ls_dir('/')",
                "SELECT lo_import('/etc/shadow')",
                "SELECT pg_sleep(60)",
                "SELECT pg_terminate_backend(123)",
                "SELECT * FROM dblink('host=evil', 'SELECT 1') AS t(x int)",
                "SELECT nextval('orders_id_seq')",
                "SELECT setval('orders_id_seq', 1)",
        })
        void shouldRejectDangerousFunctions(String sql) {
            assertThatThrownBy(() -> guard.requireReadOnly(sql))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SET ROLE postgres",
                "BEGIN",
                "COMMIT",
                "PREPARE p AS SELECT 1",
                "DECLARE c CURSOR FOR SELECT 1",
                "LOCK TABLE orders",
        })
        void shouldRejectSessionAndTransactionControl(String sql) {
            assertThatThrownBy(() -> guard.requireReadOnly(sql))
                    .isInstanceOf(UnsafeSqlException.class);
        }
    }

    @Nested
    class ExplainAnalyze {

        @Test
        void shouldRejectExplainAnalyzeByDefault() {
            assertThatThrownBy(() -> guard.requireReadOnly("EXPLAIN ANALYZE SELECT * FROM orders"))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("executes the statement");
        }

        @Test
        void shouldRejectParenthesisedExplainAnalyzeByDefault() {
            assertThatThrownBy(() -> guard.requireReadOnly("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM orders"))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldAllowExplainAnalyzeWhenExplicitlyEnabled() {
            assertThatCode(() -> guard.requireReadOnly("EXPLAIN ANALYZE SELECT * FROM orders", true))
                    .doesNotThrowAnyException();
        }

        @Test
        void shouldStillRejectWritesWhenExplainAnalyzeEnabled() {
            assertThatThrownBy(() -> guard.requireReadOnly("EXPLAIN ANALYZE DELETE FROM orders", true))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectSecondForbiddenKeywordAfterAnalyze() {
            assertThatThrownBy(() -> guard.requireReadOnly(
                    "EXPLAIN ANALYZE SELECT pg_read_file('/etc/passwd')", true))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("PG_READ_FILE");
        }
    }

    @Nested
    class RejectsMalformedInput {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\n\t "})
        void shouldRejectBlank(String sql) {
            assertThatThrownBy(() -> guard.requireReadOnly(sql))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void shouldRejectNull() {
            assertThatThrownBy(() -> guard.requireReadOnly(null))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectOversizedInput() {
            String huge = "SELECT " + "a,".repeat(20_000);
            assertThatThrownBy(() -> guard.requireReadOnly(huge))
                    .isInstanceOf(UnsafeSqlException.class)
                    .hasMessageContaining("maximum length");
        }

        @Test
        void shouldRejectStatementThatIsOnlyAComment() {
            assertThatThrownBy(() -> guard.requireReadOnly("-- just a comment"))
                    .isInstanceOf(UnsafeSqlException.class);
        }

        @Test
        void shouldRejectUnterminatedLiteralFollowedByWrite() {
            // Everything after the opening quote is consumed as literal, so the statement
            // never becomes a valid SELECT — and must not be waved through.
            assertThatCode(() -> guard.requireReadOnly("SELECT 'unterminated"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.requireReadOnly("DROP TABLE x -- 'unterminated"))
                    .isInstanceOf(UnsafeSqlException.class);
        }
    }

    @Nested
    class Masking {

        @Test
        void shouldBlankLineComments() {
            assertThat(SqlSafetyGuard.maskLiteralsAndComments("SELECT 1 -- DROP TABLE x"))
                    .doesNotContain("DROP");
        }

        @Test
        void shouldBlankNestedBlockComments() {
            assertThat(SqlSafetyGuard.maskLiteralsAndComments("SELECT /* a /* b */ c */ 1"))
                    .doesNotContain("a", "b", "c");
        }

        @Test
        void shouldBlankStringLiteralsButKeepStructure() {
            String masked = SqlSafetyGuard.maskLiteralsAndComments("SELECT 'DROP' FROM t");
            assertThat(masked).doesNotContain("DROP").contains("SELECT").contains("FROM t");
        }

        @Test
        void shouldNotTreatCommentMarkerInsideLiteralAsComment() {
            String masked = SqlSafetyGuard.maskLiteralsAndComments("SELECT '--' , x FROM t");
            assertThat(masked).contains("FROM t");
        }
    }
}
