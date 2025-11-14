package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes SQL queries to extract structure and optimization opportunities
 */
@Component
public class QueryAnalyzer {
    
    /**
     * Analyze a parsed query to extract predicates and structure
     */
    public QueryAnalysis analyze(String sql, ParsedQuery parsedQuery) throws SqlParseException {
        System.out.println("=== QUERY ANALYSIS ===");
        System.out.println("SQL: " + sql);
        
        // For multi-way JOIN queries
        if (parsedQuery.isMultiWayJoin()) {
            return analyzeMultiWayJoinQuery(sql, parsedQuery.getMultiWayJoin());
        }
        
        // For binary JOIN queries
        if (parsedQuery.isJoin()) {
            return analyzeJoinQuery(sql, parsedQuery.getJoinQuery());
        }
        
        // For simple SELECT queries
        if (parsedQuery.getType() == ParsedQuery.Type.SELECT) {
            return analyzeSelectQuery(sql);
        }
        
        // For other query types, return empty analysis
        return new QueryAnalysis();
    }
    
    /**
     * Analyze JOIN query with WHERE clause
     */
    private QueryAnalysis analyzeJoinQuery(String sql, JoinQuery joinQuery) throws SqlParseException {
        // Parse SQL with Calcite
        SqlParser.Config config = SqlParser.config()
            .withCaseSensitive(false)
            .withParserFactory(SqlDdlParserImpl.FACTORY);
        
        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlSelect)) {
            return new QueryAnalysis();
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        // Extract table aliases
        Map<String, String> aliases = new HashMap<>();
        List<String> tables = new ArrayList<>();
        
        tables.add(joinQuery.getLeftTable());
        tables.add(joinQuery.getRightTable());
        
        // Extract aliases from JOIN (e.g., "users u" -> u -> users)
        SqlNode from = select.getFrom();
        if (from instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) from;
            extractAliases(join.getLeft(), aliases);
            extractAliases(join.getRight(), aliases);
        }
        
        System.out.println("Table aliases: " + aliases);
        
        // Extract WHERE clause predicates
        List<Predicate> predicates = new ArrayList<>();
        SqlNode where = select.getWhere();
        
        if (where != null) {
            System.out.println("Found WHERE clause: " + where);
            extractPredicates(where, predicates, aliases);
            System.out.println("Extracted " + predicates.size() + " predicates");
            for (Predicate pred : predicates) {
                System.out.println("  - " + pred);
            }
        } else {
            System.out.println("No WHERE clause found");
        }
        
        // Extract SELECT columns
        List<String> selectColumns = extractSelectColumns(select);
        
        System.out.println("======================\n");
        
        return new QueryAnalysis(tables, aliases, predicates, selectColumns, joinQuery);
    }
    
    /**
     * Analyze simple SELECT query
     */
    private QueryAnalysis analyzeSelectQuery(String sql) throws SqlParseException {
        SqlParser.Config config = SqlParser.config()
            .withCaseSensitive(false)
            .withParserFactory(SqlDdlParserImpl.FACTORY);
        
        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlSelect)) {
            return new QueryAnalysis();
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        List<String> tables = new ArrayList<>();
        Map<String, String> aliases = new HashMap<>();
        List<Predicate> predicates = new ArrayList<>();
        
        // Extract table
        SqlNode from = select.getFrom();
        if (from instanceof SqlIdentifier) {
            tables.add(((SqlIdentifier) from).getSimple());
        }
        
        // Extract WHERE predicates
        SqlNode where = select.getWhere();
        if (where != null) {
            extractPredicates(where, predicates, aliases);
        }
        
        List<String> selectColumns = extractSelectColumns(select);
        
        return new QueryAnalysis(tables, aliases, predicates, selectColumns, null);
    }
    
    /**
     * Analyze multi-way JOIN query with WHERE clause
     */
    private QueryAnalysis analyzeMultiWayJoinQuery(String sql, MultiWayJoinQuery multiWayJoin) throws SqlParseException {
        // Parse SQL with Calcite
        SqlParser.Config config = SqlParser.config()
            .withCaseSensitive(false)
            .withParserFactory(SqlDdlParserImpl.FACTORY);
        
        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();
        
        if (!(sqlNode instanceof SqlSelect)) {
            return new QueryAnalysis();
        }
        
        SqlSelect select = (SqlSelect) sqlNode;
        
        // Use aliases from MultiWayJoinQuery
        Map<String, String> aliases = multiWayJoin.getTableAliases();
        List<String> tables = multiWayJoin.getTables();
        
        System.out.println("Table aliases: " + aliases);
        
        // Extract WHERE clause predicates
        List<Predicate> predicates = new ArrayList<>();
        SqlNode where = select.getWhere();
        
        if (where != null) {
            System.out.println("Found WHERE clause: " + where);
            extractPredicates(where, predicates, aliases);
            System.out.println("Extracted " + predicates.size() + " predicates");
            for (Predicate pred : predicates) {
                System.out.println("  - " + pred);
            }
        } else {
            System.out.println("No WHERE clause found");
        }
        
        // Extract SELECT columns
        List<String> selectColumns = multiWayJoin.getSelectColumns();
        
        System.out.println("======================\n");
        
        return new QueryAnalysis(tables, aliases, predicates, selectColumns, null);
    }
    
    /**
     * Extract table aliases from JOIN nodes
     */
    private void extractAliases(SqlNode node, Map<String, String> aliases) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            // Handle "table AS alias" or "table alias"
            if (call.getOperator().getName().equals("AS") || 
                call.getOperandList().size() == 2) {
                SqlNode table = call.getOperandList().get(0);
                SqlNode alias = call.getOperandList().get(1);
                
                if (table instanceof SqlIdentifier && alias instanceof SqlIdentifier) {
                    String tableName = ((SqlIdentifier) table).getSimple();
                    String aliasName = ((SqlIdentifier) alias).getSimple();
                    aliases.put(aliasName, tableName);
                    System.out.println("Found alias: " + aliasName + " -> " + tableName);
                }
            }
        }
    }
    
    /**
     * Extract predicates from WHERE clause
     */
    private void extractPredicates(SqlNode node, List<Predicate> predicates, 
                                   Map<String, String> aliases) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            String opName = call.getOperator().getName();
            
            // Handle AND - recursively extract both sides
            if (opName.equals("AND")) {
                for (SqlNode operand : call.getOperandList()) {
                    extractPredicates(operand, predicates, aliases);
                }
                return;
            }
            
            // Handle comparison operators
            Predicate.Operator operator = mapOperator(opName);
            if (operator != null && call.getOperandList().size() >= 2) {
                SqlNode left = call.getOperandList().get(0);
                SqlNode right = call.getOperandList().get(1);
                
                // Extract column reference (e.g., u.name)
                if (left instanceof SqlIdentifier) {
                    SqlIdentifier id = (SqlIdentifier) left;
                    String tableName = null;
                    String columnName = null;
                    
                    if (id.names.size() == 2) {
                        // Qualified: table.column
                        tableName = id.names.get(0);
                        columnName = id.names.get(1);
                    } else if (id.names.size() == 1) {
                        // Unqualified: column
                        columnName = id.names.get(0);
                    }
                    
                    // Extract value
                    Object value = extractValue(right);
                    
                    if (columnName != null) {
                        Predicate pred = new Predicate(
                            tableName,
                            columnName,
                            operator,
                            value,
                            node.toString()
                        );
                        predicates.add(pred);
                    }
                }
            }
        }
    }
    
    /**
     * Map SQL operator to Predicate.Operator
     */
    private Predicate.Operator mapOperator(String sqlOp) {
        switch (sqlOp.toUpperCase()) {
            case "=":
                return Predicate.Operator.EQUALS;
            case "<>":
            case "!=":
                return Predicate.Operator.NOT_EQUALS;
            case ">":
                return Predicate.Operator.GREATER_THAN;
            case ">=":
                return Predicate.Operator.GREATER_EQUAL;
            case "<":
                return Predicate.Operator.LESS_THAN;
            case "<=":
                return Predicate.Operator.LESS_EQUAL;
            case "IN":
                return Predicate.Operator.IN;
            case "LIKE":
                return Predicate.Operator.LIKE;
            default:
                return null;
        }
    }
    
    /**
     * Extract value from SqlNode
     */
    private Object extractValue(SqlNode node) {
        if (node instanceof SqlLiteral) {
            SqlLiteral literal = (SqlLiteral) node;
            return literal.getValue();
        }
        return node.toString().replace("'", "");
    }
    
    /**
     * Extract SELECT columns
     */
    private List<String> extractSelectColumns(SqlSelect select) {
        List<String> columns = new ArrayList<>();
        
        for (SqlNode node : select.getSelectList()) {
            if (node instanceof SqlIdentifier) {
                SqlIdentifier id = (SqlIdentifier) node;
                columns.add(String.join(".", id.names));
            }
        }
        
        return columns;
    }
}

