package dev.vansh.leaderboard.core;

/**
 * Result of the Postgres transaction for one submission.
 *
 * @param entry     the player's ranking entry after the transaction; this is what gets written to Redis.
 * @param improved  this submission became the player's ranking entry.
 * @param duplicate the idempotency key had already been processed; nothing was counted.
 */
public record SubmitOutcome(RankingEntry entry, boolean improved, boolean duplicate) {
}
