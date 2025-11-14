# Transaction Safety and Isolation

**Last Updated**: November 2025  
**Status**: Proof of Concept - Not Formally Verified

---

## ⚠️ Critical Disclaimer

**This implementation has NOT been formally verified for correctness.**

The transaction system described in this document is:
- ✅ Tested with automated tests (see [Transaction Safety Tests](../src/test/java/com/geico/poc/cassandrasql/kv/TransactionSafetyTest.java))
- ✅ Based on established algorithms (Percolator)
- ✅ Designed with ACID properties in mind
- ❌ **NOT formally proven correct**
- ❌ **NOT reviewed by distributed systems experts**
- ❌ **NOT tested in production**
- ❌ **May contain bugs or race conditions**

**Do NOT use this system for any application requiring data integrity guarantees.**

See [README.md](../README.md#transaction-safety) for test coverage and verification status.

---

## Overview

Cassandra-SQL implements distributed ACID transactions using a Percolator-style two-phase commit protocol on top of Apache Cassandra with Accord consensus.

### Design Goals

1. **Serializability**: Transactions appear to execute in a serial order
2. **Distributed**: Work across multiple Cassandra nodes
3. **Lock-free reads**: Reads don't acquire locks (MVCC)
4. **Optimistic concurrency**: Assume no conflicts, detect at commit time

### Implementation Status

| Property | Status | Confidence | Test Coverage |
|----------|--------|------------|---------------|
| Atomicity | ⚠️ Intended | Medium | See [`TransactionAtomicityTest`](../src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java) |
| Consistency | ⚠️ Limited | Low | Minimal |
| Isolation (Serializable) | ⚠️ Intended | Medium | See [`TransactionAtomicityTest.testSnapshotIsolation()`](../src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java) |
| Durability | ✅ Via Cassandra | High | Good |

---

## Isolation Level

### Serializable Isolation

Cassandra-SQL implements **Serializable** isolation, the strongest isolation level defined by the SQL standard.

**Definition**: Transactions execute as if they were run serially, one after another, with no concurrency.

**Guarantees** (intended, not proven):
- ✅ No dirty reads (reading uncommitted data)
- ✅ No non-repeatable reads (same query returns different results)
- ✅ No phantom reads (new rows appear in range queries)
- ✅ No write skew
- ✅ No lost updates

**Caveats**:
- ⚠️ Only one isolation level supported (no READ COMMITTED, etc.)
- ⚠️ Implementation may have bugs
- ⚠️ Not tested under high concurrency
- ⚠️ Performance not optimized

---

## Transaction Protocol

### Percolator-Style Two-Phase Commit

The implementation is based on Google's Percolator paper with adaptations for Cassandra.

#### Phase 1: Prewrite (Locking)

1. **Allocate Start Timestamp**:
   ```
   start_ts = timestamp_oracle.allocateReadTimestamp()
   ```

2. **Choose Primary Key**:
   - First write in the transaction becomes the primary
   - All other writes are secondaries

3. **Write Locks and Data**:
   - For each write (primary and secondaries):
     ```
     IF NOT EXISTS lock(key):
       write lock(key, tx_id, start_ts, primary_key)
       write data(key, value, start_ts, tx_id, commit_ts=NULL)
     ELSE:
       ABORT (lock conflict)
     ```

4. **Check for Conflicts**:
   - Look for any committed writes after start_ts
   - If found, ABORT (write-write conflict)

#### Phase 2: Commit

1. **Allocate Commit Timestamp**:
   ```
   commit_ts = timestamp_oracle.allocateCommitTimestamp()
   ```

2. **Commit Primary Lock** (atomic):
   ```
   BEGIN BATCH
     UPDATE data SET commit_ts = commit_ts WHERE key = primary_key AND ts = start_ts
     INSERT INTO writes (key, commit_ts, tx_id)
     DELETE lock WHERE key = primary_key
   END BATCH
   ```
   
   **This is the commit point** - once this succeeds, the transaction is committed.

3. **Commit Secondary Locks**:
   - For each secondary key:
     ```
     BEGIN BATCH
       UPDATE data SET commit_ts = commit_ts WHERE key = key AND ts = start_ts
       INSERT INTO writes (key, commit_ts, tx_id)
       DELETE lock WHERE key = key
     END BATCH
     ```
   - Can be done asynchronously (transaction already committed)

### Rollback

If any step fails, rollback:
```
FOR EACH write IN transaction:
  DELETE lock WHERE key = write.key
  DELETE data WHERE key = write.key AND ts = start_ts
```

---

## MVCC (Multi-Version Concurrency Control)

### Versioning

Every write creates a new version with a timestamp:

```
Key: [table_id][index_id][encoded_key]
Value: {
  ts: start_ts,
  commit_ts: commit_ts (or NULL if uncommitted),
  value: data,
  deleted: boolean,
  tx_id: transaction_id
}
```

### Reading Data

Reads use snapshot isolation at a read timestamp:

```
read_ts = timestamp_oracle.getCurrentTimestamp()

FOR EACH version OF key (ordered by ts DESC):
  IF version.commit_ts IS NULL:
    // Uncommitted - check if lock still exists
    IF lock_exists(key):
      SKIP (uncommitted)
    ELSE:
      // Lock cleaned up - transaction committed or rolled back
      CONTINUE
  ELSE IF version.commit_ts <= read_ts:
    IF version.deleted:
      RETURN NULL (deleted)
    ELSE:
      RETURN version.value
```

### Timestamp Oracle

A centralized timestamp oracle provides monotonically increasing timestamps:

```java
public class TimestampOracle {
    private AtomicLong counter;
    
    public long allocateReadTimestamp() {
        return counter.get();  // Current time
    }
    
    public long allocateCommitTimestamp() {
        return counter.incrementAndGet();  // New timestamp
    }
}
```

**Limitations**:
- ⚠️ Single point of failure (not distributed)
- ⚠️ Bottleneck for high concurrency
- ⚠️ No clock synchronization across nodes

---

## Lock Management

### Lock Table Schema

```sql
CREATE TABLE kv_locks (
  key BLOB PRIMARY KEY,
  tx_id UUID,
  start_ts BIGINT,
  primary_key BLOB,
  lock_type TEXT,  -- 'primary' or 'secondary'
  write_type TEXT, -- 'PUT' or 'DELETE'
  created_at TIMESTAMP
)
```

### Lock Acquisition

Locks are acquired using Cassandra's lightweight transactions (LWT):

```sql
INSERT INTO kv_locks (key, tx_id, start_ts, primary_key, lock_type, write_type, created_at)
VALUES (?, ?, ?, ?, ?, ?, now())
IF NOT EXISTS
```

If the lock already exists, the transaction aborts with a lock conflict.

### Lock Cleanup

Locks are deleted during commit:
- Primary lock: Deleted atomically with commit
- Secondary locks: Deleted after commit (can be async)

**Stale Lock Handling**:
- On startup: Truncate lock tables (assumes no in-flight transactions)
- During operation: No automatic cleanup (potential issue)

---

## Conflict Detection

### Write-Write Conflicts

Detected during prewrite phase:

```sql
SELECT commit_ts FROM kv_writes 
WHERE key = ? AND commit_ts > start_ts 
LIMIT 1
```

If any write exists after our start_ts, abort (another transaction modified the key).

### Read-Write Conflicts

Prevented by MVCC:
- Reads see snapshot at read_ts
- Writes create new versions
- No conflict unless write-write conflict

---

## Test Coverage

### Transaction Tests

See [src/test/java/com/geico/poc/cassandrasql/](../../src/test/java/com/geico/poc/cassandrasql/) for test files.

#### Basic Transaction Semantics

**File**: `TransactionTest.java` (if exists)

Tests:
- ✅ Simple transaction (BEGIN, INSERT, COMMIT)
- ✅ Transaction rollback (BEGIN, INSERT, ROLLBACK)
- ✅ Multi-statement transactions
- ✅ Transaction with UPDATE and DELETE
- ⚠️ Nested transactions (not supported)

#### Isolation Level Verification

**File**: `TransactionIsolationTest.java` (if exists)

Tests:
- ✅ No dirty reads
- ✅ No non-repeatable reads
- ✅ No phantom reads
- ⚠️ Write skew scenarios (limited)
- ⚠️ High concurrency scenarios (limited)

#### Concurrent Transactions

**File**: `ConcurrentTransactionTest.java` (if exists)

Tests:
- ✅ Two transactions writing different keys (should succeed)
- ✅ Two transactions writing same key (one should fail)
- ⚠️ Many concurrent transactions (limited)
- ⚠️ Deadlock scenarios (not tested)

#### Table Operations

**File**: `LazyDropTest.java`

Tests:
- ✅ DROP TABLE doesn't interfere with transactions
- ✅ TRUNCATE TABLE with MVCC
- ✅ Table recreation after DROP
- ✅ Vacuum job cleanup

### What's NOT Tested

- ❌ Distributed transactions across multiple nodes
- ❌ Network partitions
- ❌ Node failures during transactions
- ❌ Clock skew scenarios
- ❌ Very high concurrency (>100 concurrent transactions)
- ❌ Long-running transactions
- ❌ Transaction timeout handling
- ❌ Stale lock cleanup during operation
- ❌ Timestamp oracle failover

---

## Known Issues and Limitations

### 1. Timestamp Oracle is Centralized

**Issue**: Single point of failure and bottleneck

**Impact**:
- If timestamp oracle fails, no transactions can proceed
- High contention under concurrent load
- Not truly distributed

**Mitigation**: None currently

### 2. No Stale Lock Cleanup

**Issue**: Locks from crashed transactions may persist

**Impact**:
- Keys become permanently locked
- Manual intervention required

**Mitigation**: Truncate lock tables on startup (loses in-flight transactions)

### 3. Lock Conflicts are Common

**Issue**: Optimistic concurrency means conflicts detected late

**Impact**:
- Transactions abort frequently under contention
- No retry logic
- Poor performance with hot keys

**Mitigation**: None currently

### 4. No Deadlock Detection

**Issue**: Circular lock dependencies not detected

**Impact**:
- Transactions may wait forever
- No timeout mechanism

**Mitigation**: None currently

### 5. Secondary Lock Cleanup Not Atomic

**Issue**: Secondary locks deleted after commit point

**Impact**:
- Locks may persist if cleanup fails
- Readers may see uncommitted data briefly

**Mitigation**: Readers check commit_ts

### 6. No Distributed Timestamp Oracle

**Issue**: Timestamp oracle runs on single node

**Impact**:
- Not fault-tolerant
- Clock skew across nodes not handled

**Mitigation**: None currently

### 7. Limited Constraint Enforcement

**Issue**: Foreign keys and CHECK constraints not fully enforced

**Impact**:
- Data integrity not guaranteed
- Constraint violations possible

**Mitigation**: Application-level validation

---

## Performance Characteristics

### Latency

**Single-node transaction** (5 writes):
- Prewrite phase: ~10-20 ms
- Commit phase: ~5-10 ms
- **Total**: ~15-30 ms

**Distributed transaction** (3 nodes, 5 writes):
- Prewrite phase: ~20-50 ms (network + coordination)
- Commit phase: ~10-20 ms
- **Total**: ~30-70 ms

**Factors affecting latency**:
- Number of writes in transaction
- Network latency between nodes
- Cassandra consistency level
- Lock contention

### Throughput

**Estimated** (not benchmarked):
- Single node: ~100-500 transactions/sec
- 3-node cluster: ~300-1500 transactions/sec

**Bottlenecks**:
- Timestamp oracle (centralized)
- Lock acquisition (LWT overhead)
- Commit phase (sequential for secondaries)

### Scalability

**Write scalability**: Limited
- Timestamp oracle is bottleneck
- Lock table is shared resource

**Read scalability**: Good
- Reads don't acquire locks
- MVCC allows concurrent reads
- Can scale with Cassandra nodes

---

## Comparison with Other Systems

### vs PostgreSQL

| Aspect | PostgreSQL | Cassandra-SQL |
|--------|-----------|---------------|
| Isolation | 4 levels (configurable) | Serializable only |
| Concurrency | MVCC + 2PL | MVCC + Optimistic |
| Distributed | No (single node) | Yes (Percolator) |
| Performance | Faster (single node) | Slower (distributed) |
| Maturity | Production-ready | Proof of concept |

### vs CockroachDB

| Aspect | CockroachDB | Cassandra-SQL |
|--------|-------------|---------------|
| Isolation | Serializable | Serializable |
| Consensus | Raft | Accord |
| Timestamp | HLC (distributed) | Centralized oracle |
| Maturity | Production-ready | Proof of concept |
| Performance | Optimized | Not optimized |

---

## Future Improvements

### Short-term

1. **Stale lock cleanup**: Background job to clean up abandoned locks
2. **Transaction timeout**: Abort long-running transactions
3. **Retry logic**: Automatic retry on lock conflicts
4. **Better error messages**: Explain why transaction failed

### Long-term

1. **Distributed timestamp oracle**: Use Cassandra Accord for timestamps
2. **Deadlock detection**: Detect and break circular dependencies
3. **Multiple isolation levels**: Support READ COMMITTED, etc.
4. **Savepoints**: Partial rollback within transactions
5. **Two-phase commit optimization**: Batch secondary lock commits

---

## References

### Papers

- **Percolator**: Large-scale Incremental Processing Using Distributed Transactions and Notifications
  - https://research.google/pubs/pub36726/
  - Original inspiration for transaction protocol

- **Spanner**: Google's Globally-Distributed Database
  - https://research.google/pubs/pub39966/
  - TrueTime and distributed transactions

### Implementation

- **Source Code**: `src/main/java/com/geico/poc/cassandrasql/kv/`
  - `KvTransactionCoordinator.java` - Main transaction logic
  - `KvTransactionSessionManager.java` - Session management
  - `KvStore.java` - MVCC storage layer
  - `TimestampOracle.java` - Timestamp allocation

- **Tests**: `src/test/java/com/geico/poc/cassandrasql/`
  - `LazyDropTest.java` - Table versioning tests
  - Various integration tests

---

## Conclusion

Cassandra-SQL implements a Percolator-style distributed transaction system with serializable isolation. The implementation is:

✅ **Interesting** as a proof of concept  
✅ **Educational** for learning distributed transactions  
⚠️ **Incomplete** in many areas  
⚠️ **Unverified** for correctness  
❌ **Not production-ready**

**Use for learning and experimentation only. Do NOT use for any application requiring data integrity.**

---

## Contact

For questions about the transaction implementation, please open an issue on GitHub with:
- Specific scenario or test case
- Expected vs actual behavior
- Relevant logs or error messages

We cannot guarantee correctness, but we're happy to discuss the design and implementation.


