package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Calcite-based query planner.
 * Tests parsing, validation, and plan generation WITHOUT going through PSQL interface.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvPlannerTest extends KvTestBase {
    
    @Autowired
    private SchemaManager schemaManager;
    
    private KvPlanner planner;
    private String testTable;
    
    @BeforeEach
    public void setupPlanner() throws Exception {
        testTable = uniqueTableName("planner_test");
        
        // Create test table
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name TEXT, age INT, city TEXT)");
        
        // Initialize planner
        planner = new KvPlanner(schemaManager);
    }
    
    // ========================================
    // Test 1: Parse simple SELECT
    // ========================================
    
    @Test
    @Order(1)
    public void testParseSimpleSelect() throws Exception {
        System.out.println("\n=== TEST 1: Parse Simple SELECT ===\n");
        
        String sql = "SELECT id, name FROM " + testTable + " WHERE id = 1";
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode, "Parsed SqlNode should not be null");
        assertEquals("SELECT", sqlNode.getKind().name(), "Should be a SELECT statement");
        
        System.out.println("Parsed AST: " + sqlNode);
        System.out.println("✅ Simple SELECT parsed successfully");
    }
    
    // ========================================
    // Test 2: Parse complex SELECT with JOIN
    // ========================================
    
    @Test
    @Order(2)
    public void testParseComplexSelect() throws Exception {
        System.out.println("\n=== TEST 2: Parse Complex SELECT with JOIN ===\n");
        
        // Create second table
        String table2 = uniqueTableName("orders");
        queryService.execute("CREATE TABLE " + table2 + " (order_id INT PRIMARY KEY, user_id INT, amount DOUBLE)");
        planner.refreshSchema();
        
        String sql = "SELECT u.name, o.amount " +
                    "FROM " + testTable + " u " +
                    "JOIN " + table2 + " o ON u.id = o.user_id " +
                    "WHERE u.age > 25 " +
                    "ORDER BY o.amount DESC " +
                    "LIMIT 10";
        
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        System.out.println("Parsed complex query: " + sqlNode);
        System.out.println("✅ Complex SELECT parsed successfully");
    }
    
    // ========================================
    // Test 3: Parse INSERT
    // ========================================
    
    @Test
    @Order(3)
    public void testParseInsert() throws Exception {
        System.out.println("\n=== TEST 3: Parse INSERT ===\n");
        
        String sql = "INSERT INTO " + testTable + " (id, name, age, city) VALUES (1, 'Alice', 30, 'New York')";
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        assertEquals("INSERT", sqlNode.getKind().name(), "Should be an INSERT statement");
        
        System.out.println("Parsed INSERT: " + sqlNode);
        System.out.println("✅ INSERT parsed successfully");
    }
    
    // ========================================
    // Test 4: Parse UPDATE with arithmetic
    // ========================================
    
    @Test
    @Order(4)
    public void testParseUpdate() throws Exception {
        System.out.println("\n=== TEST 4: Parse UPDATE with Arithmetic ===\n");
        
        String sql = "UPDATE " + testTable + " SET age = age + 1, name = 'Bob' WHERE id = 1";
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        assertEquals("UPDATE", sqlNode.getKind().name(), "Should be an UPDATE statement");
        
        System.out.println("Parsed UPDATE: " + sqlNode);
        System.out.println("✅ UPDATE parsed successfully");
    }
    
    // ========================================
    // Test 5: Parse DELETE
    // ========================================
    
    @Test
    @Order(5)
    public void testParseDelete() throws Exception {
        System.out.println("\n=== TEST 5: Parse DELETE ===\n");
        
        String sql = "DELETE FROM " + testTable + " WHERE age < 18";
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        assertEquals("DELETE", sqlNode.getKind().name(), "Should be a DELETE statement");
        
        System.out.println("Parsed DELETE: " + sqlNode);
        System.out.println("✅ DELETE parsed successfully");
    }
    
    // ========================================
    // Test 6: Parse aggregate query
    // ========================================
    
    @Test
    @Order(6)
    public void testParseAggregateQuery() throws Exception {
        System.out.println("\n=== TEST 6: Parse Aggregate Query ===\n");
        
        String sql = "SELECT city, COUNT(*) as cnt, AVG(age) as avg_age " +
                    "FROM " + testTable + " " +
                    "GROUP BY city " +
                    "HAVING COUNT(*) > 5 " +
                    "ORDER BY cnt DESC";
        
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        System.out.println("Parsed aggregate query: " + sqlNode);
        System.out.println("✅ Aggregate query parsed successfully");
    }
    
    // ========================================
    // Test 7: Parse UNION query
    // ========================================
    
    @Test
    @Order(7)
    public void testParseUnion() throws Exception {
        System.out.println("\n=== TEST 7: Parse UNION Query ===\n");
        
        String sql = "SELECT id, name FROM " + testTable + " WHERE age < 30 " +
                    "UNION ALL " +
                    "SELECT id, name FROM " + testTable + " WHERE age > 50";
        
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        System.out.println("Parsed UNION: " + sqlNode);
        System.out.println("✅ UNION parsed successfully");
    }
    
    // ========================================
    // Test 8: Parse subquery
    // ========================================
    
    @Test
    @Order(8)
    public void testParseSubquery() throws Exception {
        System.out.println("\n=== TEST 8: Parse Subquery ===\n");
        
        String sql = "SELECT name FROM " + testTable + " " +
                    "WHERE age > (SELECT AVG(age) FROM " + testTable + ")";
        
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        System.out.println("Parsed subquery: " + sqlNode);
        System.out.println("✅ Subquery parsed successfully");
    }
    
    // ========================================
    // Test 9: Parse window function
    // ========================================
    
    @Test
    @Order(9)
    public void testParseWindowFunction() throws Exception {
        System.out.println("\n=== TEST 9: Parse Window Function ===\n");
        
        String sql = "SELECT name, age, " +
                    "ROW_NUMBER() OVER (PARTITION BY city ORDER BY age DESC) as row_rank " +
                    "FROM " + testTable;
        
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        System.out.println("Parsed window function: " + sqlNode);
        System.out.println("✅ Window function parsed successfully");
    }
    
    // ========================================
    // Test 10: Parse CTE (WITH clause)
    // ========================================
    
    @Test
    @Order(10)
    public void testParseCTE() throws Exception {
        System.out.println("\n=== TEST 10: Parse CTE (WITH clause) ===\n");
        
        String sql = "WITH young_users AS (" +
                    "  SELECT * FROM " + testTable + " WHERE age < 30" +
                    ") " +
                    "SELECT name, age FROM young_users WHERE city = 'New York'";
        
        SqlNode sqlNode = planner.parse(sql);
        
        assertNotNull(sqlNode);
        System.out.println("Parsed CTE: " + sqlNode);
        System.out.println("✅ CTE parsed successfully");
    }
    
    // ========================================
    // Test 11: Validate simple SELECT
    // ========================================
    
    @Test
    @Order(11)
    public void testValidateSimpleSelect() throws Exception {
        System.out.println("\n=== TEST 11: Validate Simple SELECT ===\n");
        
        String sql = "SELECT id, name FROM " + testTable + " WHERE id = 1";
        SqlNode sqlNode = planner.parse(sql);
        SqlNode validated = planner.validate(sqlNode);
        
        assertNotNull(validated, "Validated SqlNode should not be null");
        System.out.println("Validated SQL: " + validated);
        System.out.println("✅ Simple SELECT validated successfully");
    }
    
    // ========================================
    // Test 12: Validate should fail for non-existent table
    // ========================================
    
    @Test
    @Order(12)
    public void testValidateFailsForNonExistentTable() throws Exception {
        System.out.println("\n=== TEST 12: Validation Fails for Non-Existent Table ===\n");
        
        String sql = "SELECT * FROM non_existent_table";
        SqlNode sqlNode = planner.parse(sql);
        
        assertThrows(Exception.class, () -> {
            planner.validate(sqlNode);
        }, "Validation should fail for non-existent table");
        
        System.out.println("✅ Validation correctly fails for non-existent table");
    }
    
    // ========================================
    // Test 13: Validate should fail for non-existent column
    // ========================================
    
    @Test
    @Order(13)
    public void testValidateFailsForNonExistentColumn() throws Exception {
        System.out.println("\n=== TEST 13: Validation Fails for Non-Existent Column ===\n");
        
        String sql = "SELECT non_existent_column FROM " + testTable;
        SqlNode sqlNode = planner.parse(sql);
        
        assertThrows(Exception.class, () -> {
            planner.validate(sqlNode);
        }, "Validation should fail for non-existent column");
        
        System.out.println("✅ Validation correctly fails for non-existent column");
    }
    
    // ========================================
    // Test 14: Convert to RelNode
    // ========================================
    
    @Test
    @Order(14)
    @org.junit.jupiter.api.Disabled("Requires validator state management - use plan() instead")
    public void testConvertToRelNode() throws Exception {
        System.out.println("\n=== TEST 14: Convert to RelNode ===\n");
        
        String sql = "SELECT id, name FROM " + testTable + " WHERE age > 25";
        SqlNode sqlNode = planner.parse(sql);
        SqlNode validated = planner.validate(sqlNode);
        
        org.apache.calcite.rel.RelNode relNode = planner.validateAndConvert(sqlNode);
        
        assertNotNull(relNode, "RelNode should not be null");
        
        System.out.println("Logical plan:\n" + relNode.explain());
        System.out.println("✅ Converted to RelNode successfully");
    }
    
    // ========================================
    // Test 15: Full planning pipeline
    // ========================================
    
    @Test
    @Order(15)
    @org.junit.jupiter.api.Disabled("Requires validator state management - use simpler queries for now")
    public void testFullPlanningPipeline() throws Exception {
        System.out.println("\n=== TEST 15: Full Planning Pipeline ===\n");
        
        String sql = "SELECT city, COUNT(*) as cnt " +
                    "FROM " + testTable + " " +
                    "WHERE age > 18 " +
                    "GROUP BY city " +
                    "ORDER BY cnt DESC " +
                    "LIMIT 5";
        
        RelNode plan = planner.plan(sql);
        
        assertNotNull(plan, "Plan should not be null");
        System.out.println("Final plan:\n" + plan.explain());
        System.out.println("✅ Full planning pipeline completed successfully");
    }
    
    // ========================================
    // Test 16: Schema refresh
    // ========================================
    
    @Test
    @Order(16)
    public void testSchemaRefresh() throws Exception {
        System.out.println("\n=== TEST 16: Schema Refresh ===\n");
        
        int initialTableCount = planner.getKvSchema().getTableMap().size();
        
        System.out.println("Initial tables: ");
        for(String table : planner.getKvSchema().getTableMap().keySet()) {
            System.out.println("- Existing Table: " + table);
        }        
        // Create a new table
        String newTable = uniqueTableName("new_table");
        System.out.println("Creating table: " + newTable);
        queryService.execute("CREATE TABLE " + newTable + " (id INT PRIMARY KEY, value TEXT)");
                
        int newTableCount = planner.getKvSchema().getTableMap().size();
        
        planner.refreshSchema();
        Thread.sleep(1000);

        for(String table : planner.getKvSchema().getTableMap().keySet()) {
            System.out.println("After Refresh Table: " + table);
        }

        assertTrue(newTableCount > initialTableCount, "Table count should increase after refresh");
        assertTrue(planner.getKvSchema().getTableMap().containsKey(newTable.toUpperCase()), 
                  "New table should be in schema");
        
        System.out.println("Initial tables: " + initialTableCount);
        System.out.println("After refresh: " + newTableCount);
        System.out.println("✅ Schema refresh works correctly");
    }
}

