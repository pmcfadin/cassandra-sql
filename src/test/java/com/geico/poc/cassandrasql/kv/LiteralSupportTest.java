package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test literal expression support in aggregation queries
 */
public class LiteralSupportTest extends KvTestBase {
    
    @Test
    public void testLiteralWithAggregate() throws Exception {
        System.out.println("\n=== Literal + Aggregate Test ===\n");
        
        // Create table
        String tableName = uniqueTableName("test_table");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, val INT)");
        
        // Insert data
        queryService.execute("INSERT INTO " + tableName + " (id, val) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + tableName + " (id, val) VALUES (2, 200)");
        
        // Query with literal
        String sql = "SELECT 'TestTable' as table_name, COUNT(*) as row_count, SUM(val) as total FROM " + tableName;
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Columns: " + response.getColumns());
        System.out.println("Rows: " + response.getRows());
        
        assertNull(response.getError(), "Should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(1, response.getRows().size(), "Should have 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        System.out.println("Row: " + row);
        
        // Check literal column
        Object tableNameCol = row.get("table_name");
        if (tableNameCol == null) tableNameCol = row.get("TABLE_NAME");
        assertNotNull(tableNameCol, "Should have table_name");
        assertEquals("TestTable", tableNameCol.toString(), "Literal value should be 'TestTable'");
        
        // Check aggregate columns
        Object countObj = row.get("row_count");
        if (countObj == null) countObj = row.get("ROW_COUNT");
        assertNotNull(countObj, "Should have row_count");
        assertEquals(2, ((Number) countObj).intValue(), "Should count 2 rows");
        
        Object totalObj = row.get("total");
        if (totalObj == null) totalObj = row.get("TOTAL");
        assertNotNull(totalObj, "Should have total");
        assertEquals(300, ((Number) totalObj).intValue(), "Should sum to 300");
    }
    
    @Test
    public void testUserUnionQueryWithLiterals() throws Exception {
        System.out.println("\n=== User's UNION Query with Literals ===\n");
        
        // Use unique table names
        String branches = uniqueTableName("mini_branches");
        String tellers = uniqueTableName("mini_tellers");
        String accounts = uniqueTableName("mini_accounts");
        String history = uniqueTableName("mini_history");
        
        // Create tables
        queryService.execute("CREATE TABLE " + branches + " (bid INT PRIMARY KEY, bbalance INT)");
        queryService.execute("CREATE TABLE " + tellers + " (tid INT PRIMARY KEY, tbalance INT)");
        queryService.execute("CREATE TABLE " + accounts + " (aid INT PRIMARY KEY, abalance INT)");
        queryService.execute("CREATE TABLE " + history + " (hid INT PRIMARY KEY)");
        
        // Insert data
        queryService.execute("INSERT INTO " + branches + " (bid, bbalance) VALUES (1, 1000)");
        queryService.execute("INSERT INTO " + branches + " (bid, bbalance) VALUES (2, 2000)");
        queryService.execute("INSERT INTO " + tellers + " (tid, tbalance) VALUES (1, 500)");
        queryService.execute("INSERT INTO " + tellers + " (tid, tbalance) VALUES (2, 600)");
        queryService.execute("INSERT INTO " + accounts + " (aid, abalance) VALUES (1, 100)");
        queryService.execute("INSERT INTO " + accounts + " (aid, abalance) VALUES (2, 200)");
        queryService.execute("INSERT INTO " + accounts + " (aid, abalance) VALUES (3, 300)");
        queryService.execute("INSERT INTO " + history + " (hid) VALUES (1)");
        queryService.execute("INSERT INTO " + history + " (hid) VALUES (2)");
        queryService.execute("INSERT INTO " + history + " (hid) VALUES (3)");
        queryService.execute("INSERT INTO " + history + " (hid) VALUES (4)");
        
        // Execute the user's UNION query WITH literals
        String sql = "SELECT 'Branches' as table_name, COUNT(*) as row_count, SUM(bbalance) as total_balance FROM " + branches + " " +
                     "UNION ALL " +
                     "SELECT 'Tellers' as table_name, COUNT(*) as row_count, SUM(tbalance) as total_balance FROM " + tellers + " " +
                     "UNION ALL " +
                     "SELECT 'Accounts' as table_name, COUNT(*) as row_count, SUM(abalance) as total_balance FROM " + accounts + " " +
                     "UNION ALL " +
                     "SELECT 'History' as table_name, COUNT(*) as row_count, 0 as total_balance FROM " + history;
        
        System.out.println("Query: " + sql);
        
        QueryResponse response = queryService.execute(sql);
        
        System.out.println("Error: " + response.getError());
        System.out.println("Columns: " + response.getColumns());
        System.out.println("Rows: " + response.getRows());
        
        assertNull(response.getError(), "Should succeed");
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(4, response.getRows().size(), "Should have 4 rows");
        
        // Print results
        for (Map<String, Object> row : response.getRows()) {
            Object tableName = row.get("table_name");
            if (tableName == null) tableName = row.get("TABLE_NAME");
            
            Object count = row.get("row_count");
            if (count == null) count = row.get("ROW_COUNT");
            
            Object total = row.get("total_balance");
            if (total == null) total = row.get("TOTAL_BALANCE");
            
            System.out.println("  " + tableName + ": count=" + count + ", total=" + total);
        }
        
        // Verify first row has 'Branches' literal
        Map<String, Object> firstRow = response.getRows().get(0);
        Object tableName = firstRow.get("table_name");
        if (tableName == null) tableName = firstRow.get("TABLE_NAME");
        assertNotNull(tableName, "Should have table_name");
        assertEquals("Branches", tableName.toString(), "First row should be 'Branches'");
        
        System.out.println("\n✅ User's UNION query with literals works!");
    }
}

