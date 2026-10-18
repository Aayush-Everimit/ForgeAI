# Implementation Roadmap

## Phase 0: Foundation
Monorepo, conventions, Docker Compose, CI, lint/type checks, environment templates and decision docs.
**Exit:** clean local setup works from documentation.

## Phase 1: Identity and durable data
GitHub OAuth, sessions, PostgreSQL migrations, project authorization, rate limits and safe errors.
**Exit:** tenant-isolation tests pass.

## Phase 2: Context and handoffs
Project notes, approved knowledge workflow, handoff generation/versioning, canonical prompt framework, editing and export.
**Exit:** manually reviewed handoffs preserve required facts.

## Phase 3: OpenRouter and BYOK
Credential encryption, dynamic catalogue, capability checks, model selection, visible fallback and safe provider errors.
**Exit:** keys remain private and fallback respects user constraints.

## Phase 4: RAG
FastAPI contracts, parsing, structure-aware chunking, BGE-small, Qdrant, vector + keyword retrieval, source validation and evaluation. Add reranking only if proven useful.
**Exit:** reproducible evaluation and source attribution.

## Phase 5: GitHub read-only context
GitHub App, selected repos, least privilege, ingestion, revocation and injection tests.
**Exit:** repository access is scoped and read-only.

## Phase 6: Jobs and retention
PostgreSQL-backed worker, day-eight reminder, day-ten expiry, deletion, cache invalidation, Qdrant cleanup and reconciliation.
**Exit:** failure-injection and deletion tests pass.

## Phase 7: Production hardening
Choose free tiers based on real limits, monitoring, quota alerts, backup restoration, security review and acceptance testing.
**Exit:** gates met or documented exceptions approved.

## Phase 8: Release 1 discovery
Use real Release 0 evidence before team features, paid retention, autonomous changes, extra providers or event-streaming infrastructure.
