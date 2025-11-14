#!/bin/bash
#
# PostgreSQL Benchmark Harness using pgbench
#
# pgbench is the standard PostgreSQL benchmarking tool that comes with PostgreSQL.
# It supports TPC-B-like workloads and custom SQL scripts.
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-cassandra_sql}"
SCALE="${SCALE:-1}"           # Scale factor (10 = ~1MB, 100 = ~10MB, 1000 = ~100MB)
CLIENTS="${CLIENTS:-10}"        # Number of concurrent clients
THREADS="${THREADS:-4}"         # Number of threads
DURATION="${DURATION:-600}"      # Duration in seconds
RESULTS_DIR="benchmark-results/$(date +%Y%m%d-%H%M%S)"

echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║           PostgreSQL Benchmark Harness (pgbench)               ║${NC}"
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo ""

# Check if pgbench is installed
if ! command -v pgbench &> /dev/null; then
    echo -e "${RED}❌ Error: pgbench is not installed${NC}"
    echo ""
    echo "Install PostgreSQL client tools:"
    echo "  macOS:   brew install postgresql"
    echo "  Ubuntu:  sudo apt-get install postgresql-client"
    echo "  RHEL:    sudo yum install postgresql"
    exit 1
fi

# Create results directory
mkdir -p "$RESULTS_DIR"

echo -e "${GREEN}Configuration:${NC}"
echo "  Host:       $PGHOST"
echo "  Port:       $PGPORT"
echo "  Database:   $PGDATABASE"
echo "  Scale:      $SCALE (approx $(($SCALE / 10))MB)"
echo "  Clients:    $CLIENTS"
echo "  Threads:    $THREADS"
echo "  Duration:   ${DURATION}s"
echo "  Results:    $RESULTS_DIR"
echo ""

# Test connection
echo -e "${YELLOW}Testing connection...${NC}"
if ! psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c "SELECT 1" > /dev/null 2>&1; then
    echo -e "${RED}❌ Cannot connect to database${NC}"
    echo "Make sure the Cassandra-SQL server is running:"
    echo "  ./gradlew bootRun"
    exit 1
fi
echo -e "${GREEN}✅ Connection successful${NC}"
echo ""

# Function to run a benchmark
run_benchmark() {
    local name=$1
    local description=$2
    local extra_args=$3
    
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}Benchmark: $name${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo "$description"
    echo ""
    
    local output_file="$RESULTS_DIR/${name}.txt"
    local json_file="$RESULTS_DIR/${name}.json"
    
    # Run pgbench
    pgbench -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -c "$CLIENTS" -j "$THREADS" -T "$DURATION" \
        $extra_args \
        2>&1 | tee "$output_file"
    
    # Extract key metrics
    local tps=$(grep "tps = " "$output_file" | awk '{print $3}')
    local latency=$(grep "latency average" "$output_file" | awk '{print $4}')
    
    echo ""
    echo -e "${GREEN}Results saved to: $output_file${NC}"
    echo -e "${GREEN}  TPS:     $tps${NC}"
    echo -e "${GREEN}  Latency: ${latency}ms${NC}"
    echo ""
}

# Initialize database (create schema and load data)
echo -e "${YELLOW}Initializing benchmark database...${NC}"
echo "This will create pgbench tables and load $SCALE scale factor of data"
echo ""

pgbench -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
    -i -s "$SCALE" 2>&1 | tee "$RESULTS_DIR/init.txt"

echo ""
echo -e "${GREEN}✅ Database initialized${NC}"
echo ""

# Wait a moment for data to settle
sleep 2

# Run benchmarks
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                    Running Benchmarks                          ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# 1. TPC-B (default) - Mix of SELECT, UPDATE, INSERT
run_benchmark "tpc-b-default" \
    "TPC-B workload (default): Mix of SELECT, UPDATE, INSERT" \
    ""

# 2. SELECT-only workload
run_benchmark "select-only" \
    "SELECT-only workload: Read-heavy queries" \
    "-S"

# 3. Simple-update workload
run_benchmark "simple-update" \
    "Simple UPDATE workload: Write-heavy updates" \
    "-N"

# 4. Custom: Read-heavy with JOINs
cat > "$RESULTS_DIR/read-join.sql" << 'EOF'
SELECT a.aid, b.bid, t.tid, a.abalance, b.bbalance
FROM pgbench_accounts a
JOIN pgbench_branches b ON a.bid = b.bid
JOIN pgbench_tellers t ON a.bid = t.bid
WHERE a.aid = :aid;
EOF

run_benchmark "read-join" \
    "Custom: Read with JOINs" \
    "-f $RESULTS_DIR/read-join.sql"

# 5. Custom: Aggregation workload
cat > "$RESULTS_DIR/aggregation.sql" << 'EOF'
SELECT bid, COUNT(*), SUM(abalance), AVG(abalance), MIN(abalance), MAX(abalance)
FROM pgbench_accounts
WHERE bid = :bid
GROUP BY bid;
EOF

run_benchmark "aggregation" \
    "Custom: Aggregation workload (GROUP BY)" \
    "-f $RESULTS_DIR/aggregation.sql"

# 6. Custom: Transaction workload
cat > "$RESULTS_DIR/transaction.sql" << 'EOF'
BEGIN;
UPDATE pgbench_accounts SET abalance = abalance + :delta WHERE aid = :aid;
SELECT abalance FROM pgbench_accounts WHERE aid = :aid;
INSERT INTO pgbench_history (tid, bid, aid, delta, mtime) VALUES (:tid, :bid, :aid, :delta, CURRENT_TIMESTAMP);
COMMIT;
EOF

run_benchmark "transaction" \
    "Custom: Multi-statement transaction" \
    "-f $RESULTS_DIR/transaction.sql"

# Generate summary report
echo -e "${BLUE}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                      Summary Report                            ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

cat > "$RESULTS_DIR/SUMMARY.md" << EOF
# Benchmark Results Summary

**Date**: $(date)
**Configuration**:
- Host: $PGHOST:$PGPORT
- Database: $PGDATABASE
- Scale Factor: $SCALE
- Clients: $CLIENTS
- Threads: $THREADS
- Duration: ${DURATION}s

## Results

| Benchmark | TPS | Latency (ms) | Description |
|-----------|-----|--------------|-------------|
EOF

for result_file in "$RESULTS_DIR"/*.txt; do
    if [[ "$result_file" != *"init.txt"* ]]; then
        name=$(basename "$result_file" .txt)
        tps=$(grep "tps = " "$result_file" | awk '{print $3}' || echo "N/A")
        latency=$(grep "latency average" "$result_file" | awk '{print $4}' || echo "N/A")
        
        case $name in
            "tpc-b-default")
                desc="TPC-B (default mix)"
                ;;
            "select-only")
                desc="SELECT-only (read-heavy)"
                ;;
            "simple-update")
                desc="Simple UPDATE (write-heavy)"
                ;;
            "read-join")
                desc="Read with JOINs"
                ;;
            "aggregation")
                desc="Aggregation (GROUP BY)"
                ;;
            "transaction")
                desc="Multi-statement transactions"
                ;;
            *)
                desc="Custom workload"
                ;;
        esac
        
        echo "| $name | $tps | $latency | $desc |" >> "$RESULTS_DIR/SUMMARY.md"
        
        echo -e "${GREEN}$name:${NC}"
        echo "  TPS:     $tps"
        echo "  Latency: ${latency}ms"
        echo ""
    fi
done

cat >> "$RESULTS_DIR/SUMMARY.md" << EOF

## Detailed Results

See individual result files in this directory for detailed metrics including:
- Transaction throughput (TPS)
- Latency (average, min, max, stddev)
- Percentiles (50th, 90th, 95th, 99th)

## Files

- \`init.txt\` - Database initialization log
- \`*-*.txt\` - Detailed benchmark results
- \`*.sql\` - Custom SQL scripts used
- \`SUMMARY.md\` - This summary report

## Interpreting Results

**TPS (Transactions Per Second)**: Higher is better
- Good: > 1000 TPS
- Fair: 100-1000 TPS
- Poor: < 100 TPS

**Latency (milliseconds)**: Lower is better
- Excellent: < 10ms
- Good: 10-50ms
- Fair: 50-100ms
- Poor: > 100ms

## Next Steps

1. Compare results against native PostgreSQL
2. Identify bottlenecks (CPU, network, Cassandra)
3. Tune configuration based on workload
4. Re-run benchmarks after optimizations
EOF

echo -e "${GREEN}✅ Benchmark complete!${NC}"
echo ""
echo -e "${BLUE}Results saved to: $RESULTS_DIR${NC}"
echo ""
echo "View summary:"
echo "  cat $RESULTS_DIR/SUMMARY.md"
echo ""
echo "View detailed results:"
echo "  ls -la $RESULTS_DIR/"
echo ""

# Display summary
cat "$RESULTS_DIR/SUMMARY.md"



