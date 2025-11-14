package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for different JOIN types: INNER, LEFT, RIGHT, FULL OUTER.
 * 
 * Test data setup:
 * - users: (1, Alice), (2, Bob), (3, Charlie)
 * - orders: (101, 1, 100), (102, 1, 50), (103, 2, 75)
 * 
 * Note: Charlie (id=3) has no orders, order 103 belongs to Bob (id=2)
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra.contact-points=localhost",
    "cassandra.port=9042",
    "cassandra.local-datacenter=datacenter1",
    "cassandra.keyspace=cassandra_sql"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class JoinTypesTest {

    @Autowired
    private QueryService queryService;
    
    @Autowired
    private com.geico.poc.cassandrasql.config.CassandraSqlConfig config;

    private static final String USERS_TABLE = "join_users";
    private static final String ORDERS_TABLE = "join_orders";

    @BeforeEach
    public void setup() throws Exception {
        // Verify KV mode is active
        if (config.getStorageMode() != com.geico.poc.cassandrasql.config.CassandraSqlConfig.StorageMode.KV) {
            throw new IllegalStateException("Tests must run in KV mode! Current mode: " + config.getStorageMode());
        }
        System.out.println("✅ Running JOIN tests in KV mode");
        
        // Clean up
        try {
            queryService.execute("DROP TABLE IF EXISTS " + USERS_TABLE);
            queryService.execute("DROP TABLE IF EXISTS " + ORDERS_TABLE);
            Thread.sleep(200);
        } catch (Exception e) {
            // Ignore
        }
        
        // Create tables
        queryService.execute(
            "CREATE TABLE " + USERS_TABLE + " (id INT PRIMARY KEY, name TEXT)"
        );
        queryService.execute(
            "CREATE TABLE " + ORDERS_TABLE + " (order_id INT PRIMARY KEY, user_id INT, amount INT)"
        );
        Thread.sleep(100);
        
        // Insert test data
        // Users: Alice (1), Bob (2), Charlie (3) - Charlie has no orders
        queryService.execute("INSERT INTO " + USERS_TABLE + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + USERS_TABLE + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + USERS_TABLE + " (id, name) VALUES (3, 'Charlie')");
        
        // Orders: 2 for Alice (1), 1 for Bob (2), 0 for Charlie (3)
        queryService.execute("INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (101, 1, 100)");
        queryService.execute("INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (102, 1, 50)");
        queryService.execute("INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (103, 2, 75)");
        
        Thread.sleep(100);
    }

    @AfterEach
    public void cleanup() throws Exception {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + USERS_TABLE);
            queryService.execute("DROP TABLE IF EXISTS " + ORDERS_TABLE);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
    }

    // ========================================
    // Test 1: INNER JOIN (Baseline)
    // ========================================

    @Test
    @Order(1)
    public void testInnerJoin() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "INNER JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "INNER JOIN should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // INNER JOIN should return only matching rows
        // Alice has 2 orders, Bob has 1 order, Charlie has 0 orders
        // Expected: 3 rows (Alice-101, Alice-102, Bob-103)
        assertEquals(3, response.getRows().size(), "INNER JOIN should return 3 rows");
        
        // Verify the rows
        Set<String> expectedRows = new HashSet<>(Arrays.asList(
            "Alice-101-100",
            "Alice-102-50",
            "Bob-103-75"
        ));
        
        Set<String> actualRows = new HashSet<>();
        for (Map<String, Object> row : response.getRows()) {
            String rowStr = row.get("name") + "-" + row.get("order_id") + "-" + row.get("amount");
            actualRows.add(rowStr);
        }
        
        assertEquals(expectedRows, actualRows, "INNER JOIN rows should match expected");
        
        // Verify Charlie is NOT in the results (no orders)
        for (Map<String, Object> row : response.getRows()) {
            assertNotEquals("Charlie", row.get("name"), "Charlie should not appear in INNER JOIN");
        }
    }

    // ========================================
    // Test 2: LEFT JOIN (LEFT OUTER JOIN)
    // ========================================

    @Test
    @Order(2)
    public void testLeftJoin() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "LEFT JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "LEFT JOIN should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // Debug: Print actual rows
        System.out.println("LEFT JOIN returned " + response.getRows().size() + " rows:");
        for (Map<String, Object> row : response.getRows()) {
            System.out.println("  " + row);
        }
        
        // LEFT JOIN should return ALL rows from left table (users)
        // Alice has 2 orders, Bob has 1 order, Charlie has 0 orders
        // Expected: 4 rows (Alice-101, Alice-102, Bob-103, Charlie-NULL)
        assertEquals(4, response.getRows().size(), 
            "LEFT JOIN should return 4 rows (including Charlie with NULL)");
        
        // Verify Alice's orders
        int aliceCount = 0;
        for (Map<String, Object> row : response.getRows()) {
            if ("Alice".equals(row.get("name"))) {
                aliceCount++;
                assertNotNull(row.get("order_id"), "Alice should have order_id");
                assertNotNull(row.get("amount"), "Alice should have amount");
            }
        }
        assertEquals(2, aliceCount, "Alice should appear 2 times");
        
        // Verify Bob's order
        int bobCount = 0;
        for (Map<String, Object> row : response.getRows()) {
            if ("Bob".equals(row.get("name"))) {
                bobCount++;
                assertNotNull(row.get("order_id"), "Bob should have order_id");
                assertEquals(103, row.get("order_id"), "Bob's order_id should be 103");
                assertEquals(75, row.get("amount"), "Bob's amount should be 75");
            }
        }
        assertEquals(1, bobCount, "Bob should appear 1 time");
        
        // Verify Charlie appears with NULL values
        int charlieCount = 0;
        for (Map<String, Object> row : response.getRows()) {
            if ("Charlie".equals(row.get("name"))) {
                charlieCount++;
                assertNull(row.get("order_id"), 
                    "Charlie should have NULL order_id (no orders)");
                assertNull(row.get("amount"), 
                    "Charlie should have NULL amount (no orders)");
            }
        }
        assertEquals(1, charlieCount, "Charlie should appear 1 time with NULL values");
    }

    // ========================================
    // Test 3: LEFT OUTER JOIN (explicit OUTER keyword)
    // ========================================

    @Test
    @Order(3)
    public void testLeftOuterJoin() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "LEFT OUTER JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "LEFT OUTER JOIN should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // LEFT OUTER JOIN should behave identically to LEFT JOIN
        assertEquals(4, response.getRows().size(), 
            "LEFT OUTER JOIN should return 4 rows");
        
        // Verify Charlie appears with NULL
        boolean foundCharlie = false;
        for (Map<String, Object> row : response.getRows()) {
            if ("Charlie".equals(row.get("name"))) {
                foundCharlie = true;
                assertNull(row.get("order_id"), "Charlie should have NULL order_id");
            }
        }
        assertTrue(foundCharlie, "Charlie should appear in LEFT OUTER JOIN");
    }

    // ========================================
    // Test 4: RIGHT JOIN (RIGHT OUTER JOIN)
    // ========================================

    @Test
    @Order(4)
    public void testRightJoin() throws Exception {
        // First, add an orphan order (no matching user)
        queryService.execute(
            "INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (104, 999, 200)"
        );
        Thread.sleep(100);
        
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "RIGHT JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "RIGHT JOIN should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // RIGHT JOIN should return ALL rows from right table (orders)
        // Orders: 101 (Alice), 102 (Alice), 103 (Bob), 104 (no user)
        // Expected: 4 rows (Alice-101, Alice-102, Bob-103, NULL-104)
        assertEquals(4, response.getRows().size(), 
            "RIGHT JOIN should return 4 rows (including orphan order with NULL user)");
        
        // Verify orphan order appears with NULL user
        boolean foundOrphan = false;
        for (Map<String, Object> row : response.getRows()) {
            if (Integer.valueOf(104).equals(row.get("order_id"))) {
                foundOrphan = true;
                assertNull(row.get("name"), 
                    "Orphan order should have NULL name (no matching user)");
                assertEquals(200, row.get("amount"), "Orphan order amount should be 200");
            }
        }
        assertTrue(foundOrphan, "Orphan order (104) should appear in RIGHT JOIN with NULL user");
        
        // Verify all orders are present
        Set<Integer> orderIds = new HashSet<>();
        for (Map<String, Object> row : response.getRows()) {
            orderIds.add((Integer) row.get("order_id"));
        }
        assertEquals(new HashSet<>(Arrays.asList(101, 102, 103, 104)), orderIds, 
            "All orders should be present in RIGHT JOIN");
    }

    // ========================================
    // Test 5: RIGHT OUTER JOIN (explicit OUTER keyword)
    // ========================================

    @Test
    @Order(5)
    public void testRightOuterJoin() throws Exception {
        // Add orphan order
        queryService.execute(
            "INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (104, 999, 200)"
        );
        Thread.sleep(100);
        
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "RIGHT OUTER JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "RIGHT OUTER JOIN should succeed: " + response.getError());
        
        // RIGHT OUTER JOIN should behave identically to RIGHT JOIN
        assertEquals(4, response.getRows().size(), 
            "RIGHT OUTER JOIN should return 4 rows");
        
        // Verify orphan order
        boolean foundOrphan = false;
        for (Map<String, Object> row : response.getRows()) {
            if (Integer.valueOf(104).equals(row.get("order_id"))) {
                foundOrphan = true;
                assertNull(row.get("name"), "Orphan order should have NULL name");
            }
        }
        assertTrue(foundOrphan, "Orphan order should appear in RIGHT OUTER JOIN");
    }

    // ========================================
    // Test 6: FULL OUTER JOIN
    // ========================================

    @Test
    @Order(6)
    public void testFullOuterJoin() throws Exception {
        // Add orphan order (no matching user)
        queryService.execute(
            "INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (104, 999, 200)"
        );
        Thread.sleep(100);
        
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "FULL OUTER JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "FULL OUTER JOIN should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // FULL OUTER JOIN should return ALL rows from BOTH tables
        // Users: Alice (2 orders), Bob (1 order), Charlie (0 orders)
        // Orders: 101 (Alice), 102 (Alice), 103 (Bob), 104 (no user)
        // Expected: 5 rows (Alice-101, Alice-102, Bob-103, Charlie-NULL, NULL-104)
        assertEquals(5, response.getRows().size(), 
            "FULL OUTER JOIN should return 5 rows (all users and all orders)");
        
        // Verify Charlie appears with NULL order
        boolean foundCharlie = false;
        for (Map<String, Object> row : response.getRows()) {
            if ("Charlie".equals(row.get("name"))) {
                foundCharlie = true;
                assertNull(row.get("order_id"), "Charlie should have NULL order_id");
            }
        }
        assertTrue(foundCharlie, "Charlie should appear in FULL OUTER JOIN");
        
        // Verify orphan order appears with NULL user
        boolean foundOrphan = false;
        for (Map<String, Object> row : response.getRows()) {
            if (row.get("order_id") != null && Integer.valueOf(104).equals(row.get("order_id"))) {
                foundOrphan = true;
                assertNull(row.get("name"), "Orphan order should have NULL name");
            }
        }
        assertTrue(foundOrphan, "Orphan order should appear in FULL OUTER JOIN");
        
        // Verify all users are present
        Set<String> userNames = new HashSet<>();
        for (Map<String, Object> row : response.getRows()) {
            if (row.get("name") != null) {
                userNames.add((String) row.get("name"));
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("Alice", "Bob", "Charlie")), userNames, 
            "All users should be present in FULL OUTER JOIN");
        
        // Verify all orders are present
        Set<Integer> orderIds = new HashSet<>();
        for (Map<String, Object> row : response.getRows()) {
            if (row.get("order_id") != null) {
                orderIds.add((Integer) row.get("order_id"));
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(101, 102, 103, 104)), orderIds, 
            "All orders should be present in FULL OUTER JOIN");
    }

    // ========================================
    // Test 7: FULL JOIN (without OUTER keyword)
    // ========================================

    @Test
    @Order(7)
    public void testFullJoin() throws Exception {
        // Add orphan order
        queryService.execute(
            "INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (104, 999, 200)"
        );
        Thread.sleep(100);
        
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "FULL JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id"
        );
        
        assertNull(response.getError(), "FULL JOIN should succeed: " + response.getError());
        
        // FULL JOIN should behave identically to FULL OUTER JOIN
        assertEquals(5, response.getRows().size(), 
            "FULL JOIN should return 5 rows");
    }

    // ========================================
    // Test 8: LEFT JOIN with WHERE clause
    // ========================================

    @Test
    @Order(8)
    public void testLeftJoinWithWhere() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT u.name, o.order_id, o.amount " +
            "FROM " + USERS_TABLE + " u " +
            "LEFT JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id " +
            "WHERE o.amount > 60"
        );
        
        assertNull(response.getError(), "LEFT JOIN with WHERE should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // WHERE clause filters AFTER the join
        // Alice-101 (100), Alice-102 (50), Bob-103 (75), Charlie-NULL
        // Filter: amount > 60
        // Expected: Alice-101 (100), Bob-103 (75)
        // Note: Charlie-NULL is filtered out because NULL > 60 is false
        assertEquals(2, response.getRows().size(), 
            "LEFT JOIN with WHERE should return 2 rows (amount > 60)");
        
        // Verify amounts are > 60
        for (Map<String, Object> row : response.getRows()) {
            Integer amount = (Integer) row.get("amount");
            assertNotNull(amount, "Amount should not be NULL in filtered results");
            assertTrue(amount > 60, "Amount should be > 60");
        }
    }

    // ========================================
    // Test 9: Multiple LEFT JOINs
    // ========================================

    @Test
    @Order(9)
    public void testMultipleLeftJoins() throws Exception {
        // Create a third table: products
        queryService.execute(
            "CREATE TABLE join_products (product_id INT PRIMARY KEY, product_name TEXT)"
        );
        queryService.execute(
            "INSERT INTO join_products (product_id, product_name) VALUES (1, 'Laptop')"
        );
        queryService.execute(
            "INSERT INTO join_products (product_id, product_name) VALUES (2, 'Mouse')"
        );
        
        // Add product_id to orders
        queryService.execute(
            "INSERT INTO " + ORDERS_TABLE + " (order_id, user_id, amount) VALUES (105, 3, 150)"
        );
        Thread.sleep(100);
        
        try {
            QueryResponse response = queryService.execute(
                "SELECT u.name, o.order_id, p.product_name " +
                "FROM " + USERS_TABLE + " u " +
                "LEFT JOIN " + ORDERS_TABLE + " o ON u.id = o.user_id " +
                "LEFT JOIN join_products p ON o.order_id = p.product_id"
            );
            
            assertNull(response.getError(), "Multiple LEFT JOINs should succeed: " + response.getError());
            assertNotNull(response.getRows(), "Should have rows");
            
            // All users should appear
            Set<String> userNames = new HashSet<>();
            for (Map<String, Object> row : response.getRows()) {
                if (row.get("name") != null) {
                    userNames.add((String) row.get("name"));
                }
            }
            assertTrue(userNames.contains("Alice"), "Alice should appear");
            assertTrue(userNames.contains("Bob"), "Bob should appear");
            assertTrue(userNames.contains("Charlie"), "Charlie should appear");
            
        } finally {
            queryService.execute("DROP TABLE IF EXISTS join_products");
        }
    }

    // ========================================
    // Test 10: Cross JOIN (Cartesian Product)
    // ========================================

    @Test
    @Order(10)
    public void testCrossJoin() throws Exception {
        // Create small tables for cross join
        queryService.execute("DROP TABLE IF EXISTS small_a");
        queryService.execute("DROP TABLE IF EXISTS small_b");
        
        queryService.execute("CREATE TABLE small_a (id INT PRIMARY KEY, val TEXT)");
        queryService.execute("CREATE TABLE small_b (id INT PRIMARY KEY, val TEXT)");
        
        queryService.execute("INSERT INTO small_a (id, val) VALUES (1, 'A1')");
        queryService.execute("INSERT INTO small_a (id, val) VALUES (2, 'A2')");
        
        queryService.execute("INSERT INTO small_b (id, val) VALUES (1, 'B1')");
        queryService.execute("INSERT INTO small_b (id, val) VALUES (2, 'B2')");
        queryService.execute("INSERT INTO small_b (id, val) VALUES (3, 'B3')");
        
        Thread.sleep(100);
        
        try {
            QueryResponse response = queryService.execute(
                "SELECT a.val AS a_val, b.val AS b_val " +
                "FROM small_a a " +
                "CROSS JOIN small_b b"
            );
            
            assertNull(response.getError(), "CROSS JOIN should succeed: " + response.getError());
            assertNotNull(response.getRows(), "Should have rows");
            
            // CROSS JOIN should return cartesian product: 2 × 3 = 6 rows
            assertEquals(6, response.getRows().size(), 
                "CROSS JOIN should return 6 rows (2 × 3)");
            
            // Verify all combinations exist
            Set<String> combinations = new HashSet<>();
            for (Map<String, Object> row : response.getRows()) {
                String combo = row.get("a_val") + "-" + row.get("b_val");
                combinations.add(combo);
            }
            
            Set<String> expected = new HashSet<>(Arrays.asList(
                "A1-B1", "A1-B2", "A1-B3",
                "A2-B1", "A2-B2", "A2-B3"
            ));
            
            assertEquals(expected, combinations, "All combinations should be present");
            
        } finally {
            queryService.execute("DROP TABLE IF EXISTS small_a");
            queryService.execute("DROP TABLE IF EXISTS small_b");
        }
    }
}

