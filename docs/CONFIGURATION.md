# Configuration Guide

## Overview

Cassandra-SQL is configured via `application.yml` (Spring Boot configuration). This document covers all configuration options.

## Storage Modes

### Choosing a Storage Mode

```yaml
cassandra-sql:
  storage-mode: kv  # or 'schema'
```

| Mode | Description | Use Case | Transactions | SQL Support |
|------|-------------|----------|--------------|-------------|
| **kv** | Key-value mode with custom encoding | **Recommended** for production | ✅ Full ACID | ✅ Full SQL |
| **schema** | Native Cassandra schema mode | Legacy/compatibility | ❌ None | ⚠️ Limited |

### KV Mode (Recommended)

**Advantages**:
- Full ACID transactions
- Complete SQL support (JOINs, subqueries, window functions)
- Cost-based query optimization
- Secondary indexes
- PostgreSQL compatibility

**Configuration**:
```yaml
cassandra-sql:
  storage-mode: kv
  
  kv-storage:
    mvcc:
      enabled: true
      gc-retention-seconds: 86400  # 24 hours
      gc-interval-seconds: 3600    # 1 hour
      max-versions-per-key: 10
    
    transaction:
      timeout-seconds: 30
      lock-ttl-seconds: 60
    
    encoding:
      compression: false  # Future feature
      encryption: false   # Future feature
    
    performance:
      batch-size: 1000
      scan-page-size: 100
```

### Schema Mode (Legacy)

**Advantages**:
- Lower latency (native Cassandra performance)
- Simpler deployment
- No transaction overhead

**Limitations**:
- No transactions
- Limited SQL (basic SELECT, INSERT, UPDATE, DELETE)
- No JOINs or subqueries
- Eventual consistency only

**Configuration**:
```yaml
cassandra-sql:
  storage-mode: schema
```

## Cassandra Connection

```yaml
cassandra:
  contact-points: localhost  # Comma-separated list
  port: 9042
  local-datacenter: datacenter1
  keyspace: cassandra_sql
  
  # Optional: Authentication
  username: cassandra
  password: cassandra
  
  # Optional: Connection pool
  connection-pool-size: 10
  request-timeout-ms: 12000
```

**Multiple Contact Points**:
```yaml
cassandra:
  contact-points: node1.example.com,node2.example.com,node3.example.com
```

## PostgreSQL Protocol

```yaml
cassandra-sql:
  protocol:
    port: 5432  # PostgreSQL wire protocol port
    
    # Connection limits
    max-connections: 1000
    connection-timeout-seconds: 30
    
    # Query execution
    query-timeout-seconds: 30
    
    # Authentication (optional)
    require-auth: false
    allowed-users:
      - username: admin
        password: secret
      - username: readonly
        password: readonly123
```

## Query Optimizer

```yaml
cassandra-sql:
  optimizer:
    # Enable Calcite cost-based optimizer
    enable-calcite-cbo: true  # Default: true
    
    # Plan caching
    cache-plans: true
    cache-size: 1000
    
    # Statistics collection
    collect-statistics: true
```

**When to disable CBO**:
- Debugging query plans
- Very simple queries (single-table SELECT)
- Benchmarking raw performance

## Schema Management

```yaml
cassandra-sql:
  schema:
    # Background refresh interval (seconds)
    # How often to reload schema from KV storage
    refresh-interval-seconds: 10
    
    # Force reload from KV on every getTable() call
    # WARNING: Significant performance impact - only for debugging
    always-reload: false
```

**Recommendations**:
- **Production**: `refresh-interval-seconds: 10` (default)
- **Development**: `refresh-interval-seconds: 5` (faster schema updates)
- **Debugging**: `always-reload: true` (ensures latest schema, but slow)

## Background Jobs

```yaml
cassandra-sql:
  jobs:
    # Vacuum job (cleanup dropped tables and old MVCC versions)
    vacuum:
      enabled: true
      interval-seconds: 3600  # 1 hour
      retention-seconds: 86400  # 24 hours
    
    # Statistics collector (for query optimizer)
    statistics:
      enabled: true
      interval-seconds: 300  # 5 minutes
    
    # Index statistics (for index selection)
    index-statistics:
      enabled: true
      interval-seconds: 600  # 10 minutes
```

## Logging

```yaml
logging:
  level:
    org.cassandrasql: INFO  # DEBUG for verbose logging
    org.apache.calcite: INFO
    com.datastax: WARN
    org.springframework: INFO
    io.netty: WARN
```

**Log Levels**:
- `ERROR`: Errors only
- `WARN`: Warnings and errors
- `INFO`: General information (recommended for production)
- `DEBUG`: Detailed debugging information
- `TRACE`: Very verbose (not recommended)

**Useful Debug Settings**:
```yaml
logging:
  level:
    org.cassandrasql.mvp.calcite: DEBUG  # Query planning
    org.cassandrasql.mvp.kv.KvQueryExecutor: DEBUG  # Query execution
    org.cassandrasql.mvp.kv.SchemaManager: DEBUG  # Schema operations
    org.cassandrasql.mvp.kv.KvTransactionCoordinator: DEBUG  # Transactions
```

## Performance Tuning

### For High Throughput

```yaml
cassandra-sql:
  protocol:
    max-connections: 5000
    query-timeout-seconds: 60
  
  kv-storage:
    performance:
      batch-size: 5000
      scan-page-size: 500
    
    transaction:
      timeout-seconds: 60
  
  optimizer:
    cache-plans: true
    cache-size: 10000
```

### For Low Latency

```yaml
cassandra-sql:
  kv-storage:
    performance:
      batch-size: 100
      scan-page-size: 50
    
    transaction:
      timeout-seconds: 10
  
  optimizer:
    cache-plans: true
    cache-size: 1000
```

### For High Concurrency

```yaml
cassandra-sql:
  protocol:
    max-connections: 10000
  
  kv-storage:
    transaction:
      timeout-seconds: 5
      lock-ttl-seconds: 10
    
    mvcc:
      max-versions-per-key: 5
```

## Multi-Datacenter Setup

```yaml
cassandra:
  contact-points: dc1-node1,dc1-node2,dc2-node1,dc2-node2
  local-datacenter: dc1
  
  # Consistency levels
  read-consistency: LOCAL_QUORUM
  write-consistency: LOCAL_QUORUM
```

## Environment Variables

All configuration can be overridden via environment variables:

```bash
# Storage mode
export CASSANDRA_SQL_STORAGE_MODE=kv

# Cassandra connection
export CASSANDRA_CONTACT_POINTS=node1,node2,node3
export CASSANDRA_PORT=9042
export CASSANDRA_KEYSPACE=my_database

# Protocol
export CASSANDRA_SQL_PROTOCOL_PORT=5432
export CASSANDRA_SQL_PROTOCOL_MAX_CONNECTIONS=1000

# Logging
export LOGGING_LEVEL_ORG_CASSANDRASQL=DEBUG
```

## Docker Deployment

**docker-compose.yml**:
```yaml
version: '3.8'

services:
  cassandra:
    image: cassandra:4.1
    ports:
      - "9042:9042"
    environment:
      - CASSANDRA_CLUSTER_NAME=cassandra-sql-cluster
      - CASSANDRA_DC=dc1
      - CASSANDRA_RACK=rack1
    volumes:
      - cassandra-data:/var/lib/cassandra

  cassandra-sql:
    image: cassandra-sql:latest
    ports:
      - "5432:5432"
      - "8080:8080"
    environment:
      - CASSANDRA_CONTACT_POINTS=cassandra
      - CASSANDRA_SQL_STORAGE_MODE=kv
      - LOGGING_LEVEL_ORG_CASSANDRASQL=INFO
    depends_on:
      - cassandra

volumes:
  cassandra-data:
```

## Kubernetes Deployment

**ConfigMap**:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: cassandra-sql-config
data:
  application.yml: |
    cassandra:
      contact-points: cassandra-service
      port: 9042
      local-datacenter: datacenter1
      keyspace: cassandra_sql
    
    cassandra-sql:
      storage-mode: kv
      protocol:
        port: 5432
        max-connections: 1000
```

**Deployment**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cassandra-sql
spec:
  replicas: 3
  selector:
    matchLabels:
      app: cassandra-sql
  template:
    metadata:
      labels:
        app: cassandra-sql
    spec:
      containers:
      - name: cassandra-sql
        image: cassandra-sql:latest
        ports:
        - containerPort: 5432
        - containerPort: 8080
        volumeMounts:
        - name: config
          mountPath: /app/config
      volumes:
      - name: config
        configMap:
          name: cassandra-sql-config
```

## Health Checks

Cassandra-SQL exposes health check endpoints:

- `http://localhost:8080/actuator/health` - Overall health
- `http://localhost:8080/actuator/health/cassandra` - Cassandra connection
- `http://localhost:8080/actuator/health/diskSpace` - Disk space

**Configuration**:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  health:
    cassandra:
      enabled: true
```

## Monitoring

### Metrics

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: cassandra-sql
      environment: production
```

**Available Metrics**:
- `cassandra_sql_queries_total` - Total queries executed
- `cassandra_sql_query_duration_seconds` - Query execution time
- `cassandra_sql_transactions_total` - Total transactions
- `cassandra_sql_transaction_conflicts_total` - Transaction conflicts
- `cassandra_sql_connections_active` - Active connections

### Prometheus Configuration

```yaml
scrape_configs:
  - job_name: 'cassandra-sql'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
```

## Troubleshooting

### Connection Issues

```yaml
logging:
  level:
    com.datastax.oss.driver: DEBUG
    io.netty: DEBUG
```

### Query Performance

```yaml
logging:
  level:
    org.cassandrasql.mvp.calcite.KvPlanner: DEBUG
    org.cassandrasql.mvp.kv.KvQueryExecutor: DEBUG
```

Enable `EXPLAIN ANALYZE` for query plans:
```sql
EXPLAIN ANALYZE SELECT * FROM my_table WHERE id = 1;
```

### Transaction Conflicts

```yaml
logging:
  level:
    org.cassandrasql.mvp.kv.KvTransactionCoordinator: DEBUG
```

Increase timeout:
```yaml
cassandra-sql:
  kv-storage:
    transaction:
      timeout-seconds: 60
```

## Complete Example

**Production Configuration**:
```yaml
server:
  port: 8080

spring:
  application:
    name: cassandra-sql-prod

cassandra:
  contact-points: node1.prod.example.com,node2.prod.example.com,node3.prod.example.com
  port: 9042
  local-datacenter: datacenter1
  keyspace: production_db
  username: ${CASSANDRA_USERNAME}
  password: ${CASSANDRA_PASSWORD}

cassandra-sql:
  storage-mode: kv
  
  protocol:
    port: 5432
    max-connections: 5000
    connection-timeout-seconds: 30
    query-timeout-seconds: 60
  
  kv-storage:
    mvcc:
      enabled: true
      gc-retention-seconds: 86400
      gc-interval-seconds: 3600
      max-versions-per-key: 10
    
    transaction:
      timeout-seconds: 30
      lock-ttl-seconds: 60
    
    performance:
      batch-size: 1000
      scan-page-size: 100
  
  optimizer:
    enable-calcite-cbo: true
    cache-plans: true
    cache-size: 10000
    collect-statistics: true
  
  schema:
    refresh-interval-seconds: 10
    always-reload: false
  
  jobs:
    vacuum:
      enabled: true
      interval-seconds: 3600
    statistics:
      enabled: true
      interval-seconds: 300
    index-statistics:
      enabled: true
      interval-seconds: 600

logging:
  level:
    org.cassandrasql: INFO
    org.apache.calcite: WARN
    com.datastax: WARN

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

