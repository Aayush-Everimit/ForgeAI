# Deployment and Operations

## Budget
Target zero upfront infrastructure spending. Use local Docker and suitable free tiers, then upgrade when measured needs exceed safe capacity. Verify free-tier limits and terms before choosing a provider.

## Initial deployment
- Next.js (`apps/web`): free hosting tier supporting required behavior.
- Spring Boot API (`services/api`) and Java worker (`workers/jobs`): suitable free container hosting running Java 21 LTS; combine temporarily via Spring profiles only if job lifecycle/reliability remains sound.
- FastAPI RAG (`services/rag`): local Docker during development; suitable free compute if available.
- Neon PostgreSQL: free tier if storage, connections and recovery capabilities fit; schema managed via Flyway migrations.
- Redis: suitable free tier, otherwise reassess which Redis features are necessary.
- Qdrant: local Docker for development; persistent storage for public production.
- Embeddings: local BGE-small.
- Monitoring: Spring Boot Actuator health/metrics endpoints, free-tier error reporting and basic health/quota monitoring.

## Build and local development commands
- **Java API & Worker (Maven multi-module from `ForgeAI/`):**
  - Compile and package: `mvn clean package`
  - Run tests: `mvn test`
  - Start Spring Boot API locally: `mvn -pl services/api spring-boot:run`
  - Start Java background worker locally: `mvn -pl workers/jobs spring-boot:run`
- **Database migrations (Flyway):**
  - Versioned SQL migrations live in `packages/shared/src/main/resources/db/migration/V*.sql`.
  - Flyway runs automatically on API/worker startup (`spring.flyway.enabled=true`) and Hibernate schema auto-generation is disabled in production (`spring.jpa.hibernate.ddl-auto=validate`).
- **RAG Service (FastAPI from `ForgeAI/services/rag`):**
  - Run locally: `uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload`
  - Run tests: `pytest`
- **Web Frontend (Next.js from `ForgeAI/apps/web`):**
  - Run locally: `npm run dev`
  - Typecheck & build: `npm run lint && npm run build`

## Docker
Provide multi-stage Dockerfiles for web (`apps/web/Dockerfile`), Spring Boot API (`services/api/Dockerfile`), Java worker (`workers/jobs/Dockerfile`) and FastAPI RAG (`services/rag/Dockerfile`), plus `infra/docker-compose.yml` for local development (including PostgreSQL, Redis and Qdrant). Keep secrets out of images. Use locked dependencies, health/readiness checks, non-root containers where practical and persistent Qdrant storage when needed.

## Monitoring
Track API errors/latency, retrieval latency, job queue depth/age/retries/failures, DB saturation, Qdrant reconciliation, expiry/deletion status, quotas and resource use. Avoid logging secrets and unnecessary user content.

## Backup/recovery
Proposed seven-day retention, RPO up to 24 hours, RTO up to eight hours. Verify provider support and test restore in an isolated environment before launch. Validate relational data, encrypted credentials, configuration and Qdrant rebuild/reconciliation. If free tiers cannot meet targets, document the limitation and implement an alternative or narrow the promise.

## Launch checklist
- [ ] Auth and authorization verified.
- [ ] Internal services protected.
- [ ] Secrets injected securely.
- [ ] Flyway migrations tested.
- [ ] Qdrant persistence verified.
- [ ] Restore test passed.
- [ ] Job retry/stale-job tests passed.
- [ ] Monitoring and quota alerts configured.
- [ ] Deletion verified across applicable stores.
- [ ] Free-tier limits reviewed.
