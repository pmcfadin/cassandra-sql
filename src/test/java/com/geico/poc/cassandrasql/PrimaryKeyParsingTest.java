package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PRIMARY KEY parsing in CREATE TABLE statements (KV mode)
 * 
 * This test verifies that different PRIMARY KEY syntaxes are correctly parsed:
 * 1. Inline: CREATE TABLE t (id INT PRIMARY KEY, ...)
 * 2. Constraint: CREATE TABLE t (id INT, name TEXT, PRIMARY KEY (id))
 * 3. Composite: CREATE TABLE t (id INT, type TEXT, PRIMARY KEY (id, type))
 * 4. With IF NOT EXISTS
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PrimaryKeyParsingTest extends KvTestBase {
    
    @Autowired
    private SchemaManager schemaManager;
    
    private String testInlinePk;
    private String testConstraintPk;
    private String testCompositePk;
    private String testIfNotExists;
    
    @BeforeEach
    public void setup() {
        // Generate unique table names per test
        testInlinePk = uniqueTableName("testInlinePk");
        testConstraintPk = uniqueTableName("testConstraintPk");
        testCompositePk = uniqueTableName("testCompositePk");
        testIfNotExists = uniqueTableName("testIfNotExists");
        
        // Clean up any test tables
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testInlinePk);
            queryService.execute("DROP TABLE IF EXISTS " + testConstraintPk);
            queryService.execute("DROP TABLE IF EXISTS " + testCompositePk);
            queryService.execute("DROP TABLE IF EXISTS " + testIfNotExists);
            Thread.sleep(50);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @AfterEach
    public void cleanup() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testInlinePk);
            queryService.execute("DROP TABLE IF EXISTS " + testConstraintPk);
            queryService.execute("DROP TABLE IF EXISTS " + testCompositePk);
            queryService.execute("DROP TABLE IF EXISTS " + testIfNotExists);
            Thread.sleep(50);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(1)
    public void testInlinePrimaryKey() throws Exception {
        System.out.println("\n=== TEST: Inline PRIMARY KEY ===");
        
        String sql = "CREATE TABLE " + testInlinePk + " (id INT PRIMARY KEY, name TEXT, email TEXT)";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE TABLE should succeed: " + 
            (response.getError() != null ? response.getError() : ""));
        
        // Verify table exists
        assertNotNull(schemaManager.getTable(testInlinePk), 
            "Table should exist in schema cache");
        
        // Verify primary key
        var table = schemaManager.getTable(testInlinePk);
        assertEquals(1, table.getPrimaryKeyColumns().size(), 
            "Should have 1 primary key column");
        assertEquals("id", table.getPrimaryKeyColumns().get(0).toLowerCase(), 
            "Primary key should be 'id'");
        
        System.out.println("✅ PASS: Inline PRIMARY KEY works");
    }
    
    @Test
    @Order(2)
    public void testConstraintPrimaryKey() throws Exception {
        System.out.println("\n=== TEST: Constraint PRIMARY KEY ===");
        
        String sql = "CREATE TABLE " + testConstraintPk + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE TABLE should succeed: " + 
            (response.getError() != null ? response.getError() : ""));
        
        // Verify table exists
        assertNotNull(schemaManager.getTable(testConstraintPk), 
            "Table should exist in schema cache");
        
        // Verify primary key
        var table = schemaManager.getTable(testConstraintPk);
        assertEquals(1, table.getPrimaryKeyColumns().size(), 
            "Should have 1 primary key column");
        assertEquals("id", table.getPrimaryKeyColumns().get(0).toLowerCase(), 
            "Primary key should be 'id'");
        
        // Verify no "primary" column was created
        boolean hasPrimaryColumn = table.getColumns().stream()
            .anyMatch(col -> col.getName().equalsIgnoreCase("primary"));
        assertFalse(hasPrimaryColumn, 
            "Should NOT have a column named 'primary' (parser bug)");
        
        System.out.println("✅ PASS: Constraint PRIMARY KEY works");
    }
    
    @Test
    @Order(3)
    public void testCompositePrimaryKey() throws Exception {
        System.out.println("\n=== TEST: Composite PRIMARY KEY ===");
        
        String sql = "CREATE TABLE " + testCompositePk + " (user_id INT, order_id INT, amount DECIMAL, PRIMARY KEY (user_id, order_id))";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE TABLE should succeed: " + 
            (response.getError() != null ? response.getError() : ""));
        
        // Verify table exists
        assertNotNull(schemaManager.getTable(testCompositePk), 
            "Table should exist in schema cache");
        
        // Verify composite primary key
        var table = schemaManager.getTable(testCompositePk);
        assertEquals(2, table.getPrimaryKeyColumns().size(), 
            "Should have 2 primary key columns");
        assertTrue(table.getPrimaryKeyColumns().stream()
            .anyMatch(col -> col.equalsIgnoreCase("user_id")), 
            "Primary key should include 'user_id'");
        assertTrue(table.getPrimaryKeyColumns().stream()
            .anyMatch(col -> col.equalsIgnoreCase("order_id")), 
            "Primary key should include 'order_id'");
        
        System.out.println("✅ PASS: Composite PRIMARY KEY works");
    }
    
    @Test
    @Order(4)
    public void testIfNotExistsWithPrimaryKey() throws Exception {
        System.out.println("\n=== TEST: IF NOT EXISTS with PRIMARY KEY ===");
        
        String sql = "CREATE TABLE IF NOT EXISTS " + testIfNotExists + " (id INT, name TEXT, PRIMARY KEY (id))";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE TABLE IF NOT EXISTS should succeed: " + 
            (response.getError() != null ? response.getError() : ""));
        
        // Verify table exists
        assertNotNull(schemaManager.getTable(testIfNotExists), 
            "Table should exist in schema cache");
        
        // Verify no "IF" table was created (parser bug)
        assertNull(schemaManager.getTable("IF"), 
            "Should NOT have created a table named 'IF' (parser bug)");
        
        // Run again - should not error
        QueryResponse response2 = queryService.execute(sql);
        assertNull(response2.getError(), "Second CREATE TABLE IF NOT EXISTS should not error");
        
        System.out.println("✅ PASS: IF NOT EXISTS with PRIMARY KEY works");
    }
    
    @Test
    @Order(5)
    public void testInsertAfterConstraintPrimaryKey() throws Exception {
        System.out.println("\n=== TEST: INSERT after constraint PRIMARY KEY ===");
        
        // Create table with constraint syntax
        String createSql = "CREATE TABLE " + testConstraintPk +" (id INT, name TEXT, PRIMARY KEY (id))";
        QueryResponse createResponse = queryService.execute(createSql);
        assertNull(createResponse.getError(), "CREATE TABLE should succeed");
        
        // Insert data
        String insertSql = "INSERT INTO " + testConstraintPk + " (id, name) VALUES (1, 'Alice')";
        QueryResponse insertResponse = queryService.execute(insertSql);
        assertNull(insertResponse.getError(), "INSERT should succeed: " + 
            (insertResponse.getError() != null ? insertResponse.getError() : ""));
        
        // Query data
        String selectSql = "SELECT * FROM " + testConstraintPk + " WHERE id = 1";
        QueryResponse selectResponse = queryService.execute(selectSql);
        assertNull(selectResponse.getError(), "SELECT should succeed");
        assertNotNull(selectResponse.getRows(), "Should have rows");
        assertEquals(1, selectResponse.getRows().size(), "Should have 1 row");
        
        System.out.println("✅ PASS: INSERT after constraint PRIMARY KEY works");
    }
    
    @AfterEach
    public void cleanupAfter() {
        // Clean up test tables
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testInlinePk);
            queryService.execute("DROP TABLE IF EXISTS " + testCompositePk);
            queryService.execute("DROP TABLE IF EXISTS " + testConstraintPk);
            queryService.execute("DROP TABLE IF EXISTS " + testIfNotExists);
        } catch (Exception e) {
            // Ignore
        }
    }
}

