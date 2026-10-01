package dev.vansh.leaderboard.core;

/**
 * Packs (score, event id) into the single double that a Redis sorted set ranks by.
 *
 * <p>Requirement: higher score ranks first; on equal scores, the earlier submission ranks first.
 * Redis compares only one number per member, so both keys must be folded into it:
 *
 * <pre>
 *   bit 52 ........... 27 | 26 ............... 0
 *   [      score         ] [ MAX_EVENT_ID - id  ]
 * </pre>
 *
 * <ul>
 *   <li>The score occupies the high bits, so any score difference outweighs any tie-breaker.</li>
 *   <li>The low bits hold the event id <em>inverted</em>: an earlier submission has a lower id, so
 *       a larger low part, so it ranks higher among equal scores.</li>
 *   <li>A double represents every integer up to 2^53 exactly, and the composite never exceeds that,
 *       so the encoding is lossless and comparisons between composites are exact.</li>
 * </ul>
 *
 * <p>Trade-off: 26 bits for scores (max 67,108,863) and 27 bits for event ids (max 134,217,727).
 * Postgres enforces the same limits (V2 migration), so exhausting either fails loudly.
 */
public final class RankScore {

    public static final int EVENT_ID_BITS = 27;
    public static final long MAX_EVENT_ID = (1L << EVENT_ID_BITS) - 1;
    public static final long MAX_SCORE = (1L << (53 - EVENT_ID_BITS)) - 1;

    private RankScore() {
    }

    public static double encode(long score, long eventId) {
        if (score < 0 || score > MAX_SCORE) {
            throw new IllegalArgumentException("score out of range [0, " + MAX_SCORE + "]: " + score);
        }
        if (eventId < 1 || eventId > MAX_EVENT_ID) {
            throw new IllegalArgumentException("event id out of range [1, " + MAX_EVENT_ID + "]: " + eventId);
        }
        long composite = (score << EVENT_ID_BITS) | (MAX_EVENT_ID - eventId);
        return (double) composite;
    }

    public static long decodeScore(double composite) {
        return ((long) composite) >>> EVENT_ID_BITS;
    }

    public static long decodeEventId(double composite) {
        return MAX_EVENT_ID - (((long) composite) & MAX_EVENT_ID);
    }
}
