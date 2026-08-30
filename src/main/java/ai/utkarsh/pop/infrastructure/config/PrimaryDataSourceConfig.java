package ai.utkarsh.pop.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * pop's own datasource — the one Flyway migrates and JPA writes to.
 *
 * <p>This has to be declared explicitly. Boot's {@code DataSourceAutoConfiguration} is
 * conditional on <em>no</em> {@code DataSource} bean existing, so the moment
 * {@link TargetDataSourceConfig} defines the read-only target, auto-configuration backs off
 * entirely and the target would become the application's only datasource — Flyway would try to
 * migrate the database pop is supposed to be observing.
 *
 * <p>{@code @Primary} is what makes JPA, the transaction manager and Flyway resolve to this one.
 */
@Configuration(proxyBeanMethods = false)
class PrimaryDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties primaryDataSourceProperties) {
        HikariDataSource dataSource = primaryDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("pop-primary");
        return dataSource;
    }
}
