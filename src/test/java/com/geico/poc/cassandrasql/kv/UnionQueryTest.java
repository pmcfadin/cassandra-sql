package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UNION and UNION ALL queries
 */
@SpringBootTest
@TestPropertySource(properties = {"storage.backend=kv"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UnionQueryTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    private static int testCounter = 0;

    
    @Test
    @Order(1)
    public void testUnionAllWithMatchingColumns() throws Exception {
        String table1 = uniqueTableName("union_t1");
        String table2 = uniqueTableName("union_t2");
        
        // Create two tables
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)", table1));
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)", table2));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'Alice')", table1));
        queryService.execute(String.format("INSERT INTO %s VALUES (2, 'Bob')", table1));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 'Caroline')", table2));
        Thread.sleep(200);
        
        // UNION ALL with matching columns (no ORDER BY for now)
        QueryResponse result = queryService.execute(String.format(
            "SELECT id, name FROM %s UNION ALL SELECT id, name FROM %s",
            table1, table2
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(3, result.getRows().size(), "Should return 3 rows");
        
        // Check that all three names are present (order not guaranteed without ORDER BY)
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var row : result.getRows()) {
            names.add((String) row.get("name"));
        }
        assertTrue(names.contains("Alice"), "Should contain Alice");
        assertTrue(names.contains("Bob"), "Should contain Bob");
        assertTrue(names.contains("Caroline"), "Should contain Caroline");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + table1);
        queryService.execute("DROP TABLE IF EXISTS " + table2);
    }
    
    @Test
    @Order(2)
    public void testUnionAllWithAggregates() throws Exception {
        String table1 = uniqueTableName("union_agg1");
        String table2 = uniqueTableName("union_agg2");
        
        // Cleanup any existing tables first
        try {
            queryService.execute("DROP TABLE IF EXISTS " + table1);
            queryService.execute("DROP TABLE IF EXISTS " + table2);
            Thread.sleep(100);
        } catch (Exception e) {
            // Ignore
        }
        
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, amount INT)", table1));
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, amount INT)", table2));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 100), (2, 200)", table1));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 300)", table2));
        Thread.sleep(200);
        
        // UNION ALL with aggregates and literals
        QueryResponse result = queryService.execute(String.format(
            "SELECT 'Table1' AS table_name, COUNT(*) AS row_count FROM %s " +
            "UNION ALL " +
            "SELECT 'Table2', COUNT(*) AS row_count FROM %s",
            table1, table2
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(2, result.getRows().size(), 
            "Should return 2 rows, but got " + result.getRows().size() + " rows. Rows: " + result.getRows());
        
        // Verify both rows have data
        assertTrue(result.getColumns().contains("table_name"), "Should have table_name column");
        assertTrue(result.getColumns().contains("row_count"), "Should have row_count column");
        
        // Find the rows by table_name since order might vary
        String table1Name = null;
        String table2Name = null;
        Number table1Count = null;
        Number table2Count = null;
        
        for (var row : result.getRows()) {
            String name = (String) row.get("table_name");
            Number count = ((Number) row.get("row_count"));
            if ("Table1".equals(name)) {
                table1Name = name;
                table1Count = count;
            } else if ("Table2".equals(name)) {
                table2Name = name;
                table2Count = count;
            }
        }
        
        assertNotNull(table1Name, "Should have Table1 row");
        assertNotNull(table2Name, "Should have Table2 row");
        assertEquals(2, table1Count.intValue(), 
            "Table1 should have 2 rows, but got " + table1Count.intValue());
        assertEquals(1, table2Count.intValue(), 
            "Table2 should have 1 row, but got " + table2Count.intValue() + ". All rows: " + result.getRows());
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + table1);
        queryService.execute("DROP TABLE IF EXISTS " + table2);
    }
    
    @Test
    @Order(3)
    public void testUnionWithDuplicateRemoval() throws Exception {
        String table1 = uniqueTableName("union_dup1");
        String table2 = uniqueTableName("union_dup2");
        
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, item TEXT)", table1));
        queryService.execute(String.format("CREATE TABLE %s (id INT PRIMARY KEY, item TEXT)", table2));
        Thread.sleep(200);
        
        queryService.execute(String.format("INSERT INTO %s VALUES (1, 'A'), (2, 'B')", table1));
        queryService.execute(String.format("INSERT INTO %s VALUES (3, 'A'), (4, 'C')", table2));
        Thread.sleep(200);
        
        // UNION (without ALL) should remove duplicates (no ORDER BY for now)
        QueryResponse result = queryService.execute(String.format(
            "SELECT item FROM %s UNION SELECT item FROM %s",
            table1, table2
        ));
        
        assertNull(result.getError(), "Query should succeed: " + result.getError());
        assertEquals(3, result.getRows().size(), "Should return 3 unique values (A, B, C)");
        
        // Check that we have all three values (order not guaranteed)
        java.util.Set<String> items = new java.util.HashSet<>();
        for (var row : result.getRows()) {
            items.add((String) row.get("item"));
        }
        assertTrue(items.contains("A"), "Should contain A");
        assertTrue(items.contains("B"), "Should contain B");
        assertTrue(items.contains("C"), "Should contain C");
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + table1);
        queryService.execute("DROP TABLE IF EXISTS " + table2);
    }
}

