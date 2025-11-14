package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for NUMERIC and DECIMAL data types
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NumericDecimalTypeTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Test
    @Order(1)
    public void testNumericType() throws Exception {
        String tableName = uniqueTableName("numeric_test");
        
        // Create table with NUMERIC column
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, price NUMERIC(10,2))",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());
        Thread.sleep(200);
        
        // Insert values
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, price) VALUES (1, 123.45)",
            tableName
        ));
        assertNull(insert1.getError());
        
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, price) VALUES (2, 999.99)",
            tableName
        ));
        assertNull(insert2.getError());
        
        // Query and verify
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s ORDER BY id",
            tableName
        ));
        assertNull(selectResp.getError());
        assertEquals(2, selectResp.getRows().size());
        
        // Verify values are BigDecimal
        Object price1 = selectResp.getRows().get(0).get("price");
        assertNotNull(price1);
        assertTrue(price1 instanceof BigDecimal || price1 instanceof Number);
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(2)
    public void testDecimalType() throws Exception {
        String tableName = uniqueTableName("decimal_test");
        
        // Create table with DECIMAL column
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, amount DECIMAL(15,4))",
            tableName
        ));
        assertNull(createResp.getError());
        Thread.sleep(200);
        
        // Insert large decimal value
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, amount) VALUES (1, 12345678.9012)",
            tableName
        ));
        assertNull(insertResp.getError());
        
        // Query and verify
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError());
        assertEquals(1, selectResp.getRows().size());
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(3)
    public void testNumericPrecision() throws Exception {
        String tableName = uniqueTableName("precision_test");
        
        // Create table
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, val1 NUMERIC(5,2), val2 NUMERIC(10,5))",
            tableName
        ));
        assertNull(createResp.getError());
        Thread.sleep(200);
        
        // Insert values with different precision
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, val1, val2) VALUES (1, 123.45, 12345.67890)",
            tableName
        ));
        assertNull(insertResp.getError());
        
        // Query
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError());
        assertEquals(1, selectResp.getRows().size());
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(4)
    public void testNumericArithmetic() throws Exception {
        String tableName = uniqueTableName("arithmetic_test");
        
        // Create table
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, price NUMERIC(10,2), quantity INT)",
            tableName
        ));
        assertNull(createResp.getError());
        Thread.sleep(200);
        
        // Insert test data
        queryService.execute(String.format(
            "INSERT INTO %s (id, price, quantity) VALUES (1, 10.50, 3)",
            tableName
        ));
        queryService.execute(String.format(
            "INSERT INTO %s (id, price, quantity) VALUES (2, 25.75, 2)",
            tableName
        ));
        
        // Test arithmetic operations
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT id, price, quantity FROM %s ORDER BY id",
            tableName
        ));
        assertNull(selectResp.getError());
        assertEquals(2, selectResp.getRows().size());
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(5)
    public void testNumericWithNull() throws Exception {
        String tableName = uniqueTableName("null_test");
        
        // Create table with nullable NUMERIC column
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, optional_price NUMERIC(10,2))",
            tableName
        ));
        assertNull(createResp.getError());
        Thread.sleep(200);
        
        // Insert NULL value
        QueryResponse insertResp = queryService.execute(String.format(
            "INSERT INTO %s (id, optional_price) VALUES (1, NULL)",
            tableName
        ));
        assertNull(insertResp.getError());
        
        // Query and verify NULL
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(selectResp.getError());
        assertEquals(1, selectResp.getRows().size());
        assertNull(selectResp.getRows().get(0).get("optional_price"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
}

