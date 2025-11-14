package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.optimizer.QueryOptimizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Executes multi-way JOIN operations (3+ tables) using left-deep join tree
 */
@Component
public class MultiWayJoinExecutor {
    
    private final CassandraExecutor cassandraExecutor;
    private final QueryAnalyzer queryAnalyzer;
    private final PredicatePushdown predicatePushdown;
    
    @Autowired(required = false)
    private QueryOptimizer queryOptimizer;
    
    public MultiWayJoinExecutor(CassandraExecutor cassandraExecutor,
                               QueryAnalyzer queryAnalyzer,
                               PredicatePushdown predicatePushdown) {
        this.cassandraExecutor = cassandraExecutor;
        this.queryAnalyzer = queryAnalyzer;
        this.predicatePushdown = predicatePushdown;
    }
    
    /**
     * Execute multi-way join using left-deep join tree
     */
    public QueryResponse execute(MultiWayJoinQuery query, String originalSql, QueryAnalysis analysis) {
        System.out.println("=== MULTI-WAY JOIN EXECUTION ===");
        System.out.println("Tables: " + query.getTables());
        System.out.println("Join conditions: " + query.getJoinConditions().size());
        
        long startTime = System.currentTimeMillis();
        
        // Apply cost-based optimization if available
        MultiWayJoinQuery effectiveQuery = query;
        if (queryOptimizer != null) {
            try {
                ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.MULTI_WAY_JOIN, query, originalSql);
                QueryOptimizer.OptimizedQuery optimizedQuery = queryOptimizer.optimize(parsedQuery);
                
                if (optimizedQuery.hasOptimizations()) {
                    System.out.println("\n[COST-BASED OPTIMIZER]");
                    for (String opt : optimizedQuery.getOptimizations()) {
                        System.out.println("   ✅ " + opt);
                    }
                    System.out.println("   Estimated cost: " + String.format("%.2f", optimizedQuery.getEstimatedCost()));
                    
                    // Apply optimal table order if suggested
                    if (optimizedQuery.getOptimalTableOrder() != null) {
                        effectiveQuery = reorderTables(query, optimizedQuery.getOptimalTableOrder());
                    }
                }
            } catch (Exception e) {
                System.err.println("⚠️  Optimizer failed: " + e.getMessage());
                // Continue without optimization
            }
        }
        
        // Execute left-deep join tree
        List<Map<String, Object>> result = executeLeftDeepJoin(effectiveQuery, analysis);
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("\nMulti-way join complete: " + result.size() + " rows (" + totalTime + "ms)");
        System.out.println("================================\n");
        
        // Project columns
        List<String> columns = query.getSelectColumns();
        if (columns != null && !columns.isEmpty() && !columns.contains("*")) {
            result = projectColumns(result, columns);
            // Strip table prefixes from column names and convert to lowercase
            List<String> normalizedColumns = new ArrayList<>();
            for (String col : columns) {
                if (col.contains(".")) {
                    String[] parts = col.split("\\.");
                    normalizedColumns.add(parts[parts.length - 1].toLowerCase());
                } else {
                    normalizedColumns.add(col.toLowerCase());
                }
            }
            columns = normalizedColumns;
        } else {
            // Extract all columns from result
            columns = extractColumns(result);
        }
        
        return new QueryResponse(result, columns);
    }
    
    /**
     * Execute left-deep join tree: ((T1 ⋈ T2) ⋈ T3) ⋈ T4
     */
    private List<Map<String, Object>> executeLeftDeepJoin(MultiWayJoinQuery query, QueryAnalysis analysis) {
        // Start with first table
        String firstTable = query.getTables().get(0);
        List<Map<String, Object>> result = scanTable(firstTable, analysis);
        
        System.out.println("\n[STEP 1] Starting with " + firstTable + ": " + result.size() + " rows");
        
        // Join with each subsequent table
        for (int i = 1; i < query.getTables().size(); i++) {
            String nextTable = query.getTables().get(i);
            JoinCondition condition = query.getJoinConditions().get(i - 1);
            
            System.out.println("\n[STEP " + (i + 1) + "] Joining with " + nextTable);
            System.out.println("  Condition: " + condition);
            System.out.println("  Current result size: " + result.size() + " rows");
            
            // Perform binary join: result ⋈ nextTable
            result = joinWithTable(result, nextTable, condition, analysis);
            
            System.out.println("  After join: " + result.size() + " rows");
        }
        
        return result;
    }
    
    /**
     * Scan a single table with predicate pushdown
     */
    private List<Map<String, Object>> scanTable(String tableName, QueryAnalysis analysis) {
        // Get predicates for this table
        List<Predicate> predicates = analysis != null ? 
            analysis.getPredicatesForTable(tableName) : 
            Collections.emptyList();
        
        // Build query with predicates
        StringBuilder query = new StringBuilder("SELECT * FROM " + tableName);
        
        if (!predicates.isEmpty()) {
            query.append(" WHERE ");
            boolean first = true;
            for (Predicate pred : predicates) {
                if (!first) query.append(" AND ");
                query.append(pred.toCql());
                first = false;
            }
            query.append(" ALLOW FILTERING");
        }
        
        String cql = query.toString();
        System.out.println("  Scanning: " + cql);
        
        ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.SELECT, tableName, cql);
        QueryResponse response = cassandraExecutor.execute(parsedQuery);
        
        return response.getRows();
    }
    
    /**
     * Join current result with next table
     */
    private List<Map<String, Object>> joinWithTable(
            List<Map<String, Object>> leftData,
            String rightTable,
            JoinCondition condition,
            QueryAnalysis analysis) {
        
        // Scan right table (with predicate pushdown)
        List<Map<String, Object>> rightData = scanTable(rightTable, analysis);
        
        System.out.println("  Right table returned: " + rightData.size() + " rows");
        
        // Build hash table from left data (already filtered)
        Map<Object, List<Map<String, Object>>> hashTable = buildHashTable(
            leftData, condition.getLeftColumn(), condition.getLeftTable()
        );
        
        System.out.println("  Hash table built: " + hashTable.size() + " unique keys");
        
        // Probe with right data
        List<Map<String, Object>> results = new ArrayList<>();
        int nullKeyCount = 0;
        
        for (Map<String, Object> rightRow : rightData) {
            Object joinKey = extractJoinKey(rightRow, condition.getRightColumn(), condition.getRightTable());
            
            if (joinKey == null) {
                nullKeyCount++;
                continue;
            }
            
            List<Map<String, Object>> matches = hashTable.get(joinKey);
            if (matches != null) {
                for (Map<String, Object> leftRow : matches) {
                    results.add(mergeRows(leftRow, rightRow));
                }
            }
        }
        
        if (nullKeyCount > 0) {
            System.out.println("  Warning: " + nullKeyCount + " rows had null join keys");
        }
        
        return results;
    }
    
    /**
     * Build hash table from data
     */
    private Map<Object, List<Map<String, Object>>> buildHashTable(
            List<Map<String, Object>> data, 
            String joinColumn,
            String tableName) {
        
        Map<Object, List<Map<String, Object>>> hashTable = new HashMap<>();
        
        for (Map<String, Object> row : data) {
            Object key = extractJoinKey(row, joinColumn, tableName);
            
            if (key != null) {
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        
        return hashTable;
    }
    
    /**
     * Extract join key from row (case-insensitive)
     */
    private Object extractJoinKey(Map<String, Object> row, String columnName, String tableName) {
        // Try with table prefix (e.g., "u.id")
        String qualifiedName = tableName + "." + columnName;
        Object value = findColumnValue(row, qualifiedName);
        if (value != null) {
            return value;
        }
        
        // Try without prefix
        value = findColumnValue(row, columnName);
        if (value != null) {
            return value;
        }
        
        return null;
    }
    
    /**
     * Find column value in row (case-insensitive)
     */
    private Object findColumnValue(Map<String, Object> row, String column) {
        // Try exact match first
        if (row.containsKey(column)) {
            return row.get(column);
        }
        
        // Extract column name without table prefix
        String columnName = column.contains(".") ? 
            column.substring(column.lastIndexOf('.') + 1) : column;
        
        // Try case-insensitive match
        String columnLower = columnName.toLowerCase();
        
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            String keyLower = key.toLowerCase();
            
            // Check if key matches column name (with or without table prefix)
            if (keyLower.equals(columnLower) || 
                keyLower.endsWith("." + columnLower)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Merge two rows into one
     */
    private Map<String, Object> mergeRows(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new HashMap<>(left);
        merged.putAll(right);
        return merged;
    }
    
    /**
     * Reorder tables in the query based on optimizer suggestion
     */
    private MultiWayJoinQuery reorderTables(MultiWayJoinQuery original, List<String> optimalOrder) {
        // Create a new MultiWayJoinQuery with reordered tables
        // Keep the same join conditions, select columns, and table aliases
        return new MultiWayJoinQuery(
            optimalOrder,
            original.getJoinConditions(),
            original.getSelectColumns(),
            original.getTableAliases()
        );
    }
    
    /**
     * Project selected columns from results
     */
    private List<Map<String, Object>> projectColumns(List<Map<String, Object>> rows, List<String> columns) {
        List<Map<String, Object>> projected = new ArrayList<>();
        
        for (Map<String, Object> row : rows) {
            Map<String, Object> projectedRow = new LinkedHashMap<>();
            
            for (String column : columns) {
                Object value = findColumnValue(row, column);
                // Extract just the column name (strip table prefix) and convert to lowercase
                String columnName = column;
                if (column.contains(".")) {
                    String[] parts = column.split("\\.");
                    columnName = parts[parts.length - 1].toLowerCase();
                } else {
                    columnName = column.toLowerCase();
                }
                projectedRow.put(columnName, value);
            }
            
            projected.add(projectedRow);
        }
        
        return projected;
    }
    
    /**
     * Extract column names from result rows
     */
    private List<String> extractColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        
        return new ArrayList<>(rows.get(0).keySet());
    }
}





