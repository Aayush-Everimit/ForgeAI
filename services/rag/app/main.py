import os

from fastapi import FastAPI

app = FastAPI(title="ForgeAI RAG")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "rag"}


def main() -> None:
    import uvicorn

    port = int(os.environ.get("RAG_PORT", "8000"))
    uvicorn.run("app.main:app", host="0.0.0.0", port=port, reload=True)


if __name__ == "__main__":
    main()
