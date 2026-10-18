# Deployment and Operations

## Budget
Target zero upfront infrastructure spending. Use local Docker and suitable free tiers, then upgrade when measured needs exceed safe capacity. Verify free-tier limits and terms before choosing a provider.

## Initial deployment
- Next.js: free hosting tier supporting required behavior.
- Node API and worker: suitable free container hosting; combine temporarily only if job lifecycle/reliability remains sound.
- FastAPI RAG: local Docker during development; suitable free compute if available.
- Neon PostgreSQL: free tier if storage, connections and recovery capabilities fit.
- Redis: suitable free tier, otherwise reassess which Redis features are necessary.
- Qdrant: local Docker for development; persistent storage for public production.
- Embeddings: local BGE-small.
- Monitoring: free-tier error reporting and basic health/quota monitoring.

## Docker
Provide Dockerfiles for web/API/worker/RAG and Compose for local development. Keep secrets out of images. Use locked dependencies, health/readiness checks, non-root containers where practical and persistent Qdrant storage when needed.

## Monitoring
Track API errors/latency, retrieval latency, job queue depth/age/retries/failures, DB saturation, Qdrant reconciliation, expiry/deletion status, quotas and resource use. Avoid logging secrets and unnecessary user content.

## Backup/recovery
Proposed seven-day retention, RPO up to 24 hours, RTO up to eight hours. Verify provider support and test restore in an isolated environment before launch. Validate relational data, encrypted credentials, configuration and Qdrant rebuild/reconciliation. If free tiers cannot meet targets, document the limitation and implement an alternative or narrow the promise.

## Launch checklist
- [ ] Auth and authorization verified.
- [ ] Internal services protected.
- [ ] Secrets injected securely.
- [ ] Migrations tested.
- [ ] Qdrant persistence verified.
- [ ] Restore test passed.
- [ ] Job retry/stale-job tests passed.
- [ ] Monitoring and quota alerts configured.
- [ ] Deletion verified across applicable stores.
- [ ] Free-tier limits reviewed.
