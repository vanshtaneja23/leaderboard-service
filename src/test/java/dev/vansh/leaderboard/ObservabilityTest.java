package dev.vansh.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vansh.leaderboard.api.RequestContextInterceptor;
import dev.vansh.leaderboard.proto.v1.GetPlayerStatsRequest;
import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc;
import dev.vansh.leaderboard.proto.v1.LeaderboardServiceGrpc.LeaderboardServiceBlockingStub;
import dev.vansh.leaderboard.proto.v1.SubmitScoreRequest;
import dev.vansh.leaderboard.support.TestcontainersConfig;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/** Health probes, Prometheus metrics, and request-id propagation, over the real HTTP port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.grpc.server.port=0",
        "spring.grpc.server.inprocess.name=observability-test",
        "leaderboard.reconcile.interval=1h"
})
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {

    @LocalServerPort
    int httpPort;

    private ManagedChannel channel;
    private RestClient http;

    @BeforeEach
    void setUp() {
        channel = InProcessChannelBuilder.forName("observability-test").build();
        http = RestClient.create("http://localhost:" + httpPort);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void livenessAndReadinessProbesAreUp() {
        assertThat(http.get().uri("/actuator/health/liveness").retrieve().body(String.class)).contains("\"UP\"");
        assertThat(http.get().uri("/actuator/health/readiness").retrieve().body(String.class)).contains("\"UP\"");
    }

    @Test
    void prometheusExposesPerRpcCountAndLatencyHistogram() {
        LeaderboardServiceBlockingStub stub = LeaderboardServiceGrpc.newBlockingStub(channel);
        stub.submitScore(SubmitScoreRequest.newBuilder()
                .setPlayerId("metrics-player").setLeaderboardId("metrics-board").setScore(1)
                .setIdempotencyKey("metrics-" + System.nanoTime()).build());

        String scrape = http.get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(scrape)
                .as("latency histogram buckets per RPC method")
                .containsPattern("grpc_server_seconds_bucket\\{.*rpc_method=\"SubmitScore\".*le=\"")
                .as("request count per RPC method and status")
                .containsPattern("grpc_server_seconds_count\\{.*grpc_status_code=\"OK\".*rpc_method=\"SubmitScore\"")
                .contains("leaderboard_submissions_total{")
                .contains("leaderboard_rebuilds_total");
    }

    @Test
    void requestIdIsEchoedAndLogged(CapturedOutput output) {
        AtomicReference<Metadata> responseHeaders = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        Metadata outgoing = new Metadata();
        outgoing.put(RequestContextInterceptor.REQUEST_ID_HEADER, "client-req-42");
        LeaderboardServiceBlockingStub stub = LeaderboardServiceGrpc.newBlockingStub(channel)
                .withInterceptors(
                        MetadataUtils.newAttachHeadersInterceptor(outgoing),
                        MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, trailers));

        // An error response is "trailers-only", the trickier path for propagating the id.
        assertThatThrownBy(() -> stub.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("ghost").build()))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(trailers.get().get(RequestContextInterceptor.REQUEST_ID_HEADER)).isEqualTo("client-req-42");
        assertThat(output.getOut())
                .as("structured JSON access log carries the request id")
                .containsPattern("\\{.*\"request_id\":\"client-req-42\".*\"message\":\"rpc completed\".*\\}"
                        + "|\\{.*\"message\":\"rpc completed\".*\"request_id\":\"client-req-42\".*\\}");
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() {
        AtomicReference<Metadata> responseHeaders = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        Metadata outgoing = new Metadata();
        outgoing.put(RequestContextInterceptor.REQUEST_ID_HEADER, "evil\",\"level\":\"FAKE");
        LeaderboardServiceBlockingStub stub = LeaderboardServiceGrpc.newBlockingStub(channel)
                .withInterceptors(
                        MetadataUtils.newAttachHeadersInterceptor(outgoing),
                        MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, trailers));

        assertThatThrownBy(() -> stub.getPlayerStats(GetPlayerStatsRequest.newBuilder().setPlayerId("ghost").build()))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(trailers.get().get(RequestContextInterceptor.REQUEST_ID_HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
