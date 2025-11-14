package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.*;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Executes queries with subqueries
 * Supports: IN, EXISTS, scalar subqueries, and subqueries in FROM clause
 */
@Component
public class SubqueryExecutor {
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private CalciteParser calciteParser;
    
    @Autowired
    private CorrelatedSubqueryExecutor correlatedExecutor;
    
    @Autowired
    @Lazy  // Break circular dependency: SubqueryExecutor -> StorageBackend -> KvQueryExecutor -> SubqueryExecutor
    private com.geico.poc.cassandrasql.storage.StorageBackend storageBackend;
    
    /**
     * Execute a query with subqueries
     * Subqueries are executed first, then the outer query is rewritten
     */
    public QueryResponse execute(ParsedQuery outerQuery) throws Exception {
        System.out.println("Executing query with subqueries");
        
        // Parse the SQL to find subqueries
        String sql = outerQuery.getRawSql();
        
        // Check if any subqueries are correlated
        if (hasCorrelatedSubquery(sql)) {
            System.out.println("Detected correlated subquery - routing to CorrelatedSubqueryExecutor");
            return correlatedExecutor.execute(outerQuery);
        }
        
        SubqueryInfo subqueryInfo = analyzeSubqueries(sql);
        
        if (!subqueryInfo.hasSubqueries()) {
            // No subqueries, execute normally via storage backend
            return storageBackend.execute(outerQuery);
        }
        
        // FROM subqueries are now handled by KvQueryExecutor before routing here
        // If we detect FROM subqueries at this point, they must be nested inside other subqueries
        // Skip them for now - they'll be handled when the outer subquery is executed
        boolean hasFromSubquery = subqueryInfo.getSubqueries().stream()
            .anyMatch(sq -> sq.getType() == SubqueryType.FROM);
        
        if (hasFromSubquery) {
            // FROM subqueries nested in other subqueries (IN, EXISTS, etc.) are not yet supported
            // Skip them and let the outer query handle them
            System.out.println("⚠️  FROM subquery nested in other subquery detected - skipping for now");
            // Remove FROM subqueries from the list and continue with other subqueries
            subqueryInfo.getSubqueries().removeIf(sq -> sq.getType() == SubqueryType.FROM);
            
            // If no other subqueries remain, execute normally
            if (!subqueryInfo.hasSubqueries()) {
                return storageBackend.execute(outerQuery);
            }
        }
        
        System.out.println("Found " + subqueryInfo.getSubqueryCount() + " subquery(ies)");
        
        // Execute subqueries and rewrite the outer query
        String rewrittenSql = executeAndRewriteSubqueries(sql, subqueryInfo);
        
        System.out.println("Rewritten SQL: " + rewrittenSql);
        
        // Execute the rewritten query via storage backend
        ParsedQuery rewrittenQuery = calciteParser.parse(rewrittenSql);
        return storageBackend.execute(rewrittenQuery);
    }
    
    /**
     * Check if the query has any correlated subqueries
     */
    private boolean hasCorrelatedSubquery(String sql) {
        // Find all subqueries
        SubqueryInfo info = analyzeSubqueries(sql);
        
        // Check if any are correlated
        for (Subquery subquery : info.getSubqueries()) {
            if (isCorrelated(subquery.getSql())) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Analyze SQL to find subqueries
     */
    private SubqueryInfo analyzeSubqueries(String sql) {
        SubqueryInfo info = new SubqueryInfo();
        
        // Find all subqueries (enclosed in parentheses with SELECT)
        int depth = 0;
        int subqueryStart = -1;
        StringBuilder currentSubquery = new StringBuilder();
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            
            if (c == '(') {
                if (depth == 0) {
                    // Check if this is a subquery (starts with SELECT)
                    int selectPos = sql.indexOf("SELECT", i + 1);
                    int closePos = findMatchingParen(sql, i);
                    
                    if (selectPos > i && selectPos < closePos) {
                        // This is a subquery
                        subqueryStart = i;
                    }
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && subqueryStart >= 0) {
                    // End of subquery
                    String subquery = sql.substring(subqueryStart + 1, i);
                    
                    // Determine subquery type
                    SubqueryType type = determineSubqueryType(sql, subqueryStart);
                    
                    info.addSubquery(subqueryStart, i + 1, subquery, type);
                    subqueryStart = -1;
                }
            }
        }
        
        return info;
    }
    
    /**
     * Determine the type of subquery based on context
     */
    private SubqueryType determineSubqueryType(String sql, int subqueryStart) {
        // Look backwards to find the keyword before the subquery
        String before = sql.substring(Math.max(0, subqueryStart - 20), subqueryStart).toUpperCase().trim();
        
        if (before.endsWith(" IN")) {
            return SubqueryType.IN;
        } else if (before.endsWith("EXISTS")) {
            return SubqueryType.EXISTS;
        } else if (before.endsWith("NOT EXISTS")) {
            return SubqueryType.NOT_EXISTS;
        } else if (before.contains("FROM") || before.contains("JOIN")) {
            return SubqueryType.FROM;
        } else {
            // Scalar subquery (returns single value)
            return SubqueryType.SCALAR;
        }
    }
    
    /**
     * Find matching closing parenthesis
     */
    private int findMatchingParen(String sql, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < sql.length(); i++) {
            if (sql.charAt(i) == '(') depth++;
            else if (sql.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
    
    /**
     * Execute subqueries and rewrite the outer query
     */
    private String executeAndRewriteSubqueries(String sql, SubqueryInfo info) throws Exception {
        String result = sql;
        
        // Execute subqueries in reverse order (innermost first)
        List<Subquery> subqueries = new ArrayList<>(info.getSubqueries());
        Collections.reverse(subqueries);
        
        for (Subquery subquery : subqueries) {
            String replacement = executeSubquery(subquery);
            
            // Replace the subquery with the result
            result = result.substring(0, subquery.getStartPos()) + 
                    replacement + 
                    result.substring(subquery.getEndPos());
        }
        
        return result;
    }
    
    /**
     * Execute a single subquery and return the replacement string
     */
    private String executeSubquery(Subquery subquery) throws Exception {
        System.out.println("Executing subquery (" + subquery.getType() + "): " + subquery.getSql());
        
        // Check if this is a correlated subquery (references outer query)
        if (isCorrelated(subquery.getSql())) {
            System.out.println("⚠️  Correlated subquery detected - routing to CorrelatedSubqueryExecutor");
            // Note: This will be handled at a higher level by checking the full query
            // For now, we still throw an error here as this method is for non-correlated subqueries
            throw new UnsupportedOperationException(
                "Correlated subquery detected in non-correlated execution path. " +
                "This should be handled by CorrelatedSubqueryExecutor."
            );
        }
        
        // Clean up the subquery SQL (remove table aliases if present)
        String cleanedSql = cleanSubquerySql(subquery.getSql());
        System.out.println("Cleaned subquery SQL: " + cleanedSql);
        
        // Parse and execute the subquery via storage backend (supports KV mode aggregations)
        ParsedQuery subqueryParsed = calciteParser.parse(cleanedSql);
        QueryResponse subqueryResult = storageBackend.execute(subqueryParsed);
        
        // Convert result based on subquery type
        switch (subquery.getType()) {
            case IN:
                return convertToInList(subqueryResult);
                
            case EXISTS:
            case NOT_EXISTS:
                return convertToExists(subqueryResult);
                
            case SCALAR:
                return convertToScalar(subqueryResult);
                
            case FROM:
                // FROM subqueries are handled by KvQueryExecutor.checkAndExecuteFromSubquery()
                // Delegate to storage backend which will route to the appropriate executor
                // The storage backend (KvQueryExecutor) has full FROM subquery support
                ParsedQuery fromSubqueryParsed = calciteParser.parse(subquery.getSql());
                QueryResponse fromSubqueryResult = storageBackend.execute(fromSubqueryParsed);
                
                if (fromSubqueryResult.getError() != null) {
                    throw new RuntimeException("FROM subquery execution failed: " + fromSubqueryResult.getError());
                }
                
                // For FROM subqueries, we return the result set
                // The outer query executor will handle materializing this as a derived table
                // This is a placeholder - actual FROM subquery handling happens in KvQueryExecutor
                return fromSubqueryResult.getRows().toString();
                
            default:
                throw new IllegalArgumentException("Unknown subquery type: " + subquery.getType());
        }
    }
    
    /**
     * Check if a subquery is correlated (references outer query tables)
     */
    private boolean isCorrelated(String subquery) {
        String upper = subquery.toUpperCase();
        
        // Look for patterns like "o.column" or "u.column" in WHERE clause
        // This is a simple heuristic - a proper implementation would parse the query
        
        // Check for qualified column references (table.column or alias.column)
        int wherePos = upper.indexOf("WHERE");
        if (wherePos > 0) {
            String whereClause = subquery.substring(wherePos);
            
            // Look for patterns like "word.word" that might be table references
            // But exclude the table in the FROM clause
            int fromPos = upper.indexOf("FROM");
            if (fromPos > 0) {
                String fromClause = subquery.substring(fromPos, wherePos);
                
                // Extract table alias from FROM clause (e.g., "orders o" -> "o")
                String[] fromParts = fromClause.split("\\s+");
                String subqueryTableAlias = null;
                if (fromParts.length >= 3) {
                    subqueryTableAlias = fromParts[2].toLowerCase();
                }
                
                // Check if WHERE clause references any other table aliases
                // Look for patterns like "x." where x is not the subquery's own table
                for (int i = 0; i < whereClause.length() - 1; i++) {
                    if (whereClause.charAt(i + 1) == '.') {
                        char before = whereClause.charAt(i);
                        if (Character.isLetter(before)) {
                            // Found a qualified reference
                            // Check if it's the subquery's own table
                            String alias = String.valueOf(before).toLowerCase();
                            if (subqueryTableAlias != null && !alias.equals(subqueryTableAlias)) {
                                // References a different table - this is correlated
                                return true;
                            }
                        }
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Clean subquery SQL by removing unnecessary syntax
     */
    private String cleanSubquerySql(String sql) {
        // For now, just return as-is
        // Future: handle table aliases, etc.
        return sql.trim();
    }
    
    /**
     * Convert subquery result to IN list
     * Example: (1, 2, 3)
     */
    private String convertToInList(QueryResponse result) {
        if (result.getRows().isEmpty()) {
            return "(NULL)";  // Empty IN list
        }
        
        // Get first column values
        String firstColumn = result.getColumns().get(0);
        List<String> values = new ArrayList<>();
        
        for (Map<String, Object> row : result.getRows()) {
            Object value = row.get(firstColumn);
            if (value == null) {
                values.add("NULL");
            } else if (value instanceof Number) {
                values.add(value.toString());
            } else {
                values.add("'" + value.toString().replace("'", "''") + "'");
            }
        }
        
        return "(" + String.join(", ", values) + ")";
    }
    
    /**
     * Convert subquery result to EXISTS boolean
     * Example: TRUE or FALSE
     */
    private String convertToExists(QueryResponse result) {
        // EXISTS returns true if subquery returns any rows
        return result.getRows().isEmpty() ? "FALSE" : "TRUE";
    }
    
    /**
     * Convert subquery result to scalar value
     * Example: 42 or 'value'
     */
    private String convertToScalar(QueryResponse result) {
        if (result.getRows().isEmpty()) {
            return "NULL";
        }
        
        if (result.getRows().size() > 1) {
            throw new IllegalArgumentException("Scalar subquery returned more than one row");
        }
        
        Map<String, Object> row = result.getRows().get(0);
        
        if (row.size() > 1) {
            throw new IllegalArgumentException("Scalar subquery returned more than one column");
        }
        
        Object value = row.values().iterator().next();
        
        if (value == null) {
            return "NULL";
        } else if (value instanceof Number) {
            return value.toString();
        } else {
            return "'" + value.toString().replace("'", "''") + "'";
        }
    }
    
    /**
     * Information about subqueries in a SQL statement
     */
    private static class SubqueryInfo {
        private final List<Subquery> subqueries = new ArrayList<>();
        
        public void addSubquery(int startPos, int endPos, String sql, SubqueryType type) {
            subqueries.add(new Subquery(startPos, endPos, sql, type));
        }
        
        public boolean hasSubqueries() {
            return !subqueries.isEmpty();
        }
        
        public int getSubqueryCount() {
            return subqueries.size();
        }
        
        public List<Subquery> getSubqueries() {
            return subqueries;
        }
    }
    
    /**
     * Represents a single subquery
     */
    private static class Subquery {
        private final int startPos;
        private final int endPos;
        private final String sql;
        private final SubqueryType type;
        
        public Subquery(int startPos, int endPos, String sql, SubqueryType type) {
            this.startPos = startPos;
            this.endPos = endPos;
            this.sql = sql;
            this.type = type;
        }
        
        public int getStartPos() {
            return startPos;
        }
        
        public int getEndPos() {
            return endPos;
        }
        
        public String getSql() {
            return sql;
        }
        
        public SubqueryType getType() {
            return type;
        }
    }
}


