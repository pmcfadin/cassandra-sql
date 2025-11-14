package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import com.geico.poc.cassandrasql.kv.jobs.VacuumJob;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for lazy DROP TABLE optimization.
 * 
 * Similar to TRUNCATE, DROP TABLE is now O(1):
 * 1. Sets droppedTimestamp in metadata
 * 2. Removes from in-memory cache
 * 3. VacuumJob cleans up data later
 */
@SpringBootTest
@ActiveProfiles("test")
public class LazyDropTest extends KvTestBase {

    @Autowired
    private QueryService queryService;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private VacuumJob vacuumJob;

    @Test
    @Order(1)
    public void testLazyDropIsInstant() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");

        // Create table with data
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (2, 'Bob')");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (3, 'Charlie')");

        // Verify data exists
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable + "");
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertEquals(3, selectResp.getRowCount(), "Should have 3 rows");

        // Drop table (should be instant, O(1))
        long startTime = System.currentTimeMillis();
        QueryResponse dropResp = queryService.execute("DROP TABLE " + testTable + "");
        long dropTime = System.currentTimeMillis() - startTime;

        assertNull(dropResp.getError(), "DROP should succeed: " + dropResp.getError());
        assertTrue(dropTime < 1000, "DROP should be instant (< 1s), was " + dropTime + "ms");

        // Table should not be accessible
        QueryResponse selectAfterDrop = queryService.execute("SELECT * FROM " + testTable + "");
        assertNotNull(selectAfterDrop.getError(), "SELECT should fail after DROP");
        assertTrue(selectAfterDrop.getError().contains("does not exist") || 
                   selectAfterDrop.getError().contains("not found"),
                   "Error should indicate table doesn't exist: " + selectAfterDrop.getError());
    }

    @Test
    @Order(2)
    public void testDroppedTableNotInCache() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");

        // Create and drop table
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("INSERT INTO " + testTable + "p_test (id, name) VALUES (1, 'Alice')");
        queryService.execute("DROP TABLE " + testTable + "");

        // Table should not be in active cache
        TableMetadata table = schemaManager.getTable(testTable);
        assertNull(table, "Dropped table should not be in cache");

        // But should be in dropped tables list
        List<TableMetadata> droppedTables = schemaManager.getAllDroppedTables();
        boolean found = droppedTables.stream()
            .anyMatch(t -> t.getTableName().equalsIgnoreCase(testTable));
        assertTrue(found, "Table should be in dropped tables list");
    }

    @Test
    @Order(3)
    public void testDroppedTableHasTimestamp() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");

        // Create and drop table
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        long beforeDrop = System.currentTimeMillis() * 1000; // Convert to microseconds
        queryService.execute("DROP TABLE " + testTable + "");
        long afterDrop = System.currentTimeMillis() * 1000;

        // Get dropped table metadata
        List<TableMetadata> droppedTables = schemaManager.getAllDroppedTables();
        TableMetadata droppedTable = droppedTables.stream()
            .filter(t -> t.getTableName().equalsIgnoreCase(testTable))
            .findFirst()
            .orElse(null);

        assertNotNull(droppedTable, "Should find dropped table");
        assertTrue(droppedTable.isDropped(), "Table should be marked as dropped");
        assertNotNull(droppedTable.getDroppedTimestamp(), "Should have droppedTimestamp");
        
        // Timestamp should be reasonable (within the test timeframe, with some tolerance)
        long droppedTs = droppedTable.getDroppedTimestamp();
        System.out.println("Before: " + beforeDrop + " After: " + afterDrop + " Dropped: " + droppedTs);
        assertTrue(droppedTs >= beforeDrop - 10000000, // 10 second tolerance before
                   "droppedTimestamp should be after beforeDrop: " + droppedTs + " >= " + (beforeDrop - 10000000));
        assertTrue(droppedTs <= afterDrop + 10000000, // 10 second tolerance after
                   "droppedTimestamp should be before afterDrop: " + droppedTs + " <= " + (afterDrop + 10000000));
    }

    @Test
    @Order(4)
    public void testVacuumCleansUpDroppedTable() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");

        // Create table with data
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (2, 'Bob')");

        // Drop table
        queryService.execute("DROP TABLE " + testTable + "");

        // Verify table is in dropped list
        List<TableMetadata> droppedBefore = schemaManager.getAllDroppedTables();
        boolean foundBefore = droppedBefore.stream()
            .anyMatch(t -> t.getTableName().equalsIgnoreCase(testTable));
        assertTrue(foundBefore, "Table should be in dropped list before vacuum");

        // Run vacuum job
        System.out.println("\n=== Running VacuumJob to clean up dropped table ===");
        vacuumJob.execute();
        System.out.println("=== VacuumJob completed ===");

        // Verify table is cleaned up
        List<TableMetadata> droppedAfter = schemaManager.getAllDroppedTables();
        boolean foundAfter = droppedAfter.stream()
            .anyMatch(t -> t.getTableName().equalsIgnoreCase(testTable));
        assertFalse(foundAfter, "Table should be cleaned up after vacuum");
    }

    @Test
    @Order(5)
    public void testDropMultipleTablesLazy() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");
        String testTable2 = uniqueTableName("lazy_drop_test2");

        // Create multiple tables
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("CREATE TABLE " + testTable2 + " (id INT PRIMARY KEY, v INT)");
        
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + testTable2 + " (id, v) VALUES (1, 100)");

        // Drop both tables
        QueryResponse dropResp = queryService.execute("DROP TABLE " + testTable + ", " + testTable2 + "");
        assertNull(dropResp.getError(), "DROP should succeed: " + dropResp.getError());

        // Both tables should not be accessible
        QueryResponse select1 = queryService.execute("SELECT * FROM " + testTable + "");
        assertNotNull(select1.getError(), "SELECT should fail after DROP");

        QueryResponse select2 = queryService.execute("SELECT * FROM " + testTable2 + "");
        assertNotNull(select2.getError(), "SELECT should fail after DROP");

        // Both should be in dropped list
        List<TableMetadata> droppedTables = schemaManager.getAllDroppedTables();
        System.out.println("Test tables: " + testTable + ", " + testTable2);
        System.out.println("Dropped tables: " + droppedTables);
        long droppedCount = droppedTables.stream()
            .filter(t -> t.getTableName().equalsIgnoreCase(testTable) ||
                        t.getTableName().equalsIgnoreCase(testTable2))
            .count();
        assertEquals(2, droppedCount, "Both tables should be in dropped list");
    }

    @Test   
    @Order(6)
    public void testDropTableWithSecondaryIndex() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");
        String testIndex = uniqueTableName("idx_name");
        
        // Create table with secondary index
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR, age INT)");
        queryService.execute("CREATE INDEX " + testIndex + " ON " + testTable + " (name)");
        
        queryService.execute("INSERT INTO " + testTable + " (id, name, age) VALUES (1, 'Alice', 30)");
        queryService.execute("INSERT INTO " + testTable + " (id, name, age) VALUES (2, 'Bob', 25)");

        // Drop table (should mark both table and index for cleanup)
        QueryResponse dropResp = queryService.execute("DROP TABLE " + testTable + "");
        assertNull(dropResp.getError(), "DROP should succeed: " + dropResp.getError());

        // Table should not be accessible
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable + "");
        assertNotNull(selectResp.getError(), "SELECT should fail after DROP");

        // Vacuum should clean up both table data and index data
        vacuumJob.execute();

        List<TableMetadata> droppedAfter = schemaManager.getAllDroppedTables();
        boolean found = droppedAfter.stream()
            .anyMatch(t -> t.getTableName().equalsIgnoreCase(testTable));
        assertFalse(found, "Table should be fully cleaned up after vacuum");
    }

    @Test
    @Order(6)
    public void testDropIfExistsLazy() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");

        // Drop non-existent table with IF EXISTS
        QueryResponse dropResp1 = queryService.execute("DROP TABLE IF EXISTS " + testTable + " ");
        assertNull(dropResp1.getError(), "DROP IF EXISTS should succeed for non-existent table");

        // Create and drop with IF EXISTS
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        QueryResponse dropResp2 = queryService.execute("DROP TABLE IF EXISTS " + testTable + "");
        assertNull(dropResp2.getError(), "DROP IF EXISTS should succeed for existing table");

        // Table should not be accessible
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable + "");
        assertNotNull(selectResp.getError(), "SELECT should fail after DROP");
    }

    @Test
    @Order(7)
    public void testRecreateTableAfterDrop() throws Exception {
        String testTable = uniqueTableName("lazy_drop_recreate");

        // Create table
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (1, 'Alice')");

        // Get original table ID
        TableMetadata originalTable = schemaManager.getTable(testTable);
        assertNotNull(originalTable, "Table should exist");
        long originalTableId = originalTable.getTableId();

        // Drop table
        queryService.execute("DROP TABLE " + testTable + "");

        // Run vacuum to clean up
        System.out.println("=== Running VacuumJob to clean up dropped table ===");
        vacuumJob.execute();
        System.out.println("=== VacuumJob completed ===");

        // Recreate table with same name
        QueryResponse createResp = queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, v  INT)");
        assertNull(createResp.getError(), "CREATE should succeed after vacuum: " + createResp.getError());

        // New table should have different table ID
        TableMetadata newTable = schemaManager.getTable(testTable);
        assertNotNull(newTable, "New table should exist");
        assertNotEquals(originalTableId, newTable.getTableId(), 
                       "New table should have different table ID to avoid data collision");

        // New table should be empty
        QueryResponse selectResp = queryService.execute("SELECT * FROM " + testTable + "");
        assertNull(selectResp.getError(), "SELECT should succeed");
        assertEquals(0, selectResp.getRowCount(), "New table should be empty");

        // Insert into new table
        QueryResponse insertResp = queryService.execute("INSERT INTO " + testTable + " (id, v) VALUES (1, 100)");
        assertNull(insertResp.getError(), "INSERT should succeed: " + insertResp.getError());
        
        QueryResponse selectResp2 = queryService.execute("SELECT * FROM " + testTable + " ");
        assertNull(selectResp2.getError(), "SELECT should succeed: " + selectResp2.getError());
        assertEquals(1, selectResp2.getRowCount(), "Should have 1 row in new table");
    }

    @Test
    @Order(8)
    public void testDropAndTruncateInteraction() throws Exception {
        String testTable = uniqueTableName("lazy_drop_test");

        // Create table
        queryService.execute("CREATE TABLE " + testTable + " (id INT PRIMARY KEY, name VARCHAR)");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (1, 'Alice')");
        queryService.execute("INSERT INTO " + testTable + " (id, name) VALUES (2, 'Bob')");

        // Truncate table
        queryService.execute("TRUNCATE TABLE " + testTable + "");

        // Get truncate timestamp
        TableMetadata tableAfterTruncate = schemaManager.getTable(testTable);
        assertNotNull(tableAfterTruncate.getTruncateTimestamp(), "Should have truncate timestamp");

        // Drop table
        queryService.execute("DROP TABLE " + testTable + "");

        // Get dropped table metadata
        List<TableMetadata> droppedTables = schemaManager.getAllDroppedTables();
        TableMetadata droppedTable = droppedTables.stream()
            .filter(t -> t.getTableName().equalsIgnoreCase(testTable))
            .findFirst()
            .orElse(null);

        assertNotNull(droppedTable, "Should find dropped table");
        assertNotNull(droppedTable.getTruncateTimestamp(), "Should preserve truncate timestamp");
        assertNotNull(droppedTable.getDroppedTimestamp(), "Should have dropped timestamp");
        assertTrue(droppedTable.getDroppedTimestamp() >= droppedTable.getTruncateTimestamp(),
                   "Dropped timestamp should be after truncate timestamp");

        // Vacuum should clean up
        System.out.println("=== Running VacuumJob to clean up dropped table ===");
        vacuumJob.execute();
        System.out.println("=== VacuumJob completed ==="); 
          
        List<TableMetadata> droppedAfter = schemaManager.getAllDroppedTables();
        boolean found = droppedAfter.stream()
            .anyMatch(t -> t.getTableName().equalsIgnoreCase(testTable));
        assertFalse(found, "Table should be fully cleaned up");
    }
}

