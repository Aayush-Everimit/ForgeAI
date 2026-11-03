package com.forgeai.shared.rag;

import com.forgeai.shared.rag.RagContractDtos.IngestRequest;
import com.forgeai.shared.rag.RagContractDtos.IngestResponse;
import com.forgeai.shared.rag.RagContractDtos.RetrieveFilters;
import com.forgeai.shared.rag.RagContractDtos.RetrieveRequest;
import com.forgeai.shared.rag.RagContractDtos.RetrieveResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagServiceClientTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsInternalTokenAndCorrelationIdAndRetriesTransientFailure() {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicReference<String> receivedToken = new AtomicReference<>();
        AtomicReference<String> receivedRequestId = new AtomicReference<>();

        server.createContext("/internal/v1/ingest", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst(RagServiceClient.HEADER_INTERNAL_TOKEN));
            receivedRequestId.set(exchange.getRequestHeaders().getFirst(RagServiceClient.HEADER_REQUEST_ID));
            int count = attempts.incrementAndGet();
            if (count == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String json = """
                    {
                      "request_id": "req-123",
                      "source_id": "11111111-1111-1111-1111-111111111111",
                      "source_version": 1,
                      "status": "ok",
                      "chunks": [{
                        "chunk_id": "c-1",
                        "vector_id": "v-1",
                        "chunk_hash": "sha256:abc",
                        "embedding_model": "BAAI/bge-small-en-v1.5",
                        "embedding_revision": "v1.5",
                        "estimated_tokens": 42
                      }]
                    }
                    """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        RagServiceClient client = new RagServiceClient(
                "http://localhost:" + port,
                "secret-service-token",
                1000,
                1000,
                2
        );

        IngestResponse response = client.ingest(new IngestRequest(
                "req-123",
                UUID.randomUUID(),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "DOCUMENT",
                "docs/arch.md",
                1L,
                "sha256:doc",
                "Architecture content"
        ));

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(receivedToken.get()).isEqualTo("secret-service-token");
        assertThat(receivedRequestId.get()).isEqualTo("req-123");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.chunks()).hasSize(1);
    }

    @Test
    void retrieveReturnsDegradedResponseWhenRagUnavailableAndIngestThrows() {
        RagServiceClient unavailableClient = new RagServiceClient(
                "http://localhost:1", // unreachable port
                "secret-service-token",
                200,
                200,
                2
        );

        RetrieveResponse degraded = unavailableClient.retrieve(new RetrieveRequest(
                "req-degraded",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "How does retry work?",
                "debugging",
                List.of(UUID.randomUUID()),
                5,
                4000,
                new RetrieveFilters(true, true)
        ));

        assertThat(degraded.retrievalStatus()).isEqualTo("degraded");
        assertThat(degraded.results()).isEmpty();
        assertThat(degraded.warnings()).anyMatch(w -> w.contains("RAG_SERVICE_UNAVAILABLE"));

        assertThatThrownBy(() -> unavailableClient.ingest(new IngestRequest(
                "req-fail",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "DOCUMENT",
                "README.md",
                1L,
                "sha256:1",
                "content"
        ))).isInstanceOf(RagServiceUnavailableException.class);
    }
}
