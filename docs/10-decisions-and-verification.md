# Architecture Decisions and Verification Register

## Approved decisions
- Frontend: Next.js + TypeScript (`apps/web`).
- Main backend: Java 21 LTS + Spring Boot 3.4.x built with Maven (`services/api`). Selected for strong compile-time type safety, mature Spring Security OAuth2/session/CSRF controls, transactional integrity with Spring Data JPA + JDBC, and versioned database migrations with Flyway.
- Shared backend domain & persistence library: Java 21 Maven module (`packages/shared`) shared between the Spring Boot API and Java background worker to eliminate duplicate business rules.
- AI/RAG workloads: separate Python FastAPI service (`services/rag`).
- Relational source of truth: Neon PostgreSQL (or compatible PostgreSQL 16+), managed via Flyway migrations, subject to free-tier/backup verification.
- Vector index: Qdrant, derived from authoritative PostgreSQL records.
- Redis: server-side sessions, cache, rate limiting and short-lived coordination.
- Durable jobs: PostgreSQL-backed queue (`jobs` table with leases, exponential backoff + jitter, and generation/version guards) plus separate Java Spring Boot worker (`workers/jobs`).
- Kafka: deferred unless measured throughput, fan-out or streaming requirements justify it.
- Deployment: Docker-first, Docker Compose locally; simple container hosting in production; no Kubernetes requirement.
- AI gateway: OpenRouter-first with full available catalogue, dynamic metadata and user-provided keys.
- Embeddings: `BAAI/bge-small-en-v1.5` local baseline, benchmark before production.
- RAG: structure-aware chunking, vector + keyword retrieval, source validation, token budgeting; reranking only if evaluation improves.
- Prompt framework: Role; Task; Context; Constraints and instructions; Output and success criteria.
- MCPs: relevant recommendations only; installation/authorization is environment-dependent.
- Auth: GitHub OAuth sign-in via Spring Security; separate GitHub App for selected-repository read-only access.
- Secrets: AES-256-GCM envelope encryption (per-credential DEK wrapped with KEK) and KMS-backed KEK in production where available.
- Handoff lifecycle: ten-day default; day-eight reminder; day-ten expiry if not extended; explicit deletion.
- Persistent knowledge: retained until item/project/account deletion policy applies.
- Initial infrastructure target: zero upfront spend with free tiers/local Docker, upgraded only when justified.
- Release 0 excludes teams, billing, autonomous repo writes, PR creation, complex multi-agent orchestration and unnecessary infrastructure.

## Conditional decisions
- Kafka: only if measured workload requires event streaming or independent fan-out.
- Kubernetes: only if deployment complexity warrants it.
- Direct provider adapters: only if OpenRouter has a demonstrated gap.
- Reranker/query expansion: only if evaluation shows measurable uplift.
- Paid infrastructure: only when measured usage, reliability or security needs exceed free-tier capabilities.

## Verification register
| ID | Verify | Evidence required |
|---|---|---|
| V-01 | Neon free-tier compute, storage, pooling and backup capabilities | Current plan documentation and test |
| V-02 | Frontend free hosting compatibility | Successful deploy and limit review |
| V-03 | Java worker host sleep/restart and lease-recovery behavior | Long-running scheduling and restart tests |
| V-04 | FastAPI/BGE-small resource needs | Memory and latency benchmark |
| V-05 | Qdrant persistence | Restart/redeploy durability test |
| V-06 | OpenRouter catalogue metadata and model features | Live integration tests |
| V-07 | Per-model tool/JSON/streaming capabilities | Capability tests |
| V-08 | BGE-small quality on code/docs | Frozen evaluation set |
| V-09 | Hybrid retrieval/reranker uplift | Baseline comparison |
| V-10 | Seven-day backup feasibility | Plan check and restore test |
| V-11 | RPO 24h/RTO 8h feasibility | Timed recovery drill |
| V-12 | 24-hour deletion verification target | Failure-injection and reconciliation tests |
| V-13 | Quotas and concurrency limits | Load test against selected free tiers |
| V-14 | Public launch privacy/legal requirements | Review for applicable jurisdiction |

A provider/model failing a requirement triggers an alternative, fallback, scope limitation or documented exception. Unverified assumptions must not be presented as guarantees.
