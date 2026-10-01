package dev.vansh.leaderboard.api;

import com.google.protobuf.Timestamp;
import dev.vansh.leaderboard.core.LeaderboardService;
import dev.vansh.leaderboard.core.PlayerStats;
import dev.vansh.leaderboard.core.RankWindow;
import dev.vansh.leaderboard.core.RankedPlayer;
import dev.vansh.leaderboard.core.ScoreSubmission;
import dev.vansh.leaderboard.core.SubmitOutcome;
import dev.vansh.leaderboard.proto.v1.GetPlayerRankRequest;
import dev.vansh.leaderboard.proto.v1.GetPlayerRankResponse;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsRequest;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsResponse;
import dev.vansh.leaderboard.proto.v1.GetTopNRequest;
import dev.vansh.leaderboard.proto.v1.GetTopNResponse;
import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc;
import dev.vansh.leaderboard.proto.v1.RankedEntry;
import dev.vansh.leaderboard.proto.v1.SubmitScoreRequest;
import dev.vansh.leaderboard.proto.v1.SubmitScoreResponse;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC adapter: validate, translate proto <-> domain, delegate. No business logic lives here, and
 * no error handling either: exceptions propagate to {@link GrpcErrorMapper}.
 */
@GrpcService
public class LeaderboardGrpcService extends LeaderboardServiceGrpc.LeaderboardServiceImplBase {

    private final LeaderboardService service;

    public LeaderboardGrpcService(LeaderboardService service) {
        this.service = service;
    }

    @Override
    public void submitScore(SubmitScoreRequest req, StreamObserver<SubmitScoreResponse> out) {
        RequestValidator.id("player_id", req.getPlayerId());
        RequestValidator.id("leaderboard_id", req.getLeaderboardId());
        RequestValidator.score(req.getScore());
        RequestValidator.idempotencyKey(req.getIdempotencyKey());

        SubmitOutcome outcome = service.submit(new ScoreSubmission(
                req.getPlayerId(), req.getLeaderboardId(), req.getScore(), req.getIdempotencyKey()));

        out.onNext(SubmitScoreResponse.newBuilder()
                .setBestScore(outcome.entry().score())
                .setImproved(outcome.improved())
                .setDuplicate(outcome.duplicate())
                .build());
        out.onCompleted();
    }

    @Override
    public void getTopN(GetTopNRequest req, StreamObserver<GetTopNResponse> out) {
        RequestValidator.id("leaderboard_id", req.getLeaderboardId());
        RequestValidator.range("n", req.getN(), 1, RequestValidator.MAX_TOP_N);

        List<RankedPlayer> top = service.topN(req.getLeaderboardId(), req.getN());

        out.onNext(GetTopNResponse.newBuilder().addAllEntries(toProto(top)).build());
        out.onCompleted();
    }

    @Override
    public void getPlayerRank(GetPlayerRankRequest req, StreamObserver<GetPlayerRankResponse> out) {
        RequestValidator.id("leaderboard_id", req.getLeaderboardId());
        RequestValidator.id("player_id", req.getPlayerId());
        RequestValidator.range("neighbors", req.getNeighbors(), 0, RequestValidator.MAX_NEIGHBORS);

        RankWindow window = service.playerRank(req.getLeaderboardId(), req.getPlayerId(), req.getNeighbors());

        out.onNext(GetPlayerRankResponse.newBuilder()
                .setPlayer(toProto(window.player()))
                .addAllAbove(toProto(window.above()))
                .addAllBelow(toProto(window.below()))
                .build());
        out.onCompleted();
    }

    @Override
    public void getPlayerStats(GetPlayerStatsRequest req, StreamObserver<GetPlayerStatsResponse> out) {
        RequestValidator.id("player_id", req.getPlayerId());

        PlayerStats stats = service.playerStats(req.getPlayerId());

        out.onNext(GetPlayerStatsResponse.newBuilder()
                .setPlayerId(stats.playerId())
                .setGamesPlayed(stats.gamesPlayed())
                .setBestScore(stats.bestScore())
                .setLastUpdated(Timestamp.newBuilder()
                        .setSeconds(stats.lastUpdated().getEpochSecond())
                        .setNanos(stats.lastUpdated().getNano()))
                .build());
        out.onCompleted();
    }

    private static RankedEntry toProto(RankedPlayer p) {
        return RankedEntry.newBuilder().setRank(p.rank()).setPlayerId(p.playerId()).setScore(p.score()).build();
    }

    private static List<RankedEntry> toProto(List<RankedPlayer> players) {
        return players.stream().map(LeaderboardGrpcService::toProto).toList();
    }
}
