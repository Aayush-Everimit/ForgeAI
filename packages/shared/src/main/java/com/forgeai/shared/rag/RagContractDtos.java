package com.forgeai.shared.rag;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RagContractDtos {

    private RagContractDtos() {
    }

    public record IngestRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("project_id") UUID projectId,
            @JsonProperty("source_id") UUID sourceId,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_reference") String sourceReference,
            @JsonProperty("source_version") long sourceVersion,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("content") String content
    ) {}

    public record IngestedChunkRecord(
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("vector_id") String vectorId,
            @JsonProperty("chunk_hash") String chunkHash,
            @JsonProperty("embedding_model") String embeddingModel,
            @JsonProperty("embedding_revision") String embeddingRevision,
            @JsonProperty("estimated_tokens") int estimatedTokens
    ) {}

    public record IngestResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("source_id") UUID sourceId,
            @JsonProperty("source_version") long sourceVersion,
            @JsonProperty("status") String status,
            @JsonProperty("chunks") List<IngestedChunkRecord> chunks
    ) {}

    public record RetrieveFilters(
            @JsonProperty("approved_only") boolean approvedOnly,
            @JsonProperty("exclude_deleted") boolean excludeDeleted
    ) {}

    public record RetrieveRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("project_id") UUID projectId,
            @JsonProperty("authorized_user_id") UUID authorizedUserId,
            @JsonProperty("query") String query,
            @JsonProperty("task_type") String taskType,
            @JsonProperty("allowed_source_ids") List<UUID> allowedSourceIds,
            @JsonProperty("top_k") int topK,
            @JsonProperty("context_token_budget") int contextTokenBudget,
            @JsonProperty("filters") RetrieveFilters filters
    ) {}

    public record RetrievedChunk(
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("source_id") UUID sourceId,
            @JsonProperty("source_reference") String sourceReference,
            @JsonProperty("content") String content,
            @JsonProperty("score") double score,
            @JsonProperty("score_type") String scoreType,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("estimated_tokens") int estimatedTokens
    ) {}

    public record RetrieveResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("results") List<RetrievedChunk> results,
            @JsonProperty("warnings") List<String> warnings,
            @JsonProperty("retrieval_status") String retrievalStatus
    ) {}

    public record DeleteSourceResponse(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("source_id") UUID sourceId,
            @JsonProperty("deleted") boolean deleted
    ) {}

    public record HealthResponse(
            @JsonProperty("status") String status,
            @JsonProperty("service") String service,
            @JsonProperty("details") Map<String, Object> details
    ) {}
}
