package com.geico.poc.cassandrasql.kv.jobs;

import java.util.HashMap;
import java.util.Map;

/**
 * Statistics for a table, used by cost-based optimizer.
 * 
 * Includes:
 * - Row count
 * - Column cardinality (distinct values)
 * - Min/max values per column
 * - Null counts
 * 
 * Similar to PostgreSQL's pg_statistics.
 */
public class TableStatistics {
    
    private final String tableName;
    private long rowCount;
    private long lastUpdated;
    
    // Column name -> distinct value count
    private Map<String, Long> cardinality = new HashMap<>();
    
    // Column name -> min value
    private Map<String, Object> minValue = new HashMap<>();
    
    // Column name -> max value
    private Map<String, Object> maxValue = new HashMap<>();
    
    // Column name -> null count
    private Map<String, Long> nullCount = new HashMap<>();
    
    public TableStatistics(String tableName) {
        this.tableName = tableName;
        this.lastUpdated = System.currentTimeMillis();
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public long getRowCount() {
        return rowCount;
    }
    
    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }
    
    public long getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    public Map<String, Long> getCardinality() {
        return cardinality;
    }
    
    public void setCardinality(Map<String, Long> cardinality) {
        this.cardinality = cardinality;
    }
    
    public Map<String, Object> getMinValue() {
        return minValue;
    }
    
    public void setMinValue(Map<String, Object> minValue) {
        this.minValue = minValue;
    }
    
    public Map<String, Object> getMaxValue() {
        return maxValue;
    }
    
    public void setMaxValue(Map<String, Object> maxValue) {
        this.maxValue = maxValue;
    }
    
    public Map<String, Long> getNullCount() {
        return nullCount;
    }
    
    public void setNullCount(Map<String, Long> nullCount) {
        this.nullCount = nullCount;
    }
    
    /**
     * Get selectivity estimate for a column.
     * 
     * Selectivity = 1 / cardinality
     * Lower selectivity = more selective (better for indexes)
     */
    public double getSelectivity(String column) {
        // Try exact match first
        Long card = cardinality.get(column);
        
        // If not found, try lowercase (statistics are stored in lowercase)
        if (card == null) {
            card = cardinality.get(column.toLowerCase());
        }
        
        if (card == null || card == 0) {
            return 1.0; // Unknown, assume not selective
        }
        return 1.0 / card;
    }
    
    @Override
    public String toString() {
        return String.format("TableStatistics[table=%s, rows=%d, columns=%d, updated=%d]",
                tableName, rowCount, cardinality.size(), lastUpdated);
    }
}



