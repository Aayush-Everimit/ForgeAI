# Security and Privacy

## Authentication
GitHub OAuth is Release 0 sign-in. Use secure state validation, session expiry, logout and revocation. Store only required identity data.

## GitHub access
OAuth sign-in does not grant repository access. Use a GitHub App for selected repositories and least-privilege read-only permissions. Respect installation removal and access revocation; do not continue syncing revoked sources.

## Tenant isolation
Authorize every project-scoped request across SQL, vector retrieval, cache, ingestion and jobs. Never trust client-supplied project IDs. Test negative access paths. Any confirmed cross-user disclosure blocks launch.

## Provider keys
Use envelope encryption and authenticated encryption through maintained libraries. Protect KEKs with managed KMS in production where available. Track key versions and support rotation. Never expose plaintext keys to browser storage, URLs, logs, prompts, exceptions or analytics. Never silently use operator-owned paid keys.

## Internal services
FastAPI is not a public unauthenticated retrieval endpoint. Authenticate service calls, validate scope, use private networking where available, TLS and rotated service credentials otherwise. Bound request size, rate and duration.

## Prompt injection
Repository content and retrieved chunks are untrusted data. They cannot override system instructions, grant permissions or authorize tools. Any future execution agent requires sandboxing, explicit scopes, approval boundaries and auditable actions.

## Logs and deletion
Minimize stored request/response content. Use correlation IDs and safe error codes. Deletion covers PostgreSQL, Qdrant, Redis/cache and queued derived work; use tombstones/version guards and verification. Backups expire under a separate documented policy.
