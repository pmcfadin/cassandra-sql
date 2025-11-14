package com.geico.poc.cassandrasql.validation;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates SQL statements for Cassandra compatibility
 * Prevents unsafe SQL from reaching Cassandra
 */
@Component
public class SqlValidator {
    
    /**
     * Validate SQL and return list of issues
     * Empty list = valid SQL
     */
    public ValidationResult validate(String sql) {
        ValidationResult result = new ValidationResult();
        
        // First, do pre-parsing validation for features that Calcite can't parse
        preParseValidation(sql, result);
        if (result.hasErrors()) {
            return result;
        }
        
        try {
            // Parse SQL
            // Use DDL parser for CREATE/DROP, standard parser for DML
            String sqlUpper = sql.trim().toUpperCase();
            
            // Skip validation for CREATE INDEX, DROP TABLE, and transaction commands
            if (sqlUpper.startsWith("CREATE INDEX") || 
                sqlUpper.startsWith("CREATE UNIQUE INDEX") ||
                sqlUpper.startsWith("DROP TABLE") ||
                sqlUpper.equals("BEGIN") || sqlUpper.equals("COMMIT") || sqlUpper.equals("ROLLBACK")) {
                return result;  // Valid, skip parsing
            }
            
            boolean isDDL = sqlUpper.startsWith("CREATE ") || 
                           sqlUpper.startsWith("DROP ") || 
                           sqlUpper.startsWith("ALTER ");
            
            SqlParser.Config config;
            if (isDDL) {
                config = SqlParser.config()
                    .withParserFactory(SqlDdlParserImpl.FACTORY)
                    .withCaseSensitive(false);
            } else {
                config = SqlParser.config()
                    .withCaseSensitive(false);
            }
            
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            // Validate based on statement type
            if (sqlNode instanceof SqlSelect) {
                validateSelect((SqlSelect) sqlNode, result);
            } else if (sqlNode instanceof SqlInsert) {
                validateInsert((SqlInsert) sqlNode, result);
            } else if (sqlNode instanceof SqlUpdate) {
                validateUpdate((SqlUpdate) sqlNode, result);
            } else if (sqlNode instanceof SqlDelete) {
                validateDelete((SqlDelete) sqlNode, result);
            } else if (sqlNode instanceof SqlCreate) {
                validateCreate((SqlCreate) sqlNode, result);
            } else if (sqlNode instanceof SqlDrop) {
                validateDrop((SqlDrop) sqlNode, result);
            } else {
                // Unknown statement type - allow but warn
                result.addWarning("Unknown statement type: " + sqlNode.getKind());
            }
            
        } catch (SqlParseException e) {
            // If Calcite can't parse it, it's likely invalid
            result.addError("SQL parse error: " + e.getMessage());
        } catch (Exception e) {
            // Unexpected error - be safe and reject
            result.addError("Validation error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Pre-parsing validation for features that Calcite can't parse
     * This catches syntax that would cause parse errors
     */
    private void preParseValidation(String sql, ValidationResult result) {
        String sqlUpper = sql.toUpperCase();
        
        // Window functions are now supported!
        // Removed validation block - window functions will be parsed and executed
        
        // Check for CTEs (WITH clause) - not yet implemented
        if (sqlUpper.trim().startsWith("WITH ")) {
            result.addError("Common Table Expressions (WITH clause) are not yet implemented");
            return;
        }
        
        // Check for UNION/INTERSECT/EXCEPT
        if (sqlUpper.contains(" UNION ") || sqlUpper.contains(" UNION ALL ")) {
            result.addError("UNION is not yet implemented");
            return;
        }
        
        if (sqlUpper.contains(" INTERSECT ")) {
            result.addError("INTERSECT is not yet implemented");
            return;
        }
        
        if (sqlUpper.contains(" EXCEPT ")) {
            result.addError("EXCEPT is not yet implemented");
            return;
        }
        
        // Check for CASE expressions (not well supported in Cassandra)
        if (sqlUpper.contains(" CASE ") && sqlUpper.contains(" WHEN ") && sqlUpper.contains(" END")) {
            result.addWarning("CASE expressions may have limited support in Cassandra");
        }
    }
    
    /**
     * Validate SELECT statement
     */
    private void validateSelect(SqlSelect select, ValidationResult result) {
        // Check for unsupported features
        
        // 1. DISTINCT (Cassandra supports this, but with limitations)
        if (select.isDistinct()) {
            result.addWarning("DISTINCT may have limited support in Cassandra");
        }
        
        // 2. GROUP BY (not yet implemented in our layer)
        if (select.getGroup() != null && select.getGroup().size() > 0) {
            result.addError("GROUP BY is not yet implemented");
        }
        
        // 3. HAVING (not yet implemented)
        if (select.getHaving() != null) {
            result.addError("HAVING clause is not yet implemented");
        }
        
        // 4. Window functions (not supported)
        if (select.getWindowList() != null && select.getWindowList().size() > 0) {
            result.addError("Window functions are not supported");
        }
        
        // 5. UNION/INTERSECT/EXCEPT (not supported)
        // These would be caught at a higher level
        
        // 6. Subqueries (now supported!)
        // No error - subqueries are implemented
        
        // 7. Complex JOINs
        if (select.getFrom() != null) {
            validateFrom(select.getFrom(), result);
        }
        
        // 8. ORDER BY - This is OK, we handle it in ResultProcessor
        // No error needed
        
        // 9. LIMIT/OFFSET - This is OK, we handle it in ResultProcessor
        // No error needed
    }
    
    /**
     * Check if SELECT has subqueries
     */
    private boolean hasSubquery(SqlSelect select) {
        // Check FROM clause
        if (select.getFrom() != null && select.getFrom() instanceof SqlSelect) {
            return true;
        }
        
        // Check WHERE clause for subqueries
        if (select.getWhere() != null) {
            if (containsSubquery(select.getWhere())) {
                return true;
            }
        }
        
        // Check SELECT list for subqueries
        if (select.getSelectList() != null) {
            for (SqlNode node : select.getSelectList()) {
                if (node instanceof SqlSelect || containsSubquery(node)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Recursively check if node contains subquery
     */
    private boolean containsSubquery(SqlNode node) {
        if (node instanceof SqlSelect) {
            return true;
        }
        
        if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            for (SqlNode operand : call.getOperandList()) {
                if (operand != null && containsSubquery(operand)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Validate FROM clause
     */
    private void validateFrom(SqlNode from, ValidationResult result) {
        if (from instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) from;
            
            // Check join type
            if (join.isNatural()) {
                result.addError("NATURAL JOIN is not supported");
            }
            
            // We support INNER, LEFT, RIGHT joins
            // FULL OUTER join is not well-supported
            if (join.getJoinType() == JoinType.FULL) {
                result.addWarning("FULL OUTER JOIN may have limited support");
            }
            
            // Cross joins without conditions are dangerous
            if (join.getJoinType() == JoinType.CROSS) {
                result.addWarning("CROSS JOIN can be very expensive - use with caution");
            }
            
            // Recursively validate nested joins
            validateFrom(join.getLeft(), result);
            validateFrom(join.getRight(), result);
        }
    }
    
    /**
     * Validate INSERT statement
     */
    private void validateInsert(SqlInsert insert, ValidationResult result) {
        // Check for unsupported features
        
        // 1. INSERT ... SELECT (not yet implemented)
        if (insert.getSource() instanceof SqlSelect) {
            result.addError("INSERT ... SELECT is not yet implemented");
        }
        
        // 2. INSERT with DEFAULT values
        // Cassandra doesn't support DEFAULT keyword
        
        // 3. Multi-row INSERT (VALUES (...), (...))
        // This is OK, Cassandra supports it
    }
    
    /**
     * Validate UPDATE statement
     */
    private void validateUpdate(SqlUpdate update, ValidationResult result) {
        // Check for unsupported features
        
        // 1. UPDATE with JOIN (not supported)
        if (update.getTargetTable() instanceof SqlJoin) {
            result.addError("UPDATE with JOIN is not supported");
        }
        
        // 2. UPDATE with subquery in SET clause
        if (update.getSourceExpressionList() != null) {
            for (SqlNode expr : update.getSourceExpressionList()) {
                if (containsSubquery(expr)) {
                    result.addError("UPDATE with subquery in SET clause is not supported");
                }
            }
        }
        
        // 3. UPDATE without WHERE (dangerous but allowed)
        if (update.getCondition() == null) {
            result.addWarning("UPDATE without WHERE clause will affect all rows - use with caution");
        }
    }
    
    /**
     * Validate DELETE statement
     */
    private void validateDelete(SqlDelete delete, ValidationResult result) {
        // Check for unsupported features
        
        // 1. DELETE with JOIN (not supported)
        if (delete.getTargetTable() instanceof SqlJoin) {
            result.addError("DELETE with JOIN is not supported");
        }
        
        // 2. DELETE without WHERE (dangerous but allowed)
        if (delete.getCondition() == null) {
            result.addWarning("DELETE without WHERE clause will delete all rows - use with caution");
        }
    }
    
    /**
     * Validate CREATE statement
     */
    private void validateCreate(SqlCreate create, ValidationResult result) {
        // Most CREATE statements are handled by SqlToCqlTranslator
        // Just check for obviously unsupported features
        
        String sql = create.toString().toUpperCase();
        
        // Check for unsupported CREATE types
        if (sql.contains("CREATE VIEW")) {
            result.addWarning("CREATE VIEW may have limited support");
        }
        
        if (sql.contains("CREATE TRIGGER")) {
            result.addError("CREATE TRIGGER is not supported");
        }
        
        if (sql.contains("CREATE PROCEDURE") || sql.contains("CREATE FUNCTION")) {
            result.addError("Stored procedures/functions are not supported");
        }
        
        if (sql.contains("CREATE SEQUENCE")) {
            result.addError("SEQUENCE is not supported (use UUID or counter columns)");
        }
    }
    
    /**
     * Validate DROP statement
     */
    private void validateDrop(SqlDrop drop, ValidationResult result) {
        // DROP statements are generally safe
        // Just warn about DROP without IF EXISTS
        
        String sql = drop.toString().toUpperCase();
        if (!sql.contains("IF EXISTS")) {
            result.addWarning("Consider using IF EXISTS to avoid errors if object doesn't exist");
        }
    }
    
    /**
     * Check if SQL contains dangerous patterns that might bypass validation
     */
    public ValidationResult validateRawSql(String sql) {
        ValidationResult result = new ValidationResult();
        String upperSql = sql.toUpperCase().trim();
        
        // Check for dangerous patterns in quoted identifiers (table/column names)
        // Even though quoted identifiers can contain any characters, we block
        // dangerous patterns like ; and -- to prevent SQL injection attempts
        if (containsDangerousPatternsInQuotedIdentifiers(sql)) {
            result.addError("Table or column names cannot contain semicolons (;) or SQL comments (-- or /*)");
        }
        
        // Check for SQL injection patterns OUTSIDE of quoted identifiers
        // We need to parse the SQL to distinguish between:
        // - "--" in a quoted identifier (already checked above)
        // - "--" as an actual comment (potentially dangerous)
        if (containsDangerousPatternsOutsideQuotes(sql)) {
            result.addError("SQL contains comment or string termination patterns outside of quoted identifiers");
        }
        
        // Check for system commands
        if (upperSql.contains("TRUNCATE")) {
            result.addWarning("TRUNCATE is destructive - use with caution");
        }
        
        if (upperSql.contains("DROP DATABASE") || upperSql.contains("DROP KEYSPACE")) {
            result.addError("DROP DATABASE/KEYSPACE is not allowed for safety");
        }
        
        // Multiple statements are now supported (removed restriction)
        // DO blocks are also supported
        
        return result;
    }
    
    /**
     * Check if SQL contains dangerous patterns (;, --, /*) inside quoted identifiers
     * This prevents SQL injection attempts via malicious table/column names
     */
    private boolean containsDangerousPatternsInQuotedIdentifiers(String sql) {
        boolean inDoubleQuote = false;
        StringBuilder quotedIdentifier = new StringBuilder();
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;
            
            if (c == '"') {
                if (!inDoubleQuote) {
                    // Start of quoted identifier
                    inDoubleQuote = true;
                    quotedIdentifier.setLength(0);
                } else {
                    // Check for escaped quote
                    if (next == '"') {
                        quotedIdentifier.append('"');
                        i++; // Skip next quote
                        continue;
                    } else {
                        // End of quoted identifier - check its contents
                        String identifier = quotedIdentifier.toString();
                        if (identifier.contains(";") || 
                            identifier.contains("--") || 
                            identifier.contains("/*") ||
                            identifier.contains("*/")) {
                            return true; // Found dangerous pattern in quoted identifier
                        }
                        inDoubleQuote = false;
                        quotedIdentifier.setLength(0);
                    }
                }
            } else if (inDoubleQuote) {
                // Inside quoted identifier - collect characters
                quotedIdentifier.append(c);
            }
        }
        
        // Check if we ended while still inside a quoted identifier
        if (inDoubleQuote) {
            String identifier = quotedIdentifier.toString();
            if (identifier.contains(";") || 
                identifier.contains("--") || 
                identifier.contains("/*") ||
                identifier.contains("*/")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if SQL contains dangerous patterns (--, /*, ';) outside of quoted identifiers
     * This helps prevent SQL injection while allowing quoted identifiers to contain these characters
     */
    private boolean containsDangerousPatternsOutsideQuotes(String sql) {
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean inDollarQuote = false;
        String dollarTag = null;
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;
            char prev = (i > 0) ? sql.charAt(i - 1) : 0;
            
            // Handle dollar-quoted strings (PostgreSQL: $tag$...$tag$)
            if (!inDoubleQuote && !inSingleQuote && c == '$') {
                if (dollarTag == null) {
                    // Start of dollar quote - find the tag
                    int tagEnd = sql.indexOf('$', i + 1);
                    if (tagEnd > i) {
                        dollarTag = sql.substring(i, tagEnd + 1);
                        inDollarQuote = true;
                        i = tagEnd;
                        continue;
                    }
                } else {
                    // Check if this is the end tag
                    if (sql.substring(i).startsWith(dollarTag)) {
                        inDollarQuote = false;
                        dollarTag = null;
                        i += dollarTag.length() - 1;
                        continue;
                    }
                }
            }
            
            // Skip checks if we're inside a quoted identifier or string literal
            if (inDoubleQuote || inSingleQuote || inDollarQuote) {
                // Handle escaped quotes
                if ((c == '"' && inDoubleQuote) || (c == '\'' && inSingleQuote)) {
                    if (next == c) {
                        i++; // Skip escaped quote
                        continue;
                    } else {
                        // End of quote
                        if (c == '"') inDoubleQuote = false;
                        if (c == '\'') inSingleQuote = false;
                        continue;
                    }
                }
                continue; // Inside quotes, skip pattern checking
            }
            
            // Check for double-quote start (quoted identifier)
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            
            // Check for single-quote start (string literal)
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            
            // Check for SQL comment patterns (only outside quotes)
            // Single-line comment: --
            if (c == '-' && next == '-') {
                // Check if this is actually a comment (not part of a larger token)
                // Look for whitespace or start of line before --
                if (i == 0 || Character.isWhitespace(prev) || prev == ';' || prev == '\n' || prev == '\r') {
                    return true; // Found -- comment outside quotes
                }
            }
            
            // Multi-line comment: /*
            if (c == '/' && next == '*') {
                return true; // Found /* comment outside quotes
            }
            
            // Check for string termination pattern: ';
            if (c == '\'' && next == ';') {
                return true; // Found '; pattern (potential SQL injection)
            }
            
            // Check for semicolon followed by DROP/INSERT/UPDATE/DELETE (multiple statements)
            if (c == ';' && i + 1 < sql.length()) {
                String remaining = sql.substring(i + 1).trim().toUpperCase();
                if (remaining.startsWith("DROP") || 
                    remaining.startsWith("INSERT") || 
                    remaining.startsWith("UPDATE") || 
                    remaining.startsWith("DELETE") ||
                    remaining.startsWith("CREATE") ||
                    remaining.startsWith("ALTER") ||
                    remaining.startsWith("TRUNCATE")) {
                    return true; // Found multiple statements
                }
            }
        }
        
        return false;
    }
}

