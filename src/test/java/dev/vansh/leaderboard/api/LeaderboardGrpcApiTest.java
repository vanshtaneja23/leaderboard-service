package dev.vansh.leaderboard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vansh.leaderboard.proto.v1.GetPlayerRankRequest;
import dev.vansh.leaderboard.proto.v1.GetPlayerRankResponse;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsRequest;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsResponse;
import dev.vansh.leaderboard.proto.v1.GetTopNRequest;
import dev.vansh.leaderboard.proto.v1.RankedEntry;
import dev.vansh.leaderboard.proto.v1.SubmitScoreRequest;
import dev.vansh.leaderboard.proto.v1.SubmitScoreResponse;
import dev.vansh.leaderboard.support.IntegrationTest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** End-to-end through a real gRPC client: proto -> validation -> Postgres + Redis -> proto. */
class LeaderboardGrpcApiTest extends IntegrationTest {

    private SubmitScoreResponse submit(String player, String board, long score, String key) {
        return grpc.submitScore(SubmitScoreRequest.newBuilder()
                .setPlayerId(player).setLeaderboardId(board).setScore(score).setIdempotencyKey(key).build());
    }

    private static RankedEntry ranked(long rank, String player, long score) {
        return RankedEntry.newBuilder().setRank(rank).setPlayerId(player).setScore(score).build();
    }

    @Test
    void submitThenReadEverything() {
        submit("alice", "weekly", 300, "k1");
        submit("bob", "weekly", 500, "k2");
        submit("carol", "weekly", 300, "k3");   // ties alice, but later
        submit("alice", "weekly", 100, "k4");   // worse: counted as a game, not ranked

        var top = grpc.getTopN(GetTopNRequest.newBuilder().setLeaderboardId("weekly").setN(10).build());
        assertThat(top.getEntriesList()).containsExactly(
                ranked(1, "bob", 500), ranked(2, "alice", 300), ranked(3, "carol", 300));

        GetPlayerRankResponse rank = grpc.getPlayerRank(GetPlayerRankRequest.newBuilder()
                .setLeaderboardId("weekly").setPlayerId("alice").setNeighbors(1).build());
        assertThat(rank.getPlayer()).isEqualTo(ranked(2, "alice", 300));
        assertThat(rank.getAboveList()).containsExactly(ranked(1, "bob", 500));
        assertThat(rank.getBelowList()).containsExactly(ranked(3, "carol", 300));

        GetPlayerStatsResponse stats = grpc.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("alice").build());
        assertThat(stats.getGamesPlayed()).isEqualTo(2);
        assertThat(stats.getBestScore()).isEqualTo(300);
        assertThat(stats.getLastUpdated().getSeconds()).isPositive();
    }

    @Test
    void submitResponseFlags() {
        assertThat(submit("alice", "weekly", 100, "k1"))
                .returns(100L, SubmitScoreResponse::getBestScore)
                .returns(true, SubmitScoreResponse::getImproved)
                .returns(false, SubmitScoreResponse::getDuplicate);
        assertThat(submit("alice", "weekly", 50, "k2"))
                .returns(100L, SubmitScoreResponse::getBestScore)
                .returns(false, SubmitScoreResponse::getImproved);
        assertThat(submit("alice", "weekly", 100, "k1"))
                .returns(100L, SubmitScoreResponse::getBestScore)
                .returns(false, SubmitScoreResponse::getImproved)
                .returns(true, SubmitScoreResponse::getDuplicate);
    }

    @Test
    void retryAfterRedisLostTheWriteRepairsTheRanking() {
        submit("alice", "weekly", 100, "k1");
        flushRedis();  // simulate: Postgres committed, but the ZADD never landed

        SubmitScoreResponse retry = submit("alice", "weekly", 100, "k1");

        assertThat(retry.getDuplicate()).isTrue();
        var top = grpc.getTopN(GetTopNRequest.newBuilder().setLeaderboardId("weekly").setN(1).build());
        assertThat(top.getEntriesList()).containsExactly(ranked(1, "alice", 100));
        var stats = grpc.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("alice").build());
        assertThat(stats.getGamesPlayed()).as("retry did not double-count").isEqualTo(1);
    }

    @Test
    void reusingIdempotencyKeyForDifferentScoreIsAlreadyExists() {
        submit("alice", "weekly", 100, "k1");
        assertCode(() -> submit("alice", "weekly", 999, "k1"), Status.Code.ALREADY_EXISTS);
    }

    @Test
    void notFoundCases() {
        assertCode(() -> grpc.getTopN(GetTopNRequest.newBuilder().setLeaderboardId("nope").setN(5).build()),
                Status.Code.NOT_FOUND);
        assertCode(() -> grpc.getPlayerRank(GetPlayerRankRequest.newBuilder()
                .setLeaderboardId("weekly").setPlayerId("ghost").build()), Status.Code.NOT_FOUND);
        assertCode(() -> grpc.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("ghost").build()),
                Status.Code.NOT_FOUND);
    }

    static Stream<Arguments> invalidRequests() {
        SubmitScoreRequest valid = SubmitScoreRequest.newBuilder()
                .setPlayerId("p").setLeaderboardId("b").setScore(1).setIdempotencyKey("k").build();
        return Stream.of(
                Arguments.of("empty player_id", valid.toBuilder().setPlayerId("").build()),
                Arguments.of("empty leaderboard_id", valid.toBuilder().setLeaderboardId("").build()),
                Arguments.of("bad characters", valid.toBuilder().setPlayerId("a b/c").build()),
                Arguments.of("id too long", valid.toBuilder().setPlayerId("x".repeat(65)).build()),
                Arguments.of("negative score", valid.toBuilder().setScore(-1).build()),
                Arguments.of("score above encoding limit", valid.toBuilder().setScore(67_108_864L).build()),
                Arguments.of("empty idempotency_key", valid.toBuilder().setIdempotencyKey("").build()),
                Arguments.of("idempotency_key too long", valid.toBuilder().setIdempotencyKey("k".repeat(129)).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidSubmissionsAreRejectedBeforeTouchingStorage(String name, SubmitScoreRequest request) {
        assertCode(() -> grpc.submitScore(request), Status.Code.INVALID_ARGUMENT);
        assertThat(jdbc.sql("SELECT count(*) FROM score_events").query(Long.class).single()).isZero();
    }

    @Test
    void invalidReadArguments() {
        assertCode(() -> grpc.getTopN(GetTopNRequest.newBuilder().setLeaderboardId("b").setN(0).build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> grpc.getTopN(GetTopNRequest.newBuilder().setLeaderboardId("b").setN(101).build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> grpc.getPlayerRank(GetPlayerRankRequest.newBuilder()
                .setLeaderboardId("b").setPlayerId("p").setNeighbors(-1).build()), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> grpc.getPlayerRank(GetPlayerRankRequest.newBuilder()
                .setLeaderboardId("b").setPlayerId("p").setNeighbors(51).build()), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> grpc.getPlayerStats(GetPlayerStatsRequest.getDefaultInstance()), Status.Code.INVALID_ARGUMENT);
    }

    private static void assertCode(ThrowingCallable call, Status.Code expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(expected));
    }
}
