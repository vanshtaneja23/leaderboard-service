package dev.vansh.leaderboard.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vansh.leaderboard.core.RankWindow;
import dev.vansh.leaderboard.core.RankedPlayer;
import dev.vansh.leaderboard.core.RankingEntry;
import dev.vansh.leaderboard.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RankingStoreTest extends IntegrationTest {

    private static final String BOARD = "weekly";

    @Autowired
    RankingStore ranking;

    private static RankingEntry entry(String player, long score, long eventId) {
        return new RankingEntry(BOARD, player, score, eventId);
    }

    @Test
    void ranksByScoreDescending() {
        ranking.add(entry("low", 10, 1));
        ranking.add(entry("high", 30, 2));
        ranking.add(entry("mid", 20, 3));

        assertThat(ranking.topN(BOARD, 10)).containsExactly(
                new RankedPlayer(1, "high", 30),
                new RankedPlayer(2, "mid", 20),
                new RankedPlayer(3, "low", 10));
    }

    @Test
    void equalScoresRankEarlierSubmissionFirst() {
        // Inserted in "wrong" order on purpose: the later event arrives in Redis first.
        ranking.add(entry("later", 100, 9));
        ranking.add(entry("earlier", 100, 4));

        assertThat(ranking.topN(BOARD, 2)).extracting(RankedPlayer::playerId).containsExactly("earlier", "later");
    }

    @Test
    void zaddGtIgnoresWorseEntries() {
        ranking.add(entry("alice", 100, 1));
        ranking.add(entry("alice", 50, 2));   // lower score
        ranking.add(entry("alice", 100, 3));  // equal score, later submission

        assertThat(ranking.topN(BOARD, 1)).containsExactly(new RankedPlayer(1, "alice", 100));
        assertThat(ranking.size(BOARD)).isEqualTo(1);
    }

    @Test
    void writesConvergeRegardlessOfArrivalOrder() {
        ranking.add(entry("alice", 300, 3));
        ranking.add(entry("alice", 100, 1));
        ranking.add(entry("alice", 200, 2));

        assertThat(ranking.topN(BOARD, 1)).containsExactly(new RankedPlayer(1, "alice", 300));
    }

    @Test
    void topNReturnsFewerWhenBoardIsSmall() {
        ranking.add(entry("alice", 1, 1));
        assertThat(ranking.topN(BOARD, 100)).hasSize(1);
        assertThat(ranking.topN("no-such-board", 5)).isEmpty();
    }

    @Test
    void rankWindowInTheMiddle() {
        seed(10);  // p1 (score 100) ... p10 (score 10)

        RankWindow w = ranking.rankWindow(BOARD, "p5", 2).orElseThrow();

        assertThat(w.player()).isEqualTo(new RankedPlayer(5, "p5", 60));
        assertThat(w.above()).extracting(RankedPlayer::playerId).containsExactly("p3", "p4");
        assertThat(w.below()).extracting(RankedPlayer::playerId).containsExactly("p6", "p7");
        assertThat(w.below().get(0).rank()).isEqualTo(6);
    }

    @Test
    void rankWindowClipsAtTheEdges() {
        seed(5);

        RankWindow top = ranking.rankWindow(BOARD, "p1", 3).orElseThrow();
        assertThat(top.player().rank()).isEqualTo(1);
        assertThat(top.above()).isEmpty();
        assertThat(top.below()).extracting(RankedPlayer::playerId).containsExactly("p2", "p3", "p4");

        RankWindow bottom = ranking.rankWindow(BOARD, "p5", 3).orElseThrow();
        assertThat(bottom.player().rank()).isEqualTo(5);
        assertThat(bottom.above()).extracting(RankedPlayer::playerId).containsExactly("p2", "p3", "p4");
        assertThat(bottom.below()).isEmpty();
    }

    @Test
    void rankWindowWithZeroNeighbors() {
        seed(3);
        RankWindow w = ranking.rankWindow(BOARD, "p2", 0).orElseThrow();
        assertThat(w.player()).isEqualTo(new RankedPlayer(2, "p2", 90));
        assertThat(w.above()).isEmpty();
        assertThat(w.below()).isEmpty();
    }

    @Test
    void rankWindowForUnknownPlayerIsEmpty() {
        seed(3);
        assertThat(ranking.rankWindow(BOARD, "ghost", 2)).isEmpty();
    }

    @Test
    void addAllUsesGtSemanticsToo() {
        ranking.add(entry("alice", 500, 1));
        ranking.addAll(BOARD, List.of(entry("alice", 100, 2), entry("bob", 200, 3)));

        assertThat(ranking.topN(BOARD, 10)).containsExactly(
                new RankedPlayer(1, "alice", 500),
                new RankedPlayer(2, "bob", 200));
    }

    private void seed(int players) {
        for (int i = 1; i <= players; i++) {
            ranking.add(entry("p" + i, 110 - 10L * i, i));
        }
    }
}
