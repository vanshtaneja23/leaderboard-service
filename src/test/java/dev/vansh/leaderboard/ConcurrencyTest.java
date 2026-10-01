package dev.vansh.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vansh.leaderboard.proto.v1.GetPlayerRankRequest;
import dev.vansh.leaderboard.proto.v1.GetPlayerRankResponse;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsRequest;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsResponse;
import dev.vansh.leaderboard.proto.v1.GetTopNRequest;
import dev.vansh.leaderboard.proto.v1.RankedEntry;
import dev.vansh.leaderboard.proto.v1.SubmitScoreRequest;
import dev.vansh.leaderboard.proto.v1.SubmitScoreResponse;
import dev.vansh.leaderboard.core.RankScore;
import dev.vansh.leaderboard.ranking.RankingRebuilder;
import dev.vansh.leaderboard.ranking.RankingStore;
import dev.vansh.leaderboard.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fires 1,000 SubmitScore calls at once from 64 threads and checks that every rank and every stat
 * is exactly what a sequential model predicts.
 *
 * <p>The workload is built to hit every race the design has to survive:
 * <ul>
 *   <li>many submissions per player (row-lock contention on leaderboard_entries and player_stats),</li>
 *   <li>a small score range, so equal scores are common both within and across players
 *       (exercises the tie-break in SQL and in Redis),</li>
 *   <li>100 of the 1,000 calls are retries of another call's idempotency key, sent concurrently
 *       with the original (exercises the unique constraint under contention).</li>
 * </ul>
 */
class ConcurrencyTest extends IntegrationTest {

    private static final int TOTAL_CALLS = 1_000;
    private static final int RETRIES = 100;
    private static final int UNIQUE = TOTAL_CALLS - RETRIES;
    private static final int THREADS = 64;
    private static final int PLAYERS = 40;
    private static final int MAX_SCORE_EXCLUSIVE = 200;
    private static final List<String> BOARDS = List.of("alpha", "beta");

    @Autowired
    RankingRebuilder rebuilder;

    /** What a correct implementation must end up with for one (board, player). */
    record Expected(String board, String player, long score, long eventId) {
    }

    @Test
    void thousandConcurrentSubmissionsProduceExactRanksAndStats() throws Exception {
        // --- Build the workload (seeded, so a failure is reproducible) ---------------------------
        Random random = new Random(42);
        List<SubmitScoreRequest> unique = new ArrayList<>();
        for (int i = 0; i < UNIQUE; i++) {
            unique.add(SubmitScoreRequest.newBuilder()
                    .setPlayerId("p" + random.nextInt(PLAYERS))
                    .setLeaderboardId(BOARDS.get(random.nextInt(BOARDS.size())))
                    .setScore(random.nextInt(MAX_SCORE_EXCLUSIVE))
                    .setIdempotencyKey("key-" + i)
                    .build());
        }
        List<SubmitScoreRequest> calls = new ArrayList<>(unique);
        for (int i = 0; i < RETRIES; i++) {
            calls.add(unique.get(random.nextInt(UNIQUE)));  // identical request = client retry
        }
        Collections.shuffle(calls, random);

        // --- Fire them all at once ---------------------------------------------------------------
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<SubmitScoreResponse>> futures = new ArrayList<>();
        for (SubmitScoreRequest call : calls) {
            futures.add(pool.submit(() -> {
                startGun.await();
                return grpc.submitScore(call);
            }));
        }
        startGun.countDown();
        List<SubmitScoreResponse> responses = new ArrayList<>();
        for (Future<SubmitScoreResponse> f : futures) {
            responses.add(f.get(60, TimeUnit.SECONDS));  // any RPC error fails the test here
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // --- Idempotency: exactly one call per key was counted ----------------------------------
        assertThat(responses).filteredOn(SubmitScoreResponse::getDuplicate).hasSize(RETRIES);
        assertThat(jdbc.sql("SELECT count(*) FROM score_events").query(Long.class).single()).isEqualTo(UNIQUE);

        // --- Expected state, computed independently of the service ------------------------------
        // "Earlier submission" is defined by the event id Postgres assigned to each key.
        Map<String, Long> eventIdByKey = new HashMap<>();
        jdbc.sql("SELECT idempotency_key, id FROM score_events")
                .query(rs -> {
                    eventIdByKey.put(rs.getString(1), rs.getLong(2));
                });
        Comparator<Expected> rankOrder = Comparator.comparingLong(Expected::score).reversed()
                .thenComparingLong(Expected::eventId);

        Map<String, Expected> bestByBoardAndPlayer = new HashMap<>();
        Map<String, Long> gamesByPlayer = new HashMap<>();
        Map<String, Long> bestScoreByPlayer = new HashMap<>();
        for (SubmitScoreRequest r : unique) {
            Expected candidate = new Expected(r.getLeaderboardId(), r.getPlayerId(), r.getScore(),
                    eventIdByKey.get(r.getIdempotencyKey()));
            bestByBoardAndPlayer.merge(r.getLeaderboardId() + "/" + r.getPlayerId(), candidate,
                    (a, b) -> rankOrder.compare(a, b) <= 0 ? a : b);
            gamesByPlayer.merge(r.getPlayerId(), 1L, Long::sum);
            bestScoreByPlayer.merge(r.getPlayerId(), r.getScore(), Math::max);
        }

        // --- Ranks: GetTopN and GetPlayerRank match the model exactly ----------------------------
        for (String board : BOARDS) {
            List<Expected> expectedOrder = bestByBoardAndPlayer.values().stream()
                    .filter(e -> e.board().equals(board))
                    .sorted(rankOrder)
                    .toList();
            List<RankedEntry> expectedEntries = new ArrayList<>();
            for (int i = 0; i < expectedOrder.size(); i++) {
                expectedEntries.add(RankedEntry.newBuilder()
                        .setRank(i + 1).setPlayerId(expectedOrder.get(i).player()).setScore(expectedOrder.get(i).score())
                        .build());
            }

            assertThat(topN(board)).as("top-N of %s", board).containsExactlyElementsOf(expectedEntries);

            for (RankedEntry expected : expectedEntries) {
                GetPlayerRankResponse rank = grpc.getPlayerRank(GetPlayerRankRequest.newBuilder()
                        .setLeaderboardId(board).setPlayerId(expected.getPlayerId()).setNeighbors(1).build());
                assertThat(rank.getPlayer()).isEqualTo(expected);
            }

            // Postgres agrees with Redis on which submission ranks each player.
            for (Expected e : expectedOrder) {
                long storedEventId = jdbc.sql("""
                                SELECT best_event_id FROM leaderboard_entries
                                WHERE leaderboard_id = :b AND player_id = :p""")
                        .param("b", e.board()).param("p", e.player())
                        .query(Long.class).single();
                assertThat(storedEventId).isEqualTo(e.eventId());
            }
        }

        // --- Stats: no lost updates, no double counts -------------------------------------------
        for (var entry : gamesByPlayer.entrySet()) {
            GetPlayerStatsResponse stats = grpc.getPlayerStats(
                    GetPlayerStatsRequest.newBuilder().setPlayerId(entry.getKey()).build());
            assertThat(stats.getGamesPlayed()).as("games of %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(stats.getBestScore()).as("best of %s", entry.getKey())
                    .isEqualTo(bestScoreByPlayer.get(entry.getKey()));
        }

        // --- And Redis rebuilt from Postgres alone is identical ----------------------------------
        Map<String, List<RankedEntry>> live = new HashMap<>();
        BOARDS.forEach(b -> live.put(b, topN(b)));
        flushRedis();
        rebuilder.reconcile();
        BOARDS.forEach(b -> assertThat(topN(b)).isEqualTo(live.get(b)));
    }

    /**
     * The hardest tie-break race: one player, one board, one score, many concurrent submissions.
     * Event ids are handed out at INSERT time, but the row lock on leaderboard_entries is taken a
     * moment later, so a submission with a lower id often reaches the lock <em>after</em> one with a
     * higher id. The upsert's WHERE clause must still let the lowest id win, and Postgres and Redis
     * must agree on it. (The 1,000-call test above rarely produces this interleaving on its own.)
     */
    @Test
    void hotRowEqualScoresKeepTheEarliestSubmissionInBothStores() throws Exception {
        int calls = 200;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<SubmitScoreResponse>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            SubmitScoreRequest req = SubmitScoreRequest.newBuilder()
                    .setPlayerId("hot").setLeaderboardId("alpha").setScore(1_000).setIdempotencyKey("hot-" + i)
                    .build();
            futures.add(pool.submit(() -> {
                startGun.await();
                return grpc.submitScore(req);
            }));
        }
        startGun.countDown();
        for (Future<SubmitScoreResponse> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long earliest = jdbc.sql("SELECT min(id) FROM score_events").query(Long.class).single();
        long inPostgres = jdbc.sql("SELECT best_event_id FROM leaderboard_entries WHERE player_id = 'hot'")
                .query(Long.class).single();
        Double composite = redis.opsForZSet().score(RankingStore.key("alpha"), "hot");

        assertThat(inPostgres).isEqualTo(earliest);
        assertThat(RankScore.decodeEventId(composite)).isEqualTo(earliest);
        assertThat(grpc.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("hot").build())
                .getGamesPlayed()).isEqualTo(calls);
    }

    private List<RankedEntry> topN(String board) {
        return grpc.getTopN(GetTopNRequest.newBuilder().setLeaderboardId(board).setN(100).build()).getEntriesList();
    }
}
