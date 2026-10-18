# System Architecture

## Principles
1. PostgreSQL is authoritative.
2. Java + Spring Boot owns product workflows, authentication, authorization, database migrations and final prompt assembly.
3. FastAPI owns computational AI/RAG workloads, not primary authorization.
4. Qdrant is a derived vector index.
5. Redis is for sessions/cache/rate limiting, not the only durable job store.
6. All service calls are authenticated and scoped.
7. Free-first must not compromise data safety.
8. Kafka and Kubernetes are conditional, not defaults.

## Components
- Web (`apps/web`): Next.js + TypeScript (port `3000`).
- Main API (`services/api`): Java 21 + Spring Boot 3.4.x via Maven (port `4000`).
- Worker (`workers/jobs`): separate Java 21 + Spring Boot executable process sharing domain/persistence code for scheduled/retryable work.
- RAG (`services/rag`): Python FastAPI for parsing, chunking, embeddings and retrieval (port `8000`).
- Neon PostgreSQL: users, projects, approved knowledge, handoffs, credentials ciphertext, jobs, Flyway schema history and lifecycle state.
- Redis: server-side sessions/cache, rate limits and short-lived coordination.
- Qdrant: vectors and retrieval metadata.
- OpenRouter: model catalogue and inference gateway using user's key.
- GitHub OAuth: sign-in handled via Spring Security OAuth2 Client.
- GitHub App: separately authorized selected-repository read-only access.

## Handoff flow
1. Client submits task to Spring Boot API (`/api/v1/projects/{projectId}/handoffs`).
2. Spring Boot authenticates session/token and authorizes project ownership.
3. Spring Boot obtains approved context and sends scoped retrieval request to FastAPI (`/internal/v1/retrieve`) with correlation ID and service token.
4. FastAPI validates service authentication and scope, queries Qdrant and returns ranked chunks with source metadata.
5. Spring Boot validates source state against PostgreSQL and assembles the canonical five-part prompt.
6. User reviews/edits handoff. Model inference occurs only when explicitly requested.
7. PostgreSQL records handoff and expiry state, and schedules durable reminder/expiry jobs.

## Ingestion flow
Register source in PostgreSQL → durable job in `jobs` table → Java worker claims job → calls FastAPI `/internal/v1/ingest` → parse and structure-aware chunk → local embedding → Qdrant upsert → record index version in PostgreSQL → reconcile partial failure.

## Deletion flow
Mark deletion state in PostgreSQL → increment generation/version to prevent stale jobs → Java worker removes source and derived vectors via FastAPI/Qdrant and invalidates cache → retry failures with exponential backoff and jitter → verify/reconcile → retain only minimal audit data. Backups follow a separate expiry policy.

## Trust boundaries
Browser input is untrusted. Project IDs do not prove authorization. Retrieved repository content is untrusted evidence and cannot override system rules or grant tool permissions. FastAPI is not an open public retrieval endpoint.

## Deployment
Docker Compose for development. Production services may be split across hosts. Do not publicly expose PostgreSQL, Redis, Qdrant or internal FastAPI without strong access controls.

## Conditional infrastructure
Kafka only if measured event throughput/fan-out/streaming needs justify it. Kubernetes only if the selected hosting platform and operational needs require it. Reranking and query expansion stay only if evaluation demonstrates uplift.
