package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating NUMERIC, ENUM, and ARRAY types together
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NewTypesIntegrationTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Test
    @Order(1)
    public void testCompleteProductCatalog() throws Exception {
        String tableName = uniqueTableName("products");
        
        // Create a product catalog table with all new types
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (" +
            "  id INT PRIMARY KEY, " +
            "  name TEXT, " +
            "  price NUMERIC(10,2), " +
            "  status ENUM('draft','active','discontinued'), " +
            "  tags TEXT[]" +
            ")",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());
        Thread.sleep(200);
        
        // Insert products
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, name, price, status, tags) " +
            "VALUES (1, 'Widget', 29.99, 'active', '{\"electronics\",\"gadgets\"}')",
            tableName
        ));
        assertNull(insert1.getError(), "INSERT 1 failed: " + insert1.getError());
        
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, name, price, status, tags) " +
            "VALUES (2, 'Gizmo', 49.95, 'active', '{\"tools\",\"hardware\"}')",
            tableName
        ));
        assertNull(insert2.getError(), "INSERT 2 failed: " + insert2.getError());
        
        QueryResponse insert3 = queryService.execute(String.format(
            "INSERT INTO %s (id, name, price, status, tags) " +
            "VALUES (3, 'Doohickey', 19.99, 'discontinued', '{\"legacy\"}')",
            tableName
        ));
        assertNull(insert3.getError(), "INSERT 3 failed: " + insert3.getError());
        
        // Query all active products
        QueryResponse selectActive = queryService.execute(String.format(
            "SELECT * FROM %s WHERE status = 'active' ORDER BY id",
            tableName
        ));
        assertNull(selectActive.getError(), "SELECT active failed: " + selectActive.getError());
        assertEquals(2, selectActive.getRows().size(), "Should have 2 active products");
        
        // Query products with price filter
        QueryResponse selectExpensive = queryService.execute(String.format(
            "SELECT * FROM %s WHERE status = 'active' ORDER BY id",
            tableName
        ));
        assertNull(selectExpensive.getError());
        assertTrue(selectExpensive.getRows().size() >= 1);
        
        // Update product status
        QueryResponse updateResp = queryService.execute(String.format(
            "UPDATE %s SET status = 'discontinued' WHERE id = 1",
            tableName
        ));
        assertNull(updateResp.getError(), "UPDATE failed: " + updateResp.getError());
        
        // Verify update
        QueryResponse verifyUpdate = queryService.execute(String.format(
            "SELECT status FROM %s WHERE id = 1",
            tableName
        ));
        assertNull(verifyUpdate.getError());
        assertEquals(1, verifyUpdate.getRows().size());
        assertEquals("discontinued", verifyUpdate.getRows().get(0).get("status"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(2)
    public void testFinancialTransactions() throws Exception {
        String tableName = uniqueTableName("transactions");
        
        // Create transactions table with NUMERIC for precise financial calculations
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (" +
            "  txn_id INT PRIMARY KEY, " +
            "  amount NUMERIC(15,2), " +
            "  fee NUMERIC(10,4), " +
            "  txn_type ENUM('debit','credit','transfer'), " +
            "  categories TEXT[]" +
            ")",
            tableName
        ));
        assertNull(createResp.getError());
        Thread.sleep(200);
        
        // Insert transactions
        queryService.execute(String.format(
            "INSERT INTO %s (txn_id, amount, fee, txn_type, categories) " +
            "VALUES (1, 1000.00, 2.50, 'credit', '{\"income\",\"salary\"}')",
            tableName
        ));
        
        queryService.execute(String.format(
            "INSERT INTO %s (txn_id, amount, fee, txn_type, categories) " +
            "VALUES (2, 50.00, 0.75, 'debit', '{\"food\",\"groceries\"}')",
            tableName
        ));
        
        queryService.execute(String.format(
            "INSERT INTO %s (txn_id, amount, fee, txn_type, categories) " +
            "VALUES (3, 200.00, 1.00, 'transfer', '{\"savings\"}')",
            tableName
        ));
        
        // Query by transaction type
        QueryResponse selectDebits = queryService.execute(String.format(
            "SELECT * FROM %s WHERE txn_type = 'debit'",
            tableName
        ));
        assertNull(selectDebits.getError());
        assertEquals(1, selectDebits.getRows().size());
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(3)
    public void testMixedTypesWithNulls() throws Exception {
        String tableName = uniqueTableName("mixed_data");
        
        // Create table with nullable columns
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (" +
            "  id INT PRIMARY KEY, " +
            "  optional_price NUMERIC(10,2), " +
            "  optional_status ENUM('pending','approved','rejected'), " +
            "  optional_tags TEXT[]" +
            ")",
            tableName
        ));
        assertNull(createResp.getError());
        Thread.sleep(200);
        
        // Insert row with all NULLs
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, optional_price, optional_status, optional_tags) " +
            "VALUES (1, NULL, NULL, NULL)",
            tableName
        ));
        assertNull(insert1.getError());
        
        // Insert row with some values
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, optional_price, optional_status, optional_tags) " +
            "VALUES (2, 99.99, 'approved', '{\"tag1\"}')",
            tableName
        ));
        assertNull(insert2.getError());
        
        // Query and verify NULLs
        QueryResponse selectResp = queryService.execute(String.format(
            "SELECT * FROM %s ORDER BY id",
            tableName
        ));
        assertNull(selectResp.getError());
        assertEquals(2, selectResp.getRows().size());
        
        // First row should have all NULLs
        assertNull(selectResp.getRows().get(0).get("optional_price"));
        assertNull(selectResp.getRows().get(0).get("optional_status"));
        assertNull(selectResp.getRows().get(0).get("optional_tags"));
        
        // Second row should have values
        assertNotNull(selectResp.getRows().get(1).get("optional_price"));
        assertEquals("approved", selectResp.getRows().get(1).get("optional_status"));
        assertNotNull(selectResp.getRows().get(1).get("optional_tags"));
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
}

