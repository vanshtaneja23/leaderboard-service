package dev.vansh.leaderboard.core;

/** A player's position on a leaderboard. {@code rank} is 1-based. */
public record RankedPlayer(long rank, String playerId, long score) {
}
