# Quick Start with Docker

This directory contains everything you need to run **Cassandra-SQL** using Docker Compose.

## Prerequisites

- **Docker** (20.10+) and **Docker Compose** (2.0+)
- **8GB RAM** minimum (Cassandra requires memory)
- **PostgreSQL client** (`psql`) for connecting (optional)

## Quick Start

### 1. Start Services

From the `docker/` directory:

```bash
docker-compose up -d
```

This starts:
- **Cassandra 5.0+** with Accord enabled (internal, port 9042)
- **cassandra-sql application** (PostgreSQL wire protocol on port 5432)

**First run takes 3-5 minutes**:
- Cassandra initialization: ~90 seconds
- Application build (Gradle): ~2-3 minutes

### 2. Check Status

```bash
docker-compose ps
```

Wait until both services show `healthy` status:
```
NAME                IMAGE                          STATUS
cassandra-sql-app   docker-cassandra-sql:latest    Up (healthy)
cassandra-sql-db    pmcfadin/cassandra-accord      Up (healthy)
```

### 3. Connect with psql

```bash
psql -h localhost -p 5432 -d cassandra_sql
```

**Try a query**:
```sql
CREATE TABLE users (
    id INT PRIMARY KEY,
    name VARCHAR(100),
    email VARCHAR(255)
);

INSERT INTO users VALUES (1, 'Alice', 'alice@example.com');
INSERT INTO users VALUES (2, 'Bob', 'bob@example.com');

SELECT * FROM users WHERE id = 1;
```

### 4. Run the Demo

From the project root (parent directory):

```bash
../demo-ecommerce.sh
```

This demonstrates:
- Complex DDL (tables, indexes, enums, views)
- Transactions with multiple statements
- JOINs, aggregations, and subqueries
- Materialized views

## Services

### Cassandra (cassandra-sql-db)

- **Image**: `pmcfadin/cassandra-accord:latest`
- **Ports**: 9042 (internal only, not exposed to host)
- **Configuration**:
  - ByteOrderedPartitioner (required for ordered range scans)
  - Accord enabled (ACID transactions)
- **Data Persistence**:
  - `cassandra-sql-data` volume (database files)
  - `cassandra-accord-journal` volume (transaction logs)

### Application (cassandra-sql-app)

- **Build**: Multi-stage (Gradle + Java 17)
- **Ports**:
  - `5432` - PostgreSQL wire protocol
  - `18080` - REST API / Health checks (mapped to internal port 8080)
- **Health Check**: `http://localhost:18080/actuator/health`

## Common Commands

```bash
# Start services in background
docker-compose up -d

# View logs
docker-compose logs -f

# View logs for specific service
docker-compose logs -f cassandra-sql

# Stop services (keeps data)
docker-compose stop

# Stop and remove containers (keeps data volumes)
docker-compose down

# Stop and remove everything including data
docker-compose down -v

# Rebuild application after code changes
docker-compose build cassandra-sql
docker-compose up -d cassandra-sql

# Check health status
docker-compose ps
curl http://localhost:18080/actuator/health
```

## Troubleshooting

### Services Won't Start

**Check Docker resources**:
```bash
docker stats
```

Cassandra needs at least **2GB RAM**. Increase Docker Desktop memory limit if needed.

**View logs**:
```bash
docker-compose logs cassandra
docker-compose logs cassandra-sql
```

### Cassandra Takes Too Long to Start

This is normal. Cassandra initialization can take **60-120 seconds** on first run.

Watch the logs:
```bash
docker-compose logs -f cassandra
```

Look for: `"Startup complete"` or `"Listening for thrift clients..."`

### Application Can't Connect to Cassandra

**Verify Cassandra is healthy**:
```bash
docker-compose ps
```

**Check network connectivity**:
```bash
docker-compose exec cassandra-sql ping -c 3 cassandra
docker-compose exec cassandra /opt/cassandra/bin/nodetool status
```

### Port 5432 Already in Use

If you have PostgreSQL running locally:

**Option 1**: Stop local PostgreSQL
```bash
# macOS
brew services stop postgresql

# Linux
sudo systemctl stop postgresql
```

**Option 2**: Change cassandra-sql port in `docker-compose.yml`:
```yaml
ports:
  - "15432:5432"  # Change host port to 15432
```

Then connect with:
```bash
psql -h localhost -p 15432 -d cassandra_sql
```

### Reset Everything

To completely reset (deletes all data):

```bash
docker-compose down -v
docker-compose up -d
```

## Important Configuration Notes

### ByteOrderedPartitioner

The Cassandra instance uses **ByteOrderedPartitioner** instead of the default Murmur3Partitioner.

**Why?** Cassandra-SQL requires ordered range scans for SQL semantics:
- `WHERE id BETWEEN 100 AND 200` needs contiguous key ordering
- `ORDER BY` operations benefit from pre-sorted data
- The KV storage layer relies on lexicographic key ordering

**Warning**: This partitioner is **not recommended for production Cassandra clusters** due to potential hotspotting. It's used here because:
1. This is a proof-of-concept, not production
2. Cassandra 5.0+ Transactional Cluster Metadata makes range splitting safer
3. SQL semantics require ordered key-value operations

### Accord Transactions

The setup enables **Cassandra Accord** for ACID transactions:
- Multi-key transactions across tables
- Serializable isolation level
- Two-phase commit protocol (Percolator-style)

Transaction logs are persisted in: `/opt/cassandra/data/accord_journal`

## Architecture

```
┌─────────────────────────────┐
│   PostgreSQL Clients        │
│   (psql, JDBC, pgAdmin)     │
└─────────────┬───────────────┘
              │ Port 5432
              │ (PostgreSQL Wire Protocol)
┌─────────────▼───────────────┐
│   cassandra-sql-app         │
│   - PostgreSQL Protocol     │
│   - SQL Parser (Calcite)    │
│   - Query Executor          │
│   - Transaction Coordinator │
│   - KV Store Layer          │
└─────────────┬───────────────┘
              │ Port 9042 (internal)
              │ (CQL / Cassandra Protocol)
┌─────────────▼───────────────┐
│   cassandra-sql-db          │
│   - Cassandra 5.0+ Accord   │
│   - ByteOrderedPartitioner  │
│   - Accord Transactions     │
└─────────────────────────────┘
```

## Data Persistence

Docker volumes persist data between container restarts:

- **cassandra-sql-data**: Cassandra database files (tables, indexes)
- **cassandra-accord-journal**: Accord transaction journal

**Location** (varies by OS):
```bash
# Linux
/var/lib/docker/volumes/

# macOS / Windows (Docker Desktop)
~/Library/Containers/com.docker.docker/Data/
```

**To inspect volumes**:
```bash
docker volume ls
docker volume inspect cassandra-sql-data
```

## Development

### Rebuilding After Code Changes

```bash
# Rebuild and restart app (Cassandra keeps running)
docker-compose build cassandra-sql
docker-compose up -d cassandra-sql
```

### Running Tests

Tests require a running Cassandra instance. From the project root:

```bash
# Start Cassandra
cd docker && docker-compose up -d cassandra

# Run tests
cd .. && ./gradlew test

# Or run specific test
./gradlew test --tests "com.geico.poc.cassandrasql.LazyDropTest"
```

### Accessing Cassandra Directly (CQL)

Uncomment the port mapping in `docker-compose.yml`:

```yaml
ports:
  - "9042:9042"  # CQL port
```

Then:
```bash
docker-compose up -d

# Connect with cqlsh
docker-compose exec cassandra /opt/cassandra/bin/cqlsh
```

## Support

For issues, questions, or contributions:
- **GitHub Issues**: https://github.com/geico/cassandra-sql/issues
- **Main Documentation**: See `../README.md`
- **Architecture**: See `../docs/ARCHITECTURE.md`

## Disclaimer

**This is a proof-of-concept. DO NOT use in production.**

See main README.md for limitations and warnings.
