package com.geico.poc.cassandrasql.kv.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import com.geico.poc.cassandrasql.kv.KvStore;
import com.geico.poc.cassandrasql.kv.TimestampOracle;
import com.geico.poc.cassandrasql.kv.KeyEncoder;
import com.geico.poc.cassandrasql.kv.ValueEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Statistics collector job for cost-based optimizer in KV mode.
 * 
 * Collects:
 * - Row counts per table
 * - Column cardinality (distinct values)
 * - Min/max values per column
 * - Null counts
 * 
 * Uses sampling for large tables to avoid full scans.
 * Similar to PostgreSQL's ANALYZE command.
 */
@Component
public class StatisticsCollectorJob implements BackgroundJob {
    
    private static final Logger log = LoggerFactory.getLogger(StatisticsCollectorJob.class);
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private KeyEncoder keyEncoder;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Value("${cassandra-sql.kv.statistics.enabled:true}")
    private boolean enabled;
    
    @Value("${cassandra-sql.kv.statistics.period-minutes:30}")
    private int periodMinutes;
    
    @Value("${cassandra-sql.kv.statistics.sample-rate:0.1}")
    private double sampleRate;
    
    // In-memory cache of statistics (for optimizer)
    private Map<String, TableStatistics> statisticsCache = new HashMap<>();
    private RateLimiter rateLimiter;
    
    @Override
    public void execute() {
        try {
            // Initialize rate limiter if needed
            if (rateLimiter == null && config.getBackgroundJobs().isRateLimitEnabled()) {
                rateLimiter = new RateLimiter(config.getBackgroundJobs().getKeysPerHour());
                log.info("   Rate limiter initialized: " + rateLimiter.getKeysPerHour() + " keys/hour");
            }
            
            List<String> tableNames = schemaManager.getAllTableNames();
            
            // Filter out internal tables
            tableNames = tableNames.stream()
                .filter(name -> !name.startsWith("kv_") && 
                               !name.startsWith("tx_") && 
                               !name.startsWith("pg_") &&
                               !name.startsWith("information_schema_"))
                .toList();
            
            if (tableNames.isEmpty()) {
                log.info("   No user tables to analyze");
                return;
            }
            
            log.info("   Analyzing " + tableNames.size() + " table(s)");
            
            for (String tableName : tableNames) {
                try {
                    TableStatistics stats = collectStatistics(tableName);
                    storeStatistics(stats);
                    statisticsCache.put(tableName, stats);
                    
                    log.info("   " + tableName + ": " + stats.getRowCount() + " rows, " +
                                      stats.getCardinality().size() + " columns");
                } catch (Exception e) {
                    log.error("   Failed to analyze table " + tableName + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.error("   Statistics collection failed: " + e.getMessage());
            throw new RuntimeException("Statistics collection failed", e);
        }
    }
    
    /**
     * Collect statistics for a table
     */
    private TableStatistics collectStatistics(String tableName) {
        TableMetadata table = schemaManager.getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        
        TableStatistics stats = new TableStatistics(tableName);
        
        // Get current timestamp for reading
        long readTs = timestampOracle.getCurrentTimestamp();
        
        // Scan table and collect statistics
        // For now, do a full scan (TODO: implement sampling for large tables)
        List<Map<String, Object>> rows = scanTable(tableName, readTs);
        
        stats.setRowCount(rows.size());
        
        if (rows.isEmpty()) {
            return stats;
        }
        
        // Collect column statistics
        // Normalize column names to lowercase for consistency (matching how rows store column names)
        for (TableMetadata.ColumnMetadata column : table.getColumns()) {
            collectColumnStatistics(stats, column.getName().toLowerCase(), rows);
        }
        
        return stats;
    }
    
    /**
     * Scan table to get all rows
     */
    private List<Map<String, Object>> scanTable(String tableName, long readTs) {
        // Get table metadata to get the actual table ID
        TableMetadata table = schemaManager.getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        
        // Build start and end keys for table using actual table ID
        byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        
        // Scan KV store (with limit to avoid OOM on large tables)
        int limit = 10000; // TODO: Make configurable
        List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, limit);
        
        // Get column types for decoding
        List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return Object.class; // Default
                })
                .collect(Collectors.toList());
        
        List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
        
        // Decode rows
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KvStore.KvEntry entry : entries) {
            // Rate limit: one key per iteration
            if (rateLimiter != null) {
                try {
                    rateLimiter.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("   Statistics collection interrupted during rate limiting");
                    break;
                }
            }
            
            if (!entry.isDeleted() && entry.getValue() != null) {
                try {
                    Map<String, Object> row = decodeRow(entry, table, pkColumnTypes, nonPkColumnTypes);
                    rows.add(row);
                } catch (Exception e) {
                    // Skip invalid rows
                    log.debug("   Warning: Could not decode row: " + e.getMessage());
                }
            }
        }
        return rows;
    }
    
    /**
     * Decode row from KV entry (key + value)
     */
    private Map<String, Object> decodeRow(KvStore.KvEntry entry, TableMetadata table, 
                                          List<Class<?>> pkColumnTypes, List<Class<?>> nonPkColumnTypes) {
        Map<String, Object> row = new HashMap<>();
        
        // Decode primary key values from the key
        List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
        
        // Add PK columns
        for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
            String colName = table.getPrimaryKeyColumns().get(i);
            row.put(colName.toLowerCase(), pkValues.get(i));
        }
        
        // Decode non-PK values from the value bytes
        List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
        
        // Add non-PK columns
        List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
        for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
            String colName = nonPkColumns.get(i).getName();
            row.put(colName.toLowerCase(), nonPkValues.get(i));
        }
        
        return row;
    }
    
    /**
     * Collect statistics for a single column
     * 
     * @param column Column name (should already be normalized to lowercase)
     */
    private void collectColumnStatistics(TableStatistics stats, String column, List<Map<String, Object>> rows) {
        Set<Object> distinctValues = new HashSet<>();
        Object minValue = null;
        Object maxValue = null;
        long nullCount = 0;
        
        // Column name should already be lowercase (normalized by caller)
        // Rows store columns in lowercase, so lookup should match
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            
            if (value == null) {
                nullCount++;
                continue;
            }
            
            distinctValues.add(value);
            
            // Update min/max
            if (value instanceof Comparable) {
                if (minValue == null || ((Comparable) value).compareTo(minValue) < 0) {
                    minValue = value;
                }
                if (maxValue == null || ((Comparable) value).compareTo(maxValue) > 0) {
                    maxValue = value;
                }
            }
        }
        
        // Store statistics using normalized lowercase column name
        stats.getCardinality().put(column, (long) distinctValues.size());
        stats.getNullCount().put(column, nullCount);
        
        if (minValue != null) {
            stats.getMinValue().put(column, minValue);
        }
        if (maxValue != null) {
            stats.getMaxValue().put(column, maxValue);
        }
    }
    
    /**
     * Store statistics (for now, just keep in memory)
     * TODO: Persist to system_statistics table
     */
    private void storeStatistics(TableStatistics stats) {
        // For now, statistics are only kept in memory
        // In production, should persist to Cassandra for durability
    }
    
    /**
     * Get statistics for a table (for optimizer)
     */
    public TableStatistics getStatistics(String tableName) {
        return statisticsCache.get(tableName);
    }
    
    /**
     * Get all statistics (for optimizer)
     */
    public Map<String, TableStatistics> getAllStatistics() {
        return new HashMap<>(statisticsCache);
    }
    
    @Override
    public String getName() {
        return "StatisticsCollectorJob";
    }
    
    @Override
    public long getInitialDelayMs() {
        // Start after 2 minutes
        return 2 * 60 * 1000;
    }
    
    @Override
    public long getPeriodMs() {
        return periodMinutes * 60 * 1000L;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

