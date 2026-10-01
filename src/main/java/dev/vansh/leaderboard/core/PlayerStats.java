package dev.vansh.leaderboard.core;

import java.time.Instant;

public record PlayerStats(String playerId, long gamesPlayed, long bestScore, Instant lastUpdated) {
}
