package dev.vansh.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vansh.leaderboard.proto.v1.GetPlayerStatsRequest;
import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc;
import dev.vansh.leaderboard.support.IntegrationTest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.Test;

/** Proves the generated service is registered with the gRPC server. */
class GrpcWiringTest extends IntegrationTest {

    @Test
    void serviceIsRegistered() {
        ManagedChannel channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).directExecutor().build();
        try {
            var stub = LeaderboardServiceGrpc.newBlockingStub(channel);
            assertThatThrownBy(() -> stub.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("p1").build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
        } finally {
            channel.shutdownNow();
        }
    }
}
