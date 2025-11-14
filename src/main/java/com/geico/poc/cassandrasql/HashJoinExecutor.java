package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.optimizer.QueryOptimizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Executes JOIN operations using Hash Join algorithm
 */
@Component
public class HashJoinExecutor {
    
    private final CassandraExecutor cassandraExecutor;
    private final QueryAnalyzer queryAnalyzer;
    private final PredicatePushdown predicatePushdown;
    
    @Autowired(required = false)
    private QueryOptimizer queryOptimizer;
    
    public HashJoinExecutor(CassandraExecutor cassandraExecutor,
                           QueryAnalyzer queryAnalyzer,
                           PredicatePushdown predicatePushdown) {
        this.cassandraExecutor = cassandraExecutor;
        this.queryAnalyzer = queryAnalyzer;
        this.predicatePushdown = predicatePushdown;
    }
    
    /**
     * Execute hash join between two tables (with optimization)
     * 
     * Algorithm:
     * 1. Build phase: Scan smaller table, build hash table on join key
     * 2. Probe phase: Scan larger table, probe hash table for matches
     * 
     * Complexity: O(N + M) where N, M are table sizes
     */
    public QueryResponse executeHashJoin(JoinQuery joinQuery) {
        return executeHashJoin(joinQuery, null, null);
    }
    
    /**
     * Execute hash join with query analysis and optimization
     */
    public QueryResponse executeHashJoin(JoinQuery joinQuery, String originalSql, QueryAnalysis analysis) {
        System.out.println("=== HASH JOIN EXECUTION ===");
        System.out.println("Join: " + joinQuery.getLeftTable() + " ⋈ " + joinQuery.getRightTable());
        System.out.println("Condition: " + joinQuery.getLeftJoinKey() + " = " + joinQuery.getRightJoinKey());
        System.out.println("Type: " + joinQuery.getJoinType());
        
        long startTime = System.currentTimeMillis();
        
        // Apply cost-based optimization if available
        boolean swapSides = false;
        if (queryOptimizer != null) {
            try {
                ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.JOIN, joinQuery, originalSql);
                QueryOptimizer.OptimizedQuery optimizedQuery = queryOptimizer.optimize(parsedQuery);
                
                if (optimizedQuery.hasOptimizations()) {
                    System.out.println("\n[COST-BASED OPTIMIZER]");
                    for (String opt : optimizedQuery.getOptimizations()) {
                        System.out.println("   ✅ " + opt);
                    }
                    System.out.println("   Estimated cost: " + String.format("%.2f", optimizedQuery.getEstimatedCost()));
                    
                    swapSides = optimizedQuery.shouldSwapJoinSides();
                }
            } catch (Exception e) {
                System.err.println("⚠️  Optimizer failed: " + e.getMessage());
                // Continue without optimization
            }
        }
        
        // Apply predicate pushdown optimization if analysis is available
        PredicatePushdown.OptimizedJoinQuery optimized = null;
        
        if (analysis != null && !analysis.getPredicates().isEmpty()) {
            System.out.println("\n[OPTIMIZATION] Applying predicate pushdown...");
            
            // Check if we should swap join sides for better performance
            // If right table has predicates (smaller result set), use it as build side
            int leftPredicateCount = analysis.getPredicatesForTable(joinQuery.getLeftTable()).size();
            int rightPredicateCount = analysis.getPredicatesForTable(joinQuery.getRightTable()).size();
            
            if (rightPredicateCount > leftPredicateCount && !swapSides) {
                System.out.println("[OPTIMIZATION] Swapping join sides: right table has more predicates (" + 
                                 rightPredicateCount + " vs " + leftPredicateCount + ")");
                swapSides = true;
            }
        }
        
        // Swap sides if optimization suggests it
        JoinQuery effectiveJoin = joinQuery;
        if (swapSides) {
            // Strip table prefixes from join keys before swapping
            String leftKeyStripped = stripTablePrefix(joinQuery.getRightJoinKey());
            String rightKeyStripped = stripTablePrefix(joinQuery.getLeftJoinKey());
            
            effectiveJoin = new JoinQuery(
                joinQuery.getRightTable(),
                joinQuery.getLeftTable(),
                leftKeyStripped,   // Use stripped key
                rightKeyStripped,  // Use stripped key
                joinQuery.getJoinType(),
                joinQuery.getSelectColumns(),
                joinQuery.getWhereClause()
            );
            
            System.out.println("[DEBUG] Swapped join keys: " + leftKeyStripped + " <-> " + rightKeyStripped);
            
            // Re-optimize with swapped join
            if (analysis != null && !analysis.getPredicates().isEmpty()) {
                optimized = predicatePushdown.optimize(effectiveJoin, analysis);
            }
        } else {
            // Use original optimization
            if (analysis != null && !analysis.getPredicates().isEmpty()) {
                optimized = predicatePushdown.optimize(joinQuery, analysis);
            }
        }
        
        // Phase 1: BUILD - Create hash table from left table (build side)
        System.out.println("\n[BUILD PHASE] Scanning " + effectiveJoin.getLeftTable());
        if (optimized != null) {
            System.out.println("Using optimized query: " + optimized.getLeftQuery());
        }
        Map<Object, List<Map<String, Object>>> hashTable = buildHashTable(effectiveJoin, optimized);
        
        long buildTime = System.currentTimeMillis() - startTime;
        System.out.println("Hash table built: " + hashTable.size() + " unique keys (" + buildTime + "ms)");
        
        // Phase 2: PROBE - Scan right table and probe hash table
        System.out.println("\n[PROBE PHASE] Scanning " + effectiveJoin.getRightTable());
        if (optimized != null) {
            System.out.println("Using optimized query: " + optimized.getRightQuery());
        }
        List<Map<String, Object>> results = probeHashTable(effectiveJoin, hashTable, optimized);
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("\nJoin complete: " + results.size() + " rows (" + totalTime + "ms)");
        
        // Apply unqualified predicates (those that couldn't be pushed down)
        if (analysis != null) {
            List<Predicate> unqualifiedPredicates = getUnqualifiedPredicates(analysis);
            if (!unqualifiedPredicates.isEmpty()) {
                System.out.println("\n[POST-JOIN FILTER] Applying " + unqualifiedPredicates.size() + " unqualified predicates");
                results = applyPostJoinFilter(results, unqualifiedPredicates);
                System.out.println("After filter: " + results.size() + " rows");
            }
        }
        
        System.out.println("===========================\n");
        
        // Extract column names from results
        List<String> columns = extractColumns(results, joinQuery);
        
        // Apply column selection if specified
        if (joinQuery.getSelectColumns() != null && !joinQuery.getSelectColumns().isEmpty()) {
            results = projectColumns(results, joinQuery.getSelectColumns());
            // Strip table prefixes from column names and convert to lowercase
            columns = new ArrayList<>();
            for (String col : joinQuery.getSelectColumns()) {
                if (col.contains(".")) {
                    String[] parts = col.split("\\.");
                    columns.add(parts[parts.length - 1].toLowerCase());
                } else {
                    columns.add(col.toLowerCase());
                }
            }
        }
        
        return new QueryResponse(results, columns);
    }
    
    /**
     * Build phase: Create hash table from left table
     */
    private Map<Object, List<Map<String, Object>>> buildHashTable(JoinQuery joinQuery, 
                                                                  PredicatePushdown.OptimizedJoinQuery optimized) {
        Map<Object, List<Map<String, Object>>> hashTable = new HashMap<>();
        
        // Use optimized query if available, otherwise full scan
        String leftQuery = (optimized != null) ? 
            optimized.getLeftQuery() : 
            "SELECT * FROM " + joinQuery.getLeftTable();
        
        ParsedQuery leftParsed = new ParsedQuery(
            ParsedQuery.Type.SELECT, 
            joinQuery.getLeftTable(), 
            leftQuery
        );
        
        QueryResponse leftData = cassandraExecutor.execute(leftParsed);
        
        System.out.println("  Left table returned " + leftData.getRows().size() + " rows");
        if (!leftData.getRows().isEmpty()) {
            System.out.println("  Sample row: " + leftData.getRows().get(0));
            System.out.println("  Join query left table: " + joinQuery.getLeftTable());
            System.out.println("  Join query left key: " + joinQuery.getLeftJoinKey());
            System.out.println("  Looking for join key: " + joinQuery.getLeftJoinKey());
        }
        
        // Build hash table: key -> list of rows with that key
        int rowCount = 0;
        int nullKeys = 0;
        for (Map<String, Object> row : leftData.getRows()) {
            Object key = extractJoinKey(row, joinQuery.getLeftJoinKey());
            
            if (key != null) {
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                rowCount++;
                if (rowCount <= 3) {
                    System.out.println("  Added to hash table: key=" + key + ", row=" + row);
                }
            } else {
                nullKeys++;
            }
        }
        
        System.out.println("  Scanned " + rowCount + " rows from " + joinQuery.getLeftTable());
        if (nullKeys > 0) {
            System.out.println("  Warning: " + nullKeys + " rows had null join keys");
        }
        System.out.println("  Hash table size: " + hashTable.size() + " unique keys");
        
        return hashTable;
    }
    
    /**
     * Probe phase: Scan right table and probe hash table
     */
    private List<Map<String, Object>> probeHashTable(
            JoinQuery joinQuery, 
            Map<Object, List<Map<String, Object>>> hashTable,
            PredicatePushdown.OptimizedJoinQuery optimized) {
        
        List<Map<String, Object>> results = new ArrayList<>();
        
        // Use optimized query if available, otherwise full scan
        String rightQuery = (optimized != null) ? 
            optimized.getRightQuery() : 
            "SELECT * FROM " + joinQuery.getRightTable();
        
        ParsedQuery rightParsed = new ParsedQuery(
            ParsedQuery.Type.SELECT,
            joinQuery.getRightTable(),
            rightQuery
        );
        
        QueryResponse rightData = cassandraExecutor.execute(rightParsed);
        
        System.out.println("  Right table returned " + rightData.getRows().size() + " rows");
        if (!rightData.getRows().isEmpty()) {
            System.out.println("  Sample row: " + rightData.getRows().get(0));
            System.out.println("  Looking for join key: " + joinQuery.getRightJoinKey());
        }
        
        int probeCount = 0;
        int matchCount = 0;
        int nullKeys = 0;
        
        // For each row in right table, probe hash table
        for (Map<String, Object> rightRow : rightData.getRows()) {
            probeCount++;
            Object key = extractJoinKey(rightRow, joinQuery.getRightJoinKey());
            
            if (probeCount <= 3) {
                System.out.println("  Probing: key=" + key + ", row=" + rightRow);
            }
            
            if (key != null) {
                List<Map<String, Object>> leftRows = hashTable.get(key);
                
                if (leftRows != null) {
                    // Match found - join rows
                    for (Map<String, Object> leftRow : leftRows) {
                        Map<String, Object> joinedRow = joinRows(
                            leftRow, rightRow,
                            joinQuery.getLeftTable(), joinQuery.getRightTable()
                        );
                        results.add(joinedRow);
                        matchCount++;
                        if (matchCount <= 3) {
                            System.out.println("  Match found! Joined row has " + joinedRow.size() + " columns");
                        }
                    }
                } else {
                    if (probeCount <= 3) {
                        System.out.println("  No match in hash table for key: " + key);
                    }
                    if (joinQuery.getJoinType() == JoinQuery.JoinType.RIGHT ||
                               joinQuery.getJoinType() == JoinQuery.JoinType.FULL) {
                        // RIGHT/FULL JOIN: include unmatched right rows
                        Map<String, Object> joinedRow = joinRows(
                            null, rightRow,
                            joinQuery.getLeftTable(), joinQuery.getRightTable()
                        );
                        results.add(joinedRow);
                    }
                }
            } else {
                nullKeys++;
            }
        }
        
        System.out.println("  Probed " + probeCount + " rows from " + joinQuery.getRightTable());
        if (nullKeys > 0) {
            System.out.println("  Warning: " + nullKeys + " rows had null join keys");
        }
        System.out.println("  Found " + matchCount + " matches");
        
        // Handle LEFT/FULL JOIN: include unmatched left rows
        if (joinQuery.getJoinType() == JoinQuery.JoinType.LEFT ||
            joinQuery.getJoinType() == JoinQuery.JoinType.FULL) {
            addUnmatchedLeftRows(results, hashTable, joinQuery);
        }
        
        return results;
    }
    
    /**
     * Strip table prefix from join key (e.g., "orders.product" -> "product")
     */
    private String stripTablePrefix(String joinKey) {
        if (joinKey.contains(".")) {
            String[] parts = joinKey.split("\\.");
            return parts[parts.length - 1];
        }
        return joinKey;
    }
    
    /**
     * Extract join key from row, handling table prefixes
     */
    private Object extractJoinKey(Map<String, Object> row, String joinKey) {
        // Try with table prefix (e.g., "u.id" or "U.ID")
        if (joinKey.contains(".")) {
            String[] parts = joinKey.split("\\.");
            String columnName = parts[parts.length - 1];
            
            // Try exact match first
            Object value = row.get(columnName);
            if (value != null) {
                return value;
            }
            
            // Try case-insensitive match
            String columnNameLower = columnName.toLowerCase();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().toLowerCase().equals(columnNameLower)) {
                    return entry.getValue();
                }
            }
            
            return null;
        }
        
        // Try direct column name (exact match)
        Object value = row.get(joinKey);
        if (value != null) {
            return value;
        }
        
        // Try case-insensitive match
        String joinKeyLower = joinKey.toLowerCase();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().toLowerCase().equals(joinKeyLower)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Join two rows into a single row
     */
    private Map<String, Object> joinRows(
            Map<String, Object> leftRow,
            Map<String, Object> rightRow,
            String leftTable,
            String rightTable) {
        
        Map<String, Object> joinedRow = new LinkedHashMap<>();
        
        // Add left row columns with table prefix
        if (leftRow != null) {
            for (Map.Entry<String, Object> entry : leftRow.entrySet()) {
                joinedRow.put(leftTable + "." + entry.getKey(), entry.getValue());
                joinedRow.put(entry.getKey(), entry.getValue()); // Also add without prefix
            }
        }
        
        // Add right row columns with table prefix
        if (rightRow != null) {
            for (Map.Entry<String, Object> entry : rightRow.entrySet()) {
                joinedRow.put(rightTable + "." + entry.getKey(), entry.getValue());
                // Only add without prefix if not already present
                if (!joinedRow.containsKey(entry.getKey())) {
                    joinedRow.put(entry.getKey(), entry.getValue());
                }
            }
        }
        
        return joinedRow;
    }
    
    /**
     * Add unmatched left rows for LEFT/FULL JOIN
     */
    private void addUnmatchedLeftRows(
            List<Map<String, Object>> results,
            Map<Object, List<Map<String, Object>>> hashTable,
            JoinQuery joinQuery) {
        
        // Track which left rows were matched
        Set<Map<String, Object>> matchedLeftRows = new HashSet<>();
        for (Map<String, Object> result : results) {
            // Extract original left row (simplified - would need better tracking)
            matchedLeftRows.add(result);
        }
        
        // Add unmatched left rows with null right side
        // (Simplified implementation - full version would track matches properly)
    }
    
    /**
     * Extract column names from result set
     */
    private List<String> extractColumns(List<Map<String, Object>> results, JoinQuery joinQuery) {
        if (results.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Get columns from first row
        return new ArrayList<>(results.get(0).keySet());
    }
    
    /**
     * Project only selected columns
     */
    private List<Map<String, Object>> projectColumns(
            List<Map<String, Object>> results,
            List<String> selectColumns) {
        
        List<Map<String, Object>> projected = new ArrayList<>();
        
        for (Map<String, Object> row : results) {
            Map<String, Object> projectedRow = new LinkedHashMap<>();
            
            for (String column : selectColumns) {
                Object value = findColumnValue(row, column);
                if (value != null) {
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
            }
            
            projected.add(projectedRow);
        }
        
        return projected;
    }
    
    /**
     * Find column value in row, handling case-insensitivity and table prefixes
     */
    private Object findColumnValue(Map<String, Object> row, String column) {
        // Try exact match first
        if (row.containsKey(column)) {
            return row.get(column);
        }
        
        // Extract column name without table prefix (e.g., "u.name" -> "name")
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
     * Get unqualified predicates (those without table name)
     */
    private List<Predicate> getUnqualifiedPredicates(QueryAnalysis analysis) {
        List<Predicate> unqualified = new ArrayList<>();
        for (Predicate pred : analysis.getPredicates()) {
            if (pred.getTableName() == null) {
                unqualified.add(pred);
            }
        }
        return unqualified;
    }
    
    /**
     * Apply predicates to joined results (post-join filter)
     */
    private List<Map<String, Object>> applyPostJoinFilter(List<Map<String, Object>> rows, 
                                                           List<Predicate> predicates) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        for (Map<String, Object> row : rows) {
            boolean matches = true;
            
            for (Predicate pred : predicates) {
                if (!evaluatePredicate(row, pred)) {
                    matches = false;
                    break;
                }
            }
            
            if (matches) {
                filtered.add(row);
            }
        }
        
        return filtered;
    }
    
    /**
     * Evaluate a predicate against a row
     */
    private boolean evaluatePredicate(Map<String, Object> row, Predicate pred) {
        // Find the column value (case-insensitive)
        Object value = findColumnValue(row, pred.getColumnName());
        
        System.out.println("  Evaluating predicate: " + pred.getColumnName() + " " + pred.getOperator() + " " + pred.getValue());
        System.out.println("  Found value: " + value + " in row keys: " + row.keySet());
        
        if (value == null) {
            System.out.println("  Result: " + (pred.getOperator() == Predicate.Operator.IS_NULL));
            return pred.getOperator() == Predicate.Operator.IS_NULL;
        }
        
        // Compare based on operator
        boolean result;
        switch (pred.getOperator()) {
            case EQUALS:
                result = value.toString().equals(pred.getValue().toString());
                System.out.println("  Comparing '" + value + "' == '" + pred.getValue() + "': " + result);
                return result;
            case NOT_EQUALS:
                return !value.toString().equals(pred.getValue().toString());
            case GREATER_THAN:
                return compareValues(value, pred.getValue()) > 0;
            case GREATER_EQUAL:
                return compareValues(value, pred.getValue()) >= 0;
            case LESS_THAN:
                return compareValues(value, pred.getValue()) < 0;
            case LESS_EQUAL:
                return compareValues(value, pred.getValue()) <= 0;
            case IS_NULL:
                return false; // value is not null if we got here
            case IS_NOT_NULL:
                return true; // value is not null if we got here
            case LIKE:
                // Simple LIKE implementation (% = wildcard)
                String pattern = pred.getValue().toString().replace("%", ".*");
                return value.toString().matches(pattern);
            default:
                System.err.println("Unsupported operator: " + pred.getOperator());
                return false;
        }
    }
    
    /**
     * Compare two values (handles strings and numbers)
     */
    private int compareValues(Object v1, Object v2) {
        try {
            // Try numeric comparison
            double d1 = Double.parseDouble(v1.toString());
            double d2 = Double.parseDouble(v2.toString());
            return Double.compare(d1, d2);
        } catch (NumberFormatException e) {
            // Fall back to string comparison
            return v1.toString().compareTo(v2.toString());
        }
    }
}

