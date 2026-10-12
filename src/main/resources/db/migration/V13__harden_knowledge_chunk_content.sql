CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source VARCHAR(180) NOT NULL,
    source_index INTEGER NOT NULL DEFAULT 0,
    content TEXT NOT NULL,
    embedding_json LONGTEXT,
    created_at TIMESTAMP(6) NOT NULL
);

ALTER TABLE knowledge_chunks MODIFY COLUMN content TEXT NOT NULL;
