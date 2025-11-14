package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to ensure key encoding properly isolates data between tables.
 * 
 * This prevents critical data corruption bugs where data from one table
 * appears in another table (e.g., customer phone numbers in product categories).
 * 
 * This is a CRITICAL correctness test - data isolation is fundamental to correctness.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KeyEncodingIsolationTest extends KvTestBase {
    
    @Test
    @Order(1)
    public void testGroupByDoesNotMixTablesData() throws Exception {
        String productsTable = uniqueTableName("iso_products");
        String customersTable = uniqueTableName("iso_customers");
        
        // Create two tables with different schemas
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, price DECIMAL)",
            productsTable
        ));
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, phone TEXT, city TEXT)",
            customersTable
        ));
        Thread.sleep(200);
        
        // Insert data into both tables
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 'Electronics', 100.00), (2, 'Electronics', 200.00), (3, 'Audio', 150.00)",
            productsTable
        ));
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (101, '555-0101', 'Seattle'), (102, '555-0102', 'Portland')",
            customersTable
        ));
        Thread.sleep(200);
        
        // GROUP BY on products table should ONLY return product categories
        QueryResponse result = queryService.execute(String.format(
            "SELECT category, COUNT(*) AS product_count, AVG(price) AS avg_price FROM %s GROUP BY category",
            productsTable
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should have 2 categories");
        
        // Verify that ONLY product categories appear, not customer data
        for (var row : result.getRows()) {
            String category = (String) row.get("category");
            assertNotNull(category, "Category should not be null");
            
            // These should be product categories
            assertTrue(category.equals("Electronics") || category.equals("Audio"),
                "Category should be Electronics or Audio, got: " + category);
            
            // These should NOT be customer phone numbers
            assertFalse(category.startsWith("555-"), 
                "Category should not be a phone number, got: " + category);
            assertFalse(category.equals("Seattle") || category.equals("Portland"),
                "Category should not be a city name, got: " + category);
        }
        
        // Tables are automatically cleaned up by KvTestBase
    }
    
    @Test
    @Order(2)
    public void testSelectDoesNotMixTablesData() throws Exception {
        String table1 = uniqueTableName("iso_t1");
        String table2 = uniqueTableName("iso_t2");
        
        // Create two tables with different column names
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT, value INT)",
            table1
        ));
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, email TEXT, score INT)",
            table2
        ));
        Thread.sleep(200);
        
        // Insert data
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 'Alice', 100), (2, 'Bob', 200)",
            table1
        ));
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 'alice@example.com', 95), (2, 'bob@example.com', 85)",
            table2
        ));
        Thread.sleep(200);
        
        // SELECT from table1 should only return table1 data
        QueryResponse result1 = queryService.execute(String.format(
            "SELECT * FROM %s",
            table1
        ));
        
        assertNull(result1.getError(), "Query should succeed");
        assertEquals(2, result1.getRows().size(), "Should have 2 rows");
        
        for (var row : result1.getRows()) {
            // Should have name column from table1
            assertNotNull(row.get("name"), "Should have name column");
            String name = (String) row.get("name");
            assertTrue(name.equals("Alice") || name.equals("Bob"), "Name should be Alice or Bob");
            
            // Should NOT have email column from table2
            assertNull(row.get("email"), "Should not have email column from table2");
        }
        
        // SELECT from table2 should only return table2 data
        QueryResponse result2 = queryService.execute(String.format(
            "SELECT * FROM %s",
            table2
        ));
        
        assertNull(result2.getError(), "Query should succeed");
        assertEquals(2, result2.getRows().size(), "Should have 2 rows");
        
        for (var row : result2.getRows()) {
            // Should have email column from table2
            assertNotNull(row.get("email"), "Should have email column");
            String email = (String) row.get("email");
            assertTrue(email.contains("@"), "Email should contain @");
            
            // Should NOT have name column from table1
            assertNull(row.get("name"), "Should not have name column from table1");
        }
        
        // Tables are automatically cleaned up by KvTestBase
    }
    
    @Test
    @Order(3)
    public void testAggregationDoesNotMixTablesData() throws Exception {
        String ordersTable = uniqueTableName("iso_orders");
        String usersTable = uniqueTableName("iso_users");
        
        // Create tables
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, amount DECIMAL, status TEXT)",
            ordersTable
        ));
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, username TEXT, age INT)",
            usersTable
        ));
        Thread.sleep(200);
        
        // Insert data
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 100.00, 'completed'), (2, 200.00, 'completed'), (3, 50.00, 'pending')",
            ordersTable
        ));
        queryService.execute(String.format(
            "INSERT INTO %s VALUES (1, 'john_doe', 30), (2, 'jane_smith', 25)",
            usersTable
        ));
        Thread.sleep(200);
        
        // Aggregate on orders should only use orders data
        QueryResponse result = queryService.execute(String.format(
            "SELECT status, COUNT(*) AS order_count, SUM(amount) AS total FROM %s GROUP BY status",
            ordersTable
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should have 2 status groups");
        
        // Verify only order statuses appear
        for (var row : result.getRows()) {
            String status = (String) row.get("status");
            assertNotNull(status, "Status should not be null");
            assertTrue(status.equals("completed") || status.equals("pending"),
                "Status should be completed or pending, got: " + status);
            
            // Should NOT be usernames
            assertFalse(status.contains("_"), "Status should not be a username with underscore");
            assertFalse(status.equals("john_doe") || status.equals("jane_smith"),
                "Status should not be a username");
        }
        
        // Tables are automatically cleaned up by KvTestBase
    }
}

