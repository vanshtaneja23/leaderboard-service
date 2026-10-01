package dev.vansh.leaderboard.api;

import dev.vansh.leaderboard.core.RankScore;
import java.util.regex.Pattern;

/**
 * Validates requests at the edge, before anything touches a database. Proto3 has no "required"
 * fields (a missing string is just ""), so every field is checked explicitly.
 */
final class RequestValidator {

    static final int MAX_ID_LENGTH = 64;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    static final int MAX_TOP_N = 100;
    static final int MAX_NEIGHBORS = 50;

    // Ids become part of Redis keys and log lines; keep them to a boring, safe alphabet.
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]+");

    private RequestValidator() {
    }

    static void id(String field, String value) {
        if (value.isEmpty()) {
            throw new InvalidArgumentException(field, "is required");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new InvalidArgumentException(field, "must be at most " + MAX_ID_LENGTH + " characters");
        }
        if (!ID.matcher(value).matches()) {
            throw new InvalidArgumentException(field, "may only contain letters, digits, '_', '.', ':' and '-'");
        }
    }

    static void idempotencyKey(String value) {
        if (value.isEmpty()) {
            throw new InvalidArgumentException("idempotency_key", "is required");
        }
        if (value.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidArgumentException("idempotency_key",
                    "must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }

    static void score(long value) {
        if (value < 0 || value > RankScore.MAX_SCORE) {
            throw new InvalidArgumentException("score", "must be between 0 and " + RankScore.MAX_SCORE);
        }
    }

    static void range(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new InvalidArgumentException(field, "must be between " + min + " and " + max);
        }
    }
}
