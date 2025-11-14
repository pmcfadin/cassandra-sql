package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for WHERE clause operators in KV mode
 * Tests: LIKE, NOT, IS NULL, IS NOT NULL, IN, BETWEEN, OR
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WhereClauseOperatorsTest extends KvTestBase {
    
    private String testTable;
    
    @BeforeEach
    public void setupTable() throws Exception {
        testTable = uniqueTableName("operators_test");
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name TEXT, age INT, city TEXT)");
        
        // Insert test data
        queryService.execute("INSERT INTO " + testTable + " (id, name, age, city) VALUES (1, 'Alice', 25, 'New York')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, age, city) VALUES (2, 'Bob', 30, 'San Francisco')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, age, city) VALUES (3, 'Charlie', 35, 'New York')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, age, city) VALUES (4, 'Diana', NULL, 'Boston')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, age) VALUES (5, 'Eve', 28)"); // city is NULL
    }
    
    // ========================================
    // Test 1: LIKE operator
    // ========================================
    
    @Test
    @Order(1)
    public void testLikeOperator() throws Exception {
        System.out.println("\n=== TEST 1: LIKE Operator ===\n");
        
        // Test: name LIKE 'A%' (starts with A)
        String sql = "SELECT id, name FROM " + testTable + " WHERE name LIKE 'A%'";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "LIKE query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should find 1 row (Alice)");
        assertEquals("Alice", response.getRows().get(0).get("name"), "Should be Alice");
        
        // Test: name LIKE '%e' (ends with e)
        sql = "SELECT id, name FROM " + testTable + " WHERE name LIKE '%e'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(3, response.getRows().size(), "Should find 3 rows (Alice, Charlie, Eve)");
        
        // Test: city LIKE '%York' (ends with York)
        sql = "SELECT id, name FROM " + testTable + " WHERE city LIKE '%York'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 rows (New York)");
        
        System.out.println("✅ LIKE operator works correctly");
    }
    
    // ========================================
    // Test 2: NOT operator
    // ========================================
    
    @Test
    @Order(2)
    public void testNotOperator() throws Exception {
        System.out.println("\n=== TEST 2: NOT Operator ===\n");
        
        // Test: NOT (age > 30)
        String sql = "SELECT id, name FROM " + testTable + " WHERE NOT (age > 30)";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "NOT query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(3, response.getRows().size(), "Should find 3 rows (age <= 30 or NULL)");
        
        // Test: name NOT LIKE 'A%'
        sql = "SELECT id, name FROM " + testTable + " WHERE name NOT LIKE 'A%'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(4, response.getRows().size(), "Should find 4 rows (not starting with A)");
        
        System.out.println("✅ NOT operator works correctly");
    }
    
    // ========================================
    // Test 3: IS NULL / IS NOT NULL
    // ========================================
    
    @Test
    @Order(3)
    public void testNullCheckOperators() throws Exception {
        System.out.println("\n=== TEST 3: IS NULL / IS NOT NULL ===\n");
        
        // Test: age IS NULL
        String sql = "SELECT id, name FROM " + testTable + " WHERE age IS NULL";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "IS NULL query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should find 1 row (Diana)");
        assertEquals("Diana", response.getRows().get(0).get("name"), "Should be Diana");
        
        // Test: age IS NOT NULL
        sql = "SELECT id, name FROM " + testTable + " WHERE age IS NOT NULL";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(4, response.getRows().size(), "Should find 4 rows (with age)");
        
        // Test: city IS NULL
        sql = "SELECT id, name FROM " + testTable + " WHERE city IS NULL";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size(), "Should find 1 row (Eve)");
        
        System.out.println("✅ IS NULL / IS NOT NULL work correctly");
    }
    
    // ========================================
    // Test 4: IN operator
    // ========================================
    
    @Test
    @Order(4)
    public void testInOperator() throws Exception {
        System.out.println("\n=== TEST 4: IN Operator ===\n");
        
        // Test: age IN (25, 30, 35)
        String sql = "SELECT id, name FROM " + testTable + " WHERE age IN (25, 30, 35)";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "IN query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(3, response.getRows().size(), "Should find 3 rows");
        
        // Test: city IN ('New York', 'Boston')
        sql = "SELECT id, name FROM " + testTable + " WHERE city IN ('New York', 'Boston')";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(3, response.getRows().size(), "Should find 3 rows");
        
        // Test: name NOT IN ('Alice', 'Bob')
        sql = "SELECT id, name FROM " + testTable + " WHERE name NOT IN ('Alice', 'Bob')";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(3, response.getRows().size(), "Should find 3 rows");
        
        System.out.println("✅ IN operator works correctly");
    }
    
    // ========================================
    // Test 5: BETWEEN operator
    // ========================================
    
    @Test
    @Order(5)
    public void testBetweenOperator() throws Exception {
        System.out.println("\n=== TEST 5: BETWEEN Operator ===\n");
        
        // Test: age BETWEEN 25 AND 30
        String sql = "SELECT id, name FROM " + testTable + " WHERE age BETWEEN 25 AND 30";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "BETWEEN query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(3, response.getRows().size(), "Should find 3 rows (25, 28, 30)");
        
        // Test: age NOT BETWEEN 25 AND 30
        sql = "SELECT id, name FROM " + testTable + " WHERE age NOT BETWEEN 25 AND 30";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(1, response.getRows().size(), "Should find 1 row (35)");
        
        System.out.println("✅ BETWEEN operator works correctly");
    }
    
    // ========================================
    // Test 6: OR operator
    // ========================================
    
    @Test
    @Order(6)
    public void testOrOperator() throws Exception {
        System.out.println("\n=== TEST 6: OR Operator ===\n");
        
        // Test: age < 26 OR age > 32
        String sql = "SELECT id, name FROM " + testTable + " WHERE age < 26 OR age > 32";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "OR query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(2, response.getRows().size(), "Should find 2 rows (Alice: 25, Charlie: 35)");
        
        // Test: city = 'Boston' OR city = 'New York'
        sql = "SELECT id, name FROM " + testTable + " WHERE city = 'Boston' OR city = 'New York'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(3, response.getRows().size(), "Should find 3 rows");
        
        // Test: Complex: (age < 28 AND city = 'New York') OR name = 'Bob'
        sql = "SELECT id, name FROM " + testTable + " WHERE (age < 28 AND city = 'New York') OR name = 'Bob'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 rows (Alice, Bob)");
        
        System.out.println("✅ OR operator works correctly");
    }
    
    // ========================================
    // Test 7: Combined operators
    // ========================================
    
    @Test
    @Order(7)
    public void testCombinedOperators() throws Exception {
        System.out.println("\n=== TEST 7: Combined Operators ===\n");
        
        // Test: name LIKE 'A%' AND age BETWEEN 20 AND 30
        String sql = "SELECT id, name FROM " + testTable + " WHERE name LIKE 'A%' AND age BETWEEN 20 AND 30";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Combined query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should find 1 row (Alice)");
        
        // Test: city IS NOT NULL AND (age < 28 OR age > 32)
        sql = "SELECT id, name FROM " + testTable + " WHERE city IS NOT NULL AND (age < 28 OR age > 32)";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 rows (Alice, Charlie)");
        
        // Test: name IN ('Alice', 'Bob', 'Charlie') AND city NOT LIKE '%Francisco'
        sql = "SELECT id, name FROM " + testTable + " WHERE name IN ('Alice', 'Bob', 'Charlie') AND city NOT LIKE '%Francisco'";
        response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertEquals(2, response.getRows().size(), "Should find 2 rows (Alice, Charlie)");
        
        System.out.println("✅ Combined operators work correctly");
    }
}

