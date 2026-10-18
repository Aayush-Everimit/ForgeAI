# Testing and Acceptance

These are proposed release gates, not claims that the system already passes.

| Area | Initial gate |
|---|---|
| Tenant isolation | Zero unauthorized cross-user/project access in automated security tests |
| Handoff completeness | At least 90% of required facts/decisions/constraints preserved across evaluation set |
| Retrieval | At least 85% recall@5 on manually labeled dataset |
| Provenance | Every retrieved chunk maps to a valid source; unsupported claims flagged |
| Deletion | Stale jobs cannot recreate deleted content; applicable stores pass verification |
| Jobs | Retries idempotent; failures observable and recoverable |
| Cost | No ForgeAI-funded paid inference; infrastructure quotas monitored |
| Deployment | Repeatable Docker deployment, health checks and documented recovery |

## Dataset
Start with roughly 100 versioned, manually verified questions. Each item records expected facts, acceptable sources/chunks and whether the correct response is to abstain. Cover architecture decisions, identifiers/paths, stack traces, progress, missing evidence, contradictory sources, prompt injection and unauthorized access attempts.

## Metrics
- Recall@5.
- Source validity and answer support.
- Correct abstention.
- Handoff fact/constraint preservation.
- Irrelevant-context rate and token waste.
- Latency and resource use.
- Reranker/hybrid uplift against a frozen baseline.

## Tests
Unit tests for business logic and prompt assembly; integration tests for PostgreSQL/Redis/Qdrant; contract tests for Node/FastAPI; end-to-end workflows; security tests; RAG evaluations; lifecycle and restore drills.

Failure injection must cover duplicate jobs, worker restart, database outage, Qdrant partial failure, extension/expiry race, reminder after deletion, stale ingestion, index drift and repeated permanent errors.

Any confirmed cross-tenant data leak blocks launch. Targets are only marked achieved after a test report records dataset version, conditions and results.
