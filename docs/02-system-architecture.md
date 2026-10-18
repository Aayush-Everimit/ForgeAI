# System Architecture

## Principles
1. PostgreSQL is authoritative.
2. Node.js owns product workflows, authentication, authorization and final prompt assembly.
3. FastAPI owns computational AI/RAG workloads, not primary authorization.
4. Qdrant is a derived vector index.
5. Redis is for sessions/cache/rate limiting, not the only durable job store.
6. All service calls are authenticated and scoped.
7. Free-first must not compromise data safety.
8. Kafka and Kubernetes are conditional, not defaults.

## Components
- Web: Next.js + TypeScript.
- Main API: Node.js + TypeScript.
- Worker: separate Node.js process for scheduled/retryable work.
- RAG: Python FastAPI for parsing, chunking, embeddings and retrieval.
- Neon PostgreSQL: users, projects, approved knowledge, handoffs, credentials ciphertext, jobs and lifecycle state.
- Redis: server-side sessions, cache, rate limits and short-lived coordination.
- Qdrant: vectors and retrieval metadata.
- OpenRouter: model catalogue and inference gateway using user's key.
- GitHub OAuth: sign-in.
- GitHub App: separately authorized selected-repository read-only access.

## Handoff flow
1. Client submits task to Node API.
2. Node authenticates session and authorizes project.
3. Node obtains approved context and sends scoped retrieval request to FastAPI.
4. FastAPI validates service authentication and scope, queries Qdrant and returns ranked chunks with source metadata.
5. Node validates source state and assembles prompt.
6. User reviews/edits handoff. Model inference occurs only when explicitly requested.
7. PostgreSQL records handoff and expiry state.

## Ingestion flow
Register source in PostgreSQL → durable job → parse and structure-aware chunk → local embedding → Qdrant upsert → record index version → reconcile partial failure.

## Deletion flow
Mark deletion state in PostgreSQL → prevent stale jobs → remove source and derived vectors/cache → retry failures → verify/reconcile → retain only minimal audit data. Backups follow a separate expiry policy.

## Trust boundaries
Browser input is untrusted. Project IDs do not prove authorization. Retrieved repository content is untrusted evidence and cannot override system rules or grant tool permissions. FastAPI is not an open public retrieval endpoint.

## Deployment
Docker Compose for development. Production services may be split across hosts. Do not publicly expose Redis, Qdrant or internal FastAPI without strong access controls.

## Conditional infrastructure
Kafka only if measured event throughput/fan-out/streaming needs justify it. Kubernetes only if the selected hosting platform and operational needs require it. Reranking and query expansion stay only if evaluation demonstrates uplift.
