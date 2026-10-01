package dev.vansh.leaderboard.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base class for tests that run the full application against real containers. All subclasses share
 * one Spring context (same configuration), so containers and the app start once per test run.
 */
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "spring.grpc.server.inprocess.name=" + IntegrationTest.IN_PROCESS_NAME
})
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    public static final String IN_PROCESS_NAME = "leaderboard-test";

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("TRUNCATE leaderboard_entries, player_stats, score_events RESTART IDENTITY CASCADE").update();
    }
}
