package com.geico.poc.cassandrasql.kv.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geico.poc.cassandrasql.kv.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Background job that checks for constraint violations.
 * 
 * This job scans tables to detect violations of:
 * 1. UNIQUE constraints - multiple rows with same values in unique columns
 * 2. FOREIGN KEY constraints - rows referencing non-existent parent rows
 * 
 * Unlike enforcement at write-time, this job detects violations that may have
 * occurred due to:
 * - Bugs in constraint enforcement code
 * - Data imported before constraints were added
 * - Concurrent transaction anomalies
 * - System failures during multi-step operations
 * 
 * This job runs less frequently and with aggressive rate limiting since it
 * performs full table scans.
 */
@Component
public class ConstraintViolationCheckerJob implements BackgroundJob {
    
    private static final Logger log = LoggerFactory.getLogger(ConstraintViolationCheckerJob.class);
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    // Rate limiter for scanning operations
    private RateLimiter rateLimiter;
    
    // Violation tracking
    private final Map<String, List<ConstraintViolation>> violationsByTable = new HashMap<>();
    
    @Override
    public String getName() {
        return "ConstraintViolationCheckerJob";
    }
    
    @Override
    public long getInitialDelayMs() {
        // Start after 5 minutes to allow system to stabilize
        return 5 * 60 * 1000;
    }
    
    @Override
    public long getPeriodMs() {
        // Run every 30 minutes (less frequent than other jobs)
        return 30 * 60 * 1000;
    }
    
    @Override
    public boolean isEnabled() {
        // Always enabled in KV mode
        return true;
    }
    
    @Override
    public void execute() {
        try {
            log.info("🔍 Starting constraint violation check...");
            
            // Initialize rate limiter (default: 10,000 keys/hour = ~3 keys/second)
            if (rateLimiter == null) {
                rateLimiter = new RateLimiter(10000);
                log.info("   Rate limiter initialized: " + rateLimiter.getKeysPerHour() + " keys/hour");
            }
            
            // Clear previous violations
            violationsByTable.clear();
            
            // Get all tables
            Collection<TableMetadata> tables = schemaManager.getAllTables();
            
            int tablesChecked = 0;
            int totalViolations = 0;
            
            for (TableMetadata table : tables) {
                // Skip dropped tables
                if (table.getDroppedTimestamp() != null) {
                    continue;
                }
                
                // Skip pg_catalog tables
                if (table.getTableName().startsWith("pg_")) {
                    continue;
                }
                
                log.debug("   Checking constraints for table: {}", table.getTableName());
                
                // Check UNIQUE constraints
                int uniqueViolations = checkUniqueConstraints(table);
                if (uniqueViolations > 0) {
                    log.warn("   ⚠️  Found {} UNIQUE constraint violations in {}", 
                             uniqueViolations, table.getTableName());
                    totalViolations += uniqueViolations;
                }
                
                // Check FOREIGN KEY constraints
                int fkViolations = checkForeignKeyConstraints(table);
                if (fkViolations > 0) {
                    log.warn("   ⚠️  Found {} FOREIGN KEY constraint violations in {}", 
                             fkViolations, table.getTableName());
                    totalViolations += fkViolations;
                }
                
                tablesChecked++;
            }
            
            if (totalViolations == 0) {
                log.info("   ✅ No constraint violations found ({} tables checked)", tablesChecked);
            } else {
                log.warn("   ⚠️  Found {} total constraint violations across {} tables", 
                         totalViolations, tablesChecked);
                log.warn("   Run 'SELECT * FROM constraint_violations()' to see details");
            }
            
        } catch (Exception e) {
            log.error("   Constraint violation check failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check UNIQUE constraints for a table
     */
    private int checkUniqueConstraints(TableMetadata table) {
        if (table.getUniqueConstraints().isEmpty()) {
            return 0; // No unique constraints to check
        }
        
        int violationCount = 0;
        
        try {
            // Scan all rows in the table
            long readTs = timestampOracle.allocateStartTimestamp();
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 100000, table.getTruncateTimestamp());
            
            // Get column types for decoding
            List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            // Decode all rows
            List<Map<String, Object>> rows = new ArrayList<>();
            for (KvStore.KvEntry entry : entries) {
                // Rate limit
                if (rateLimiter != null) {
                    try {
                        rateLimiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("   Constraint check interrupted during rate limiting");
                        return violationCount;
                    }
                }
                
                try {
                    // Decode primary key values
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                    
                    // Decode non-PK values
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                    
                    // Build full row map
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                        row.put(table.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                    }
                    List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    rows.add(row);
                } catch (Exception e) {
                    log.warn("⚠️  Skipping corrupted entry during constraint check: {}", e.getMessage());
                }
            }
            
            // Check each UNIQUE constraint
            for (TableMetadata.UniqueConstraint constraint : table.getUniqueConstraints()) {
                Map<String, List<Map<String, Object>>> valueGroups = new HashMap<>();
                
                // Group rows by unique column values
                for (Map<String, Object> row : rows) {
                    List<Object> uniqueValues = new ArrayList<>();
                    boolean hasNull = false;
                    
                    for (String colName : constraint.getColumns()) {
                        Object value = row.get(colName.toLowerCase());
                        if (value == null) {
                            hasNull = true;
                            break;
                        }
                        uniqueValues.add(value);
                    }
                    
                    // Skip rows with NULL in unique columns (NULLs don't violate UNIQUE)
                    if (hasNull) {
                        continue;
                    }
                    
                    String key = uniqueValues.toString();
                    valueGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
                
                // Find duplicates
                for (Map.Entry<String, List<Map<String, Object>>> entry : valueGroups.entrySet()) {
                    if (entry.getValue().size() > 1) {
                        // Found duplicate!
                        ConstraintViolation violation = new ConstraintViolation(
                            table.getTableName(),
                            "UNIQUE",
                            constraint.getName(),
                            "Duplicate values in unique constraint: " + entry.getKey(),
                            entry.getValue().size()
                        );
                        
                        violationsByTable.computeIfAbsent(table.getTableName(), k -> new ArrayList<>())
                            .add(violation);
                        
                        violationCount++;
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Error checking UNIQUE constraints for {}: {}", table.getTableName(), e.getMessage());
        }
        
        return violationCount;
    }
    
    /**
     * Check FOREIGN KEY constraints for a table
     */
    private int checkForeignKeyConstraints(TableMetadata table) {
        if (table.getForeignKeyConstraints().isEmpty()) {
            return 0; // No foreign key constraints to check
        }
        
        int violationCount = 0;
        
        try {
            // Scan all rows in the table
            long readTs = timestampOracle.allocateStartTimestamp();
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 100000, table.getTruncateTimestamp());
            
            // Get column types for decoding
            List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            // Check each FOREIGN KEY constraint
            for (TableMetadata.ForeignKeyConstraint fk : table.getForeignKeyConstraints()) {
                TableMetadata referencedTable = schemaManager.getTable(fk.getReferencedTable());
                if (referencedTable == null) {
                    log.warn("Referenced table not found: {}", fk.getReferencedTable());
                    continue;
                }
                
                // Scan each row and check if foreign key values exist in parent table
                for (KvStore.KvEntry entry : entries) {
                    // Rate limit
                    if (rateLimiter != null) {
                        try {
                            rateLimiter.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.error("   Constraint check interrupted during rate limiting");
                            return violationCount;
                        }
                    }
                    
                    try {
                        // Decode primary key values
                        List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                        
                        // Decode non-PK values
                        List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                        
                        // Build full row map
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                            row.put(table.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                        }
                        List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
                        for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                            row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                        }
                        
                        // Extract foreign key values
                        List<Object> fkValues = new ArrayList<>();
                        boolean hasNull = false;
                        for (String fkCol : fk.getColumns()) {
                            Object value = row.get(fkCol.toLowerCase());
                            if (value == null) {
                                hasNull = true;
                                break;
                            }
                            fkValues.add(value);
                        }
                        
                        // Skip rows with NULL in FK columns (NULLs don't violate FK)
                        if (hasNull) {
                            continue;
                        }
                        
                        // Check if referenced row exists
                        byte[] parentKey = KeyEncoder.encodeTableDataKey(
                            referencedTable.getTableId(),
                            fkValues,
                            readTs
                        );
                        
                        KvStore.KvEntry parentEntry = kvStore.get(parentKey, readTs);
                        
                        if (parentEntry == null || parentEntry.isDeleted()) {
                            // Foreign key violation!
                            ConstraintViolation violation = new ConstraintViolation(
                                table.getTableName(),
                                "FOREIGN KEY",
                                fk.getName(),
                                "Foreign key references non-existent row: " + fkValues,
                                1
                            );
                            
                            violationsByTable.computeIfAbsent(table.getTableName(), k -> new ArrayList<>())
                                .add(violation);
                            
                            violationCount++;
                        }
                        
                    } catch (Exception e) {
                        log.warn("⚠️  Skipping corrupted entry during FK check: {}", e.getMessage());
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Error checking FOREIGN KEY constraints for {}: {}", table.getTableName(), e.getMessage());
        }
        
        return violationCount;
    }
    
    /**
     * Get all detected violations (for diagnostic queries)
     */
    public Map<String, List<ConstraintViolation>> getViolations() {
        return new HashMap<>(violationsByTable);
    }
    
    /**
     * Represents a constraint violation
     */
    public static class ConstraintViolation {
        private final String tableName;
        private final String constraintType;
        private final String constraintName;
        private final String description;
        private final int affectedRows;
        
        public ConstraintViolation(String tableName, String constraintType, String constraintName,
                                  String description, int affectedRows) {
            this.tableName = tableName;
            this.constraintType = constraintType;
            this.constraintName = constraintName;
            this.description = description;
            this.affectedRows = affectedRows;
        }
        
        public String getTableName() { return tableName; }
        public String getConstraintType() { return constraintType; }
        public String getConstraintName() { return constraintName; }
        public String getDescription() { return description; }
        public int getAffectedRows() { return affectedRows; }
        
        @Override
        public String toString() {
            return String.format("[%s] %s.%s: %s (%d rows affected)",
                constraintType, tableName, constraintName, description, affectedRows);
        }
    }
}



