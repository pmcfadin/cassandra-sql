package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for string concatenation expressions using the || operator
 */
@SpringBootTest
@TestPropertySource(properties = {"storage.backend=kv"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StringConcatenationTest {
    
    @Autowired
    private QueryService queryService;
    
    private static int testCounter = 0;
    
    private String uniqueTableName(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + (testCounter++);
    }
    
    @Test
    @Order(1)
    public void testSimpleStringConcatenation() throws Exception {
        String tableName = uniqueTableName("concat_test");
        
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, first_name TEXT, last_name TEXT)", tableName));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'Alice', 'Johnson'), (2, 'Bob', 'Smith')", tableName));
        Thread.sleep(200);
        
        QueryResponse result = queryService.execute(String.format(
            "SELECT id, first_name || ' ' || last_name AS full_name FROM %s ORDER BY id",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), "Should return 2 rows");
        assertTrue(result.getColumns().contains("full_name"), "Should have full_name column");
        
        // Check concatenated values
        String name1 = (String) result.getRows().get(0).get("full_name");
        String name2 = (String) result.getRows().get(1).get("full_name");
        
        assertTrue(name1.equals("Alice Johnson") || name1.equals("Bob Smith"), 
            "First name should be Alice Johnson or Bob Smith, got: " + name1);
        assertTrue(name2.equals("Alice Johnson") || name2.equals("Bob Smith"), 
            "Second name should be Alice Johnson or Bob Smith, got: " + name2);
        assertNotEquals(name1, name2, "Names should be different");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(2)
    public void testConcatenationWithLiterals() throws Exception {
        String tableName = uniqueTableName("concat_lit");
        
        // Cleanup any existing table first
        try {
            queryService.execute("DROP TABLE IF EXISTS " + tableName);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
        
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, name TEXT, age INT)", tableName));
        Thread.sleep(200);
        
        // Verify table is empty before insert
        QueryResponse emptyCheck = queryService.execute(String.format("SELECT COUNT(*) AS cnt FROM %s", tableName));
        if (emptyCheck.getRows() != null && !emptyCheck.getRows().isEmpty()) {
            Long count = ((Number) emptyCheck.getRows().get(0).get("cnt")).longValue();
            if (count > 0) {
                System.out.println("WARNING: Table " + tableName + " already has " + count + " rows before insert");
            }
        }
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'Alice', 30)", tableName));
        Thread.sleep(200);
        
        QueryResponse result = queryService.execute(String.format(
            "SELECT name || ' is ' || 'years old' AS description FROM %s",
            tableName
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(1, result.getRows().size(), 
            "Should return 1 row, but got " + result.getRows().size() + " rows. Rows: " + result.getRows());
        
        String description = (String) result.getRows().get(0).get("description");
        assertEquals("Alice is years old", description, "Concatenation should work with literals");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(3)
    public void testConcatenationInJoin() throws Exception {
        String table1 = uniqueTableName("concat_join1");
        String table2 = uniqueTableName("concat_join2");
        
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, first_name TEXT, last_name TEXT)", table1));
        queryService.execute(String.format("CREATE TABLE %s (customer_id INT PRIMARY KEY, order_count INT)", table2));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'Alice', 'Johnson')", table1));
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 5)", table2));
        Thread.sleep(200);
        
        QueryResponse result = queryService.execute(String.format(
            "SELECT c.first_name || ' ' || c.last_name AS customer_name, o.order_count " +
            "FROM %s c INNER JOIN %s o ON c.id = o.customer_id",
            table1, table2
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(1, result.getRows().size(), "Should return 1 row");
        
        String customerName = (String) result.getRows().get(0).get("customer_name");
        assertEquals("Alice Johnson", customerName, "Concatenation should work in JOIN");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + table1);
        queryService.execute("DROP TABLE IF EXISTS " + table2);
    }
}

