# Background Jobs in KV Mode

This document describes the background maintenance jobs that run automatically in KV storage mode to maintain system health, performance, and data integrity.

## Overview

Cassandra-SQL KV mode includes several background jobs that run periodically to:
1. Clean up old MVCC versions (Vacuum)
2. Collect statistics for the cost-based optimizer (Statistics)
3. Verify index consistency (Index Consistency)
4. Check for constraint violations (Constraint Checker)

All jobs are configured in `application-kv.yml` and can be enabled/disabled independently.

---

## 1. Vacuum Job

### Purpose
Cleans up old MVCC versions and deleted data to reclaim storage space and maintain performance.

### What It Does
- **Old Version Cleanup**: Deletes MVCC versions older than the retention period
- **Tombstone Removal**: Removes deleted rows (tombstones) after they're no longer needed
- **Truncated Data Cleanup**: Removes data from truncated tables
- **Dropped Table Cleanup**: Removes all data and metadata from dropped tables

### Algorithm
```
For each key in the KV store:
  1. Group all versions by key
  2. Sort versions by timestamp (newest first)
  3. Keep the latest committed version (ALWAYS)
  4. For older versions:
     - If deleted=true AND commit_ts < minSafeTs: DELETE
     - If deleted=false AND commit_ts < minSafeTs AND not latest: DELETE
  5. Never delete the latest committed version
```

### Safety Guarantees
- **Latest version is never deleted**: Ensures data availability
- **Retention period**: Configurable grace period before deletion
- **Rate limiting**: Prevents overwhelming the system
- **Batch processing**: Deletes in small batches to avoid long-running transactions

### Configuration
```yaml
vacuum:
  enabled: true
  period-minutes: 5              # Run every 5 minutes
  initial-delay-minutes: 1       # Start after 1 minute
  retention-hours: 1             # Keep versions for 1 hour
  batch-size: 1000               # Delete 1000 rows per batch
  max-execution-minutes: 10      # Stop after 10 minutes
```

### Monitoring
- Log messages indicate how many versions were freed
- Check logs for `Vacuum freed: X old versions`

---

## 2. Statistics Collection Job

### Purpose
Collects table and index statistics for the cost-based optimizer to make better query planning decisions.

### What It Collects
- **Row count**: Total number of rows in each table
- **Index size**: Number of entries in each index
- **Cardinality**: Number of distinct values in indexed columns
- **Selectivity**: Cardinality / Row count (0.0 to 1.0)

### How It Works
1. Scans each table (with sampling for large tables)
2. Counts rows and analyzes index columns
3. Calculates selectivity metrics
4. Caches results for the cost-based optimizer

### Configuration
```yaml
statistics:
  enabled: true
  period-minutes: 30             # Run every 30 minutes
  initial-delay-minutes: 2       # Start after 2 minutes
  sample-rate: 0.1               # Sample 10% of rows for large tables
  max-rows-per-table: 10000      # Limit scan to 10K rows
  min-rows-threshold: 100        # Skip tables with < 100 rows
  exclude-tables: ""             # Tables to skip (comma-separated)
```

### Usage
The cost-based optimizer uses these statistics to:
- Choose between index scan vs full table scan
- Select the best index when multiple are available
- Estimate query execution cost

---

## 3. Index Consistency Job

### Purpose
Verifies that indexes are consistent with the primary data and detects any inconsistencies.

### What It Checks
1. **Forward consistency**: Every data row has corresponding index entries
2. **Backward consistency**: Every index entry points to an existing data row
3. **Value consistency**: Index values match the actual column values
4. **No orphans**: No index entries without data rows
5. **No missing entries**: No data rows without index entries

### How It Works
1. Scans all data rows in a table
2. For each row, verifies all index entries exist and are correct
3. Scans index entries and verifies they point to valid rows
4. Reports any inconsistencies found

### Configuration
```yaml
index-consistency:
  enabled: true
  period-minutes: 15             # Run every 15 minutes
  initial-delay-minutes: 1       # Start after 1 minute
```

### What It Detects
- Bugs in index maintenance code
- Partial transaction failures
- Data corruption
- MVCC issues

---

## 4. Constraint Violation Checker Job

### Purpose
Scans tables to detect violations of UNIQUE and FOREIGN KEY constraints.

### What It Checks

#### UNIQUE Constraints
- Detects multiple rows with the same values in unique columns
- Ignores NULL values (multiple NULLs are allowed per SQL standard)
- Reports the number of duplicate rows found

#### FOREIGN KEY Constraints
- Detects rows referencing non-existent parent rows
- Ignores NULL foreign key values
- Checks all foreign key relationships

### How It Works
1. Scans each table with constraints
2. Groups rows by unique column values
3. Checks foreign key references against parent tables
4. Reports violations with details

### Rate Limiting
This job performs full table scans, so it includes aggressive rate limiting:
- Default: 10,000 keys/hour (~3 keys/second)
- Configurable based on system capacity
- Prevents overwhelming the system

### Configuration
```yaml
constraint-checker:
  enabled: true
  period-minutes: 30             # Run every 30 minutes (less frequent)
  initial-delay-minutes: 5       # Start after 5 minutes
  rate-limit-keys-per-hour: 10000  # Throttle scanning
  max-tables-per-run: 0          # 0 = unlimited
  exclude-tables: ""             # Tables to skip
```

### Violation Reporting
When violations are found:
```
⚠️  Found 3 UNIQUE constraint violations in users
⚠️  Found 1 FOREIGN KEY constraint violations in orders
Run 'SELECT * FROM constraint_violations()' to see details
```

### Why This Exists
Constraint violations can occur due to:
- Bugs in constraint enforcement code
- Data imported before constraints were added
- Concurrent transaction anomalies
- System failures during multi-step operations

This job provides a safety net to detect these issues.

---

## Index Building Process

### Problem
When creating an index on a table with existing data, we need to:
1. Create the index metadata
2. Backfill index entries for existing rows
3. Ensure new writes are handled correctly during backfill

### Solution: Building Flag

Indexes have a `building` flag that controls their visibility:

```java
public class IndexMetadata {
    private final boolean building;  // True during backfill, false when ready
    
    public boolean isBuilding() {
        return building;
    }
}
```

### Index Creation Flow

```
1. CREATE INDEX statement received
   ↓
2. Create index metadata with building=true
   ↓
3. Backfill index entries in batches (1000 rows per transaction)
   ↓
4. Mark index as building=false (ready)
   ↓
5. Index is now queryable
```

### Safety Guarantees

#### During Backfill (building=true)
- **Queries skip the index**: Cost-based optimizer won't use it
- **Writes skip the index**: INSERT/UPDATE/DELETE don't maintain it
- **No race conditions**: Backfill can proceed without conflicts

#### After Backfill (building=false)
- **Index is fully consistent**: All existing data is indexed
- **Queries can use it**: Cost-based optimizer considers it
- **Writes maintain it**: All DML operations update the index atomically

### Incremental Backfill

The backfill process is incremental to handle large tables:

```java
int batchSize = 1000; // Process 1000 rows per transaction

for (int i = 0; i < entries.size(); i += batchSize) {
    // Start a new transaction for this batch
    KvTransactionContext txn = new KvTransactionContext(...);
    
    // Add index entries for this batch
    for (KvStore.KvEntry entry : batch) {
        byte[] indexKey = KeyEncoder.encodeIndexKey(...);
        txn.addWrite(indexKey, indexValue, WriteType.PUT);
    }
    
    // Commit this batch
    coordinator.commit(txn);
}
```

### Why Incremental?
- **Avoids transaction size limits**: Large tables can't fit in one transaction
- **Better progress tracking**: Can log progress every N batches
- **Failure recovery**: If backfill fails, can resume from last batch
- **System stability**: Smaller transactions are less likely to cause issues

### Atomicity Guarantees

#### Index Maintenance in DML Operations
All index updates are part of the same Accord transaction as the primary data:

```java
// INSERT example
KvTransactionContext txn = new KvTransactionContext(...);

// Write primary data
txn.addWrite(primaryKey, primaryValue, WriteType.PUT);

// Write index entries (same transaction)
for (TableMetadata.IndexMetadata index : table.getIndexes()) {
    if (!index.isBuilding()) {  // Skip building indexes
        byte[] indexKey = KeyEncoder.encodeIndexKey(...);
        txn.addWrite(indexKey, indexValue, WriteType.PUT);
    }
}

// Commit atomically
coordinator.commit(txn);
```

**Result**: Primary data and all index entries are written atomically. Either all succeed or all fail.

#### Why This Matters
- **Consistency**: Indexes always reflect the current data
- **No orphans**: No index entries without data rows
- **No missing entries**: No data rows without index entries
- **ACID compliance**: Full transactional guarantees

---

## Job Scheduling

### Thread Pool
- Each job runs in its own thread
- Configurable thread pool size (default: 3)
- Jobs run independently and don't block each other

### Execution Model
```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(poolSize);

for (BackgroundJob job : jobs) {
    if (job.isEnabled()) {
        scheduler.scheduleAtFixedRate(
            () -> job.execute(),
            job.getInitialDelayMs(),
            job.getPeriodMs(),
            TimeUnit.MILLISECONDS
        );
    }
}
```

### Job Lifecycle
1. **Initialization**: Jobs are registered at startup
2. **Initial Delay**: Each job waits before first execution
3. **Periodic Execution**: Jobs run at configured intervals
4. **Shutdown**: Jobs are stopped gracefully on system shutdown

---

## Monitoring and Troubleshooting

### Log Messages

#### Vacuum
```
🧹 Starting vacuum job...
   Vacuum threshold: 1234567890 (retention: 1h)
   Found 1000 unique keys
   Vacuum freed: 500 old versions
   Vacuum freed: 100 truncated rows
   Vacuum cleaned up: 2 dropped tables
```

#### Statistics
```
📊 Collecting table statistics...
   Analyzing table: users (1000 rows)
   Index stats: email_idx (cardinality=950, selectivity=0.95)
   ✅ Statistics collected for 5 tables
```

#### Index Consistency
```
🔍 Checking index consistency...
   Checking indexes for table: users
   ✅ All indexes consistent (5 tables checked)
```

#### Constraint Checker
```
🔍 Starting constraint violation check...
   Checking constraints for table: users
   ⚠️  Found 2 UNIQUE constraint violations in users
   ⚠️  Found 1 FOREIGN KEY constraint violations in orders
   ⚠️  Found 3 total constraint violations across 10 tables
```

### Health Checks

The system includes health indicators for background jobs:

```yaml
management:
  health:
    background-jobs:
      enabled: true
      vacuum-max-delay-minutes: 15
      statistics-max-delay-minutes: 60
```

Health check fails if:
- Vacuum hasn't run in 15 minutes
- Statistics haven't been collected in 60 minutes

### Disabling Jobs

To disable a specific job:

```yaml
vacuum:
  enabled: false  # Disable vacuum job
```

To disable all background jobs:

```yaml
background-jobs:
  enabled: false  # Master switch
```

---

## Performance Considerations

### Rate Limiting
Jobs that scan large amounts of data include rate limiting:
- **Vacuum**: Limits keys processed per hour
- **Constraint Checker**: Aggressive rate limiting (default: 10K keys/hour)

### Sampling
Statistics collection uses sampling for large tables:
- Default: 10% sample rate
- Configurable via `sample-rate` parameter
- Balances accuracy vs performance

### Batch Processing
Jobs process data in batches to avoid:
- Long-running transactions
- Memory exhaustion
- System overload

### Execution Time Limits
Jobs have maximum execution times:
- **Vacuum**: 10 minutes (configurable)
- Prevents jobs from running indefinitely

---

## Best Practices

### Production Configuration

```yaml
vacuum:
  period-minutes: 5
  retention-hours: 4              # Longer retention for production

statistics:
  period-minutes: 60              # Less frequent in production
  sample-rate: 0.05               # 5% sample for very large tables

constraint-checker:
  period-minutes: 60              # Less frequent due to cost
  rate-limit-keys-per-hour: 5000  # More conservative rate limit
```

### Monitoring
1. Check logs regularly for warnings
2. Monitor health check endpoints
3. Watch for constraint violations
4. Track vacuum effectiveness (freed versions)

### Troubleshooting

#### Vacuum Not Freeing Space
- Check retention period (may be too long)
- Verify old versions exist (check MVCC timestamps)
- Look for long-running transactions blocking cleanup

#### Statistics Not Updating
- Check if job is enabled
- Verify tables meet min-rows-threshold
- Look for errors in logs

#### Constraint Violations Found
- Review application logic for constraint enforcement
- Check for data import issues
- Investigate concurrent transaction patterns

---

## Summary

Background jobs in KV mode provide:
- **Automatic maintenance**: No manual intervention required
- **Data integrity**: Continuous validation of constraints and indexes
- **Performance optimization**: Statistics for cost-based optimizer
- **Storage efficiency**: Cleanup of old versions and deleted data

All jobs are configurable, monitorable, and can be disabled if needed.




