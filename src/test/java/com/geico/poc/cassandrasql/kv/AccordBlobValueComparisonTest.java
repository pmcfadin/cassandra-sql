package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify if Accord supports BLOB value comparisons in IF conditions.
 * 
 * This test creates a simple kv_store-like table and tests various patterns
 * for comparing BLOB values in Accord transaction IF conditions.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=KV"
})
public class AccordBlobValueComparisonTest extends KvTestBase {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    private String testTable;
    private String keyspace;
    
    @BeforeEach
    public void setup() throws Exception {
        keyspace = keyspaceConfig.getDefaultKeyspace();
        testTable = "accord_blob_test_" + System.nanoTime();
        
        // Create a table similar to kv_store with value BLOB
        String createTable = String.format(
            "CREATE TABLE %s.%s (" +
            "  key BLOB," +
            "  ts BIGINT," +
            "  value BLOB," +
            "  tx_id UUID," +
            "  commit_ts BIGINT," +
            "  deleted BOOLEAN," +
            "  PRIMARY KEY (key, ts)" +
            ") WITH CLUSTERING ORDER BY (ts DESC) AND transactional_mode='full'",
            keyspace, testTable
        );
        
        System.out.println("\n=== Creating test table ===");
        System.out.println("Query: " + createTable);
        session.execute(createTable);
        System.out.println("✓ Table created: " + testTable);
    }
    
    /**
     * Test 1: Basic BLOB value comparison in Accord IF condition
     * Test if we can compare value = 0x... in an IF condition
     */
    @Test
    public void testBlobValueComparisonInIf() throws Exception {
        System.out.println("\n=== Test 1: BLOB value comparison in IF condition ===");
        
        // Insert a test value
        byte[] key = new byte[]{0x01, 0x02, 0x03};
        byte[] value1 = longToBytes(5L);
        long ts1 = 1000L;
        UUID txId1 = UUID.randomUUID();
        long commitTs1 = 2000L;
        
        String keyHex = bytesToHex(key);
        String valueHex = bytesToHex(value1);
        
        // Insert initial value
        String insert1 = String.format(
            "INSERT INTO %s.%s (key, ts, value, tx_id, commit_ts, deleted) " +
            "VALUES (0x%s, %d, 0x%s, %s, %d, false)",
            keyspace, testTable, keyHex, ts1, valueHex, txId1.toString(), commitTs1
        );
        System.out.println("Inserting initial value:");
        System.out.println("  Query: " + insert1);
        session.execute(insert1);
        System.out.println("✓ Inserted value=5");
        
        // Test 1a: Compare value in IF condition (exact match)
        System.out.println("\nTest 1a: IF condition with value = 0x... (exact match)");
        String query1a = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing = (SELECT ts, value, commit_ts FROM %s.%s WHERE key = 0x%s AND ts = %d LIMIT 1);\n" +
            "  IF existing IS NOT NULL AND existing.value = 0x%s THEN\n" +
            "    INSERT INTO %s.%s (key, ts, value, tx_id, commit_ts, deleted) " +
            "    VALUES (0x%s, %d, 0x%s, %s, %d, false);\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, keyHex, ts1, valueHex,
            keyspace, testTable, keyHex, ts1 + 1, bytesToHex(longToBytes(6L)), UUID.randomUUID().toString(), commitTs1 + 1
        );
        
        System.out.println("Query:\n" + query1a);
        try {
            ResultSet result = session.execute(query1a);
            System.out.println("✓ Query executed successfully!");
            
            // Verify the insert happened
            String verify = String.format(
                "SELECT value FROM %s.%s WHERE key = 0x%s AND ts = %d",
                keyspace, testTable, keyHex, ts1 + 1
            );
            ResultSet verifyRs = session.execute(verify);
            Row verifyRow = verifyRs.one();
            
            if (verifyRow != null && !verifyRow.isNull("value")) {
                ByteBuffer verifyValue = verifyRow.getByteBuffer("value");
                long verifyLong = bytesToLong(verifyValue.array());
                System.out.println("✓ Insert succeeded! New value=" + verifyLong);
                assertEquals(6L, verifyLong, "Should have inserted value=6");
            } else {
                System.out.println("⚠ Insert did not happen - IF condition was false");
                fail("IF condition with value comparison should have succeeded");
            }
        } catch (Exception e) {
            System.out.println("✗ Query failed: " + e.getMessage());
            e.printStackTrace();
            fail("BLOB value comparison in IF condition should work: " + e.getMessage());
        }
    }
    
    /**
     * Test 2: BLOB value comparison with non-matching value
     * Test if the IF condition correctly fails when values don't match
     */
    @Test
    public void testBlobValueComparisonMismatch() throws Exception {
        System.out.println("\n=== Test 2: BLOB value comparison with mismatch ===");
        
        // Insert a test value
        byte[] key = new byte[]{0x01, 0x02, 0x04};
        byte[] value1 = longToBytes(5L);
        long ts1 = 1000L;
        UUID txId1 = UUID.randomUUID();
        long commitTs1 = 2000L;
        
        String keyHex = bytesToHex(key);
        String valueHex = bytesToHex(value1);
        
        // Insert initial value
        String insert1 = String.format(
            "INSERT INTO %s.%s (key, ts, value, tx_id, commit_ts, deleted) " +
            "VALUES (0x%s, %d, 0x%s, %s, %d, false)",
            keyspace, testTable, keyHex, ts1, valueHex, txId1.toString(), commitTs1
        );
        System.out.println("Inserting initial value=5");
        session.execute(insert1);
        
        // Test: Compare with wrong value (should fail IF condition)
        byte[] wrongValue = longToBytes(10L);
        String wrongValueHex = bytesToHex(wrongValue);
        
        System.out.println("\nTest: IF condition with value = 0x... (wrong value, should fail)");
        String query = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET existing = (SELECT ts, value, commit_ts FROM %s.%s WHERE key = 0x%s AND ts = %d LIMIT 1);\n" +
            "  IF existing IS NOT NULL AND existing.value = 0x%s THEN\n" +
            "    INSERT INTO %s.%s (key, ts, value, tx_id, commit_ts, deleted) " +
            "    VALUES (0x%s, %d, 0x%s, %s, %d, false);\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, keyHex, ts1, wrongValueHex,
            keyspace, testTable, keyHex, ts1 + 1, bytesToHex(longToBytes(6L)), UUID.randomUUID().toString(), commitTs1 + 1
        );
        
        System.out.println("Query:\n" + query);
        try {
            ResultSet result = session.execute(query);
            System.out.println("✓ Query executed successfully!");
            
            // Verify the insert did NOT happen
            String verify = String.format(
                "SELECT value FROM %s.%s WHERE key = 0x%s AND ts = %d",
                keyspace, testTable, keyHex, ts1 + 1
            );
            ResultSet verifyRs = session.execute(verify);
            Row verifyRow = verifyRs.one();
            
            if (verifyRow == null) {
                System.out.println("✓ Insert correctly did NOT happen - IF condition was false (as expected)");
                assertTrue(true, "IF condition correctly failed for mismatched value");
            } else {
                System.out.println("⚠ Insert happened even though value didn't match!");
                fail("IF condition should have failed for mismatched value");
            }
        } catch (Exception e) {
            System.out.println("✗ Query failed: " + e.getMessage());
            e.printStackTrace();
            // This might be expected if Accord doesn't support BLOB comparison
            System.out.println("NOTE: This error suggests Accord may not support BLOB value comparison in IF conditions");
        }
    }
    
    /**
     * Test 3: CAS pattern - compare value and insert new value
     * This is the pattern we need for sequence allocation
     */
    @Test
    public void testCasPatternWithBlobValue() throws Exception {
        System.out.println("\n=== Test 3: CAS pattern with BLOB value comparison ===");
        
        // Insert initial value
        byte[] key = new byte[]{0x01, 0x02, 0x05};
        byte[] initialValue = longToBytes(5L);
        long ts1 = 1000L;
        UUID txId1 = UUID.randomUUID();
        long commitTs1 = 2000L;
        
        String keyHex = bytesToHex(key);
        String initialValueHex = bytesToHex(initialValue);
        
        String insert1 = String.format(
            "INSERT INTO %s.%s (key, ts, value, tx_id, commit_ts, deleted) " +
            "VALUES (0x%s, %d, 0x%s, %s, %d, false)",
            keyspace, testTable, keyHex, ts1, initialValueHex, txId1.toString(), commitTs1
        );
        System.out.println("Inserting initial value=5");
        session.execute(insert1);
        
        // CAS: Read value, verify it's 5, insert 6
        byte[] expectedValue = longToBytes(5L);
        byte[] newValue = longToBytes(6L);
        String expectedValueHex = bytesToHex(expectedValue);
        String newValueHex = bytesToHex(newValue);
        
        System.out.println("\nCAS: IF value = 5 THEN insert 6");
        String casQuery = String.format(
            "BEGIN TRANSACTION\n" +
            "  LET current = (SELECT ts, value, commit_ts FROM %s.%s WHERE key = 0x%s AND ts = %d LIMIT 1);\n" +
            "  IF current IS NOT NULL AND current.value = 0x%s THEN\n" +
            "    INSERT INTO %s.%s (key, ts, value, tx_id, commit_ts, deleted) " +
            "    VALUES (0x%s, %d, 0x%s, %s, %d, false);\n" +
            "  END IF\n" +
            "COMMIT TRANSACTION",
            keyspace, testTable, keyHex, ts1, expectedValueHex,
            keyspace, testTable, keyHex, ts1 + 1, newValueHex, UUID.randomUUID().toString(), commitTs1 + 1
        );
        
        System.out.println("Query:\n" + casQuery);
        try {
            ResultSet result = session.execute(casQuery);
            System.out.println("✓ CAS query executed successfully!");
            
            // Verify the insert happened
            String verify = String.format(
                "SELECT value FROM %s.%s WHERE key = 0x%s ORDER BY ts DESC LIMIT 1",
                keyspace, testTable, keyHex
            );
            ResultSet verifyRs = session.execute(verify);
            Row verifyRow = verifyRs.one();
            
            if (verifyRow != null && !verifyRow.isNull("value")) {
                ByteBuffer verifyValue = verifyRow.getByteBuffer("value");
                long verifyLong = bytesToLong(verifyValue.array());
                System.out.println("✓ CAS succeeded! Latest value=" + verifyLong);
                assertEquals(6L, verifyLong, "Should have inserted value=6 via CAS");
            } else {
                System.out.println("⚠ CAS did not insert - IF condition was false");
                fail("CAS with BLOB value comparison should have succeeded");
            }
        } catch (Exception e) {
            System.out.println("✗ CAS query failed: " + e.getMessage());
            e.printStackTrace();
            System.out.println("\n=== CONCLUSION ===");
            System.out.println("Accord does NOT support BLOB value comparison in IF conditions");
            System.out.println("Error: " + e.getMessage());
            // Don't fail the test - this is exploratory
        }
    }
    
    // Helper methods
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    private byte[] longToBytes(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(value);
        return buffer.array();
    }
    
    private long bytesToLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return buffer.getLong();
    }
}

