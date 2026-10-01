package dev.vansh.leaderboard.core;

/** An idempotency key was reused for a different submission. Almost always a client bug. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency_key '" + idempotencyKey + "' was already used for a different submission");
    }
}
