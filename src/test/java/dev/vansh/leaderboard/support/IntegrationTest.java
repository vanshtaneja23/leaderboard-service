package dev.vansh.leaderboard.support;

import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc;
import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc.LeaderboardServiceBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private static ManagedChannel channel;

    /** A real gRPC client talking to the real server over the in-process transport. */
    protected LeaderboardServiceBlockingStub grpc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeAll
    static void openChannel() {
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).build();
    }

    @AfterAll
    static void closeChannel() {
        channel.shutdownNow();
    }

    @BeforeEach
    void resetState() {
        grpc = LeaderboardServiceGrpc.newBlockingStub(channel);
        jdbc.sql("TRUNCATE leaderboard_entries, player_stats, score_events RESTART IDENTITY CASCADE").update();
        flushRedis();
    }

    protected void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
