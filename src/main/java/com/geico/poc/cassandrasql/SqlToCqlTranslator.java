package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates PostgreSQL-compatible SQL to Cassandra CQL
 */
public class SqlToCqlTranslator {
    
    /**
     * Translate CREATE TABLE from PostgreSQL syntax to Cassandra CQL
     * 
     * PostgreSQL: CREATE TABLE users (id INT PRIMARY KEY, name TEXT, email TEXT)
     * Cassandra:  CREATE TABLE users (id INT PRIMARY KEY, name TEXT, email TEXT)
     * 
     * PostgreSQL: CREATE TABLE users (id INT, name TEXT, PRIMARY KEY (id))
     * Cassandra:  CREATE TABLE users (id INT, name TEXT, PRIMARY KEY (id))
     */
    public static String translateCreateTable(String sql) {
        System.out.println("Translating CREATE TABLE: " + sql);
        
        // Extract table name
        Pattern tablePattern = Pattern.compile(
            "CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.+)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher tableMatcher = tablePattern.matcher(sql.trim());
        
        if (!tableMatcher.find()) {
            throw new IllegalArgumentException("Invalid CREATE TABLE syntax");
        }
        
        String tableName = tableMatcher.group(1);
        String columnsDef = tableMatcher.group(2).trim();
        
        // Parse column definitions
        List<ColumnDef> columns = new ArrayList<>();
        String primaryKeyDef = null;
        boolean hasInlinePrimaryKey = false;
        
        // Split by comma (but not inside parentheses)
        List<String> parts = splitByComma(columnsDef);
        
        for (String part : parts) {
            part = part.trim();
            
            // Check if it's a PRIMARY KEY constraint
            if (part.toUpperCase().startsWith("PRIMARY KEY")) {
                primaryKeyDef = part;
                continue;
            }
            
            // Check if it's a CONSTRAINT
            if (part.toUpperCase().startsWith("CONSTRAINT")) {
                // Skip constraints for now
                continue;
            }
            
            // Parse column definition
            ColumnDef col = parseColumnDef(part);
            columns.add(col);
            
            if (col.isPrimaryKey) {
                hasInlinePrimaryKey = true;
            }
        }
        
        // Build CQL
        StringBuilder cql = new StringBuilder();
        cql.append("CREATE TABLE ").append(tableName).append(" (");
        
        // Add columns
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) cql.append(", ");
            cql.append(columns.get(i).toCql());
        }
        
        // Add PRIMARY KEY constraint if defined separately
        if (primaryKeyDef != null) {
            cql.append(", ").append(translatePrimaryKey(primaryKeyDef));
        }
        
        cql.append(")");
        
        String result = cql.toString();
        System.out.println("Translated to CQL: " + result);
        return result;
    }
    
    /**
     * Parse a single column definition
     */
    private static ColumnDef parseColumnDef(String def) {
        // Pattern: column_name data_type [PRIMARY KEY] [NOT NULL] [DEFAULT value]
        String[] tokens = def.trim().split("\\s+");
        
        if (tokens.length < 2) {
            throw new IllegalArgumentException("Invalid column definition: " + def);
        }
        
        String name = tokens[0];
        String type = translateDataType(tokens[1]);
        boolean isPrimaryKey = false;
        boolean notNull = false;
        
        // Check for constraints
        String remaining = def.substring(def.indexOf(tokens[1]) + tokens[1].length()).trim();
        
        if (remaining.toUpperCase().contains("PRIMARY KEY")) {
            isPrimaryKey = true;
            remaining = remaining.replaceAll("(?i)PRIMARY\\s+KEY", "").trim();
        }
        
        if (remaining.toUpperCase().contains("NOT NULL")) {
            notNull = true;
            remaining = remaining.replaceAll("(?i)NOT\\s+NULL", "").trim();
        }
        
        return new ColumnDef(name, type, isPrimaryKey, notNull);
    }
    
    /**
     * Translate PostgreSQL data types to Cassandra data types
     */
    private static String translateDataType(String pgType) {
        String upper = pgType.toUpperCase();
        
        // Handle common PostgreSQL types
        switch (upper) {
            // Integer types
            case "SMALLINT":
            case "INT2":
                return "SMALLINT";
            case "INTEGER":
            case "INT":
            case "INT4":
                return "INT";
            case "BIGINT":
            case "INT8":
                return "BIGINT";
            
            // Floating point
            case "REAL":
            case "FLOAT4":
                return "FLOAT";
            case "DOUBLE":
            case "DOUBLE PRECISION":
            case "FLOAT8":
                return "DOUBLE";
            case "NUMERIC":
            case "DECIMAL":
                return "DECIMAL";
            
            // String types
            case "VARCHAR":
            case "CHARACTER VARYING":
            case "CHAR":
            case "CHARACTER":
            case "TEXT":
                return "TEXT";
            
            // Boolean
            case "BOOLEAN":
            case "BOOL":
                return "BOOLEAN";
            
            // Date/Time
            case "TIMESTAMP":
            case "TIMESTAMP WITHOUT TIME ZONE":
                return "TIMESTAMP";
            case "DATE":
                return "DATE";
            case "TIME":
                return "TIME";
            
            // UUID
            case "UUID":
                return "UUID";
            
            // Binary
            case "BYTEA":
                return "BLOB";
            
            // JSON (Cassandra doesn't have native JSON, use TEXT)
            case "JSON":
            case "JSONB":
                return "TEXT";
            
            default:
                // If type contains parentheses (e.g., VARCHAR(255)), extract base type
                if (pgType.contains("(")) {
                    String baseType = pgType.substring(0, pgType.indexOf('(')).trim();
                    return translateDataType(baseType);
                }
                // Pass through unknown types (might be valid Cassandra types)
                return pgType;
        }
    }
    
    /**
     * Translate PRIMARY KEY constraint
     */
    private static String translatePrimaryKey(String pkDef) {
        // Extract column names from PRIMARY KEY (col1, col2, ...)
        Pattern pattern = Pattern.compile(
            "PRIMARY\\s+KEY\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(pkDef);
        
        if (matcher.find()) {
            String columns = matcher.group(1).trim();
            return "PRIMARY KEY (" + columns + ")";
        }
        
        return pkDef;
    }
    
    /**
     * Split by comma, but not inside parentheses
     */
    private static List<String> splitByComma(String str) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        
        for (char c : str.toCharArray()) {
            if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && parenDepth == 0) {
                parts.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        
        return parts;
    }
    
    /**
     * Column definition
     */
    private static class ColumnDef {
        String name;
        String type;
        boolean isPrimaryKey;
        boolean notNull;
        
        ColumnDef(String name, String type, boolean isPrimaryKey, boolean notNull) {
            this.name = name;
            this.type = type;
            this.isPrimaryKey = isPrimaryKey;
            this.notNull = notNull;
        }
        
        String toCql() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(" ").append(type);
            
            if (isPrimaryKey) {
                sb.append(" PRIMARY KEY");
            }
            
            // Note: Cassandra doesn't have NOT NULL constraint (all non-key columns are nullable)
            // We silently ignore it for compatibility
            
            return sb.toString();
        }
    }
    
    /**
     * Translate PostgreSQL CREATE INDEX to Cassandra CQL
     * 
     * PostgreSQL syntax:
     *   CREATE INDEX idx_name ON table_name (column_name)
     *   CREATE UNIQUE INDEX idx_name ON table_name (column_name)
     *   CREATE INDEX idx_name ON table_name (column1, column2)
     * 
     * Cassandra CQL syntax:
     *   CREATE INDEX idx_name ON table_name (column_name)
     *   CREATE INDEX ON table_name (column_name)  -- Auto-generated name
     * 
     * Note: Cassandra doesn't support UNIQUE indices (uniqueness enforced by primary key)
     */
    public static String translateCreateIndex(String postgresSql) {
        String sql = postgresSql.trim();
        String sqlUpper = sql.toUpperCase();
        
        // Remove UNIQUE keyword if present (Cassandra doesn't support it)
        if (sqlUpper.startsWith("CREATE UNIQUE INDEX")) {
            System.out.println("Warning: UNIQUE constraint ignored - Cassandra enforces uniqueness via PRIMARY KEY");
            sql = sql.replaceFirst("(?i)CREATE\\s+UNIQUE\\s+INDEX", "CREATE INDEX");
        }
        
        // Cassandra CREATE INDEX syntax is very similar to PostgreSQL
        // Just pass through with warning about UNIQUE
        return sql;
    }
    
    /**
     * Translate PostgreSQL DROP TABLE to Cassandra CQL
     * 
     * PostgreSQL: DROP TABLE table_name
     * Cassandra:  DROP TABLE table_name
     * 
     * PostgreSQL: DROP TABLE IF EXISTS table_name
     * Cassandra:  DROP TABLE IF EXISTS table_name
     */
    public static String translateDropTable(String postgresSql) {
        // DROP TABLE syntax is identical in PostgreSQL and Cassandra
        return postgresSql.trim();
    }
    
    /**
     * Translate other SQL statements (INSERT, SELECT, UPDATE, DELETE)
     * For MVP, these are mostly compatible between PostgreSQL and Cassandra
     */
    public static String translate(String sql) {
        String sqlUpper = sql.trim().toUpperCase();
        
        if (sqlUpper.startsWith("CREATE TABLE")) {
            return translateCreateTable(sql);
        } else if (sqlUpper.startsWith("CREATE INDEX") || sqlUpper.startsWith("CREATE UNIQUE INDEX")) {
            return translateCreateIndex(sql);
        }
        
        // For SELECT statements, add literal aliases, remove table aliases, and remove ORDER BY and LIMIT
        // These are handled by ResultProcessor in our SQL layer
        if (sqlUpper.startsWith("SELECT")) {
            System.out.println("[SqlToCqlTranslator] Original SQL: " + sql);
            
            // Step 1: Remove table aliases (Cassandra doesn't support them)
            String withoutAliases = removeTableAliases(sql);
            System.out.println("[SqlToCqlTranslator] After removing aliases: " + withoutAliases);
            
            // Step 2: Add literal aliases
            String withLiteralAliases = addLiteralAliases(withoutAliases);
            System.out.println("[SqlToCqlTranslator] After adding literal aliases: " + withLiteralAliases);
            
            // Step 3: Strip ORDER BY and LIMIT
            String result = stripOrderByAndLimit(withLiteralAliases);
            
            // Step 4: Add ALLOW FILTERING if there's a WHERE clause
            // Cassandra requires this for filtering on non-primary-key columns
            if (result.toUpperCase().contains(" WHERE ") && !result.toUpperCase().contains("ALLOW FILTERING")) {
                result = result.trim();
                if (result.endsWith(";")) {
                    result = result.substring(0, result.length() - 1).trim();
                }
                result += " ALLOW FILTERING";
                System.out.println("[SqlToCqlTranslator] Added ALLOW FILTERING");
            }
            
            System.out.println("[SqlToCqlTranslator] Final CQL: " + result);
            
            return result;
        }
        
        // For other statements, minimal translation needed
        // PostgreSQL and Cassandra have similar syntax for basic DML
        return sql;
    }
    
    /**
     * Remove table aliases from SQL (Cassandra doesn't support them)
     * Also rewrites qualified column references
     * 
     * Examples:
     *   FROM users u           -> FROM users
     *   FROM users AS u        -> FROM users
     *   u.id                   -> id
     *   users.id               -> id (if unambiguous)
     */
    public static String removeTableAliases(String sql) {
        try {
            // Parse SQL to find table aliases
            SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            if (!(sqlNode instanceof SqlSelect)) {
                return sql; // Not a SELECT, return as-is
            }
            
            SqlSelect select = (SqlSelect) sqlNode;
            
            // Build alias map (alias -> table name)
            Map<String, String> aliasMap = new HashMap<>();
            extractAliases(select.getFrom(), aliasMap);
            
            if (aliasMap.isEmpty()) {
                return sql; // No aliases to remove
            }
            
            System.out.println("[SqlToCqlTranslator] Found aliases: " + aliasMap);
            
            // Rewrite the SQL
            String result = sql;
            
            // Remove aliases from FROM clause
            for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
                String alias = entry.getKey();
                String tableName = entry.getValue();
                
                // Pattern: "table_name alias" or "table_name AS alias"
                result = result.replaceAll("(?i)\\b" + tableName + "\\s+AS\\s+" + alias + "\\b", tableName);
                result = result.replaceAll("(?i)\\b" + tableName + "\\s+" + alias + "\\b", tableName);
            }
            
            // Rewrite qualified column references (alias.column -> column)
            for (String alias : aliasMap.keySet()) {
                result = result.replaceAll("(?i)\\b" + alias + "\\.", "");
            }
            
            return result;
            
        } catch (Exception e) {
            System.err.println("[SqlToCqlTranslator] Error removing aliases: " + e.getMessage());
            // If parsing fails, try simple regex-based removal
            return removeAliasesSimple(sql);
        }
    }
    
    /**
     * Simple regex-based alias removal (fallback)
     */
    private static String removeAliasesSimple(String sql) {
        // Remove single-letter aliases after table names in FROM clause
        // Pattern: "FROM table_name x" -> "FROM table_name"
        String result = sql.replaceAll("(?i)FROM\\s+(\\w+)\\s+([a-z])\\b", "FROM $1");
        
        // Remove qualified references with single-letter aliases
        // Pattern: "x.column" -> "column"
        result = result.replaceAll("(?i)\\b([a-z])\\.", "");
        
        return result;
    }
    
    /**
     * Extract table aliases from FROM clause
     */
    private static void extractAliases(SqlNode from, Map<String, String> aliasMap) {
        if (from == null) {
            return;
        }
        
        if (from instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) from;
            
            // Check for AS operator (table AS alias)
            if (call.getOperator().getName().equalsIgnoreCase("AS")) {
                if (call.getOperandList().size() >= 2) {
                    SqlNode tableNode = call.getOperandList().get(0);
                    SqlNode aliasNode = call.getOperandList().get(1);
                    
                    if (tableNode instanceof SqlIdentifier && aliasNode instanceof SqlIdentifier) {
                        String tableName = ((SqlIdentifier) tableNode).getSimple();
                        String alias = ((SqlIdentifier) aliasNode).getSimple();
                        aliasMap.put(alias, tableName);
                    }
                }
            }
            // Check for JOIN
            else if (call.getOperator().getName().toUpperCase().contains("JOIN")) {
                // Recursively extract from both sides
                for (SqlNode operand : call.getOperandList()) {
                    extractAliases(operand, aliasMap);
                }
            }
        } else if (from instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) from;
            // Check for implicit alias (table alias without AS)
            // This is tricky to detect from AST alone
            if (id.names.size() == 2) {
                // Might be "table alias" parsed as qualified identifier
                aliasMap.put(id.names.get(1), id.names.get(0));
            }
        }
    }
    
    /**
     * Strip ORDER BY and LIMIT clauses from SELECT statements
     * These are handled by ResultProcessor, not Cassandra
     */
    private static String stripOrderByAndLimit(String sql) {
        String result = sql;
        
        // Remove ORDER BY clause (everything from ORDER BY to LIMIT or end of string)
        // Use case-insensitive matching
        result = result.replaceAll("(?i)\\s+ORDER\\s+BY\\s+[^;]+?(?=(\\s+LIMIT|\\s*;|$))", "");
        
        // Remove LIMIT clause (LIMIT n or LIMIT n OFFSET m)
        result = result.replaceAll("(?i)\\s+LIMIT\\s+\\d+(\\s+OFFSET\\s+\\d+)?\\s*", " ");
        
        // Clean up trailing semicolon if present
        result = result.trim();
        if (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        
        return result;
    }
    
    /**
     * Add CAST to literals in SELECT clause for Cassandra compatibility
     * Cassandra requires literals to be cast to a specific type
     * 
     * Examples:
     *   SELECT 1 FROM table           -> SELECT CAST(1 AS INT) AS col_1 FROM table
     *   SELECT 1, 'test', true        -> SELECT CAST(1 AS INT) AS col_1, CAST('test' AS TEXT) AS col_2, CAST(true AS BOOLEAN) AS col_3
     *   SELECT id, 1, name            -> SELECT id, CAST(1 AS INT) AS col_1, name
     */
    public static String addLiteralAliases(String sql) {
        // Pattern to match SELECT clause
        Pattern selectPattern = Pattern.compile(
            "SELECT\\s+(.+?)\\s+FROM",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher selectMatcher = selectPattern.matcher(sql);
        
        if (!selectMatcher.find()) {
            return sql; // Not a SELECT with FROM, return as-is
        }
        
        String selectClause = selectMatcher.group(1).trim();
        String beforeSelect = sql.substring(0, selectMatcher.start());
        String afterFrom = sql.substring(selectMatcher.end() - 4); // Include "FROM"
        
        // Split by comma (but not inside parentheses or strings)
        List<String> selectItems = splitSelectItems(selectClause);
        List<String> translatedItems = new ArrayList<>();
        int literalCounter = 1;
        
        for (String item : selectItems) {
            String trimmed = item.trim();
            
            // Check if it's a literal without an alias
            if (isLiteralWithoutAlias(trimmed)) {
                // Add CAST and alias
                String castType = inferLiteralType(trimmed);
                String castExpr = "CAST(" + trimmed + " AS " + castType + ") AS col_" + literalCounter;
                translatedItems.add(castExpr);
                literalCounter++;
            } else {
                // Keep as-is
                translatedItems.add(trimmed);
            }
        }
        
        // Reconstruct SQL
        return beforeSelect + "SELECT " + String.join(", ", translatedItems) + " " + afterFrom;
    }
    
    /**
     * Split SELECT items by comma, respecting parentheses and strings
     */
    private static List<String> splitSelectItems(String selectClause) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        boolean inString = false;
        char stringChar = 0;
        
        for (int i = 0; i < selectClause.length(); i++) {
            char c = selectClause.charAt(i);
            
            // Handle string literals
            if ((c == '\'' || c == '"') && (i == 0 || selectClause.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                current.append(c);
            } else if (inString) {
                current.append(c);
            } else if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && parenDepth == 0) {
                items.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            items.add(current.toString().trim());
        }
        
        return items;
    }
    
    /**
     * Infer Cassandra type for a literal value
     */
    private static String inferLiteralType(String literal) {
        String upper = literal.toUpperCase();
        
        // Boolean
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
            return "BOOLEAN";
        }
        
        // NULL
        if (upper.equals("NULL")) {
            return "TEXT"; // Default to TEXT for NULL
        }
        
        // Number (integer)
        if (literal.matches("-?\\d+")) {
            long value = Long.parseLong(literal);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return "INT";
            } else {
                return "BIGINT";
            }
        }
        
        // Number (decimal)
        if (literal.matches("-?\\d+\\.\\d+")) {
            return "DECIMAL";
        }
        
        // String literal
        if ((literal.startsWith("'") && literal.endsWith("'")) ||
            (literal.startsWith("\"") && literal.endsWith("\""))) {
            return "TEXT";
        }
        
        // Default to TEXT
        return "TEXT";
    }
    
    /**
     * Check if a SELECT item is a literal without an alias
     * 
     * Literals:
     *   - Numbers: 1, 3.14, -5
     *   - Strings: 'text', "text"
     *   - Booleans: true, false
     *   - NULL
     * 
     * Not literals:
     *   - Column names: id, user_id, table.column
     *   - Function calls: COUNT(*), SUM(amount)
     *   - Expressions with AS: 1 AS col, COUNT(*) AS total
     */
    private static boolean isLiteralWithoutAlias(String item) {
        String upper = item.toUpperCase();
        
        // Already has an alias
        if (upper.contains(" AS ")) {
            return false;
        }
        
        // Function call (has parentheses)
        if (item.contains("(")) {
            return false;
        }
        
        // Wildcard
        if (item.equals("*")) {
            return false;
        }
        
        // Check if it's a literal first (before checking for dots)
        // Number literal (integer or decimal)
        if (item.matches("-?\\d+") || item.matches("-?\\d+\\.\\d+")) {
            return true;
        }
        
        // Qualified column (table.column) - check after number literals
        if (item.contains(".")) {
            return false;
        }
        
        // String literal (single or double quotes)
        if ((item.startsWith("'") && item.endsWith("'")) ||
            (item.startsWith("\"") && item.endsWith("\""))) {
            return true;
        }
        
        // Boolean literal
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
            return true;
        }
        
        // NULL literal
        if (upper.equals("NULL")) {
            return true;
        }
        
        // If it's not a literal and doesn't have an alias, it's likely a column name
        return false;
    }
}

