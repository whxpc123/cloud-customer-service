CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE SCHEMA IF NOT EXISTS ai;
CREATE TABLE ai.knowledge_vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text NOT NULL,
    metadata json NOT NULL DEFAULT '{}'::json,
    embedding vector(1024) NOT NULL
);
CREATE INDEX knowledge_vector_store_embedding_hnsw_idx
    ON ai.knowledge_vector_store USING hnsw (embedding vector_cosine_ops);
