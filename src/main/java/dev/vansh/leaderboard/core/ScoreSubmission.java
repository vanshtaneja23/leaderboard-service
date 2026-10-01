package dev.vansh.leaderboard.core;

/** One game result as sent by a client. Validated before it reaches this type. */
public record ScoreSubmission(String playerId, String leaderboardId, long score, String idempotencyKey) {
}
