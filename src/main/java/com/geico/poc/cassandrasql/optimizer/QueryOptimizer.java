package com.geico.poc.cassandrasql.optimizer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.JoinQuery;
import com.geico.poc.cassandrasql.MultiWayJoinQuery;
import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pragmatic Cost-Based Query Optimizer
 * 
 * Uses statistics and heuristics to optimize queries:
 * 1. Join reordering (smallest table first)
 * 2. Index selection
 * 3. Predicate pushdown
 * 4. Cost estimation
 * 
 * Inspired by Calcite's optimizer but simplified for Cassandra.
 */
@Component
public class QueryOptimizer {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired(required = false)
    private SchemaManager schemaManager;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    private final Map<String, TableStatistics> statsCache = new HashMap<>();
    
    /**
     * Optimize a parsed query
     */
    public OptimizedQuery optimize(ParsedQuery query) {
        if (query.getType() == ParsedQuery.Type.JOIN) {
            return optimizeJoin(query.getJoinQuery());
        } else if (query.getType() == ParsedQuery.Type.MULTI_WAY_JOIN) {
            return optimizeMultiWayJoin(query.getMultiWayJoin());
        } else {
            // For non-JOIN queries, return as-is
            return new OptimizedQuery(query, Collections.emptyList());
        }
    }
    
    /**
     * Optimize a binary JOIN
     */
    private OptimizedQuery optimizeJoin(JoinQuery joinQuery) {
        System.out.println("🔧 Optimizing binary JOIN...");
        
        List<String> optimizations = new ArrayList<>();
        
        // Get statistics for both tables
        TableStatistics leftStats = getTableStatistics(joinQuery.getLeftTable());
        TableStatistics rightStats = getTableStatistics(joinQuery.getRightTable());
        
        System.out.println("   Left table (" + joinQuery.getLeftTable() + "): ~" + leftStats.getRowCount() + " rows");
        System.out.println("   Right table (" + joinQuery.getRightTable() + "): ~" + rightStats.getRowCount() + " rows");
        
        // Optimization 1: Swap tables if left is larger (hash join optimization)
        boolean shouldSwap = leftStats.getRowCount() > rightStats.getRowCount();
        if (shouldSwap) {
            optimizations.add("Swapped join order (smaller table as build side)");
            System.out.println("   ✅ Optimization: Swap join order (build hash table from smaller table)");
        }
        
        // Optimization 2: Check for index usage on WHERE clause
        if (joinQuery.getWhereClause() != null && !joinQuery.getWhereClause().isEmpty()) {
            String indexAdvice = checkIndexUsage(joinQuery.getLeftTable(), joinQuery.getWhereClause());
            if (indexAdvice != null) {
                optimizations.add(indexAdvice);
                System.out.println("   ✅ " + indexAdvice);
            }
        }
        
        // Calculate estimated cost
        double cost = estimateJoinCost(leftStats, rightStats);
        System.out.println("   Estimated cost: " + String.format("%.2f", cost));
        
        return new OptimizedQuery(
            null,  // We don't modify the ParsedQuery
            optimizations,
            cost,
            shouldSwap
        );
    }
    
    /**
     * Optimize a multi-way JOIN
     */
    private OptimizedQuery optimizeMultiWayJoin(MultiWayJoinQuery joinQuery) {
        System.out.println("🔧 Optimizing multi-way JOIN (" + joinQuery.getTables().size() + " tables)...");
        
        List<String> optimizations = new ArrayList<>();
        
        // Get statistics for all tables
        List<TableWithStats> tablesWithStats = new ArrayList<>();
        for (String tableName : joinQuery.getTables()) {
            TableStatistics stats = getTableStatistics(tableName);
            tablesWithStats.add(new TableWithStats(tableName, stats));
            System.out.println("   Table " + tableName + ": ~" + stats.getRowCount() + " rows");
        }
        
        // Optimization: Reorder tables by size (smallest first)
        tablesWithStats.sort(Comparator.comparingLong(t -> t.stats.getRowCount()));
        
        List<String> optimalOrder = new ArrayList<>();
        for (TableWithStats t : tablesWithStats) {
            optimalOrder.add(t.tableName);
        }
        
        if (!optimalOrder.equals(joinQuery.getTables())) {
            optimizations.add("Reordered tables: " + optimalOrder);
            System.out.println("   ✅ Optimization: Reordered tables by size");
            System.out.println("      Original: " + joinQuery.getTables());
            System.out.println("      Optimized: " + optimalOrder);
        }
        
        // Calculate estimated cost
        double cost = estimateMultiWayJoinCost(tablesWithStats);
        System.out.println("   Estimated cost: " + String.format("%.2f", cost));
        
        return new OptimizedQuery(
            null,
            optimizations,
            cost,
            false,
            optimalOrder
        );
    }
    
    /**
     * Get statistics for a table
     */
    public TableStatistics getTableStatistics(String tableName) {
        // Check cache first
        if (statsCache.containsKey(tableName.toUpperCase())) {
            return statsCache.get(tableName.toUpperCase());
        }
        
        TableStatistics stats;
        
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            stats = getKvModeStatistics(tableName);
        } else {
            stats = getSchemaModeStatistics(tableName);
        }
        
        // Cache for future use
        statsCache.put(tableName.toUpperCase(), stats);
        
        return stats;
    }
    
    /**
     * Get statistics from KV mode
     */
    private TableStatistics getKvModeStatistics(String tableName) {
        // For KV mode, we'd need to scan or maintain statistics
        // For now, return estimates based on table metadata
        TableMetadata metadata = schemaManager.getTable(tableName);
        if (metadata == null) {
            return new TableStatistics(tableName, 1000, 5); // Default estimate (consistent with schema mode)
        }
        
        // TODO: Implement actual row counting for KV mode
        // For now, use a heuristic based on table ID (newer tables are smaller)
        long estimatedRows = Math.max(100, 10000 - (metadata.getTableId() * 1000));
        
        // Ensure column count is at least 1 (tables should have at least one column)
        int columnCount = Math.max(1, metadata.getColumns().size());
        
        return new TableStatistics(tableName, estimatedRows, columnCount);
    }
    
    /**
     * Get statistics from Cassandra system tables
     */
    private TableStatistics getSchemaModeStatistics(String tableName) {
        try {
            // Query Cassandra for table statistics
            ResultSet rs = session.execute(
                "SELECT estimated_partition_count FROM system.size_estimates WHERE keyspace_name = ? AND table_name = ?",
                keyspaceConfig.getDefaultKeyspace(), tableName.toLowerCase()
            );
            
            long rowCount = 0;
            for (Row row : rs) {
                rowCount += row.getLong("estimated_partition_count");
            }
            
            if (rowCount == 0) {
                // No statistics available, use default
                rowCount = 1000;
            }
            
            // Get column count
            ResultSet colRs = session.execute(
                "SELECT COUNT(*) as col_count FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
                keyspaceConfig.getDefaultKeyspace(), tableName.toLowerCase()
            );
            
            int columnCount = colRs.one() != null ? (int) colRs.one().getLong("col_count") : 5;
            
            return new TableStatistics(tableName, rowCount, columnCount);
            
        } catch (Exception e) {
            System.err.println("⚠️  Failed to get statistics for " + tableName + ": " + e.getMessage());
            return new TableStatistics(tableName, 1000, 5); // Default estimate
        }
    }
    
    /**
     * Check if an index can be used for the WHERE clause
     */
    private String checkIndexUsage(String tableName, String whereClause) {
        // Simple heuristic: check if WHERE clause mentions an indexed column
        // TODO: Implement proper index analysis
        return null;
    }
    
    /**
     * Estimate cost of a binary join
     */
    private double estimateJoinCost(TableStatistics left, TableStatistics right) {
        // Cost model: build_cost + probe_cost
        // build_cost = smaller_table_rows (to build hash table)
        // probe_cost = larger_table_rows * log(smaller_table_rows) (hash lookups)
        
        long buildRows = Math.min(left.getRowCount(), right.getRowCount());
        long probeRows = Math.max(left.getRowCount(), right.getRowCount());
        
        double buildCost = buildRows;
        double probeCost = probeRows * Math.log(buildRows + 1) / Math.log(2);
        
        return buildCost + probeCost;
    }
    
    /**
     * Estimate cost of a multi-way join
     */
    private double estimateMultiWayJoinCost(List<TableWithStats> tables) {
        if (tables.isEmpty()) {
            return 0;
        }
        
        // Cost model: cumulative join cost
        double totalCost = 0;
        long currentRows = tables.get(0).stats.getRowCount();
        
        for (int i = 1; i < tables.size(); i++) {
            long nextTableRows = tables.get(i).stats.getRowCount();
            // Cost of joining current result with next table
            double joinCost = currentRows * Math.log(nextTableRows + 1) / Math.log(2);
            totalCost += joinCost;
            // Result size grows (simplified model)
            currentRows = Math.min(currentRows * nextTableRows / 100, currentRows + nextTableRows);
        }
        
        return totalCost;
    }
    
    /**
     * Clear statistics cache (useful when data changes)
     */
    public void clearCache() {
        statsCache.clear();
        System.out.println("✅ Statistics cache cleared");
    }
    
    /**
     * Table statistics
     */
    public static class TableStatistics {
        private final String tableName;
        private final long rowCount;
        private final int columnCount;
        
        public TableStatistics(String tableName, long rowCount, int columnCount) {
            this.tableName = tableName;
            this.rowCount = rowCount;
            this.columnCount = columnCount;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public long getRowCount() {
            return rowCount;
        }
        
        public int getColumnCount() {
            return columnCount;
        }
    }
    
    /**
     * Table with statistics (for sorting)
     */
    private static class TableWithStats {
        final String tableName;
        final TableStatistics stats;
        
        TableWithStats(String tableName, TableStatistics stats) {
            this.tableName = tableName;
            this.stats = stats;
        }
    }
    
    /**
     * Optimized query result
     */
    public static class OptimizedQuery {
        private final ParsedQuery originalQuery;
        private final List<String> optimizations;
        private final double estimatedCost;
        private final boolean shouldSwapJoinSides;
        private final List<String> optimalTableOrder;
        
        public OptimizedQuery(ParsedQuery originalQuery, List<String> optimizations) {
            this(originalQuery, optimizations, 0.0, false, null);
        }
        
        public OptimizedQuery(ParsedQuery originalQuery, List<String> optimizations, double estimatedCost, boolean shouldSwapJoinSides) {
            this(originalQuery, optimizations, estimatedCost, shouldSwapJoinSides, null);
        }
        
        public OptimizedQuery(ParsedQuery originalQuery, List<String> optimizations, double estimatedCost, boolean shouldSwapJoinSides, List<String> optimalTableOrder) {
            this.originalQuery = originalQuery;
            this.optimizations = optimizations;
            this.estimatedCost = estimatedCost;
            this.shouldSwapJoinSides = shouldSwapJoinSides;
            this.optimalTableOrder = optimalTableOrder;
        }
        
        public ParsedQuery getOriginalQuery() {
            return originalQuery;
        }
        
        public List<String> getOptimizations() {
            return optimizations;
        }
        
        public double getEstimatedCost() {
            return estimatedCost;
        }
        
        public boolean shouldSwapJoinSides() {
            return shouldSwapJoinSides;
        }
        
        public List<String> getOptimalTableOrder() {
            return optimalTableOrder;
        }
        
        public boolean hasOptimizations() {
            return !optimizations.isEmpty();
        }
    }
}

