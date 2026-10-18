-- Knowledge base (RAG): documents, chunks (with full-text search) and embeddings (pgvector, HNSW).

CREATE TABLE knowledge_documents (
    id                 uuid         NOT NULL,
    title              varchar(200) NOT NULL,
    source_type        varchar(20)  NOT NULL,
    doc_type           varchar(30)  NOT NULL,
    visibility         varchar(10)  NOT NULL,
    owner_user_id      uuid,
    skill_id           bigint,
    source_path        varchar(300),
    original_filename  varchar(255),
    mime_type          varchar(60)  NOT NULL,
    byte_size          int          NOT NULL,
    content_sha256     char(64)     NOT NULL,
    extracted_text     text         NOT NULL,
    status             varchar(20)  NOT NULL,
    error_message      varchar(500),
    chunk_count        int          NOT NULL DEFAULT 0,
    metadata           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_by         uuid,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_documents PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_documents_owner FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_documents_skill FOREIGN KEY (skill_id) REFERENCES skills (id),
    CONSTRAINT fk_knowledge_documents_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_knowledge_documents_source_type CHECK (source_type IN ('CURATED', 'ADMIN_UPLOAD', 'RESUME')),
    CONSTRAINT ck_knowledge_documents_doc_type CHECK (doc_type IN ('REFERENCE', 'QUESTION_EXEMPLAR', 'DESIGN_REFERENCE', 'RESUME')),
    CONSTRAINT ck_knowledge_documents_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT ck_knowledge_documents_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_knowledge_documents_private_has_owner CHECK (visibility = 'PUBLIC' OR owner_user_id IS NOT NULL),
    CONSTRAINT ck_knowledge_documents_resume_private CHECK ((source_type <> 'RESUME' AND doc_type <> 'RESUME') OR visibility = 'PRIVATE'),
    CONSTRAINT ck_knowledge_documents_content_sha256_hex CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_documents_byte_size CHECK (byte_size >= 0),
    CONSTRAINT ck_knowledge_documents_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT uq_knowledge_documents_owner_sha256 UNIQUE NULLS NOT DISTINCT (owner_user_id, content_sha256)
);
CREATE INDEX ix_knowledge_documents_skill ON knowledge_documents (skill_id);
CREATE INDEX ix_knowledge_documents_created_by ON knowledge_documents (created_by) WHERE created_by IS NOT NULL;

CREATE TABLE knowledge_chunks (
    id            uuid         NOT NULL,
    document_id   uuid         NOT NULL,
    chunk_index   int          NOT NULL,
    heading_path  varchar(500),
    content       text         NOT NULL,
    token_count   int          NOT NULL,
    topic_id      bigint,
    difficulty    smallint,
    metadata      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    content_tsv   tsvector GENERATED ALWAYS AS
                  (to_tsvector('english', coalesce(heading_path, '') || ' ' || content)) STORED,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_chunks PRIMARY KEY (id),
    CONSTRAINT uq_knowledge_chunks_document_index UNIQUE (document_id, chunk_index),
    CONSTRAINT fk_knowledge_chunks_document FOREIGN KEY (document_id) REFERENCES knowledge_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_chunks_topic FOREIGN KEY (topic_id) REFERENCES topics (id),
    CONSTRAINT ck_knowledge_chunks_difficulty CHECK (difficulty BETWEEN 1 AND 5),
    CONSTRAINT ck_knowledge_chunks_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_knowledge_chunks_token_count CHECK (token_count >= 0)
);
CREATE INDEX ix_knowledge_chunks_tsv ON knowledge_chunks USING gin (content_tsv);
CREATE INDEX ix_knowledge_chunks_topic ON knowledge_chunks (topic_id);

CREATE TABLE embeddings (
    id              uuid         NOT NULL,
    chunk_id        uuid         NOT NULL,
    model           varchar(80)  NOT NULL,
    dimensions      smallint     NOT NULL,
    embedding       vector(768)  NOT NULL,
    content_sha256  char(64)     NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_embeddings PRIMARY KEY (id),
    CONSTRAINT uq_embeddings_chunk_model UNIQUE (chunk_id, model),
    CONSTRAINT fk_embeddings_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunks (id) ON DELETE CASCADE,
    CONSTRAINT ck_embeddings_dimensions CHECK (dimensions = 768),
    CONSTRAINT ck_embeddings_content_sha256_hex CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_embeddings_hnsw ON embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE knowledge_documents IS 'RAG source documents. PUBLIC = curated/admin material visible to everyone; PRIVATE = owned by one user (resumes). NULLS NOT DISTINCT makes (NULL owner, sha) unique for curated documents too.';
COMMENT ON COLUMN knowledge_documents.extracted_text IS 'Kept so a document can be re-chunked without re-upload.';
COMMENT ON TABLE embeddings IS 'One vector per (chunk, model). Vectors from different models are never compared; searches filter by the active model.';
