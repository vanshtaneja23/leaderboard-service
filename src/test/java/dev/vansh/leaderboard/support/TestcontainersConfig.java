package dev.vansh.leaderboard.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres for tests, started once per JVM. {@code @ServiceConnection} points Spring's
 * datasource at the container, so no connection properties are needed in test code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }
}
