package com.geico.poc.cassandrasql.kv;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Calcite-based SQL parser for KV mode.
 * 
 * Parses SQL statements and extracts structured information
 * for execution planning.
 */
@Component
public class CalciteSqlParser {
    
    /**
     * Parse an INSERT statement
     */
    public InsertInfo parseInsert(String sql) throws SqlParseException {
        SqlParser parser = SqlParser.create(sql);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlInsert)) {
            throw new IllegalArgumentException("Not an INSERT statement");
        }
        
        SqlInsert insert = (SqlInsert) sqlNode;
        
        // Extract table name
        String tableName = insert.getTargetTable().toString();
        
        // Extract column names (if specified)
        List<String> columns = new ArrayList<>();
        if (insert.getTargetColumnList() != null) {
            for (SqlNode col : insert.getTargetColumnList()) {
                columns.add(col.toString());
            }
        }
        
        // Extract values
        List<List<Object>> valuesList = new ArrayList<>();
        SqlNode source = insert.getSource();
        
        if (source instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) source;
            if (call.getOperator().getName().equals("VALUES")) {
                for (SqlNode row : call.getOperandList()) {
                    List<Object> rowValues = extractValues(row);
                    valuesList.add(rowValues);
                }
            }
        }
        
        return new InsertInfo(tableName, columns, valuesList);
    }
    
    /**
     * Parse an UPDATE statement
     */
    public UpdateInfo parseUpdate(String sql) throws SqlParseException {
        SqlParser parser = SqlParser.create(sql);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlUpdate)) {
            throw new IllegalArgumentException("Not an UPDATE statement");
        }
        
        SqlUpdate update = (SqlUpdate) sqlNode;
        
        // Extract table name
        String tableName = update.getTargetTable().toString();
        
        // Extract SET clauses - store as expressions, not just values
        Map<String, UpdateExpression> setExpressions = new LinkedHashMap<>();
        SqlNodeList targetColumns = update.getTargetColumnList();
        SqlNodeList sourceExpressions = update.getSourceExpressionList();
        
        for (int i = 0; i < targetColumns.size(); i++) {
            String column = targetColumns.get(i).toString();
            SqlNode expression = sourceExpressions.get(i);
            setExpressions.put(column, new UpdateExpression(expression));
        }
        
        // Extract WHERE clause
        WhereClause whereClause = null;
        if (update.getCondition() != null) {
            whereClause = parseWhereClause(update.getCondition());
        }
        
        return new UpdateInfo(tableName, setExpressions, whereClause);
    }
    
    /**
     * Parse a DELETE statement
     */
    public DeleteInfo parseDelete(String sql) throws SqlParseException {
        SqlParser parser = SqlParser.create(sql);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlDelete)) {
            throw new IllegalArgumentException("Not a DELETE statement");
        }
        
        SqlDelete delete = (SqlDelete) sqlNode;
        
        // Extract table name
        String tableName = delete.getTargetTable().toString();
        
        // Extract WHERE clause
        WhereClause whereClause = null;
        if (delete.getCondition() != null) {
            whereClause = parseWhereClause(delete.getCondition());
        }
        
        return new DeleteInfo(tableName, whereClause);
    }
    
    /**
     * Parse a SELECT statement
     */
    public SelectInfo parseSelect(String sql) throws SqlParseException {
        SqlParser parser = SqlParser.create(sql);
        SqlNode sqlNode = parser.parseStmt();
        
        // Handle SqlOrderBy wrapping SqlSelect (ORDER BY at top level)
        SqlOrderBy topLevelOrderBy = null;
        if (sqlNode instanceof SqlOrderBy) {
            topLevelOrderBy = (SqlOrderBy) sqlNode;
            sqlNode = topLevelOrderBy.query;
        }
        
        // Handle UNION ALL queries - they parse as SqlBasicCall, not SqlSelect
        if (sqlNode instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) sqlNode;
            if (call.getOperator().getName().toUpperCase().contains("UNION")) {
                // UNION queries are now supported - return a minimal SelectInfo
                // The actual execution will be handled by UnionExecutor
                return new SelectInfo(null, Collections.emptyList(), null, Collections.emptyList(), null);
            }
        }
        
        if (!(sqlNode instanceof SqlSelect)) {
            throw new IllegalArgumentException("Not a SELECT statement, got: " + sqlNode.getClass().getSimpleName());
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        // Extract table name
        String tableName = null;
        if (select.getFrom() != null) {
            tableName = extractTableName(select.getFrom());
        }
        
        // Extract selected columns and literals
        List<String> columns = new ArrayList<>();
        List<LiteralColumn> literals = new ArrayList<>();
        List<SqlNode> selectNodes = new ArrayList<>();
        int position = 0;
        
        if (select.getSelectList() != null) {
            for (SqlNode col : select.getSelectList()) {
                // Always store the original SqlNode for proper evaluation
                selectNodes.add(col);
                
                // Check if it's an AS expression (e.g., 'Branches' as table_name)
                if (col.getKind() == SqlKind.AS) {
                    SqlCall asCall = (SqlCall) col;
                    SqlNode valueNode = asCall.operand(0);
                    String alias = asCall.operand(1).toString();
                    
                    // Check if the value is a literal
                    if (valueNode instanceof SqlLiteral) {
                        Object value = extractValue(valueNode);
                        literals.add(new LiteralColumn(alias, value, position));
                    } else {
                        // Expression with alias - preserve the full expression including AS
                        // e.g., "first_name || ' ' || last_name AS full_name"
                        columns.add(col.toString());
                    }
                } else if (col instanceof SqlLiteral) {
                    // Literal without alias (e.g., SELECT 0)
                    Object value = extractValue(col);
                    String alias = "?column?";
                    literals.add(new LiteralColumn(alias, value, position));
                } else if (col instanceof SqlIdentifier) {
                    columns.add(col.toString());
                } else if (col.toString().equals("*")) {
                    columns.add("*");
                } else {
                    // Other expressions (aggregates, etc.) - keep as column
                    columns.add(col.toString());
                }
                position++;
            }
        }
        
        // Extract WHERE clause
        WhereClause whereClause = null;
        if (select.getWhere() != null) {
            whereClause = parseWhereClause(select.getWhere());
        }
        
        // Extract ORDER BY (from top-level SqlOrderBy or from SELECT)
        List<OrderByColumn> orderBy = new ArrayList<>();
        if (topLevelOrderBy != null && topLevelOrderBy.orderList != null) {
            for (SqlNode orderNode : topLevelOrderBy.orderList) {
                orderBy.add(parseOrderBy(orderNode));
            }
        } else if (select.getOrderList() != null) {
            for (SqlNode orderNode : select.getOrderList()) {
                orderBy.add(parseOrderBy(orderNode));
            }
        }
        
        // Extract LIMIT (from top-level SqlOrderBy or from SELECT)
        Integer limit = null;
        if (topLevelOrderBy != null && topLevelOrderBy.fetch != null) {
            limit = extractIntValue(topLevelOrderBy.fetch);
        } else if (select.getFetch() != null) {
            limit = extractIntValue(select.getFetch());
        }
        
        // Extract OFFSET (from top-level SqlOrderBy or from SELECT)
        Integer offset = null;
        if (topLevelOrderBy != null && topLevelOrderBy.offset != null) {
            offset = extractIntValue(topLevelOrderBy.offset);
        } else if (select.getOffset() != null) {
            offset = extractIntValue(select.getOffset());
        }
        
        return new SelectInfo(tableName, columns, whereClause, orderBy, limit, offset, literals, selectNodes);
    }
    
    /**
     * Parse WHERE clause into structured predicates
     */
    private WhereClause parseWhereClause(SqlNode condition) {
        List<Predicate> predicates = new ArrayList<>();
        extractPredicates(condition, predicates);
        return new WhereClause(predicates);
    }
    
    private void extractPredicates(SqlNode node, List<Predicate> predicates) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            String operator = call.getOperator().getName().toUpperCase();
            
            if (operator.equals("AND")) {
                // Recursively extract predicates from both sides
                for (SqlNode operand : call.getOperandList()) {
                    extractPredicates(operand, predicates);
                }
            } else if (operator.equals("OR")) {
                // OR operator - extract each branch separately
                List<List<Predicate>> orBranches = new ArrayList<>();
                for (SqlNode operand : call.getOperandList()) {
                    List<Predicate> branchPredicates = new ArrayList<>();
                    extractPredicates(operand, branchPredicates);
                    orBranches.add(branchPredicates);
                }
                // Wrap OR branches in a special marker
                predicates.add(new Predicate("__OR__", "OR", orBranches));
            } else if (operator.equals("NOT")) {
                // NOT operator - negate the inner predicate
                SqlNode inner = call.operand(0);
                List<Predicate> innerPredicates = new ArrayList<>();
                extractPredicates(inner, innerPredicates);
                for (Predicate p : innerPredicates) {
                    predicates.add(new Predicate(p.getColumn(), "NOT_" + p.getOperator(), p.getValue()));
                }
            } else if (operator.equals("=") || operator.equals(">") || 
                       operator.equals("<") || operator.equals(">=") || 
                       operator.equals("<=") || operator.equals("!=") || operator.equals("<>")) {
                // Binary comparison
                SqlNode left = call.operand(0);
                SqlNode right = call.operand(1);
                
                String column = left.toString();
                // Remove backticks that Calcite adds around identifiers
                column = column.replace("`", "");
                Object value = extractValue(right);
                
                // Normalize <> to !=
                if (operator.equals("<>")) {
                    operator = "!=";
                }
                
                predicates.add(new Predicate(column, operator, value));
            } else if (operator.equals("LIKE")) {
                // LIKE operator
                SqlNode left = call.operand(0);
                SqlNode right = call.operand(1);
                
                String column = left.toString().replace("`", "");
                Object pattern = extractValue(right);
                
                predicates.add(new Predicate(column, "LIKE", pattern));
            } else if (operator.equals("NOT LIKE")) {
                // NOT LIKE operator
                SqlNode left = call.operand(0);
                SqlNode right = call.operand(1);
                
                String column = left.toString().replace("`", "");
                Object pattern = extractValue(right);
                
                predicates.add(new Predicate(column, "NOT_LIKE", pattern));
            } else if (operator.equals("IN")) {
                // IN operator
                SqlNode left = call.operand(0);
                SqlNode right = call.operand(1);
                
                String column = left.toString().replace("`", "");
                // Extract list of values
                List<Object> values = new ArrayList<>();
                if (right instanceof SqlNodeList) {
                    SqlNodeList list = (SqlNodeList) right;
                    for (SqlNode item : list) {
                        values.add(extractValue(item));
                    }
                }
                
                predicates.add(new Predicate(column, "IN", values));
            } else if (operator.equals("NOT IN")) {
                // NOT IN operator
                SqlNode left = call.operand(0);
                SqlNode right = call.operand(1);
                
                String column = left.toString().replace("`", "");
                // Extract list of values
                List<Object> values = new ArrayList<>();
                if (right instanceof SqlNodeList) {
                    SqlNodeList list = (SqlNodeList) right;
                    for (SqlNode item : list) {
                        values.add(extractValue(item));
                    }
                }
                
                predicates.add(new Predicate(column, "NOT_IN", values));
            } else if (operator.contains("BETWEEN")) {
                // BETWEEN operator (can be "BETWEEN", "BETWEEN ASYMMETRIC", "BETWEEN SYMMETRIC")
                SqlNode column = call.operand(0);
                SqlNode lower = call.operand(1);
                SqlNode upper = call.operand(2);
                
                String columnName = column.toString().replace("`", "");
                Object lowerValue = extractValue(lower);
                Object upperValue = extractValue(upper);
                
                // Check if it's NOT BETWEEN
                boolean isNot = operator.startsWith("NOT");
                String opName = isNot ? "NOT_BETWEEN" : "BETWEEN";
                
                // Store as array [lower, upper]
                predicates.add(new Predicate(columnName, opName, new Object[]{lowerValue, upperValue}));
            } else if (operator.equals("IS NULL")) {
                // IS NULL operator
                SqlNode column = call.operand(0);
                predicates.add(new Predicate(column.toString().replace("`", ""), "IS_NULL", null));
            } else if (operator.equals("IS NOT NULL")) {
                // IS NOT NULL operator
                SqlNode column = call.operand(0);
                predicates.add(new Predicate(column.toString().replace("`", ""), "IS_NOT_NULL", null));
            }
        }
    }
    
    private OrderByColumn parseOrderBy(SqlNode orderNode) {
        // ORDER BY nodes are typically DESCENDING or column identifiers
        if (orderNode instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) orderNode;
            if (call.getOperator().getName().equals("DESC")) {
                String column = call.operand(0).toString();
                return new OrderByColumn(column, false);
            }
        }
        
        String column = orderNode.toString();
        return new OrderByColumn(column, true);
    }
    
    private String extractTableName(SqlNode from) {
        if (from instanceof SqlIdentifier) {
            return from.toString();
        } else if (from instanceof SqlBasicCall) {
            // Handle table with alias
            SqlBasicCall call = (SqlBasicCall) from;
            if (call.getOperator().getName().equals("AS")) {
                return call.operand(0).toString();
            }
        }
        return from.toString();
    }
    
    private List<Object> extractValues(SqlNode row) {
        List<Object> values = new ArrayList<>();
        
        if (row instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) row;
            for (SqlNode operand : call.getOperandList()) {
                values.add(extractValue(operand));
            }
        } else {
            values.add(extractValue(row));
        }
        
        return values;
    }
    
    private Object extractValue(SqlNode node) {
        // Handle SqlLiteral and its subclasses
        if (node instanceof SqlCharStringLiteral) {
            // String literal - extract the actual string value without quotes
            SqlCharStringLiteral strLiteral = (SqlCharStringLiteral) node;
            return strLiteral.getNlsString().getValue();
        } else if (node instanceof SqlNumericLiteral) {
            // Numeric literal
            SqlNumericLiteral numLiteral = (SqlNumericLiteral) node;
            Object value = numLiteral.getValue();
            // Convert BigDecimal to Integer or Long for better comparison
            if (value instanceof java.math.BigDecimal) {
                java.math.BigDecimal bd = (java.math.BigDecimal) value;
                if (bd.scale() == 0) {
                    // No decimal places - convert to integer type
                    try {
                        return bd.intValueExact();
                    } catch (ArithmeticException e) {
                        return bd.longValueExact();
                    }
                }
            }
            return value;
        } else if (node instanceof SqlLiteral) {
            // Other literals (boolean, null, etc.)
            SqlLiteral literal = (SqlLiteral) node;
            return literal.getValue();
        }
        
        // Fallback: parse node.toString()
        String str = node.toString();
        
        // Remove quotes if present (handles cases where SqlCharStringLiteral didn't match)
        if (str.startsWith("'") && str.endsWith("'") && str.length() >= 2) {
            return str.substring(1, str.length() - 1);
        }
        
        // Try to parse as number
        try {
            if (str.contains(".")) {
                return Double.parseDouble(str);
            } else {
                return Integer.parseInt(str);
            }
        } catch (NumberFormatException e) {
            // Not a number, return as-is (but without quotes)
            return str;
        }
    }
    
    private Integer extractIntValue(SqlNode node) {
        Object value = extractValue(node);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
    
    // Data classes for parsed information
    
    public static class InsertInfo {
        private final String tableName;
        private final List<String> columns;
        private final List<List<Object>> valuesList;
        
        public InsertInfo(String tableName, List<String> columns, List<List<Object>> valuesList) {
            this.tableName = tableName;
            this.columns = columns;
            this.valuesList = valuesList;
        }
        
        public String getTableName() { return tableName; }
        public List<String> getColumns() { return columns; }
        public List<List<Object>> getValuesList() { return valuesList; }
    }
    
    public static class UpdateInfo {
        private final String tableName;
        private final Map<String, UpdateExpression> setExpressions;
        private final WhereClause whereClause;
        
        public UpdateInfo(String tableName, Map<String, UpdateExpression> setExpressions, WhereClause whereClause) {
            this.tableName = tableName;
            this.setExpressions = setExpressions;
            this.whereClause = whereClause;
        }
        
        public String getTableName() { return tableName; }
        public Map<String, UpdateExpression> getSetExpressions() { return setExpressions; }
        public WhereClause getWhereClause() { return whereClause; }
    }
    
    /**
     * Public method to evaluate any SQL expression using Calcite AST
     * Supports: arithmetic, CASE, functions, literals, column references
     */
    public Object evaluateExpression(SqlNode node, Map<String, Object> row) {
        return evaluateExpressionInternal(node, row);
    }
    
    /**
     * Represents an expression in an UPDATE SET clause
     * Can be a literal value or an expression involving column references
     */
    public static class UpdateExpression {
        private final SqlNode expression;
        
        public UpdateExpression(SqlNode expression) {
            this.expression = expression;
        }
        
        /**
         * Evaluate the expression given the current row values
         */
        public Object evaluate(Map<String, Object> row) {
            return evaluateExpressionInternal(expression, row);
        }
    }
    
    /**
     * Internal expression evaluator - handles all SQL expression types
     */
    private static Object evaluateExpressionInternal(SqlNode node, Map<String, Object> row) {
        // Handle literals
        if (node instanceof SqlCharStringLiteral) {
            SqlCharStringLiteral strLiteral = (SqlCharStringLiteral) node;
            return strLiteral.getNlsString().getValue();
        } else if (node instanceof SqlNumericLiteral) {
            SqlNumericLiteral numLiteral = (SqlNumericLiteral) node;
            return numLiteral.getValue();
        } else if (node instanceof SqlLiteral) {
            SqlLiteral literal = (SqlLiteral) node;
            return literal.getValue();
        }
        
        // Handle column references
        if (node instanceof SqlIdentifier) {
            String columnName = node.toString().toLowerCase();
            // Remove backticks if present
            columnName = columnName.replace("`", "");
            Object value = row.get(columnName);
            if (value == null) {
                // Try without table prefix
                if (columnName.contains(".")) {
                    String unqualified = columnName.substring(columnName.lastIndexOf(".") + 1);
                    value = row.get(unqualified);
                }
            }
            return value;
        }
        
        // Handle CASE expression (SqlCase is a direct subclass of SqlCall, not SqlBasicCall)
        if (node.getKind() == SqlKind.CASE) {
            if (node instanceof SqlBasicCall) {
                return evaluateCaseExpression((SqlBasicCall) node, row);
            } else if (node instanceof SqlCall) {
                // SqlCase extends SqlCall directly
                return evaluateSqlCaseExpression((SqlCall) node, row);
            }
        }
        
        // Handle function calls and expressions
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            SqlKind kind = call.getOperator().getKind();
            
            // CASE already handled above
            if (kind == SqlKind.CASE) {
                return evaluateCaseExpression(call, row);
            }
            
            // Handle comparison operations
            if (kind == SqlKind.GREATER_THAN || kind == SqlKind.GREATER_THAN_OR_EQUAL ||
                kind == SqlKind.LESS_THAN || kind == SqlKind.LESS_THAN_OR_EQUAL ||
                kind == SqlKind.EQUALS || kind == SqlKind.NOT_EQUALS) {
                Object left = evaluateExpressionInternal(call.operand(0), row);
                Object right = evaluateExpressionInternal(call.operand(1), row);
                
                int cmp = compareValues(left, right);
                
                switch (kind) {
                    case GREATER_THAN:
                        return cmp > 0;
                    case GREATER_THAN_OR_EQUAL:
                        return cmp >= 0;
                    case LESS_THAN:
                        return cmp < 0;
                    case LESS_THAN_OR_EQUAL:
                        return cmp <= 0;
                    case EQUALS:
                        return cmp == 0;
                    case NOT_EQUALS:
                        return cmp != 0;
                    default:
                        throw new IllegalArgumentException("Unsupported comparison: " + kind);
                }
            }
            
            // Handle string concatenation (||)
            String operatorName = call.getOperator().getName().toUpperCase();
            if (operatorName.equals("||") || kind == SqlKind.OTHER && operatorName.contains("CONCAT")) {
                // String concatenation
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < call.getOperandList().size(); i++) {
                    Object operand = evaluateExpressionInternal(call.operand(i), row);
                    if (operand != null) {
                        result.append(operand.toString());
                    }
                }
                return result.toString();
            }
            
            // Handle arithmetic operations
            if (kind == SqlKind.PLUS || kind == SqlKind.MINUS || 
                kind == SqlKind.TIMES || kind == SqlKind.DIVIDE || kind == SqlKind.MOD) {
                Object left = evaluateExpressionInternal(call.operand(0), row);
                Object right = evaluateExpressionInternal(call.operand(1), row);
                
                double leftNum = toDouble(left);
                double rightNum = toDouble(right);
                
                switch (kind) {
                    case PLUS:
                        return leftNum + rightNum;
                    case MINUS:
                        return leftNum - rightNum;
                    case TIMES:
                        return leftNum * rightNum;
                    case DIVIDE:
                        if (rightNum == 0) {
                            throw new ArithmeticException("Division by zero");
                        }
                        return leftNum / rightNum;
                    case MOD:
                        return leftNum % rightNum;
                    default:
                        throw new IllegalArgumentException("Unsupported operator: " + kind);
                }
            }
            
            // Handle functions (ROUND, CEIL, FLOOR, POWER, SQRT, etc.)
            String funcName = call.getOperator().getName().toUpperCase();
            switch (funcName) {
                case "ROUND":
                    return evaluateRound(call, row);
                case "CEIL":
                case "CEILING":
                    return Math.ceil(toDouble(evaluateExpressionInternal(call.operand(0), row)));
                case "FLOOR":
                    return Math.floor(toDouble(evaluateExpressionInternal(call.operand(0), row)));
                case "POWER":
                case "POW":
                    double base = toDouble(evaluateExpressionInternal(call.operand(0), row));
                    double exp = toDouble(evaluateExpressionInternal(call.operand(1), row));
                    return Math.pow(base, exp);
                case "SQRT":
                    return Math.sqrt(toDouble(evaluateExpressionInternal(call.operand(0), row)));
                case "ABS":
                    return Math.abs(toDouble(evaluateExpressionInternal(call.operand(0), row)));
                case "COALESCE":
                    return evaluateCoalesce(call, row);
                default:
                    // Try to evaluate as expression
                    break;
            }
        }
        
        // Fallback: try to parse as literal
        String str = node.toString();
        if (str.startsWith("'") && str.endsWith("'") && str.length() >= 2) {
            return str.substring(1, str.length() - 1);
        }
        
        try {
            if (str.contains(".")) {
                return Double.parseDouble(str);
            } else {
                return Integer.parseInt(str);
            }
        } catch (NumberFormatException e) {
            return str;
        }
    }
    
    /**
     * Evaluate CASE expression (SqlCase extends SqlCall directly)
     */
    private static Object evaluateSqlCaseExpression(SqlCall caseNode, Map<String, Object> row) {
        // CASE has operands: [value (optional), when1, then1, when2, then2, ..., else (optional)]
        List<SqlNode> operands = caseNode.getOperandList();
        
        // SqlCase operands structure: [caseValue, whenList, thenList, elseValue]
        // - operand 0: case value (null for searched CASE)
        // - operand 1: SqlNodeList of WHEN conditions
        // - operand 2: SqlNodeList of THEN values
        // - operand 3: ELSE value (optional)
        
        if (operands.size() < 3) {
            return null;
        }
        
        SqlNode whenListNode = operands.get(1);
        SqlNode thenListNode = operands.get(2);
        SqlNode elseNode = operands.size() > 3 ? operands.get(3) : null;
        
        // whenListNode and thenListNode should be SqlNodeList
        if (!(whenListNode instanceof org.apache.calcite.sql.SqlNodeList) || 
            !(thenListNode instanceof org.apache.calcite.sql.SqlNodeList)) {
            return null;
        }
        
        org.apache.calcite.sql.SqlNodeList whenList = (org.apache.calcite.sql.SqlNodeList) whenListNode;
        org.apache.calcite.sql.SqlNodeList thenList = (org.apache.calcite.sql.SqlNodeList) thenListNode;
        
        // Evaluate WHEN clauses
        for (int i = 0; i < whenList.size() && i < thenList.size(); i++) {
            SqlNode condition = whenList.get(i);
            SqlNode result = thenList.get(i);
            
            // Evaluate condition as boolean
            Object condResult = evaluateExpressionInternal(condition, row);
            boolean conditionMet = toBoolean(condResult);
            
            if (conditionMet) {
                return evaluateExpressionInternal(result, row);
            }
        }
        
        // No condition matched, return ELSE value
        if (elseNode != null) {
            return evaluateExpressionInternal(elseNode, row);
        }
        
        return null;
    }
    
    /**
     * Evaluate CASE expression (SqlBasicCall version)
     */
    private static Object evaluateCaseExpression(SqlBasicCall caseNode, Map<String, Object> row) {
        // CASE has operands: [value (optional), when1, then1, when2, then2, ..., else (optional)]
        List<SqlNode> operands = caseNode.getOperandList();
        
        int startIdx = 0;
        SqlNode caseValue = null;
        
        // CASE expression structure in Calcite:
        // For searched CASE: [when1_condition, then1_value, when2_condition, then2_value, ..., else_value]
        // For simple CASE: [case_value, when1_value, then1_value, when2_value, then2_value, ..., else_value]
        
        // Try to detect if it's simple CASE by checking if first operand looks like a value expression
        // This is a heuristic - in practice, Calcite usually structures CASE consistently
        
        // For now, assume searched CASE (CASE WHEN condition THEN value)
        // Operands come in pairs: condition, result, condition, result, ..., [optional else]
        
        // Evaluate WHEN clauses
        for (int i = startIdx; i < operands.size(); i++) {
            SqlNode operand = operands.get(i);
            
            if (operand == null) {
                // Null operand - skip
                continue;
            }
            
            // Operands come in pairs: condition, result
            if (i + 1 < operands.size()) {
                SqlNode condition = operands.get(i);
                SqlNode result = operands.get(i + 1);
                
                boolean conditionMet = false;
                if (caseValue != null) {
                    // Simple CASE: compare caseValue with condition
                    Object caseVal = evaluateExpressionInternal(caseValue, row);
                    Object condVal = evaluateExpressionInternal(condition, row);
                    conditionMet = Objects.equals(caseVal, condVal);
                } else {
                    // Searched CASE: evaluate condition as boolean
                    Object condResult = evaluateExpressionInternal(condition, row);
                    conditionMet = toBoolean(condResult);
                }
                
                if (conditionMet) {
                    return evaluateExpressionInternal(result, row);
                }
                
                i++; // Skip the result operand in next iteration
            }
        }
        
        // No condition matched, check for ELSE
        if (operands.size() % 2 == 1) {
            // Odd number of operands means there's an ELSE
            return evaluateExpressionInternal(operands.get(operands.size() - 1), row);
        }
        
        return null;
    }
    
    /**
     * Evaluate ROUND function
     */
    private static Object evaluateRound(SqlBasicCall call, Map<String, Object> row) {
        double value = toDouble(evaluateExpressionInternal(call.operand(0), row));
        if (call.getOperandList().size() > 1) {
            int decimals = ((Number) evaluateExpressionInternal(call.operand(1), row)).intValue();
            double multiplier = Math.pow(10, decimals);
            return Math.round(value * multiplier) / multiplier;
        }
        return Math.round(value);
    }
    
    /**
     * Evaluate COALESCE function
     */
    private static Object evaluateCoalesce(SqlBasicCall call, Map<String, Object> row) {
        for (SqlNode operand : call.getOperandList()) {
            Object value = evaluateExpressionInternal(operand, row);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
    
    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        return value != null;
    }
    
    private static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot convert to number: " + value);
        }
    }
    
    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        // Handle null values
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        
        // Handle numeric comparisons (convert both to double for comparison)
        if (a instanceof Number && b instanceof Number) {
            double aVal = ((Number) a).doubleValue();
            double bVal = ((Number) b).doubleValue();
            return Double.compare(aVal, bVal);
        }
        
        // Handle comparable types of the same class
        if (a instanceof Comparable && b instanceof Comparable && a.getClass().equals(b.getClass())) {
            try {
                return ((Comparable) a).compareTo(b);
            } catch (ClassCastException e) {
                // Fall through to string comparison
            }
        }
        
        // Default: string comparison
        return a.toString().compareTo(b.toString());
    }
    
    public static class DeleteInfo {
        private final String tableName;
        private final WhereClause whereClause;
        
        public DeleteInfo(String tableName, WhereClause whereClause) {
            this.tableName = tableName;
            this.whereClause = whereClause;
        }
        
        public String getTableName() { return tableName; }
        public WhereClause getWhereClause() { return whereClause; }
    }
    
    public static class SelectInfo {
        private final String tableName;
        private final List<String> columns;
        private final WhereClause whereClause;
        private final List<OrderByColumn> orderBy;
        private final Integer limit;
        private final Integer offset;
        private final List<LiteralColumn> literals;
        private final List<SqlNode> selectNodes;  // Store original SqlNode objects for proper evaluation
        
        public SelectInfo(String tableName, List<String> columns, WhereClause whereClause,
                         List<OrderByColumn> orderBy, Integer limit) {
            this(tableName, columns, whereClause, orderBy, limit, null, Collections.emptyList(), Collections.emptyList());
        }
        
        public SelectInfo(String tableName, List<String> columns, WhereClause whereClause,
                         List<OrderByColumn> orderBy, Integer limit, List<LiteralColumn> literals) {
            this(tableName, columns, whereClause, orderBy, limit, null, literals, Collections.emptyList());
        }
        
        public SelectInfo(String tableName, List<String> columns, WhereClause whereClause,
                         List<OrderByColumn> orderBy, Integer limit, List<LiteralColumn> literals, List<SqlNode> selectNodes) {
            this(tableName, columns, whereClause, orderBy, limit, null, literals, selectNodes);
        }
        
        public SelectInfo(String tableName, List<String> columns, WhereClause whereClause,
                         List<OrderByColumn> orderBy, Integer limit, Integer offset, List<LiteralColumn> literals, List<SqlNode> selectNodes) {
            this.tableName = tableName;
            this.columns = columns;
            this.whereClause = whereClause;
            this.orderBy = orderBy;
            this.limit = limit;
            this.offset = offset;
            this.literals = literals != null ? literals : Collections.emptyList();
            this.selectNodes = selectNodes != null ? selectNodes : Collections.emptyList();
        }
        
        public String getTableName() { return tableName; }
        public List<String> getColumns() { return columns; }
        public WhereClause getWhereClause() { return whereClause; }
        public List<OrderByColumn> getOrderBy() { return orderBy; }
        public Integer getLimit() { return limit; }
        public Integer getOffset() { return offset; }
        public List<LiteralColumn> getLiterals() { return literals; }
        public List<SqlNode> getSelectNodes() { return selectNodes; }
    }
    
    public static class LiteralColumn {
        private final String alias;
        private final Object value;
        private final int position; // Position in SELECT list
        
        public LiteralColumn(String alias, Object value, int position) {
            this.alias = alias;
            this.value = value;
            this.position = position;
        }
        
        public String getAlias() { return alias; }
        public Object getValue() { return value; }
        public int getPosition() { return position; }
    }
    
    public static class WhereClause {
        private final List<Predicate> predicates;
        
        public WhereClause(List<Predicate> predicates) {
            this.predicates = predicates;
        }
        
        public List<Predicate> getPredicates() { return predicates; }
        
        public boolean isEmpty() {
            return predicates == null || predicates.isEmpty();
        }
    }
    
    public static class Predicate {
        private final String column;
        private final String operator;
        private final Object value;
        
        public Predicate(String column, String operator, Object value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
        
        public String getColumn() { return column; }
        public String getOperator() { return operator; }
        public Object getValue() { return value; }
        
        public boolean evaluate(Object rowValue) {
            // Handle IS NULL / IS NOT NULL first (rowValue can be null)
            if (operator.equals("IS_NULL")) {
                return rowValue == null;
            }
            if (operator.equals("IS_NOT_NULL")) {
                return rowValue != null;
            }
            
            // For other operators, null values don't match
            if (rowValue == null) {
                return false;
            }
            
            switch (operator) {
                case "=":
                    return compareValues(rowValue, value) == 0;
                case "!=":
                    return compareValues(rowValue, value) != 0;
                case ">":
                    return compareValues(rowValue, value) > 0;
                case ">=":
                    return compareValues(rowValue, value) >= 0;
                case "<":
                    return compareValues(rowValue, value) < 0;
                case "<=":
                    return compareValues(rowValue, value) <= 0;
                    
                case "LIKE":
                    return evaluateLike(rowValue, value);
                    
                case "NOT_LIKE":
                    return !evaluateLike(rowValue, value);
                    
                case "IN":
                    if (value instanceof List) {
                        List<?> values = (List<?>) value;
                        for (Object v : values) {
                            if (compareValues(rowValue, v) == 0) {
                                return true;
                            }
                        }
                    }
                    return false;
                    
                case "NOT_IN":
                    if (value instanceof List) {
                        List<?> values = (List<?>) value;
                        for (Object v : values) {
                            if (compareValues(rowValue, v) == 0) {
                                return false;
                            }
                        }
                        return true;
                    }
                    return false;
                    
                case "BETWEEN":
                    if (value instanceof Object[]) {
                        Object[] range = (Object[]) value;
                        if (range.length == 2) {
                            return compareValues(rowValue, range[0]) >= 0 && 
                                   compareValues(rowValue, range[1]) <= 0;
                        }
                    }
                    return false;
                    
                case "NOT_BETWEEN":
                    if (value instanceof Object[]) {
                        Object[] range = (Object[]) value;
                        if (range.length == 2) {
                            return compareValues(rowValue, range[0]) < 0 || 
                                   compareValues(rowValue, range[1]) > 0;
                        }
                    }
                    return false;
                    
                // Handle NOT_ prefixed operators
                case "NOT_=":
                    return compareValues(rowValue, value) != 0;
                case "NOT_>":
                    return compareValues(rowValue, value) <= 0;
                case "NOT_<":
                    return compareValues(rowValue, value) >= 0;
                case "NOT_>=":
                    return compareValues(rowValue, value) < 0;
                case "NOT_<=":
                    return compareValues(rowValue, value) > 0;
                case "NOT_!=":
                    return compareValues(rowValue, value) == 0;
                    
                default:
                    return false;
            }
        }
        
        /**
         * Evaluate LIKE pattern matching
         * Supports % (any characters) and _ (single character)
         */
        private boolean evaluateLike(Object rowValue, Object pattern) {
            String str = rowValue.toString();
            String pat = pattern.toString();
            
            // Convert SQL LIKE pattern to Java regex
            // Escape regex special chars except % and _
            String regex = pat
                .replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("+", "\\+")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("|", "\\|")
                // Now convert SQL wildcards to regex
                .replace("%", ".*")
                .replace("_", ".");
            
            return str.matches(regex);
        }
        
        @SuppressWarnings("unchecked")
        private int compareValues(Object a, Object b) {
            // Handle null values
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            
            // Handle numeric comparisons (convert both to double for comparison)
            if (a instanceof Number && b instanceof Number) {
                double aVal = ((Number) a).doubleValue();
                double bVal = ((Number) b).doubleValue();
                return Double.compare(aVal, bVal);
            }
            
            // Handle comparable types of the same class
            if (a instanceof Comparable && b instanceof Comparable && a.getClass().equals(b.getClass())) {
                try {
                    return ((Comparable) a).compareTo(b);
                } catch (ClassCastException e) {
                    // Fall through to string comparison
                }
            }
            
            // Default: string comparison
            return a.toString().compareTo(b.toString());
        }
    }
    
    public static class OrderByColumn {
        private final String column;
        private final boolean ascending;
        
        public OrderByColumn(String column, boolean ascending) {
            this.column = column;
            this.ascending = ascending;
        }
        
        public String getColumn() { return column; }
        public boolean isAscending() { return ascending; }
    }
    
    // End of nested classes - close CalciteSqlParser class
}

