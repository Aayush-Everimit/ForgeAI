# ForgeAI

ForgeAI is a persistent engineering workspace for solo developers. It stores structured, approved project context, performs source-grounded RAG retrieval, and generates portable five-part handoff prompts across AI coding assistants.

## Target Architecture (Release 0)

| Service / Module | Path | Stack | Responsibility |
|---|---|---|---|
| **Web Frontend** | `apps/web` | Next.js 16 + TypeScript | UI, project management, knowledge review, BYOK settings, handoff editor (`:3000`). |
| **Main Product API** | `services/api` | Java 21 LTS + Spring Boot 3.4.3 (Maven) | GitHub OAuth2, Spring Security, tenant isolation, project/knowledge/handoff CRUD, prompt assembly, BYOK encryption (`:4000`). |
| **Shared Backend Library** | `packages/shared` | Java 21 LTS + Spring Data JPA + Flyway | Authoritative PostgreSQL entities, repositories, Flyway SQL migrations, AES-256-GCM envelope encryption, FastAPI RAG HTTP client. |
| **Background Worker** | `workers/jobs` | Java 21 LTS + Spring Boot 3.4.3 (Maven) | Durable PostgreSQL-backed job processor with leases, exponential backoff + jitter, stale-job recovery, and generation guards (`:4001`). |
| **AI / RAG Service** | `services/rag` | Python 3.12 + FastAPI | Document parsing, structure-aware chunking, local BGE-small embeddings, hybrid retrieval (`:8000`). |
| **Data Stores** | `infra/docker-compose.yml` | PostgreSQL 16, Qdrant, Redis | PostgreSQL is authoritative; Qdrant stores derived vector chunks; Redis supports sessions, cache, and rate limiting. |

Architecture diagrams are located in `diagrams/` and detailed engineering specifications in `docs/`.

---

## Local Development Quickstart

### Prerequisites
- **JDK 21 LTS** (e.g., Eclipse Temurin 21) and **Apache Maven 3.9+**
- **Node.js 20+** and **npm** (for `apps/web` Next.js frontend)
- **Python 3.12+** (for `services/rag` FastAPI service)
- **Docker & Docker Compose** (optional, for full containerized stack)

### 1. Environment Configuration
Copy `.env.example` to `.env` and populate local values:
```bash
cp .env.example .env
```
Never commit `.env` or real API keys to Git.

### 2. Build & Test Java Backend (API, Shared, Worker)
From the `ForgeAI/` root directory:
```bash
# Compile, run Flyway + H2/PostgreSQL integration & security tests, and package jars
mvn clean test package

# Start the Spring Boot Main API (port 4000)
mvn -pl services/api spring-boot:run

# Start the Java Durable Background Worker (port 4001)
mvn -pl workers/jobs spring-boot:run
```

### 3. Run Python FastAPI RAG Service
```bash
cd services/rag
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

### 4. Run Next.js Frontend
```bash
cd apps/web
npm install
npm run dev
```

### 5. Run with Docker Compose
```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml up --build
```

---

## Database Migrations (Flyway)

- Versioned SQL migrations live in `packages/shared/src/main/resources/db/migration/V*.sql`.
- Flyway runs automatically when `services/api` or `workers/jobs` starts (`spring.flyway.enabled=true`).
- Hibernate schema auto-update is set to `validate` (`spring.jpa.hibernate.ddl-auto=validate`) so all schema modifications must go through forward Flyway migrations.

---

## Troubleshooting

- **Database Connection Refused on Startup:** Ensure PostgreSQL is running and reachable at `DATABASE_URL` (`jdbc:postgresql://localhost:5432/forgeai`), or start PostgreSQL via `docker compose -f infra/docker-compose.yml up -d postgres`.
- **403 Forbidden on POST/PATCH/DELETE to `/api/v1/**`:** Spring Security enforces CSRF protection via `XSRF-TOKEN` cookies (`X-XSRF-TOKEN` header). Call `GET /api/v1/auth/me` first with `credentials: "include"` so the CSRF cookie is issued.
- **RAG Retrieval Warning (`RAG_SERVICE_UNAVAILABLE`):** Verify `services/rag` is running on `RAG_BASE_URL` and that `RAG_INTERNAL_TOKEN` matches between the Spring Boot services and FastAPI.
