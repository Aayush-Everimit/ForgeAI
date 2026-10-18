# API Contracts and Background Jobs

## API rules
Use versioned internal REST endpoints, typed schemas, authenticated service calls, size limits, timeouts, safe structured errors and correlation IDs. Never trust user/project scope supplied directly by the browser.

## RAG request example
```json
{
  "request_id": "uuid",
  "project_id": "uuid",
  "authorized_user_id": "uuid",
  "query": "Why does ingestion retry?",
  "task_type": "debugging",
  "allowed_source_ids": ["uuid"],
  "top_k": 5,
  "context_token_budget": 6000,
  "filters": {"approved_only": true, "exclude_deleted": true}
}
```
This is illustrative. Trusted backend authentication and authorization must establish scope; fields alone are not proof.

## RAG response example
```json
{
  "request_id": "uuid",
  "results": [{
    "chunk_id": "uuid",
    "source_id": "uuid",
    "source_reference": "services/worker/ingestion.ts",
    "content": "Retry policy ...",
    "score": 0.83,
    "score_type": "reranker",
    "content_hash": "sha256:...",
    "estimated_tokens": 180
  }],
  "warnings": [],
  "retrieval_status": "ok"
}
```
Scores are not calibrated probabilities unless explicitly calibrated.

## Suggested endpoints
- `POST /internal/v1/ingest`
- `POST /internal/v1/retrieve`
- `DELETE /internal/v1/sources/{source_id}`
- `GET /internal/v1/health`
- `GET /internal/v1/ready`

## Errors
Return stable code, safe message, retryability, request ID and optional retry-after hint. Never expose stack traces, credentials or private content.

## Durable jobs
Use PostgreSQL-backed queue with scheduling, leases/claims, bounded retries with exponential backoff and jitter, idempotency keys, entity version guards, observable failed status and safe replay. Redis is not the only durable store.

Job types: day-eight reminder, day-ten expiry, handoff cleanup, source ingestion, Qdrant reconciliation, read-only GitHub sync, cache invalidation.

Before writing, workers re-check authoritative status and generation/version. Deleted entities cannot be reindexed. Kafka is deferred unless measured requirements justify a durable event stream.
