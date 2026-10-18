# ForgeAI Engineering Documentation
Version 1.0-draft | Architecture baseline approved; production verification pending.

ForgeAI is a persistent development workspace for solo developers. It stores structured, approved project context and generates source-grounded, portable handoffs for different AI coding assistants. The model is replaceable; project context is not.

## Documents
1. `01-product-requirements.md` — scope and requirements
2. `02-system-architecture.md` — components and flows
3. `03-data-model-and-lifecycle.md` — entities, retention and deletion
4. `04-api-and-jobs.md` — RAG API and durable jobs
5. `05-rag-and-prompts.md` — advanced RAG, embeddings, MCPs and prompt framework
6. `06-security.md` — authentication, authorization and secrets
7. `07-deployment-operations.md` — Docker, free-first deployment, recovery
8. `08-testing-acceptance.md` — evaluation and release gates
9. `09-roadmap.md` — incremental build plan
10. `10-decisions-and-verification.md` — ADRs and unresolved verification items

SVG diagrams are in `diagrams/`. Treat target metrics as proposed gates, not achieved results. Provider plans, backup capabilities, free-tier limits and model compatibility must be verified before production.
