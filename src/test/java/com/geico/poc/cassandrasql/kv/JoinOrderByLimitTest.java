package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test JOIN with ORDER BY and LIMIT
 */
@SpringBootTest
public class JoinOrderByLimitTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Test
    public void testJoinWithMultiColumnOrderByAndLimit() throws Exception {
        String accounts = uniqueTableName("accounts");
        String branches = uniqueTableName("branches");
        
        // Create tables
        queryService.execute(String.format(
            "CREATE TABLE %s (aid INT PRIMARY KEY, bid INT, abalance INT, filler TEXT)",
            accounts
        ));
        
        queryService.execute(String.format(
            "CREATE TABLE %s (bid INT PRIMARY KEY, bbalance DOUBLE, filler TEXT)",
            branches
        ));
        
        // Insert test data
        // Branch 1 with balance -9810.0
        queryService.execute(String.format(
            "INSERT INTO %s (bid, bbalance, filler) VALUES (1, -9810.0, 'filler')",
            branches
        ));
        
        // Branch 2 with balance 5000.0
        queryService.execute(String.format(
            "INSERT INTO %s (bid, bbalance, filler) VALUES (2, 5000.0, 'filler')",
            branches
        ));
        
        // Accounts for branch 1 (various balances)
        queryService.execute(String.format(
            "INSERT INTO %s (aid, bid, abalance, filler) VALUES (1, 1, 100, 'filler')",
            accounts
        ));
        queryService.execute(String.format(
            "INSERT INTO %s (aid, bid, abalance, filler) VALUES (2, 1, 200, 'filler')",
            accounts
        ));
        queryService.execute(String.format(
            "INSERT INTO %s (aid, bid, abalance, filler) VALUES (3, 1, 50, 'filler')",
            accounts
        ));
        
        // Accounts for branch 2
        queryService.execute(String.format(
            "INSERT INTO %s (aid, bid, abalance, filler) VALUES (4, 2, 300, 'filler')",
            accounts
        ));
        queryService.execute(String.format(
            "INSERT INTO %s (aid, bid, abalance, filler) VALUES (5, 2, 400, 'filler')",
            accounts
        ));
        
        // Test: JOIN with ORDER BY (multi-column) and LIMIT
        String sql = String.format(
            "SELECT * FROM %s INNER JOIN %s ON %s.bid = %s.bid " +
            "ORDER BY bbalance DESC, aid ASC LIMIT 3",
            accounts, branches, branches, accounts
        );
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        
        // Should return exactly 3 rows (LIMIT 3)
        assertEquals(3, response.getRows().size(), 
            "Should return exactly 3 rows, not " + response.getRows().size());
        
        // Verify ordering: bbalance DESC (5000.0 first), then aid ASC
        List<Map<String, Object>> rows = response.getRows();
        
        // First 2 rows should be from branch 2 (bbalance=5000.0), ordered by aid ASC
        assertEquals(4, ((Number) rows.get(0).get("aid")).intValue(), 
            "First row should be aid=4");
        assertEquals(5, ((Number) rows.get(1).get("aid")).intValue(), 
            "Second row should be aid=5");
        
        // Third row should be from branch 1 (bbalance=-9810.0), lowest aid (which is 1)
        assertEquals(1, ((Number) rows.get(2).get("aid")).intValue(), 
            "Third row should be aid=1 (lowest aid from branch 1)");
        
        // Verify bbalance values
        assertEquals(5000.0, ((Number) rows.get(0).get("bbalance")).doubleValue(), 0.01);
        assertEquals(5000.0, ((Number) rows.get(1).get("bbalance")).doubleValue(), 0.01);
        assertEquals(-9810.0, ((Number) rows.get(2).get("bbalance")).doubleValue(), 0.01);
    }
    
    @Test
    public void testJoinWithSingleColumnOrderByAndLimit() throws Exception {
        String table1 = uniqueTableName("t1");
        String table2 = uniqueTableName("t2");
        
        // Create tables
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, val INT)",
            table1
        ));
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            table2
        ));
        
        // Insert data
        for (int i = 1; i <= 20; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, val) VALUES (%d, %d)",
                table1, i, i * 10
            ));
            queryService.execute(String.format(
                "INSERT INTO %s (id, name) VALUES (%d, 'name%d')",
                table2, i, i
            ));
        }
        
        // Test: JOIN with ORDER BY and LIMIT
        String sql = String.format(
            "SELECT * FROM %s INNER JOIN %s ON %s.id = %s.id " +
            "ORDER BY val DESC LIMIT 5",
            table1, table2, table1, table2
        );
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "Query should succeed");
        assertNotNull(response.getRows());
        
        // Should return exactly 5 rows
        assertEquals(5, response.getRows().size(), 
            "Should return exactly 5 rows");
        
        // Verify ordering: val DESC (200, 190, 180, 170, 160)
        List<Map<String, Object>> rows = response.getRows();
        assertEquals(200, ((Number) rows.get(0).get("val")).intValue());
        assertEquals(190, ((Number) rows.get(1).get("val")).intValue());
        assertEquals(180, ((Number) rows.get(2).get("val")).intValue());
        assertEquals(170, ((Number) rows.get(3).get("val")).intValue());
        assertEquals(160, ((Number) rows.get(4).get("val")).intValue());
    }
    
    @Test
    public void testJoinWithOffsetAndLimit() throws Exception {
        String table1 = uniqueTableName("t1");
        String table2 = uniqueTableName("t2");
        
        // Create tables
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, val INT)",
            table1
        ));
        
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            table2
        ));
        
        // Insert 10 rows
        for (int i = 1; i <= 10; i++) {
            queryService.execute(String.format(
                "INSERT INTO %s (id, val) VALUES (%d, %d)",
                table1, i, i
            ));
            queryService.execute(String.format(
                "INSERT INTO %s (id, name) VALUES (%d, 'name%d')",
                table2, i, i
            ));
        }
        
        // Test: JOIN with ORDER BY, OFFSET and LIMIT
        String sql = String.format(
            "SELECT * FROM %s INNER JOIN %s ON %s.id = %s.id " +
            "ORDER BY val ASC LIMIT 3 OFFSET 5",
            table1, table2, table1, table2
        );
        
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError());
        assertNotNull(response.getRows());
        
        // Should return 3 rows (rows 6, 7, 8)
        assertEquals(3, response.getRows().size());
        
        // Verify: should be val=6, 7, 8
        List<Map<String, Object>> rows = response.getRows();
        assertEquals(6, ((Number) rows.get(0).get("val")).intValue());
        assertEquals(7, ((Number) rows.get(1).get("val")).intValue());
        assertEquals(8, ((Number) rows.get(2).get("val")).intValue());
    }
}

