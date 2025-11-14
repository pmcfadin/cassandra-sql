package com.geico.poc.cassandrasql.kv.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.kv.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background job that collects statistics for indexes.
 * 
 * Statistics collected:
 * - Row count: Total number of rows in the table
 * - Index size: Number of entries in each index
 * - Cardinality: Number of distinct values in indexed columns
 * - Selectivity: Cardinality / Row count (0.0 to 1.0)
 * 
 * These statistics are used by the cost-based optimizer to:
 * - Choose between index scan vs full table scan
 * - Select the best index when multiple are available
 * - Estimate query execution cost
 */
@Component
public class IndexStatisticsJob implements BackgroundJob {
    
    private static final Logger log = LoggerFactory.getLogger(IndexStatisticsJob.class);
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private CassandraSqlConfig config;
    
    // Statistics cache: tableName -> statistics map
    private final Map<String, Map<String, Object>> statisticsCache = new ConcurrentHashMap<>();
    private RateLimiter rateLimiter;
    
    @Override
    public String getName() {
        return "IndexStatisticsJob";
    }
    
    @Override
    public long getInitialDelayMs() {
        // Start after 30 seconds to allow system to initialize
        return 30_000;
    }
    
    @Override
    public long getPeriodMs() {
        // Run every 5 minutes
        return 5 * 60 * 1000;
    }
    
    @Override
    public boolean isEnabled() {
        // Always enabled in KV mode
        return true;
    }
    
    @Override
    public void execute() {
        log.info("🔍 Running IndexStatisticsJob...");
        
        try {
            // Initialize rate limiter if needed
            if (rateLimiter == null && config.getBackgroundJobs().isRateLimitEnabled()) {
                rateLimiter = new RateLimiter(config.getBackgroundJobs().getKeysPerHour());
                log.info("  Rate limiter initialized: " + rateLimiter.getKeysPerHour() + " keys/hour");
            }
            
            // Collect statistics for all tables
            List<String> tableNames = schemaManager.getAllTableNames();
            
            int tablesProcessed = 0;
            for (String tableName : tableNames) {
                try {
                    collectStatistics(tableName);
                    tablesProcessed++;
                } catch (Exception e) {
                    log.error("  ⚠️  Failed to collect statistics for table " + tableName + ": " + e.getMessage());
                }
            }
            
            log.info("  ✅ Collected statistics for " + tablesProcessed + " tables");
            
        } catch (Exception e) {
            log.error("  ❌ IndexStatisticsJob failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Collect statistics for a specific table.
     * 
     * @param tableName The name of the table
     */
    public void collectStatistics(String tableName) {
        TableMetadata table = schemaManager.getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        long startTime = System.currentTimeMillis();
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Get current timestamp for MVCC read
            long readTs = timestampOracle.getCurrentTimestamp();
            
            // Scan all data rows to count total rows
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> dataRows = kvStore.scan(startKey, endKey, readTs, 1000000);
            
            // Apply rate limiting to scanned rows
            if (rateLimiter != null) {
                try {
                    rateLimiter.acquire(dataRows.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("  Index statistics collection interrupted during rate limiting");
                    return;
                }
            }
            
            long rowCount = dataRows.size();
            
            stats.put("row_count", rowCount);
            stats.put("table_name", tableName);
            stats.put("collection_timestamp", System.currentTimeMillis());
            
            // Collect statistics for each index
            for (TableMetadata.IndexMetadata index : table.getIndexes()) {
                Map<String, Object> indexStats = collectIndexStatistics(table, index, readTs, rowCount);
                stats.put(index.getName(), indexStats);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            stats.put("collection_duration_ms", duration);
            
            // Cache the statistics
            statisticsCache.put(tableName.toLowerCase(), stats);
            
            log.info("  📊 Collected statistics for " + tableName + 
                " (rows=" + rowCount + ", indexes=" + table.getIndexes().size() + ", duration=" + duration + "ms)");
            
        } catch (Exception e) {
            log.error("  ⚠️  Failed to collect statistics for " + tableName + ": " + e.getMessage());
            throw new RuntimeException("Failed to collect statistics for " + tableName, e);
        }
    }
    
    /**
     * Collect statistics for a specific index.
     */
    private Map<String, Object> collectIndexStatistics(TableMetadata table, 
                                                       TableMetadata.IndexMetadata index,
                                                       long readTs,
                                                       long rowCount) {
        Map<String, Object> indexStats = new HashMap<>();
        
        try {
            // Scan all index entries
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), index.getIndexId(), null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), index.getIndexId(), null);
            
            List<KvStore.KvEntry> indexEntries = kvStore.scan(startKey, endKey, readTs, 1000000);
            long indexSize = indexEntries.size();
            
            indexStats.put("index_size", indexSize);
            indexStats.put("index_name", index.getName());
            indexStats.put("columns", index.getColumns());
            
            // Calculate cardinality (distinct values)
            // For composite indexes, we count distinct combinations
            Set<String> distinctValues = new HashSet<>();
            
            // Get the types of indexed columns
            List<Class<?>> indexColumnTypes = new ArrayList<>();
            for (String indexColName : index.getColumns()) {
                for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                    if (col.getName().equalsIgnoreCase(indexColName)) {
                        indexColumnTypes.add(col.getJavaType());
                        break;
                    }
                }
            }
            
            // Decode index values from each entry and count distinct combinations
            for (KvStore.KvEntry entry : indexEntries) {
                try {
                    // Decode just the index column values (not PK or timestamp)
                    List<Object> indexValues = KeyEncoder.decodeIndexValues(entry.getKey(), indexColumnTypes);
                    
                    // Convert to string for Set comparison
                    // For composite indexes, this creates a string representation of the combination
                    String valueKey = indexValues.toString();
                    distinctValues.add(valueKey);
                } catch (Exception e) {
                    // If decoding fails, treat as distinct (pessimistic)
                    log.error("  ⚠️  Failed to decode index value: " + e.getMessage());
                    distinctValues.add(Base64.getEncoder().encodeToString(entry.getKey()));
                }
            }
            
            // Cardinality is the number of distinct index value combinations
            long cardinality = distinctValues.size();
            indexStats.put("cardinality", cardinality);
            
            // Calculate selectivity (cardinality / row count)
            // Selectivity of 1.0 means all values are unique (good for index)
            // Selectivity of 0.0 means all values are the same (bad for index)
            double selectivity = rowCount > 0 ? (double) cardinality / rowCount : 0.0;
            indexStats.put("selectivity", selectivity);
            
            return indexStats;
            
        } catch (Exception e) {
            log.error("  ⚠️  Failed to collect statistics for index " + index.getName() + ": " + e.getMessage());
            return indexStats;
        }
    }
    
    /**
     * Get cached statistics for a table.
     * 
     * @param tableName The name of the table
     * @return Statistics map, or null if not available
     */
    public Map<String, Object> getStatistics(String tableName) {
        return statisticsCache.get(tableName.toLowerCase());
    }
    
    /**
     * Get all cached statistics.
     * 
     * @return Map of table name to statistics
     */
    public Map<String, Map<String, Object>> getAllStatistics() {
        return new HashMap<>(statisticsCache);
    }
    
    /**
     * Clear statistics cache.
     */
    public void clearCache() {
        statisticsCache.clear();
    }
    
    /**
     * Get selectivity for a specific index.
     * Used by the cost-based optimizer.
     * 
     * @param tableName The table name
     * @param indexName The index name
     * @return Selectivity (0.0 to 1.0), or 0.5 if unknown
     */
    public double getIndexSelectivity(String tableName, String indexName) {
        Map<String, Object> tableStats = getStatistics(tableName);
        if (tableStats == null) {
            return 0.5; // Default selectivity if no statistics available
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> indexStats = (Map<String, Object>) tableStats.get(indexName);
        if (indexStats == null) {
            return 0.5;
        }
        
        Double selectivity = (Double) indexStats.get("selectivity");
        return selectivity != null ? selectivity : 0.5;
    }
    
    /**
     * Get row count for a table.
     * Used by the cost-based optimizer.
     * 
     * @param tableName The table name
     * @return Row count, or -1 if unknown
     */
    public long getRowCount(String tableName) {
        Map<String, Object> tableStats = getStatistics(tableName);
        if (tableStats == null) {
            return -1;
        }
        
        Long rowCount = (Long) tableStats.get("row_count");
        return rowCount != null ? rowCount : -1;
    }
}

