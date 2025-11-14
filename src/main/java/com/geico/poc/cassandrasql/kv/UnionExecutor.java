package com.geico.poc.cassandrasql.kv;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes UNION and UNION ALL queries in KV mode.
 * 
 * UNION ALL: Concatenates results from multiple SELECT queries
 * UNION: Concatenates and removes duplicates
 */
@Component
public class UnionExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(UnionExecutor.class);
    
    @Autowired
    private KvQueryExecutor kvQueryExecutor;
    
    @Autowired
    @org.springframework.context.annotation.Lazy
    private QueryService queryService;
    
    /**
     * Execute a UNION or UNION ALL query
     */
    public QueryResponse execute(String sql) {
        try {
            log.debug("Executing UNION query: {}", sql);
            
            // Parse the UNION query
            SqlParser parser = SqlParser.create(sql);
            SqlNode sqlNode = parser.parseStmt();
            
            // Handle ORDER BY wrapping
            if (sqlNode instanceof SqlOrderBy) {
                SqlOrderBy orderBy = (SqlOrderBy) sqlNode;
                sqlNode = orderBy.query;
            }
            
            if (!(sqlNode instanceof SqlBasicCall)) {
                return QueryResponse.error("Expected UNION query, got: " + sqlNode.getClass().getSimpleName());
            }
            
            SqlBasicCall unionCall = (SqlBasicCall) sqlNode;
            String operatorName = unionCall.getOperator().getName().toUpperCase();
            if (!operatorName.contains("UNION")) {
                return QueryResponse.error("Expected UNION operator, got: " + unionCall.getOperator().getName());
            }
            
            // Check if it's UNION ALL or UNION (with duplicate removal)
            boolean isUnionAll = operatorName.contains("ALL");
            
            // Extract all SELECT statements from the UNION tree
            List<SqlSelect> selects = extractSelects(unionCall);
            log.debug("Found {} SELECT statements in UNION", selects.size());
            
            // Execute each SELECT and collect results
            List<Map<String, Object>> allRows = new ArrayList<>();
            List<String> columns = null;
            
            for (int i = 0; i < selects.size(); i++) {
                SqlSelect select = selects.get(i);
                
                log.info("🔍 UNION: Processing SELECT {} of {}, SqlNode type: {}", i + 1, selects.size(), select.getClass().getSimpleName());
                
                // Convert SqlSelect to SQL string
                String selectSql = select.toSqlString(org.apache.calcite.sql.SqlDialect.DatabaseProduct.UNKNOWN.getDialect()).getSql();
                
                log.info("🔍 UNION: Raw generated SQL (before cleanup): {}", selectSql);
                
                // Remove backticks that Calcite adds around identifiers
                selectSql = selectSql.replace("`", "");
                
                log.info("🔍 UNION: Executing SELECT {} of {}", i + 1, selects.size());
                log.info("🔍 UNION: Generated SQL (after cleanup): {}", selectSql);
                
                // Execute the SELECT directly through KvQueryExecutor to avoid UNION routing
                // Parse the SELECT SQL first
                QueryResponse response;
                try {
                    com.geico.poc.cassandrasql.CalciteParser calciteParser = new com.geico.poc.cassandrasql.CalciteParser();
                    com.geico.poc.cassandrasql.ParsedQuery parsedQuery = calciteParser.parse(selectSql);
                    response = kvQueryExecutor.execute(parsedQuery);
                } catch (Exception e) {
                    log.error("Failed to execute UNION sub-SELECT", e);
                    response = QueryResponse.error("Sub-SELECT failed: " + e.getMessage());
                }
                
                log.info("🔍 UNION: Response error: {}", response.getError());
                log.info("🔍 UNION: Response rows: {}", response.getRows() != null ? response.getRows().size() : 0);
                log.info("🔍 UNION: Response columns: {}", response.getColumns());
                if (response.getRows() != null && !response.getRows().isEmpty()) {
                    log.info("🔍 UNION: First row data: {}", response.getRows().get(0));
                }
                
                if (response.getError() != null) {
                    return QueryResponse.error("UNION SELECT " + (i + 1) + " failed: " + response.getError());
                }
                
                // Get columns from first query
                if (columns == null) {
                    columns = response.getColumns();
                    log.debug("Columns from first SELECT: {}", columns);
                }
                
                // Add rows to result, normalizing column names to match first SELECT
                if (response.getRows() != null) {
                    if (columns != null && response.getColumns() != null && 
                        !columns.equals(response.getColumns())) {
                        // Column names differ - need to remap
                        log.debug("Column names differ, remapping from {} to {}", response.getColumns(), columns);
                        List<Map<String, Object>> remappedRows = remapColumns(response.getRows(), response.getColumns(), columns);
                        allRows.addAll(remappedRows);
                    } else {
                        allRows.addAll(response.getRows());
                    }
                }
            }
            
            // Remove duplicates if UNION (not UNION ALL)
            if (!isUnionAll) {
                int beforeCount = allRows.size();
                log.debug("Removing duplicates (UNION without ALL), before: {} rows", beforeCount);
                allRows = removeDuplicates(allRows, columns);
                log.debug("After duplicate removal: {} rows (removed {})", allRows.size(), beforeCount - allRows.size());
            }
            
            log.debug("UNION complete: {} total rows", allRows.size());
            return new QueryResponse(allRows, columns != null ? columns : Collections.emptyList());
            
        } catch (Exception e) {
            log.error("UNION execution failed", e);
            return QueryResponse.error("UNION failed: " + e.getMessage());
        }
    }
    
    /**
     * Extract all SELECT statements from a UNION tree
     */
    private List<SqlSelect> extractSelects(SqlBasicCall unionCall) {
        List<SqlSelect> selects = new ArrayList<>();
        extractSelectsRecursive(unionCall, selects);
        return selects;
    }
    
    /**
     * Recursively extract SELECT statements from nested UNION calls
     */
    private void extractSelectsRecursive(SqlNode node, List<SqlSelect> selects) {
        log.debug("extractSelectsRecursive: node type={}, class={}", 
            node != null ? node.getKind() : "null", 
            node != null ? node.getClass().getSimpleName() : "null");
        
        if (node instanceof SqlSelect) {
            log.debug("  -> Adding SqlSelect to list");
            selects.add((SqlSelect) node);
        } else if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            String opName = call.getOperator().getName().toUpperCase();
            log.debug("  -> SqlBasicCall operator: {}", opName);
            if (opName.contains("UNION")) {
                // UNION has two operands: left and right
                SqlNode left = call.operand(0);
                SqlNode right = call.operand(1);
                
                log.debug("  -> Recursing into UNION left and right operands");
                extractSelectsRecursive(left, selects);
                extractSelectsRecursive(right, selects);
            }
        }
    }
    
    /**
     * Remap column names from source columns to target columns
     * Maps values by position (first source column -> first target column, etc.)
     */
    private List<Map<String, Object>> remapColumns(List<Map<String, Object>> rows, 
                                                     List<String> sourceColumns, 
                                                     List<String> targetColumns) {
        List<Map<String, Object>> remappedRows = new ArrayList<>();
        
        for (Map<String, Object> row : rows) {
            Map<String, Object> remappedRow = new LinkedHashMap<>();
            
            // Map by position: sourceColumns[i] -> targetColumns[i]
            for (int i = 0; i < Math.min(sourceColumns.size(), targetColumns.size()); i++) {
                String sourceCol = sourceColumns.get(i);
                String targetCol = targetColumns.get(i);
                Object value = row.get(sourceCol);
                remappedRow.put(targetCol, value);
            }
            
            remappedRows.add(remappedRow);
        }
        
        return remappedRows;
    }
    
    /**
     * Remove duplicate rows from the result set
     * Only considers columns that are in the SELECT list (not extra columns like PK)
     */
    private List<Map<String, Object>> removeDuplicates(List<Map<String, Object>> rows, List<String> selectedColumns) {
        // Use LinkedHashSet to preserve order while removing duplicates
        // Convert each row to a canonical string representation for comparison
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        
        for (Map<String, Object> row : rows) {
            String rowKey = createRowKey(row, selectedColumns);
            if (seen.add(rowKey)) {
                unique.add(row);
            }
        }
        
        return unique;
    }
    
    /**
     * Create a canonical string representation of a row for duplicate detection
     * Only includes columns that were selected (not extra columns like PK)
     */
    private String createRowKey(Map<String, Object> row, List<String> selectedColumns) {
        // Sort selected columns to ensure consistent ordering
        List<String> sortedKeys = new ArrayList<>(selectedColumns);
        Collections.sort(sortedKeys);
        
        StringBuilder key = new StringBuilder();
        for (String colName : sortedKeys) {
            // Find the column in the row (case-insensitive)
            Object value = null;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(colName)) {
                    value = entry.getValue();
                    break;
                }
            }
            
            key.append(colName.toLowerCase()).append("=");
            if (value == null) {
                key.append("NULL");
            } else {
                key.append(value.toString());
            }
            key.append(";");
        }
        
        String result = key.toString();
        log.trace("Row key: {} for row: {} (selected columns: {})", result, row, selectedColumns);
        return result;
    }
}

