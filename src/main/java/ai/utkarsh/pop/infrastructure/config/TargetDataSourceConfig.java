package ai.utkarsh.pop.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Wires the connection to the database under observation.
 *
 * <p>Kept entirely separate from the primary datasource: pop must never migrate, write to, or
 * accidentally treat the observed system as its own storage. The pool is deliberately small —
 * an investigation should never be able to exhaust connections on the system it is diagnosing.
 *
 * <p>The connection is marked read-only at the pool level, so every transaction it hands out
 * starts read-only regardless of what the calling code does. Together with the restricted
 * database role and {@code SqlSafetyGuard}, that is three independent barriers to a write.
 */
@Configuration(proxyBeanMethods = false)
public class TargetDataSourceConfig {

    public static final String TARGET_DATA_SOURCE = "targetDataSource";
    public static final String TARGET_JDBC_TEMPLATE = "targetJdbcTemplate";

    @Bean(name = TARGET_DATA_SOURCE, destroyMethod = "close")
    HikariDataSource targetDataSource(TargetDatabaseProperties properties) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(properties.url())
                .username(properties.username())
                .password(properties.password())
                .build();

        dataSource.setPoolName("pop-target");
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(0);
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(true);
        dataSource.setConnectionTimeout(5_000);
        dataSource.setIdleTimeout(30_000);
        // Applied per connection; bounds every statement the agent can issue.
        dataSource.addDataSourceProperty("options",
                "-c statement_timeout=" + properties.statementTimeout().toMillis());
        return dataSource;
    }

    /**
     * Explicitly qualified: the primary datasource is {@code @Primary}, so an unqualified
     * {@code DataSource} parameter would silently resolve to pop's own database and point
     * every investigator at the wrong server.
     */
    @Bean(name = TARGET_JDBC_TEMPLATE)
    JdbcTemplate targetJdbcTemplate(@Qualifier(TARGET_DATA_SOURCE) DataSource targetDataSource,
                                    TargetDatabaseProperties properties) {
        JdbcTemplate template = new JdbcTemplate(targetDataSource);
        template.setMaxRows(properties.maxRows());
        template.setQueryTimeout((int) properties.statementTimeout().toSeconds());
        return template;
    }
}
