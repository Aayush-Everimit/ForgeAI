import hashlib
import os
import re
from typing import Any
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field

app = FastAPI(title="ForgeAI RAG")

EMBEDDING_MODEL = "BAAI/bge-small-en-v1.5"
EMBEDDING_REVISION = "v1.5-local-baseline"

# Derived in-memory vector/keyword index keyed by source_id for local/dev execution when Qdrant is not attached
_INDEXED_CHUNKS: dict[str, list[dict[str, Any]]] = {}


def verify_internal_service_token(
    x_internal_service_token: str | None = Header(default=None, alias="X-Internal-Service-Token"),
) -> None:
    expected = os.environ.get("RAG_INTERNAL_TOKEN", "local-dev-rag-internal-token")
    if not x_internal_service_token or x_internal_service_token != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "UNAUTHORIZED_INTERNAL_CALL",
                "message": "Valid X-Internal-Service-Token header is required",
                "retryable": False,
            },
        )


class IngestRequest(BaseModel):
    request_id: str
    project_id: UUID
    source_id: UUID
    source_type: str
    source_reference: str
    source_version: int = Field(ge=1)
    content_hash: str
    content: str = Field(min_length=1, max_length=200000)


class IngestedChunkRecord(BaseModel):
    chunk_id: str
    vector_id: str
    chunk_hash: str
    embedding_model: str
    embedding_revision: str
    estimated_tokens: int


class IngestResponse(BaseModel):
    request_id: str
    source_id: UUID
    source_version: int
    status: str
    chunks: list[IngestedChunkRecord]


class RetrieveFilters(BaseModel):
    approved_only: bool = True
    exclude_deleted: bool = True


class RetrieveRequest(BaseModel):
    request_id: str
    project_id: UUID
    authorized_user_id: UUID
    query: str = Field(min_length=1, max_length=4000)
    task_type: str = "general"
    allowed_source_ids: list[UUID] = Field(default_factory=list)
    top_k: int = Field(default=5, ge=1, le=20)
    context_token_budget: int = Field(default=6000, ge=100, le=32000)
    filters: RetrieveFilters = Field(default_factory=RetrieveFilters)


class RetrievedChunk(BaseModel):
    chunk_id: str
    source_id: UUID
    source_reference: str
    content: str
    score: float
    score_type: str
    content_hash: str
    estimated_tokens: int


class RetrieveResponse(BaseModel):
    request_id: str
    results: list[RetrievedChunk]
    warnings: list[str]
    retrieval_status: str


class DeleteSourceResponse(BaseModel):
    request_id: str
    source_id: UUID
    deleted: bool


def _structure_aware_chunk(content: str, max_chars: int = 800) -> list[str]:
    """Splits document/code preserving markdown headings, classes, or double-newline blocks."""
    raw_sections = re.split(r"\n(?=#{1,4}\s|class\s|def\s|public\s|function\s)|\n\n+", content.strip())
    chunks: list[str] = []
    current = ""
    for section in raw_sections:
        part = section.strip()
        if not part:
            continue
        if len(current) + len(part) + 2 <= max_chars:
            current = f"{current}\n\n{part}".strip() if current else part
        else:
            if current:
                chunks.append(current)
            if len(part) <= max_chars:
                current = part
            else:
                for i in range(0, len(part), max_chars):
                    chunks.append(part[i : i + max_chars])
                current = ""
    if current:
        chunks.append(current)
    return chunks or [content.strip()]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "rag"}


@app.get("/internal/v1/health")
def internal_health() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "rag",
        "details": {"embedding_model": EMBEDDING_MODEL, "embedding_revision": EMBEDDING_REVISION},
    }


@app.get("/internal/v1/ready", dependencies=[Depends(verify_internal_service_token)])
def internal_ready() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "rag",
        "details": {"indexed_sources": len(_INDEXED_CHUNKS), "embedding_model": EMBEDDING_MODEL},
    }


@app.post(
    "/internal/v1/ingest",
    response_model=IngestResponse,
    dependencies=[Depends(verify_internal_service_token)],
)
def ingest_source(req: IngestRequest) -> IngestResponse:
    pieces = _structure_aware_chunk(req.content)
    records: list[IngestedChunkRecord] = []
    stored_entries: list[dict[str, Any]] = []

    for idx, text in enumerate(pieces):
        chunk_hash = "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()
        chunk_id = str(uuid4())
        vector_id = f"vec-{req.source_id}-{req.source_version}-{idx}"
        est_tokens = max(1, len(text) // 4)

        records.append(
            IngestedChunkRecord(
                chunk_id=chunk_id,
                vector_id=vector_id,
                chunk_hash=chunk_hash,
                embedding_model=EMBEDDING_MODEL,
                embedding_revision=EMBEDDING_REVISION,
                estimated_tokens=est_tokens,
            )
        )
        stored_entries.append(
            {
                "chunk_id": chunk_id,
                "project_id": str(req.project_id),
                "source_id": req.source_id,
                "source_reference": req.source_reference,
                "source_version": req.source_version,
                "content": text,
                "content_hash": chunk_hash,
                "estimated_tokens": est_tokens,
            }
        )

    _INDEXED_CHUNKS[str(req.source_id)] = stored_entries
    return IngestResponse(
        request_id=req.request_id,
        source_id=req.source_id,
        source_version=req.source_version,
        status="ok",
        chunks=records,
    )


@app.post(
    "/internal/v1/retrieve",
    response_model=RetrieveResponse,
    dependencies=[Depends(verify_internal_service_token)],
)
def retrieve_chunks(req: RetrieveRequest) -> RetrieveResponse:
    allowed_ids = {str(sid) for sid in req.allowed_source_ids}
    query_terms = {t.lower() for t in re.findall(r"[A-Za-z0-9_./-]+", req.query) if len(t) > 1}

    candidates: list[RetrievedChunk] = []
    for source_id_str, entries in _INDEXED_CHUNKS.items():
        if allowed_ids and source_id_str not in allowed_ids:
            continue
        for entry in entries:
            if entry["project_id"] != str(req.project_id):
                continue
            text_lower = entry["content"].lower()
            ref_lower = entry["source_reference"].lower()
            matches = sum(1 for term in query_terms if term in text_lower or term in ref_lower)
            score = round(min(0.99, 0.35 + (matches / max(1, len(query_terms))) * 0.60), 4)
            candidates.append(
                RetrievedChunk(
                    chunk_id=entry["chunk_id"],
                    source_id=entry["source_id"],
                    source_reference=entry["source_reference"],
                    content=entry["content"],
                    score=score,
                    score_type="hybrid",
                    content_hash=entry["content_hash"],
                    estimated_tokens=entry["estimated_tokens"],
                )
            )

    candidates.sort(key=lambda c: c.score, reverse=True)
    budgeted: list[RetrievedChunk] = []
    used_tokens = 0
    for c in candidates[: req.top_k]:
        if used_tokens + c.estimated_tokens > req.context_token_budget:
            continue
        budgeted.append(c)
        used_tokens += c.estimated_tokens

    warnings: list[str] = []
    if not budgeted:
        warnings.append("WEAK_EVIDENCE: No indexed chunks matched within the authorized project scope.")

    return RetrieveResponse(
        request_id=req.request_id,
        results=budgeted,
        warnings=warnings,
        retrieval_status="ok",
    )


@app.delete(
    "/internal/v1/sources/{source_id}",
    response_model=DeleteSourceResponse,
    dependencies=[Depends(verify_internal_service_token)],
)
def delete_source(
    source_id: UUID,
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> DeleteSourceResponse:
    existed = _INDEXED_CHUNKS.pop(str(source_id), None) is not None
    return DeleteSourceResponse(
        request_id=x_request_id or str(uuid4()),
        source_id=source_id,
        deleted=existed,
    )


def main() -> None:
    import uvicorn

    port = int(os.environ.get("RAG_PORT", "8000"))
    uvicorn.run("app.main:app", host="0.0.0.0", port=port, reload=True)


if __name__ == "__main__":
    main()
