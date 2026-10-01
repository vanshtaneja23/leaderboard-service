package dev.vansh.leaderboard.core;

import static dev.vansh.leaderboard.core.RankScore.MAX_EVENT_ID;
import static dev.vansh.leaderboard.core.RankScore.MAX_SCORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RankScoreTest {

    @Test
    void limitsMatchTheDatabaseConstraints() {
        // V2__rank_encoding_limits.sql hard-codes these; keep them in sync.
        assertThat(MAX_SCORE).isEqualTo(67_108_863L);
        assertThat(MAX_EVENT_ID).isEqualTo(134_217_727L);
    }

    @Test
    void higherScoreAlwaysWinsRegardlessOfEventId() {
        assertThat(RankScore.encode(101, MAX_EVENT_ID)).isGreaterThan(RankScore.encode(100, 1));
    }

    @Test
    void equalScoreEarlierEventWins() {
        assertThat(RankScore.encode(100, 5)).isGreaterThan(RankScore.encode(100, 6));
    }

    @Test
    void roundTripsExactlyAtTheExtremes() {
        long[][] cases = {{0, 1}, {0, MAX_EVENT_ID}, {MAX_SCORE, 1}, {MAX_SCORE, MAX_EVENT_ID}, {12_345, 678_910}};
        for (long[] c : cases) {
            double composite = RankScore.encode(c[0], c[1]);
            assertThat(composite).as("fits in 53 bits, so the double is exact").isLessThan(0x1p53);
            assertThat(RankScore.decodeScore(composite)).isEqualTo(c[0]);
            assertThat(RankScore.decodeEventId(composite)).isEqualTo(c[1]);
        }
    }

    @Test
    void adjacentCompositesStayDistinctAtTheTopOfTheRange() {
        // The hardest case for double precision: the two largest composites must not collapse.
        assertThat(RankScore.encode(MAX_SCORE, 1)).isNotEqualTo(RankScore.encode(MAX_SCORE, 2));
    }

    @Test
    void rejectsValuesThatCannotBeEncoded() {
        assertThatThrownBy(() -> RankScore.encode(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RankScore.encode(MAX_SCORE + 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RankScore.encode(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RankScore.encode(1, MAX_EVENT_ID + 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
