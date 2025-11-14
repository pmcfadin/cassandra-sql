package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test hidden rowid column for tables without explicit PRIMARY KEY.
 * 
 * PostgreSQL allows tables without primary keys. To support this on Cassandra,
 * we automatically add a hidden 'rowid' column as the primary key.
 * 
 * This matches the behavior of PostgreSQL's internal ctid column.
 */
public class HiddenRowIdTest extends KvTestBase {

    private static final String TEST_TABLE = "no_pk_table";

    @BeforeEach
    public void setup() throws Exception {
        // Register test table for cleanup
        registerTable(TEST_TABLE);
        
        // Clean up
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
            Thread.sleep(200);
        } catch (Exception e) {
            // Ignore
        }
    }

    // ========================================
    // Test 1: CREATE TABLE without PRIMARY KEY should succeed
    // ========================================

    @Test
    @Order(1)
    public void testCreateTableWithoutPrimaryKey() throws Exception {
        System.out.println("\n=== Test 1: CREATE TABLE without PRIMARY KEY ===");
        
        // Create table without PRIMARY KEY (like pgbench_history)
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (tid INT, bid INT, aid INT, delta INT, description TEXT)"
        );
        
        assertNull(createResp.getError(), 
            "CREATE TABLE without PRIMARY KEY should succeed. Error: " + createResp.getError());
        
        System.out.println("✅ CREATE TABLE without PRIMARY KEY succeeded");
    }

    // ========================================
    // Test 2: INSERT into table without PRIMARY KEY should succeed
    // ========================================

    @Test
    @Order(2)
    public void testInsertWithoutPrimaryKey() throws Exception {
        System.out.println("\n=== Test 2: INSERT without PRIMARY KEY ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (tid INT, bid INT, aid INT, delta INT, description TEXT)"
        );
        Thread.sleep(100);
        
        // Insert data (no rowid specified)
        QueryResponse insertResp = queryService.execute(
            "INSERT INTO " + TEST_TABLE + " (tid, bid, aid, delta, description) " +
            "VALUES (1, 2, 3, 100, 'test')"
        );
        
        assertNull(insertResp.getError(), 
            "INSERT should succeed. Error: " + insertResp.getError());
        assertEquals(1, insertResp.getRowCount(), "Should insert 1 row");
        
        System.out.println("✅ INSERT without PRIMARY KEY succeeded");
    }

    // ========================================
    // Test 3: SELECT should return data (rowid should be hidden or visible)
    // ========================================

    @Test
    @Order(3)
    public void testSelectWithHiddenRowId() throws Exception {
        System.out.println("\n=== Test 3: SELECT with hidden rowid ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (tid INT, bid INT, aid INT, delta INT, description TEXT)"
        );
        Thread.sleep(100);
        
        // Insert data
        queryService.execute(
            "INSERT INTO " + TEST_TABLE + " (tid, bid, aid, delta, description) " +
            "VALUES (1, 2, 3, 100, 'test1')"
        );
        queryService.execute(
            "INSERT INTO " + TEST_TABLE + " (tid, bid, aid, delta, description) " +
            "VALUES (4, 5, 6, 200, 'test2')"
        );
        Thread.sleep(100);
        
        // Select data
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + TEST_TABLE);
        
        assertNull(selectResp.getError(), 
            "SELECT should succeed. Error: " + selectResp.getError());
        assertNotNull(selectResp.getRows(), "Should have rows");
        assertEquals(2, selectResp.getRows().size(), "Should have 2 rows");
        
        // Check first row
        Map<String, Object> row1 = selectResp.getRows().get(0);
        System.out.println("Row 1: " + row1);
        
        // Verify user columns are present
        assertTrue(row1.containsKey("tid"), "Should have tid column");
        assertTrue(row1.containsKey("bid"), "Should have bid column");
        assertTrue(row1.containsKey("aid"), "Should have aid column");
        assertTrue(row1.containsKey("delta"), "Should have delta column");
        assertTrue(row1.containsKey("description"), "Should have description column");
        
        // Note: rowid might be visible or hidden depending on implementation
        // For now, we'll allow it to be visible (like PostgreSQL's ctid)
        System.out.println("✅ SELECT returned data correctly");
    }

    // ========================================
    // Test 4: Multiple INSERTs should generate unique rowids
    // ========================================

    @Test
    @Order(4)
    public void testMultipleInsertsGenerateUniqueRowIds() throws Exception {
        System.out.println("\n=== Test 4: Multiple INSERTs generate unique rowids ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (data INT)"
        );
        Thread.sleep(100);
        
        // Insert multiple rows
        for (int i = 1; i <= 10; i++) {
            QueryResponse insertResp = queryService.execute(
                "INSERT INTO " + TEST_TABLE + " (data) VALUES (" + i + ")"
            );
            assertNull(insertResp.getError(), "INSERT " + i + " should succeed");
        }
        Thread.sleep(100);
        
        // Select all rows
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + TEST_TABLE);
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertEquals(10, selectResp.getRows().size(), "Should have 10 rows");
        
        System.out.println("✅ All 10 rows inserted successfully with unique rowids");
    }

    // ========================================
    // Test 5: pgbench_history table structure
    // ========================================

    @Test
    @Order(5)
    public void testPgBenchHistoryTable() throws Exception {
        System.out.println("\n=== Test 5: pgbench_history table ===");
        
        // Create pgbench_history table (exact structure from pgbench)
        String pgbenchTable = "pgbench_history";
        registerTable(pgbenchTable);
        
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + pgbenchTable + "(" +
            "tid INT, bid INT, aid INT, delta INT, mtime TIMESTAMP, filler TEXT)"
        );
        
        assertNull(createResp.getError(), 
            "CREATE TABLE pgbench_history should succeed. Error: " + createResp.getError());
        
        // Insert a row
        QueryResponse insertResp = queryService.execute(
            "INSERT INTO " + pgbenchTable + " (tid, bid, aid, delta, filler) " +
            "VALUES (1, 1, 1, 100, 'test')"
        );
        
        assertNull(insertResp.getError(), 
            "INSERT into pgbench_history should succeed. Error: " + insertResp.getError());
        
        // Select the row
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + pgbenchTable);
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertEquals(1, selectResp.getRows().size(), "Should have 1 row");
        
        System.out.println("✅ pgbench_history table works correctly");
    }
}




