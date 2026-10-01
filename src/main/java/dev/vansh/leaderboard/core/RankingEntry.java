package dev.vansh.leaderboard.core;

/**
 * The submission that currently ranks a player on a leaderboard: a row of
 * {@code leaderboard_entries}, and exactly what the Redis sorted set mirrors.
 *
 * @param eventId id of the winning {@code score_events} row; lower means submitted earlier.
 */
public record RankingEntry(String leaderboardId, String playerId, long score, long eventId) {
}
