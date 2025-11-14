package com.geico.poc.cassandrasql.kv;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.sql.SqlNode;
import com.geico.poc.cassandrasql.CalciteParser;
import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.calcite.KvPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Executes EXPLAIN and EXPLAIN ANALYZE queries to show query plans and execution statistics.
 */
@Component
public class ExplainExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(ExplainExecutor.class);
    
    @Autowired
    @Lazy
    private KvPlanner kvPlanner;
    
    @Autowired
    @Lazy
    private KvQueryExecutor kvQueryExecutor;
    
    /**
     * Execute EXPLAIN or EXPLAIN ANALYZE query
     */
    public QueryResponse execute(ParsedQuery explainQuery) {
        String targetSql = explainQuery.getExplainTargetSql();
        boolean analyze = explainQuery.isExplainAnalyze();
        
        log.info("🔍 EXPLAIN{} query: {}", analyze ? " ANALYZE" : "", targetSql);
        
        try {
            // Refresh schema to ensure KvPlanner has latest tables
            kvPlanner.refreshSchema();
            
            // Build the explain output
            List<String> planLines = new ArrayList<>();
            Map<String, Object> stats = new LinkedHashMap<>();
            
            // 1. Parse and validate the target SQL
            planLines.add("Query: " + targetSql);
            planLines.add("");
            
            SqlNode parsed = kvPlanner.parse(targetSql);
            planLines.add("✅ Parse: SUCCESS");
            planLines.add("  SQL Kind: " + parsed.getKind());
            planLines.add("");
            
            // 2. Validate and convert to logical plan (must be done together)
            RelNode logicalPlan = kvPlanner.validateAndConvert(parsed);
            planLines.add("✅ Validate: SUCCESS");
            planLines.add("");
            planLines.add("📋 Logical Plan (Before Optimization):");
            planLines.add("");
            String logicalPlanStr = logicalPlan.explain();
            for (String line : logicalPlanStr.split("\n")) {
                planLines.add("  " + line);
            }
            planLines.add("");
            
            // 4. Optimize the plan
            RelNode optimizedPlan = kvPlanner.optimize(logicalPlan);
            planLines.add("⚡ Optimized Plan (After Cost-Based Optimization):");
            planLines.add("");
            String optimizedPlanStr = optimizedPlan.explain();
            for (String line : optimizedPlanStr.split("\n")) {
                planLines.add("  " + line);
            }
            planLines.add("");
            
            // 4.5. Extract and show index selection
            List<String> indexInfo = extractIndexInformation(targetSql, optimizedPlan);
            if (!indexInfo.isEmpty()) {
                planLines.add("📊 Index Selection:");
                for (String info : indexInfo) {
                    planLines.add("  " + info);
                }
                planLines.add("");
            }
            
            // 5. Show cost estimates
            planLines.add("💰 Cost Estimates:");
            if (optimizedPlan.getCluster() != null && optimizedPlan.getCluster().getMetadataQuery() != null) {
                try {
                    RelMetadataQuery mq = optimizedPlan.getCluster().getMetadataQuery();
                    Double rowCount = mq.getRowCount(optimizedPlan);
                    if (rowCount != null) {
                        planLines.add("  Rows: " + String.format("%.2f", rowCount));
                    }
                    Double selectivity = mq.getSelectivity(optimizedPlan, null);
                    if (selectivity != null) {
                        planLines.add("  Selectivity: " + String.format("%.4f", selectivity));
                    }
                } catch (Exception e) {
                    // Metadata not available for this node type
                    planLines.add("  (Cost metadata not available)");
                }
            }
            planLines.add("");
            
            // 6. If EXPLAIN ANALYZE, execute the query and collect stats
            if (analyze) {
                planLines.add("⏱️  Execution Statistics:");
                planLines.add("");
                
                long startTime = System.nanoTime();
                long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                
                // Execute the target query
                CalciteParser parser = new CalciteParser();
                ParsedQuery targetQuery = parser.parse(targetSql);
                QueryResponse targetResponse = kvQueryExecutor.execute(targetQuery);
                
                long endTime = System.nanoTime();
                long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                
                double executionTimeMs = (endTime - startTime) / 1_000_000.0;
                long memoryUsedBytes = endMemory - startMemory;
                
                stats.put("execution_time_ms", executionTimeMs);
                stats.put("rows_returned", targetResponse.getRowCount());
                stats.put("memory_used_bytes", memoryUsedBytes);
                
                planLines.add("  Execution Time: " + String.format("%.3f ms", executionTimeMs));
                planLines.add("  Rows Returned: " + targetResponse.getRowCount());
                planLines.add("  Memory Used: " + formatBytes(memoryUsedBytes));
                
                if (targetResponse.getError() != null) {
                    planLines.add("  ❌ Error: " + targetResponse.getError());
                }
                planLines.add("");
            }
            
            // 7. Add optimizer info
            planLines.add("🔧 Optimizer Configuration:");
            planLines.add("  Cost-Based Optimizer: ENABLED");
            planLines.add("  Planner: Apache Calcite VolcanoPlanner");
            planLines.add("  Rules: Filter Pushdown, Index Selection, Join Reordering");
            planLines.add("");
            
            // Build response as a single-column table with plan text
            List<String> columns = Arrays.asList("QUERY PLAN");
            List<Map<String, Object>> rows = new ArrayList<>();
            
            for (String line : planLines) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("QUERY PLAN", line);
                rows.add(row);
            }
            
            return new QueryResponse(rows, columns);
            
        } catch (Exception e) {
            log.error("Failed to explain query: {}", targetSql, e);
            
            // Return error as explain output
            List<String> columns = Arrays.asList("QUERY PLAN");
            List<Map<String, Object>> rows = new ArrayList<>();
            
            Map<String, Object> errorRow = new LinkedHashMap<>();
            errorRow.put("QUERY PLAN", "❌ EXPLAIN failed: " + e.getMessage());
            rows.add(errorRow);
            
            Map<String, Object> stackRow = new LinkedHashMap<>();
            stackRow.put("QUERY PLAN", "");
            rows.add(stackRow);
            
            Map<String, Object> detailRow = new LinkedHashMap<>();
            detailRow.put("QUERY PLAN", "Error Details:");
            rows.add(detailRow);
            
            for (StackTraceElement element : e.getStackTrace()) {
                if (element.getClassName().startsWith("org.cassandrasql")) {
                    Map<String, Object> traceRow = new LinkedHashMap<>();
                    traceRow.put("QUERY PLAN", "  at " + element.toString());
                    rows.add(traceRow);
                }
            }
            
            return new QueryResponse(rows, columns);
        }
    }
    
    /**
     * Extract index information from the query
     */
    private List<String> extractIndexInformation(String sql, RelNode plan) {
        List<String> indexInfo = new ArrayList<>();
        
        try {
            // Parse the SQL to find WHERE clauses
            String upperSql = sql.toUpperCase();
            if (!upperSql.contains("WHERE")) {
                indexInfo.add("No WHERE clause - full table scan");
                return indexInfo;
            }
            
            // Extract table name
            String tableName = extractTableName(sql);
            if (tableName == null) {
                return indexInfo;
            }
            
            // Get table metadata
            TableMetadata table = kvQueryExecutor.getSchemaManager().getTable(tableName);
            if (table == null) {
                return indexInfo;
            }
            
            // Extract WHERE conditions
            int whereIdx = upperSql.indexOf("WHERE");
            String whereClause = sql.substring(whereIdx + 5).trim();
            
            // Remove ORDER BY, LIMIT, etc.
            whereClause = whereClause.split("ORDER BY")[0].split("LIMIT")[0].split("GROUP BY")[0].trim();
            
            // Find applicable indexes
            List<TableMetadata.IndexMetadata> applicableIndexes = new ArrayList<>();
            for (TableMetadata.IndexMetadata index : table.getIndexes()) {
                for (String indexCol : index.getColumns()) {
                    if (whereClause.toUpperCase().contains(indexCol.toUpperCase())) {
                        applicableIndexes.add(index);
                        break;
                    }
                }
            }
            
            if (applicableIndexes.isEmpty()) {
                indexInfo.add("No applicable indexes found - full table scan");
                indexInfo.add("Available indexes: " + table.getIndexes().stream()
                    .map(idx -> idx.getName() + " (" + String.join(", ", idx.getColumns()) + ")")
                    .collect(java.util.stream.Collectors.joining(", ")));
            } else {
                // Choose the best index using cost-based selection
                TableMetadata.IndexMetadata selectedIndex = selectBestIndex(applicableIndexes, table);
                
                indexInfo.add("✅ Selected Index: " + selectedIndex.getName());
                indexInfo.add("   Columns: " + String.join(", ", selectedIndex.getColumns()));
                indexInfo.add("   Type: " + (selectedIndex.isUnique() ? "UNIQUE" : "NON-UNIQUE"));
                
                // Show cardinality and selectivity
                if (selectedIndex.getTotalRows() > 0) {
                    indexInfo.add("   Cardinality: " + selectedIndex.getDistinctValues() + 
                        " distinct values / " + selectedIndex.getTotalRows() + " total rows");
                    indexInfo.add("   Selectivity: " + String.format("%.4f", selectedIndex.getSelectivity()) + 
                        " (" + (selectedIndex.getSelectivity() > 0.5 ? "HIGH" : "LOW") + ")");
                } else {
                    indexInfo.add("   Selectivity: HIGH (statistics not yet collected)");
                }
                
                if (applicableIndexes.size() > 1) {
                    indexInfo.add("   Other applicable indexes (ranked by selectivity):");
                    for (int i = 1; i < applicableIndexes.size(); i++) {
                        TableMetadata.IndexMetadata altIndex = applicableIndexes.get(i);
                        indexInfo.add("     - " + altIndex.getName() + " (" + 
                            String.join(", ", altIndex.getColumns()) + 
                            ", selectivity=" + String.format("%.4f", altIndex.getSelectivity()) + ")");
                    }
                }
            }
            
        } catch (Exception e) {
            log.warn("Failed to extract index information: {}", e.getMessage());
        }
        
        return indexInfo;
    }
    
    /**
     * Select the best index from applicable indexes using cost-based optimization
     */
    private TableMetadata.IndexMetadata selectBestIndex(
            List<TableMetadata.IndexMetadata> applicableIndexes, 
            TableMetadata table) {
        
        // Sort indexes by cost (lower is better)
        applicableIndexes.sort((idx1, idx2) -> {
            double cost1 = calculateIndexCost(idx1, table);
            double cost2 = calculateIndexCost(idx2, table);
            return Double.compare(cost1, cost2);
        });
        
        return applicableIndexes.get(0);
    }
    
    /**
     * Calculate the estimated cost of using an index
     * Cost formula considers:
     * - Selectivity (higher is better - fewer rows to scan)
     * - Index type (UNIQUE is best)
     * - Cardinality (more distinct values = better selectivity)
     */
    private double calculateIndexCost(TableMetadata.IndexMetadata index, TableMetadata table) {
        // Base cost starts at 100
        double cost = 100.0;
        
        // UNIQUE indexes are always best (cost = 1)
        if (index.isUnique()) {
            return 1.0;
        }
        
        // If we have statistics, use them
        if (index.getTotalRows() > 0) {
            // Higher selectivity = lower cost
            // Selectivity ranges from 0.0 to 1.0
            // Cost should be inversely proportional to selectivity
            double selectivity = index.getSelectivity();
            if (selectivity > 0) {
                cost = cost / selectivity;
            }
            
            // Adjust cost based on total rows (larger indexes cost more to scan)
            double rowFactor = Math.log(Math.max(index.getTotalRows(), 1.0)) / Math.log(2.0);
            cost += rowFactor;
        } else {
            // No statistics - use heuristic based on index position
            // Assume first column in WHERE clause is most selective
            cost = 50.0;
        }
        
        return cost;
    }
    
    /**
     * Extract table name from SQL query
     */
    private String extractTableName(String sql) {
        try {
            String upperSql = sql.toUpperCase();
            int fromIdx = upperSql.indexOf(" FROM ");
            if (fromIdx < 0) {
                return null;
            }
            
            String afterFrom = sql.substring(fromIdx + 6).trim();
            
            // Extract table name (stop at WHERE, JOIN, ORDER BY, etc.)
            String[] stopWords = {" WHERE", " JOIN", " ORDER", " GROUP", " LIMIT", " INNER", " LEFT", " RIGHT"};
            int endIdx = afterFrom.length();
            for (String stopWord : stopWords) {
                int idx = afterFrom.toUpperCase().indexOf(stopWord);
                if (idx > 0 && idx < endIdx) {
                    endIdx = idx;
                }
            }
            
            String tableName = afterFrom.substring(0, endIdx).trim();
            
            // Remove alias if present
            if (tableName.contains(" ")) {
                tableName = tableName.split(" ")[0];
            }
            
            return tableName;
        } catch (Exception e) {
            log.warn("Failed to extract table name: {}", e.getMessage());
            return null;
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }
}

