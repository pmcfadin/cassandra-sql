package com.geico.poc.cassandrasql;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Validates SQL queries against Cassandra schema
 */
@Component
public class SchemaValidator {
    
    private final CassandraExecutor cassandraExecutor;
    private static final String KEYSPACE = "cassandra_sql";
    
    public SchemaValidator(CassandraExecutor cassandraExecutor) {
        this.cassandraExecutor = cassandraExecutor;
    }
    
    private CqlSession getSession() {
        return cassandraExecutor.getSession();
    }
    
    /**
     * Validate a parsed query against the schema
     */
    public ValidationResult validate(ParsedQuery query) {
        ValidationResult result = new ValidationResult();
        
        // Skip validation for DDL statements
        if (query.getType() == ParsedQuery.Type.CREATE_TABLE || 
            query.getType() == ParsedQuery.Type.CREATE_INDEX) {
            return result;
        }
        
        try {
            // Validate based on query type
            if (query.isMultiWayJoin()) {
                validateMultiWayJoin(query.getMultiWayJoin(), result);
            } else if (query.isJoin()) {
                validateJoin(query.getJoinQuery(), result);
            } else if (query.getType() == ParsedQuery.Type.SELECT) {
                validateSelect(query, result);
            }
        } catch (Exception e) {
            result.addError("Schema validation failed: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Validate multi-way join query
     */
    private void validateMultiWayJoin(MultiWayJoinQuery joinQuery, ValidationResult result) {
        // For now, skip validation for multi-way joins with aliases
        // The aliases are resolved at execution time
        // TODO: Enhance to support alias validation
        System.out.println("Schema validation: Skipping detailed validation for multi-way JOIN query (aliases not yet supported)");
    }
    
    /**
     * Validate binary join query
     */
    private void validateJoin(JoinQuery joinQuery, ValidationResult result) {
        // For now, skip validation for binary joins with aliases
        // The aliases are resolved at execution time
        // TODO: Enhance to support alias validation
        System.out.println("Schema validation: Skipping detailed validation for JOIN query (aliases not yet supported)");
    }
    
    /**
     * Validate simple SELECT query
     */
    private void validateSelect(ParsedQuery query, ValidationResult result) {
        String tableName = query.getTableName();
        
        if (!tableExists(tableName)) {
            result.addError("Table does not exist: " + tableName);
        }
    }
    
    /**
     * Validate join condition
     */
    private void validateJoinCondition(JoinCondition condition, ValidationResult result) {
        String leftTable = condition.getLeftTable();
        String rightTable = condition.getRightTable();
        
        validateColumnInTable(condition.getLeftColumn(), leftTable, result);
        validateColumnInTable(condition.getRightColumn(), rightTable, result);
    }
    
    /**
     * Validate column reference (e.g., "u.name" or "name")
     */
    private void validateColumnReference(String columnRef, List<String> tables, 
                                        Map<String, String> aliases, ValidationResult result) {
        String[] parts = columnRef.split("\\.");
        
        if (parts.length == 2) {
            // Qualified: table.column or alias.column
            String tableOrAlias = parts[0];
            String column = parts[1];
            
            // Resolve alias to table name
            String tableName = aliases.getOrDefault(tableOrAlias, tableOrAlias);
            
            validateColumnInTable(column, tableName, result);
        } else if (parts.length == 1) {
            // Unqualified: column
            // Check if column exists in any of the tables
            boolean found = false;
            for (String table : tables) {
                if (columnExistsInTable(parts[0], table)) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                result.addWarning("Column '" + parts[0] + "' not found in any table. " +
                                "Available tables: " + tables);
            }
        }
    }
    
    /**
     * Validate column exists in table
     */
    private void validateColumnInTable(String column, String table, ValidationResult result) {
        // Strip table prefix if present (e.g., "ORDERS.PRODUCT" -> "PRODUCT")
        // This can happen if the parser didn't split the qualified name correctly
        if (column.contains(".")) {
            String[] parts = column.split("\\.");
            if (parts.length == 2) {
                column = parts[1]; // Use just the column name
            }
        }
        
        // Column names from Calcite are often uppercased, but Cassandra stores them lowercase
        // The columnExistsInTable method already handles case-insensitive matching
        if (!columnExistsInTable(column, table)) {
            // Get available columns for helpful error message
            Set<String> availableColumns = getTableColumns(table);
            result.addError("Column '" + column.toUpperCase() + "' does not exist in table '" + table.toUpperCase() + "'. " +
                          "Available columns: " + availableColumns);
        }
    }
    
    /**
     * Check if table exists
     */
    private boolean tableExists(String tableName) {
        CqlSession session = getSession();
        if (session == null) {
            return true; // Skip validation if session not ready
        }
        
        KeyspaceMetadata keyspace = session.getMetadata()
            .getKeyspace(KEYSPACE)
            .orElse(null);
        
        if (keyspace == null) {
            return false;
        }
        
        // Try exact match first
        if (keyspace.getTable(tableName).isPresent()) {
            return true;
        }
        
        // Try case-insensitive match
        String tableNameLower = tableName.toLowerCase();
        return keyspace.getTable(tableNameLower).isPresent();
    }
    
    /**
     * Check if column exists in table
     */
    private boolean columnExistsInTable(String columnName, String tableName) {
        Set<String> columns = getTableColumns(tableName);
        
        // Try exact match
        if (columns.contains(columnName)) {
            return true;
        }
        
        // Try case-insensitive match
        String columnLower = columnName.toLowerCase();
        for (String col : columns) {
            if (col.toLowerCase().equals(columnLower)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get all columns for a table
     */
    private Set<String> getTableColumns(String tableName) {
        Set<String> columns = new HashSet<>();
        
        CqlSession session = getSession();
        if (session == null) {
            return columns; // Skip validation if session not ready
        }
        
        KeyspaceMetadata keyspace = session.getMetadata()
            .getKeyspace(KEYSPACE)
            .orElse(null);
        
        if (keyspace == null) {
            return columns;
        }
        
        // Try exact match first
        Optional<TableMetadata> table = keyspace.getTable(tableName);
        if (!table.isPresent()) {
            // Try case-insensitive
            table = keyspace.getTable(tableName.toLowerCase());
        }
        
        if (table.isPresent()) {
            for (ColumnMetadata col : table.get().getColumns().values()) {
                columns.add(col.getName().toString());
            }
        }
        
        return columns;
    }
    
    /**
     * Validation result
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        public String getErrorMessage() {
            if (errors.isEmpty()) {
                return null;
            }
            return String.join("; ", errors);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (!errors.isEmpty()) {
                sb.append("Errors: ").append(errors);
            }
            if (!warnings.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("Warnings: ").append(warnings);
            }
            return sb.toString();
        }
    }
}

