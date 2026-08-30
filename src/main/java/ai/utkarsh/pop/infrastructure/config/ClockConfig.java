package ai.utkarsh.pop.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injected {@link Clock} rather than scattered {@code Instant.now()} calls, so time
 * is controllable in tests and every timestamp in one investigation comes from the same source.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
