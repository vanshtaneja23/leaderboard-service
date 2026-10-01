package dev.vansh.leaderboard.core;

import java.util.List;

/** A player plus their neighbors; both lists are ordered best-first. */
public record RankWindow(RankedPlayer player, List<RankedPlayer> above, List<RankedPlayer> below) {
}
