package com.geico.poc.cassandrasql.window;

import com.geico.poc.cassandrasql.dto.QueryResponse;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executor for window functions.
 * 
 * Processes queries with OVER clauses to compute window functions like:
 * - ROW_NUMBER(), RANK(), DENSE_RANK()
 * - SUM() OVER, AVG() OVER, COUNT() OVER, MIN() OVER, MAX() OVER
 * - LAG(), LEAD(), FIRST_VALUE(), LAST_VALUE()
 */
public class WindowFunctionExecutor {
    
    /**
     * Apply window functions to query results
     */
    public QueryResponse applyWindowFunctions(QueryResponse baseResults, List<WindowSpec> windowSpecs) {
        if (windowSpecs == null || windowSpecs.isEmpty()) {
            return baseResults;
        }
        
        System.out.println("🪟 Applying " + windowSpecs.size() + " window function(s)");
        
        // Get base rows
        List<Map<String, Object>> rows = new ArrayList<>(baseResults.getRows());
        List<String> columns = new ArrayList<>(baseResults.getColumns());
        
        // Apply each window function
        for (WindowSpec spec : windowSpecs) {
            System.out.println("   Processing: " + spec);
            applyWindowFunction(rows, spec);
            
            // Add window function column to column list
            if (spec.getAlias() != null && !columns.contains(spec.getAlias())) {
                columns.add(spec.getAlias());
            }
        }
        
        return new QueryResponse(rows, columns);
    }
    
    /**
     * Apply a single window function
     */
    private void applyWindowFunction(List<Map<String, Object>> rows, WindowSpec spec) {
        // Step 1: Partition rows
        Map<PartitionKey, List<Map<String, Object>>> partitions = partitionRows(rows, spec.getPartitionByColumns());
        
        System.out.println("   Created " + partitions.size() + " partition(s)");
        
        // Step 2: Process each partition
        for (List<Map<String, Object>> partition : partitions.values()) {
            // Sort partition by ORDER BY columns
            sortPartition(partition, spec.getOrderByColumns());
            
            // Apply window function to partition
            applyFunctionToPartition(partition, spec);
        }
    }
    
    /**
     * Partition rows by PARTITION BY columns
     */
    private Map<PartitionKey, List<Map<String, Object>>> partitionRows(
            List<Map<String, Object>> rows, List<String> partitionByColumns) {
        
        if (partitionByColumns == null || partitionByColumns.isEmpty()) {
            // No PARTITION BY - all rows in one partition
            PartitionKey key = new PartitionKey(Collections.emptyList());
            Map<PartitionKey, List<Map<String, Object>>> result = new HashMap<>();
            result.put(key, rows);
            return result;
        }
        
        // Group by partition key
        Map<PartitionKey, List<Map<String, Object>>> partitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> keyValues = new ArrayList<>();
            for (String column : partitionByColumns) {
                keyValues.add(row.get(column));
            }
            PartitionKey key = new PartitionKey(keyValues);
            partitions.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        
        return partitions;
    }
    
    /**
     * Sort partition by ORDER BY columns
     */
    private void sortPartition(List<Map<String, Object>> partition, List<WindowSpec.OrderByColumn> orderByColumns) {
        if (orderByColumns == null || orderByColumns.isEmpty()) {
            return;
        }
        
        partition.sort((row1, row2) -> {
            for (WindowSpec.OrderByColumn orderBy : orderByColumns) {
                Object val1 = row1.get(orderBy.getColumn());
                Object val2 = row2.get(orderBy.getColumn());
                
                int cmp = compareValues(val1, val2);
                if (cmp != 0) {
                    return orderBy.isAscending() ? cmp : -cmp;
                }
            }
            return 0;
        });
    }
    
    /**
     * Apply window function to a partition
     */
    private void applyFunctionToPartition(List<Map<String, Object>> partition, WindowSpec spec) {
        String functionName = spec.getFunctionName().toUpperCase();
        String alias = spec.getAlias() != null ? spec.getAlias() : functionName.toLowerCase();
        
        switch (functionName) {
            case "ROW_NUMBER":
                applyRowNumber(partition, alias);
                break;
            case "RANK":
                applyRank(partition, spec, alias);
                break;
            case "DENSE_RANK":
                applyDenseRank(partition, spec, alias);
                break;
            case "SUM":
                applySum(partition, spec, alias);
                break;
            case "AVG":
                applyAvg(partition, spec, alias);
                break;
            case "COUNT":
                applyCount(partition, spec, alias);
                break;
            case "MIN":
                applyMin(partition, spec, alias);
                break;
            case "MAX":
                applyMax(partition, spec, alias);
                break;
            case "LAG":
                applyLag(partition, spec, alias);
                break;
            case "LEAD":
                applyLead(partition, spec, alias);
                break;
            case "FIRST_VALUE":
                applyFirstValue(partition, spec, alias);
                break;
            case "LAST_VALUE":
                applyLastValue(partition, spec, alias);
                break;
            default:
                throw new UnsupportedOperationException("Window function not supported: " + functionName);
        }
    }
    
    /**
     * ROW_NUMBER() - Sequential numbering within partition
     */
    private void applyRowNumber(List<Map<String, Object>> partition, String alias) {
        int rowNumber = 1;
        for (Map<String, Object> row : partition) {
            row.put(alias, rowNumber++);
        }
    }
    
    /**
     * RANK() - Ranking with gaps for ties
     */
    private void applyRank(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getOrderByColumns() == null || spec.getOrderByColumns().isEmpty()) {
            // No ORDER BY - all rows have rank 1
            for (Map<String, Object> row : partition) {
                row.put(alias, 1);
            }
            return;
        }
        
        int rank = 1;
        List<Object> prevValues = null;
        
        for (int i = 0; i < partition.size(); i++) {
            Map<String, Object> row = partition.get(i);
            
            // Get current ORDER BY values
            List<Object> currentValues = spec.getOrderByColumns().stream()
                    .map(col -> row.get(col.getColumn()))
                    .collect(Collectors.toList());
            
            // Check if values changed
            if (prevValues != null && !valuesEqual(prevValues, currentValues)) {
                rank = i + 1;
            }
            
            row.put(alias, rank);
            prevValues = currentValues;
        }
    }
    
    /**
     * DENSE_RANK() - Ranking without gaps for ties
     */
    private void applyDenseRank(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getOrderByColumns() == null || spec.getOrderByColumns().isEmpty()) {
            // No ORDER BY - all rows have rank 1
            for (Map<String, Object> row : partition) {
                row.put(alias, 1);
            }
            return;
        }
        
        int rank = 1;
        List<Object> prevValues = null;
        
        for (Map<String, Object> row : partition) {
            // Get current ORDER BY values
            List<Object> currentValues = spec.getOrderByColumns().stream()
                    .map(col -> row.get(col.getColumn()))
                    .collect(Collectors.toList());
            
            // Check if values changed
            if (prevValues != null && !valuesEqual(prevValues, currentValues)) {
                rank++;
            }
            
            row.put(alias, rank);
            prevValues = currentValues;
        }
    }
    
    /**
     * SUM() OVER - Running sum
     */
    private void applySum(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("SUM() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        double sum = 0;
        
        for (Map<String, Object> row : partition) {
            Object value = row.get(column);
            if (value != null) {
                sum += toDouble(value);
            }
            row.put(alias, sum);
        }
    }
    
    /**
     * AVG() OVER - Running average
     */
    private void applyAvg(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("AVG() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        double sum = 0;
        int count = 0;
        
        for (Map<String, Object> row : partition) {
            Object value = row.get(column);
            if (value != null) {
                sum += toDouble(value);
                count++;
            }
            row.put(alias, count > 0 ? sum / count : null);
        }
    }
    
    /**
     * COUNT() OVER - Running count
     */
    private void applyCount(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        int count = 0;
        
        for (Map<String, Object> row : partition) {
            count++;
            row.put(alias, count);
        }
    }
    
    /**
     * MIN() OVER - Running minimum
     */
    private void applyMin(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("MIN() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        Object min = null;
        
        for (Map<String, Object> row : partition) {
            Object value = row.get(column);
            if (value != null) {
                if (min == null || compareValues(value, min) < 0) {
                    min = value;
                }
            }
            row.put(alias, min);
        }
    }
    
    /**
     * MAX() OVER - Running maximum
     */
    private void applyMax(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("MAX() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        Object max = null;
        
        for (Map<String, Object> row : partition) {
            Object value = row.get(column);
            if (value != null) {
                if (max == null || compareValues(value, max) > 0) {
                    max = value;
                }
            }
            row.put(alias, max);
        }
    }
    
    /**
     * LAG() - Access previous row
     */
    private void applyLag(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("LAG() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        int offset = 1;
        if (spec.getFunctionArgs().size() > 1) {
            offset = Integer.parseInt(spec.getFunctionArgs().get(1));
        }
        
        for (int i = 0; i < partition.size(); i++) {
            Map<String, Object> row = partition.get(i);
            if (i >= offset) {
                row.put(alias, partition.get(i - offset).get(column));
            } else {
                row.put(alias, null);
            }
        }
    }
    
    /**
     * LEAD() - Access next row
     */
    private void applyLead(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("LEAD() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        int offset = 1;
        if (spec.getFunctionArgs().size() > 1) {
            offset = Integer.parseInt(spec.getFunctionArgs().get(1));
        }
        
        for (int i = 0; i < partition.size(); i++) {
            Map<String, Object> row = partition.get(i);
            if (i + offset < partition.size()) {
                row.put(alias, partition.get(i + offset).get(column));
            } else {
                row.put(alias, null);
            }
        }
    }
    
    /**
     * FIRST_VALUE() - First value in window
     */
    private void applyFirstValue(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("FIRST_VALUE() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        Object firstValue = partition.isEmpty() ? null : partition.get(0).get(column);
        
        for (Map<String, Object> row : partition) {
            row.put(alias, firstValue);
        }
    }
    
    /**
     * LAST_VALUE() - Last value in window
     */
    private void applyLastValue(List<Map<String, Object>> partition, WindowSpec spec, String alias) {
        if (spec.getFunctionArgs() == null || spec.getFunctionArgs().isEmpty()) {
            throw new IllegalArgumentException("LAST_VALUE() requires an argument");
        }
        
        String column = spec.getFunctionArgs().get(0);
        
        // For LAST_VALUE, we need to consider the window frame
        // Default frame is RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        // So LAST_VALUE should return the current row's value
        for (int i = 0; i < partition.size(); i++) {
            Map<String, Object> row = partition.get(i);
            row.put(alias, row.get(column));
        }
    }
    
    /**
     * Compare two values
     */
    @SuppressWarnings("unchecked")
    private int compareValues(Object v1, Object v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        
        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            return ((Comparable) v1).compareTo(v2);
        }
        
        return v1.toString().compareTo(v2.toString());
    }
    
    /**
     * Check if two value lists are equal
     */
    private boolean valuesEqual(List<Object> v1, List<Object> v2) {
        if (v1.size() != v2.size()) return false;
        for (int i = 0; i < v1.size(); i++) {
            if (!Objects.equals(v1.get(i), v2.get(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Convert value to double
     */
    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }
    
    /**
     * Partition key for grouping rows
     */
    private static class PartitionKey {
        private final List<Object> values;
        
        public PartitionKey(List<Object> values) {
            this.values = values;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PartitionKey that = (PartitionKey) o;
            return Objects.equals(values, that.values);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(values);
        }
        
        @Override
        public String toString() {
            return values.toString();
        }
    }
}



