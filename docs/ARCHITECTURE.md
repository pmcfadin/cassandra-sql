# Architecture Overview

**Last Updated**: November 2025  
**Status**: Proof of Concept

---

## High-Level Architecture

Cassandra-SQL is built as a layered architecture that translates PostgreSQL wire protocol requests into operations on Apache Cassandra using Accord transactions.

```
┌─────────────────────────────────────────────────────────────┐
│                    PostgreSQL Clients                        │
│                  (psql, JDBC, pgAdmin, etc.)                 │
└────────────────────────┬────────────────────────────────────┘
                         │ PostgreSQL Wire Protocol
┌────────────────────────▼────────────────────────────────────┐
│                  PostgreSQL Protocol Server                  │
│              (PostgresConnectionHandler.java)                │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                     Query Service                            │
│                  (QueryService.java)                         │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                   Calcite SQL Parser                        │
│                  (CalciteParser.java)                       │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                   Query Executor (KV Mode)                  │
│                  (KvQueryExecutor.java)                     │
│  ┌──────────────┬──────────────┬──────────────┬──────────┐  │
│  │ Join Exec    │ Agg Exec     │Subquery Exec │Union Exec│  │
│  └──────────────┴──────────────┴──────────────┴──────────┘  │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│              Transaction Coordinator                        │
│          (KvTransactionCoordinator.java)                    │
│               (Percolator-Like Protocol)                    │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                    KV Store Layer                           │
│                   (KvStore.java)                            │
│              (MVCC, Timestamp Oracle)                       │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                 Apache Cassandra 5.0+                       │
│                   (with Accord)                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. PostgreSQL Protocol Server

**File**: `src/main/java/com/geico/poc/cassandrasql/postgres/PostgresConnectionHandler.java`

Handles PostgreSQL wire protocol connections, parsing protocol messages, and sending responses. Supports:
- Query execution
- Prepared statements
- Transactions (BEGIN, COMMIT, ROLLBACK)
- Error handling

### 2. Query Service

**File**: `src/main/java/com/geico/poc/cassandrasql/QueryService.java`

Orchestrates query execution:
- Routes queries to appropriate executor
- Manages connection state
- Handles transaction boundaries
- Validates SQL syntax

### 3. Calcite SQL Parser

**File**: `src/main/java/com/geico/poc/cassandrasql/CalciteParser.java`

Uses Apache Calcite to parse SQL into an abstract syntax tree (AST):
- SQL parsing and validation
- Query type detection (SELECT, INSERT, UPDATE, DELETE, etc.)
- Subquery detection
- Expression parsing

### 4. KV Query Executor

**File**: `src/main/java/com/geico/poc/cassandrasql/kv/KvQueryExecutor.java`

Main query execution engine for KV mode:
- Executes SELECT, INSERT, UPDATE, DELETE
- Routes complex queries to specialized executors:
  - `JoinExecutor` - JOIN operations
  - `AggregationExecutor` - GROUP BY, aggregations
  - `SubqueryExecutor` - Subquery handling
  - `UnionExecutor` - UNION operations
- Handles FROM subqueries (derived tables)
- Manages query results and formatting

### 5. Transaction Coordinator

**File**: `src/main/java/com/geico/poc/cassandrasql/kv/KvTransactionCoordinator.java`

Implements Percolator-style distributed transactions:

**Prewrite Phase**:
- Acquires locks atomically using Accord transactions
- Checks for write conflicts
- Writes uncommitted data (commit_ts = NULL)

**Commit Phase**:
- Allocates commit timestamp
- Commits primary lock atomically
- Commits secondary locks (with retry logic)

**Rollback**:
- Deletes all locks
- Deletes uncommitted data

**Key Design**:
- Uses Accord transactions for atomic lock acquisition
- Primary lock commit is the commit point
- Secondary locks committed asynchronously (best-effort)
- Lock conflicts cause transaction abort

### 6. KV Store

**File**: `src/main/java/com/geico/poc/cassandrasql/kv/KvStore.java`

MVCC-based key-value storage layer:
- Stores data with timestamps (MVCC versions)
- Supports snapshot reads (read at a specific timestamp)
- Handles tombstones for deletes
- Range scans with timestamp filtering

**Data Model**:
- `kv_store` table: Actual data with MVCC versions
- `kv_locks` table: Transaction locks
- `kv_writes` table: Commit records for conflict detection

### 7. Timestamp Oracle

**File**: `src/main/java/com/geico/poc/cassandrasql/kv/TimestampOracle.java`

Allocates distributed timestamps:
- Batches timestamp allocation for performance
- Uses Accord transactions for atomic allocation
- Ensures monotonic timestamps across nodes
- Prevents duplicate timestamp allocation

---

## Key Design Decisions

### 1. KV Storage Backend

All data is stored as key-value pairs with encoded keys:
- Table data: `TABLE_DATA | table_id | encoded_pk | rowid`
- Index entries: `SECONDARY_INDEX | table_id | index_id | encoded_index_key | encoded_pk`
- Schema metadata: `SCHEMA_*` prefixes

**Benefits**:
- Simple, uniform storage model
- Efficient range scans
- Easy to implement MVCC
- Works well with Accord transactions

### 2. MVCC (Multi-Version Concurrency Control)

Each write creates a new version with a timestamp:
- Reads use snapshot isolation (read at start_ts)
- Writes create new versions
- Old versions cleaned up by VacuumJob

**Benefits**:
- Lock-free reads
- Snapshot isolation
- No dirty reads

### 3. Percolator-Style Transactions

Two-phase commit protocol:
- **Prewrite**: Acquire locks, write uncommitted data
- **Commit**: Atomically commit primary lock, then secondary locks

**Benefits**:
- Works on eventually consistent storage
- Provides serializable isolation
- Handles distributed transactions

### 4. Accord Transactions

Uses Cassandra Accord for atomic operations:
- Lock acquisition is atomic
- Commit operations are atomic
- Prevents race conditions

**Implementation**: All critical operations use Accord `BEGIN TRANSACTION ... IF ... THEN ... COMMIT TRANSACTION` syntax.

### 5. Lazy Schema Operations

Table versioning instead of immediate deletion:
- DROP TABLE increments table version
- Old data remains but is invisible to new queries
- VacuumJob cleans up old versions

**Benefits**:
- O(1) DROP TABLE (no data deletion)
- O(1) TRUNCATE (no data deletion)
- Fast schema changes

### 6. Background Jobs

Asynchronous maintenance tasks:
- **VacuumJob**: Cleans up old MVCC versions and tombstones
- **IndexConsistencyJob**: Verifies index consistency
- **StatisticsCollectorJob**: Collects table statistics for query optimization

---

## Data Flow Examples

### SELECT Query

```
1. PostgreSQL client sends: SELECT * FROM users WHERE id = 1
2. PostgresConnectionHandler parses protocol message
3. QueryService routes to KvQueryExecutor
4. CalciteParser parses SQL into AST
5. KvQueryExecutor.executeSelect():
   - Encodes key: TABLE_DATA | users_table_id | encoded(1)
   - Calls KvStore.get(key, readTimestamp)
   - KvStore queries Cassandra kv_store table
   - Filters by timestamp (MVCC)
   - Returns result
6. Result formatted and sent back via PostgreSQL protocol
```

### INSERT in Transaction

```
1. Client: BEGIN
   - TransactionSessionManager creates KvTransactionContext
   - Allocates start_ts

2. Client: INSERT INTO users (id, name) VALUES (1, 'Alice')
   - KvQueryExecutor adds write intent to transaction context
   - No Cassandra write yet (buffered)

3. Client: COMMIT
   - KvTransactionCoordinator.prewrite():
     - For each key (data + indexes):
       - Uses Accord transaction to atomically:
         - Check for conflicts
         - Acquire lock
         - Write uncommitted data
   - KvTransactionCoordinator.commit():
     - Allocate commit_ts
     - Commit primary lock atomically (Accord transaction):
       - Update commit_ts
       - Write commit record
       - Delete lock
     - Commit secondary locks (with retry)
   - Transaction complete
```

---

## Accord Transaction Usage

Accord transactions are used for all critical atomic operations:

### Lock Acquisition (Prewrite)

```sql
BEGIN TRANSACTION
  LET existing_lock = (SELECT tx_id FROM kv_locks WHERE key = ?);
  LET conflict = (SELECT commit_ts FROM kv_writes WHERE key = ? AND commit_ts > ?);
  IF existing_lock IS NULL AND conflict IS NULL THEN
    INSERT INTO kv_locks ...;
    INSERT INTO kv_store ... (commit_ts = NULL);
  END IF
COMMIT TRANSACTION
```

### Primary Lock Commit

```sql
BEGIN TRANSACTION
  LET lock_check = (SELECT tx_id FROM kv_locks WHERE key = ?);
  LET data_check = (SELECT commit_ts FROM kv_store WHERE key = ? AND ts = ?);
  IF lock_check.tx_id = ? AND lock_check.start_ts = ? AND data_check.commit_ts IS NULL THEN
    UPDATE kv_store SET commit_ts = ? WHERE key = ? AND ts = ?;
    INSERT INTO kv_writes ...;
    DELETE FROM kv_locks WHERE key = ?;
  END IF
COMMIT TRANSACTION
```

### Timestamp Allocation

```sql
BEGIN TRANSACTION
  LET current = (SELECT current_timestamp FROM timestamp_oracle WHERE oracle_id = 'global');
  IF current IS NULL THEN
    UPDATE timestamp_oracle SET current_timestamp = ?, last_allocated_batch = ? WHERE oracle_id = 'global';
  END IF
COMMIT TRANSACTION
```

---

## Performance Optimizations

### 1. Batch Timestamp Allocation

Timestamps allocated in batches (default: 1000) to reduce Cassandra round trips.

### 2. Accord Transaction Consolidation

Multiple checks combined into single Accord transaction:
- Lock check + conflict check + lock acquisition = 1 Accord transaction
- Commit verification + commit_ts update + lock deletion = 1 Accord transaction

### 3. Lazy Secondary Lock Commits

Secondary locks committed asynchronously after primary lock commit:
- Transaction is committed once primary lock commits
- Secondary locks committed with retry logic
- Reduces commit latency

### 4. Index Updates Batched

Index updates for a single row operation are batched together in the same transaction.

---

## Limitations and Known Issues

### 1. Not Production Ready

- ⚠️ No formal correctness proofs
- ⚠️ Limited test coverage
- ⚠️ Performance not optimized
- ⚠️ Error handling incomplete

### 2. Transaction Limitations

- ⚠️ Lock conflicts cause transaction abort (no deadlock detection)
- ⚠️ Long-running transactions may hold locks for extended periods
- ⚠️ No transaction timeout mechanism (planned)

### 3. FROM Subqueries

- ⚠️ FROM subqueries nested in other subqueries not fully supported
- ⚠️ Some complex FROM subquery patterns may fail

### 4. Performance

- ⚠️ Not optimized for high-throughput workloads
- ⚠️ No query result caching
- ⚠️ Limited predicate pushdown

---

## Testing

See [TESTING.md](TESTING.md) for testing philosophy and coverage.

Key test files:
- [`kv/TransactionSafetyTest.java`](../src/test/java/com/geico/poc/cassandrasql/kv/TransactionSafetyTest.java) - Atomic operations, lock acquisition, commit verification
- [`kv/TransactionAtomicityTest.java`](../src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java) - ACID properties, multi-key atomicity, snapshot isolation
- [`kv/KvCorrectnessTest.java`](../src/test/java/com/geico/poc/cassandrasql/kv/KvCorrectnessTest.java) - MVCC correctness, delete/update operations
- [`kv/PercolatorTransactionTest.java`](../src/test/java/com/geico/poc/cassandrasql/kv/PercolatorTransactionTest.java) - Percolator protocol correctness

---

## References

- **Google Percolator**: [Large-scale Incremental Processing Using Distributed Transactions and Notifications](https://research.google/pubs/pub36726/)
- **Apache Cassandra Accord**: [Accord Transactions](https://cassandra.apache.org/doc/latest/cassandra/new/accord.html)
- **Apache Calcite**: [Calcite Documentation](https://calcite.apache.org/docs/)

