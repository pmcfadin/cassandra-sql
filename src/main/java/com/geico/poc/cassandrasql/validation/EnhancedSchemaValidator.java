package com.geico.poc.cassandrasql.validation;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Enhanced schema validator with type checking and detailed error messages
 * Provides PostgreSQL-quality error messages for better developer experience
 */
@Component
public class EnhancedSchemaValidator {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired(required = false)
    private SchemaManager schemaManager;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    // Cache for table metadata
    private final Map<String, TableMetadata> tableCache = new HashMap<>();
    
    /**
     * Context for validation - tracks table aliases
     */
    private static class ValidationContext {
        // Map of alias -> actual table name
        final Map<String, String> aliasMap = new HashMap<>();
        
        void addAlias(String alias, String tableName) {
            aliasMap.put(alias.toLowerCase(), tableName.toLowerCase());
        }
        
        String resolveAlias(String nameOrAlias) {
            String lower = nameOrAlias.toLowerCase();
            return aliasMap.getOrDefault(lower, lower);
        }
        
        boolean isAlias(String name) {
            return aliasMap.containsKey(name.toLowerCase());
        }
    }
    
    /**
     * Check if SQL contains table aliases
     * Simple heuristic: look for pattern "FROM table alias" or "JOIN table alias"
     */
    private boolean hasTableAliases(String sql) {
        String sqlUpper = sql.toUpperCase();
        // Look for patterns like "FROM users u" or "JOIN orders o"
        // where there's a word after the table name that's not a SQL keyword
        return sqlUpper.matches(".*\\b(FROM|JOIN)\\s+\\w+\\s+\\w+\\s+(JOIN|ON|WHERE|GROUP|ORDER|LIMIT|;|$).*");
    }
    
    /**
     * Check if SQL contains GROUP BY or aggregate functions
     */
    private boolean hasAggregationOrGroupBy(String sql) {
        String sqlUpper = sql.toUpperCase();
        return sqlUpper.contains("GROUP BY") || 
               sqlUpper.contains("HAVING") ||
               sqlUpper.contains("COUNT(") || 
               sqlUpper.contains("SUM(") || 
               sqlUpper.contains("AVG(") || 
               sqlUpper.contains("MIN(") || 
               sqlUpper.contains("MAX(");
    }
    
    /**
     * Safely extract column name from SqlIdentifier (handles qualified names)
     */
    private String getColumnName(SqlIdentifier id) {
        if (id.names.size() > 1) {
            // Qualified: return last part (column name)
            return id.names.get(id.names.size() - 1).toLowerCase();
        } else {
            // Unqualified: use getSimple()
            return id.getSimple().toLowerCase();
        }
    }
    
    /**
     * Safely extract table name from SqlIdentifier (handles qualified names)
     */
    private String getTableName(SqlIdentifier id) {
        if (id.names.size() > 1) {
            // Qualified: return first part (table name)
            return id.names.get(0).toLowerCase();
        } else {
            // Unqualified: return the name
            return id.getSimple().toLowerCase();
        }
    }
    
    /**
     * Validate SQL with enhanced error messages
     */
    public EnhancedValidationResult validate(String sql) {
        EnhancedValidationResult result = new EnhancedValidationResult();
        

        // Skip validation in KV mode - KV mode has its own validation in KvQueryExecutor
        if (config != null && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            result.setValid(true);
            result.addInfo("Skipping validation in KV mode");
            return result;
        }        
        
        // TODO: Everything else is left over from the previous implementation that used the 
        // Cassandra schema directly instead of using cassandra as a KV store. It's dead code and should
        // be removed


        // Skip validation for DDL statements (CREATE, DROP, ALTER) and transaction commands
        // These are handled by CalciteParser with DDL parser or TransactionSessionManager
        String sqlTrimmed = sql.trim();
        String sqlUpper = sqlTrimmed.toUpperCase();
        

        // Check for DDL statements - note the space after keywords to avoid matching "CREATED", "CREATES", etc.
        if (sqlUpper.startsWith("CREATE ") || sqlUpper.startsWith("DROP ") || sqlUpper.startsWith("ALTER ") ||
            sqlUpper.equals("BEGIN") || sqlUpper.equals("COMMIT") || sqlUpper.equals("ROLLBACK") ||
            sqlUpper.equals("BEGIN TRANSACTION") || sqlUpper.equals("START TRANSACTION") || sqlUpper.equals("ABORT")) {
            result.setValid(true);
            return result;
        }
        
        // Skip validation for queries with table aliases (temporary workaround)
        // TODO: Implement full alias resolution in validator
        if (hasTableAliases(sql)) {
            result.setValid(true);
            return result;
        }
        
        // Skip validation for aggregation queries (GROUP BY or aggregate functions)
        // These have computed columns (aliases) that don't exist in the base schema
        if (hasAggregationOrGroupBy(sql)) {
            result.setValid(true);
            return result;
        }
        
        try {
            // Parse SQL
            SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            // Validate based on query type
            if (sqlNode instanceof SqlSelect) {
                validateSelect((SqlSelect) sqlNode, result);
            } else if (sqlNode instanceof SqlInsert) {
                validateInsert((SqlInsert) sqlNode, result);
            } else if (sqlNode instanceof SqlUpdate) {
                validateUpdate((SqlUpdate) sqlNode, result);
            } else if (sqlNode instanceof SqlDelete) {
                validateDelete((SqlDelete) sqlNode, result);
            }
            
            result.setValid(!result.hasErrors());
            
        } catch (SqlParseException e) {
            result.addError("SQL Parse Error", formatParseError(e));
            result.addSuggestion("Check your SQL syntax near: " + getErrorContext(sql, e));
            result.setValid(false);
        } catch (Exception e) {
            result.addError("Validation Error", e.getMessage());
            result.setValid(false);
        }
        
        return result;
    }
    
    /**
     * Validate SELECT statement
     */
    private void validateSelect(SqlSelect select, EnhancedValidationResult result) {
        // Validate FROM clause
        if (select.getFrom() != null) {
            validateFrom(select.getFrom(), result);
        }
        
        // Validate SELECT list
        if (select.getSelectList() != null) {
            validateSelectList(select.getSelectList(), select.getFrom(), result);
        }
        
        // Validate WHERE clause
        if (select.getWhere() != null) {
            validateWhere(select.getWhere(), select.getFrom(), result);
        }
        
        // Validate GROUP BY
        if (select.getGroup() != null) {
            validateGroupBy(select.getGroup(), select.getSelectList(), result);
        }
        
        // Validate ORDER BY
        if (select.getOrderList() != null) {
            validateOrderBy(select.getOrderList(), select.getFrom(), result);
        }
    }
    
    /**
     * Validate FROM clause (tables and joins)
     */
    private void validateFrom(SqlNode from, EnhancedValidationResult result) {
        if (from instanceof SqlIdentifier) {
            // Simple table reference
            String tableName = getTableName((SqlIdentifier) from);
            if (!tableExists(tableName)) {
                result.addError(
                    "Table Not Found",
                    String.format("Table '%s' does not exist", tableName)
                );
                result.addSuggestion(getTableSuggestion(tableName));
            }
        } else if (from instanceof SqlJoin) {
            // JOIN - validate both sides
            SqlJoin join = (SqlJoin) from;
            validateFrom(join.getLeft(), result);
            validateFrom(join.getRight(), result);
            
            // Validate join condition
            if (join.getCondition() != null) {
                validateJoinCondition(join.getCondition(), join.getLeft(), join.getRight(), result);
            }
        } else if (from instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) from;
            // Handle AS (table alias)
            if (call.getOperator().getName().equalsIgnoreCase("AS")) {
                validateFrom(call.operand(0), result);
            }
        }
    }
    
    /**
     * Validate SELECT list columns
     */
    private void validateSelectList(SqlNodeList selectList, SqlNode from, EnhancedValidationResult result) {
        for (SqlNode node : selectList) {
            if (node instanceof SqlIdentifier) {
                SqlIdentifier id = (SqlIdentifier) node;
                if (!id.isStar()) {
                    validateColumnReference(id, from, result);
                }
            } else if (node instanceof SqlBasicCall) {
                validateExpression((SqlBasicCall) node, from, result);
            } else if (node instanceof SqlLiteral) {
                // Literals are now supported - SqlToCqlTranslator automatically adds aliases
                // No validation error needed
            }
        }
    }
    
    /**
     * Validate WHERE clause
     */
    private void validateWhere(SqlNode where, SqlNode from, EnhancedValidationResult result) {
        if (where instanceof SqlBasicCall) {
            validateExpression((SqlBasicCall) where, from, result);
        }
    }
    
    /**
     * Validate expression (operators, functions, etc.)
     */
    private void validateExpression(SqlBasicCall call, SqlNode from, EnhancedValidationResult result) {
        String operator = call.getOperator().getName();
        
        // Validate operands
        for (SqlNode operand : call.getOperandList()) {
            if (operand instanceof SqlIdentifier) {
                SqlIdentifier id = (SqlIdentifier) operand;
                if (!id.isStar()) {
                    validateColumnReference(id, from, result);
                }
            } else if (operand instanceof SqlBasicCall) {
                validateExpression((SqlBasicCall) operand, from, result);
            }
        }
        
        // Type checking for binary operators
        if (isBinaryOperator(operator) && call.getOperandList().size() >= 2) {
            validateBinaryOperatorTypes(call, from, result);
        }
    }
    
    /**
     * Validate column reference
     */
    private void validateColumnReference(SqlIdentifier id, SqlNode from, EnhancedValidationResult result) {
        // Handle qualified identifiers (e.g., users.id)
        String columnName;
        String tableName = null;
        
        if (id.names.size() > 1) {
            // Qualified: table.column
            tableName = id.names.get(0).toLowerCase();
            columnName = id.names.get(id.names.size() - 1).toLowerCase();
        } else {
            // Unqualified: column
            columnName = id.getSimple().toLowerCase();
        }
        
        // If table is specified, check that table and column exist
        if (tableName != null) {
            if (!tableExists(tableName)) {
                result.addError(
                    "Table Not Found",
                    String.format("Table '%s' does not exist", tableName)
                );
                result.addSuggestion(getTableSuggestion(tableName));
                return;
            }
            
            if (!columnExists(tableName, columnName)) {
                result.addError(
                    "Column Not Found",
                    String.format("Column '%s' does not exist in table '%s'", columnName, tableName)
                );
                result.addSuggestion(getColumnSuggestion(tableName, columnName));
            }
        } else {
            // Column not qualified - check if it exists in any table in FROM clause
            List<String> tables = extractTableNames(from);
            boolean found = false;
            
            for (String table : tables) {
                if (columnExists(table, columnName)) {
                    found = true;
                    break;
                }
            }
            
            if (!found && !tables.isEmpty()) {
                result.addError(
                    "Column Not Found",
                    String.format("Column '%s' not found in any table", columnName)
                );
                result.addSuggestion(String.format(
                    "💡 Qualify the column with a table name (e.g., table_name.%s)", columnName
                ));
            }
        }
    }
    
    /**
     * Validate binary operator type compatibility
     */
    private void validateBinaryOperatorTypes(SqlBasicCall call, SqlNode from, EnhancedValidationResult result) {
        String operator = call.getOperator().getName();
        
        // Get operand types
        SqlNode left = call.operand(0);
        SqlNode right = call.operand(1);
        
        String leftType = inferType(left, from);
        String rightType = inferType(right, from);
        
        if (leftType != null && rightType != null) {
            if (!areTypesCompatible(leftType, rightType, operator)) {
                result.addWarning(
                    "Type Mismatch",
                    String.format(
                        "Comparing %s with %s using '%s' operator may not work as expected",
                        leftType, rightType, operator
                    )
                );
                result.addSuggestion(String.format(
                    "💡 Consider casting one operand to match the other type"
                ));
            }
        }
    }
    
    /**
     * Validate JOIN condition
     */
    private void validateJoinCondition(SqlNode condition, SqlNode leftTable, SqlNode rightTable, 
                                      EnhancedValidationResult result) {
        if (condition instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) condition;
            
            // Check that join condition references both tables
            Set<String> referencedTables = new HashSet<>();
            collectReferencedTables(call, referencedTables);
            
            if (referencedTables.size() < 2) {
                result.addWarning(
                    "JOIN Condition",
                    "JOIN condition should reference columns from both tables"
                );
            }
        }
    }
    
    /**
     * Validate GROUP BY clause
     */
    private void validateGroupBy(SqlNodeList groupBy, SqlNodeList selectList, EnhancedValidationResult result) {
        // Check that non-aggregated columns in SELECT are in GROUP BY
        Set<String> groupByColumns = new HashSet<>();
        for (SqlNode node : groupBy) {
            if (node instanceof SqlIdentifier) {
                groupByColumns.add(getColumnName((SqlIdentifier) node));
            }
        }
        
        for (SqlNode node : selectList) {
            if (node instanceof SqlIdentifier) {
                String colName = getColumnName((SqlIdentifier) node);
                if (!groupByColumns.contains(colName)) {
                    result.addWarning(
                        "GROUP BY",
                        String.format("Column '%s' should be in GROUP BY clause or use an aggregate function", colName)
                    );
                }
            }
        }
    }
    
    /**
     * Validate ORDER BY clause
     */
    private void validateOrderBy(SqlNodeList orderBy, SqlNode from, EnhancedValidationResult result) {
        for (SqlNode node : orderBy) {
            if (node instanceof SqlIdentifier) {
                validateColumnReference((SqlIdentifier) node, from, result);
            }
        }
    }
    
    /**
     * Validate INSERT statement
     */
    private void validateInsert(SqlInsert insert, EnhancedValidationResult result) {
        // Get table name
        SqlNode targetTable = insert.getTargetTable();
        if (targetTable instanceof SqlIdentifier) {
            String tableName = getTableName((SqlIdentifier) targetTable);
            
            if (!tableExists(tableName)) {
                result.addError(
                    "Table Not Found",
                    String.format("Table '%s' does not exist", tableName)
                );
                result.addSuggestion(getTableSuggestion(tableName));
                return;
            }
            
            // Validate columns if specified
            SqlNodeList targetColumnList = insert.getTargetColumnList();
            if (targetColumnList != null) {
                for (SqlNode col : targetColumnList) {
                    if (col instanceof SqlIdentifier) {
                        String colName = getColumnName((SqlIdentifier) col);
                        if (!columnExists(tableName, colName)) {
                            result.addError(
                                "Column Not Found",
                                String.format("Column '%s' does not exist in table '%s'", colName, tableName)
                            );
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Validate UPDATE statement
     */
    private void validateUpdate(SqlUpdate update, EnhancedValidationResult result) {
        // Get table name
        SqlNode targetTable = update.getTargetTable();
        if (targetTable instanceof SqlIdentifier) {
            String tableName = getTableName((SqlIdentifier) targetTable);
            
            if (!tableExists(tableName)) {
                result.addError(
                    "Table Not Found",
                    String.format("Table '%s' does not exist", tableName)
                );
                return;
            }
            
            // Validate SET columns
            SqlNodeList targetColumnList = update.getTargetColumnList();
            if (targetColumnList != null) {
                for (SqlNode col : targetColumnList) {
                    if (col instanceof SqlIdentifier) {
                        String colName = getColumnName((SqlIdentifier) col);
                        if (!columnExists(tableName, colName)) {
                            result.addError(
                                "Column Not Found",
                                String.format("Column '%s' does not exist in table '%s'", colName, tableName)
                            );
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Validate DELETE statement
     */
    private void validateDelete(SqlDelete delete, EnhancedValidationResult result) {
        // Get table name
        SqlNode targetTable = delete.getTargetTable();
        if (targetTable instanceof SqlIdentifier) {
            String tableName = getTableName((SqlIdentifier) targetTable);
            
            if (!tableExists(tableName)) {
                result.addError(
                    "Table Not Found",
                    String.format("Table '%s' does not exist", tableName)
                );
            }
        }
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Check if table exists
     */
    private boolean tableExists(String tableName) {
        // In KV mode, check SchemaManager directly
        if (config != null && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV && schemaManager != null) {
            return schemaManager.getTable(tableName) != null;
        }
        
        // Cassandra schema mode - use Cassandra metadata
        return getTableMetadata(tableName) != null;
    }
    
    /**
     * Check if column exists in table
     */
    private boolean columnExists(String tableName, String columnName) {
        TableMetadata table = getTableMetadata(tableName);
        if (table == null) {
            return false;
        }
        
        return table.getColumn(columnName).isPresent();
    }
    
    /**
     * Get table metadata (with caching)
     */
    private TableMetadata getTableMetadata(String tableName) {
        if (tableCache.containsKey(tableName)) {
            return tableCache.get(tableName);
        }
        
        try {
            // In KV mode, check SchemaManager instead of Cassandra system schema
            if (config != null && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV && schemaManager != null) {
                // KV mode - check if table exists in SchemaManager
                com.geico.poc.cassandrasql.kv.TableMetadata kvTable = schemaManager.getTable(tableName);
                if (kvTable != null) {
                    // Table exists in KV mode, but we can't return it as Cassandra TableMetadata
                    // For now, return a dummy TableMetadata to indicate existence
                    // The validation will skip detailed column checks in KV mode anyway
                    return null; // Will be handled by tableExists() returning true
                }
                return null;
            }
            
            // Cassandra schema mode - query system schema
            KeyspaceMetadata keyspaceMetadata = session.getMetadata()
                .getKeyspace(keyspaceConfig.getDefaultKeyspace())
                .orElse(null);
            
            if (keyspaceMetadata == null) {
                return null;
            }
            
            TableMetadata table = keyspaceMetadata.getTable(tableName).orElse(null);
            if (table != null) {
                tableCache.put(tableName, table);
            }
            
            return table;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Infer type of expression
     */
    private String inferType(SqlNode node, SqlNode from) {
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            String columnName;
            String tableName = null;
            
            if (id.names.size() > 1) {
                // Qualified: table.column
                tableName = id.names.get(0).toLowerCase();
                columnName = id.names.get(id.names.size() - 1).toLowerCase();
            } else {
                // Unqualified: column
                columnName = id.getSimple().toLowerCase();
                // Try to find table
                List<String> tables = extractTableNames(from);
                for (String table : tables) {
                    if (columnExists(table, columnName)) {
                        tableName = table;
                        break;
                    }
                }
            }
            
            if (tableName != null) {
                return getColumnType(tableName, columnName);
            }
        } else if (node instanceof SqlLiteral) {
            SqlLiteral literal = (SqlLiteral) node;
            return literal.getTypeName().getName();
        }
        
        return null;
    }
    
    /**
     * Get column type
     */
    private String getColumnType(String tableName, String columnName) {
        TableMetadata table = getTableMetadata(tableName);
        if (table == null) {
            return null;
        }
        
        ColumnMetadata column = table.getColumn(columnName).orElse(null);
        if (column == null) {
            return null;
        }
        
        return cassandraTypeToSqlType(column.getType());
    }
    
    /**
     * Convert Cassandra type to SQL type name
     */
    private String cassandraTypeToSqlType(DataType type) {
        String typeName = type.toString().toLowerCase();
        
        if (typeName.contains("int")) return "INTEGER";
        if (typeName.contains("bigint")) return "BIGINT";
        if (typeName.contains("text") || typeName.contains("varchar")) return "VARCHAR";
        if (typeName.contains("boolean")) return "BOOLEAN";
        if (typeName.contains("decimal")) return "DECIMAL";
        if (typeName.contains("double")) return "DOUBLE";
        if (typeName.contains("float")) return "FLOAT";
        if (typeName.contains("timestamp")) return "TIMESTAMP";
        
        return "UNKNOWN";
    }
    
    /**
     * Check if types are compatible
     */
    private boolean areTypesCompatible(String type1, String type2, String operator) {
        // Numeric types are compatible with each other
        Set<String> numericTypes = Set.of("INTEGER", "BIGINT", "DECIMAL", "DOUBLE", "FLOAT");
        
        if (numericTypes.contains(type1) && numericTypes.contains(type2)) {
            return true;
        }
        
        // Same types are always compatible
        if (type1.equals(type2)) {
            return true;
        }
        
        // VARCHAR is compatible with most things for equality
        if (operator.equals("=") || operator.equals("!=")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Extract table names from FROM clause
     */
    private List<String> extractTableNames(SqlNode from) {
        List<String> tables = new ArrayList<>();
        
        if (from instanceof SqlIdentifier) {
            tables.add(getTableName((SqlIdentifier) from));
        } else if (from instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) from;
            tables.addAll(extractTableNames(join.getLeft()));
            tables.addAll(extractTableNames(join.getRight()));
        } else if (from instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) from;
            if (call.getOperator().getName().equalsIgnoreCase("AS")) {
                tables.addAll(extractTableNames(call.operand(0)));
            }
        }
        
        return tables;
    }
    
    /**
     * Collect referenced tables from expression
     */
    private void collectReferencedTables(SqlNode node, Set<String> tables) {
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            if (id.names.size() > 1) {
                tables.add(id.names.get(0).toLowerCase());
            }
        } else if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            for (SqlNode operand : call.getOperandList()) {
                collectReferencedTables(operand, tables);
            }
        }
    }
    
    /**
     * Check if operator is binary
     */
    private boolean isBinaryOperator(String operator) {
        return Set.of("=", "!=", "<>", "<", ">", "<=", ">=", "+", "-", "*", "/", "AND", "OR")
            .contains(operator.toUpperCase());
    }
    
    /**
     * Get suggestion for similar table name
     */
    private String getTableSuggestion(String tableName) {
        try {
            // In KV mode, use SchemaManager instead of Cassandra system schema
            List<String> availableTables;
            if (config != null && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV && schemaManager != null) {
                availableTables = schemaManager.getAllTableNames();
            } else {
                // Cassandra schema mode - query system schema
                KeyspaceMetadata keyspaceMetadata = session.getMetadata()
                    .getKeyspace(keyspaceConfig.getDefaultKeyspace())
                    .orElse(null);
                
                if (keyspaceMetadata == null) {
                    return "💡 Check that the table name is correct";
                }
                
                availableTables = new ArrayList<>();
                for (TableMetadata table : keyspaceMetadata.getTables().values()) {
                    String existingTable = table.getName().toString();
                    // Filter out internal KV tables
                    if (!existingTable.startsWith("kv_") && 
                        !existingTable.startsWith("tx_") &&
                        !existingTable.startsWith("pg_") &&
                        !existingTable.startsWith("information_schema_")) {
                        availableTables.add(existingTable);
                    }
                }
            }
            
            // Find similar table names
            List<String> similarTables = new ArrayList<>();
            for (String existingTable : availableTables) {
                if (isSimilar(tableName, existingTable)) {
                    similarTables.add(existingTable);
                }
            }
            
            if (!similarTables.isEmpty()) {
                return String.format("💡 Did you mean: %s?", String.join(", ", similarTables));
            }
            
            return "💡 Available tables: " + availableTables.toString();
        } catch (Exception e) {
            return "💡 Check that the table name is correct";
        }
    }
    
    /**
     * Get suggestion for similar column name
     */
    private String getColumnSuggestion(String tableName, String columnName) {
        TableMetadata table = getTableMetadata(tableName);
        if (table == null) {
            return "💡 Check that the column name is correct";
        }
        
        // Find similar column names
        List<String> similarColumns = new ArrayList<>();
        for (ColumnMetadata column : table.getColumns().values()) {
            String existingColumn = column.getName().toString();
            if (isSimilar(columnName, existingColumn)) {
                similarColumns.add(existingColumn);
            }
        }
        
        if (!similarColumns.isEmpty()) {
            return String.format("💡 Did you mean: %s?", String.join(", ", similarColumns));
        }
        
        List<String> allColumns = new ArrayList<>();
        for (ColumnMetadata column : table.getColumns().values()) {
            allColumns.add(column.getName().toString());
        }
        
        return "💡 Available columns: " + String.join(", ", allColumns);
    }
    
    /**
     * Check if two strings are similar (for suggestions)
     */
    private boolean isSimilar(String s1, String s2) {
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();
        
        // Check if one contains the other
        if (s1.contains(s2) || s2.contains(s1)) {
            return true;
        }
        
        // Check Levenshtein distance
        return levenshteinDistance(s1, s2) <= 2;
    }
    
    /**
     * Calculate Levenshtein distance
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
    
    /**
     * Format parse error
     */
    private String formatParseError(SqlParseException e) {
        return String.format("at line %d, column %d: %s",
            e.getPos().getLineNum(),
            e.getPos().getColumnNum(),
            e.getMessage()
        );
    }
    
    /**
     * Get error context from SQL
     */
    private String getErrorContext(String sql, SqlParseException e) {
        int pos = e.getPos().getColumnNum();
        int start = Math.max(0, pos - 20);
        int end = Math.min(sql.length(), pos + 20);
        
        return "..." + sql.substring(start, end) + "...";
    }
}

