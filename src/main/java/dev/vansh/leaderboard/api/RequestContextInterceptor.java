package dev.vansh.leaderboard.api;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * Gives every RPC a request id and writes one structured access-log line when it finishes.
 *
 * <ul>
 *   <li>The id comes from the caller's {@code x-request-id} metadata if it is well-formed (so one id
 *       can follow a request across services), otherwise a fresh UUID.</li>
 *   <li>It is put in the SLF4J MDC, so <em>every</em> log line written while handling the call
 *       carries {@code request_id}. gRPC may run each callback of a call on a different thread and
 *       MDC is thread-local, so it is set and cleared around each callback, not once per call.</li>
 *   <li>It is echoed back in response headers and trailers, so clients can quote it in bug reports.</li>
 * </ul>
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final String MDC_KEY = "request_id";

    private static final Logger accessLog = LoggerFactory.getLogger("leaderboard.access");
    // Anything else is replaced: a client must not be able to inject arbitrary text into our logs.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String incoming = headers.get(REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        String method = call.getMethodDescriptor().getFullMethodName();
        long startNanos = System.nanoTime();

        ServerCall<ReqT, RespT> tracked = new SimpleForwardingServerCall<>(call) {
            private boolean headersSent;

            @Override
            public void sendHeaders(Metadata responseHeaders) {
                responseHeaders.put(REQUEST_ID_HEADER, requestId);
                headersSent = true;
                super.sendHeaders(responseHeaders);
            }

            @Override
            public void close(Status status, Metadata trailers) {
                if (!headersSent) {
                    // Errors before any response are "trailers-only"; put the id there instead.
                    trailers.put(REQUEST_ID_HEADER, requestId);
                }
                inMdc(requestId, () -> accessLog.atInfo()
                        .addKeyValue("rpc.method", method)
                        .addKeyValue("grpc.status", status.getCode().name())
                        .addKeyValue("duration_ms", (System.nanoTime() - startNanos) / 1_000_000)
                        .log("rpc completed"));
                super.close(status, trailers);
            }
        };

        MDC.put(MDC_KEY, requestId);
        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = next.startCall(tracked, headers);
        } finally {
            MDC.remove(MDC_KEY);
        }

        return new SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                inMdc(requestId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                // For unary calls this is where the service method actually runs.
                inMdc(requestId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                inMdc(requestId, super::onCancel);
            }

            @Override
            public void onComplete() {
                inMdc(requestId, super::onComplete);
            }

            @Override
            public void onReady() {
                inMdc(requestId, super::onReady);
            }
        };
    }

    private static void inMdc(String requestId, Runnable action) {
        MDC.put(MDC_KEY, requestId);
        try {
            action.run();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
