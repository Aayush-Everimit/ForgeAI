import asyncio
import json
import unittest
from typing import Any
from urllib.parse import urlencode
from uuid import uuid4

from app.main import app


def asgi_request(
    method: str,
    path: str,
    headers: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
) -> tuple[int, Any]:
    raw_body = json.dumps(body).encode("utf-8") if body is not None else b""
    hdrs = [(b"host", b"localhost")]
    if body is not None:
        hdrs.append((b"content-type", b"application/json"))
        hdrs.append((b"content-length", str(len(raw_body)).encode("ascii")))
    for k, v in (headers or {}).items():
        hdrs.append((k.lower().encode("ascii"), v.encode("utf-8")))

    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": method.upper(),
        "scheme": "http",
        "path": path,
        "raw_path": path.encode("ascii"),
        "query_string": b"",
        "headers": hdrs,
        "client": ("127.0.0.1", 12345),
        "server": ("localhost", 8000),
    }

    sent = False
    status_code = 500
    response_chunks: list[bytes] = []

    async def receive() -> dict[str, Any]:
        nonlocal sent
        if not sent:
            sent = True
            return {"type": "http.request", "body": raw_body, "more_body": False}
        return {"type": "http.disconnect"}

    async def send(message: dict[str, Any]) -> None:
        nonlocal status_code
        if message["type"] == "http.response.start":
            status_code = message["status"]
        elif message["type"] == "http.response.body":
            response_chunks.append(message.get("body", b""))

    asyncio.run(app(scope, receive, send))
    full_body = b"".join(response_chunks).decode("utf-8")
    return status_code, json.loads(full_body) if full_body else None


class TestRagInternalApi(unittest.TestCase):
    def setUp(self) -> None:
        self.headers = {
            "X-Internal-Service-Token": "local-dev-rag-internal-token",
            "X-Request-Id": "test-corr-id-1",
        }

    def test_public_and_internal_health(self) -> None:
        code1, body1 = asgi_request("GET", "/health")
        self.assertEqual(code1, 200)
        self.assertEqual(body1["status"], "ok")

        code2, body2 = asgi_request("GET", "/internal/v1/health")
        self.assertEqual(code2, 200)
        self.assertEqual(
            body2["details"]["embedding_model"], "BAAI/bge-small-en-v1.5"
        )

    def test_internal_endpoints_reject_missing_or_invalid_service_token(self) -> None:
        code1, _ = asgi_request("GET", "/internal/v1/ready")
        self.assertEqual(code1, 401)

        code2, _ = asgi_request(
            "POST",
            "/internal/v1/retrieve",
            headers={"X-Internal-Service-Token": "wrong-token"},
            body={},
        )
        self.assertEqual(code2, 401)

    def test_ingest_retrieve_and_delete_lifecycle(self) -> None:
        project_id = str(uuid4())
        source_id = str(uuid4())
        user_id = str(uuid4())

        code_ing, ingest_body = asgi_request(
            "POST",
            "/internal/v1/ingest",
            headers=self.headers,
            body={
                "request_id": "req-ingest-1",
                "project_id": project_id,
                "source_id": source_id,
                "source_type": "DOCUMENT",
                "source_reference": "workers/jobs/src/main/java/com/forgeai/worker/DurableJobWorkerService.java",
                "source_version": 1,
                "content_hash": "sha256:doc1",
                "content": "# Retry Policy\nWorkers use bounded retries with exponential backoff and jitter.",
            },
        )
        self.assertEqual(code_ing, 200)
        self.assertEqual(ingest_body["status"], "ok")
        self.assertGreaterEqual(len(ingest_body["chunks"]), 1)

        code_ret, retrieve_body = asgi_request(
            "POST",
            "/internal/v1/retrieve",
            headers=self.headers,
            body={
                "request_id": "req-retrieve-1",
                "project_id": project_id,
                "authorized_user_id": user_id,
                "query": "exponential backoff retry policy",
                "task_type": "debugging",
                "allowed_source_ids": [source_id],
                "top_k": 5,
                "context_token_budget": 4000,
                "filters": {"approved_only": True, "exclude_deleted": True},
            },
        )
        self.assertEqual(code_ret, 200)
        self.assertEqual(retrieve_body["retrieval_status"], "ok")
        self.assertEqual(len(retrieve_body["results"]), 1)
        self.assertEqual(retrieve_body["results"][0]["source_id"], source_id)

        # Cross-project isolation check
        code_other, other_body = asgi_request(
            "POST",
            "/internal/v1/retrieve",
            headers=self.headers,
            body={
                "request_id": "req-retrieve-cross-tenant",
                "project_id": str(uuid4()),
                "authorized_user_id": user_id,
                "query": "exponential backoff retry policy",
                "task_type": "debugging",
                "allowed_source_ids": [source_id],
                "top_k": 5,
                "context_token_budget": 4000,
            },
        )
        self.assertEqual(code_other, 200)
        self.assertEqual(other_body["results"], [])

        # Delete source
        code_del, del_body = asgi_request(
            "DELETE",
            f"/internal/v1/sources/{source_id}",
            headers=self.headers,
        )
        self.assertEqual(code_del, 200)
        self.assertTrue(del_body["deleted"])

        # Verify deleted source is no longer returned
        code_after, after_body = asgi_request(
            "POST",
            "/internal/v1/retrieve",
            headers=self.headers,
            body={
                "request_id": "req-retrieve-after-del",
                "project_id": project_id,
                "authorized_user_id": user_id,
                "query": "exponential backoff retry policy",
                "allowed_source_ids": [source_id],
            },
        )
        self.assertEqual(code_after, 200)
        self.assertEqual(after_body["results"], [])


if __name__ == "__main__":
    unittest.main()
