package dev.vansh.leaderboard.api;

/** A request field failed validation. Maps to INVALID_ARGUMENT. */
public class InvalidArgumentException extends RuntimeException {

    public InvalidArgumentException(String field, String problem) {
        super(field + ": " + problem);
    }
}
