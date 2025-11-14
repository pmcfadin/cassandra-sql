package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for multi-column indexes
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MultiColumnIndexTest extends KvTestBase {
    
    private String testTable;
    private String indexName;
    
    @BeforeEach
    public void setupTable() throws Exception {
        testTable = uniqueTableName("multi_idx_test");
        indexName = "idx_" + testTable;
        
        // Create table with multiple columns
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, lastname TEXT, firstname TEXT, age INT, city TEXT)");
        
        // Insert test data
        queryService.execute("INSERT INTO " + testTable + " (id, lastname, firstname, age, city) VALUES (1, 'Smith', 'John', 30, 'New York')");
        queryService.execute("INSERT INTO " + testTable + " (id, lastname, firstname, age, city) VALUES (2, 'Smith', 'Jane', 28, 'Boston')");
        queryService.execute("INSERT INTO " + testTable + " (id, lastname, firstname, age, city) VALUES (3, 'Doe', 'John', 35, 'New York')");
        queryService.execute("INSERT INTO " + testTable + " (id, lastname, firstname, age, city) VALUES (4, 'Doe', 'Jane', 32, 'Chicago')");
        queryService.execute("INSERT INTO " + testTable + " (id, lastname, firstname, age, city) VALUES (5, 'Brown', 'Bob', 40, 'New York')");
    }
    
    // ========================================
    // Test 1: Create multi-column index
    // ========================================
    
    @Test
    @Order(1)
    public void testCreateMultiColumnIndex() throws Exception {
        System.out.println("\n=== TEST 1: Create Multi-Column Index ===\n");
        
        // Create index on (lastname, firstname)
        String sql = "CREATE INDEX " + indexName + " ON " + testTable + " (lastname, firstname)";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE INDEX should succeed: " + response.getError());
        
        System.out.println("✅ Multi-column index created successfully");
    }
    
    // ========================================
    // Test 2: Query using first column of index
    // ========================================
    
    @Test
    @Order(2)
    public void testQueryWithFirstColumn() throws Exception {
        System.out.println("\n=== TEST 2: Query Using First Column ===\n");
        
        // Create index
        queryService.execute("CREATE INDEX " + indexName + " ON " + testTable + " (lastname, firstname)");
        
        // Query using first column (should use index)
        String sql = "SELECT id, firstname FROM " + testTable + " WHERE lastname = 'Smith'";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 Smiths");
        
        System.out.println("✅ Query with first column works");
    }
    
    // ========================================
    // Test 3: Query using both columns of index
    // ========================================
    
    @Test
    @Order(3)
    public void testQueryWithBothColumns() throws Exception {
        System.out.println("\n=== TEST 3: Query Using Both Columns ===\n");
        
        // Create index
        queryService.execute("CREATE INDEX " + indexName + " ON " + testTable + " (lastname, firstname)");
        
        // Query using both columns (should use index)
        String sql = "SELECT id, age FROM " + testTable + " WHERE lastname = 'Smith' AND firstname = 'John'";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size(), "Should find 1 person");
        assertEquals(30, ((Number) response.getRows().get(0).get("age")).intValue());
        
        System.out.println("✅ Query with both columns works");
    }
    
    // ========================================
    // Test 4: Multi-column index with 3 columns
    // ========================================
    
    @Test
    @Order(4)
    public void testThreeColumnIndex() throws Exception {
        System.out.println("\n=== TEST 4: Three-Column Index ===\n");
        
        // Create index on (city, lastname, firstname)
        String idx3 = "idx3_" + testTable;
        String sql = "CREATE INDEX " + idx3 + " ON " + testTable + " (city, lastname, firstname)";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE INDEX should succeed: " + response.getError());
        
        // Query using all three columns
        sql = "SELECT id FROM " + testTable + " WHERE city = 'New York' AND lastname = 'Smith' AND firstname = 'John'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size(), "Should find 1 person");
        
        System.out.println("✅ Three-column index works");
    }
    
    // ========================================
    // Test 5: Range query on multi-column index
    // ========================================
    
    @Test
    @Order(5)
    public void testRangeQueryOnMultiColumnIndex() throws Exception {
        System.out.println("\n=== TEST 5: Range Query on Multi-Column Index ===\n");
        
        // Create index
        queryService.execute("CREATE INDEX " + indexName + " ON " + testTable + " (lastname, firstname)");
        
        // Query with range on first column
        String sql = "SELECT id, lastname, firstname FROM " + testTable + " WHERE lastname >= 'D' AND lastname < 'E'";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 Does");
        
        System.out.println("✅ Range query on multi-column index works");
    }
    
    // ========================================
    // Test 6: Update with multi-column index
    // ========================================
    
    @Test
    @Order(6)
    public void testUpdateWithMultiColumnIndex() throws Exception {
        System.out.println("\n=== TEST 6: UPDATE with Multi-Column Index ===\n");
        
        // Create index
        queryService.execute("CREATE INDEX " + indexName + " ON " + testTable + " (lastname, firstname)");
        
        // Update a row
        String sql = "UPDATE " + testTable + " SET age = 31 WHERE lastname = 'Smith' AND firstname = 'John'";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError());
        
        // Verify the update
        sql = "SELECT age FROM " + testTable + " WHERE lastname = 'Smith' AND firstname = 'John'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size());
        assertEquals(31, ((Number) response.getRows().get(0).get("age")).intValue());
        
        System.out.println("✅ UPDATE with multi-column index works");
    }
    
    // ========================================
    // Test 7: Delete with multi-column index
    // ========================================
    
    @Test
    @Order(7)
    public void testDeleteWithMultiColumnIndex() throws Exception {
        System.out.println("\n=== TEST 7: DELETE with Multi-Column Index ===\n");
        
        // Create index
        queryService.execute("CREATE INDEX " + indexName + " ON " + testTable + " (lastname, firstname)");
        
        // Delete a row
        String sql = "DELETE FROM " + testTable + " WHERE lastname = 'Doe' AND firstname = 'Jane'";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError());
        
        // Verify the deletion
        sql = "SELECT id FROM " + testTable + " WHERE lastname = 'Doe' AND firstname = 'Jane'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(0, response.getRows().size(), "Row should be deleted");
        
        System.out.println("✅ DELETE with multi-column index works");
    }
}

