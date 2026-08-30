package ai.utkarsh.pop;

import ai.utkarsh.pop.application.tool.OpsTools;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.port.in.GetInvestigationUseCase;
import ai.utkarsh.pop.domain.port.in.StartInvestigationUseCase;
import ai.utkarsh.pop.domain.port.out.DiagnosisEnginePort;
import ai.utkarsh.pop.domain.port.out.InvestigationRepository;
import ai.utkarsh.pop.domain.port.out.InvestigatorPort;
import ai.utkarsh.pop.infrastructure.config.TargetDataSourceConfig;
import ai.utkarsh.pop.infrastructure.mcp.OpsMcpTools;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application to prove the wiring holds: both datasources, Flyway, the HTTP
 * interface client group, the resilience proxies, the agent's tools and the MCP server.
 *
 * <p>No network calls to Anthropic happen — the key is a placeholder and nothing invokes the
 * chat client. This is a wiring test, not a model test.
 */
@SpringBootTest
@Import(PopApplicationTests.TestInfrastructure.class)
class PopApplicationTests {

	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pop")
			.withUsername("pop")
			.withPassword("pop");

	static {
		POSTGRES.start();
	}

	@Autowired
	private StartInvestigationUseCase startInvestigation;

	@Autowired
	private GetInvestigationUseCase getInvestigation;

	@Autowired
	private InvestigationRepository investigationRepository;

	@Autowired
	private DiagnosisEnginePort diagnosisEngine;

	@Autowired
	private List<InvestigatorPort> investigators;

	@Autowired
	private OpsTools opsTools;

	@Autowired
	private OpsMcpTools opsMcpTools;

	@Autowired
	private DataSource primaryDataSource;

	@Autowired
	@Qualifier(TargetDataSourceConfig.TARGET_DATA_SOURCE)
	private DataSource targetDataSource;

	@Test
	void contextLoads() {
		assertThat(startInvestigation).isNotNull();
		assertThat(getInvestigation).isNotNull();
		assertThat(investigationRepository).isNotNull();
		assertThat(diagnosisEngine).isNotNull();
	}

	@Test
	void bothInvestigatorsShouldBeRegistered() {
		assertThat(investigators)
				.extracting(InvestigatorPort::source)
				.containsExactlyInAnyOrder(FindingSource.POSTGRES, FindingSource.PROMETHEUS);
	}

	@Test
	void bothToolSurfacesShouldBeAvailable() {
		// The Spring AI agent surface and the MCP surface are separate beans over one toolkit.
		assertThat(opsTools).isNotNull();
		assertThat(opsMcpTools).isNotNull();
	}

	@Test
	void recent_shouldQueryTheFlywayManagedSchema() {
		// Exercises the real schema end to end; fails loudly if migrations did not run.
		assertThat(getInvestigation.recent(5)).isEmpty();
	}

	/**
	 * Guards a subtle wiring failure: declaring the target DataSource makes Boot's
	 * DataSourceAutoConfiguration back off, and without an explicit @Primary the read-only
	 * target becomes the application's only datasource — Flyway then tries to migrate the
	 * database pop is meant to be observing.
	 */
	@Test
	void theTwoDataSourcesShouldBeDistinctAndOnlyTheTargetReadOnly() {
		assertThat(primaryDataSource).isNotSameAs(targetDataSource);
		assertThat(((HikariDataSource) primaryDataSource).isReadOnly())
				.as("pop's own datasource must be writable")
				.isFalse();
		assertThat(((HikariDataSource) targetDataSource).isReadOnly())
				.as("the observed datasource must be read-only")
				.isTrue();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestInfrastructure {

		@Bean
		DynamicPropertyRegistrar testProperties() {
			return registry -> {
				registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
				registry.add("spring.datasource.username", POSTGRES::getUsername);
				registry.add("spring.datasource.password", POSTGRES::getPassword);
				// The "observed" database is the same container here; in production it is a
				// different server reached with a read-only role.
				registry.add("pop.target-datasource.url", POSTGRES::getJdbcUrl);
				registry.add("pop.target-datasource.username", POSTGRES::getUsername);
				registry.add("pop.target-datasource.password", POSTGRES::getPassword);
				registry.add("spring.ai.openai.api-key", () -> "test-key-not-used");
			};
		}
	}
}
