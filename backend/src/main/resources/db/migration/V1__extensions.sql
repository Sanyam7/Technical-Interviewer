-- Extensions required by the schema (see DATABASE_SCHEMA.md).
-- vector: pgvector embeddings and similarity search. citext: case-insensitive unique emails.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS citext;
