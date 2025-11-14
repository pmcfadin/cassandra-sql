package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlCharStringLiteral;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl;
import com.geico.poc.cassandrasql.window.WindowFunctionParser;
import com.geico.poc.cassandrasql.window.WindowSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CalciteParser {
    
    private static final Logger log = LoggerFactory.getLogger(CalciteParser.class);

    public ParsedQuery parse(String sql) throws SqlParseException {
        log.info("🚀 CalciteParser.parse() called with SQL: {}", sql.substring(0, Math.min(100, sql.length())));
        
        // Strip trailing semicolon (psql sends queries with semicolons)
        String originalSql = sql;
        sql = sql.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
            System.out.println("Stripped semicolon from SQL: " + originalSql.length() + " -> " + sql.length() + " chars");
        }
        
        // Extract query type first
        String sqlUpper = sql.trim().toUpperCase();
        ParsedQuery.Type type;

        // Handle EXPLAIN queries
        if (sqlUpper.startsWith("EXPLAIN")) {
            return parseExplainQuery(sql);
        }
        
        // Handle transaction commands
        if (sqlUpper.equals("BEGIN") || sqlUpper.equals("BEGIN TRANSACTION") || sqlUpper.equals("START TRANSACTION")) {
            return new ParsedQuery(ParsedQuery.Type.BEGIN, "", sql);
        } else if (sqlUpper.equals("COMMIT")) {
            return new ParsedQuery(ParsedQuery.Type.COMMIT, "", sql);
        } else if (sqlUpper.equals("ROLLBACK") || sqlUpper.equals("ABORT")) {
            return new ParsedQuery(ParsedQuery.Type.ROLLBACK, "", sql);
        }

        if (sqlUpper.startsWith("SELECT")) {
            // Check for UNION first (before aggregation check)
            // UNION queries may contain aggregates but should be treated as regular SELECTs
            // Check for UNION with various whitespace (space, newline, tab)
            // Use (?s) for DOTALL mode to match newlines
            if (sqlUpper.matches("(?s).*\\sUNION(\\s+ALL)?\\s.*")) {
                log.debug("UNION query detected, treating as regular SELECT");
                type = ParsedQuery.Type.SELECT;
                String tableName = extractTableName(sql);
                return new ParsedQuery(type, tableName, sql);
            }
            // Check if it has window functions (must check first)
            if (WindowFunctionParser.hasWindowFunctions(sql)) {
                return parseWindowFunctionQuery(sql);
            }
            // Check for FROM subqueries (derived tables) - must check early
            // Parse with Calcite to inspect the FROM clause
            log.info("🔍 Checking for FROM subquery in SQL: {}", sql.substring(0, Math.min(100, sql.length())));
            try {
                SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
                SqlParser parser = SqlParser.create(sql, config);
                SqlNode sqlNode = parser.parseStmt();
                log.info("🔍 Parsed SQL node type: {}", sqlNode.getClass().getSimpleName());
                
                // Unwrap SqlOrderBy if present
                if (sqlNode instanceof SqlOrderBy) {
                    sqlNode = ((SqlOrderBy) sqlNode).query;
                }
                
                if (sqlNode instanceof SqlSelect) {
                    SqlSelect select = (SqlSelect) sqlNode;
                    if (select.getFrom() != null && hasFromSubquery(select.getFrom())) {
                        log.info("🔍 FROM subquery detected in CalciteParser");
                        // Still need to determine if it's a JOIN, aggregation, etc.
                        // For now, mark it and let the executor handle it
                        type = ParsedQuery.Type.SELECT;
                        ParsedQuery result = new ParsedQuery(type, "derived_table", sql);
                        log.info("🔍 Returning ParsedQuery with type={}, table={}", type, "derived_table");
                        return result;
                    }
                }
            } catch (Exception e) {
                // If parsing fails, continue with other checks
                log.info("⚠️ Failed to check for FROM subquery: {}", e.getMessage(), e);
            }
            // Check if it has subqueries (must check before aggregation to avoid infinite loops)
            // Subqueries may contain aggregates, and should be handled by SubqueryExecutor
            if (hasSubquery(sql)) {
                type = ParsedQuery.Type.SELECT_WITH_SUBQUERY;
                String tableName = extractTableName(sql);
                return new ParsedQuery(type, tableName, sql);
            }
            // Check if it has GROUP BY or aggregate functions (after subquery check)
            if (sqlUpper.contains(" GROUP BY ") || hasAggregateFunction(sql)) {
                return parseAggregationQuery(sql);
            }
            // Check if it's a JOIN query
            if (sqlUpper.contains(" JOIN ")) {
                return parseJoinQuery(sql);
            }
            type = ParsedQuery.Type.SELECT;
        } else if (sqlUpper.startsWith("INSERT")) {
            type = ParsedQuery.Type.INSERT;
        } else if (sqlUpper.startsWith("CREATE TABLE")) {
            type = ParsedQuery.Type.CREATE_TABLE;
            // For CREATE TABLE, skip Calcite validation due to Cassandra-specific syntax
            String tableName = extractTableName(sql);
            return new ParsedQuery(type, tableName, sql);
        } else if (sqlUpper.startsWith("CREATE INDEX") || sqlUpper.startsWith("CREATE UNIQUE INDEX")) {
            type = ParsedQuery.Type.CREATE_INDEX;
            // For CREATE INDEX, skip Calcite validation and pass through
            String tableName = extractTableNameFromIndex(sql);
            return new ParsedQuery(type, tableName, sql);
        } else if (sqlUpper.startsWith("DROP TABLE")) {
            type = ParsedQuery.Type.DROP_TABLE;
            // For DROP TABLE, skip Calcite validation and pass through
            List<String> tableNames = extractTableNamesFromDrop(sql);
            // Return first table as primary (for backwards compatibility)
            String tableName = tableNames.isEmpty() ? "unknown" : tableNames.get(0);
            ParsedQuery query = new ParsedQuery(type, tableName, sql);
            // Store all table names for multi-table drop
            query.setDropTableNames(tableNames);
            return query;
        } else if (sqlUpper.startsWith("CREATE MATERIALIZED VIEW")) {
            type = ParsedQuery.Type.CREATE_MATERIALIZED_VIEW;
            // Skip Calcite validation for CREATE MATERIALIZED VIEW
            String viewName = extractViewName(sql);
            return new ParsedQuery(type, viewName, sql);
        } else if (sqlUpper.startsWith("CREATE VIEW")) {
            type = ParsedQuery.Type.CREATE_VIEW;
            // Skip Calcite validation for CREATE VIEW
            String viewName = extractViewName(sql);
            return new ParsedQuery(type, viewName, sql);
        } else if (sqlUpper.startsWith("DROP MATERIALIZED VIEW") || sqlUpper.startsWith("DROP VIEW")) {
            type = ParsedQuery.Type.DROP_VIEW;
            // Skip Calcite validation for DROP VIEW
            String viewName = extractViewNameFromDrop(sql);
            return new ParsedQuery(type, viewName, sql);
        } else if (sqlUpper.startsWith("REFRESH MATERIALIZED VIEW")) {
            type = ParsedQuery.Type.REFRESH_MATERIALIZED_VIEW;
            // Skip Calcite validation for REFRESH MATERIALIZED VIEW
            String viewName = extractViewNameFromRefresh(sql);
            return new ParsedQuery(type, viewName, sql);
        } else if (sqlUpper.startsWith("CREATE TYPE")) {
            type = ParsedQuery.Type.CREATE_TYPE;
            // Skip Calcite validation for CREATE TYPE (custom ENUM syntax)
            String typeName = extractTypeName(sql);
            return new ParsedQuery(type, typeName, sql);
        } else if (sqlUpper.startsWith("CREATE SEQUENCE")) {
            type = ParsedQuery.Type.CREATE_SEQUENCE;
            // Skip Calcite validation for CREATE SEQUENCE
            return new ParsedQuery(type, "sequence", sql);
        } else if (sqlUpper.startsWith("DROP TYPE")) {
            type = ParsedQuery.Type.DROP_TYPE;
            // Skip Calcite validation for DROP TYPE
            String typeName = extractTypeNameFromDrop(sql);
            return new ParsedQuery(type, typeName, sql);
        } else if (sqlUpper.startsWith("DROP SEQUENCE")) {
            type = ParsedQuery.Type.DROP_SEQUENCE;
            // Skip Calcite validation for DROP SEQUENCE
            return new ParsedQuery(type, "sequence", sql);
        } else if (sqlUpper.startsWith("TRUNCATE")) {
            type = ParsedQuery.Type.TRUNCATE;
            // For TRUNCATE, skip Calcite validation and pass through
            List<String> tableNames = extractTableNamesFromTruncate(sql);
            // For now, return first table (we'll handle multiple tables in executor)
            String tableName = tableNames.isEmpty() ? "unknown" : tableNames.get(0);
            ParsedQuery query = new ParsedQuery(type, tableName, sql);
            // Store all table names for multi-table truncate
            query.setTruncateTableNames(tableNames);
            return query;
        } else if (sqlUpper.startsWith("UPDATE")) {
            type = ParsedQuery.Type.UPDATE;
        } else if (sqlUpper.startsWith("DELETE")) {
            type = ParsedQuery.Type.DELETE;
        } else if (sqlUpper.startsWith("VACUUM")) {
            // VACUUM or VACUUM ANALYZE - treat as no-op in KV mode
            // In KV mode, VacuumJob handles cleanup automatically
            type = ParsedQuery.Type.VACUUM;
            return new ParsedQuery(type, "vacuum", sql);
        } else if (sqlUpper.startsWith("ANALYZE")) {
            // ANALYZE - treat as no-op in KV mode
            // In KV mode, StatisticsCollectorJob handles this automatically
            type = ParsedQuery.Type.ANALYZE;
            return new ParsedQuery(type, "analyze", sql);
        } else if (sqlUpper.startsWith("COPY")) {
            type = ParsedQuery.Type.COPY;
            // For COPY, skip Calcite validation (PostgreSQL-specific command)
            String tableName = extractTableNameFromCopy(sql);
            return new ParsedQuery(type, tableName, sql);
        } else if (sqlUpper.startsWith("ALTER TABLE")) {
            // ALTER TABLE - parse using Calcite DDL parser
            type = ParsedQuery.Type.ALTER_TABLE;
            return parseAlterTable(sql);
        } else if (sqlUpper.startsWith("SET ")) {
            // SET - PostgreSQL session configuration command
            // These are sent by JDBC driver during connection initialization
            // We'll treat them as no-ops but acknowledge them
            type = ParsedQuery.Type.SET;
            return new ParsedQuery(type, "set", sql);
        } else {
            throw new IllegalArgumentException("Unsupported SQL type: " + sql);
        }

        // For non-CREATE TABLE queries, validate with Calcite
        try {
            // Use standard parser for DML, DDL parser only for CREATE/DROP/ALTER
            boolean isDDL = sqlUpper.startsWith("CREATE ") || 
                           sqlUpper.startsWith("DROP ") || 
                           sqlUpper.startsWith("ALTER ");
            
            SqlParser.Config config;
            if (isDDL) {
                config = SqlParser.config()
                    .withCaseSensitive(false)
                    .withParserFactory(SqlDdlParserImpl.FACTORY);
            } else {
                config = SqlParser.config()
                    .withCaseSensitive(false);
            }

            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();

            log.debug("Parsed SQL node type: " + sqlNode.getKind());
        } catch (SqlParseException e) {
            // Log warning but continue (Cassandra might accept it)
            System.out.println("Warning: Calcite parse failed, passing through to Cassandra: " + e.getMessage());
        }

        String tableName = extractTableName(sql);
        return new ParsedQuery(type, tableName, sql);
    }

    /**
     * Parse JOIN query using Calcite
     */
    private ParsedQuery parseJoinQuery(String sql) throws SqlParseException {
        System.out.println("parseJoinQuery received SQL (length=" + sql.length() + "): " + sql);
        System.out.println("Last char: '" + (sql.isEmpty() ? "" : sql.charAt(sql.length() - 1)) + "' (code: " + (sql.isEmpty() ? 0 : (int)sql.charAt(sql.length() - 1)) + ")");
        
        // Use standard parser for SELECT queries (including JOINs)
        SqlParser.Config config = SqlParser.config()
            .withCaseSensitive(false);

        SqlParser parser = SqlParser.create(sql, config);
        SqlNode sqlNode = parser.parseStmt();

        log.debug("Parsed SQL node type: " + sqlNode.getKind());

        // Handle SqlOrderBy wrapping SqlSelect (ORDER BY at top level)
        SqlOrderBy topLevelOrderBy = null;
        if (sqlNode instanceof SqlOrderBy) {
            topLevelOrderBy = (SqlOrderBy) sqlNode;
            sqlNode = topLevelOrderBy.query;
            System.out.println("Unwrapped SqlOrderBy, inner node type: " + sqlNode.getKind());
        }

        if (!(sqlNode instanceof SqlSelect)) {
            throw new IllegalArgumentException("Expected SELECT with JOIN, got: " + sqlNode.getClass().getSimpleName());
        }

        SqlSelect select = (SqlSelect) sqlNode;
        SqlNode from = select.getFrom();

        if (!(from instanceof SqlJoin)) {
            throw new IllegalArgumentException("Expected JOIN in FROM clause");
        }

        SqlJoin join = (SqlJoin) from;
        
        // Check if this is a multi-way join (3+ tables)
        int tableCount = countTablesInJoin(join);
        System.out.println("Detected " + tableCount + " tables in JOIN");
        
        if (tableCount >= 3) {
            return parseMultiWayJoin(sql, select, join);
        }

        // Extract join information
        String leftTable = extractTableFromNode(join.getLeft());
        String rightTable = extractTableFromNode(join.getRight());

        // Extract join condition (e.g., u.id = o.user_id)
        // CROSS JOIN has no condition
        SqlNode condition = join.getCondition();
        SimpleJoinCondition joinCond = null;
        if (condition != null) {
            joinCond = extractSimpleJoinCondition(condition);
        }

        // Extract select columns
        List<String> selectColumns = extractSelectColumns(select);

        // Determine join type
        JoinQuery.JoinType joinType = JoinQuery.JoinType.INNER;
        switch (join.getJoinType()) {
            case LEFT:
                joinType = JoinQuery.JoinType.LEFT;
                break;
            case RIGHT:
                joinType = JoinQuery.JoinType.RIGHT;
                break;
            case FULL:
                joinType = JoinQuery.JoinType.FULL;
                break;
            case CROSS:
            case COMMA:  // Calcite treats "FROM a, b" as COMMA join (equivalent to CROSS)
                joinType = JoinQuery.JoinType.CROSS;
                break;
            default:
                joinType = JoinQuery.JoinType.INNER;
        }

        // Extract WHERE clause
        String whereClause = null;
        SqlNode whereNode = select.getWhere();
        if (whereNode != null) {
            whereClause = whereNode.toString();
            System.out.println("Extracted WHERE clause: " + whereClause);
        }

        // For CROSS JOIN, join keys are null
        String leftKey = joinCond != null ? joinCond.leftKey : null;
        String rightKey = joinCond != null ? joinCond.rightKey : null;
        
        JoinQuery joinQuery = new JoinQuery(
            leftTable,
            rightTable,
            leftKey,
            rightKey,
            joinType,
            selectColumns,
            whereClause
        );

        ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.JOIN, joinQuery, sql);
        
        // Extract ORDER BY and LIMIT from top-level SqlOrderBy if present, otherwise from SqlSelect
        if (topLevelOrderBy != null) {
            parsedQuery.setOrderBy(extractOrderByFromSqlOrderBy(topLevelOrderBy));
            parsedQuery.setLimit(extractLimitFromSqlOrderBy(topLevelOrderBy));
        } else {
            parsedQuery.setOrderBy(extractOrderBy(select));
            parsedQuery.setLimit(extractLimit(select));
        }
        
        return parsedQuery;
    }

    /**
     * Extract table name from SqlNode
     */
    private String extractTableFromNode(SqlNode node) {
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            return id.names.get(id.names.size() - 1);
        } else if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getOperandList().size() > 0) {
                SqlNode operand = call.getOperandList().get(0);
                if (operand instanceof SqlIdentifier) {
                    SqlIdentifier id = (SqlIdentifier) operand;
                    return id.names.get(id.names.size() - 1);
                }
            }
        }
        return "unknown";
    }

    /**
     * Extract simple join condition for binary joins (e.g., u.id = o.user_id)
     */
    private SimpleJoinCondition extractSimpleJoinCondition(SqlNode condition) {
        if (condition instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) condition;
            if (call.getOperator().getName().equals("=")) {
                List<SqlNode> operands = call.getOperandList();
                if (operands.size() == 2) {
                    String leftKey = extractColumnName(operands.get(0));
                    String rightKey = extractColumnName(operands.get(1));
                    return new SimpleJoinCondition(leftKey, rightKey);
                }
            }
        }
        throw new IllegalArgumentException("Unsupported join condition: " + condition);
    }

    /**
     * Extract column name from SqlNode
     */
    private String extractColumnName(SqlNode node) {
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            // Return full name (e.g., "u.id" or just "id")
            return String.join(".", id.names);
        }
        return "unknown";
    }

    /**
     * Extract select columns (with alias support)
     * Returns column expressions in format "column" or "column AS alias"
     */
    private List<String> extractSelectColumns(SqlSelect select) {
        List<String> columns = new ArrayList<>();
        
        // Check if SELECT *
        if (select.getSelectList().toString().equals("*")) {
            return columns;  // Empty list means SELECT *
        }

        // Extract individual columns
        for (SqlNode node : select.getSelectList()) {
            if (node instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) node;
                // Handle "column AS alias"
                if (call.getOperator().getName().equals("AS") && call.getOperandList().size() >= 2) {
                    SqlNode columnNode = call.getOperandList().get(0);
                    SqlNode aliasNode = call.getOperandList().get(1);
                    
                    String columnExpr = columnNode.toString();
                    String alias = aliasNode.toString();
                    
                    // Store as "column AS alias"
                    columns.add(columnExpr + " AS " + alias);
                } else {
                    // Other expressions (functions, etc.)
                    columns.add(node.toString());
                }
            } else if (node instanceof SqlIdentifier) {
                SqlIdentifier id = (SqlIdentifier) node;
                columns.add(String.join(".", id.names));
            } else {
                // Fallback for other node types
                columns.add(node.toString());
            }
        }

        return columns;
    }

    /**
     * Helper class for simple join condition (binary joins)
     */
    private static class SimpleJoinCondition {
        String leftKey;
        String rightKey;

        SimpleJoinCondition(String leftKey, String rightKey) {
            this.leftKey = leftKey;
            this.rightKey = rightKey;
        }
    }


    private String extractTableName(String sql) {
        // Very naive table name extraction
        // Production would use Calcite's AST
        String[] words = sql.trim().split("\\s+");
        
        if (sql.toUpperCase().startsWith("SELECT")) {
            // SELECT ... FROM table_name
            for (int i = 0; i < words.length - 1; i++) {
                if (words[i].equalsIgnoreCase("FROM")) {
                    return words[i + 1].replaceAll("[;,]", "");
                }
            }
        } else if (sql.toUpperCase().startsWith("INSERT")) {
            // INSERT INTO table_name or INSERT INTO table_name(col1, col2)
            for (int i = 0; i < words.length - 1; i++) {
                if (words[i].equalsIgnoreCase("INTO")) {
                    // Extract just the table name, removing everything after (
                    String tablePart = words[i + 1];
                    int parenIndex = tablePart.indexOf('(');
                    if (parenIndex > 0) {
                        tablePart = tablePart.substring(0, parenIndex);
                    }
                    return tablePart.replaceAll("[;,]", "");
                }
            }
        } else if (sql.toUpperCase().startsWith("CREATE TABLE")) {
            // CREATE TABLE table_name or CREATE TABLE IF NOT EXISTS table_name
            int tableNameIndex = 2;
            
            // Skip "IF NOT EXISTS" if present
            if (words.length > 4 && 
                words[2].equalsIgnoreCase("IF") && 
                words[3].equalsIgnoreCase("NOT") && 
                words[4].equalsIgnoreCase("EXISTS")) {
                tableNameIndex = 5;
            }
            
            if (words.length > tableNameIndex) {
                String tablePart = words[tableNameIndex];
                // Extract just the table name, removing everything after (
                int parenIndex = tablePart.indexOf('(');
                if (parenIndex > 0) {
                    tablePart = tablePart.substring(0, parenIndex);
                }
                return tablePart.replaceAll("[;,]", "");
            }
        } else if (sql.toUpperCase().startsWith("UPDATE")) {
            // UPDATE table_name
            if (words.length > 1) {
                return words[1].replaceAll("[;,]", "");
            }
        } else if (sql.toUpperCase().startsWith("DELETE")) {
            // DELETE FROM table_name
            for (int i = 0; i < words.length - 1; i++) {
                if (words[i].equalsIgnoreCase("FROM")) {
                    return words[i + 1].replaceAll("[;,]", "");
                }
            }
        }
        
        return "unknown";
    }
    
    /**
     * Extract table name from CREATE INDEX statement
     */
    private String extractTableNameFromIndex(String sql) {
        // CREATE INDEX idx_name ON table_name (column)
        // CREATE UNIQUE INDEX idx_name ON table_name (column)
        String[] words = sql.trim().split("\\s+");
        
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equalsIgnoreCase("ON")) {
                // Get the next word and remove any trailing punctuation
                String tableName = words[i + 1];
                // Remove semicolons, commas, and opening parentheses
                tableName = tableName.replaceAll("[;,()].*$", "");
                return tableName;
            }
        }
        
        return "unknown";
    }
    
    private List<String> extractTableNamesFromDrop(String sql) {
        // DROP TABLE table1, table2, table3
        // DROP TABLE IF EXISTS table1, table2, table3
        List<String> tableNames = new ArrayList<>();
        
        String[] words = sql.trim().split("\\s+");
        
        for (int i = 0; i < words.length; i++) {
            if (words[i].equalsIgnoreCase("TABLE")) {
                // Next word is either table name(s) or "IF"
                if (i + 1 < words.length) {
                    String tablesPart;
                    if (words[i + 1].equalsIgnoreCase("IF") && i + 3 < words.length) {
                        // DROP TABLE IF EXISTS table_name(s)
                        // Join all remaining words (handles "table1, table2, table3")
                        tablesPart = String.join(" ", java.util.Arrays.copyOfRange(words, i + 3, words.length));
                    } else {
                        // DROP TABLE table_name(s)
                        // Join all remaining words
                        tablesPart = String.join(" ", java.util.Arrays.copyOfRange(words, i + 1, words.length));
                    }
                    
                    // Split by comma to handle multiple tables
                    String[] tables = tablesPart.split(",");
                    for (String table : tables) {
                        String cleanTable = table.trim().replaceAll("[;]", "");
                        if (!cleanTable.isEmpty()) {
                            tableNames.add(cleanTable);
                        }
                    }
                    break;
                }
            }
        }
        
        return tableNames.isEmpty() ? java.util.Arrays.asList("unknown") : tableNames;
    }
    
    private String extractTableNameFromCopy(String sql) {
        // COPY table_name FROM STDIN
        // COPY table_name TO STDOUT
        // COPY table_name (col1, col2) FROM STDIN
        String[] words = sql.trim().split("\\s+");
        
        if (words.length >= 2) {
            // Second word is the table name
            String tableName = words[1];
            // Remove parentheses if column list is present
            int parenPos = tableName.indexOf('(');
            if (parenPos > 0) {
                tableName = tableName.substring(0, parenPos);
            }
            return tableName.replaceAll("[;,]", "");
        }
        
        return "unknown";
    }
    
    private List<String> extractTableNamesFromTruncate(String sql) {
        // TRUNCATE TABLE table1, table2, table3
        // TRUNCATE table1, table2
        List<String> tableNames = new ArrayList<>();
        
        String[] words = sql.trim().split("\\s+", 3); // Split into at most 3 parts
        
        // Skip "TRUNCATE" and optionally "TABLE"
        String tablesPart;
        if (words.length >= 2 && words[1].equalsIgnoreCase("TABLE")) {
            // TRUNCATE TABLE ...
            tablesPart = words.length >= 3 ? words[2] : "";
        } else {
            // TRUNCATE ...
            tablesPart = words.length >= 2 ? words[1] : "";
        }
        
        // Split by comma to handle multiple tables
        String[] tables = tablesPart.split(",");
        for (String table : tables) {
            String cleanTable = table.trim().replaceAll("[;]", "");
            if (!cleanTable.isEmpty()) {
                tableNames.add(cleanTable);
            }
        }
        
        return tableNames;
    }
    
    /**
     * Count the number of tables in a JOIN tree
     */
    private int countTablesInJoin(SqlJoin join) {
        int count = 0;
        
        // Count left side (could be nested join)
        SqlNode left = join.getLeft();
        if (left instanceof SqlJoin) {
            count += countTablesInJoin((SqlJoin) left);
        } else {
            count += 1;  // Single table
        }
        
        // Count right side (always a single table in Calcite's left-deep tree)
        count += 1;
        
        return count;
    }
    
    /**
     * Parse multi-way join (3+ tables)
     */
    private ParsedQuery parseMultiWayJoin(String sql, SqlSelect select, SqlJoin join) {
        System.out.println("=== PARSING MULTI-WAY JOIN ===");
        
        List<String> tables = new ArrayList<>();
        List<JoinCondition> joinConditions = new ArrayList<>();
        java.util.Map<String, String> tableAliases = new java.util.HashMap<>();
        
        // Recursively parse the join tree (left-deep)
        parseJoinTree(join, tables, joinConditions, tableAliases);
        
        System.out.println("Tables: " + tables);
        System.out.println("Join conditions: " + joinConditions);
        System.out.println("Aliases: " + tableAliases);
        
        // Extract SELECT columns
        List<String> selectColumns = extractSelectColumns(select);
        
        // Extract WHERE clause
        String whereClause = null;
        SqlNode whereNode = select.getWhere();
        if (whereNode != null) {
            whereClause = whereNode.toString();
            System.out.println("WHERE clause: " + whereClause);
        }
        
        MultiWayJoinQuery multiWayJoin = new MultiWayJoinQuery(
            tables,
            joinConditions,
            selectColumns,
            tableAliases,
            whereClause
        );
        
        System.out.println("==============================\n");
        
        return new ParsedQuery(ParsedQuery.Type.MULTI_WAY_JOIN, multiWayJoin, sql);
    }
    
    /**
     * Recursively parse join tree to extract tables and conditions
     */
    private void parseJoinTree(SqlJoin join, List<String> tables, 
                              List<JoinCondition> joinConditions,
                              java.util.Map<String, String> tableAliases) {
        // Parse left side (could be nested join)
        SqlNode left = join.getLeft();
        if (left instanceof SqlJoin) {
            // Recursively parse nested join
            parseJoinTree((SqlJoin) left, tables, joinConditions, tableAliases);
        } else {
            // Base case: single table
            String tableName = extractTableFromNode(left);
            String alias = extractAliasFromNode(left);
            tables.add(tableName);
            if (alias != null && !alias.equals(tableName)) {
                tableAliases.put(alias, tableName);
            }
        }
        
        // Parse right side (always a single table)
        SqlNode right = join.getRight();
        String rightTable = extractTableFromNode(right);
        String rightAlias = extractAliasFromNode(right);
        tables.add(rightTable);
        if (rightAlias != null && !rightAlias.equals(rightTable)) {
            tableAliases.put(rightAlias, rightTable);
        }
        
        // Extract join condition
        SqlNode condition = join.getCondition();
        JoinCondition joinCond = extractJoinConditionFull(condition, join);
        if (joinCond != null) {
            joinConditions.add(joinCond);
        }
    }
    
    /**
     * Extract alias from SqlNode
     */
    private String extractAliasFromNode(SqlNode node) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            // Handle "table AS alias" or "table alias"
            if (call.getOperator().getName().equals("AS") && call.getOperandList().size() >= 2) {
                SqlNode aliasNode = call.getOperandList().get(1);
                if (aliasNode instanceof SqlIdentifier) {
                    return ((SqlIdentifier) aliasNode).getSimple();
                }
            }
        } else if (node instanceof SqlIdentifier) {
            // No alias, return table name
            SqlIdentifier id = (SqlIdentifier) node;
            return id.getSimple();
        }
        return null;
    }
    
    /**
     * Extract full join condition with table names
     */
    private JoinCondition extractJoinConditionFull(SqlNode condition, SqlJoin join) {
        if (condition instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) condition;
            if (call.getOperator().getName().equals("=")) {
                List<SqlNode> operands = call.getOperandList();
                if (operands.size() == 2) {
                    // Extract left side (e.g., u.id)
                    String leftKey = extractColumnName(operands.get(0));
                    String[] leftParts = leftKey.split("\\.");
                    String leftTable = leftParts.length == 2 ? leftParts[0] : "unknown";
                    String leftColumn = leftParts.length == 2 ? leftParts[1] : leftKey;
                    
                    // Extract right side (e.g., o.user_id)
                    String rightKey = extractColumnName(operands.get(1));
                    String[] rightParts = rightKey.split("\\.");
                    String rightTable = rightParts.length == 2 ? rightParts[0] : "unknown";
                    String rightColumn = rightParts.length == 2 ? rightParts[1] : rightKey;
                    
                    // Determine join type
                    JoinCondition.JoinType joinType = JoinCondition.JoinType.INNER;
                    switch (join.getJoinType()) {
                        case LEFT:
                            joinType = JoinCondition.JoinType.LEFT;
                            break;
                        case RIGHT:
                            joinType = JoinCondition.JoinType.RIGHT;
                            break;
                        case FULL:
                            joinType = JoinCondition.JoinType.FULL;
                            break;
                        default:
                            joinType = JoinCondition.JoinType.INNER;
                    }
                    
                    return new JoinCondition(leftTable, leftColumn, rightTable, rightColumn, joinType);
                }
            }
        }
        return null;
    }
    
    /**
     * Extract ORDER BY clause from SqlSelect
     */
    private OrderByClause extractOrderBy(SqlSelect select) {
        SqlNodeList orderList = select.getOrderList();
        if (orderList == null || orderList.size() == 0) {
            return null;
        }
        
        List<OrderByClause.OrderByItem> items = new ArrayList<>();
        
        for (SqlNode node : orderList) {
            String column;
            OrderByClause.Direction direction = OrderByClause.Direction.ASC;
            
            if (node instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) node;
                // Handle DESC/ASC
                if (call.getOperator().getName().equals("DESC")) {
                    direction = OrderByClause.Direction.DESC;
                    column = extractColumnName(call.getOperandList().get(0));
                } else {
                    column = extractColumnName(node);
                }
            } else {
                column = extractColumnName(node);
            }
            
            items.add(new OrderByClause.OrderByItem(column, direction));
        }
        
        return new OrderByClause(items);
    }
    
    /**
     * Extract LIMIT and OFFSET from SqlSelect
     */
    private LimitClause extractLimit(SqlSelect select) {
        SqlNode fetch = select.getFetch();
        SqlNode offset = select.getOffset();
        
        int limit = -1;
        int off = 0;
        
        if (fetch != null) {
            limit = extractIntValue(fetch);
        }
        
        if (offset != null) {
            off = extractIntValue(offset);
        }
        
        return limit > 0 ? new LimitClause(limit, off) : null;
    }
    
    /**
     * Extract ORDER BY from SqlOrderBy node
     */
    private OrderByClause extractOrderByFromSqlOrderBy(SqlOrderBy orderBy) {
        SqlNodeList orderList = orderBy.orderList;
        if (orderList == null || orderList.size() == 0) {
            return null;
        }
        
        List<OrderByClause.OrderByItem> items = new ArrayList<>();
        
        for (SqlNode node : orderList) {
            String column;
            OrderByClause.Direction direction = OrderByClause.Direction.ASC;
            
            if (node instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) node;
                // Handle DESC/ASC
                if (call.getOperator().getName().equals("DESC")) {
                    direction = OrderByClause.Direction.DESC;
                    column = extractColumnName(call.getOperandList().get(0));
                } else {
                    column = extractColumnName(node);
                }
            } else {
                column = extractColumnName(node);
            }
            
            items.add(new OrderByClause.OrderByItem(column, direction));
        }
        
        return new OrderByClause(items);
    }
    
    /**
     * Extract LIMIT and OFFSET from SqlOrderBy node
     */
    private LimitClause extractLimitFromSqlOrderBy(SqlOrderBy orderBy) {
        SqlNode fetch = orderBy.fetch;
        SqlNode offset = orderBy.offset;
        
        int limit = -1;
        int off = 0;
        
        if (fetch != null) {
            limit = extractIntValue(fetch);
        }
        
        if (offset != null) {
            off = extractIntValue(offset);
        }
        
        return limit > 0 ? new LimitClause(limit, off) : null;
    }
    
    /**
     * Extract integer value from SqlNode
     */
    private int extractIntValue(SqlNode node) {
        if (node instanceof SqlNumericLiteral) {
            SqlNumericLiteral literal = (SqlNumericLiteral) node;
            return literal.intValue(true);
        }
        return -1;
    }
    
    /**
     * Check if SQL contains subqueries
     */
    private boolean hasSubquery(String sql) {
        String sqlUpper = sql.toUpperCase();
        
        // Simple heuristic: Look for SELECT inside parentheses
        // This catches: IN (SELECT...), EXISTS (SELECT...), FROM (SELECT...), etc.
        
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            
            // Handle strings
            if (c == '\'' || c == '"') {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }
            
            if (inString) continue;
            
            // Track parenthesis depth
            if (c == '(') {
                depth++;
                
                // Check if there's a SELECT after this opening paren
                if (depth > 0) {
                    // Look ahead for SELECT keyword
                    String remaining = sql.substring(i + 1, Math.min(i + 50, sql.length())).toUpperCase().trim();
                    if (remaining.startsWith("SELECT")) {
                        return true;
                    }
                }
            } else if (c == ')') {
                depth--;
            }
        }
        
        return false;
    }
    
    /**
     * Check if SQL has aggregate functions in the main query.
     * Note: This is called AFTER hasSubquery() check, so we don't need to worry
     * about aggregates in subqueries causing infinite loops.
     */
    private boolean hasAggregateFunction(String sql) {
        String upper = sql.toUpperCase();
        // Don't treat UNION queries as aggregation queries, even if they contain aggregates
        if (upper.matches(".*\\sUNION\\s.*")) {
            return false;
        }
        
        // Use Calcite AST parser with visitor pattern to properly detect aggregates
        // only in the main query, not in subqueries
        try {
            SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            // Handle ORDER BY wrapping
            if (sqlNode instanceof SqlOrderBy) {
                SqlOrderBy orderBy = (SqlOrderBy) sqlNode;
                sqlNode = orderBy.query;
            }
            
            // Only check SELECT statements
            if (!(sqlNode instanceof SqlSelect)) {
                return false;
            }
            
            SqlSelect select = (SqlSelect) sqlNode;
            
            // Check if the SELECT list contains aggregate functions
            // Use a visitor to check only the top-level SELECT, not subqueries
            if (select.getSelectList() != null) {
                for (SqlNode selectItem : select.getSelectList()) {
                    if (containsAggregateAtTopLevel(selectItem)) {
                        return true;
                    }
                }
            }
            
            return false;
            
        } catch (Exception e) {
            // If parsing fails, fall back to simple string check
            // This shouldn't happen for valid SQL, but provides a safety net
            log.debug("Failed to parse SQL for aggregate detection, using fallback: {}", e.getMessage());
            return upper.contains("COUNT(") || upper.contains("SUM(") || 
                   upper.contains("AVG(") || upper.contains("MIN(") || upper.contains("MAX(");
        }
    }
    
    /**
     * Check if a SqlNode contains an aggregate function at the top level
     * (not in subqueries). Uses visitor pattern to traverse the AST.
     */
    private boolean containsAggregateAtTopLevel(SqlNode node) {
        if (node == null) {
            return false;
        }
        
        // Check if this node itself is an aggregate function
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            String opName = call.getOperator().getName().toUpperCase();
            
            // Check for aggregate functions
            if (opName.equals("COUNT") || opName.equals("SUM") || 
                opName.equals("AVG") || opName.equals("MIN") || opName.equals("MAX")) {
                return true;
            }
            
            // Check for AS wrapper (e.g., COUNT(*) AS total)
            if (call.getOperator().getKind() == SqlKind.AS) {
                // Recursively check the first operand (the actual expression)
                return containsAggregateAtTopLevel(call.operand(0));
            }
            
            // For other operators, check operands but stop at subqueries
            for (SqlNode operand : call.getOperandList()) {
                // Don't recurse into subqueries (SqlSelect nodes)
                if (operand instanceof SqlSelect) {
                    continue;
                }
                if (containsAggregateAtTopLevel(operand)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Parse aggregation query (GROUP BY or aggregate functions)
     */
    private ParsedQuery parseAggregationQuery(String sql) throws SqlParseException {
        System.out.println("🔧 Parsing aggregation query");
        
        try {
            SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            // Handle ORDER BY wrapping
            if (sqlNode instanceof SqlOrderBy) {
                SqlOrderBy orderBy = (SqlOrderBy) sqlNode;
                sqlNode = orderBy.query;
            }
            
            // Handle UNION ALL - treat as regular SELECT for now
            // UNION ALL creates a SqlBasicCall, not a SqlSelect
            if (sqlNode instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) sqlNode;
                if (call.getOperator().getName().equalsIgnoreCase("UNION")) {
                    // For UNION queries, just return as a regular SELECT
                    // The KV executor will handle it as a simple query
                    log.debug("UNION query detected, treating as regular SELECT");
                    ParsedQuery query = new ParsedQuery(ParsedQuery.Type.SELECT, extractTableName(sql), sql);
                    return query;
                }
            }
            
            if (!(sqlNode instanceof SqlSelect)) {
                throw new IllegalArgumentException("Expected SELECT, got: " + sqlNode.getClass().getSimpleName());
            }
            
            SqlSelect select = (SqlSelect) sqlNode;
            
            // Extract GROUP BY columns
            List<String> groupByColumns = new ArrayList<>();
            if (select.getGroup() != null && select.getGroup().size() > 0) {
                for (SqlNode groupNode : select.getGroup()) {
                    groupByColumns.add(groupNode.toString());
                }
            }
            
            // Extract aggregate functions and literals from SELECT list
            List<AggregateFunction> aggregates = new ArrayList<>();
            List<LiteralColumn> literals = new ArrayList<>();
            int position = 0;
            
            if (select.getSelectList() != null) {
                for (SqlNode selectItem : select.getSelectList()) {
                    // Try to parse as aggregate function
                    AggregateFunction agg = parseAggregateFunction(selectItem);
                    if (agg != null) {
                        aggregates.add(agg);
                    } else {
                        // Check if it's a literal expression
                        LiteralColumn literal = parseLiteralColumn(selectItem, position);
                        if (literal != null) {
                            literals.add(literal);
                        }
                    }
                    position++;
                }
            }
            
            // Extract HAVING clause
            String havingClause = null;
            System.out.println("  🔍 Checking for HAVING clause...");
            System.out.println("  select.getHaving() = " + select.getHaving());
            if (select.getHaving() != null) {
                havingClause = select.getHaving().toString();
                System.out.println("  📋 HAVING clause extracted: " + havingClause);
            } else {
                System.out.println("  ℹ️  No HAVING clause found");
            }
            
            // Build base query (SELECT without aggregates and GROUP BY)
            String baseQuerySql = buildBaseQuery(sql, select);
            
            // Determine table name
            String tableName = extractTableNameFromSelect(select);
            
            // Parse base query
            ParsedQuery baseQuery = parse(baseQuerySql);
            
            // Create aggregation query
            AggregationQuery aggQuery = new AggregationQuery(
                groupByColumns,
                aggregates,
                baseQuery,
                sql,  // Original SQL
                havingClause,
                literals
            );
            
            ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.AGGREGATION, tableName, sql);
            parsedQuery.setAggregationQuery(aggQuery);
            
            System.out.println("  ✅ Parsed aggregation: " + groupByColumns.size() + " group columns, " + 
                             aggregates.size() + " aggregates" + 
                             (havingClause != null ? ", with HAVING" : ""));
            
            return parsedQuery;
            
        } catch (Exception e) {
            System.err.println("  ❌ Failed to parse aggregation query: " + e.getMessage());
            e.printStackTrace();
            // Wrap in RuntimeException since SqlParseException constructor is complex
            throw new RuntimeException("Failed to parse GROUP BY query: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parse aggregate function from SELECT item
     */
    private AggregateFunction parseAggregateFunction(SqlNode node) {
        String alias = null;
        SqlNode actualNode = node;
        
        // Check if this is an AS expression (e.g., COUNT(*) as row_count)
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getOperator().getKind() == SqlKind.AS) {
                // Extract the actual function and the alias
                actualNode = call.operand(0);
                alias = call.operand(1).toString();
            }
        }
        
        if (!(actualNode instanceof SqlBasicCall)) {
            return null;
        }
        
        SqlBasicCall call = (SqlBasicCall) actualNode;
        String funcName = call.getOperator().getName().toUpperCase();
        
        // Check if it's an aggregate function
        if (!funcName.equals("COUNT") && !funcName.equals("SUM") && 
            !funcName.equals("AVG") && !funcName.equals("MIN") && !funcName.equals("MAX")) {
            return null;
        }
        
        // Extract column name
        String column = "*";
        if (call.getOperandList().size() > 0) {
            SqlNode operand = call.getOperandList().get(0);
            column = operand.toString();
        }
        
        // Use provided alias or generate one
        if (alias == null) {
            alias = funcName.toLowerCase() + "_" + column.replace("*", "all");
        }
        
        // Convert string to Type enum
        AggregateFunction.Type type = AggregateFunction.Type.valueOf(funcName);
        
        return new AggregateFunction(type, column, alias);
    }
    
    /**
     * Parse literal column from SELECT item (e.g., 'Branches' as table_name, 0 as total)
     */
    private LiteralColumn parseLiteralColumn(SqlNode node, int position) {
        String alias = null;
        SqlNode actualNode = node;
        
        // Check if this is an AS expression (e.g., 'Branches' as table_name)
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            if (call.getOperator().getKind() == SqlKind.AS) {
                // Extract the actual value and the alias
                actualNode = call.operand(0);
                alias = call.operand(1).toString();
            }
        }
        
        // Check if it's a literal
        if (!(actualNode instanceof SqlLiteral)) {
            return null;
        }
        
        SqlLiteral literal = (SqlLiteral) actualNode;
        Object value = extractLiteralValue(literal);
        
        // Use provided alias or generate one
        if (alias == null) {
            alias = "?column?";
        }
        
        return new LiteralColumn(alias, value, position);
    }
    
    /**
     * Extract value from SqlLiteral
     */
    private Object extractLiteralValue(SqlLiteral literal) {
        if (literal instanceof SqlCharStringLiteral) {
            // String literal
            SqlCharStringLiteral strLiteral = (SqlCharStringLiteral) literal;
            return strLiteral.getNlsString().getValue();
        } else if (literal instanceof SqlNumericLiteral) {
            // Numeric literal
            SqlNumericLiteral numLiteral = (SqlNumericLiteral) literal;
            return numLiteral.getValue();
        } else {
            // Other literals (boolean, null, etc.)
            return literal.getValue();
        }
    }
    
    /**
     * Build base query without aggregates and GROUP BY
     */
    private String buildBaseQuery(String originalSql, SqlSelect select) {
        String fromClause = extractTableNameFromSelect(select);
        String whereClause = null;
        
        // Extract WHERE clause from SqlSelect if present
        if (select.getWhere() != null) {
            whereClause = select.getWhere().toString();
            // Remove backticks that Calcite adds around identifiers
            // Calcite generates `COLUMN_NAME` but our parser doesn't handle backticks
            whereClause = whereClause.replace("`", "");
            System.out.println("  🔍 Extracted WHERE clause: " + whereClause);
        } else {
            System.out.println("  ℹ️  No WHERE clause in SqlSelect");
        }
        
        // Build base query with WHERE clause if present
        // Note: fromClause may be a simple table name or a complex JOIN expression
        StringBuilder baseQuery = new StringBuilder("SELECT * FROM ");
        baseQuery.append(fromClause);
        
        if (whereClause != null && !whereClause.isEmpty()) {
            baseQuery.append(" WHERE ").append(whereClause);
        }
        
        String result = baseQuery.toString();
        System.out.println("  📋 Built base query: " + result);
        
        return result;
    }
    
    /**
     * Extract table name or FROM clause from SqlSelect using proper AST traversal
     * This may return a simple table name or a complex JOIN expression
     */
    private String extractTableNameFromSelect(SqlSelect select) {
        if (select.getFrom() == null) {
            return "unknown";
        }
        
        SqlNode fromNode = select.getFrom();
        System.out.println("  🔍 FROM node type: " + fromNode.getClass().getSimpleName());
        System.out.println("  🔍 FROM node kind: " + fromNode.getKind());
        
        // Build FROM clause by traversing the AST
        String fromClause = buildFromClauseFromAst(fromNode);
        
        // Remove backticks that Calcite adds
        fromClause = fromClause.replace("`", "");
        
        System.out.println("  🔍 FROM clause from AST: " + fromClause);
        
        return fromClause;
    }
    
    /**
     * Build FROM clause by traversing the AST (handles simple tables, JOINs, and subqueries)
     */
    private String buildFromClauseFromAst(SqlNode fromNode) {
        if (fromNode instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) fromNode;
            
            // Recursively build left and right sides
            String left = buildFromClauseFromAst(join.getLeft());
            String right = buildFromClauseFromAst(join.getRight());
            
            // Get join type
            String joinType = join.getJoinType().toString();
            
            // Get join condition
            String condition = join.getCondition() != null ? join.getCondition().toString() : "";
            
            // Build JOIN clause
            return left + " " + joinType + " JOIN " + right + 
                   (condition.isEmpty() ? "" : " ON " + condition);
                   
        } else if (fromNode instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) fromNode;
            // Handle AS (table alias)
            if (call.getOperator().getKind() == SqlKind.AS) {
                SqlNode operand = call.operand(0);
                String alias = call.operand(1).toString();
                
                // Check if the operand is a subquery (SqlSelect)
                if (operand instanceof SqlSelect) {
                    // This is a derived table/subquery
                    return "(" + operand.toString() + ") AS " + alias;
                } else {
                    String tableName = operand.toString();
                    return tableName + " AS " + alias;
                }
            }
            return call.toString();
        } else if (fromNode instanceof SqlSelect) {
            // Subquery without alias (should have alias, but handle gracefully)
            return "(" + fromNode.toString() + ")";
        } else {
            // Simple table name or identifier
            return fromNode.toString();
        }
    }
    
    /**
     * Check if FROM clause contains a subquery (derived table)
     */
    private boolean hasFromSubquery(SqlNode fromNode) {
        if (fromNode instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) fromNode;
            if (call.getOperator().getKind() == SqlKind.AS) {
                return call.operand(0) instanceof SqlSelect;
            }
        } else if (fromNode instanceof SqlSelect) {
            return true;
        } else if (fromNode instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) fromNode;
            return hasFromSubquery(join.getLeft()) || hasFromSubquery(join.getRight());
        }
        return false;
    }
    
    /**
     * Extract FROM clause from SQL
     */
    private String extractFromClause(String sql) {
        String upper = sql.toUpperCase();
        int fromPos = upper.indexOf(" FROM ");
        if (fromPos < 0) {
            return "";
        }
        
        int endPos = sql.length();
        int wherePos = upper.indexOf(" WHERE ", fromPos);
        int groupPos = upper.indexOf(" GROUP BY ", fromPos);
        int orderPos = upper.indexOf(" ORDER BY ", fromPos);
        int limitPos = upper.indexOf(" LIMIT ", fromPos);
        
        if (wherePos > 0) endPos = Math.min(endPos, wherePos);
        if (groupPos > 0) endPos = Math.min(endPos, groupPos);
        if (orderPos > 0) endPos = Math.min(endPos, orderPos);
        if (limitPos > 0) endPos = Math.min(endPos, limitPos);
        
        return sql.substring(fromPos + 6, endPos).trim();
    }
    
    /**
     * Extract WHERE clause from SQL
     */
    private String extractWhereClause(String sql) {
        String upper = sql.toUpperCase();
        int wherePos = upper.indexOf(" WHERE ");
        if (wherePos < 0) {
            return null;
        }
        
        int endPos = sql.length();
        int groupPos = upper.indexOf(" GROUP BY ", wherePos);
        int orderPos = upper.indexOf(" ORDER BY ", wherePos);
        int limitPos = upper.indexOf(" LIMIT ", wherePos);
        
        if (groupPos > 0) endPos = Math.min(endPos, groupPos);
        if (orderPos > 0) endPos = Math.min(endPos, orderPos);
        if (limitPos > 0) endPos = Math.min(endPos, limitPos);
        
        return sql.substring(wherePos + 7, endPos).trim();
    }
    
    /**
     * Parse query with window functions
     */
    private ParsedQuery parseWindowFunctionQuery(String sql) throws SqlParseException {
        System.out.println("🪟 Parsing window function query");
        
        // Parse window functions
        List<WindowSpec> windowSpecs = WindowFunctionParser.parseWindowFunctions(sql);
        System.out.println("   Found " + windowSpecs.size() + " window function(s)");
        
        // Get base query (without window functions)
        String baseQuery = WindowFunctionParser.removeWindowFunctions(sql);
        System.out.println("   Base query: " + baseQuery);
        
        // Extract table name
        String tableName = extractTableName(baseQuery);
        
        // Create parsed query
        ParsedQuery query = new ParsedQuery(ParsedQuery.Type.WINDOW_FUNCTION, tableName, sql);
        query.setWindowFunctions(windowSpecs);
        
        return query;
    }
    
    /**
     * Extract table name from ALTER TABLE statement
     */
    private String extractTableNameFromAlter(String sql) {
        // ALTER TABLE table_name ...
        String[] parts = sql.trim().split("\\s+", 4);
        if (parts.length >= 3) {
            return parts[2].replaceAll("[;,]", "");
        }
        return "unknown";
    }
    
    /**
     * Parse EXPLAIN or EXPLAIN ANALYZE query
     */
    private ParsedQuery parseExplainQuery(String sql) {
        String sqlUpper = sql.trim().toUpperCase();
        boolean isAnalyze = false;
        String targetSql;
        
        // Check for EXPLAIN ANALYZE
        if (sqlUpper.startsWith("EXPLAIN ANALYZE") || sqlUpper.startsWith("EXPLAIN (ANALYZE)")) {
            isAnalyze = true;
            // Extract the target SQL after EXPLAIN ANALYZE
            if (sqlUpper.startsWith("EXPLAIN ANALYZE")) {
                targetSql = sql.substring("EXPLAIN ANALYZE".length()).trim();
            } else {
                // EXPLAIN (ANALYZE) format
                int closeParen = sql.indexOf(')');
                if (closeParen > 0) {
                    targetSql = sql.substring(closeParen + 1).trim();
                } else {
                    targetSql = sql.substring("EXPLAIN (ANALYZE)".length()).trim();
                }
            }
        } else {
            // Plain EXPLAIN
            targetSql = sql.substring("EXPLAIN".length()).trim();
        }
        
        ParsedQuery.Type type = isAnalyze ? ParsedQuery.Type.EXPLAIN_ANALYZE : ParsedQuery.Type.EXPLAIN;
        ParsedQuery query = new ParsedQuery(type, (String) null, sql);
        query.setExplainTargetSql(targetSql);
        query.setExplainAnalyze(isAnalyze);
        
        log.debug("Parsed EXPLAIN query: analyze={}, target={}", isAnalyze, targetSql);
        
        return query;
    }
    
    /**
     * Parse ALTER TABLE
     * Note: Calcite's DDL parser has limited ALTER TABLE support, so we skip parsing
     * and rely on the execution layer to validate and execute the statement.
     */
    private ParsedQuery parseAlterTable(String sql) {
        // Extract table name from the SQL string
        String tableName = extractTableNameFromAlter(sql);
        
        // Create ParsedQuery with ALTER_TABLE type
        // We don't parse with Calcite since it has limited ALTER TABLE support
        ParsedQuery query = new ParsedQuery(ParsedQuery.Type.ALTER_TABLE, tableName, sql);
        
        log.debug("Parsed ALTER TABLE on table: {}", tableName);
        
        return query;
    }
    
    /**
     * Extract view name from CREATE VIEW or CREATE MATERIALIZED VIEW statement
     * Format: CREATE [MATERIALIZED] VIEW view_name AS SELECT ...
     */
    private String extractViewName(String sql) {
        String sqlUpper = sql.toUpperCase();
        int viewPos = sqlUpper.indexOf(" VIEW ");
        if (viewPos == -1) {
            return "unknown_view";
        }
        
        // Skip past " VIEW "
        int nameStart = viewPos + 6;
        String remainder = sql.substring(nameStart).trim();
        
        log.info("📝 extractViewName: remainder='{}'", remainder);
        
        // Extract name until AS keyword (handle both " AS " and " AS\n" or "AS ")
        // Use regex to find AS surrounded by whitespace (including newlines)
        String remainderUpper = remainder.toUpperCase();
        int asPos = -1;
        
        // Look for AS preceded and followed by whitespace
        for (int i = 0; i < remainderUpper.length() - 2; i++) {
            if (remainderUpper.charAt(i) == 'A' && remainderUpper.charAt(i+1) == 'S') {
                // Check if preceded by whitespace or start of string
                boolean precedingWhitespace = (i == 0) || Character.isWhitespace(remainderUpper.charAt(i-1));
                // Check if followed by whitespace or end of string
                boolean followingWhitespace = (i+2 >= remainderUpper.length()) || Character.isWhitespace(remainderUpper.charAt(i+2));
                
                if (precedingWhitespace && followingWhitespace) {
                    asPos = i;
                    break;
                }
            }
        }
        
        log.info("📝 extractViewName: asPos={}", asPos);
        
        if (asPos == -1) {
            // No AS found, take first word
            int spacePos = remainder.indexOf(' ');
            String result = spacePos == -1 ? remainder : remainder.substring(0, spacePos);
            log.info("📝 extractViewName: no AS found, result='{}'", result);
            return result;
        }
        
        String result = remainder.substring(0, asPos).trim();
        log.info("📝 extractViewName: result='{}'", result);
        return result;
    }
    
    /**
     * Extract view name from DROP VIEW statement
     * Format: DROP [MATERIALIZED] VIEW [IF EXISTS] view_name
     */
    private String extractViewNameFromDrop(String sql) {
        String sqlUpper = sql.toUpperCase();
        int viewPos = sqlUpper.indexOf(" VIEW ");
        if (viewPos == -1) {
            return "unknown_view";
        }
        
        // Skip past " VIEW "
        String remainder = sql.substring(viewPos + 6).trim();
        
        // Check for IF EXISTS
        if (remainder.toUpperCase().startsWith("IF EXISTS ")) {
            remainder = remainder.substring(10).trim();
        }
        
        // Extract first word as view name
        int spacePos = remainder.indexOf(' ');
        return spacePos == -1 ? remainder : remainder.substring(0, spacePos);
    }
    
    /**
     * Extract view name from REFRESH MATERIALIZED VIEW statement
     * Format: REFRESH MATERIALIZED VIEW view_name
     */
    private String extractViewNameFromRefresh(String sql) {
        String sqlUpper = sql.toUpperCase();
        int viewPos = sqlUpper.indexOf(" VIEW ");
        if (viewPos == -1) {
            return "unknown_view";
        }
        
        // Skip past " VIEW "
        String remainder = sql.substring(viewPos + 6).trim();
        
        // Extract first word as view name
        int spacePos = remainder.indexOf(' ');
        return spacePos == -1 ? remainder : remainder.substring(0, spacePos);
    }
    
    /**
     * Extract type name from CREATE TYPE statement
     * Example: "CREATE TYPE order_status AS ENUM ('pending', 'shipped')"
     */
    private String extractTypeName(String sql) {
        String sqlUpper = sql.toUpperCase();
        int typePos = sqlUpper.indexOf("TYPE");
        if (typePos == -1) {
            return "unknown_type";
        }
        
        int nameStart = typePos + 4;
        String remainder = sql.substring(nameStart).trim();
        
        // Extract type name (up to AS keyword)
        int asPos = remainder.toUpperCase().indexOf(" AS ");
        if (asPos == -1) {
            int spacePos = remainder.indexOf(' ');
            return spacePos == -1 ? remainder : remainder.substring(0, spacePos);
        }
        
        return remainder.substring(0, asPos).trim();
    }
    
    /**
     * Extract type name from DROP TYPE statement
     * Example: "DROP TYPE order_status" or "DROP TYPE IF EXISTS order_status"
     */
    private String extractTypeNameFromDrop(String sql) {
        String sqlUpper = sql.toUpperCase();
        
        // Handle "DROP TYPE IF EXISTS type_name"
        if (sqlUpper.contains("IF EXISTS")) {
            int existsPos = sqlUpper.indexOf("EXISTS");
            String remainder = sql.substring(existsPos + 6).trim();
            int spacePos = remainder.indexOf(' ');
            return spacePos == -1 ? remainder : remainder.substring(0, spacePos);
        }
        
        // Handle "DROP TYPE type_name"
        int typePos = sqlUpper.indexOf("TYPE");
        if (typePos == -1) {
            return "unknown_type";
        }
        
        String remainder = sql.substring(typePos + 4).trim();
        int spacePos = remainder.indexOf(' ');
        return spacePos == -1 ? remainder : remainder.substring(0, spacePos);
    }
}

