package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test UNION with literal expressions
 */
public class LiteralUnionTest extends KvTestBase {
    
    @Test
    public void testUnionWithLiterals() throws Exception {
        System.out.println("\n=== UNION with Literals Test ===\n");
        
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
        
        // Execute UNION with literals (the user's original query!)
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
        
        if (response.getRows() != null) {
            for (Map<String, Object> row : response.getRows()) {
                System.out.println("  Row: " + row);
            }
        }
        
        assertNull(response.getError(), "Should succeed: " + response.getError());
        assertNotNull(response.getRows(), "Should have rows");
        assertEquals(4, response.getRows().size(), "Should have 4 rows");
        
        // Verify each row has the literal table_name column
        Map<String, Object> row1 = response.getRows().get(0);
        Map<String, Object> row2 = response.getRows().get(1);
        Map<String, Object> row3 = response.getRows().get(2);
        Map<String, Object> row4 = response.getRows().get(3);
        
        System.out.println("Row 1: " + row1);
        System.out.println("Row 2: " + row2);
        System.out.println("Row 3: " + row3);
        System.out.println("Row 4: " + row4);

        // Check table names (case-insensitive)
        Object tableName1 = row1.get("table_name");
        if (tableName1 == null) tableName1 = row1.get("TABLE_NAME");
        assertEquals("Branches", tableName1, "First row should be Branches");
        
        Object tableName2 = row2.get("table_name");
        if (tableName2 == null) tableName2 = row2.get("TABLE_NAME");
        assertEquals("Tellers", tableName2, "Second row should be Tellers");
        
        Object tableName3 = row3.get("table_name");
        if (tableName3 == null) tableName3 = row3.get("TABLE_NAME");
        assertEquals("Accounts", tableName3, "Third row should be Accounts");
        
        Object tableName4 = row4.get("table_name");
        if (tableName4 == null) tableName4 = row4.get("TABLE_NAME");
        assertEquals("History", tableName4, "Fourth row should be History");
        
        // Check counts
        Object count1 = row1.get("row_count");
        if (count1 == null) count1 = row1.get("ROW_COUNT");
        assertEquals(2, ((Number) count1).intValue(), "Branches should have 2 rows");
        
        Object count2 = row2.get("row_count");
        if (count2 == null) count2 = row2.get("ROW_COUNT");
        assertEquals(2, ((Number) count2).intValue(), "Tellers should have 2 rows");
        
        Object count3 = row3.get("row_count");
        if (count3 == null) count3 = row3.get("ROW_COUNT");
        assertEquals(3, ((Number) count3).intValue(), "Accounts should have 3 rows");
        
        Object count4 = row4.get("row_count");
        if (count4 == null) count4 = row4.get("ROW_COUNT");
        assertEquals(4, ((Number) count4).intValue(), "History should have 4 rows");
        
        // Check totals
        Object total1 = row1.get("total_balance");
        if (total1 == null) total1 = row1.get("TOTAL_BALANCE");
        assertEquals(3000, ((Number) total1).intValue(), "Branches total should be 3000");
        
        Object total2 = row2.get("total_balance");
        if (total2 == null) total2 = row2.get("TOTAL_BALANCE");
        assertEquals(1100, ((Number) total2).intValue(), "Tellers total should be 1100");
        
        Object total3 = row3.get("total_balance");
        if (total3 == null) total3 = row3.get("TOTAL_BALANCE");
        assertEquals(600, ((Number) total3).intValue(), "Accounts total should be 600");
        
        Object total4 = row4.get("total_balance");
        if (total4 == null) total4 = row4.get("TOTAL_BALANCE");
        assertEquals(0, ((Number) total4).intValue(), "History total should be 0");
        
        System.out.println("\n✅ UNION with literals works perfectly!");
        System.out.println("Literal expressions are now fully supported in KV mode!");
    }
}

