package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Executes queries with correlated subqueries using nested loop execution
 * 
 * A correlated subquery references columns from the outer query.
 * Example: SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)
 * 
 * Execution strategy:
 * 1. Execute outer query to get candidate rows
 * 2. For each outer row, substitute correlated references into subquery
 * 3. Execute subquery and evaluate predicate
 * 4. Keep outer rows that satisfy the predicate
 */
@Component
public class CorrelatedSubqueryExecutor {
    
    @Autowired
    private CassandraExecutor cassandraExecutor;
    
    @Autowired
    private CalciteParser calciteParser;
    
    /**
     * Execute a query with correlated subqueries
     */
    public QueryResponse execute(ParsedQuery outerQuery) throws Exception {
        System.out.println("Executing query with correlated subqueries");
        
        String sql = outerQuery.getRawSql();
        
        // Analyze the query to find correlated subqueries
        CorrelatedSubqueryInfo info = analyzeCorrelatedSubquery(sql);
        
        if (!info.hasCorrelatedSubquery()) {
            // No correlated subquery, execute normally
            return cassandraExecutor.execute(outerQuery);
        }
        
        System.out.println("Found correlated subquery: " + info.getSubquerySql());
        System.out.println("Correlated columns: " + info.getCorrelatedColumns());
        
        // Execute using nested loop
        return executeNestedLoop(info);
    }
    
    /**
     * Analyze SQL to find correlated subqueries
     */
    private CorrelatedSubqueryInfo analyzeCorrelatedSubquery(String sql) throws SqlParseException {
        CorrelatedSubqueryInfo info = new CorrelatedSubqueryInfo();
        info.setOriginalSql(sql);
        
        // Parse SQL
        SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlSelect)) {
            return info;
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        // Check WHERE clause for EXISTS/IN subqueries
        if (select.getWhere() != null) {
            findCorrelatedSubquery(select, select.getWhere(), info);
        }
        
        return info;
    }
    
    /**
     * Recursively find correlated subqueries in WHERE clause
     */
    private void findCorrelatedSubquery(SqlSelect outerSelect, SqlNode node, CorrelatedSubqueryInfo info) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            SqlOperator operator = call.getOperator();
            
            // Check for EXISTS/NOT EXISTS
            if (operator.getName().equalsIgnoreCase("EXISTS") || 
                operator.getName().equalsIgnoreCase("NOT EXISTS")) {
                
                // Get the subquery
                if (call.getOperandList().size() > 0) {
                    SqlNode subqueryNode = call.getOperandList().get(0);
                    
                    if (subqueryNode instanceof SqlSelect) {
                        SqlSelect subquery = (SqlSelect) subqueryNode;
                        
                        // Check if it's correlated
                        Set<String> correlatedCols = findCorrelatedColumns(outerSelect, subquery);
                        
                        if (!correlatedCols.isEmpty()) {
                            info.setHasCorrelatedSubquery(true);
                            info.setSubquerySql(subqueryNode.toString());
                            info.setSubqueryType(operator.getName().equalsIgnoreCase("EXISTS") ? 
                                SubqueryType.EXISTS : SubqueryType.NOT_EXISTS);
                            info.setCorrelatedColumns(correlatedCols);
                            info.setOuterTableAlias(extractOuterTableAlias(outerSelect));
                            info.setSubqueryTableAlias(extractSubqueryTableAlias(subquery));
                        }
                    }
                }
            }
            // Check for IN subqueries
            else if (operator.getName().equalsIgnoreCase("IN")) {
                // TODO: Handle correlated IN subqueries
            }
            
            // Recursively check operands
            for (SqlNode operand : call.getOperandList()) {
                if (operand != null) {
                    findCorrelatedSubquery(outerSelect, operand, info);
                }
            }
        }
    }
    
    /**
     * Find columns in subquery that reference outer query
     */
    private Set<String> findCorrelatedColumns(SqlSelect outerSelect, SqlSelect subquery) {
        Set<String> correlatedCols = new HashSet<>();
        
        // Get outer table alias
        String outerAlias = extractOuterTableAlias(outerSelect);
        
        if (outerAlias == null) {
            return correlatedCols;
        }
        
        // Check subquery WHERE clause for references to outer table
        if (subquery.getWhere() != null) {
            findOuterReferences(subquery.getWhere(), outerAlias, correlatedCols);
        }
        
        return correlatedCols;
    }
    
    /**
     * Recursively find references to outer table in subquery
     */
    private void findOuterReferences(SqlNode node, String outerAlias, Set<String> correlatedCols) {
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            
            // Check if this identifier references the outer table
            if (id.names.size() == 2) {
                String tableAlias = id.names.get(0);
                String columnName = id.names.get(1);
                
                if (tableAlias.equalsIgnoreCase(outerAlias)) {
                    correlatedCols.add(columnName);
                }
            }
        } else if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            for (SqlNode operand : call.getOperandList()) {
                if (operand != null) {
                    findOuterReferences(operand, outerAlias, correlatedCols);
                }
            }
        }
    }
    
    /**
     * Extract outer table alias from SELECT
     */
    private String extractOuterTableAlias(SqlSelect select) {
        if (select.getFrom() == null) {
            return null;
        }
        
        SqlNode from = select.getFrom();
        
        if (from instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) from;
            if (call.getOperator().getName().equalsIgnoreCase("AS")) {
                // Table with alias: "users AS u"
                if (call.getOperandList().size() >= 2) {
                    SqlNode aliasNode = call.getOperandList().get(1);
                    if (aliasNode instanceof SqlIdentifier) {
                        return ((SqlIdentifier) aliasNode).getSimple();
                    }
                }
            }
        } else if (from instanceof SqlIdentifier) {
            // Just table name, no alias
            SqlIdentifier id = (SqlIdentifier) from;
            if (id.names.size() == 2) {
                // "users u" (implicit alias)
                return id.names.get(1);
            }
        }
        
        return null;
    }
    
    /**
     * Extract subquery table alias
     */
    private String extractSubqueryTableAlias(SqlSelect subquery) {
        if (subquery.getFrom() == null) {
            return null;
        }
        
        SqlNode from = subquery.getFrom();
        
        if (from instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) from;
            if (call.getOperator().getName().equalsIgnoreCase("AS")) {
                if (call.getOperandList().size() >= 2) {
                    SqlNode aliasNode = call.getOperandList().get(1);
                    if (aliasNode instanceof SqlIdentifier) {
                        return ((SqlIdentifier) aliasNode).getSimple();
                    }
                }
            }
        } else if (from instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) from;
            if (id.names.size() == 2) {
                return id.names.get(1);
            }
        }
        
        return null;
    }
    
    /**
     * Execute correlated subquery using nested loop
     */
    private QueryResponse executeNestedLoop(CorrelatedSubqueryInfo info) throws Exception {
        System.out.println("Executing nested loop for correlated subquery");
        
        // Step 1: Build outer query without the correlated subquery predicate
        String outerQuerySql = buildOuterQueryWithoutSubquery(info);
        System.out.println("Outer query: " + outerQuerySql);
        
        // Step 2: Execute outer query
        System.out.println("DEBUG: Outer query SQL: " + outerQuerySql);
        ParsedQuery outerParsed = calciteParser.parse(outerQuerySql);
        QueryResponse outerResult = cassandraExecutor.execute(outerParsed);
        
        System.out.println("Outer query returned " + outerResult.getRowCount() + " rows");
        
        // Step 3: For each outer row, evaluate the subquery
        List<Map<String, Object>> filteredRows = new ArrayList<>();
        
        for (Map<String, Object> outerRow : outerResult.getRows()) {
            // Substitute correlated values into subquery
            String instantiatedSubquery = instantiateSubquery(info, outerRow);
            System.out.println("DEBUG: Instantiated subquery: " + instantiatedSubquery);
            
            // Execute subquery
            ParsedQuery subqueryParsed = calciteParser.parse(instantiatedSubquery);
            QueryResponse subqueryResult = cassandraExecutor.execute(subqueryParsed);
            
            // Evaluate predicate
            boolean satisfiesPredicate = evaluatePredicate(info, subqueryResult);
            
            if (satisfiesPredicate) {
                filteredRows.add(outerRow);
            }
        }
        
        System.out.println("Filtered to " + filteredRows.size() + " rows");
        
        // Build result
        QueryResponse result = new QueryResponse();
        result.setRows(filteredRows);
        result.setRowCount(filteredRows.size());
        result.setColumns(outerResult.getColumns());
        
        return result;
    }
    
    /**
     * Build outer query without the correlated subquery predicate
     */
    private String buildOuterQueryWithoutSubquery(CorrelatedSubqueryInfo info) {
        String sql = info.getOriginalSql();
        
        // Remove the EXISTS/NOT EXISTS clause
        // This is a simple approach - just remove the subquery part
        // A more robust implementation would parse and rebuild the query
        
        // Find the subquery in the original SQL
        String subqueryStr = info.getSubquerySql();
        
        // Find WHERE clause
        int wherePos = sql.toUpperCase().indexOf("WHERE");
        if (wherePos < 0) {
            return sql;
        }
        
        // Find the EXISTS/NOT EXISTS keyword
        int existsPos = sql.toUpperCase().indexOf("EXISTS", wherePos);
        if (existsPos < 0) {
            return sql;
        }
        
        // Find the opening parenthesis after EXISTS
        int openParen = sql.indexOf("(", existsPos);
        if (openParen < 0) {
            return sql;
        }
        
        // Find matching closing parenthesis
        int closeParen = findMatchingParen(sql, openParen);
        if (closeParen < 0) {
            return sql;
        }
        
        // Check if there's "NOT" before EXISTS
        String beforeExists = sql.substring(wherePos, existsPos).trim().toUpperCase();
        boolean hasNot = beforeExists.endsWith("NOT");
        
        int removeStart = hasNot ? sql.lastIndexOf("NOT", existsPos) : existsPos;
        int removeEnd = closeParen + 1;
        
        // Remove the EXISTS clause
        String result = sql.substring(0, removeStart).trim() + " " + sql.substring(removeEnd).trim();
        
        // Clean up WHERE clause if it's now empty
        result = result.replaceAll("WHERE\\s*$", "").trim();
        
        // Remove backticks that might be in the outer query
        result = result.replace("`", "");
        
        return result;
    }
    
    /**
     * Instantiate subquery with values from outer row
     */
    private String instantiateSubquery(CorrelatedSubqueryInfo info, Map<String, Object> outerRow) {
        String subquery = info.getSubquerySql();
        
        // Remove backticks that Calcite adds (they cause parse errors)
        subquery = subquery.replace("`", "");
        
        // Remove table aliases from the subquery (e.g., "FROM orders o" -> "FROM orders")
        // This is necessary because the subquery will be executed standalone
        String subqueryAlias = info.getSubqueryTableAlias();
        if (subqueryAlias != null) {
            // Replace "FROM table_name alias" with "FROM table_name"
            // Also replace "alias.column" with just "column"
            subquery = subquery.replaceAll("(?i)\\b" + subqueryAlias + "\\.", "");
        }
        
        // Replace correlated column references with actual values
        for (String correlatedCol : info.getCorrelatedColumns()) {
            String outerAlias = info.getOuterTableAlias();
            String pattern = outerAlias + "\\." + correlatedCol;
            
            Object value = outerRow.get(correlatedCol.toLowerCase());
            String replacement;
            
            if (value == null) {
                replacement = "NULL";
            } else if (value instanceof Number) {
                replacement = value.toString();
            } else {
                replacement = "'" + value.toString().replace("'", "''") + "'";
            }
            
            subquery = subquery.replaceAll("(?i)" + pattern, replacement);
        }
        
        return subquery;
    }
    
    /**
     * Evaluate subquery predicate (EXISTS/NOT EXISTS)
     */
    private boolean evaluatePredicate(CorrelatedSubqueryInfo info, QueryResponse subqueryResult) {
        boolean hasRows = subqueryResult.getRowCount() > 0;
        
        if (info.getSubqueryType() == SubqueryType.EXISTS) {
            return hasRows;
        } else if (info.getSubqueryType() == SubqueryType.NOT_EXISTS) {
            return !hasRows;
        }
        
        return false;
    }
    
    /**
     * Find matching closing parenthesis
     */
    private int findMatchingParen(String sql, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < sql.length(); i++) {
            if (sql.charAt(i) == '(') {
                depth++;
            } else if (sql.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    /**
     * Information about a correlated subquery
     */
    private static class CorrelatedSubqueryInfo {
        private String originalSql;
        private boolean hasCorrelatedSubquery;
        private String subquerySql;
        private SubqueryType subqueryType;
        private Set<String> correlatedColumns;
        private String outerTableAlias;
        private String subqueryTableAlias;
        
        public String getOriginalSql() { return originalSql; }
        public void setOriginalSql(String originalSql) { this.originalSql = originalSql; }
        
        public boolean hasCorrelatedSubquery() { return hasCorrelatedSubquery; }
        public void setHasCorrelatedSubquery(boolean has) { this.hasCorrelatedSubquery = has; }
        
        public String getSubquerySql() { return subquerySql; }
        public void setSubquerySql(String sql) { this.subquerySql = sql; }
        
        public SubqueryType getSubqueryType() { return subqueryType; }
        public void setSubqueryType(SubqueryType type) { this.subqueryType = type; }
        
        public Set<String> getCorrelatedColumns() { return correlatedColumns; }
        public void setCorrelatedColumns(Set<String> cols) { this.correlatedColumns = cols; }
        
        public String getOuterTableAlias() { return outerTableAlias; }
        public void setOuterTableAlias(String alias) { this.outerTableAlias = alias; }
        
        public String getSubqueryTableAlias() { return subqueryTableAlias; }
        public void setSubqueryTableAlias(String alias) { this.subqueryTableAlias = alias; }
    }
}



