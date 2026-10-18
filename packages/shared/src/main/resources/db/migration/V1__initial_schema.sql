-- V1__initial_schema.sql
-- Authoritative PostgreSQL schema for ForgeAI (Release 0)

CREATE TABLE users (
    id UUID PRIMARY KEY,
    github_id VARCHAR(64) NOT NULL UNIQUE,
    github_login VARCHAR(120) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    avatar_url VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_users_github_id ON users(github_id);
CREATE INDEX idx_users_status ON users(status);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000),
    stack_metadata_json TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_projects_owner_user_id ON projects(owner_user_id);
CREATE INDEX idx_projects_deleted_at ON projects(deleted_at);

CREATE TABLE knowledge_items (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    kind VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_reference VARCHAR(500),
    content_hash VARCHAR(128) NOT NULL,
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMP WITH TIME ZONE,
    review_notes VARCHAR(1000),
    supersedes_id UUID REFERENCES knowledge_items(id),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_knowledge_project_status ON knowledge_items(project_id, status);
CREATE INDEX idx_knowledge_project_kind ON knowledge_items(project_id, kind);
CREATE INDEX idx_knowledge_supersedes ON knowledge_items(supersedes_id);

CREATE TABLE handoffs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    created_by UUID NOT NULL REFERENCES users(id),
    task VARCHAR(4000) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    role_name VARCHAR(120),
    target_model VARCHAR(160),
    prompt_body TEXT NOT NULL,
    warnings_json TEXT,
    sources_json TEXT,
    status VARCHAR(32) NOT NULL,
    reminder_sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    extended_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX idx_handoffs_project_status ON handoffs(project_id, status);
CREATE INDEX idx_handoffs_expires_at ON handoffs(expires_at);

CREATE TABLE source_documents (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    source_type VARCHAR(40) NOT NULL,
    source_reference VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    source_version BIGINT NOT NULL DEFAULT 1,
    ingestion_status VARCHAR(32) NOT NULL,
    last_indexed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_source_documents_project_id ON source_documents(project_id);
CREATE INDEX idx_source_documents_status ON source_documents(ingestion_status);

CREATE TABLE chunk_index_records (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    source_id UUID NOT NULL REFERENCES source_documents(id),
    chunk_hash VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    embedding_revision VARCHAR(64) NOT NULL,
    vector_id VARCHAR(128) NOT NULL,
    index_status VARCHAR(32) NOT NULL,
    source_version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_chunk_records_project_source ON chunk_index_records(project_id, source_id);
CREATE INDEX idx_chunk_records_vector_id ON chunk_index_records(vector_id);

CREATE TABLE provider_credentials (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(64) NOT NULL,
    key_hint VARCHAR(32) NOT NULL,
    ciphertext TEXT NOT NULL,
    wrapped_dek TEXT NOT NULL,
    kek_key_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_provider_credentials_user_provider ON provider_credentials(user_id, provider);

CREATE TABLE github_repository_bindings (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    installation_id VARCHAR(120) NOT NULL,
    repository_full_name VARCHAR(255) NOT NULL,
    default_branch VARCHAR(120) NOT NULL,
    access_mode VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_synced_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_github_bindings_project_id ON github_repository_bindings(project_id);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    project_id UUID REFERENCES projects(id),
    job_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    payload_json TEXT,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    run_after TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by VARCHAR(120),
    locked_until TIMESTAMP WITH TIME ZONE,
    safe_error_code VARCHAR(80),
    safe_error_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_jobs_status_run_after ON jobs(status, run_after);
CREATE INDEX idx_jobs_project_id ON jobs(project_id);
CREATE INDEX idx_jobs_entity ON jobs(entity_type, entity_id);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    project_id UUID,
    event_type VARCHAR(80) NOT NULL,
    entity_type VARCHAR(64),
    entity_id UUID,
    request_id VARCHAR(64),
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_events_project_id ON audit_events(project_id);
CREATE INDEX idx_audit_events_actor_user_id ON audit_events(actor_user_id);
