package dev.vansh.leaderboard.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vansh.leaderboard.core.IdempotencyConflictException;
import dev.vansh.leaderboard.core.NotFoundException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class GrpcErrorMapperTest {

    private final GrpcErrorMapper mapper = new GrpcErrorMapper();

    private Status.Code codeFor(Throwable t) {
        return mapper.toStatus(t).getCode();
    }

    @Test
    void domainErrors() {
        assertThat(codeFor(new InvalidArgumentException("n", "bad"))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(codeFor(new NotFoundException("x"))).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(codeFor(new IdempotencyConflictException("k"))).isEqualTo(Status.Code.ALREADY_EXISTS);
    }

    @Test
    void storeOutagesAreRetryable() {
        assertThat(codeFor(new RedisConnectionFailureException("down"))).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(codeFor(new CannotGetJdbcConnectionException("down"))).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(codeFor(new QueryTimeoutException("slow"))).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void bugsBecomeInternalWithoutLeakingDetails() {
        Status status = mapper.toStatus(new IllegalStateException("secret table name"));
        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).doesNotContain("secret");
    }
}
