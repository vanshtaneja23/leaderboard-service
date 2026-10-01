package dev.vansh.leaderboard.core;

import dev.vansh.leaderboard.ranking.RankingStore;
import dev.vansh.leaderboard.store.ScoreStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the two stores. Postgres is the source of truth and is always written first; Redis
 * is a derived index that is updated after the Postgres transaction commits.
 */
@Service
public class LeaderboardService {

    private final ScoreStore store;
    private final RankingStore ranking;
    private final Counter improved;
    private final Counter notImproved;
    private final Counter duplicate;

    public LeaderboardService(ScoreStore store, RankingStore ranking, MeterRegistry meters) {
        this.store = store;
        this.ranking = ranking;
        // Business-level counter, complementing the per-RPC metrics: e.g. a spike in "duplicate"
        // means clients are retrying a lot, which usually means something upstream is timing out.
        this.improved = submissions(meters, "improved");
        this.notImproved = submissions(meters, "not_improved");
        this.duplicate = submissions(meters, "duplicate");
    }

    private static Counter submissions(MeterRegistry meters, String result) {
        return Counter.builder("leaderboard.submissions")
                .description("Accepted SubmitScore calls by outcome")
                .tag("result", result)
                .register(meters);
    }

    /**
     * 1. Commit the event + stats in Postgres (one transaction).
     * 2. ZADD GT the player's ranking entry into Redis.
     *
     * <p>If step 2 fails (Redis down), the exception surfaces as UNAVAILABLE and the client retries
     * with the same idempotency key. The retry is a duplicate in Postgres (nothing double-counted)
     * but still re-applies step 2, which repairs Redis. Writing the entry is safe to repeat because
     * ZADD GT keeps the max. Redis is never written for a transaction that did not commit.
     */
    public SubmitOutcome submit(ScoreSubmission submission) {
        SubmitOutcome outcome = store.submit(submission);
        ranking.add(outcome.entry());
        (outcome.duplicate() ? duplicate : outcome.improved() ? improved : notImproved).increment();
        return outcome;
    }

    public List<RankedPlayer> topN(String leaderboardId, int n) {
        List<RankedPlayer> top = ranking.topN(leaderboardId, n);
        if (top.isEmpty()) {
            // Leaderboards are created implicitly by their first score, so "empty" == "unknown".
            throw new NotFoundException("leaderboard '" + leaderboardId + "' has no entries");
        }
        return top;
    }

    public RankWindow playerRank(String leaderboardId, String playerId, int neighbors) {
        return ranking.rankWindow(leaderboardId, playerId, neighbors)
                .orElseThrow(() -> new NotFoundException(
                        "player '" + playerId + "' has no score on leaderboard '" + leaderboardId + "'"));
    }

    public PlayerStats playerStats(String playerId) {
        return store.findPlayerStats(playerId)
                .orElseThrow(() -> new NotFoundException("player '" + playerId + "' has no stats"));
    }
}
