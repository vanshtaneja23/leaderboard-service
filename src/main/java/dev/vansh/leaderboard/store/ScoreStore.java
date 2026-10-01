package dev.vansh.leaderboard.store;

import dev.vansh.leaderboard.core.IdempotencyConflictException;
import dev.vansh.leaderboard.core.PlayerStats;
import dev.vansh.leaderboard.core.RankingEntry;
import dev.vansh.leaderboard.core.ScoreSubmission;
import dev.vansh.leaderboard.core.SubmitOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres access. Plain SQL through {@link JdbcClient} so every statement the service runs is
 * visible here; the concurrency guarantees come from the SQL itself, not from Java locks.
 */
@Repository
public class ScoreStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public ScoreStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Records a submission and updates the derived rows, all in one transaction. Either the event,
     * the ranking entry and the player stats all change, or none of them do.
     */
    public SubmitOutcome submit(ScoreSubmission s) {
        return tx.execute(status -> {
            // 1. Claim the idempotency key. ON CONFLICT DO NOTHING means a concurrent request with
            //    the same key blocks on the unique index until we commit, then inserts nothing.
            Optional<Long> eventId = jdbc.sql("""
                            INSERT INTO score_events (idempotency_key, player_id, leaderboard_id, score)
                            VALUES (:key, :player, :board, :score)
                            ON CONFLICT (idempotency_key) DO NOTHING
                            RETURNING id
                            """)
                    .param("key", s.idempotencyKey())
                    .param("player", s.playerId())
                    .param("board", s.leaderboardId())
                    .param("score", s.score())
                    .query(Long.class)
                    .optional();

            if (eventId.isEmpty()) {
                return replay(s);
            }

            // 2. Move the ranking entry only if this submission beats it. "Beats" is the same total
            //    order Redis uses: higher score first, then lower event id (earlier submission).
            //    The WHERE clause runs while holding the row lock, so concurrent submissions for the
            //    same player serialize here and the best one always wins.
            boolean improved = jdbc.sql("""
                            INSERT INTO leaderboard_entries (leaderboard_id, player_id, best_score, best_event_id, updated_at)
                            VALUES (:board, :player, :score, :eventId, now())
                            ON CONFLICT (leaderboard_id, player_id) DO UPDATE
                              SET best_score = EXCLUDED.best_score,
                                  best_event_id = EXCLUDED.best_event_id,
                                  updated_at = EXCLUDED.updated_at
                              WHERE EXCLUDED.best_score > leaderboard_entries.best_score
                                 OR (EXCLUDED.best_score = leaderboard_entries.best_score
                                     AND EXCLUDED.best_event_id < leaderboard_entries.best_event_id)
                            RETURNING 1
                            """)
                    .param("board", s.leaderboardId())
                    .param("player", s.playerId())
                    .param("score", s.score())
                    .param("eventId", eventId.get())
                    .query(Integer.class)
                    .optional()
                    .isPresent();

            // 3. Lifetime stats. Increments happen in SQL (games_played + 1), never read-modify-write
            //    in Java, so concurrent submissions cannot lose updates.
            jdbc.sql("""
                            INSERT INTO player_stats (player_id, games_played, best_score, last_updated)
                            VALUES (:player, 1, :score, now())
                            ON CONFLICT (player_id) DO UPDATE
                              SET games_played = player_stats.games_played + 1,
                                  best_score = GREATEST(player_stats.best_score, EXCLUDED.best_score),
                                  last_updated = GREATEST(player_stats.last_updated, EXCLUDED.last_updated)
                            """)
                    .param("player", s.playerId())
                    .param("score", s.score())
                    .update();

            return new SubmitOutcome(findEntry(s.leaderboardId(), s.playerId()).orElseThrow(), improved, false);
        });
    }

    /** The key was seen before: verify it is the same submission and return current state. */
    private SubmitOutcome replay(ScoreSubmission s) {
        ScoreSubmission original = jdbc.sql("""
                        SELECT player_id, leaderboard_id, score, idempotency_key
                        FROM score_events WHERE idempotency_key = :key
                        """)
                .param("key", s.idempotencyKey())
                .query((rs, i) -> new ScoreSubmission(
                        rs.getString("player_id"), rs.getString("leaderboard_id"),
                        rs.getLong("score"), rs.getString("idempotency_key")))
                .single();
        if (!original.equals(s)) {
            throw new IdempotencyConflictException(s.idempotencyKey());
        }
        // improved=false: this call changed nothing. The entry is still returned so the caller can
        // re-apply it to Redis, which heals the cache if the original attempt died after commit.
        return new SubmitOutcome(findEntry(s.leaderboardId(), s.playerId()).orElseThrow(), false, true);
    }

    public Optional<RankingEntry> findEntry(String leaderboardId, String playerId) {
        return jdbc.sql("""
                        SELECT leaderboard_id, player_id, best_score, best_event_id
                        FROM leaderboard_entries
                        WHERE leaderboard_id = :board AND player_id = :player
                        """)
                .param("board", leaderboardId)
                .param("player", playerId)
                .query(ScoreStore::mapEntry)
                .optional();
    }

    public Optional<PlayerStats> findPlayerStats(String playerId) {
        return jdbc.sql("""
                        SELECT player_id, games_played, best_score, last_updated
                        FROM player_stats WHERE player_id = :player
                        """)
                .param("player", playerId)
                .query((rs, i) -> new PlayerStats(
                        rs.getString("player_id"),
                        rs.getLong("games_played"),
                        rs.getLong("best_score"),
                        rs.getTimestamp("last_updated").toInstant()))
                .optional();
    }

    public List<String> findLeaderboardIds() {
        return jdbc.sql("SELECT DISTINCT leaderboard_id FROM leaderboard_entries")
                .query(String.class)
                .list();
    }

    /**
     * Streams every ranking entry of one leaderboard to {@code sink} without loading the whole
     * table into memory (the Postgres driver only streams inside a transaction with a fetch size).
     */
    public void forEachEntry(String leaderboardId, Consumer<RankingEntry> sink) {
        tx.executeWithoutResult(status -> {
            try (var rows = jdbc.sql("""
                            SELECT leaderboard_id, player_id, best_score, best_event_id
                            FROM leaderboard_entries WHERE leaderboard_id = :board
                            """)
                    .param("board", leaderboardId)
                    .withFetchSize(1_000)
                    .query(ScoreStore::mapEntry)
                    .stream()) {
                rows.forEach(sink);
            }
        });
    }

    private static RankingEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new RankingEntry(
                rs.getString("leaderboard_id"),
                rs.getString("player_id"),
                rs.getLong("best_score"),
                rs.getLong("best_event_id"));
    }
}
