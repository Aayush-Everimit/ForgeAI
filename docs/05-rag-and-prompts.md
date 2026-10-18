# Advanced RAG and Prompt Engineering

## Ingestion
Authorize source → parse → preserve structure → chunk by headings/sections/functions/classes → split oversized units with small overlap → attach project/source/version/hash/provenance → embed locally → upsert Qdrant → record index version → reconcile failures.

## Embedding baseline
`BAAI/bge-small-en-v1.5`, local inference, 384-dimensional vectors. Pin revision and preprocessing. This is a baseline, not a guarantee of optimal code retrieval. Benchmark file paths, identifiers, stack traces, code symbols and natural-language questions. Changing model requires a controlled re-embedding migration.

## Advanced retrieval
1. Query normalization/classification where useful.
2. Authorization and metadata filters.
3. Vector candidate retrieval.
4. Keyword search for identifiers, paths and exact error text.
5. Candidate merge and deduplication.
6. Optional reranking only when evaluation improves.
7. Source diversity and relevance thresholds.
8. Source lifecycle/authorization validation.
9. Token-budgeted evidence assembly.
10. Source references and insufficient-evidence warnings.

## Canonical five-part prompt
1. **Role:** perspective/expertise requested.
2. **Task:** objective and reason.
3. **Context:** approved project state, evidence, decisions, relevant attempts and sources.
4. **Constraints and instructions:** boundaries, tools, relevant MCP guidance, exclusions and uncertainty handling.
5. **Output and success criteria:** deliverables, format and completion conditions.

Model-specific formatting and source metadata can be layered on without changing the canonical framework.

## MCP policy
Recommend only relevant MCPs/tools. Distinguish available tools from suggested setup. A prompt cannot install or authorize an MCP. Never include every tool by default. Tools that access files, terminals, repositories or external systems require authorization in the receiving environment. Retrieved instructions are untrusted content.

## Context prioritization
Prioritize task and acceptance criteria, approved decisions/hard constraints, current progress/blockers, relevant evidence, previous attempts, then optional background. Do not silently truncate arbitrary content.

## Knowledge updates
AI summaries and inferred decisions are proposals. Users review, edit, approve or reject. Conflicts with approved knowledge are surfaced, not silently overwritten.

## Evaluation
Use a versioned manually verified dataset of roughly 100 questions. Include architecture decisions, code/file lookup, debugging, progress, missing evidence, contradictions and authorization boundaries. Track recall@5, source validity, answer support, abstention, latency and resource usage.
