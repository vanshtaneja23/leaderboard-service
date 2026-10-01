package dev.vansh.leaderboard.core;

import dev.vansh.leaderboard.ranking.RankingStore;
import dev.vansh.leaderboard.store.ScoreStore;
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

    public LeaderboardService(ScoreStore store, RankingStore ranking) {
        this.store = store;
        this.ranking = ranking;
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
