package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for UNION and UNION ALL support in KV mode
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UnionTest extends KvTestBase {
    
    // ========================================
    // Test 1: Basic UNION ALL
    // ========================================
    
    @Test
    @Order(1)
    public void testBasicUnionAll() throws Exception {
        System.out.println("\n=== TEST 1: Basic UNION ALL ===\n");
        
        // Create two tables
        String table1 = uniqueTableName("union_test1");
        String table2 = uniqueTableName("union_test2");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (3, 'Charlie')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (4, 'Diana')");
        
        String sql1 = "SELECT id, name FROM " + table1;
        String sql2 = "SELECT id, name FROM " + table2;
        System.out.println("Result 1: " + queryService.execute(sql1).getRows());
        System.out.println("Result 2: " + queryService.execute(sql2).getRows());

        // Execute UNION ALL
        String sql = "SELECT id, name FROM " + table1 + " UNION ALL SELECT id, name FROM " + table2;
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        System.out.println("Union Result: " + response.getRows());
        
        assertNull(response.getError(), "UNION ALL should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(4, response.getRows().size(), "Should have 4 rows (2 from each table)");
        
        // Verify all rows are present
        boolean hasAlice = false, hasBob = false, hasCharlie = false, hasDiana = false;
        for (Map<String, Object> row : response.getRows()) {
            String name = row.get("name").toString();
            if (name.equals("Alice")) hasAlice = true;
            if (name.equals("Bob")) hasBob = true;
            if (name.equals("Charlie")) hasCharlie = true;
            if (name.equals("Diana")) hasDiana = true;
        }
        
        assertTrue(hasAlice && hasBob && hasCharlie && hasDiana, "All names should be present");
        System.out.println("✅ UNION ALL returned all 4 rows");
    }
    
    // ========================================
    // Test 2: UNION with Duplicate Removal
    // ========================================
    
    @Test
    @Order(2)
    @org.junit.jupiter.api.Disabled("Requires column projection support - KV mode returns all columns including PK")
    public void testUnionWithDuplicateRemoval() throws Exception {
        System.out.println("\n=== TEST 2: UNION with Duplicate Removal ===\n");
        
        // Create tables
        String table1 = uniqueTableName("union_test1");
        String table2 = uniqueTableName("union_test2");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        
        // Insert duplicate data (same name, different IDs)
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (5, 'Eve')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (6, 'Eve')");
        
        // Execute UNION (without ALL) - should remove duplicates
        // Note: We're only selecting 'name', so both rows should have name='Eve' and be considered duplicates
        String sql = "SELECT name FROM " + table1 + " WHERE name = 'Eve' UNION SELECT name FROM " + table2 + " WHERE name = 'Eve'";
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UNION should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        
        // Debug: print rows
        System.out.println("Rows returned: " + response.getRows().size());
        for (Map<String, Object> row : response.getRows()) {
            System.out.println("  Row: " + row);
        }
        
        assertEquals(1, response.getRows().size(), "Should have 1 row (duplicate removed)");
        assertEquals("Eve", response.getRows().get(0).get("name"), "Should be Eve");
        
        System.out.println("✅ UNION removed duplicates correctly");
    }
    
    // ========================================
    // Test 3: UNION ALL with Aggregates
    // ========================================
    
    @Test
    @Order(3)
    @org.junit.jupiter.api.Disabled("Requires literal expression support - KV mode doesn't return literal columns")
    public void testUnionAllWithAggregates() throws Exception {
        System.out.println("\n=== TEST 3: UNION ALL with Aggregates ===\n");
        
        // Create tables with numeric data
        String accounts = uniqueTableName("accounts");
        String savings = uniqueTableName("savings");
        
        queryService.execute("CREATE TABLE " + accounts + " (id INT PRIMARY KEY, balance INT)");
        queryService.execute("CREATE TABLE " + savings + " (id INT PRIMARY KEY, balance INT)");
        
        queryService.execute("INSERT INTO " + accounts + " (id, balance) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + accounts + " (id, balance) VALUES (2, 200)");
        queryService.execute("INSERT INTO " + savings + " (id, balance) VALUES (1, 500)");
        queryService.execute("INSERT INTO " + savings + " (id, balance) VALUES (2, 600)");
        
        // UNION ALL with aggregates
        String sql = "SELECT 'Accounts' as account_type, COUNT(*) as row_count, SUM(balance) as total FROM " + accounts + 
                     " UNION ALL " +
                     "SELECT 'Savings' as account_type, COUNT(*) as row_count, SUM(balance) as total FROM " + savings;
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UNION ALL with aggregates should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 rows");
        
        // Verify aggregates
        for (Map<String, Object> row : response.getRows()) {
            String type = row.get("account_type").toString();
            int rowCount = ((Number) row.get("row_count")).intValue();
            int total = ((Number) row.get("total")).intValue();
            
            assertEquals(2, rowCount, "Each type should have 2 accounts");
            if (type.equals("Accounts")) {
                assertEquals(300, total, "Accounts total should be 300");
            } else if (type.equals("Savings")) {
                assertEquals(1100, total, "Savings total should be 1100");
            }
        }
        
        System.out.println("✅ UNION ALL with aggregates works correctly");
    }
    
    // ========================================
    // Test 4: Three-Way UNION ALL
    // ========================================
    
    @Test
    @Order(4)
    public void testThreeWayUnionAll() throws Exception {
        System.out.println("\n=== TEST 4: Three-Way UNION ALL ===\n");
        
        // Create three tables
        String table1 = uniqueTableName("union_test1");
        String table2 = uniqueTableName("union_test2");
        String table3 = uniqueTableName("union_test3");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table3 + " (id INT PRIMARY KEY, name TEXT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (3, 'Charlie')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (4, 'Diana')");
        queryService.execute("INSERT INTO " + table3 + " (id, name) VALUES (5, 'Frank')");
        queryService.execute("INSERT INTO " + table3 + " (id, name) VALUES (6, 'Grace')");
        
        // Three-way UNION ALL
        String sql = "SELECT name FROM " + table1 + 
                     " UNION ALL SELECT name FROM " + table2 + 
                     " UNION ALL SELECT name FROM " + table3;
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Three-way UNION ALL should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(6, response.getRows().size(), "Should have 6 rows (2 from each table)");
        
        System.out.println("✅ Three-way UNION ALL works: " + response.getRows().size() + " rows");
    }
    
    // ========================================
    // Test 5: UNION ALL with WHERE Clauses
    // ========================================
    
    @Test
    @Order(5)
    public void testUnionAllWithWhereClause() throws Exception {
        System.out.println("\n=== TEST 5: UNION ALL with WHERE Clauses ===\n");
        
        // Create tables
        String table1 = uniqueTableName("union_test1");
        String table2 = uniqueTableName("union_test2");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (3, 'Charlie')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (4, 'Diana')");
        
        // UNION ALL with filtering
        String sql = "SELECT id, name FROM " + table1 + " WHERE id < 3 " +
                     "UNION ALL " +
                     "SELECT id, name FROM " + table2 + " WHERE id > 3";
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UNION ALL with WHERE should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        
        // Verify filtering worked
        for (Map<String, Object> row : response.getRows()) {
            int id = ((Number) row.get("id")).intValue();
            assertTrue(id < 3 || id > 3, "IDs should be < 3 or > 3");
        }
        
        System.out.println("✅ UNION ALL with WHERE clauses works");
    }
    
    // ========================================
    // Test 6: UNION ALL with Literals
    // ========================================
    
    @Test
    @Order(6)
    @org.junit.jupiter.api.Disabled("Requires literal expression support - KV mode doesn't return literal columns")
    public void testUnionAllWithLiterals() throws Exception {
        System.out.println("\n=== TEST 6: UNION ALL with Literals ===\n");
        
        // Create tables
        String table1 = uniqueTableName("union_test1");
        String table2 = uniqueTableName("union_test2");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (3, 'Charlie')");
        
        // UNION ALL with literal values
        String sql = "SELECT 'Table1' as source, COUNT(*) as row_count FROM " + table1 + 
                     " UNION ALL " +
                     "SELECT 'Table2' as source, COUNT(*) as row_count FROM " + table2;
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UNION ALL with literals should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should have 2 rows");
        
        // Verify literals
        boolean hasTable1 = false, hasTable2 = false;
        for (Map<String, Object> row : response.getRows()) {
            String source = row.get("source").toString();
            if (source.equals("Table1")) hasTable1 = true;
            if (source.equals("Table2")) hasTable2 = true;
        }
        
        assertTrue(hasTable1 && hasTable2, "Both sources should be present");
        System.out.println("✅ UNION ALL with literals works");
    }
    
    // ========================================
    // Test 7: Empty Result UNION
    // ========================================
    
    @Test
    @Order(7)
    public void testUnionWithEmptyResult() throws Exception {
        System.out.println("\n=== TEST 7: UNION with Empty Result ===\n");
        
        // Create tables
        String table1 = uniqueTableName("union_test1");
        String table2 = uniqueTableName("union_test2");
        
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (3, 'Charlie')");
        queryService.execute("INSERT INTO " + table2 + " (id, name) VALUES (4, 'Diana')");
        
        // UNION where one side is empty
        String sql = "SELECT id, name FROM " + table1 + " WHERE id = 999 " +
                     "UNION ALL " +
                     "SELECT id, name FROM " + table2 + " WHERE id < 5";
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "UNION with empty result should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertTrue(response.getRows().size() > 0, "Should have rows from table2");
        
        System.out.println("✅ UNION with empty result works");
    }
    
    // ========================================
    // Test 8: Complex UNION with Multiple Columns
    // ========================================
    
    @Test
    @Order(8)
    public void testComplexUnionMultipleColumns() throws Exception {
        System.out.println("\n=== TEST 8: Complex UNION with Multiple Columns ===\n");
        
        // Create table with more columns
        String complex = uniqueTableName("complex");
        queryService.execute("CREATE TABLE " + complex + " (id INT PRIMARY KEY, name TEXT, age INT, city TEXT)");
        queryService.execute("INSERT INTO " + complex + " (id, name, age, city) VALUES (1, 'Alice', 30, 'NYC')");
        queryService.execute("INSERT INTO " + complex + " (id, name, age, city) VALUES (2, 'Bob', 25, 'LA')");
        
        // UNION with multiple columns
        String sql = "SELECT id, name, age, city FROM " + complex + " WHERE age > 25 " +
                     "UNION ALL " +
                     "SELECT id, name, age, city FROM " + complex + " WHERE city = 'LA'";
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Complex UNION should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        
        // Verify columns
        for (Map<String, Object> row : response.getRows()) {
            assertNotNull(row.get("id"), "Should have id");
            assertNotNull(row.get("name"), "Should have name");
            assertNotNull(row.get("age"), "Should have age");
            assertNotNull(row.get("city"), "Should have city");
        }
        
        System.out.println("✅ Complex UNION with multiple columns works");
    }
}

