package ai.utkarsh.pop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * AI Production Operations Platform.
 *
 * <p>Answers operational questions by giving an LLM read-only tools over real telemetry —
 * Postgres statistics and Prometheus metrics — and letting it decide what evidence to gather.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableResilientMethods
@ImportHttpServices(group = "prometheus",
		basePackages = "ai.utkarsh.pop.infrastructure.investigator.prometheus")
public class PopApplication {

	public static void main(String[] args) {
		SpringApplication.run(PopApplication.class, args);
	}

}
