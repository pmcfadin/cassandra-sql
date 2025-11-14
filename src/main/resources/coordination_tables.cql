-- Percolator-style Transaction Coordination Tables
-- These tables manage distributed transaction state

-- 1. Transaction Locks Table
-- Tracks which transaction has locked which row
CREATE TABLE IF NOT EXISTS tx_locks (
    table_name TEXT,
    row_key TEXT,
    tx_id UUID,
    state TEXT,              -- PENDING, COMMITTED, ABORTED
    locked_at TIMESTAMP,
    PRIMARY KEY ((table_name, row_key), tx_id)
) WITH CLUSTERING ORDER BY (tx_id DESC)
  AND comment = 'Transaction locks for conflict detection';

-- 2. Transaction Writes Table (Staging Area)
-- Stores pending writes until commit
CREATE TABLE IF NOT EXISTS tx_writes (
    tx_id UUID,
    table_name TEXT,
    row_key TEXT,
    operation TEXT,          -- INSERT, UPDATE, DELETE
    row_data TEXT,           -- JSON serialized row data
    PRIMARY KEY (tx_id, table_name, row_key)
) WITH comment = 'Staged writes for pending transactions';

-- 3. Transaction Metadata Table
-- Tracks transaction lifecycle
CREATE TABLE IF NOT EXISTS tx_metadata (
    tx_id UUID PRIMARY KEY,
    connection_id TEXT,
    state TEXT,              -- ACTIVE, COMMITTING, COMMITTED, ABORTED
    started_at TIMESTAMP,
    committed_at TIMESTAMP,
    row_count INT
) WITH comment = 'Transaction metadata and state';

-- 4. Create indexes for cleanup queries
CREATE INDEX IF NOT EXISTS tx_metadata_state_idx ON tx_metadata (state);
CREATE INDEX IF NOT EXISTS tx_locks_state_idx ON tx_locks (state);







