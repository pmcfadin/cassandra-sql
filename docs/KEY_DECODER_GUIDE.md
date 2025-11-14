# Key Decoder Guide

## Quick Answer for Your Log

**Key**: `0x0500000000000000030000000000000000726f7769645f736571`

**Decoded**:
- **Type**: Sequence key (NAMESPACE_SEQUENCE = 0x05)
- **Table ID**: 3
- **Sequence Name**: `rowid_seq`

**What's happening**: This is the rowid sequence for table ID 3. The high read count (4500+ live rows, 28625 tombstones) indicates:
1. **Concurrent inserts** into table 3 are allocating rowids
2. The **synchronized retry logic** is working but creating many MVCC versions
3. Each retry creates a tombstone; successful allocations create live rows
4. This is expected behavior during high-concurrency benchmarks

**Action**: The VacuumJob will eventually clean up the tombstones. This is normal for a sequence under high contention.

---

## Usage

### Shell Script

```bash
./decode-key.sh <hex_key>
```

**Examples**:
```bash
# Decode a sequence key
./decode-key.sh 0x0500000000000000030000000000000000726f7769645f736571

# Decode a table data key
./decode-key.sh 0x010000000000000042000000000000000000000007b000000499602d2

# Decode from Cassandra logs (copy the hex directly)
./decode-key.sh 0500000000000000030000000000000000726f7769645f736571
```

### Java Unit Test

```bash
./gradlew test --tests "*KeyDecoderTest*"
```

---

## Key Structure Reference

### 1. TABLE_DATA (0x01)
**Format**: `[01][TableID:8][IndexID:8][PKValues][Timestamp:8]`

**Example**: Primary key data for table 42
```
01 0000000000000042 0000000000000000 [pk_values] [timestamp]
```

### 2. SCHEMA (0x02)
**Format**: `[02][TableID:8][Reserved:8][MetadataType]`

**Example**: Schema metadata for table 100
```
02 0000000000000064 0000000000000000 736368656d61
                                        ^^^^^^^^^^^^^^
                                        "schema" in UTF-8
```

### 3. INDEX (0x03)
**Format**: `[03][TableID:8][IndexID:8][IndexKey][PKValues][Timestamp:8]`

**Example**: Secondary index entry
```
03 0000000000000042 0000000000000001 [index_key] [pk] [ts]
```

### 4. TRANSACTION (0x04)
**Format**: `[04][UUID:16]`

**Example**: Transaction metadata
```
04 550e8400e29b41d4a716446655440000
   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   UUID (16 bytes)
```

### 5. SEQUENCE (0x05) ⭐ Your Case
**Format**: `[05][TableID:8][Reserved:8][SequenceName]`

**Example**: rowid_seq for table 3
```
05 0000000000000003 0000000000000000 726f7769645f736571
                                      ^^^^^^^^^^^^^^^^^^^^
                                      "rowid_seq" in UTF-8
```

**Common Sequences**:
- `rowid_seq` - Auto-generated rowids for tables without explicit PK
- `table_id_seq` - Global table ID allocation
- `view_id_seq` - Global view ID allocation

### 6. LOCK (0x06)
**Format**: `[06][LockedKey]`

The locked key can be any of the above types (recursively decoded).

### 7. WRITE (0x07)
**Format**: `[07][TxID:16][DataKey]`

Buffered writes in a transaction. The data key can be any TABLE_DATA or INDEX key.

---

## Interpreting High Tombstone Counts

**Your log**: `Read 4542 live rows and 28625 tombstone cells`

This pattern indicates:
1. **Many MVCC versions** - Each update/retry creates a new version
2. **Sequence contention** - Multiple threads competing for the same sequence
3. **Normal for sequences** - Sequences are hot keys by design
4. **VacuumJob will clean up** - Tombstones older than transactions will be removed

**Performance Impact**:
- Read amplification: Cassandra must scan through tombstones
- Compaction overhead: More tombstones = more compaction work
- **Solution**: The VacuumJob runs periodically to clean old versions

**Tuning Options** (if needed):
1. Increase `SEQUENCE_BATCH_SIZE` (currently 1 for safety)
2. Reduce concurrent inserts to the same table
3. Increase VacuumJob frequency
4. Use multiple tables to distribute load

---

## Debugging Workflow

### Step 1: Identify the Key
Copy the hex key from Cassandra logs:
```
key = 0x0500000000000000030000000000000000726f7769645f736571
```

### Step 2: Decode It
```bash
./decode-key.sh 0x0500000000000000030000000000000000726f7769645f736571
```

### Step 3: Find the Table
```sql
-- If it's a sequence key, find the table
SELECT table_name FROM pg_class WHERE oid = 3;

-- Or query SchemaManager
SELECT * FROM pg_tables WHERE tablename IN (
  SELECT table_name FROM information_schema.tables
);
```

### Step 4: Investigate
- Check if the table is under heavy write load
- Look for concurrent transactions
- Check VacuumJob logs for cleanup activity
- Monitor tombstone warnings over time

---

## Common Patterns

### Pattern 1: Sequence Contention
```
NAMESPACE_SEQUENCE + high tombstone count
→ Many threads allocating from same sequence
→ Expected during concurrent inserts
→ Will be cleaned by VacuumJob
```

### Pattern 2: Transaction Lock Conflicts
```
NAMESPACE_LOCK + same key repeatedly
→ Lock contention on a hot key
→ Transactions retrying
→ May indicate need for application-level batching
```

### Pattern 3: Index Build
```
NAMESPACE_INDEX + sequential reads
→ Index being built or rebuilt
→ Expected during CREATE INDEX
→ Will complete eventually
```

### Pattern 4: Schema Changes
```
NAMESPACE_SCHEMA + multiple versions
→ Table being altered (ADD COLUMN, etc.)
→ Old schema versions being kept for MVCC
→ Will be cleaned after all transactions complete
```

---

## Tools Summary

| Tool | Purpose | Usage |
|------|---------|-------|
| `decode-key.sh` | Decode any hex key | `./decode-key.sh <hex>` |
| `KeyDecoderTest` | Programmatic decoding | `./gradlew test --tests "*KeyDecoder*"` |
| Cassandra logs | Find problematic keys | `grep "tombstone_warn" cassandra.log` |
| VacuumJob logs | Track cleanup | `grep "VacuumJob" application.log` |

---

## Next Steps

1. **Monitor**: Watch if tombstone count grows or stabilizes
2. **Measure**: Check if benchmark performance is acceptable
3. **Tune**: If needed, adjust VacuumJob settings or sequence batching
4. **Clean**: Let VacuumJob run to clean up old versions

The current behavior is **expected and safe** for high-concurrency workloads.


