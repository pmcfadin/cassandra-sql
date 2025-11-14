# Cassandra-SQL vs PostgreSQL: An Objective Comparison

**Last Updated**: November 2025  
**Status**: Proof of Concept vs Production Database

---

## Executive Summary

This document provides an objective, conservative comparison between Cassandra-SQL (this proof-of-concept project) and PostgreSQL (a mature, production-ready database). 

**TL;DR**: PostgreSQL is a mature, production-ready database with 30+ years of development. Cassandra-SQL is an experimental proof-of-concept that demonstrates distributed SQL on Cassandra. **Use PostgreSQL for any real application.**

---

## Architecture Comparison

### PostgreSQL

**Architecture**: Traditional RDBMS with optional replication
- Single-node primary handles all writes
- Optional read replicas for scaling reads
- Shared-nothing architecture for replicas
- Write-ahead log (WAL) for durability

**Strengths**:
- ✅ Proven architecture (30+ years)
- ✅ Strong consistency guarantees
- ✅ Excellent single-node performance
- ✅ Mature replication (streaming, logical)
- ✅ Point-in-time recovery

**Limitations**:
- ❌ Single write node (vertical scaling limit)
- ❌ Manual failover or requires external tools
- ❌ Limited to single-datacenter for strong consistency

### Cassandra-SQL

**Architecture**: Distributed SQL layer on Cassandra
- Masterless, peer-to-peer architecture
- All nodes can handle reads and writes
- Distributed consensus via Cassandra Accord
- Key-value storage with MVCC

**Strengths**:
- ✅ Horizontal scalability
- ✅ No single point of failure
- ✅ Multi-datacenter replication (Cassandra)
- ✅ Distributed transactions (Accord)

**Limitations**:
- ❌ Unproven in production
- ❌ Higher latency due to distributed coordination
- ❌ More complex failure modes
- ❌ Limited SQL feature support

---

## SQL Feature Comparison

| Feature Category | PostgreSQL | Cassandra-SQL | Notes |
|-----------------|------------|---------------|-------|
| **DDL** | | | |
| CREATE/DROP TABLE | ✅ Full | ✅ Basic | Cassandra-SQL lacks many constraints |
| ALTER TABLE | ✅ Full | ⚠️ Limited | Only ADD COLUMN supported |
| CREATE INDEX | ✅ Full | ✅ Basic | No partial, expression, or GIN indexes |
| Foreign Keys | ✅ Full | ❌ Not enforced | Defined but not enforced |
| CHECK Constraints | ✅ Full | ⚠️ Limited | Basic support, not fully enforced |
| **DML** | | | |
| SELECT | ✅ Full | ✅ Core features | Missing window functions, CTEs with recursion |
| INSERT | ✅ Full | ✅ Full | |
| UPDATE | ✅ Full | ✅ Full | |
| DELETE | ✅ Full | ✅ Full | |
| UPSERT (ON CONFLICT) | ✅ Full | ❌ Not supported | |
| **Joins** | | | |
| INNER JOIN | ✅ Full | ✅ Full | |
| LEFT/RIGHT/FULL JOIN | ✅ Full | ✅ Full | |
| CROSS JOIN | ✅ Full | ✅ Full | |
| Self-joins | ✅ Full | ✅ Full | |
| **Subqueries** | | | |
| Scalar subqueries | ✅ Full | ✅ Full | |
| IN/EXISTS subqueries | ✅ Full | ✅ Full | |
| Correlated subqueries | ✅ Full | ✅ Full | |
| **Aggregations** | | | |
| COUNT, SUM, AVG, MIN, MAX | ✅ Full | ✅ Full | |
| GROUP BY | ✅ Full | ✅ Full | |
| HAVING | ✅ Full | ✅ Full | |
| Window Functions | ✅ Full | ❌ Not supported | |
| **Data Types** | | | |
| Numeric types | ✅ Full | ✅ Basic | INT, BIGINT, DOUBLE, DECIMAL |
| String types | ✅ Full | ✅ Basic | VARCHAR, TEXT |
| Date/Time types | ✅ Full | ⚠️ Limited | TIMESTAMP only |
| Boolean | ✅ Full | ✅ Full | |
| JSON/JSONB | ✅ Full | ❌ Not supported | |
| Arrays | ✅ Full | ✅ Basic | Limited operations |
| Custom types (ENUM) | ✅ Full | ✅ Basic | |
| **Transactions** | | | |
| ACID guarantees | ✅ Full | ⚠️ Intended | Not formally verified |
| Isolation levels | ✅ 4 levels | ⚠️ Serializable only | Only one isolation level |
| Savepoints | ✅ Full | ❌ Not supported | |
| Two-phase commit | ✅ Full | ✅ Internal only | Via Percolator protocol |
| **Views** | | | |
| Regular views | ✅ Full | ✅ Full | |
| Materialized views | ✅ Full | ⚠️ Limited | Basic support, manual refresh |
| **Advanced Features** | | | |
| Full-text search | ✅ Full | ❌ Not supported | |
| Stored procedures | ✅ Full | ❌ Not supported | |
| Triggers | ✅ Full | ❌ Not supported | |
| User-defined functions | ✅ Full | ❌ Not supported | |
| Partitioning | ✅ Full | ❌ Not supported | |
| Replication control | ✅ Full | ❌ Not supported | Uses Cassandra replication |

**Summary**: PostgreSQL supports ~99% of SQL standard. Cassandra-SQL supports ~40% (core features only).

---

## Performance Comparison

### Disclaimer

⚠️ **These are rough estimates based on typical workloads. Actual performance depends heavily on:**
- Hardware configuration
- Data size and distribution
- Query patterns
- Concurrency levels
- Network topology (for distributed systems)

### Single-Node Workload

| Operation | PostgreSQL | Cassandra-SQL | Winner |
|-----------|-----------|---------------|--------|
| Simple SELECT (1 row) | ~0.1 ms | ~1-5 ms | PostgreSQL (10-50x faster) |
| Simple INSERT | ~0.2 ms | ~2-10 ms | PostgreSQL (10-50x faster) |
| Simple UPDATE | ~0.3 ms | ~3-15 ms | PostgreSQL (10-50x faster) |
| Transaction (5 writes) | ~1 ms | ~10-50 ms | PostgreSQL (10-50x faster) |
| JOIN (2 tables, 1K rows) | ~5 ms | ~20-100 ms | PostgreSQL (4-20x faster) |
| Aggregation (1M rows) | ~100 ms | ~500-2000 ms | PostgreSQL (5-20x faster) |

**Why PostgreSQL is faster**:
- Optimized for single-node performance (30+ years of tuning)
- No distributed coordination overhead
- Better query optimizer
- Better storage engine (B-trees vs KV encoding)

### Distributed Workload (3-node cluster)

| Operation | PostgreSQL (1 primary + 2 replicas) | Cassandra-SQL (3 nodes) | Winner |
|-----------|-------------------------------------|------------------------|--------|
| Write throughput | Limited by primary | Scales linearly | Cassandra-SQL |
| Read throughput | Scales with replicas | Scales linearly | Tie |
| Write latency | ~0.2 ms (local) | ~5-20 ms (quorum) | PostgreSQL |
| Read latency | ~0.1 ms (local) | ~2-10 ms (quorum) | PostgreSQL |
| Failover time | ~30-60 seconds (manual/automated) | ~0 seconds (no failover needed) | Cassandra-SQL |
| Multi-DC latency | Not recommended | Supported | Cassandra-SQL |

**When Cassandra-SQL might be better**:
- Very high write throughput requirements (>100K writes/sec)
- Multi-datacenter deployments with local writes
- No single point of failure requirement
- Horizontal scaling more important than latency

**When PostgreSQL is better**:
- Almost all other cases
- Low latency requirements
- Complex queries
- Single datacenter
- Mature ecosystem needed

---

## Consistency and Availability

### PostgreSQL

**Consistency Model**: Strong consistency (linearizable)
- All reads see the latest committed write
- ACID transactions with multiple isolation levels
- No eventual consistency

**Availability**: 
- ❌ Single point of failure (primary node)
- ✅ Read replicas can serve stale reads
- ⚠️ Failover requires external tools (e.g., Patroni, repmgr)
- ⚠️ Manual intervention often required

**CAP Theorem**: CP system (Consistency + Partition tolerance)

### Cassandra-SQL

**Consistency Model**: Tunable consistency (via Cassandra)
- Can choose consistency level per operation
- With Accord: Strong consistency for transactions
- Without Accord: Eventual consistency

**Availability**:
- ✅ No single point of failure
- ✅ Automatic failover (no manual intervention)
- ✅ Continues operating with node failures
- ⚠️ May sacrifice consistency for availability (tunable)

**CAP Theorem**: AP system by default, can be configured as CP

### Trade-offs

**PostgreSQL**:
- ✅ Simpler consistency model (always strong)
- ✅ Easier to reason about
- ❌ Lower availability (single primary)
- ❌ Manual failover complexity

**Cassandra-SQL**:
- ✅ Higher availability (no single point of failure)
- ✅ Automatic failover
- ⚠️ More complex consistency model
- ⚠️ Distributed transactions add latency

---

## Operational Complexity

### PostgreSQL

**Setup**: Easy
- Single binary installation
- Minimal configuration needed
- Works out of the box

**Monitoring**: Mature
- Extensive tooling (pg_stat_*, pg_top, etc.)
- Well-understood metrics
- Large community knowledge base

**Backup/Recovery**: Mature
- pg_dump, pg_basebackup
- Point-in-time recovery
- Streaming replication

**Scaling**: 
- ✅ Vertical scaling (easy)
- ⚠️ Read scaling with replicas (moderate)
- ❌ Write scaling (difficult - requires sharding)

**Operational Complexity**: ⭐⭐ (Low to Moderate)

### Cassandra-SQL

**Setup**: Complex
- Requires Cassandra cluster setup
- Cassandra Accord configuration
- SQL layer configuration
- Multiple moving parts

**Monitoring**: Immature
- Limited tooling
- Cassandra monitoring + SQL layer monitoring
- Fewer people with operational experience

**Backup/Recovery**: Inherited from Cassandra
- Cassandra snapshots
- No point-in-time recovery
- Restore requires cluster coordination

**Scaling**:
- ✅ Horizontal scaling (easy - add nodes)
- ✅ Read scaling (automatic)
- ✅ Write scaling (automatic)
- ⚠️ Rebalancing data (moderate complexity)

**Operational Complexity**: ⭐⭐⭐⭐⭐ (Very High)

---

## Maturity and Ecosystem

### PostgreSQL

**Maturity**: ⭐⭐⭐⭐⭐ (30+ years)
- First release: 1996
- Battle-tested in production
- Extensive real-world usage
- Well-documented edge cases

**Ecosystem**:
- ✅ Thousands of extensions
- ✅ Mature ORMs (Django, Rails, SQLAlchemy, etc.)
- ✅ Extensive tooling (pgAdmin, DBeaver, etc.)
- ✅ Large community
- ✅ Commercial support available
- ✅ Books, courses, certifications

**Bug Fixes**:
- ✅ Rapid response to security issues
- ✅ Regular releases
- ✅ Long-term support versions

### Cassandra-SQL

**Maturity**: ⭐ (Proof of Concept)
- First release: 2024
- Not tested in production
- Limited real-world usage
- Many edge cases unknown

**Ecosystem**:
- ❌ No extensions
- ⚠️ Basic PostgreSQL client compatibility
- ❌ Limited tooling
- ❌ Small community (if any)
- ❌ No commercial support
- ❌ No books, courses, or certifications

**Bug Fixes**:
- ⚠️ Community-driven (if any community exists)
- ⚠️ No security guarantees
- ⚠️ No release schedule

---

## Use Case Recommendations

### Use PostgreSQL When:

✅ **Almost always** - PostgreSQL is the safe, proven choice

Specifically:
- Building any production application
- Need strong consistency guarantees
- Require complex SQL queries
- Want mature ecosystem and tooling
- Need commercial support options
- Single datacenter deployment
- Team has PostgreSQL experience
- Low latency is critical
- Need advanced features (full-text search, JSON, etc.)

### Use Cassandra-SQL When:

🔬 **Research and experimentation only**

Specifically:
- Researching distributed SQL databases
- Learning about distributed transactions
- Prototyping distributed applications
- Exploring Cassandra Accord capabilities
- Academic projects
- Technology demonstrations

**Never use Cassandra-SQL for**:
- ❌ Production applications
- ❌ Applications with real users
- ❌ Applications handling real data
- ❌ Applications requiring reliability
- ❌ Applications requiring support

---

## Migration Considerations

### Migrating from PostgreSQL to Cassandra-SQL

**Difficulty**: ⭐⭐⭐⭐⭐ (Extremely Difficult)

**Why you shouldn't**:
- Cassandra-SQL is not production-ready
- Many PostgreSQL features not supported
- Performance will likely be worse
- Operational complexity much higher
- No support or community
- High risk of data loss or corruption

**If you must (for research)**:
1. Identify unsupported SQL features in your application
2. Rewrite queries to use supported features
3. Test extensively (expect bugs)
4. Plan for significantly higher latency
5. Prepare for operational challenges
6. Have a rollback plan

### Migrating from Cassandra-SQL to PostgreSQL

**Difficulty**: ⭐⭐ (Moderate)

**Steps**:
1. Export data using standard SQL dumps
2. Import into PostgreSQL
3. Adjust any Cassandra-specific features
4. Test thoroughly
5. Enjoy better performance and stability

---

## Cost Comparison

### PostgreSQL

**Infrastructure**:
- Single node: $50-500/month (cloud)
- With replicas: $150-1500/month
- Self-hosted: Hardware + maintenance costs

**Operational**:
- Lower operational costs (simpler)
- Mature tooling reduces manual work
- Easier to find experienced DBAs

**Total Cost of Ownership**: ⭐⭐ (Low to Moderate)

### Cassandra-SQL

**Infrastructure**:
- Minimum 3 nodes: $300-3000/month (cloud)
- Cassandra overhead (more storage due to replication)
- SQL layer overhead (additional compute)

**Operational**:
- Higher operational costs (complex)
- More manual work required
- Harder to find experienced operators
- More time spent debugging

**Total Cost of Ownership**: ⭐⭐⭐⭐⭐ (Very High)

---

## Conclusion

### The Bottom Line

**PostgreSQL** is a mature, production-ready database with 30+ years of development, extensive features, excellent performance, and a large ecosystem. It should be your default choice for almost any application.

**Cassandra-SQL** is an experimental proof-of-concept that demonstrates the feasibility of building a distributed SQL layer on Cassandra. It is interesting for research and learning, but **should not be used in production**.

### Decision Matrix

| Your Situation | Recommendation |
|----------------|----------------|
| Building a production application | ✅ PostgreSQL |
| Need high availability | ⚠️ PostgreSQL with HA setup (Patroni, etc.) |
| Need extreme write scalability (>100K writes/sec) | ⚠️ Consider Cassandra (native CQL, not SQL) |
| Multi-datacenter with local writes | ⚠️ Consider Cassandra (native CQL, not SQL) |
| Learning about distributed databases | 🔬 Cassandra-SQL (for learning only) |
| Research project | 🔬 Cassandra-SQL (for research only) |
| Anything else | ✅ PostgreSQL |

### Final Recommendation

**Use PostgreSQL.** It's faster, more reliable, more feature-complete, better supported, and has a much lower total cost of ownership.

Only consider Cassandra-SQL if you're doing research or learning about distributed SQL databases. Even then, you might be better off studying other distributed SQL databases like CockroachDB, YugabyteDB, or TiDB, which are actually production-ready.

---

## References

- PostgreSQL Documentation: https://www.postgresql.org/docs/
- Apache Cassandra Documentation: https://cassandra.apache.org/doc/
- Cassandra Accord: https://github.com/apache/cassandra-accord
- CAP Theorem: https://en.wikipedia.org/wiki/CAP_theorem
- Percolator Paper: https://research.google/pubs/pub36726/

---

**Disclaimer**: This comparison is provided for informational purposes only. The authors make no warranties about the accuracy or completeness of this information. Always conduct your own evaluation before choosing a database for your application.


