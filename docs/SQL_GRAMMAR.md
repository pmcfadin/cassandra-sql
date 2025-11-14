# SQL Grammar & PostgreSQL Compatibility

## Overview

Cassandra-SQL provides extensive PostgreSQL-compatible SQL support in **KV mode**. This document details supported SQL syntax and gaps compared to PostgreSQL.

## Supported SQL Statements

### Data Query Language (DQL)

#### SELECT

✅ **Fully Supported**

```sql
SELECT [DISTINCT] column_list
FROM table_name [alias]
[WHERE condition]
[GROUP BY column_list]
[HAVING condition]
[ORDER BY column_list [ASC|DESC]]
[LIMIT count] [OFFSET offset]
```

**Features**:
- Column projection (`SELECT col1, col2`)
- Wildcard selection (`SELECT *`)
- Column aliases (`SELECT col AS alias`)
- Literal values (`SELECT 'constant' AS name`)
- Arithmetic expressions (`SELECT price * quantity AS total`)
- DISTINCT
- WHERE clause with complex predicates
- ORDER BY (single or multi-column)
- LIMIT and OFFSET
- Subqueries (see below)

**Examples**:
```sql
-- Basic SELECT
SELECT id, name, email FROM users WHERE age > 18;

-- With ORDER BY and LIMIT
SELECT * FROM products ORDER BY price DESC LIMIT 10;

-- With expressions
SELECT id, price * 1.1 AS price_with_tax FROM products;

-- With DISTINCT
SELECT DISTINCT category FROM products;
```

#### JOINs

✅ **Fully Supported**

```sql
SELECT columns
FROM table1 [alias1]
[INNER | LEFT | RIGHT | FULL | CROSS] JOIN table2 [alias2]
  ON join_condition
[WHERE condition]
[ORDER BY columns]
[LIMIT count]
```

**Supported JOIN Types**:
- `INNER JOIN` - Returns matching rows from both tables
- `LEFT JOIN` (LEFT OUTER JOIN) - All rows from left table + matching from right
- `RIGHT JOIN` (RIGHT OUTER JOIN) - All rows from right table + matching from left
- `FULL JOIN` (FULL OUTER JOIN) - All rows from both tables
- `CROSS JOIN` - Cartesian product

**Multi-way JOINs** (3+ tables):
```sql
SELECT *
FROM orders o
INNER JOIN customers c ON o.customer_id = c.id
INNER JOIN products p ON o.product_id = p.id
WHERE o.status = 'shipped';
```

**Examples**:
```sql
-- INNER JOIN
SELECT u.name, o.order_date
FROM users u
INNER JOIN orders o ON u.id = o.user_id;

-- LEFT JOIN
SELECT u.name, COUNT(o.id) AS order_count
FROM users u
LEFT JOIN orders o ON u.id = o.user_id
GROUP BY u.name;

-- FULL JOIN
SELECT *
FROM table_a a
FULL JOIN table_b b ON a.id = b.id;

-- CROSS JOIN
SELECT *
FROM colors
CROSS JOIN sizes;
```

#### Aggregations

✅ **Fully Supported**

```sql
SELECT aggregate_functions
FROM table_name
[WHERE condition]
[GROUP BY column_list]
[HAVING condition]
```

**Aggregate Functions**:
- `COUNT(*)`, `COUNT(column)`, `COUNT(DISTINCT column)`
- `SUM(column)`
- `AVG(column)`
- `MIN(column)`
- `MAX(column)`

**Examples**:
```sql
-- Simple aggregation
SELECT COUNT(*) FROM users;

-- GROUP BY
SELECT category, COUNT(*), AVG(price)
FROM products
GROUP BY category;

-- HAVING clause
SELECT category, COUNT(*) AS count
FROM products
GROUP BY category
HAVING COUNT(*) > 10;

-- Multiple aggregates
SELECT 
  COUNT(*) AS total_orders,
  SUM(amount) AS total_revenue,
  AVG(amount) AS avg_order_value
FROM orders
WHERE status = 'completed';
```

#### Subqueries

✅ **Fully Supported**

**Types**:
- Scalar subqueries (returns single value)
- IN subqueries
- EXISTS subqueries
- Correlated subqueries

**Examples**:
```sql
-- IN subquery
SELECT * FROM users
WHERE id IN (SELECT user_id FROM orders WHERE total > 1000);

-- EXISTS subquery
SELECT * FROM users u
WHERE EXISTS (
  SELECT 1 FROM orders o WHERE o.user_id = u.id
);

-- Correlated subquery
SELECT u.name, 
  (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count
FROM users u;

-- Subquery in FROM clause
SELECT category, avg_price
FROM (
  SELECT category, AVG(price) AS avg_price
  FROM products
  GROUP BY category
) AS subquery
WHERE avg_price > 100;
```

#### Window Functions

✅ **Fully Supported**

```sql
SELECT column,
  window_function() OVER (
    [PARTITION BY partition_columns]
    [ORDER BY order_columns]
    [ROWS|RANGE frame_clause]
  )
FROM table_name
```

**Supported Functions**:
- `ROW_NUMBER()` - Sequential number within partition
- `RANK()` - Rank with gaps
- `DENSE_RANK()` - Rank without gaps
- `LAG(column, offset)` - Previous row value
- `LEAD(column, offset)` - Next row value
- `FIRST_VALUE(column)` - First value in window
- `LAST_VALUE(column)` - Last value in window

**Examples**:
```sql
-- ROW_NUMBER
SELECT id, name, salary,
  ROW_NUMBER() OVER (ORDER BY salary DESC) AS rank
FROM employees;

-- PARTITION BY
SELECT department, name, salary,
  RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS dept_rank
FROM employees;

-- LAG/LEAD
SELECT date, revenue,
  LAG(revenue, 1) OVER (ORDER BY date) AS prev_revenue,
  LEAD(revenue, 1) OVER (ORDER BY date) AS next_revenue
FROM daily_sales;
```

#### UNION / UNION ALL

✅ **Fully Supported**

```sql
SELECT columns FROM table1
UNION [ALL]
SELECT columns FROM table2
[ORDER BY columns]
[LIMIT count]
```

**Examples**:
```sql
-- UNION (removes duplicates)
SELECT name FROM customers
UNION
SELECT name FROM suppliers;

-- UNION ALL (keeps duplicates)
SELECT product_id FROM orders_2023
UNION ALL
SELECT product_id FROM orders_2024;

-- With ORDER BY and LIMIT
SELECT name, 'customer' AS type FROM customers
UNION ALL
SELECT name, 'supplier' AS type FROM suppliers
ORDER BY name
LIMIT 100;
```

### Data Manipulation Language (DML)

#### INSERT

✅ **Fully Supported**

```sql
INSERT INTO table_name [(column_list)]
VALUES (value_list) [, (value_list), ...]
```

**Features**:
- Single row insert
- Multi-row insert
- Auto-increment columns (SERIAL, GENERATED AS IDENTITY)
- DEFAULT values
- NULL handling

**Examples**:
```sql
-- Basic INSERT
INSERT INTO users (name, email, age)
VALUES ('John Doe', 'john@example.com', 30);

-- Multi-row INSERT
INSERT INTO products (name, price)
VALUES 
  ('Product A', 19.99),
  ('Product B', 29.99),
  ('Product C', 39.99);

-- With auto-increment
INSERT INTO orders (user_id, total)
VALUES (1, 99.99);  -- id is auto-generated

-- With DEFAULT
INSERT INTO users (name, email, created_at)
VALUES ('Jane', 'jane@example.com', DEFAULT);
```

#### UPDATE

✅ **Fully Supported**

```sql
UPDATE table_name
SET column = value [, column = value, ...]
[WHERE condition]
```

**Features**:
- Single/multiple column updates
- Arithmetic expressions (`balance = balance + 100`)
- WHERE clause filtering
- Automatic index maintenance

**Examples**:
```sql
-- Basic UPDATE
UPDATE users SET age = 31 WHERE id = 1;

-- Multiple columns
UPDATE products
SET price = 29.99, stock = 100
WHERE id = 5;

-- Arithmetic expression
UPDATE accounts
SET balance = balance + 100
WHERE id = 123;

-- Complex WHERE
UPDATE orders
SET status = 'shipped'
WHERE status = 'pending' AND created_at < '2024-01-01';
```

#### DELETE

✅ **Fully Supported**

```sql
DELETE FROM table_name
[WHERE condition]
```

**Features**:
- WHERE clause filtering
- Automatic index cleanup
- Transactional (ACID)

**Examples**:
```sql
-- Delete specific rows
DELETE FROM users WHERE age < 18;

-- Delete with complex condition
DELETE FROM orders
WHERE status = 'cancelled' AND created_at < '2023-01-01';

-- Delete all rows (use TRUNCATE for better performance)
DELETE FROM temp_table;
```

### Data Definition Language (DDL)

#### CREATE TABLE

✅ **Fully Supported**

```sql
CREATE TABLE [IF NOT EXISTS] table_name (
  column_name data_type [constraints],
  ...
  [PRIMARY KEY (column_list)]
)
```

**Supported Data Types**:
- `INT`, `INTEGER` - 32-bit integer
- `BIGINT` - 64-bit integer
- `SMALLINT` - 16-bit integer
- `SERIAL` - Auto-incrementing integer
- `BIGSERIAL` - Auto-incrementing bigint
- `TEXT`, `VARCHAR(n)` - Variable-length string
- `DOUBLE PRECISION`, `DOUBLE`, `FLOAT` - Double precision float
- `BOOLEAN`, `BOOL` - Boolean
- `JSON`, `JSONB` - JSON data
- `UUID` - UUID
- `TIMESTAMP` - Timestamp (future)
- `DATE` - Date (future)

**Constraints**:
- `PRIMARY KEY` - Single or composite
- `NOT NULL` - Non-nullable column
- `GENERATED ALWAYS AS IDENTITY` - Auto-increment (PostgreSQL 10+)
- `GENERATED BY DEFAULT AS IDENTITY` - Auto-increment with override

**Examples**:
```sql
-- Basic table
CREATE TABLE users (
  id INT PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT,
  age INT
);

-- With auto-increment
CREATE TABLE orders (
  id SERIAL PRIMARY KEY,
  user_id INT NOT NULL,
  total DOUBLE PRECISION,
  status TEXT
);

-- Composite primary key
CREATE TABLE order_items (
  order_id INT,
  product_id INT,
  quantity INT,
  PRIMARY KEY (order_id, product_id)
);

-- With GENERATED AS IDENTITY
CREATE TABLE products (
  id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name TEXT NOT NULL,
  price DOUBLE PRECISION
);

-- IF NOT EXISTS
CREATE TABLE IF NOT EXISTS logs (
  id BIGSERIAL PRIMARY KEY,
  message TEXT,
  created_at TIMESTAMP
);
```

**Hidden rowid Column**:
If no PRIMARY KEY is specified, Cassandra-SQL automatically adds a hidden `rowid` column (similar to PostgreSQL's `ctid`).

#### CREATE INDEX

✅ **Fully Supported**

```sql
CREATE [UNIQUE] INDEX [IF NOT EXISTS] index_name
ON table_name (column_list)
```

**Features**:
- Single-column indexes
- Multi-column (composite) indexes
- UNIQUE indexes (future)
- Automatic index maintenance

**Examples**:
```sql
-- Single-column index
CREATE INDEX idx_users_email ON users (email);

-- Multi-column index
CREATE INDEX idx_orders_user_status ON orders (user_id, status);

-- IF NOT EXISTS
CREATE INDEX IF NOT EXISTS idx_products_category ON products (category);
```

#### DROP TABLE

✅ **Fully Supported**

```sql
DROP TABLE [IF EXISTS] table_name [, table_name, ...]
```

**Features**:
- Single or multiple tables
- IF EXISTS clause
- Lazy deletion (VacuumJob cleans up data)

**Examples**:
```sql
-- Drop single table
DROP TABLE users;

-- Drop multiple tables
DROP TABLE orders, order_items;

-- IF EXISTS
DROP TABLE IF EXISTS temp_table;
```

#### DROP INDEX

✅ **Fully Supported**

```sql
DROP INDEX [IF EXISTS] index_name
```

#### TRUNCATE

✅ **Fully Supported**

```sql
TRUNCATE TABLE table_name
```

Fast O(1) operation using table versioning. Data is marked as invisible and cleaned up by VacuumJob.

#### ALTER TABLE

⚠️ **Partially Supported**

```sql
ALTER TABLE table_name
  ADD PRIMARY KEY (column_list)
```

**Supported**:
- `ADD PRIMARY KEY` - Add primary key constraint

**Not Yet Supported**:
- `ADD COLUMN` - Add new column
- `DROP COLUMN` - Remove column
- `ALTER COLUMN` - Modify column type
- `ADD CONSTRAINT` - Add constraints (FOREIGN KEY, CHECK, etc.)

### Transaction Control Language (TCL)

✅ **Fully Supported**

```sql
BEGIN [TRANSACTION]
START TRANSACTION

COMMIT [TRANSACTION]

ROLLBACK [TRANSACTION]
ABORT
```

**Features**:
- ACID transactions
- Snapshot isolation
- Optimistic concurrency control
- Automatic conflict detection

**Examples**:
```sql
-- Transfer money between accounts
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;

-- Rollback on error
BEGIN;
INSERT INTO users (name, email) VALUES ('Test', 'test@example.com');
-- ... error occurs ...
ROLLBACK;
```

### Utility Commands

#### EXPLAIN / EXPLAIN ANALYZE

✅ **Fully Supported**

```sql
EXPLAIN [ANALYZE] query
```

Shows query execution plan and cost estimates. With `ANALYZE`, also executes the query and shows actual statistics.

**Examples**:
```sql
-- Show query plan
EXPLAIN SELECT * FROM users WHERE age > 18;

-- Show plan with execution statistics
EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'pending';
```

#### VACUUM / ANALYZE

✅ **Supported** (No-op)

```sql
VACUUM
ANALYZE
```

These commands are no-ops in KV mode. Background jobs handle cleanup and statistics collection automatically.

#### COPY

⚠️ **Partially Supported**

```sql
COPY table_name FROM STDIN
COPY table_name TO STDOUT
```

Basic support for bulk data loading/export.

### PostgreSQL Meta-Commands (psql)

✅ **Supported**

- `\dt` - List tables
- `\di` - List indexes
- `\d table_name` - Describe table
- `\l` - List databases
- `\c database_name` - Connect to database
- `\q` - Quit

## WHERE Clause Operators

✅ **Fully Supported**

- Comparison: `=`, `!=`, `<>`, `<`, `<=`, `>`, `>=`
- Logical: `AND`, `OR`, `NOT`
- Pattern matching: `LIKE`, `NOT LIKE`
- Range: `BETWEEN`, `NOT BETWEEN`
- Set membership: `IN`, `NOT IN`
- NULL testing: `IS NULL`, `IS NOT NULL`

**Examples**:
```sql
-- Comparison
SELECT * FROM users WHERE age >= 18;

-- Logical operators
SELECT * FROM products WHERE price > 10 AND price < 100;

-- LIKE
SELECT * FROM users WHERE email LIKE '%@gmail.com';

-- BETWEEN
SELECT * FROM orders WHERE total BETWEEN 100 AND 500;

-- IN
SELECT * FROM users WHERE status IN ('active', 'pending');

-- IS NULL
SELECT * FROM users WHERE deleted_at IS NULL;

-- Complex conditions
SELECT * FROM orders
WHERE (status = 'pending' OR status = 'processing')
  AND total > 100
  AND created_at >= '2024-01-01';
```

## PostgreSQL Compatibility Gap Analysis

### ✅ Fully Compatible

| Feature | Status | Notes |
|---------|--------|-------|
| Basic SELECT | ✅ | Full support |
| JOINs (all types) | ✅ | INNER, LEFT, RIGHT, FULL, CROSS |
| Aggregations | ✅ | COUNT, SUM, AVG, MIN, MAX |
| GROUP BY / HAVING | ✅ | Full support |
| Subqueries | ✅ | Scalar, IN, EXISTS, correlated |
| Window functions | ✅ | ROW_NUMBER, RANK, LAG, LEAD, etc. |
| UNION / UNION ALL | ✅ | Full support |
| INSERT / UPDATE / DELETE | ✅ | Full support |
| Transactions | ✅ | ACID with snapshot isolation |
| CREATE/DROP TABLE | ✅ | Full support |
| CREATE/DROP INDEX | ✅ | Single and multi-column |
| ORDER BY / LIMIT / OFFSET | ✅ | Full support |
| DISTINCT | ✅ | Full support |
| Auto-increment | ✅ | SERIAL, GENERATED AS IDENTITY |

### ⚠️ Partially Compatible

| Feature | Status | Limitations |
|---------|--------|-------------|
| ALTER TABLE | ⚠️ | Only ADD PRIMARY KEY supported |
| Data types | ⚠️ | Missing TIMESTAMP, DATE, INTERVAL, ARRAY |
| COPY | ⚠️ | Basic support only |
| Constraints | ⚠️ | No FOREIGN KEY, CHECK, UNIQUE yet |
| Functions | ⚠️ | Limited built-in functions |
| CASE expressions | ⚠️ | Not yet implemented |
| CTEs (WITH) | ⚠️ | Not yet implemented |
| Recursive queries | ⚠️ | Not yet implemented |

### ❌ Not Yet Supported

| Feature | Status | Priority |
|---------|--------|----------|
| FOREIGN KEY constraints | ❌ | High |
| CHECK constraints | ❌ | Medium |
| UNIQUE constraints | ❌ | High |
| DEFAULT values (complex) | ❌ | Medium |
| Views | ❌ | High |
| Materialized views | ❌ | Medium |
| Triggers | ❌ | Low |
| Stored procedures | ❌ | Low |
| User-defined functions | ❌ | Low |
| CTEs (WITH clause) | ❌ | High |
| CASE expressions | ❌ | High |
| CAST / type conversion | ❌ | Medium |
| String functions | ❌ | Medium |
| Date/time functions | ❌ | High |
| Math functions | ❌ | Medium |
| ARRAY data type | ❌ | Medium |
| TIMESTAMP / DATE types | ❌ | High |
| INTERVAL type | ❌ | Medium |
| Sequences (CREATE SEQUENCE) | ❌ | Medium |
| UPSERT (INSERT ... ON CONFLICT) | ❌ | High |
| RETURNING clause | ❌ | Medium |
| Lateral joins | ❌ | Low |
| Recursive CTEs | ❌ | Low |
| Table partitioning | ❌ | Low |
| Inheritance | ❌ | Low |

## Performance Considerations

### Optimized Operations

- **Index scans**: Automatically used when beneficial
- **Predicate pushdown**: WHERE clauses pushed to storage layer
- **Projection pushdown**: Only requested columns retrieved
- **JOIN optimization**: Cost-based join order selection
- **LIMIT pushdown**: Early termination for LIMIT queries

### Operations to Avoid

- **Full table scans on large tables**: Use indexes
- **SELECT * on wide tables**: Project only needed columns
- **Unbounded queries**: Always use LIMIT for large result sets
- **Complex subqueries in WHERE**: Consider JOINs instead
- **High-contention transactions**: Can cause conflicts

## Migration from PostgreSQL

### Compatible Patterns

Most PostgreSQL queries work as-is:

```sql
-- These work identically in Cassandra-SQL
SELECT * FROM users WHERE age > 18 ORDER BY name LIMIT 10;
INSERT INTO orders (user_id, total) VALUES (1, 99.99);
UPDATE products SET price = price * 1.1 WHERE category = 'electronics';
DELETE FROM logs WHERE created_at < '2023-01-01';
```

### Patterns Requiring Changes

```sql
-- PostgreSQL: RETURNING clause
INSERT INTO users (name) VALUES ('John') RETURNING id;
-- Cassandra-SQL: Query after insert
INSERT INTO users (name) VALUES ('John');
SELECT id FROM users WHERE name = 'John' ORDER BY id DESC LIMIT 1;

-- PostgreSQL: UPSERT
INSERT INTO users (id, name) VALUES (1, 'John')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
-- Cassandra-SQL: Separate UPDATE or INSERT
-- (Check existence first, then INSERT or UPDATE)

-- PostgreSQL: CTEs
WITH recent_orders AS (
  SELECT * FROM orders WHERE created_at > '2024-01-01'
)
SELECT * FROM recent_orders WHERE total > 100;
-- Cassandra-SQL: Use subquery
SELECT * FROM (
  SELECT * FROM orders WHERE created_at > '2024-01-01'
) AS recent_orders WHERE total > 100;
```

## Testing SQL Compatibility

Use the provided test scripts:

```bash
# Run SQL compatibility tests
./gradlew test --tests "*KvQueryExecutor*"

# Run JOIN tests
./gradlew test --tests "*JoinTest*"

# Run transaction tests
./gradlew test --tests "*TransactionTest*"
```

## Future Roadmap

See [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md) for planned features and priorities.

