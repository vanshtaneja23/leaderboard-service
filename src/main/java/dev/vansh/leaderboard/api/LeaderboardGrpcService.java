package dev.vansh.leaderboard.api;

import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC entry point. Every RPC currently falls through to the generated base class, which answers
 * UNIMPLEMENTED; the real handlers are added once the storage layers exist.
 */
@GrpcService
public class LeaderboardGrpcService extends LeaderboardServiceGrpc.LeaderboardServiceImplBase {
}
