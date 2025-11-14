package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes queries with derived tables (subqueries in FROM clause)
 * 
 * Example:
 * SELECT * FROM (SELECT * FROM users WHERE age > 18) AS adults
 */
@Component
public class DerivedTableExecutor {
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private CalciteParser calciteParser;
    
    /**
     * Execute a query with derived tables
     */
    public QueryResponse execute(ParsedQuery outerQuery) throws Exception {
        System.out.println("🔧 Executing query with derived table");
        
        String sql = outerQuery.getRawSql();
        
        // Find all derived tables
        List<DerivedTable> derivedTables = findDerivedTables(sql);
        
        if (derivedTables.isEmpty()) {
            // No derived tables, execute normally
            return cassandraExecutor.execute(outerQuery);
        }
        
        System.out.println("   Found " + derivedTables.size() + " derived table(s)");
        
        // Execute derived tables and create temporary views
        Map<String, QueryResponse> derivedResults = new HashMap<>();
        
        for (DerivedTable dt : derivedTables) {
            System.out.println("   Executing derived table: " + dt.getAlias());
            System.out.println("   SQL: " + dt.getSubquery());
            
            // Execute the subquery
            ParsedQuery subquery = calciteParser.parse(dt.getSubquery());
            QueryResponse result = cassandraExecutor.execute(subquery);
            
            if (result.getError() != null) {
                return QueryResponse.error("Derived table '" + dt.getAlias() + "' failed: " + result.getError());
            }
            
            derivedResults.put(dt.getAlias(), result);
            System.out.println("   ✅ Derived table '" + dt.getAlias() + "' returned " + result.getRowCount() + " rows");
        }
        
        // For simple cases, if there's only one derived table and no other tables,
        // we can just return its results
        if (derivedTables.size() == 1 && isSimpleDerivedTableQuery(sql)) {
            DerivedTable dt = derivedTables.get(0);
            QueryResponse result = derivedResults.get(dt.getAlias());
            
            // Apply any outer WHERE, ORDER BY, LIMIT
            return applyOuterClauses(result, sql, dt);
        }
        
        // For complex cases (JOINs with derived tables, multiple derived tables),
        // we need more sophisticated handling
        return QueryResponse.error("Complex derived table queries (JOINs, multiple tables) not yet fully supported. " +
                                  "Try simplifying your query or using CTEs (WITH clause) instead.");
    }
    
    /**
     * Find all derived tables in the SQL
     */
    private List<DerivedTable> findDerivedTables(String sql) {
        List<DerivedTable> derivedTables = new ArrayList<>();
        
        // Pattern: (SELECT ...) AS alias
        // This is a simplified pattern - a full implementation would use Calcite's AST
        Pattern pattern = Pattern.compile(
            "\\(\\s*(SELECT\\s+.+?)\\)\\s+AS\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        
        Matcher matcher = pattern.matcher(sql);
        
        while (matcher.find()) {
            String subquery = matcher.group(1).trim();
            String alias = matcher.group(2).trim();
            
            // Verify this is a complete SELECT statement
            if (isCompleteSelect(subquery)) {
                derivedTables.add(new DerivedTable(subquery, alias, matcher.start(), matcher.end()));
            }
        }
        
        return derivedTables;
    }
    
    /**
     * Check if the subquery is a complete SELECT statement
     */
    private boolean isCompleteSelect(String sql) {
        sql = sql.trim().toUpperCase();
        if (!sql.startsWith("SELECT")) {
            return false;
        }
        
        // Check for balanced parentheses
        int depth = 0;
        for (char c : sql.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth < 0) return false;
        }
        
        return depth == 0;
    }
    
    /**
     * Check if this is a simple derived table query (no JOINs, just SELECT FROM derived)
     */
    private boolean isSimpleDerivedTableQuery(String sql) {
        String upper = sql.toUpperCase();
        
        // Check if there's a JOIN keyword
        if (upper.contains(" JOIN ")) {
            return false;
        }
        
        // Check if there are multiple tables in FROM
        int fromPos = upper.indexOf(" FROM ");
        if (fromPos < 0) {
            return false;
        }
        
        String afterFrom = upper.substring(fromPos + 6);
        int wherePos = afterFrom.indexOf(" WHERE ");
        int orderPos = afterFrom.indexOf(" ORDER ");
        int limitPos = afterFrom.indexOf(" LIMIT ");
        
        int endPos = afterFrom.length();
        if (wherePos > 0) endPos = Math.min(endPos, wherePos);
        if (orderPos > 0) endPos = Math.min(endPos, orderPos);
        if (limitPos > 0) endPos = Math.min(endPos, limitPos);
        
        String fromClause = afterFrom.substring(0, endPos).trim();
        
        // If FROM clause contains only one derived table reference, it's simple
        return !fromClause.contains(",");
    }
    
    /**
     * Apply outer WHERE, ORDER BY, LIMIT clauses to the derived table result
     */
    private QueryResponse applyOuterClauses(QueryResponse result, String sql, DerivedTable derivedTable) {
        // For now, if there are outer clauses, we need to re-execute
        // A full implementation would parse and apply these clauses
        
        String upper = sql.toUpperCase();
        boolean hasOuterWhere = upper.contains(" WHERE ") && 
                                upper.indexOf(" WHERE ") > derivedTable.getEndPos();
        boolean hasOuterOrderBy = upper.contains(" ORDER BY ") && 
                                  upper.indexOf(" ORDER BY ") > derivedTable.getEndPos();
        boolean hasOuterLimit = upper.contains(" LIMIT ") && 
                                upper.indexOf(" LIMIT ") > derivedTable.getEndPos();
        
        if (!hasOuterWhere && !hasOuterOrderBy && !hasOuterLimit) {
            // No outer clauses, just return the result
            return result;
        }
        
        // Has outer clauses - for now, return error
        return QueryResponse.error("Derived tables with outer WHERE/ORDER BY/LIMIT not yet fully supported. " +
                                  "Apply filtering/sorting in the subquery instead.");
    }
    
    /**
     * Represents a derived table (subquery in FROM clause)
     */
    private static class DerivedTable {
        private final String subquery;
        private final String alias;
        private final int startPos;
        private final int endPos;
        
        public DerivedTable(String subquery, String alias, int startPos, int endPos) {
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



