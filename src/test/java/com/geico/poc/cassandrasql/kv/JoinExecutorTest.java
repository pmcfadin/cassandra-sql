package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for JOIN executor in KV mode.
 * Tests various JOIN scenarios to ensure correctness.
 */
@SpringBootTest
@ActiveProfiles({"kv-test"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JoinExecutorTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    private String table1;
    private String table2;
    private String table3;
    
    @BeforeEach
    public void setupTest() {
        // Use unique names with timestamp to avoid interference
        long ts = System.nanoTime();
        table1 = uniqueTableName("join_t1_" + ts);
        table2 = uniqueTableName("join_t2_" + ts);
        table3 = uniqueTableName("join_t3_" + ts);
        
        // Clean up any stale data
        cleanup();
    }
    
    @AfterEach
    public void cleanupTest() {
        cleanup();
        
        // Give Cassandra more time to process deletes and flush
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void cleanup() {
        // Drop tables in reverse order
        try { 
            queryService.execute("DROP TABLE IF EXISTS " + table3); 
            Thread.sleep(50);
        } catch (Exception e) {}
        try { 
            queryService.execute("DROP TABLE IF EXISTS " + table2); 
            Thread.sleep(50);
        } catch (Exception e) {}
        try { 
            queryService.execute("DROP TABLE IF EXISTS " + table1); 
            Thread.sleep(50);
        } catch (Exception e) {}
    }
    
    @Test
    @Order(1)
    public void testSimpleInnerJoin() throws Exception {
        System.out.println("\n=== TEST: Simple INNER JOIN ===\n");
        
        // Create tables (avoid reserved keyword "value")
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, amount INT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, amount) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + table2 + " (id, amount) VALUES (3, 300)");
        
        // JOIN
        String joinSql = "SELECT t1.name, t2.amount FROM " + table1 + " t1 " +
            "JOIN " + table2 + " t2 ON t1.id = t2.id";
        System.out.println("Executing JOIN: " + joinSql);
        
        QueryResponse resp = queryService.execute(joinSql);
        
        System.out.println("JOIN error: " + resp.getError());
        System.out.println("JOIN rows: " + resp.getRows());
        System.out.println("JOIN columns: " + resp.getColumns());
        
        if (resp.getError() != null) {
            fail("JOIN should succeed but got error: " + resp.getError());
        }
        
        assertNull(resp.getError(), "JOIN should succeed: " + resp.getError());
        assertNotNull(resp.getRows(), "Rows should not be null");
        assertEquals(1, resp.getRows().size(), "Should return 1 matching row");
        
        Map<String, Object> row = (Map<String, Object>) resp.getRows().get(0);
        assertEquals("Alice", row.get("name"));
        assertEquals(100, row.get("amount"));
        
        System.out.println("✅ Simple INNER JOIN works");
    }
    
    @Test
    @Order(2)
    public void testLeftJoin() throws Exception {
        System.out.println("\n=== TEST: LEFT JOIN ===\n");
        
        // Create tables
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, amount INT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, amount) VALUES (1, 100)");
        
        // LEFT JOIN
        QueryResponse resp = queryService.execute(
            "SELECT t1.name, t2.amount FROM " + table1 + " t1 " +
            "LEFT JOIN " + table2 + " t2 ON t1.id = t2.id"
        );
        
        System.out.println("LEFT JOIN error: " + resp.getError());
        System.out.println("LEFT JOIN rows: " + resp.getRows());
        
        assertNull(resp.getError(), "LEFT JOIN should succeed: " + resp.getError());
        assertNotNull(resp.getRows(), "Rows should not be null");
        assertEquals(2, resp.getRows().size(), "Should return 2 rows (including unmatched)");
        
        System.out.println("✅ LEFT JOIN works");
    }
    
    @Test
    @Order(3)
    public void testThreeWayJoin() throws Exception {
        System.out.println("\n=== TEST: Three-way JOIN ===\n");
        
        // Create tables
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, dept_id INT)");
        queryService.execute("CREATE TABLE " + table3 + " (id INT PRIMARY KEY, dept_name TEXT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table2 + " (id, dept_id) VALUES (1, 10)");
        queryService.execute("INSERT INTO " + table3 + " (id, dept_name) VALUES (10, 'Engineering')");
        
        // Three-way JOIN
        QueryResponse resp = queryService.execute(
            "SELECT t1.name, t3.dept_name FROM " + table1 + " t1 " +
            "JOIN " + table2 + " t2 ON t1.id = t2.id " +
            "JOIN " + table3 + " t3 ON t2.dept_id = t3.id"
        );
        
        System.out.println("Three-way JOIN error: " + resp.getError());
        System.out.println("Three-way JOIN rows: " + resp.getRows());
        
        assertNull(resp.getError(), "Three-way JOIN should succeed: " + resp.getError());
        assertNotNull(resp.getRows(), "Rows should not be null");
        assertEquals(1, resp.getRows().size(), "Should return 1 row");
        
        Map<String, Object> row = (Map<String, Object>) resp.getRows().get(0);
        assertEquals("Alice", row.get("name"));
        assertEquals("Engineering", row.get("dept_name"));
        
        System.out.println("✅ Three-way JOIN works");
    }
    
    @Test
    @Order(4)
    public void testJoinWithWhereClause() throws Exception {
        System.out.println("\n=== TEST: JOIN with WHERE clause ===\n");
        
        // Create tables
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, amount INT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + table2 + " (id, amount) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + table2 + " (id, amount) VALUES (2, 200)");
        
        // JOIN with WHERE
        QueryResponse resp = queryService.execute(
            "SELECT t1.name, t2.amount FROM " + table1 + " t1 " +
            "JOIN " + table2 + " t2 ON t1.id = t2.id " +
            "WHERE t2.amount > 150"
        );
        
        System.out.println("JOIN with WHERE error: " + resp.getError());
        System.out.println("JOIN with WHERE rows: " + resp.getRows());
        
        assertNull(resp.getError(), "JOIN with WHERE should succeed: " + resp.getError());
        assertNotNull(resp.getRows(), "Rows should not be null");
        assertEquals(1, resp.getRows().size(), "Should return 1 row (filtered by WHERE)");
        
        Map<String, Object> row = (Map<String, Object>) resp.getRows().get(0);
        assertEquals("Bob", row.get("name"));
        assertEquals(200, row.get("amount"));
        
        System.out.println("✅ JOIN with WHERE clause works");
    }
    
    @Test
    @Order(5)
    public void testJoinCaseInsensitiveTableNames() throws Exception {
        System.out.println("\n=== TEST: JOIN with case-insensitive table names ===\n");
        
        // Create tables (lowercase)
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, amount INT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + table1 + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + table2 + " (id, amount) VALUES (1, 100)");
        
        // JOIN with UPPERCASE table names
        QueryResponse resp = queryService.execute(
            "SELECT t1.name, t2.amount FROM " + table1.toUpperCase() + " t1 " +
            "JOIN " + table2.toUpperCase() + " t2 ON t1.id = t2.id"
        );
        
        System.out.println("Case-insensitive JOIN error: " + resp.getError());
        System.out.println("Case-insensitive JOIN rows: " + resp.getRows());
        
        assertNull(resp.getError(), "Case-insensitive JOIN should succeed: " + resp.getError());
        assertNotNull(resp.getRows(), "Rows should not be null");
        assertEquals(1, resp.getRows().size(), "Should return 1 row");
        
        System.out.println("✅ Case-insensitive JOIN works");
    }
}

