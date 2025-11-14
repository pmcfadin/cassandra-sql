# Cassandra-SQL: A PostgreSQL-Like SQL Layer for Apache Cassandra

[![Status](https://img.shields.io/badge/status-Proof%20of%20Concept-yellow)]()
[![Java](https://img.shields.io/badge/java-17%2B-blue)]()
[![Cassandra](https://img.shields.io/badge/cassandra-5.0%2B-green)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

---

## ⚠️ Important: This is a Proof of Concept

**This project is NOT production-ready and should NOT be used in production environments.**

This is a DEMONSTRATION project exploring the feasibility of building a full SQL
layer with ACID transactions on top of Apache Cassandra. It is intended for **Proof-of-concept demonstrations only**. The intent is to spark a discussion about what "SQL" could look like 
for Apache Cassandra, if the Cassandra community believes that's an appropriate 
direction. This is to demonstrate that one COULD build a SQL-like database
on top of Cassandra, without worrying about problems like wide partitions. 

**Motivation**
This type of project approaches feasibility in 2026 due to a series of 
recent improvements, the combination of which start to provide us with the
primitives one needs to build a distributed SQL database.

In particular:
- Transactional Cluster Metadata makes it viable to run ByteOrderedPartitioner, 
  because the frequent range splitting should become safe and reliable. 
- Accord provides multi-key transactions, which allows us to break out of the
  classic Cassandra partition boundary

Without these two improvements, one would be forced to try to translate SQL
types and grammar into CQL partitions / model, and deal with wide partitions and 
similar classic Cassandra data model challenges.

With these two improvements, one can treat Cassandra as an ordered, transactional
key-value store, which is the same basic interface nearly every other distributed
SQL database builds upon. 


**Known Limitations:**
- ⚠️ **Not intended for production use** - This is a proof of concept only. Don't run this in production.
- ⚠️ **Security features excluded** - Authentication, authorization, and encryption are deliberately excluded to prevent production use. Don't run this in production. 
- ⚠️ **Performance not optimized** - Not optimized for production workloads. There are numerous patterns here that are safe but not performant. For example, Sequences / IDs likely hot spot onto a single host/process in real life. That's not great. 
- ⚠️ **Incomplete error handling** - Many edge cases and error conditions not fully handled. 
- ⚠️ **Limited test coverage** - See [docs/TESTING.md](docs/TESTING.md) for details
- ⚠️ **No formal correctness proofs** - Basic testing exists (see [src/test/java/com/geico/poc/cassandrasql/kv/TransactionSafetyTest.java](src/test/java/com/geico/poc/cassandrasql/kv/TransactionSafetyTest.java)) but no formal verification

**Cassandra Limitations:**
- ⚠️ **Cassandra doesn't support reverse range reads** - Some orderings are profoundly slow
- ⚠️ **Accord doesn't support variable sized keys** - Byte Order Partitioner + Accord is poorly tested, journals are not compacting, gets slower over time
 
---

## Overview

Cassandra-SQL provides a PostgreSQL wire protocol interface to Apache Cassandra, enabling standard SQL queries with ACID transaction support. The project demonstrates how to build a distributed SQL database by combining:

- **Apache Cassandra** for distributed storage and replication
- **Cassandra Accord** for distributed transactions and consensus
- **Apache Calcite** for SQL parsing and query planning
- **PostgreSQL Wire Protocol** for client compatibility

### Architecture Highlights

- **KV Storage Backend**: MVCC-based key-value storage with optimistic concurrency control
  - Implementation: [`kv/KvStore.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvStore.java)
- **Percolator-Style Transactions**: Two-phase commit protocol for ACID guarantees
  - Implementation: [`kv/KvTransactionCoordinator.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvTransactionCoordinator.java)
  - Uses Accord transactions for atomicity - see [docs/TRANSACTIONS.md](docs/TRANSACTIONS.md)
- **Cost-Based Query Optimizer**: Index selection, join ordering, and predicate pushdown
  - Implementation: [`optimizer/QueryOptimizer.java`](src/main/java/com/geico/poc/cassandrasql/optimizer/QueryOptimizer.java)
- **Lazy Schema Operations**: O(1) DROP TABLE and TRUNCATE using table versioning
  - Verified by: [`LazyDropTest.java`](src/test/java/com/geico/poc/cassandrasql/LazyDropTest.java)
- **Background Jobs**: Vacuum, constraint checking, and index consistency maintenance
  - See [docs/BACKGROUND_JOBS.md](docs/BACKGROUND_JOBS.md)

---

## Quick Start

### Prerequisites

- **Java 17 or higher**
- **Apache Cassandra 5.0+** with Accord enabled
- **PostgreSQL client** (`psql`) for testing

### Required Cassandra Configuration

Cassandra must be configured with Accord enabled in `cassandra.yaml`:

```yaml
# Set the partitioner to ByteOrderedPartitioner
partitioner: org.apache.cassandra.dht.ByteOrderedPartitioner

# Enable accord
accord:
  enabled: true
```

**⚠️ Important**: ByteOrderedPartitioner requires Transactional Cluster Metadata (available in Cassandra 5.0+). Without this, frequent (especially automated)  range movements are untenable. See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for complete configuration details.

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/geico/cassandra-sql.git
   cd cassandra-sql
   ```

2. **Start Cassandra**:
   ```bash
   ./bin/start-cassandra.sh
   ```

3. **Build and run the SQL server**:
   ```bash
   ./gradlew bootRun
   ```

4. **Connect with psql**:
   ```bash
   psql -h localhost -p 5432 -d cassandra_sql
   ```

### Running the Demo

An e-commerce demo script showcases the SQL capabilities:

```bash
./demo-ecommerce.sh
```

This demonstrates:
- Complex DDL (tables, indexes, enums, views)
- Transactions with multiple statements
- JOINs, aggregations, and subqueries
- Materialized views

See [docs/DEMO_ECOMMERCE.md](docs/DEMO_ECOMMERCE.md) for detailed demo walkthrough.

---

## SQL Feature Support

See [docs/SQL_GRAMMAR.md](docs/SQL_GRAMMAR.md) for detailed implementation status.

### Supported Features

**DDL (Data Definition Language)**:
- ✅ CREATE/DROP TABLE
- ✅ CREATE/DROP INDEX
- ✅ CREATE/DROP VIEW (including MATERIALIZED VIEW)
- ✅ CREATE TYPE (ENUM)
- ✅ ALTER TABLE (limited)
- ✅ TRUNCATE TABLE

**DML (Data Manipulation Language)**:
- ✅ SELECT with WHERE, ORDER BY, LIMIT, OFFSET
- ✅ INSERT, UPDATE, DELETE
- ✅ INNER JOIN, LEFT JOIN, RIGHT JOIN, FULL OUTER JOIN
- ✅ Subqueries (correlated and uncorrelated) - see [`kv/FromSubqueryTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/FromSubqueryTest.java) and [`SubqueryEnhancedTest.java`](src/test/java/com/geico/poc/cassandrasql/SubqueryEnhancedTest.java)
- ✅ UNION, UNION ALL - see [`kv/UnionQueryTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/UnionQueryTest.java)
- ✅ Common Table Expressions (WITH) - see [`kv/DemoScriptFeaturesTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/DemoScriptFeaturesTest.java)

**Data Types**:
- ✅ INT, BIGINT, DOUBLE, DECIMAL
- ✅ VARCHAR, TEXT
- ✅ BOOLEAN
- ✅ TIMESTAMP
- ✅ ARRAY
- ✅ ENUM (custom types)

**Aggregations**:
- ✅ COUNT, SUM, AVG, MIN, MAX
- ✅ GROUP BY, HAVING
- ✅ DISTINCT

**Transactions**:
- ✅ BEGIN, COMMIT, ROLLBACK - see [`kv/SimpleTransactionTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/SimpleTransactionTest.java)
- ✅ Multi-statement transactions - see [`kv/TransactionAtomicityTest.testMultiKeyAtomicity()`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java)
- ✅ Serializable isolation level - see [`kv/TransactionAtomicityTest.testSnapshotIsolation()`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java)

### Not Supported / Incomplete

- ❌ **Authentication** - Deliberately excluded so you don't use this in production
- ❌ **Foreign keys** - Constraints defined but not enforced (see [`kv/ForeignKeyConstraintTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/ForeignKeyConstraintTest.java) for current status)
- ❌ **Triggers** - Not implemented
- ❌ **Stored procedures** - Not implemented
- ❌ **Window functions** - Parsed but not executed (see [`window/WindowFunctionTest.java`](src/test/java/com/geico/poc/cassandrasql/window/WindowFunctionTest.java))
- ❌ **Full-text search** - Not implemented
- ❌ **JSON/JSONB types** - Not implemented
- ❌ **User-defined functions** - Not implemented
- ❌ **Partitioning** - Not implemented
- ❌ **Replication control** - Uses Cassandra's native replication

---

## Comparison with PostgreSQL

See [docs/COMPARISON.md](docs/COMPARISON.md) for a detailed comparison.

### Key Differences

| Feature | PostgreSQL | Cassandra-SQL |
|---------|-----------|---------------|
| **Architecture** | Single-node or primary-replica | Distributed, leaderless |
| **Consistency** | Strong (ACID) | Strong (Accord enabled tables) |
| **Transactions** | MVCC with 2PL | Percolator-like 2PC with Accord |
| **Scalability** | Vertical (with read replicas) | Horizontal (distributed) |
| **SQL Compliance** | ~99% | ~40% (core features only) |
| **Maturity** | Production-ready (30+ years) | Proof of concept (Do not use) |
| **Performance** | Optimized for single-node | Not optimized |
| **Use Case** | General-purpose OLTP | Distributed OLTP (experimental) |

### When to Use PostgreSQL

- ✅ Production applications
- ✅ Complex SQL queries and analytics
- ✅ Strong consistency requirements
- ✅ Mature ecosystem and tooling
- ✅ Single-datacenter deployments

### When to Use Cassandra-SQL (Experimental)

- 🔬 Convincing Cassandra Engineers that SQL is closer than they thought

**Again: Do NOT use in production!**

---

## Transaction Safety

See [docs/TRANSACTIONS.md](docs/TRANSACTIONS.md) for detailed information.

### Isolation Level

Cassandra-SQL implements **Serializable** isolation using a Percolator-style two-phase commit protocol:

1. **Prewrite Phase**: Acquire locks and write uncommitted data
2. **Commit Phase**: Atomically commit primary lock, then secondary locks

### Guarantees (Intended, Not Formally Verified)

- ✅ **Atomicity**: All statements in a transaction commit or rollback together
  - Verified by: [`kv/TransactionAtomicityTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java)
- ✅ **Consistency**: Constraints are checked (though enforcement is limited)
- ✅ **Isolation**: Transactions are serializable (no dirty reads, no phantom reads)
  - Verified by: [`kv/TransactionAtomicityTest.testSnapshotIsolation()`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java)
- ✅ **Durability**: Committed data persists (via Cassandra replication)

### Caveats

- ⚠️ **Not formally verified**: The implementation has not been proven correct
  - See [docs/TRANSACTIONS.md](docs/TRANSACTIONS.md) for detailed warnings
- ⚠️ **Will contain bugs**: Edge cases and race conditions almost certainly exist
- ⚠️ **Limited testing**: Test coverage is incomplete (~95 test files, many edge cases not covered)
  - See [docs/TESTING.md](docs/TESTING.md) for test coverage details
- ⚠️ **Lock conflicts**: Concurrent writes to the same keys will conflict and abort
  - Tested by: [`kv/SimpleConflictTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/SimpleConflictTest.java)

### Test Coverage

See [src/test/java/com/geico/poc/cassandrasql/](src/test/java/com/geico/poc/cassandrasql/) for transaction tests:

- [`kv/TransactionSafetyTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionSafetyTest.java) - Atomic lock acquisition, commit verification, timestamp allocation
- [`kv/TransactionAtomicityTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java) - Multi-key atomicity, snapshot isolation, write conflicts
- [`kv/PercolatorTransactionTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/PercolatorTransactionTest.java) - Percolator protocol correctness
- [`kv/SimpleTransactionTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/SimpleTransactionTest.java) - Basic transaction semantics
- [`kv/TransactionCorrectnessTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionCorrectnessTest.java) - Transaction correctness verification
- [`kv/KvCorrectnessTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/KvCorrectnessTest.java) - MVCC snapshot isolation, delete/update correctness
- [`LazyDropTest.java`](src/test/java/com/geico/poc/cassandrasql/LazyDropTest.java) - Table versioning and vacuum

---

## Testing

See [docs/TESTING.md](docs/TESTING.md) for testing philosophy and coverage.

### Test Categories

1. **Unit Tests**: Individual component testing
2. **Integration Tests**: End-to-end SQL execution
3. **Transaction Tests**: ACID property verification
4. **Concurrency Tests**: Multi-client scenarios
5. **Performance Tests**: Benchmarking (not comprehensive)

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.geico.poc.cassandrasql.LazyDropTest"

# Run with verbose output
./gradlew test --info
```

### Test Philosophy

- **Test what matters**: Focus on correctness of core features
- **Integration over unit**: Prefer end-to-end tests for SQL semantics
- **Isolation**: Each test cleans up after itself
- **Deterministic**: Tests should pass consistently
- **Fast feedback**: Tests should run quickly (< 1 minute per class)

### Current Test Status

- ✅ **95+ Java test files** (see [src/test/java/com/geico/poc/cassandrasql/](src/test/java/com/geico/poc/cassandrasql/))
- ✅ **Core SQL operations** (SELECT, INSERT, UPDATE, DELETE) - see [`kv/KvCorrectnessTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/KvCorrectnessTest.java)
- ✅ **JOIN execution** (binary and multi-way) - see [`kv/JoinOrderByLimitTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/JoinOrderByLimitTest.java)
- ✅ **Transaction isolation** - see [`kv/TransactionAtomicityTest.java`](src/test/java/com/geico/poc/cassandrasql/kv/TransactionAtomicityTest.java)
- ✅ **Lazy DROP TABLE and TRUNCATE** - see [`LazyDropTest.java`](src/test/java/com/geico/poc/cassandrasql/LazyDropTest.java) and [`TruncateTest.java`](src/test/java/com/geico/poc/cassandrasql/TruncateTest.java)
- ⚠️ **Some edge cases not covered** - See [docs/TESTING.md](docs/TESTING.md) for coverage gaps
- ⚠️ **Performance tests are minimal** - No comprehensive benchmarking suite

---

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation. Key components are implemented in:

- **Transaction Coordinator**: [`kv/KvTransactionCoordinator.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvTransactionCoordinator.java) - Percolator-style two-phase commit
- **KV Store**: [`kv/KvStore.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvStore.java) - MVCC key-value storage
- **Query Executor**: [`kv/KvQueryExecutor.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvQueryExecutor.java) - SQL query execution
- **Timestamp Oracle**: [`kv/TimestampOracle.java`](src/main/java/com/geico/poc/cassandrasql/kv/TimestampOracle.java) - Distributed timestamp allocation

### High-Level Components

```
┌─────────────────────────────────────────────────────────────┐
│                    PostgreSQL Clients                        │
│                  (psql, JDBC, pgAdmin, etc.)                 │
└────────────────────────┬────────────────────────────────────┘
                         │ PostgreSQL Wire Protocol
┌────────────────────────▼────────────────────────────────────┐
│                  PostgreSQL Protocol Server                 │
│              (PostgresConnectionHandler.java)               │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                     Query Service                           │
│                  (QueryService.java)                        │
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

### Key Design Decisions

1. **KV Storage Backend**: All data stored as key-value pairs with encoded keys
   - See [`kv/KeyEncoder.java`](src/main/java/com/geico/poc/cassandrasql/kv/KeyEncoder.java) for key encoding logic
   - See [`bin/decode-key.sh`](bin/decode-key.sh) for key decoding utility
2. **MVCC**: Multi-version concurrency control with timestamp-based versioning
   - See [`kv/KvStore.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvStore.java) for MVCC implementation
3. **Percolator Transactions**: Two-phase commit with lock tables
   - See [`kv/KvTransactionCoordinator.java`](src/main/java/com/geico/poc/cassandrasql/kv/KvTransactionCoordinator.java) and [docs/TRANSACTIONS.md](docs/TRANSACTIONS.md)
4. **Lazy Schema Operations**: Table versioning instead of immediate data deletion
   - Verified by: [`LazyDropTest.java`](src/test/java/com/geico/poc/cassandrasql/LazyDropTest.java)
5. **Background Jobs**: Asynchronous vacuum and constraint checking
   - See [docs/BACKGROUND_JOBS.md](docs/BACKGROUND_JOBS.md) for details

---

## Configuration

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for all configuration options.

### Key Settings

```yaml
# application.yml

cassandra-sql:
  # PostgreSQL protocol server
  postgres:
    port: 5432
    max-connections: 100
  
  # Cassandra connection
  cassandra:
    contact-points: localhost
    port: 9042
    keyspace: cassandra_sql
  
  # Background jobs
  background-jobs:
    vacuum:
      enabled: true
      interval-minutes: 60
      retention-hours: 24
```

---

## Contributing

This is a proof-of-concept project. Contributions are welcome for:

- Bug fixes
- Test coverage improvements
- Documentation improvements
- Performance optimizations
- New SQL features

**Important**: This project is **not intended for production use**. Major architectural changes should be discussed first via GitHub issues.

### Development Setup

1. **Prerequisites**: Java 17+, Apache Cassandra 5.0+ with Accord enabled
2. **Build**: `./gradlew build`
3. **Test**: `./gradlew test`
4. **Run**: `./gradlew bootRun`

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for detailed setup instructions.

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---

## Acknowledgments

- **Apache Cassandra** - Distributed database foundation
- **Apache Calcite** - SQL parsing and query planning
- **PostgreSQL** - Wire protocol specification
- **Google Percolator** - Transaction protocol inspiration

---

## Contact

For questions, issues, or discussions, please open an issue on GitHub.

---

## Disclaimer

This software is provided "as is", without warranty of any kind, express or implied. The authors and contributors are not liable for any damages arising from the use of this software.

**Use at your own risk. Do not use in production.**
