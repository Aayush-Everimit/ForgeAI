# Product Requirements Document

## Vision
ForgeAI reduces the repeated manual work of explaining a software project when a developer changes AI assistants or starts a new task. It combines persistent approved project context, source-grounded retrieval and portable task-specific handoffs.

## Target user
Solo developers using multiple AI coding assistants. Team collaboration and autonomous repository-writing are outside Release 0.

## Release 0: include
- GitHub OAuth and isolated user accounts.
- Project management and manual notes, progress, decisions, constraints and known issues.
- AI-generated summaries and portable handoff prompts.
- Review/edit/approve/reject for proposed persistent knowledge.
- Dynamic OpenRouter catalogue, BYOK, model selection and controlled visible fallback.
- Advanced RAG with local embeddings, Qdrant, source attribution and evaluation.
- Optional GitHub App for explicitly selected repositories, read-only permissions only.
- Ten-day handoff expiry, day-eight reminder, explicit deletion and cleanup reconciliation.
- Docker-based deployment, basic monitoring and tested recovery procedures.

## Release 1 or later
- Team collaboration.
- Paid retention plans and billing.
- Autonomous code modification, repository writes and PR creation.
- Complex multi-agent orchestration.
- Additional direct provider adapters.
- Kafka or Kubernetes unless measured requirements justify them.

## Functional requirements
FR-01 GitHub OAuth sign-in.
FR-02 Create, update and delete projects.
FR-03 Store project stack, architecture, progress, decisions, constraints and known issues.
FR-04 Generate task-specific handoffs from authorized project context.
FR-05 Retrieve relevant evidence with source provenance.
FR-06 Review, edit, approve or reject proposed persistent knowledge.
FR-07 Discover available OpenRouter models dynamically.
FR-08 Store user-provided provider keys encrypted.
FR-09 Validate model capabilities and disclose fallback.
FR-10 Allow selected repository access through a separate GitHub App.
FR-11 Keep GitHub access read-only in Release 0.
FR-12 Expire temporary handoffs after ten days unless extended.
FR-13 Offer extension on day eight to eligible active handoffs.
FR-14 Support explicit handoff and project deletion.
FR-15 Reconcile deletion across PostgreSQL, Qdrant, cache and queued work.
FR-16 Produce portable prompts usable outside ForgeAI.
FR-17 Surface missing/conflicting context instead of inventing facts.
FR-18 Expose basic health, errors, job failures and quota warnings.

## Non-functional requirements
Security: no cross-user/project access; secrets protected and redacted.
Reliability: PostgreSQL authoritative; idempotent jobs; repairable vector index.
Maintainability: versioned APIs, migrations, locked dependencies.
Portability: Docker and portable PostgreSQL schema.
Cost: no ForgeAI-funded inference in Release 0; infrastructure usage monitored.
Usability: users can inspect and edit generated handoffs and proposed knowledge.
Quality: retrieval and handoff metrics measured on versioned datasets.

## Differentiation
ForgeAI does not claim routing, RAG or project management are individually novel. The intended value is their integration around persistent approved engineering context and portable, source-grounded handoffs.
