-- V16: Add question validation columns
-- Purpose: Track LLM-generated test case correctness so only validated
--          questions reach candidate sessions (prevents BFS-correct-but-FAILED bug).

ALTER TABLE questions ADD COLUMN IF NOT EXISTS validation_status VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE questions ADD COLUMN IF NOT EXISTS validated_at TIMESTAMP;

-- Mark all existing questions as PENDING so they get re-validated on startup
UPDATE questions SET validation_status = 'PENDING' WHERE validation_status IS NULL;
