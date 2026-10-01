package dev.vansh.leaderboard.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vansh.leaderboard.core.IdempotencyConflictException;
import dev.vansh.leaderboard.core.PlayerStats;
import dev.vansh.leaderboard.core.RankingEntry;
import dev.vansh.leaderboard.core.ScoreSubmission;
import dev.vansh.leaderboard.core.SubmitOutcome;
import dev.vansh.leaderboard.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ScoreStoreTest extends IntegrationTest {

    @Autowired
    ScoreStore store;

    @Test
    void firstSubmissionCreatesEventEntryAndStats() {
        SubmitOutcome out = store.submit(new ScoreSubmission("alice", "weekly", 100, "k1"));

        assertThat(out.improved()).isTrue();
        assertThat(out.duplicate()).isFalse();
        assertThat(out.entry()).isEqualTo(new RankingEntry("weekly", "alice", 100, 1));
        PlayerStats stats = store.findPlayerStats("alice").orElseThrow();
        assertThat(stats.gamesPlayed()).isEqualTo(1);
        assertThat(stats.bestScore()).isEqualTo(100);
    }

    @Test
    void onlyHigherScoresMoveTheEntryButEveryGameCounts() {
        store.submit(new ScoreSubmission("alice", "weekly", 100, "k1"));
        SubmitOutcome lower = store.submit(new ScoreSubmission("alice", "weekly", 50, "k2"));
        SubmitOutcome higher = store.submit(new ScoreSubmission("alice", "weekly", 300, "k3"));

        assertThat(lower.improved()).isFalse();
        assertThat(lower.entry().score()).isEqualTo(100);
        assertThat(higher.improved()).isTrue();
        assertThat(higher.entry()).isEqualTo(new RankingEntry("weekly", "alice", 300, 3));
        assertThat(store.findPlayerStats("alice").orElseThrow().gamesPlayed()).isEqualTo(3);
    }

    @Test
    void equalScoreLaterDoesNotReplaceEarlierSubmission() {
        store.submit(new ScoreSubmission("alice", "weekly", 100, "k1"));
        SubmitOutcome tie = store.submit(new ScoreSubmission("alice", "weekly", 100, "k2"));

        assertThat(tie.improved()).isFalse();
        assertThat(tie.entry().eventId()).as("keeps the earlier submission's tie-breaker").isEqualTo(1);
    }

    @Test
    void retryWithSameKeyIsNotCountedTwice() {
        var submission = new ScoreSubmission("alice", "weekly", 100, "k1");
        store.submit(submission);
        SubmitOutcome retry = store.submit(submission);

        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.improved()).isFalse();
        assertThat(retry.entry()).isEqualTo(new RankingEntry("weekly", "alice", 100, 1));
        assertThat(store.findPlayerStats("alice").orElseThrow().gamesPlayed()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM score_events").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reusingKeyForDifferentSubmissionIsRejected() {
        store.submit(new ScoreSubmission("alice", "weekly", 100, "k1"));

        assertThatThrownBy(() -> store.submit(new ScoreSubmission("alice", "weekly", 999, "k1")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(store.findPlayerStats("alice").orElseThrow().bestScore()).isEqualTo(100);
    }

    @Test
    void statsAggregateAcrossLeaderboards() {
        store.submit(new ScoreSubmission("alice", "weekly", 100, "k1"));
        store.submit(new ScoreSubmission("alice", "all-time", 700, "k2"));
        store.submit(new ScoreSubmission("alice", "weekly", 200, "k3"));

        PlayerStats stats = store.findPlayerStats("alice").orElseThrow();
        assertThat(stats.gamesPlayed()).isEqualTo(3);
        assertThat(stats.bestScore()).isEqualTo(700);
        assertThat(store.findEntry("weekly", "alice").orElseThrow().score()).isEqualTo(200);
        assertThat(store.findLeaderboardIds()).containsExactlyInAnyOrder("weekly", "all-time");
    }

    @Test
    void unknownPlayerHasNoStats() {
        assertThat(store.findPlayerStats("nobody")).isEmpty();
    }

    @Test
    void forEachEntryStreamsAllEntriesOfOneLeaderboard() {
        store.submit(new ScoreSubmission("alice", "weekly", 100, "k1"));
        store.submit(new ScoreSubmission("bob", "weekly", 200, "k2"));
        store.submit(new ScoreSubmission("carol", "monthly", 300, "k3"));

        List<RankingEntry> seen = new ArrayList<>();
        store.forEachEntry("weekly", seen::add);

        assertThat(seen).extracting(RankingEntry::playerId).containsExactlyInAnyOrder("alice", "bob");
    }
}
