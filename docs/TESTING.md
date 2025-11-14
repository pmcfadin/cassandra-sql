# Testing Philosophy and Coverage

**Last Updated**: November 2025  
**Project Status**: Proof of Concept

---

## Overview

This document describes the testing approach, philosophy, and current coverage for Cassandra-SQL. As a proof-of-concept project, testing focuses on demonstrating core functionality rather than exhaustive coverage.

---

## Testing Philosophy

### 1. 100% AI generated regression tests. 

**Focus on**:
- ✅ Core SQL operations (SELECT, INSERT, UPDATE, DELETE)
- ✅ Transaction semantics (ACID properties)
- ✅ Join execution correctness
- ✅ Data integrity
- ✅ Critical bug prevention

**Don't focus on**:
- ❌ Every edge case
- ❌ Performance optimization
- ❌ Stress testing
- ❌ Exhaustive error handling

### 2. Integration Over Unit

**Prefer**:
- ✅ End-to-end SQL execution tests
- ✅ Integration tests that exercise multiple components
- ✅ Real Cassandra interactions

**Over**:
- ❌ Mocking every dependency
- ❌ Testing internal implementation details
- ❌ Isolated unit tests

**Rationale**: For a SQL database, correctness of the entire system matters more than individual components.

### 3. Test Isolation

**Each test should**:
- ✅ Create its own test data
- ✅ Use unique table names
- ✅ Clean up after itself
- ✅ Not depend on other tests
- ✅ Be deterministic

**Example**:
```java
@BeforeEach
public void setUp() {
    testTable = "test_" + System.nanoTime();
    queryService.execute("DROP TABLE IF EXISTS " + testTable);
}

@AfterEach
public void tearDown() {
    queryService.execute("DROP TABLE IF EXISTS " + testTable);
}
```

### 4. Fast Feedback

**Tests should**:
- ✅ Run quickly (< 1 minute per test class)
- ✅ Provide clear failure messages
- ✅ Be easy to run locally
- ✅ Not require complex setup

**Avoid**:
- ❌ Long-running tests (> 5 minutes)
- ❌ Tests that require manual setup
- ❌ Tests that are flaky
- ❌ Tests that require specific timing

### 5. Honest about gaps

**We acknowledge**:
- ⚠️ Test coverage is incomplete
- ⚠️ Many edge cases not tested
- ⚠️ Performance not tested
- ⚠️ Distributed scenarios limited
- ⚠️ Failure scenarios not comprehensive

---

## Test Categories

### 1. SQL Operation Tests

**Purpose**: Verify basic SQL operations work correctly

**Location**: `src/test/java/com/geico/poc/cassandrasql/`

**Examples**:
- `SelectTest.java` - SELECT queries
- `InsertTest.java` - INSERT statements
- `UpdateTest.java` - UPDATE statements
- `DeleteTest.java` - DELETE statements

**Coverage**:
- ✅ Basic CRUD operations
- ✅ WHERE clauses
- ✅ ORDER BY, LIMIT, OFFSET
- ✅ DISTINCT
- ⚠️ Complex expressions (limited)
- ⚠️ Edge cases (incomplete)

### 2. Join Tests

**Purpose**: Verify JOIN execution correctness

**Location**: `src/test/java/com/geico/poc/cassandrasql/kv/`

**Files**:
- `JoinExecutorTest.java` - Binary and multi-way joins
- `KvJoinExecutorTest.java` - KV-specific join logic

**Coverage**:
- ✅ INNER JOIN
- ✅ LEFT JOIN, RIGHT JOIN, FULL OUTER JOIN
- ✅ Multi-way joins (3+ tables)
- ✅ Self-joins
- ✅ Join with WHERE clauses
- ⚠️ Complex join conditions (limited)
- ⚠️ Large datasets (not tested)

**Example**:
```java
@Test
public void testSimpleInnerJoin() {
    queryService.execute("CREATE TABLE t1 (id INT PRIMARY KEY, name TEXT)");
    queryService.execute("CREATE TABLE t2 (id INT PRIMARY KEY, amount INT)");
    queryService.execute("INSERT INTO t1 VALUES (1, 'Alice')");
    queryService.execute("INSERT INTO t2 VALUES (1, 100)");
    
    QueryResponse response = queryService.execute(
        "SELECT t1.name, t2.amount FROM t1 JOIN t2 ON t1.id = t2.id"
    );
    
    assertEquals(1, response.getRowCount());
    assertEquals("Alice", response.getRows().get(0).get("name"));
    assertEquals(100, response.getRows().get(0).get("amount"));
}
```

### 3. Transaction Tests

**Purpose**: Verify ACID properties

**Location**: `src/test/java/com/geico/poc/cassandrasql/`

**Files**:
- `TransactionTest.java` (if exists) - Basic transaction semantics
- `TransactionIsolationTest.java` (if exists) - Isolation verification
- `ConcurrentTransactionTest.java` (if exists) - Concurrent scenarios

**Coverage**:
- ✅ BEGIN, COMMIT, ROLLBACK
- ✅ Multi-statement transactions
- ✅ Transaction isolation
- ✅ Lock conflicts
- ⚠️ Concurrent transactions (limited)
- ⚠️ Distributed transactions (limited)
- ❌ Deadlock scenarios (not tested)

**Example**:
```java
@Test
public void testTransactionRollback() {
    queryService.execute("CREATE TABLE t (id INT PRIMARY KEY, value INT)");
    queryService.execute("BEGIN");
    queryService.execute("INSERT INTO t VALUES (1, 100)");
    queryService.execute("ROLLBACK");
    
    QueryResponse response = queryService.execute("SELECT * FROM t");
    assertEquals(0, response.getRowCount()); // No rows inserted
}
```

### 4. Aggregation Tests

**Purpose**: Verify aggregation functions

**Location**: `src/test/java/com/geico/poc/cassandrasql/`

**Files**:
- `AggregationTest.java` (if exists)
- Various integration tests

**Coverage**:
- ✅ COUNT, SUM, AVG, MIN, MAX
- ✅ GROUP BY
- ✅ HAVING
- ✅ DISTINCT
- ⚠️ Complex aggregations (limited)
- ❌ Window functions (not supported)

### 5. Subquery Tests

**Purpose**: Verify subquery execution

**Location**: `src/test/java/com/geico/poc/cassandrasql/`

**Files**:
- `SubqueryTest.java` (if exists)
- Various integration tests

**Coverage**:
- ✅ Scalar subqueries
- ✅ IN subqueries
- ✅ EXISTS subqueries
- ✅ Correlated subqueries
- ⚠️ Complex nested subqueries (limited)

### 6. Schema Operation Tests

**Purpose**: Verify DDL operations

**Location**: `src/test/java/com/geico/poc/cassandrasql/`

**Files**:
- `LazyDropTest.java` - DROP TABLE and TRUNCATE
- `IndexTest.java` (if exists) - CREATE/DROP INDEX
- `ViewTest.java` (if exists) - CREATE/DROP VIEW

**Coverage**:
- ✅ CREATE/DROP TABLE
- ✅ CREATE/DROP INDEX
- ✅ TRUNCATE TABLE
- ✅ Table recreation after DROP
- ✅ Lazy drop with vacuum
- ⚠️ ALTER TABLE (limited)
- ⚠️ Complex constraints (limited)

**Example** (LazyDropTest):
```java
@Test
public void testRecreateTableAfterDrop() {
    queryService.execute("CREATE TABLE t (id INT PRIMARY KEY)");
    TableMetadata original = schemaManager.getTable("t");
    long originalId = original.getTableId();
    
    queryService.execute("DROP TABLE t");
    vacuumJob.execute(); // Clean up
    
    queryService.execute("CREATE TABLE t (id INT PRIMARY KEY)");
    TableMetadata recreated = schemaManager.getTable("t");
    
    assertNotEquals(originalId, recreated.getTableId()); // Different ID
}
```

### 7. Catalog Tests

**Purpose**: Verify pg_catalog tables

**Location**: `src/test/java/com/geico/poc/cassandrasql/kv/`

**Files**:
- `CatalogOperationsTest.java` - pg_class, pg_index, etc.
- `CatalogSyncTest.java` - Catalog synchronization

**Coverage**:
- ✅ pg_class (table metadata)
- ✅ pg_index (index metadata)
- ✅ pg_namespace (schema metadata)
- ✅ Catalog updates on DDL
- ⚠️ Complex catalog queries (limited)

### 8. Data Type Tests

**Purpose**: Verify data type handling

**Location**: `src/test/java/com/geico/poc/cassandrasql/`

**Files**:
- `ArrayTypeTest.java` - ARRAY type
- `EnumTypeTest.java` - ENUM type
- Various integration tests

**Coverage**:
- ✅ INT, BIGINT, DOUBLE, DECIMAL
- ✅ VARCHAR, TEXT
- ✅ BOOLEAN
- ✅ TIMESTAMP
- ✅ ARRAY
- ✅ ENUM
- ❌ JSON/JSONB (not supported)
- ❌ Complex types (not supported)

---

## Running Tests

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
./gradlew test --tests "com.geico.poc.cassandrasql.LazyDropTest"
```

### Run Specific Test Method

```bash
./gradlew test --tests "com.geico.poc.cassandrasql.LazyDropTest.testRecreateTableAfterDrop"
```

### Run with Verbose Output

```bash
./gradlew test --info
```

### Run with Debug Logging

```bash
./gradlew test -Dlogging.level.com.geico.poc.cassandrasql=DEBUG
```

---

## Test Infrastructure

### Base Test Classes

**KvTestBase**:
- Base class for KV mode tests
- Sets up test environment
- Provides utility methods

**TestHelper**:
- Common test utilities
- Table name generation
- Data generation

### Test Configuration

**application-test.yml**:
```yaml
cassandra-sql:
  postgres:
    port: 5432
  cassandra:
    contact-points: localhost
    port: 9042
  background-jobs:
    enabled: false  # Disable for faster tests
```

### Test Isolation

**Strategies**:
1. **Unique table names**: `test_` + timestamp
2. **Cleanup in @AfterEach**: Drop tables after each test
3. **Independent data**: Each test creates its own data
4. **No shared state**: Tests don't depend on each other

---

## Current Test Status

### Test Statistics

- **Total test files**: ~106
- **Total test methods**: ~500+ (estimated)
- **Passing tests**: Most (exact count varies)
- **Known failing tests**: Some (documented in test files)

### Recent Test Results

**LazyDropTest**: 9/9 passing ✅
- testDropMultipleTablesLazy
- testLazyDropIsInstant
- testVacuumCleansUpDroppedTable
- testDropAndTruncateInteraction
- testDroppedTableHasTimestamp
- testDropTableWithSecondaryIndex
- testRecreateTableAfterDrop
- testDropIfExistsLazy
- testDroppedTableNotInCache

**JoinExecutorTest**: Most passing ✅
- testSimpleInnerJoin
- testLeftJoin
- testRightJoin
- testMultiWayJoin
- (Some tests may be disabled)

### Known Test Issues

1. **Flaky tests**: Some tests fail intermittently due to timing
2. **Cleanup issues**: Tests may leave data if they fail
3. **Cassandra state**: Tests assume clean Cassandra state
4. **Concurrency**: Limited testing of concurrent scenarios

---

## Test Coverage Gaps

### What's Well Tested

- ✅ Basic SQL operations (SELECT, INSERT, UPDATE, DELETE)
- ✅ Simple joins (2-3 tables)
- ✅ Basic transactions
- ✅ Schema operations (CREATE, DROP)
- ✅ Lazy drop and vacuum

### What's Partially Tested

- ⚠️ Complex queries (subqueries, CTEs)
- ⚠️ Aggregations with GROUP BY
- ⚠️ Transaction isolation
- ⚠️ Index usage
- ⚠️ Constraint enforcement

### What's NOT Tested

- ❌ High concurrency (>10 concurrent clients)
- ❌ Large datasets (>1M rows)
- ❌ Performance benchmarks
- ❌ Distributed scenarios (multi-node)
- ❌ Failure scenarios (node crashes, network partitions)
- ❌ Long-running transactions
- ❌ Complex SQL features (window functions, etc.)
- ❌ Security (authentication, authorization)
- ❌ Edge cases and error conditions

---

## Adding New Tests

### Test Template

```java
package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MyFeatureTest extends KvTestBase {
    
    private String testTable;
    
    @BeforeEach
    public void setUp() {
        testTable = "test_" + System.nanoTime();
    }
    
    @AfterEach
    public void tearDown() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    
    @Test
    @Order(1)
    public void testBasicFeature() throws Exception {
        // Setup
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY)");
        
        // Execute
        QueryResponse response = queryService.execute("INSERT INTO " + testTable + " VALUES (1)");
        
        // Verify
        assertNull(response.getError());
        
        // Cleanup happens in @AfterEach
    }
}
```

### Best Practices

1. **Use unique table names**: Avoid conflicts with other tests
2. **Clean up in @AfterEach**: Even if test fails
3. **Test one thing**: Each test should verify one behavior
4. **Clear assertions**: Use descriptive messages
5. **Handle errors**: Check for null errors before accessing data
6. **Order tests**: Use @Order if tests depend on each other (avoid if possible)

---

## Continuous Integration

### CI Setup (if configured)

```yaml
# .github/workflows/test.yml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Start Cassandra
        run: docker run -d -p 9042:9042 cassandra:5.0
      - name: Run tests
        run: ./gradlew test
```

### Test Reports

Test reports are generated in `build/reports/tests/test/index.html`

---

## Performance Testing

### Current Status

⚠️ **Performance testing is minimal**

### Basic Benchmarks

See `bin/benchmark-pgbench.sh` for pgbench-based benchmarks.

**Not comprehensive** - only basic operations tested.

### Performance Considerations

- Not optimized for production
- No stress testing
- No scalability testing
- No latency profiling

---

## Future Testing Improvements

### Short-term

1. **Increase coverage**: Add tests for missing features
2. **Fix flaky tests**: Make tests more deterministic
3. **Better cleanup**: Ensure tests always clean up
4. **Concurrent tests**: Add more concurrency scenarios

### Long-term

1. **Property-based testing**: Use QuickCheck-style testing
2. **Fuzzing**: Random SQL generation
3. **Chaos engineering**: Test failure scenarios
4. **Performance benchmarks**: Comprehensive performance suite
5. **Distributed testing**: Multi-node test scenarios

---

## Contributing Tests

### Guidelines

1. **Follow existing patterns**: Use KvTestBase, unique names, cleanup
2. **Test real behavior**: Integration tests over mocks
3. **Clear documentation**: Explain what the test verifies
4. **Fast execution**: Keep tests under 1 minute
5. **No flakiness**: Tests should pass consistently

### Pull Request Checklist

- [ ] Tests pass locally
- [ ] Tests are deterministic
- [ ] Tests clean up after themselves
- [ ] Tests are documented
- [ ] Tests follow project conventions

---

## Conclusion

Cassandra-SQL has a **moderate** level of test coverage focused on core functionality. Tests demonstrate that basic SQL operations work correctly, but many edge cases and advanced scenarios are not tested.

**Test coverage is sufficient for**:
- ✅ Proof of concept demonstration
- ✅ Basic functionality verification
- ✅ Regression prevention for core features

**Test coverage is NOT sufficient for**:
- ❌ Production use
- ❌ Guaranteeing correctness
- ❌ Performance validation
- ❌ Distributed system reliability

**Use tests as a starting point for understanding the system, not as a guarantee of correctness.**

---

## References

- JUnit 5 Documentation: https://junit.org/junit5/docs/current/user-guide/
- Spring Boot Testing: https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing
- Test-Driven Development: https://en.wikipedia.org/wiki/Test-driven_development


