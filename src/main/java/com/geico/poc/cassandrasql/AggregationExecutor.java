package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Executes queries with GROUP BY and aggregation functions
 */
@Component
public class AggregationExecutor {
    
    private final CassandraExecutor cassandraExecutor;
    private final HashJoinExecutor hashJoinExecutor;
    private final MultiWayJoinExecutor multiWayJoinExecutor;
    private final QueryAnalyzer queryAnalyzer;
    
    public AggregationExecutor(CassandraExecutor cassandraExecutor,
                              HashJoinExecutor hashJoinExecutor,
                              MultiWayJoinExecutor multiWayJoinExecutor,
                              QueryAnalyzer queryAnalyzer) {
        this.cassandraExecutor = cassandraExecutor;
        this.hashJoinExecutor = hashJoinExecutor;
        this.multiWayJoinExecutor = multiWayJoinExecutor;
        this.queryAnalyzer = queryAnalyzer;
    }
    
    /**
     * Execute aggregation query
     */
    public QueryResponse execute(AggregationQuery aggQuery) throws Exception {
        System.out.println("=== AGGREGATION EXECUTION ===");
        System.out.println("GROUP BY: " + aggQuery.getGroupByColumns());
        System.out.println("Aggregates: " + aggQuery.getAggregates());
        
        long startTime = System.currentTimeMillis();
        
        // 1. Execute base query (SELECT or JOIN)
        QueryResponse baseResult = executeBaseQuery(aggQuery.getBaseQuery());
        
        System.out.println("Base query returned: " + baseResult.getRows().size() + " rows");
        
        // 2. Group rows by GROUP BY columns
        Map<GroupKey, List<Map<String, Object>>> groups = groupRows(
            baseResult.getRows(),
            aggQuery.getGroupByColumns()
        );
        
        System.out.println("Grouped into: " + groups.size() + " groups");
        
        // 3. Compute aggregates for each group
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<GroupKey, List<Map<String, Object>>> entry : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            
            // Add GROUP BY columns
            row.putAll(entry.getKey().getValues());
            
            // Compute aggregates
            for (AggregateFunction agg : aggQuery.getAggregates()) {
                Object value = computeAggregate(agg, entry.getValue());
                row.put(agg.getAlias(), value);
            }
            
            results.add(row);
        }
        
        System.out.println("Before HAVING: " + results.size() + " rows");
        
        // 4. Apply HAVING filter
        if (aggQuery.hasHaving()) {
            results = applyHaving(results, aggQuery.getHavingClause(), aggQuery.getAggregates());
            System.out.println("After HAVING: " + results.size() + " rows");
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("Aggregation complete: " + results.size() + " rows (" + totalTime + "ms)");
        System.out.println("=============================\n");
        
        // Extract column names
        List<String> columns = extractColumns(results, aggQuery);
        
        return new QueryResponse(results, columns);
    }
    
    /**
     * Execute the base query (SELECT, JOIN, or MULTI_WAY_JOIN)
     */
    private QueryResponse executeBaseQuery(ParsedQuery parsedQuery) throws Exception {
        if (parsedQuery.isMultiWayJoin()) {
            QueryAnalysis analysis = queryAnalyzer.analyze(parsedQuery.getRawSql(), parsedQuery);
            return multiWayJoinExecutor.execute(
                parsedQuery.getMultiWayJoin(),
                parsedQuery.getRawSql(),
                analysis
            );
        } else if (parsedQuery.isJoin()) {
            QueryAnalysis analysis = queryAnalyzer.analyze(parsedQuery.getRawSql(), parsedQuery);
            return hashJoinExecutor.executeHashJoin(
                parsedQuery.getJoinQuery(),
                parsedQuery.getRawSql(),
                analysis
            );
        } else {
            return cassandraExecutor.execute(parsedQuery);
        }
    }
    
    /**
     * Group rows by GROUP BY columns
     */
    private Map<GroupKey, List<Map<String, Object>>> groupRows(
            List<Map<String, Object>> rows,
            List<String> groupByColumns) {
        
        Map<GroupKey, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        
        for (Map<String, Object> row : rows) {
            // Extract group key values
            Map<String, Object> keyValues = new LinkedHashMap<>();
            for (String column : groupByColumns) {
                Object value = findColumnValue(row, column);
                keyValues.put(column, value);
            }
            
            GroupKey key = new GroupKey(keyValues);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        
        return groups;
    }
    
    /**
     * Compute aggregate function for a group
     */
    private Object computeAggregate(AggregateFunction agg, List<Map<String, Object>> rows) {
        switch (agg.getType()) {
            case COUNT:
                return rows.size();
            
            case SUM:
                return rows.stream()
                    .map(row -> findColumnValue(row, agg.getColumn()))
                    .filter(Objects::nonNull)
                    .map(this::toNumber)
                    .filter(Objects::nonNull)
                    .mapToDouble(Number::doubleValue)
                    .sum();
            
            case AVG:
                OptionalDouble avg = rows.stream()
                    .map(row -> findColumnValue(row, agg.getColumn()))
                    .filter(Objects::nonNull)
                    .map(this::toNumber)
                    .filter(Objects::nonNull)
                    .mapToDouble(Number::doubleValue)
                    .average();
                return avg.isPresent() ? avg.getAsDouble() : null;
            
            case MIN:
                return rows.stream()
                    .map(row -> findColumnValue(row, agg.getColumn()))
                    .filter(Objects::nonNull)
                    .map(this::toComparable)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            
            case MAX:
                return rows.stream()
                    .map(row -> findColumnValue(row, agg.getColumn()))
                    .filter(Objects::nonNull)
                    .map(this::toComparable)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            
            default:
                throw new UnsupportedOperationException("Unsupported aggregate: " + agg.getType());
        }
    }
    
    /**
     * Convert value to Number
     */
    private Number toNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        } else if (value instanceof String) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * Convert value to Comparable
     */
    @SuppressWarnings("unchecked")
    private Comparable<Object> toComparable(Object value) {
        if (value instanceof Comparable) {
            return (Comparable<Object>) value;
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
     * Extract column names from results
     */
    private List<String> extractColumns(List<Map<String, Object>> results, AggregationQuery aggQuery) {
        if (results.isEmpty()) {
            // Return GROUP BY columns + aggregate aliases
            List<String> columns = new ArrayList<>(aggQuery.getGroupByColumns());
            for (AggregateFunction agg : aggQuery.getAggregates()) {
                columns.add(agg.getAlias());
            }
            return columns;
        }
        
        return new ArrayList<>(results.get(0).keySet());
    }
    
    /**
     * Apply HAVING filter to aggregated results
     */
    private List<Map<String, Object>> applyHaving(List<Map<String, Object>> results, 
                                                   String havingClause,
                                                   List<AggregateFunction> aggregates) {
        System.out.println("  🔍 Applying HAVING: " + havingClause);
        
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        for (Map<String, Object> row : results) {
            if (evaluateHaving(row, havingClause, aggregates)) {
                filtered.add(row);
            }
        }
        
        return filtered;
    }
    
    /**
     * Evaluate HAVING condition for a single row
     */
    private boolean evaluateHaving(Map<String, Object> row, String havingClause, List<AggregateFunction> aggregates) {
        try {
            // Parse HAVING clause to extract comparison
            // Examples: "COUNT(*) > 5", "SUM(amount) >= 100", "AVG(price) < 50"
            
            System.out.println("    Evaluating HAVING for row: " + row);
            String clause = havingClause.trim();
            System.out.println("    HAVING clause: " + clause);
            
            // Find comparison operator
            String operator = null;
            int operatorPos = -1;
            
            if (clause.contains(">=")) {
                operator = ">=";
                operatorPos = clause.indexOf(">=");
            } else if (clause.contains("<=")) {
                operator = "<=";
                operatorPos = clause.indexOf("<=");
            } else if (clause.contains("<>") || clause.contains("!=")) {
                operator = clause.contains("<>") ? "<>" : "!=";
                operatorPos = clause.indexOf(operator);
            } else if (clause.contains(">")) {
                operator = ">";
                operatorPos = clause.indexOf(">");
            } else if (clause.contains("<")) {
                operator = "<";
                operatorPos = clause.indexOf("<");
            } else if (clause.contains("=")) {
                operator = "=";
                operatorPos = clause.indexOf("=");
            } else {
                System.err.println("  ⚠️  Unsupported HAVING operator in: " + clause);
                return true; // Don't filter if we can't parse
            }
            
            // Extract left side (aggregate function) and right side (value)
            String leftSide = clause.substring(0, operatorPos).trim();
            String rightSide = clause.substring(operatorPos + operator.length()).trim();
            
            // Find the aggregate value in the row
            System.out.println("    Looking for aggregate: " + leftSide);
            Object aggregateValue = findAggregateValue(row, leftSide, aggregates);
            System.out.println("    Found value: " + aggregateValue);
            if (aggregateValue == null) {
                System.err.println("  ⚠️  Could not find aggregate value for: " + leftSide);
                return true; // Don't filter if we can't find the value
            }
            
            // Parse comparison value
            double compareValue;
            try {
                compareValue = Double.parseDouble(rightSide);
            } catch (NumberFormatException e) {
                System.err.println("  ⚠️  Invalid comparison value: " + rightSide);
                return true;
            }
            
            // Convert aggregate value to double for comparison
            double aggValue = ((Number) aggregateValue).doubleValue();
            
            // Perform comparison
            boolean result;
            switch (operator) {
                case ">":
                    result = aggValue > compareValue;
                    break;
                case ">=":
                    result = aggValue >= compareValue;
                    break;
                case "<":
                    result = aggValue < compareValue;
                    break;
                case "<=":
                    result = aggValue <= compareValue;
                    break;
                case "=":
                    result = Math.abs(aggValue - compareValue) < 0.0001; // Floating point comparison
                    break;
                case "<>":
                case "!=":
                    result = Math.abs(aggValue - compareValue) >= 0.0001;
                    break;
                default:
                    result = true;
            }
            
            System.out.println("    Comparison: " + aggValue + " " + operator + " " + compareValue + " = " + result);
            return result;
            
        } catch (Exception e) {
            System.err.println("  ⚠️  Error evaluating HAVING: " + e.getMessage());
            e.printStackTrace();
            return true; // Don't filter on error
        }
    }
    
    /**
     * Find aggregate value in row by matching aggregate function
     */
    private Object findAggregateValue(Map<String, Object> row, String aggregateExpr, List<AggregateFunction> aggregates) {
        // Try to match by aggregate alias
        for (AggregateFunction agg : aggregates) {
            String aggStr = agg.getType() + "(" + agg.getColumn() + ")";
            if (aggregateExpr.equalsIgnoreCase(aggStr) || aggregateExpr.equalsIgnoreCase(agg.getAlias())) {
                Object value = row.get(agg.getAlias());
                if (value != null) {
                    return value;
                }
            }
        }
        
        // Try direct column name match
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(aggregateExpr)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
}

