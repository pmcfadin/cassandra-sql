package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import com.geico.poc.cassandrasql.dto.QueryResponse;

import java.util.*;

/**
 * Handles SELECT literals by extracting them from queries and injecting them into results.
 * 
 * Cassandra CQL does not support selecting literals (e.g., SELECT 1 FROM table).
 * This class works around that limitation by:
 * 1. Parsing the SELECT clause to find literals
 * 2. Removing literals from the CQL sent to Cassandra
 * 3. Adding literal columns to the result set after fetching
 */
public class LiteralHandler {
    
    /**
     * Information about a literal in a SELECT clause
     */
    public static class LiteralInfo {
        private final int position;        // Position in SELECT list (0-based)
        private final String value;        // Literal value (e.g., "1", "'test'", "true")
        private final String alias;        // Column alias (e.g., "col_1")
        private final Object typedValue;   // Typed value for result set
        
        public LiteralInfo(int position, String value, String alias, Object typedValue) {
            this.position = position;
            this.value = value;
            this.alias = alias;
            this.typedValue = typedValue;
        }
        
        public int getPosition() { return position; }
        public String getValue() { return value; }
        public String getAlias() { return alias; }
        public Object getTypedValue() { return typedValue; }
    }
    
    /**
     * Result of parsing a SELECT query for literals
     */
    public static class ParseResult {
        private final String sqlWithoutLiterals;  // SQL with literals removed
        private final List<LiteralInfo> literals;  // Extracted literals
        private final boolean hasLiterals;
        
        public ParseResult(String sqlWithoutLiterals, List<LiteralInfo> literals) {
            this.sqlWithoutLiterals = sqlWithoutLiterals;
            this.literals = literals;
            this.hasLiterals = !literals.isEmpty();
        }
        
        public String getSqlWithoutLiterals() { return sqlWithoutLiterals; }
        public List<LiteralInfo> getLiterals() { return literals; }
        public boolean hasLiterals() { return hasLiterals; }
    }
    
    /**
     * Parse SELECT query and extract literals
     */
    public static ParseResult extractLiterals(String sql) {
        try {
            SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            if (!(sqlNode instanceof SqlSelect)) {
                return new ParseResult(sql, Collections.emptyList());
            }
            
            SqlSelect select = (SqlSelect) sqlNode;
            SqlNodeList selectList = select.getSelectList();
            
            List<LiteralInfo> literals = new ArrayList<>();
            List<SqlNode> nonLiteralNodes = new ArrayList<>();
            int literalCounter = 1;
            
            // Iterate through SELECT items
            for (int i = 0; i < selectList.size(); i++) {
                SqlNode node = selectList.get(i);
                
                if (isLiteral(node)) {
                    // Extract literal
                    String value = node.toString();
                    String alias = "col_" + literalCounter;
                    Object typedValue = parseTypedValue(value);
                    
                    literals.add(new LiteralInfo(i, value, alias, typedValue));
                    literalCounter++;
                } else {
                    // Keep non-literal
                    nonLiteralNodes.add(node);
                }
            }
            
            if (literals.isEmpty()) {
                return new ParseResult(sql, Collections.emptyList());
            }
            
            // Rebuild SQL without literals
            String sqlWithoutLiterals = rebuildSqlWithoutLiterals(select, nonLiteralNodes);
            
            return new ParseResult(sqlWithoutLiterals, literals);
            
        } catch (Exception e) {
            System.err.println("[LiteralHandler] Error parsing SQL: " + e.getMessage());
            return new ParseResult(sql, Collections.emptyList());
        }
    }
    
    /**
     * Check if a SQL node is a literal
     */
    private static boolean isLiteral(SqlNode node) {
        if (node instanceof SqlLiteral) {
            return true;
        }
        
        // Check for CAST(literal AS type) - we still consider this a literal
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getOperator().getName().equalsIgnoreCase("CAST")) {
                if (call.getOperandList().size() > 0) {
                    return isLiteral(call.getOperandList().get(0));
                }
            }
        }
        
        return false;
    }
    
    /**
     * Parse literal value into typed Java object
     */
    private static Object parseTypedValue(String value) {
        String trimmed = value.trim();
        String upper = trimmed.toUpperCase();
        
        // Boolean
        if (upper.equals("TRUE")) {
            return Boolean.TRUE;
        }
        if (upper.equals("FALSE")) {
            return Boolean.FALSE;
        }
        
        // NULL
        if (upper.equals("NULL")) {
            return null;
        }
        
        // Integer
        if (trimmed.matches("-?\\d+")) {
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                try {
                    return Long.parseLong(trimmed);
                } catch (NumberFormatException e2) {
                    return trimmed;
                }
            }
        }
        
        // Decimal
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return trimmed;
            }
        }
        
        // String (remove quotes)
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        
        // Default: return as string
        return trimmed;
    }
    
    /**
     * Rebuild SQL SELECT without literals
     */
    private static String rebuildSqlWithoutLiterals(SqlSelect select, List<SqlNode> nonLiteralNodes) {
        if (nonLiteralNodes.isEmpty()) {
            // All items were literals - need to select at least something
            // Use a dummy column or COUNT(*)
            return "SELECT COUNT(*) AS dummy_count FROM " + select.getFrom().toString();
        }
        
        // Build SELECT list
        StringBuilder selectList = new StringBuilder();
        for (int i = 0; i < nonLiteralNodes.size(); i++) {
            if (i > 0) selectList.append(", ");
            selectList.append(nonLiteralNodes.get(i).toString());
        }
        
        // Build full query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectList);
        
        if (select.getFrom() != null) {
            sql.append(" FROM ").append(select.getFrom().toString());
        }
        
        if (select.getWhere() != null) {
            sql.append(" WHERE ").append(select.getWhere().toString());
        }
        
        if (select.getGroup() != null) {
            sql.append(" GROUP BY ").append(select.getGroup().toString());
        }
        
        if (select.getHaving() != null) {
            sql.append(" HAVING ").append(select.getHaving().toString());
        }
        
        if (select.getOrderList() != null && select.getOrderList().size() > 0) {
            sql.append(" ORDER BY ").append(select.getOrderList().toString());
        }
        
        if (select.getFetch() != null) {
            sql.append(" LIMIT ").append(select.getFetch().toString());
        }
        
        return sql.toString();
    }
    
    /**
     * Inject literals into query results
     */
    public static QueryResponse injectLiterals(QueryResponse originalResponse, List<LiteralInfo> literals) {
        if (literals.isEmpty()) {
            return originalResponse;
        }
        
        List<String> originalColumns = originalResponse.getColumns();
        List<Map<String, Object>> originalRows = originalResponse.getRows();
        
        // Guard against null columns or rows
        if (originalColumns == null || originalRows == null) {
            System.err.println("[LiteralHandler] Cannot inject literals: originalColumns or originalRows is null");
            return originalResponse;
        }
        
        // Build new column list with literals inserted at correct positions
        List<String> newColumns = new ArrayList<>();
        Map<Integer, LiteralInfo> literalsByPosition = new HashMap<>();
        
        for (LiteralInfo literal : literals) {
            literalsByPosition.put(literal.getPosition(), literal);
        }
        
        int originalColIndex = 0;
        int totalColumns = originalColumns.size() + literals.size();
        
        for (int i = 0; i < totalColumns; i++) {
            if (literalsByPosition.containsKey(i)) {
                // This position is a literal
                newColumns.add(literalsByPosition.get(i).getAlias());
            } else {
                // This position is an original column
                if (originalColIndex < originalColumns.size()) {
                    String colName = originalColumns.get(originalColIndex);
                    // Guard against null column names
                    if (colName != null) {
                        newColumns.add(colName);
                    }
                    originalColIndex++;
                }
            }
        }
        
        // Build new rows with literals injected
        List<Map<String, Object>> newRows = new ArrayList<>();
        
        for (Map<String, Object> originalRow : originalRows) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            
            originalColIndex = 0;
            for (int i = 0; i < totalColumns; i++) {
                if (literalsByPosition.containsKey(i)) {
                    // Inject literal value
                    LiteralInfo literal = literalsByPosition.get(i);
                    newRow.put(literal.getAlias(), literal.getTypedValue());
                } else {
                    // Copy original column value
                    if (originalColIndex < originalColumns.size()) {
                        String colName = originalColumns.get(originalColIndex);
                        // Guard against null column names
                        if (colName != null) {
                            newRow.put(colName, originalRow.get(colName));
                        }
                        originalColIndex++;
                    }
                }
            }
            
            newRows.add(newRow);
        }
        
        return new QueryResponse(newRows, newColumns);
    }
}



