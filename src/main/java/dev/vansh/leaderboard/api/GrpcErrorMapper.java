package dev.vansh.leaderboard.api;

import dev.vansh.leaderboard.core.IdempotencyConflictException;
import dev.vansh.leaderboard.core.NotFoundException;
import io.grpc.Status;
import io.grpc.StatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * The single place where exceptions become gRPC status codes. Spring gRPC's exception interceptor
 * calls this for anything a handler throws, so handlers contain no try/catch.
 */
@Component
public class GrpcErrorMapper implements GrpcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GrpcErrorMapper.class);

    @Override
    public StatusException handleException(Throwable e) {
        return toStatus(e).asException();
    }

    Status toStatus(Throwable e) {
        if (e instanceof InvalidArgumentException) {
            return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
        }
        if (e instanceof NotFoundException) {
            return Status.NOT_FOUND.withDescription(e.getMessage());
        }
        if (e instanceof IdempotencyConflictException) {
            return Status.ALREADY_EXISTS.withDescription(e.getMessage());
        }
        if (e instanceof DataAccessResourceFailureException || e instanceof TransientDataAccessException) {
            // Postgres or Redis unreachable / timed out. Retrying is safe: SubmitScore is idempotent.
            log.warn("dependency unavailable", e);
            return Status.UNAVAILABLE.withDescription("a backing store is temporarily unavailable; retry");
        }
        // Anything else is a bug. Log it with the stack trace, but never leak internals to clients.
        log.error("unhandled error", e);
        return Status.INTERNAL.withDescription("internal error");
    }
}
