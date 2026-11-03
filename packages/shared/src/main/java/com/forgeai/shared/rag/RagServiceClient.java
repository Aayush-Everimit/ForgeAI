package com.forgeai.shared.rag;

import com.forgeai.shared.rag.RagContractDtos.DeleteSourceResponse;
import com.forgeai.shared.rag.RagContractDtos.HealthResponse;
import com.forgeai.shared.rag.RagContractDtos.IngestRequest;
import com.forgeai.shared.rag.RagContractDtos.IngestResponse;
import com.forgeai.shared.rag.RagContractDtos.RetrieveRequest;
import com.forgeai.shared.rag.RagContractDtos.RetrieveResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated HTTP client for communicating with the Python FastAPI RAG service.
 * Propagates correlation IDs (X-Request-Id), internal service token (X-Internal-Service-Token),
 * bounded timeouts, and safe retries.
 */
@Component
public class RagServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RagServiceClient.class);
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Service-Token";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private final RestClient restClient;
    private final String internalToken;
    private final int maxRetries;

    public RagServiceClient(
            @Value("${forgeai.rag.base-url:http://localhost:8000}") String baseUrl,
            @Value("${forgeai.rag.internal-token:local-dev-rag-internal-token}") String internalToken,
            @Value("${forgeai.rag.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${forgeai.rag.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${forgeai.rag.max-retries:2}") int maxRetries
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.internalToken = internalToken;
        this.maxRetries = Math.max(1, maxRetries);
    }

    public IngestResponse ingest(IngestRequest request) {
        String requestId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                IngestResponse response = restClient.post()
                        .uri("/internal/v1/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_INTERNAL_TOKEN, internalToken)
                        .header(HEADER_REQUEST_ID, requestId)
                        .body(request)
                        .retrieve()
                        .body(IngestResponse.class);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                lastError = e;
                log.warn("RAG ingest attempt {}/{} failed for source {} (requestId={}): {}",
                        attempt, maxRetries, request.sourceId(), requestId, e.getMessage());
            }
        }
        throw new RagServiceUnavailableException("RAG service ingestion failed after " + maxRetries + " attempts", lastError);
    }

    public RetrieveResponse retrieve(RetrieveRequest request) {
        String requestId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                RetrieveResponse response = restClient.post()
                        .uri("/internal/v1/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HEADER_INTERNAL_TOKEN, internalToken)
                        .header(HEADER_REQUEST_ID, requestId)
                        .body(request)
                        .retrieve()
                        .body(RetrieveResponse.class);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                lastError = e;
                log.warn("RAG retrieve attempt {}/{} failed for project {} (requestId={}): {}",
                        attempt, maxRetries, request.projectId(), requestId, e.getMessage());
            }
        }
        log.warn("RAG service unavailable for project {} (requestId={}); returning degraded empty result with warning",
                request.projectId(), requestId);
        return new RetrieveResponse(
                requestId,
                List.of(),
                List.of("RAG_SERVICE_UNAVAILABLE: Evidence retrieval degraded because the RAG service could not be reached."),
                "degraded"
        );
    }

    public DeleteSourceResponse deleteSource(UUID sourceId, String requestId) {
        String corrId = requestId != null ? requestId : UUID.randomUUID().toString();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                DeleteSourceResponse response = restClient.delete()
                        .uri("/internal/v1/sources/{sourceId}", sourceId)
                        .header(HEADER_INTERNAL_TOKEN, internalToken)
                        .header(HEADER_REQUEST_ID, corrId)
                        .retrieve()
                        .body(DeleteSourceResponse.class);
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                lastError = e;
                log.warn("RAG deleteSource attempt {}/{} failed for source {} (requestId={}): {}",
                        attempt, maxRetries, sourceId, corrId, e.getMessage());
            }
        }
        throw new RagServiceUnavailableException("RAG service source deletion failed after " + maxRetries + " attempts", lastError);
    }

    public boolean isReady() {
        try {
            HealthResponse resp = restClient.get()
                    .uri("/internal/v1/ready")
                    .header(HEADER_INTERNAL_TOKEN, internalToken)
                    .retrieve()
                    .body(HealthResponse.class);
            return resp != null && "ok".equalsIgnoreCase(resp.status());
        } catch (Exception e) {
            return false;
        }
    }
}
