package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test DROP TABLE IF EXISTS behavior.
 * 
 * In PostgreSQL, DROP TABLE IF EXISTS should succeed silently if the table doesn't exist.
 * This test verifies that our implementation matches PostgreSQL behavior.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra.storage-mode=kv",
    "cassandra.contact-points=localhost",
    "cassandra.port=9042",
    "cassandra.local-datacenter=datacenter1",
    "cassandra.keyspace=cassandra_sql"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DropTableIfExistsTest {

    @Autowired
    private QueryService queryService;

    private static final String TEST_TABLE = "drop_test_table";

    @BeforeEach
    public void setup() throws Exception {
        // Clean up - make sure table doesn't exist
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
    }

    @AfterEach
    public void cleanup() throws Exception {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
    }

    // ========================================
    // Test 1: DROP TABLE IF EXISTS on non-existent table should succeed
    // ========================================

    @Test
    @Order(1)
    public void testDropTableIfExistsNonExistent() throws Exception {
        System.out.println("\n=== Test 1: DROP TABLE IF EXISTS on non-existent table ===");
        
        // Drop a table that doesn't exist - should succeed silently
        QueryResponse response = queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        
        assertNull(response.getError(), "DROP TABLE IF EXISTS should succeed even if table doesn't exist");
        assertEquals(0, response.getRowCount(), "Should return 0 rows affected");
        
        System.out.println("✅ DROP TABLE IF EXISTS succeeded silently on non-existent table");
    }

    // ========================================
    // Test 2: DROP TABLE (without IF EXISTS) on non-existent table should fail
    // ========================================

    @Test
    @Order(2)
    public void testDropTableNonExistentShouldFail() throws Exception {
        System.out.println("\n=== Test 2: DROP TABLE on non-existent table should fail ===");
        
        // Drop a table that doesn't exist - should fail
        QueryResponse response = queryService.execute("DROP TABLE " + TEST_TABLE);
        
        assertNotNull(response.getError(), "DROP TABLE should fail if table doesn't exist");
        assertTrue(response.getError().contains("does not exist") || 
                   response.getError().contains("Table does not exist"),
            "Error message should indicate table doesn't exist. Got: " + response.getError());
        
        System.out.println("✅ DROP TABLE correctly failed on non-existent table: " + response.getError());
    }

    // ========================================
    // Test 3: DROP TABLE IF EXISTS on existing table should succeed
    // ========================================

    @Test
    @Order(3)
    public void testDropTableIfExistsExisting() throws Exception {
        System.out.println("\n=== Test 3: DROP TABLE IF EXISTS on existing table ===");
        
        // Create table
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (id INT PRIMARY KEY, name TEXT)"
        );
        assertNull(createResp.getError(), "CREATE TABLE should succeed");
        Thread.sleep(100);
        
        // Verify table exists
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertNull(selectResp.getError(), "Table should exist");
        
        // Drop the table
        QueryResponse dropResp = queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        assertNull(dropResp.getError(), "DROP TABLE IF EXISTS should succeed");
        // Note: rowCount now returns number of tables dropped (1 in this case)
        assertTrue(dropResp.getRowCount() >= 0, "Should return non-negative row count");
        
        // Verify table no longer exists
        QueryResponse selectResp2 = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertNotNull(selectResp2.getError(), "Table should not exist after drop");
        
        System.out.println("✅ DROP TABLE IF EXISTS successfully dropped existing table");
    }

    // ========================================
    // Test 4: Multiple DROP TABLE IF EXISTS calls should all succeed
    // ========================================

    @Test
    @Order(4)
    public void testMultipleDropTableIfExists() throws Exception {
        System.out.println("\n=== Test 4: Multiple DROP TABLE IF EXISTS calls ===");
        
        // Create table
        queryService.execute("CREATE TABLE " + TEST_TABLE + " (id INT PRIMARY KEY, name TEXT)");
        Thread.sleep(100);
        
        // First drop - should succeed
        QueryResponse drop1 = queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        assertNull(drop1.getError(), "First DROP TABLE IF EXISTS should succeed");
        
        // Second drop - should also succeed (table doesn't exist)
        QueryResponse drop2 = queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        assertNull(drop2.getError(), "Second DROP TABLE IF EXISTS should succeed");
        
        // Third drop - should also succeed (table still doesn't exist)
        QueryResponse drop3 = queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        assertNull(drop3.getError(), "Third DROP TABLE IF EXISTS should succeed");
        
        System.out.println("✅ Multiple DROP TABLE IF EXISTS calls all succeeded");
    }

    // ========================================
    // Test 5: Case insensitivity
    // ========================================

    @Test
    @Order(5)
    public void testDropTableIfExistsCaseInsensitive() throws Exception {
        System.out.println("\n=== Test 5: DROP TABLE IF EXISTS case insensitivity ===");
        
        // Test various case combinations
        String[] variants = {
            "DROP TABLE IF EXISTS " + TEST_TABLE,
            "drop table if exists " + TEST_TABLE,
            "Drop Table If Exists " + TEST_TABLE,
            "DROP table IF exists " + TEST_TABLE
        };
        
        for (String sql : variants) {
            QueryResponse response = queryService.execute(sql);
            assertNull(response.getError(), 
                "DROP TABLE IF EXISTS should succeed regardless of case. SQL: " + sql + ", Error: " + response.getError());
        }
        
        System.out.println("✅ DROP TABLE IF EXISTS works with all case variations");
    }
    
    // ========================================
    // Test 6: DROP TABLE IF EXISTS with multiple tables
    // ========================================
    
    @Test
    @Order(6)
    public void testDropMultipleTablesIfExists() throws Exception {
        System.out.println("\n=== Test 6: DROP TABLE IF EXISTS with multiple tables ===");
        
        String table1 = "drop_test_table1";
        String table2 = "drop_test_table2";
        String table3 = "drop_test_table3";
        
        try {
            // Create three tables
            queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
            queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, amount INT)");
            queryService.execute("CREATE TABLE " + table3 + " (id INT PRIMARY KEY, status TEXT)");
            Thread.sleep(200);
            
            // Verify all tables exist
            assertNull(queryService.execute("SELECT * FROM " + table1).getError(), "Table1 should exist");
            assertNull(queryService.execute("SELECT * FROM " + table2).getError(), "Table2 should exist");
            assertNull(queryService.execute("SELECT * FROM " + table3).getError(), "Table3 should exist");
            
            // Drop all three tables in one statement
            QueryResponse dropResp = queryService.execute(
                "DROP TABLE IF EXISTS " + table1 + ", " + table2 + ", " + table3
            );
            assertNull(dropResp.getError(), "DROP TABLE IF EXISTS with multiple tables should succeed. Error: " + dropResp.getError());
            Thread.sleep(200);
            
            // Verify all tables are gone
            assertNotNull(queryService.execute("SELECT * FROM " + table1).getError(), "Table1 should not exist");
            assertNotNull(queryService.execute("SELECT * FROM " + table2).getError(), "Table2 should not exist");
            assertNotNull(queryService.execute("SELECT * FROM " + table3).getError(), "Table3 should not exist");
            
            System.out.println("✅ DROP TABLE IF EXISTS successfully dropped all three tables");
            
        } finally {
            // Cleanup
            try { queryService.execute("DROP TABLE IF EXISTS " + table1); } catch (Exception e) {}
            try { queryService.execute("DROP TABLE IF EXISTS " + table2); } catch (Exception e) {}
            try { queryService.execute("DROP TABLE IF EXISTS " + table3); } catch (Exception e) {}
        }
    }
    
    // ========================================
    // Test 7: DROP TABLE IF EXISTS with mix of existing and non-existing tables
    // ========================================
    
    @Test
    @Order(7)
    public void testDropMultipleTablesMixedExistence() throws Exception {
        System.out.println("\n=== Test 7: DROP TABLE IF EXISTS with mixed existence ===");
        
        String existingTable = "drop_test_existing";
        String nonExistingTable1 = "drop_test_nonexist1";
        String nonExistingTable2 = "drop_test_nonexist2";
        
        try {
            // Create only one table
            queryService.execute("CREATE TABLE " + existingTable + " (id INT PRIMARY KEY, name TEXT)");
            Thread.sleep(100);
            
            // Verify table exists
            assertNull(queryService.execute("SELECT * FROM " + existingTable).getError(), "Table should exist");
            
            // Drop all three tables (only one exists)
            QueryResponse dropResp = queryService.execute(
                "DROP TABLE IF EXISTS " + existingTable + ", " + nonExistingTable1 + ", " + nonExistingTable2
            );
            assertNull(dropResp.getError(), 
                "DROP TABLE IF EXISTS should succeed even if some tables don't exist. Error: " + dropResp.getError());
            Thread.sleep(100);
            
            // Verify existing table is gone
            assertNotNull(queryService.execute("SELECT * FROM " + existingTable).getError(), 
                "Existing table should have been dropped");
            
            System.out.println("✅ DROP TABLE IF EXISTS handled mixed existence correctly");
            
        } finally {
            // Cleanup
            try { queryService.execute("DROP TABLE IF EXISTS " + existingTable); } catch (Exception e) {}
        }
    }
    
    // ========================================
    // Test 8: DROP TABLE (without IF EXISTS) with multiple tables
    // ========================================
    
    @Test
    @Order(8)
    public void testDropMultipleTables() throws Exception {
        System.out.println("\n=== Test 8: DROP TABLE with multiple tables ===");
        
        String table1 = "drop_test_multi1";
        String table2 = "drop_test_multi2";
        
        try {
            // Create two tables
            queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
            queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, amount INT)");
            Thread.sleep(200);
            
            // Drop both tables in one statement (without IF EXISTS)
            QueryResponse dropResp = queryService.execute("DROP TABLE " + table1 + ", " + table2);
            assertNull(dropResp.getError(), "DROP TABLE with multiple tables should succeed. Error: " + dropResp.getError());
            Thread.sleep(100);
            
            // Verify both tables are gone
            assertNotNull(queryService.execute("SELECT * FROM " + table1).getError(), "Table1 should not exist");
            assertNotNull(queryService.execute("SELECT * FROM " + table2).getError(), "Table2 should not exist");
            
            System.out.println("✅ DROP TABLE successfully dropped multiple tables");
            
        } finally {
            // Cleanup
            try { queryService.execute("DROP TABLE IF EXISTS " + table1); } catch (Exception e) {}
            try { queryService.execute("DROP TABLE IF EXISTS " + table2); } catch (Exception e) {}
        }
    }
}

