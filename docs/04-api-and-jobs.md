# API Contracts and Background Jobs

## API rules
Use versioned REST endpoints (`/api/v1` for public product APIs in Spring Boot; `/internal/v1` for internal RAG endpoints in FastAPI), typed DTO schemas, Jakarta Bean Validation, authenticated service calls, size limits, timeouts, safe structured errors and correlation IDs (`X-Request-Id`). Never trust user/project scope supplied directly by the browser. Use DTOs exclusively on HTTP boundaries rather than exposing JPA entities.

## Public Product API Contract (Spring Boot — `/api/v1`)

All `/api/v1/**` endpoints (except OAuth initiation/callback and public health probes) require an authenticated session (`FORGEAI_SESSION` HTTP-only cookie or authenticated security principal). State-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`) enforce CSRF protection and validate payloads via Jakarta Bean Validation. Project-scoped endpoints enforce server-side ownership checks against `projects.owner_user_id` and return `404 Not Found` or `403 Forbidden` on unauthorized access to prevent IDOR.

### 1. Authentication & Current User
- `GET /api/v1/auth/github/login`
  - **Auth:** Public. Initiates GitHub OAuth2 authorization code flow with state validation.
  - **Response:** `302 Found` redirect to GitHub OAuth authorize endpoint (or `/oauth2/authorization/github`).
- `GET /api/v1/auth/me`
  - **Auth:** Authenticated user.
  - **Response (`200 OK`):** `{ "id": "uuid", "githubId": "string", "githubLogin": "string", "displayName": "string", "avatarUrl": "string", "status": "ACTIVE", "createdAt": "ISO-8601" }`
- `POST /api/v1/auth/logout`
  - **Auth:** Authenticated user + CSRF.
  - **Response (`204 No Content`):** Invalidates server session and clears session cookie.

### 2. Projects
- `GET /api/v1/projects`
  - **Auth:** Authenticated user. Lists non-deleted projects owned by the current user.
  - **Response (`200 OK`):** `[ProjectResponse]`
- `POST /api/v1/projects`
  - **Auth:** Authenticated user.
  - **Request:** `{ "name": "string (1..120)", "description": "string (0..2000)", "stackMetadata": { "languages": ["string"], "frameworks": ["string"], "notes": "string" } }`
  - **Response (`201 Created`):** `ProjectResponse`
- `GET /api/v1/projects/{projectId}`
  - **Auth:** Project owner.
  - **Response (`200 OK`):** `ProjectResponse`
- `PATCH /api/v1/projects/{projectId}`
  - **Auth:** Project owner.
  - **Request:** `{ "name": "string?", "description": "string?", "stackMetadata": { ... } }`
  - **Response (`200 OK`):** `ProjectResponse`
- `DELETE /api/v1/projects/{projectId}`
  - **Auth:** Project owner. Idempotent soft-delete + schedules durable cleanup job (`PROJECT_CLEANUP`) for sources, Qdrant vectors, handoffs, and cache.
  - **Response (`202 Accepted`):** `{ "status": "DELETING", "projectId": "uuid", "cleanupJobId": "uuid" }`

### 3. Project Knowledge & Approval Workflows
- `GET /api/v1/projects/{projectId}/knowledge`
  - **Auth:** Project owner. Optional query params: `status` (`PROPOSED`, `APPROVED`, `REJECTED`, `SUPERSEDED`), `kind` (`NOTE`, `PROGRESS`, `DECISION`, `CONSTRAINT`, `ISSUE`, `SUMMARY`).
  - **Response (`200 OK`):** `[KnowledgeItemResponse]`
- `POST /api/v1/projects/{projectId}/knowledge`
  - **Auth:** Project owner. Creates either manual knowledge (immediately `APPROVED` or `PROPOSED`) or AI-proposed knowledge (`PROPOSED`). Detects conflicts with existing approved items of the same kind.
  - **Request:** `{ "kind": "DECISION|CONSTRAINT|PROGRESS|ISSUE|NOTE|SUMMARY", "title": "string (1..200)", "content": "string (1..20000)", "sourceType": "MANUAL|AI_PROPOSAL|GITHUB|DOCUMENT", "sourceReference": "string?", "autoApprove": true|false }`
  - **Response (`201 Created`):** `KnowledgeItemResponse` (includes `conflictWarnings` if overlapping approved items exist).
- `POST /api/v1/projects/{projectId}/knowledge/{itemId}/review`
  - **Auth:** Project owner. Review, optionally edit, and approve or reject a proposed knowledge item. Approving an item that replaces an earlier item records `supersedesId` and versions the decision history without silent overwrite.
  - **Request:** `{ "action": "APPROVE|REJECT", "editedTitle": "string?", "editedContent": "string?", "supersedesItemId": "uuid?", "reviewNotes": "string?" }`
  - **Response (`200 OK`):** `KnowledgeItemResponse`
- `DELETE /api/v1/projects/{projectId}/knowledge/{itemId}`
  - **Auth:** Project owner. Idempotent deletion + vector/cache cleanup.
  - **Response (`204 No Content`)**

### 4. Handoffs & Lifecycle (Day-8 Reminder, Day-10 Expiry, Extension)
- `GET /api/v1/projects/{projectId}/handoffs`
  - **Auth:** Project owner. Lists active/expired non-deleted handoffs for the project.
  - **Response (`200 OK`):** `[HandoffResponse]`
- `POST /api/v1/projects/{projectId}/handoffs`
  - **Auth:** Project owner. Generates a source-grounded, canonical five-part handoff prompt (`Role`, `Task`, `Context`, `Constraints and instructions`, `Output and success criteria`). Queries FastAPI `/internal/v1/retrieve`, validates sources against PostgreSQL, flags missing/conflicting context, sets `expiresAt = now + 10 days`, and schedules `DAY_EIGHT_REMINDER` and `DAY_TEN_EXPIRY` jobs.
  - **Request:** `{ "task": "string (1..4000)", "taskType": "string (e.g. debugging, feature, refactor)", "role": "string?", "targetModel": "string?", "contextTokenBudget": 6000, "executeInference": false }`
  - **Response (`201 Created`):** `HandoffResponse`
- `GET /api/v1/projects/{projectId}/handoffs/{handoffId}`
  - **Auth:** Project owner.
  - **Response (`200 OK`):** `HandoffResponse`
- `PATCH /api/v1/projects/{projectId}/handoffs/{handoffId}`
  - **Auth:** Project owner. Updates prompt body/notes and increments `version` (generation guard).
  - **Request:** `{ "task": "string?", "promptBody": "string (1..50000)" }`
  - **Response (`200 OK`):** `HandoffResponse`
- `POST /api/v1/projects/{projectId}/handoffs/{handoffId}/extend`
  - **Auth:** Project owner. Extends an active, non-deleted handoff by 10 days from extension time, increments `version` to invalidate stale reminder/expiry jobs, and schedules a new expiry job for the updated generation.
  - **Request:** `{ "extensionDays": 10 }`
  - **Response (`200 OK`):** `HandoffResponse`
- `DELETE /api/v1/projects/{projectId}/handoffs/{handoffId}`
  - **Auth:** Project owner. Immediately marks handoff deleted, increments `version`, and schedules `HANDOFF_CLEANUP`.
  - **Response (`202 Accepted`):** `{ "status": "DELETED", "handoffId": "uuid", "cleanupJobId": "uuid" }`

### 5. Source Documents & Ingestion
- `GET /api/v1/projects/{projectId}/sources`
  - **Auth:** Project owner.
  - **Response (`200 OK`):** `[SourceDocumentResponse]`
- `POST /api/v1/projects/{projectId}/sources`
  - **Auth:** Project owner. Registers a source document and enqueues a durable `SOURCE_INGESTION` job.
  - **Request:** `{ "sourceType": "DOCUMENT|CODE_FILE|GITHUB_FILE|NOTE", "sourceReference": "string (1..500)", "content": "string (1..200000)" }`
  - **Response (`202 Accepted`):** `SourceDocumentResponse` (with `ingestionStatus: "PENDING"` and `jobId`).
- `DELETE /api/v1/projects/{projectId}/sources/{sourceId}`
  - **Auth:** Project owner. Marks source deleted, increments `sourceVersion` generation guard so in-flight ingestion jobs cannot re-index it, and enqueues `SOURCE_DELETION` to purge Qdrant vectors via FastAPI.
  - **Response (`202 Accepted`):** `{ "status": "DELETING", "sourceId": "uuid", "cleanupJobId": "uuid" }`

### 6. Provider Credentials (BYOK) & Model Catalogue
- `GET /api/v1/providers/models`
  - **Auth:** Authenticated user. Returns available OpenRouter models with capability metadata (`supportsTools`, `supportsJson`, `supportsStreaming`, `contextLength`) and fallback recommendations.
  - **Response (`200 OK`):** `[ModelDescriptorResponse]`
- `POST /api/v1/providers/route-preview`
  - **Auth:** Authenticated user. Validates requested model capabilities against task requirements and discloses visible fallback if the primary model lacks required capabilities or is unavailable.
  - **Request:** `{ "requestedModel": "string", "requireTools": false, "requireJson": false, "allowFallback": true }`
  - **Response (`200 OK`):** `{ "selectedModel": "string", "fallbackUsed": true|false, "fallbackReason": "string?", "warnings": ["string"] }`
- `GET /api/v1/providers/credentials`
  - **Auth:** Authenticated user. Lists masked credential metadata (never returns plaintext or raw ciphertext).
  - **Response (`200 OK`):** `[ProviderCredentialResponse]` (`id`, `provider`, `keyHint`, `kekKeyId`, `status`, `createdAt`, `revokedAt`).
- `POST /api/v1/providers/credentials`
  - **Auth:** Authenticated user. Encrypts user-supplied API key using AES-256-GCM envelope encryption (per-record DEK wrapped by KEK) and stores ciphertext.
  - **Request:** `{ "provider": "OPENROUTER", "apiKey": "string (non-blank)" }`
  - **Response (`201 Created`):** `ProviderCredentialResponse`
- `DELETE /api/v1/providers/credentials/{credentialId}`
  - **Auth:** Credential owner. Revokes and wipes credential material.
  - **Response (`204 No Content`)**

### 7. Selected GitHub Repositories (Read-Only Release 0)
- `GET /api/v1/projects/{projectId}/github/repositories`
  - **Auth:** Project owner.
  - **Response (`200 OK`):** `[GitHubRepositoryBindingResponse]`
- `POST /api/v1/projects/{projectId}/github/repositories`
  - **Auth:** Project owner. Binds an explicitly selected repository via GitHub App installation (enforcing `READ_ONLY` permission scope) and optionally schedules `GITHUB_SYNC`.
  - **Request:** `{ "installationId": "string", "repositoryFullName": "owner/repo", "defaultBranch": "main", "triggerSync": true }`
  - **Response (`201 Created`):** `GitHubRepositoryBindingResponse`
- `POST /api/v1/projects/{projectId}/github/repositories/{bindingId}/revoke`
  - **Auth:** Project owner. Marks installation/repository revoked, halts future sync jobs, and schedules cleanup.
  - **Response (`200 OK`):** `GitHubRepositoryBindingResponse`

### 8. Jobs & Operational Status
- `GET /api/v1/projects/{projectId}/jobs`
  - **Auth:** Project owner. Lists background jobs scoped to the project.
  - **Response (`200 OK`):** `[JobResponse]`
- `GET /api/v1/jobs/{jobId}`
  - **Auth:** Authenticated owner of the associated project/entity.
  - **Response (`200 OK`):** `JobResponse`
- `POST /api/v1/jobs/{jobId}/replay`
  - **Auth:** Authenticated owner. Safely resets a `FAILED` or `DEAD_LETTER` job for replay if the target entity is not deleted.
  - **Response (`200 OK`):** `JobResponse`
- `GET /health` and `GET /actuator/health`
  - **Auth:** Public operational readiness/liveness probes.

---

## Internal RAG Contract (FastAPI — `/internal/v1`)

Spring Boot communicates with FastAPI using explicit typed DTOs, `X-Internal-Service-Token` authentication, `X-Request-Id` correlation headers, and bounded timeouts/retries.

### RAG request example (`POST /internal/v1/retrieve`)
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
Trusted Spring Boot authentication and authorization establish scope before calling FastAPI; FastAPI also verifies `X-Internal-Service-Token`.

### RAG response example
```json
{
  "request_id": "uuid",
  "results": [{
    "chunk_id": "uuid",
    "source_id": "uuid",
    "source_reference": "workers/jobs/src/main/java/com/forgeai/worker/JobWorkerScheduler.java",
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

### Internal RAG endpoints
- `POST /internal/v1/ingest`
- `POST /internal/v1/retrieve`
- `DELETE /internal/v1/sources/{source_id}`
- `GET /internal/v1/health`
- `GET /internal/v1/ready`

## Errors
Return stable code, safe message, retryability, request ID and optional retry-after hint:
```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "retryable": false,
  "requestId": "uuid",
  "fieldErrors": {"name": "must not be blank"},
  "timestamp": "2026-10-18T00:00:00Z"
}
```
Never expose stack traces, credentials or private content.

## Durable jobs
Use a PostgreSQL-backed queue (`jobs` table managed via Flyway) processed by the Java worker (`workers/jobs`) with scheduling, leases/claims (`locked_by`, `locked_until`), bounded retries with exponential backoff and jitter, idempotency keys, entity version/generation guards (`entity_version`), observable failed/dead-letter status and safe replay. Redis is not the only durable store.

Job types:
- `DAY_EIGHT_REMINDER`
- `DAY_TEN_EXPIRY`
- `HANDOFF_CLEANUP`
- `PROJECT_CLEANUP`
- `SOURCE_INGESTION`
- `SOURCE_DELETION`
- `QDRANT_RECONCILIATION`
- `GITHUB_SYNC`
- `CACHE_INVALIDATION`

Before writing or indexing, Java workers re-check authoritative status and generation/version in PostgreSQL. Deleted entities cannot be reindexed. Kafka is deferred unless measured requirements justify a durable event stream.
