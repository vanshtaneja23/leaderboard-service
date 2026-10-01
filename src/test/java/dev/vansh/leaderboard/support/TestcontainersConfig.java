package dev.vansh.leaderboard.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres and Redis for tests, started once per JVM. {@code @ServiceConnection} points Spring's
 * datasource and Redis client at the containers, so no connection properties are needed in test code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    public static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine")).withExposedPorts(6379);

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return REDIS;
    }
}
