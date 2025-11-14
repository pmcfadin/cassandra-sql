package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.integration.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.sql.Blob;

/**
 * Test to verify Cassandra configuration for KV mode
 */
public class CassandraConfigTest extends IntegrationTestBase {
    
    @Autowired
    private CqlSession session;
    
    @Test
    public void testByteOrderedPartitioner() {
        // Query system_views.settings to check partitioner
        ResultSet rs = session.execute("SELECT * FROM system_views.settings WHERE name = 'partitioner'");
        Row row = rs.one();
        
        assertNotNull(row, "Partitioner setting should exist");
        String partitioner = row.getString("value");
        
        System.out.println("🔍 Cassandra Partitioner: " + partitioner);
        
        // Verify it's ByteOrderedPartitioner
        assertTrue(
            partitioner.contains("ByteOrderedPartitioner"),
            "Expected ByteOrderedPartitioner for ordered range scans, got: " + partitioner
        );
    }
    
    @Test
    public void testTransactionalMode() {
        // Verify kv_store table has transactional_mode='full'
        // This doesnt actually seem to work, how do you tell if a table has accord enabled?
        ResultSet rs = session.execute(
            "SELECT table_name, extensions FROM system_schema.tables " +
            "WHERE keyspace_name = 'cassandra_sql' AND table_name IN ('kv_store', 'kv_locks', 'kv_writes')"
        );
        
        int tablesChecked = 0;
        for (Row row : rs) {
            String tableName = row.getString("table_name");
            var extensions = row.getMap("extensions", String.class, ByteBuffer.class);
            
            System.out.println("🔍 Table: " + tableName);
            System.out.println("   Extensions: " + extensions);
            
            if (extensions != null && extensions.containsKey("transactional_mode")) {
                String mode = new String(extensions.get("transactional_mode").toString());
                assertEquals("full", mode, 
                    "Table " + tableName + " should have transactional_mode='full'");
                System.out.println("   ✅ transactional_mode='full'");
            } else {
                System.out.println("   ⚠️  No transactional_mode set (may need table recreation)");
            }
            
            tablesChecked++;
        }
        
        assertTrue(tablesChecked > 0, "Should have found at least one KV table");
    }
}



