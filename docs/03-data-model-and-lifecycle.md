# Data Model and Lifecycle

## Authoritative data
PostgreSQL stores users, projects, knowledge items, handoffs, source documents, provider credentials ciphertext, jobs, deletion state and minimal audit events. Qdrant stores derived vectors. Redis stores sessions and ephemeral data.

## Core entities
**User:** id, GitHub identity, display name, status, created_at, deleted_at.
**Project:** id, owner_user_id, name, description, stack metadata, timestamps, deleted_at.
**KnowledgeItem:** id, project_id, kind, content, status, source_type/reference, content_hash, approval metadata, timestamps, supersedes_id, deleted_at.
**Handoff:** id, project_id, created_by, task, prompt/version, status, created_at, expires_at, extended_at, deleted_at, version.
**SourceDocument:** id, project_id, source_type, source reference, content_hash, source_version, ingestion_status, last_indexed_at, deleted_at.
**ChunkIndexRecord:** id, project_id, source_id, chunk_hash, embedding model/revision, vector_id, index status.
**ProviderCredential:** id, user_id, provider, ciphertext, wrapped DEK, KEK key ID, timestamps and revocation state.
**Job:** id, type, entity type/id, generation guard, status, attempt count, run_after, lease, safe error code, timestamps.
**AuditEvent:** minimal security/operational metadata; never store API keys or unnecessary prompt bodies.

## Data rules
- Every project-scoped query must enforce authorization.
- Use foreign keys and explicit deletion rules; avoid accidental cascade deletion of persistent knowledge.
- Index project IDs, foreign keys, status and expiry.
- Use UTC timestamps and server-side expiry.
- Version approved knowledge; do not silently overwrite decisions.
- Qdrant payload should include project/source/chunk IDs, content hash, source version and embedding revision.
- Validate retrieval against PostgreSQL; vector filters alone are not sufficient authorization.

## Temporary handoffs
Day 0: create with expiry at ten days.
Day 8: remind only if active, not deleted and not already extended.
Day 10: expire unextended handoff and begin cleanup.
Extension updates expiry and invalidates stale jobs through generation/version checks.
Explicit deletion starts cleanup immediately.

## Persistent knowledge
Approved project knowledge remains until the item, project or account is deleted under the applicable policy. Handoff expiry must not erase durable knowledge.

## Deletion correctness
Authoritative deletion state first; idempotent cleanup; stale-job guards; Qdrant deletion; cache invalidation; retries; reconciliation; completion verification. Explain backup retention separately.

## Recovery baseline
Proposed seven-day backup retention, RPO up to 24 hours and RTO up to eight hours, subject to provider-plan verification and successful restore drills.
