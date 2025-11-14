package com.geico.poc.cassandrasql.window;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for window functions using Apache Calcite.
 * 
 * Extracts window function specifications from SQL queries.
 */
public class WindowFunctionParser {
    
    /**
     * Parse window functions from SQL
     */
    public static List<WindowSpec> parseWindowFunctions(String sql) throws SqlParseException {
        List<WindowSpec> windowSpecs = new ArrayList<>();
        
        // Parse SQL with Calcite
        SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();
        
        // Handle ORDER BY wrapping
        if (sqlNode instanceof SqlOrderBy) {
            SqlOrderBy orderBy = (SqlOrderBy) sqlNode;
            sqlNode = orderBy.query;
        }
        
        if (!(sqlNode instanceof SqlSelect)) {
            return windowSpecs;
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        // Extract window functions from SELECT list
        if (select.getSelectList() != null) {
            for (SqlNode selectItem : select.getSelectList()) {
                WindowSpec spec = extractWindowSpec(selectItem);
                if (spec != null) {
                    windowSpecs.add(spec);
                }
            }
        }
        
        return windowSpecs;
    }
    
    /**
     * Extract window spec from a SELECT item
     */
    private static WindowSpec extractWindowSpec(SqlNode node) {
        // Handle AS alias
        String alias = null;
        SqlNode actualNode = node;
        
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getOperator().getName().equals("AS")) {
                List<SqlNode> operands = call.getOperandList();
                if (operands.size() >= 2) {
                    actualNode = operands.get(0);
                    alias = operands.get(1).toString();
                }
            }
        }
        
        // Check if it's a window function (has OVER clause)
        if (!(actualNode instanceof SqlBasicCall)) {
            return null;
        }
        
        SqlBasicCall call = (SqlBasicCall) actualNode;
        
        // Look for OVER operator
        if (!call.getOperator().getName().equalsIgnoreCase("OVER")) {
            return null;
        }
        
        // Extract function call and window specification
        List<SqlNode> operands = call.getOperandList();
        if (operands.size() < 2) {
            return null;
        }
        
        SqlNode functionNode = operands.get(0);
        SqlNode windowNode = operands.get(1);
        
        // Extract function name and arguments
        String functionName = null;
        List<String> functionArgs = new ArrayList<>();
        
        if (functionNode instanceof SqlBasicCall) {
            SqlBasicCall funcCall = (SqlBasicCall) functionNode;
            functionName = funcCall.getOperator().getName();
            
            // Extract function arguments
            for (SqlNode arg : funcCall.getOperandList()) {
                functionArgs.add(arg.toString());
            }
        } else if (functionNode instanceof SqlIdentifier) {
            functionName = functionNode.toString();
        }
        
        if (functionName == null) {
            return null;
        }
        
        // Extract window specification (PARTITION BY, ORDER BY, frame)
        List<String> partitionByColumns = new ArrayList<>();
        List<WindowSpec.OrderByColumn> orderByColumns = new ArrayList<>();
        WindowFrame frame = WindowFrame.defaultFrame();
        
        if (windowNode instanceof SqlWindow) {
            SqlWindow window = (SqlWindow) windowNode;
            
            // Extract PARTITION BY
            if (window.getPartitionList() != null) {
                for (SqlNode partitionNode : window.getPartitionList()) {
                    partitionByColumns.add(partitionNode.toString());
                }
            }
            
            // Extract ORDER BY
            if (window.getOrderList() != null) {
                for (SqlNode orderNode : window.getOrderList()) {
                    if (orderNode instanceof SqlBasicCall) {
                        SqlBasicCall orderCall = (SqlBasicCall) orderNode;
                        String column = orderCall.getOperandList().get(0).toString();
                        boolean ascending = !orderCall.getOperator().getName().equalsIgnoreCase("DESC");
                        orderByColumns.add(new WindowSpec.OrderByColumn(column, ascending));
                    } else {
                        orderByColumns.add(new WindowSpec.OrderByColumn(orderNode.toString(), true));
                    }
                }
            }
            
            // Extract window frame (if specified)
            // For now, use default frame
            // TODO: Parse ROWS BETWEEN / RANGE BETWEEN
        }
        
        // Use function name as alias if not specified
        if (alias == null) {
            alias = functionName.toLowerCase();
        }
        
        return new WindowSpec(functionName, functionArgs, partitionByColumns, orderByColumns, frame, alias);
    }
    
    /**
     * Check if SQL contains window functions
     */
    public static boolean hasWindowFunctions(String sql) {
        String sqlUpper = sql.toUpperCase();
        return sqlUpper.contains(" OVER ") || sqlUpper.contains(" OVER(");
    }
    
    /**
     * Remove window functions from SQL to get base query
     * 
     * This is a simple approach: remove everything from function name to end of OVER clause
     */
    public static String removeWindowFunctions(String sql) throws SqlParseException {
        SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();
        
        // Handle ORDER BY wrapping
        if (sqlNode instanceof SqlOrderBy) {
            SqlOrderBy orderBy = (SqlOrderBy) sqlNode;
            sqlNode = orderBy.query;
        }
        
        if (!(sqlNode instanceof SqlSelect)) {
            return sql;
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        // Build new SELECT list without window functions
        StringBuilder newSql = new StringBuilder("SELECT ");
        List<String> selectItems = new ArrayList<>();
        
        if (select.getSelectList() != null) {
            for (SqlNode selectItem : select.getSelectList()) {
                String item = getBaseSelectItem(selectItem);
                if (item != null && !item.isEmpty()) {
                    selectItems.add(item);
                }
            }
        }
        
        if (selectItems.isEmpty()) {
            selectItems.add("*");
        }
        
        newSql.append(String.join(", ", selectItems));
        
        // Add FROM clause
        if (select.getFrom() != null) {
            newSql.append(" FROM ").append(select.getFrom());
        }
        
        // Add WHERE clause
        if (select.getWhere() != null) {
            newSql.append(" WHERE ").append(select.getWhere());
        }
        
        // Add GROUP BY clause
        if (select.getGroup() != null && select.getGroup().size() > 0) {
            newSql.append(" GROUP BY ");
            List<String> groupBy = new ArrayList<>();
            for (SqlNode groupNode : select.getGroup()) {
                groupBy.add(groupNode.toString());
            }
            newSql.append(String.join(", ", groupBy));
        }
        
        // Add HAVING clause
        if (select.getHaving() != null) {
            newSql.append(" HAVING ").append(select.getHaving());
        }
        
        return newSql.toString();
    }
    
    /**
     * Get base select item (without window function)
     */
    private static String getBaseSelectItem(SqlNode node) {
        // Handle AS alias
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getOperator().getName().equals("AS")) {
                List<SqlNode> operands = call.getOperandList();
                if (operands.size() >= 2) {
                    SqlNode actualNode = operands.get(0);
                    
                    // If it's a window function, skip it
                    if (actualNode instanceof SqlBasicCall) {
                        SqlBasicCall actualCall = (SqlBasicCall) actualNode;
                        if (actualCall.getOperator().getName().equalsIgnoreCase("OVER")) {
                            return null;
                        }
                    }
                    
                    return node.toString();
                }
            }
            
            // Check if it's a window function
            if (call.getOperator().getName().equalsIgnoreCase("OVER")) {
                return null;
            }
        }
        
        return node.toString();
    }
}

