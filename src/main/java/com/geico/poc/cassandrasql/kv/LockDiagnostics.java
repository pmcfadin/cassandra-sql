package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostic utilities for inspecting transaction locks.
 */
@Component
public class LockDiagnostics {
    
    private static final Logger log = LoggerFactory.getLogger(LockDiagnostics.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    @Autowired
    private SchemaManager schemaManager;
    
    /**
     * Dump all locks in kv_locks table with decoded keys.
     */
    public void dumpAllLocks() {
        try {
            log.info("🔍 Dumping all locks from kv_locks table...");
            
            String query = String.format("SELECT key, tx_id, start_ts, primary_key, lock_type, write_type, created_at FROM %s.kv_locks",
                keyspaceConfig.getDefaultKeyspace());
            
            ResultSet rs = session.execute(query);
            
            int count = 0;
            for (Row row : rs) {
                count++;
                
                ByteBuffer keyBuf = row.getByteBuffer("key");
                UUID txId = row.getUuid("tx_id");
                long startTs = row.getLong("start_ts");
                ByteBuffer primaryKeyBuf = row.getByteBuffer("primary_key");
                String lockType = row.getString("lock_type");
                String writeType = row.getString("write_type");
                
                log.info("  Lock #{}:", count);
                log.info("    TX ID: {}", txId);
                log.info("    Start TS: {}", startTs);
                log.info("    Lock Type: {}", lockType);
                log.info("    Write Type: {}", writeType);
                
                // Decode the key
                if (keyBuf != null) {
                    byte[] keyBytes = new byte[keyBuf.remaining()];
                    keyBuf.duplicate().get(keyBytes);
                    String decodedKey = decodeKey(keyBytes);
                    log.info("    Key (decoded): {}", decodedKey);
                    log.info("    Key (hex): {}", bytesToHex(keyBytes));
                }
                
                // Decode the primary key
                if (primaryKeyBuf != null) {
                    byte[] primaryKeyBytes = new byte[primaryKeyBuf.remaining()];
                    primaryKeyBuf.duplicate().get(primaryKeyBytes);
                    String decodedPrimaryKey = decodeKey(primaryKeyBytes);
                    log.info("    Primary Key (decoded): {}", decodedPrimaryKey);
                    log.info("    Primary Key (hex): {}", bytesToHex(primaryKeyBytes));
                }
                
                log.info("");
            }
            
            if (count == 0) {
                log.info("  No locks found");
            } else {
                log.info("Total locks: {}", count);
            }
            
        } catch (Exception e) {
            log.error("Failed to dump locks: " + e.getMessage(), e);
        }
    }
    
    /**
     * Decode a key to understand what it represents.
     */
    private String decodeKey(byte[] keyBytes) {
        try {
            // Try to extract table ID
            long tableId = KeyEncoder.extractTableId(keyBytes);
            
            // Try to extract index ID
            long indexId = KeyEncoder.extractIndexId(keyBytes);
            
            // Get table name if possible
            TableMetadata table = schemaManager.getTable(tableId);
            String tableName = (table != null) ? table.getTableName() : "unknown";
            
            // Determine key type
            String keyType;
            if (indexId == KeyEncoder.PRIMARY_INDEX_ID) {
                keyType = "PRIMARY";
            } else {
                keyType = "SECONDARY_INDEX_" + indexId;
            }
            
            // Try to decode the actual key values
            String keyValues = "";
            try {
                if (table != null) {
                    List<Class<?>> pkTypes = new ArrayList<>();
                    for (String pkCol : table.getPrimaryKeyColumns()) {
                        for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                            if (col.getName().equalsIgnoreCase(pkCol)) {
                                pkTypes.add(col.getJavaType());
                                break;
                            }
                        }
                    }
                    
                    if (!pkTypes.isEmpty()) {
                        List<Object> pkValues = KeyEncoder.decodeTableDataKey(keyBytes, pkTypes);
                        keyValues = " = " + pkValues.toString();
                    }
                }
            } catch (Exception e) {
                // Ignore decode errors
            }
            
            return String.format("Table: %s (id=%d), Type: %s%s", 
                tableName, tableId, keyType, keyValues);
                
        } catch (Exception e) {
            return "Failed to decode: " + e.getMessage();
        }
    }
    
    /**
     * Convert bytes to hex string for debugging.
     */
    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 32); i++) {
            sb.append(String.format("%02x", bytes[i]));
            if (i < bytes.length - 1) {
                sb.append(" ");
            }
        }
        if (bytes.length > 32) {
            sb.append(" ... (").append(bytes.length).append(" bytes total)");
        }
        return sb.toString();
    }
    
    /**
     * Count locks by transaction ID.
     */
    public void countLocksByTransaction() {
        try {
            log.info("🔍 Counting locks by transaction ID...");
            
            String query = String.format("SELECT tx_id, COUNT(*) as lock_count FROM %s.kv_locks GROUP BY tx_id ALLOW FILTERING",
                keyspaceConfig.getDefaultKeyspace());
            
            ResultSet rs = session.execute(query);
            
            for (Row row : rs) {
                UUID txId = row.getUuid("tx_id");
                long count = row.getLong("lock_count");
                log.info("  TX {}: {} locks", txId, count);
            }
            
        } catch (Exception e) {
            log.error("Failed to count locks: " + e.getMessage(), e);
        }
    }
}

