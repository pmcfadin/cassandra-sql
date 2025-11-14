package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Processes query results by applying ORDER BY and LIMIT clauses
 */
@Component
public class ResultProcessor {
    
    /**
     * Apply ORDER BY and LIMIT to query results
     */
    public QueryResponse process(QueryResponse response, ParsedQuery query) {
        if (response == null || response.getRows() == null) {
            return response;
        }
        
        List<Map<String, Object>> rows = new ArrayList<>(response.getRows());
        
        // Apply ORDER BY
        if (query.hasOrderBy()) {
            rows = applyOrderBy(rows, query.getOrderBy());
        }
        
        // Apply LIMIT/OFFSET
        if (query.hasLimit()) {
            rows = applyLimit(rows, query.getLimit());
        }
        
        return new QueryResponse(rows, response.getColumns());
    }
    
    /**
     * Sort rows according to ORDER BY clause
     */
    private List<Map<String, Object>> applyOrderBy(List<Map<String, Object>> rows, OrderByClause orderBy) {
        if (rows.isEmpty()) {
            return rows;
        }
        
        System.out.println("[ORDER BY] Sorting " + rows.size() + " rows by " + orderBy);
        
        List<Map<String, Object>> sortedRows = new ArrayList<>(rows);
        
        sortedRows.sort((row1, row2) -> {
            for (OrderByClause.OrderByItem item : orderBy.getItems()) {
                Object val1 = findColumnValue(row1, item.getColumn());
                Object val2 = findColumnValue(row2, item.getColumn());
                
                int comparison = compareValues(val1, val2);
                
                if (comparison != 0) {
                    return item.getDirection() == OrderByClause.Direction.DESC ? -comparison : comparison;
                }
            }
            return 0;
        });
        
        System.out.println("[ORDER BY] Sorted successfully");
        return sortedRows;
    }
    
    /**
     * Apply LIMIT and OFFSET to rows
     */
    private List<Map<String, Object>> applyLimit(List<Map<String, Object>> rows, LimitClause limit) {
        int offset = limit.getOffset();
        int fetchLimit = limit.getLimit();
        
        System.out.println("[LIMIT] Applying LIMIT " + fetchLimit + " OFFSET " + offset + " to " + rows.size() + " rows");
        
        if (offset >= rows.size()) {
            return Collections.emptyList();
        }
        
        int endIndex = Math.min(offset + fetchLimit, rows.size());
        List<Map<String, Object>> result = rows.subList(offset, endIndex);
        
        System.out.println("[LIMIT] Returned " + result.size() + " rows");
        return new ArrayList<>(result);
    }
    
    /**
     * Find column value in row (case-insensitive, handles table prefixes)
     */
    private Object findColumnValue(Map<String, Object> row, String column) {
        // Strip table prefix if present (e.g., "users.name" -> "name")
        String columnName = column;
        if (column.contains(".")) {
            String[] parts = column.split("\\.");
            columnName = parts[parts.length - 1];
        }
        
        // Try exact match first
        Object value = row.get(columnName);
        if (value != null) {
            return value;
        }
        
        // Try case-insensitive match
        String columnLower = columnName.toLowerCase();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().toLowerCase().equals(columnLower)) {
                return entry.getValue();
            }
        }
        
        // Try with table prefix
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key.toLowerCase().endsWith("." + columnLower)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Compare two values for sorting
     */
    private int compareValues(Object v1, Object v2) {
        // Handle nulls
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        
        // Try numeric comparison
        try {
            double d1 = Double.parseDouble(v1.toString());
            double d2 = Double.parseDouble(v2.toString());
            return Double.compare(d1, d2);
        } catch (NumberFormatException e) {
            // Fall back to string comparison
            return v1.toString().compareTo(v2.toString());
        }
    }
}







