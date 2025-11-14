package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SELECT * with hidden rowid columns.
 * 
 * Tables without explicit PRIMARY KEY get a hidden 'rowid' column.
 * SELECT * should not return the hidden rowid to users.
 */
@SpringBootTest
@ActiveProfiles("test")
public class HiddenRowIdSelectTest extends KvTestBase {

    @Autowired
    private QueryService queryService;

    @Autowired
    private SchemaManager schemaManager;

    @Test
    public void testSelectStarWithHiddenRowId() throws Exception {
        String testTable = uniqueTableName("rowid_test");
        
        // Create table without explicit primary key (gets hidden rowid)
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + testTable + " (name VARCHAR, age INT)");
        assertNull(createResp.getError(), "CREATE TABLE should succeed: " + createResp.getError());

        // Verify table has rowid as PK internally
        TableMetadata table = schemaManager.getTable(testTable);
        assertNotNull(table, "Table should exist");
        assertEquals(1, table.getPrimaryKeyColumns().size(), "Should have 1 PK column");
        assertEquals("rowid", table.getPrimaryKeyColumns().get(0).toLowerCase(), "PK should be rowid");

        // Insert some data
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Alice', 30)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Bob', 25)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Charlie', 35)");

        // SELECT * should NOT include rowid
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable);
        assertNull(selectResp.getError(), "SELECT should succeed: " + selectResp.getError());
        assertEquals(3, selectResp.getRowCount(), "Should have 3 rows");

        // Verify column names don't include rowid
        assertFalse(selectResp.getColumns().contains("rowid"), 
                   "Columns should not include rowid: " + selectResp.getColumns());
        assertTrue(selectResp.getColumns().contains("name"), "Should include name column");
        assertTrue(selectResp.getColumns().contains("age"), "Should include age column");

        // Verify row data doesn't include rowid
        // getRows() returns List<Map<String, Object>>, so no cast needed
        for (Map<String, Object> row : selectResp.getRows()) {
            assertFalse(row.containsKey("rowid"), 
                       "Row should not contain rowid key: " + row.keySet());
            assertTrue(row.containsKey("name"), "Row should contain name");
            assertTrue(row.containsKey("age"), "Row should contain age");
        }
    }

    @Test
    public void testSelectWithLimitAndHiddenRowId() throws Exception {
        String testTable = uniqueTableName("rowid_test");
        
        // Create table and insert data
        queryService.execute("CREATE TABLE " + testTable + " (name VARCHAR, age INT)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Alice', 30)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Bob', 25)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Charlie', 35)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('David', 40)");
        queryService.execute("INSERT INTO " + testTable + " (name, age) VALUES ('Eve', 28)");

        // SELECT with LIMIT should not include rowid
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable + " LIMIT 3");
        assertNull(selectResp.getError(), "SELECT should succeed: " + selectResp.getError());
        assertTrue(selectResp.getRowCount() >= 3, "Should have at least 3 rows, got: " + selectResp.getRowCount());

        assertFalse(selectResp.getColumns().contains("rowid"), "Should not include rowid column");
        
        // getRows() returns List<Map<String, Object>>, so no cast needed
        for (Map<String, Object> row : selectResp.getRows()) {
            assertFalse(row.containsKey("rowid"), "Row should not have rowid");
        }
    }

    @Test
    public void testTableWithExplicitPkShowsPkColumn() throws Exception {
        String testTable = uniqueTableName("rowid_test_explicit_pk");

        // Create table WITH explicit primary key
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        assertNull(createResp.getError(), "CREATE TABLE should succeed: " + createResp.getError());

        // Insert data
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (1, 'Alice')");

        // SELECT * should include the explicit PK column (id)
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable + "");
        assertNull(selectResp.getError(), "SELECT should succeed: " + selectResp.getError());
        
        assertTrue(selectResp.getColumns().contains("id"), "Should include explicit PK column 'id'");
        assertTrue(selectResp.getColumns().contains("name"), "Should include name column");
        assertFalse(selectResp.getColumns().contains("rowid"), "Should not have rowid for explicit PK table");
        
        // getRows() returns List<Map<String, Object>>, so no cast needed
        Map<String, Object> row = selectResp.getRows().get(0);
        assertTrue(row.containsKey("id"), "Row should have id");
        assertEquals(1, row.get("id"), "id should be 1");
    }
}

