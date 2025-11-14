package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.*;
import com.geico.poc.cassandrasql.kv.jobs.VacuumJob;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TRUNCATE TABLE functionality.
 * 
 * Tests cover:
 * - Basic TRUNCATE removes all data
 * - Data inserted after TRUNCATE is visible
 * - Data inserted before TRUNCATE is not visible
 * - TRUNCATE + INSERT in same transaction
 * - Vacuum cleans up truncated data
 * - Edge cases (empty table, multiple truncates, etc.)
 */
@SpringBootTest
@ActiveProfiles("test")
public class TruncateTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private VacuumJob vacuumJob;
    
    private String TEST_TABLE;
    
    @BeforeEach
    public void setup() throws Exception {
        TEST_TABLE = uniqueTableName("truncate_test");
        // Clean up any existing test table
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        
        // Create test table
        System.out.println("Creating table before test: " + TEST_TABLE);
        String createSql = "CREATE TABLE " + TEST_TABLE + " (id INT PRIMARY KEY, name TEXT, amount INT)";
        queryService.execute(createSql);
        
        System.out.println("\n=== Test Setup Complete ===\n");
    }


    @AfterEach
    public void cleanup() throws Exception {
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }

    @Test
    public void testBasicTruncate() throws Exception {
        System.out.println("TEST: Basic TRUNCATE removes all data");
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        queryService.execute("CREATE TABLE " + TEST_TABLE + " (id INT PRIMARY KEY, name TEXT, amount INT);");
        QueryResponse beforeInsert = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(0, beforeInsert.getRowCount(), "Should have 0 rows before TRUNCATE");


        // Insert some data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (3, 'Charlie', 300)");
        
        // Verify data exists
        QueryResponse beforeTruncate = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(3, beforeTruncate.getRowCount(), "Should have 3 rows before TRUNCATE");
        
        // TRUNCATE the table
        QueryResponse truncateResponse = queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        assertEquals(0, truncateResponse.getRowCount(), "TRUNCATE should return 0 rows");
        
        // Verify data is gone
        QueryResponse afterTruncate = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(0, afterTruncate.getRowCount(), "Should have 0 rows after TRUNCATE");
        
        System.out.println("✓ Basic TRUNCATE works correctly");
    }
    
    @Test
    public void testTruncateEmptyTable() throws Exception {
        System.out.println("TEST: TRUNCATE on empty table");
        
        // TRUNCATE empty table (should not fail)
        QueryResponse response = queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        assertEquals(0, response.getRowCount());
        
        // Verify still empty
        QueryResponse afterTruncate = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(0, afterTruncate.getRowCount());
        
        System.out.println("✓ TRUNCATE empty table works correctly");
    }
    
    @Test
    public void testInsertAfterTruncate() throws Exception {
        System.out.println("TEST: Insert after TRUNCATE");
        
        // Insert initial data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // Insert new data after TRUNCATE
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (10, 'NewAlice', 1000)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (20, 'NewBob', 2000)");
        
        // Verify only new data is visible
        QueryResponse response = queryService.execute("SELECT * FROM " + TEST_TABLE + " ORDER BY id");
        assertEquals(2, response.getRowCount(), "Should have 2 rows after TRUNCATE + INSERT");
        
        List<Map<String, Object>> rows = response.getRows();
        assertEquals(10, rows.get(0).get("id"));
        assertEquals("NewAlice", rows.get(0).get("name"));
        assertEquals(20, rows.get(1).get("id"));
        assertEquals("NewBob", rows.get(1).get("name"));
        
        System.out.println("✓ Insert after TRUNCATE works correctly");
    }
    
    @Test
    public void testMultipleTruncates() throws Exception {
        System.out.println("TEST: Multiple TRUNCATEs");
        
        // Insert, truncate, insert, truncate, insert
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (3, 'Charlie', 300)");
        
        // Only the last insert should be visible
        QueryResponse response = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(1, response.getRowCount(), "Should have 1 row after multiple TRUNCATEs");
        assertEquals(3, response.getRows().get(0).get("id"));
        
        System.out.println("✓ Multiple TRUNCATEs work correctly");
    }
    
    @Test
    public void testTruncateWithWhere() throws Exception {
        System.out.println("TEST: TRUNCATE filters out old data in WHERE queries");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (3, 'Charlie', 300)");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // Insert new data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'NewAlice', 1000)");
        
        // Query with WHERE should only see new data
        QueryResponse response = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 1");
        assertEquals(1, response.getRowCount());
        assertEquals("NewAlice", response.getRows().get(0).get("name"));
        assertEquals(1000, response.getRows().get(0).get("amount"));
        
        System.out.println("✓ TRUNCATE with WHERE queries works correctly");
    }
    
    @Test
    public void testTruncateWithUpdate() throws Exception {
        System.out.println("TEST: UPDATE after TRUNCATE");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // UPDATE should affect 0 rows (old data is gone)
        QueryResponse updateResponse = queryService.execute("UPDATE " + TEST_TABLE + " SET amount = 999 WHERE id = 1");
        assertEquals(0, updateResponse.getRowCount(), "UPDATE should affect 0 rows after TRUNCATE");
        
        // Insert new data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'NewAlice', 100)");
        
        // UPDATE should now work
        QueryResponse updateResponse2 = queryService.execute("UPDATE " + TEST_TABLE + " SET amount = 999 WHERE id = 1");
        assertEquals(1, updateResponse2.getRowCount(), "UPDATE should affect 1 row");
        
        // Verify update
        QueryResponse selectResponse = queryService.execute("SELECT * FROM " + TEST_TABLE + " WHERE id = 1");
        assertEquals(999, selectResponse.getRows().get(0).get("amount"));
        
        System.out.println("✓ UPDATE after TRUNCATE works correctly");
    }
    
    @Test
    public void testTruncateWithDelete() throws Exception {
        System.out.println("TEST: DELETE after TRUNCATE");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // DELETE should affect 0 rows (old data is gone)
        QueryResponse deleteResponse = queryService.execute("DELETE FROM " + TEST_TABLE + " WHERE id = 1");
        assertEquals(0, deleteResponse.getRowCount(), "DELETE should affect 0 rows after TRUNCATE");
        
        // Insert new data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'NewAlice', 100)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'NewBob', 200)");
        
        // DELETE should now work
        QueryResponse deleteResponse2 = queryService.execute("DELETE FROM " + TEST_TABLE + " WHERE id = 1");
        assertEquals(1, deleteResponse2.getRowCount(), "DELETE should affect 1 row");
        
        // Verify delete
        QueryResponse selectResponse = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertEquals(1, selectResponse.getRowCount());
        assertEquals(2, selectResponse.getRows().get(0).get("id"));
        
        System.out.println("✓ DELETE after TRUNCATE works correctly");
    }
    
    @Test
    public void testTruncateMetadata() throws Exception {
        System.out.println("TEST: TRUNCATE updates table metadata");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        
        // Get table metadata before TRUNCATE
        TableMetadata tableBefore = schemaManager.getTable(TEST_TABLE);
        assertNull(tableBefore.getTruncateTimestamp(), "truncate_ts should be null before TRUNCATE");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // Get table metadata after TRUNCATE
        TableMetadata tableAfter = schemaManager.getTable(TEST_TABLE);
        assertNotNull(tableAfter.getTruncateTimestamp(), "truncate_ts should be set after TRUNCATE");
        assertTrue(tableAfter.getTruncateTimestamp() > 0, "truncate_ts should be positive");
        
        System.out.println("✓ TRUNCATE metadata is correct");
    }
    
    @Test
    public void testVacuumCleansTruncatedData() throws Exception {
        System.out.println("TEST: Vacuum cleans up truncated data");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (3, 'Charlie', 300)");
        
        // Get table metadata
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        
        // Count rows in KV store before TRUNCATE
        byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        List<KvStore.KvEntry> entriesBefore = kvStore.scan(startKey, endKey, Long.MAX_VALUE, 10000);
        int countBefore = entriesBefore.size();
        assertTrue(countBefore >= 3, "Should have at least 3 entries before TRUNCATE");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // Data should still exist in KV store (not yet vacuumed)
        List<KvStore.KvEntry> entriesAfterTruncate = kvStore.scan(startKey, endKey, Long.MAX_VALUE, 10000);
        assertEquals(countBefore, entriesAfterTruncate.size(), 
                    "Data should still exist in KV store before vacuum");
        
        // Run vacuum
        vacuumJob.execute();
        
        // Data should be cleaned up
        List<KvStore.KvEntry> entriesAfterVacuum = kvStore.scan(startKey, endKey, Long.MAX_VALUE, 10000);
        assertEquals(0, entriesAfterVacuum.size(), 
                    "Truncated data should be cleaned up by vacuum");
        
        System.out.println("✓ Vacuum cleans up truncated data correctly");
    }
    
    @Test
    public void testVacuumPreservesDataAfterTruncate() throws Exception {
        System.out.println("TEST: Vacuum preserves data inserted after TRUNCATE");
        
        // Insert data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
        
        // TRUNCATE
        queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
        
        // Insert new data after TRUNCATE
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (3, 'Charlie', 300)");
        
        // Run vacuum
        vacuumJob.execute();
        
        // New data should still be visible
        QueryResponse response = queryService.execute("SELECT * FROM " + TEST_TABLE + " ORDER BY id");
        assertEquals(2, response.getRowCount(), "New data should be preserved after vacuum");
        assertEquals(2, response.getRows().get(0).get("id"));
        assertEquals(3, response.getRows().get(1).get("id"));
        
        System.out.println("✓ Vacuum preserves data inserted after TRUNCATE");
    }
    
    @Test
    public void testTruncateWithJoin() throws Exception {
        System.out.println("TEST: TRUNCATE with JOIN queries");
        
        // Create second table with unique name
        String ordersTable = uniqueTableName("truncate_test_orders");
        queryService.execute("CREATE TABLE " + ordersTable + " (order_id INT PRIMARY KEY, user_id INT, amount INT)");
        registerTable(ordersTable);
        
        try {
            // Insert data
            queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'Alice', 100)");
            queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (2, 'Bob', 200)");
            queryService.execute("INSERT INTO " + ordersTable + " (order_id, user_id, amount) VALUES (1, 1, 50)");
            queryService.execute("INSERT INTO " + ordersTable + " (order_id, user_id, amount) VALUES (2, 2, 75)");
            
            // JOIN should work
            QueryResponse joinBefore = queryService.execute(
                "SELECT t.name, o.amount FROM " + TEST_TABLE + " t JOIN " + ordersTable + " o ON t.id = o.user_id"
            );
            assertEquals(2, joinBefore.getRowCount());
            
            // TRUNCATE users table
            queryService.execute("TRUNCATE TABLE " + TEST_TABLE);
            
            // JOIN should return 0 rows (users table is empty)
            QueryResponse joinAfter = queryService.execute(
                "SELECT t.name, o.amount FROM " + TEST_TABLE + " t JOIN " + ordersTable + " o ON t.id = o.user_id"
            );
            assertEquals(0, joinAfter.getRowCount(), "JOIN should return 0 rows after TRUNCATE");
            
            // Insert new user
            queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, amount) VALUES (1, 'NewAlice', 1000)");
            
            // JOIN should now return 1 row
            QueryResponse joinAfterInsert = queryService.execute(
                "SELECT t.name, o.amount FROM " + TEST_TABLE + " t JOIN " + ordersTable + " o ON t.id = o.user_id"
            );
            assertEquals(1, joinAfterInsert.getRowCount());
            assertEquals("NewAlice", joinAfterInsert.getRows().get(0).get("name"));
            
        } finally {
            // Clean up
            queryService.execute("DROP TABLE IF EXISTS " + ordersTable);
        }
        
        System.out.println("✓ TRUNCATE with JOIN works correctly");
    }
}

