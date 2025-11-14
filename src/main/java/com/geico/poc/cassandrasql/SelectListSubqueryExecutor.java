package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes queries with subqueries in the SELECT list
 * 
 * Example:
 * SELECT id, name, (SELECT COUNT(*) FROM orders WHERE user_id = users.id) AS order_count
 * FROM users
 */
@Component
public class SelectListSubqueryExecutor {
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private CalciteParser calciteParser;
    
    /**
     * Execute a query with subqueries in SELECT list
     */
    public QueryResponse execute(ParsedQuery outerQuery) throws Exception {
        System.out.println("🔧 Executing query with SELECT list subqueries");
        
        String sql = outerQuery.getRawSql();
        
        // Find all subqueries in SELECT list
        List<SelectSubquery> selectSubqueries = findSelectSubqueries(sql);
        
        if (selectSubqueries.isEmpty()) {
            // No SELECT list subqueries, execute normally
            return cassandraExecutor.execute(outerQuery);
        }
        
        System.out.println("   Found " + selectSubqueries.size() + " SELECT list subquery(ies)");
        
        // Check if any are correlated (reference outer table)
        for (SelectSubquery sq : selectSubqueries) {
            if (isCorrelated(sq.getSubquery())) {
                return executeCorrelatedSelectSubqueries(sql, selectSubqueries);
            }
        }
        
        // All non-correlated - execute once and add to all rows
        return executeNonCorrelatedSelectSubqueries(sql, selectSubqueries);
    }
    
    /**
     * Find all subqueries in the SELECT list
     */
    private List<SelectSubquery> findSelectSubqueries(String sql) {
        List<SelectSubquery> subqueries = new ArrayList<>();
        
        // Find the SELECT clause (between SELECT and FROM)
        Pattern selectPattern = Pattern.compile(
            "SELECT\\s+(.+?)\\s+FROM",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        Matcher selectMatcher = selectPattern.matcher(sql);
        if (!selectMatcher.find()) {
            return subqueries;
        }
        
        String selectClause = selectMatcher.group(1);
        int selectStart = selectMatcher.start(1);
        
        // Find subqueries in the SELECT clause: (SELECT ...)
        int depth = 0;
        int subqueryStart = -1;
        StringBuilder currentSubquery = new StringBuilder();
        
        for (int i = 0; i < selectClause.length(); i++) {
            char c = selectClause.charAt(i);
            
            if (c == '(') {
                if (depth == 0) {
                    // Check if this starts a SELECT
                    int selectPos = selectClause.toUpperCase().indexOf("SELECT", i + 1);
                    if (selectPos > i && selectPos < i + 20) { // SELECT should be near the opening paren
                        subqueryStart = i;
                    }
                }
                depth++;
                if (subqueryStart >= 0) {
                    currentSubquery.append(c);
                }
            } else if (c == ')') {
                depth--;
                if (subqueryStart >= 0) {
                    currentSubquery.append(c);
                }
                
                if (depth == 0 && subqueryStart >= 0) {
                    // End of subquery
                    String subquerySql = currentSubquery.toString();
                    // Remove outer parentheses
                    subquerySql = subquerySql.substring(1, subquerySql.length() - 1).trim();
                    
                    // Find alias (AS alias or just alias after the closing paren)
                    String alias = findAlias(selectClause, i);
                    
                    subqueries.add(new SelectSubquery(
                        subquerySql,
                        alias,
                        selectStart + subqueryStart,
                        selectStart + i + 1
                    ));
                    
                    currentSubquery = new StringBuilder();
                    subqueryStart = -1;
                }
            } else if (subqueryStart >= 0) {
                currentSubquery.append(c);
            }
        }
        
        return subqueries;
    }
    
    /**
     * Find the alias for a subquery
     */
    private String findAlias(String selectClause, int closingParenPos) {
        if (closingParenPos + 1 >= selectClause.length()) {
            return "subquery_col";
        }
        
        String afterParen = selectClause.substring(closingParenPos + 1).trim();
        
        // Check for AS keyword
        Pattern asPattern = Pattern.compile("^AS\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher asMatcher = asPattern.matcher(afterParen);
        if (asMatcher.find()) {
            return asMatcher.group(1);
        }
        
        // Check for alias without AS
        Pattern aliasPattern = Pattern.compile("^(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher aliasMatcher = aliasPattern.matcher(afterParen);
        if (aliasMatcher.find()) {
            String possibleAlias = aliasMatcher.group(1);
            // Make sure it's not a SQL keyword
            if (!isSqlKeyword(possibleAlias)) {
                return possibleAlias;
            }
        }
        
        return "subquery_col";
    }
    
    /**
     * Check if a string is a SQL keyword
     */
    private boolean isSqlKeyword(String word) {
        String upper = word.toUpperCase();
        return upper.equals("FROM") || upper.equals("WHERE") || upper.equals("ORDER") || 
               upper.equals("GROUP") || upper.equals("HAVING") || upper.equals("LIMIT");
    }
    
    /**
     * Check if a subquery is correlated (references outer table)
     */
    private boolean isCorrelated(String subquery) {
        // Simple heuristic: look for qualified column references (table.column)
        // that might reference the outer query
        Pattern pattern = Pattern.compile("\\b(\\w+)\\.(\\w+)\\b");
        Matcher matcher = pattern.matcher(subquery);
        
        // If we find qualified references, assume it's correlated
        // A full implementation would check if the table is in the subquery's FROM clause
        return matcher.find();
    }
    
    /**
     * Execute non-correlated SELECT subqueries (execute once, add to all rows)
     */
    private QueryResponse executeNonCorrelatedSelectSubqueries(String sql, List<SelectSubquery> subqueries) throws Exception {
        System.out.println("   Executing non-correlated SELECT subqueries");
        
        // Execute each subquery once
        Map<String, Object> subqueryResults = new HashMap<>();
        
        for (SelectSubquery sq : subqueries) {
            System.out.println("   Executing subquery for column: " + sq.getAlias());
            
            ParsedQuery subquery = calciteParser.parse(sq.getSubquery());
            QueryResponse result = cassandraExecutor.execute(subquery);
            
            if (result.getError() != null) {
                return QueryResponse.error("Subquery failed: " + result.getError());
            }
            
            // Extract scalar value
            Object value = extractScalarValue(result);
            subqueryResults.put(sq.getAlias(), value);
            
            System.out.println("   ✅ Subquery returned: " + value);
        }
        
        // Remove subqueries from SQL and execute main query
        String simplifiedSql = removeSelectSubqueries(sql, subqueries);
        System.out.println("   Simplified SQL: " + simplifiedSql);
        
        ParsedQuery mainQuery = calciteParser.parse(simplifiedSql);
        QueryResponse mainResult = cassandraExecutor.execute(mainQuery);
        
        if (mainResult.getError() != null) {
            return mainResult;
        }
        
        // Add subquery results to each row
        List<Map<String, Object>> enhancedRows = new ArrayList<>();
        List<String> enhancedColumns = new ArrayList<>(mainResult.getColumns());
        
        // Add subquery columns
        for (SelectSubquery sq : subqueries) {
            enhancedColumns.add(sq.getAlias());
        }
        
        for (Map<String, Object> row : mainResult.getRows()) {
            Map<String, Object> enhancedRow = new LinkedHashMap<>(row);
            
            // Add subquery values
            for (SelectSubquery sq : subqueries) {
                enhancedRow.put(sq.getAlias(), subqueryResults.get(sq.getAlias()));
            }
            
            enhancedRows.add(enhancedRow);
        }
        
        return new QueryResponse(enhancedRows, enhancedColumns);
    }
    
    /**
     * Execute correlated SELECT subqueries (execute once per row)
     */
    private QueryResponse executeCorrelatedSelectSubqueries(String sql, List<SelectSubquery> subqueries) throws Exception {
        System.out.println("   ⚠️  Correlated SELECT subqueries detected");
        
        // For now, return an error with a helpful message
        return QueryResponse.error(
            "Correlated subqueries in SELECT list are not yet fully supported. " +
            "These require executing the subquery once per row, which can be very slow. " +
            "Consider using a JOIN with aggregation instead. " +
            "Example: SELECT u.*, COUNT(o.id) FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id"
        );
    }
    
    /**
     * Remove subqueries from SELECT list, keeping other columns
     */
    private String removeSelectSubqueries(String sql, List<SelectSubquery> subqueries) {
        // Find the SELECT clause
        Pattern selectPattern = Pattern.compile(
            "SELECT\\s+(.+?)\\s+FROM",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        Matcher selectMatcher = selectPattern.matcher(sql);
        if (!selectMatcher.find()) {
            return sql;
        }
        
        String selectClause = selectMatcher.group(1);
        String beforeSelect = sql.substring(0, selectMatcher.start(1));
        String afterSelect = sql.substring(selectMatcher.end(1));
        
        // Remove each subquery from the SELECT clause
        for (SelectSubquery sq : subqueries) {
            // Find the subquery in the SELECT clause
            String subqueryPattern = "\\(\\s*" + Pattern.quote(sq.getSubquery()) + "\\s*\\)";
            selectClause = selectClause.replaceAll(subqueryPattern, "");
            
            // Remove the alias
            selectClause = selectClause.replaceAll("\\s+AS\\s+" + sq.getAlias(), "");
            selectClause = selectClause.replaceAll("\\s+" + sq.getAlias() + "\\b", "");
        }
        
        // Clean up commas
        selectClause = selectClause.replaceAll(",\\s*,", ",");
        selectClause = selectClause.replaceAll("^\\s*,", "");
        selectClause = selectClause.replaceAll(",\\s*$", "");
        selectClause = selectClause.trim();
        
        // If SELECT clause is empty, use *
        if (selectClause.isEmpty()) {
            selectClause = "*";
        }
        
        return beforeSelect + selectClause + afterSelect;
    }
    
    /**
     * Extract a scalar value from a query result
     */
    private Object extractScalarValue(QueryResponse result) {
        if (result.getRows() == null || result.getRows().isEmpty()) {
            return null;
        }
        
        Map<String, Object> firstRow = result.getRows().get(0);
        if (firstRow.isEmpty()) {
            return null;
        }
        
        // Return the first column value
        return firstRow.values().iterator().next();
    }
    
    /**
     * Represents a subquery in the SELECT list
     */
    private static class SelectSubquery {
        private final String subquery;
        private final String alias;
        private final int startPos;
        private final int endPos;
        
        public SelectSubquery(String subquery, String alias, int startPos, int endPos) {
            this.subquery = subquery;
            this.alias = alias;
            this.startPos = startPos;
            this.endPos = endPos;
        }
        
        public String getSubquery() {
            return subquery;
        }
        
        public String getAlias() {
            return alias;
        }
        
        public int getStartPos() {
            return startPos;
        }
        
        public int getEndPos() {
            return endPos;
        }
    }
}



