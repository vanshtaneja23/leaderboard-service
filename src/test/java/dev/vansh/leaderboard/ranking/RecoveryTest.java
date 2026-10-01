package dev.vansh.leaderboard.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.vansh.leaderboard.LeaderboardApplication;
import dev.vansh.leaderboard.core.RankedPlayer;
import dev.vansh.leaderboard.core.RankingEntry;
import dev.vansh.leaderboard.proto.v1.SubmitScoreRequest;
import dev.vansh.leaderboard.support.IntegrationTest;
import dev.vansh.leaderboard.support.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Redis is a cache of Postgres: wiping it must never lose a ranking. */
class RecoveryTest extends IntegrationTest {

    @Autowired
    RankingStore ranking;

    @Autowired
    RankingRebuilder rebuilder;

    private void submit(String player, String board, long score, String key) {
        grpc.submitScore(SubmitScoreRequest.newBuilder()
                .setPlayerId(player).setLeaderboardId(board).setScore(score).setIdempotencyKey(key).build());
    }

    private void seed() {
        submit("alice", "weekly", 300, "k1");
        submit("bob", "weekly", 500, "k2");
        submit("carol", "weekly", 300, "k3");  // tie with alice, later -> must stay below alice
        submit("alice", "weekly", 100, "k4");
        submit("dave", "monthly", 42, "k5");
    }

    @Test
    void freshApplicationStartupRebuildsAnEmptyRedis() {
        seed();
        List<RankedPlayer> weeklyBefore = ranking.topN("weekly", 10);
        List<RankedPlayer> monthlyBefore = ranking.topN("monthly", 10);

        flushRedis();  // Redis restarted without persistence
        assertThat(ranking.exists("weekly")).isFalse();

        // Boot a second, completely separate instance of the service against the same Postgres and
        // Redis, exactly like a pod restarting. Its startup runner must rebuild before it's "ready".
        // Passed as command-line args: those outrank application.yaml (builder "properties" don't).
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(LeaderboardApplication.class)
                .run(
                        "--spring.datasource.url=" + TestcontainersConfig.POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + TestcontainersConfig.POSTGRES.getUsername(),
                        "--spring.datasource.password=" + TestcontainersConfig.POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + TestcontainersConfig.REDIS.getHost(),
                        "--spring.data.redis.port=" + TestcontainersConfig.REDIS.getMappedPort(6379),
                        "--spring.grpc.server.port=0",
                        "--leaderboard.reconcile.interval=1h")) {
            assertThat(restarted.isRunning()).isTrue();
        }

        assertThat(ranking.topN("weekly", 10)).isEqualTo(weeklyBefore);
        assertThat(ranking.topN("monthly", 10)).isEqualTo(monthlyBefore);
        assertThat(weeklyBefore).extracting(RankedPlayer::playerId).containsExactly("bob", "alice", "carol");
    }

    @Test
    void reconcileFillsInAPartiallyRepopulatedRedis() {
        seed();
        List<RankedPlayer> before = ranking.topN("weekly", 10);

        // Redis restarted, then one live write landed before any rebuild: the key exists but is
        // incomplete. A naive "does the key exist?" check would wrongly skip this leaderboard.
        flushRedis();
        ranking.add(new RankingEntry("weekly", "carol", 300, 3));
        assertThat(ranking.size("weekly")).isEqualTo(1);

        int rebuilt = rebuilder.reconcile();

        assertThat(rebuilt).isEqualTo(2);  // weekly (partial) and monthly (missing)
        assertThat(ranking.topN("weekly", 10)).isEqualTo(before);
    }

    @Test
    void reconcileLeavesHealthyLeaderboardsAlone() {
        seed();
        assertThat(rebuilder.reconcile()).isZero();
    }

    @Test
    void liveWritesBeforeTheRebuildAreMergedNotClobbered() {
        seed();
        flushRedis();
        // A live improvement lands on the fresh Redis before the rebuild runs. The rebuild must
        // merge into the existing key (ZADD GT), not delete-and-replace it.
        submit("carol", "weekly", 900, "k6");

        rebuilder.reconcile();

        assertThat(ranking.topN("weekly", 10))
                .extracting(RankedPlayer::playerId, RankedPlayer::score)
                .containsExactly(tuple("carol", 900L), tuple("bob", 500L), tuple("alice", 300L));
    }
}
