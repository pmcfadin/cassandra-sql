package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that catalog stays in sync when tables are created/dropped
 */
@SpringBootTest
@ActiveProfiles({"kv-test"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvCatalogSyncTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    private String testTable1;
    private String testTable2;
    private String testIndex1;
    private String testIndex2;
    
    @BeforeEach
    public void setupTest() {
        if (testTable1 == null) {
            testTable1 = uniqueTableName("catalog_sync_test1");
            testTable2 = uniqueTableName("catalog_sync_test2");
            testIndex1 = uniqueTableName(testIndex1) + "_idx";
            testIndex2 = uniqueTableName(testIndex2) + "_idx";
        }
    }
    
    @Test
    @Order(1)
    public void testCreateTableUpdatesCatalog() throws Exception {
        System.out.println("\n=== TEST 1: Create Table Updates Catalog ===\n");
        
        // Create table
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + testTable1 + " (id INT PRIMARY KEY, name TEXT)"
        );
        assertNull(createResp.getError(), "CREATE TABLE should succeed");
        
        // Verify in pg_class
        QueryResponse pgClassResp = queryService.execute(
            "SELECT relname, relkind FROM pg_class WHERE relname = '" + testTable1 + "'"
        );
        assertNull(pgClassResp.getError(), "pg_class query should succeed");
        assertEquals(1, pgClassResp.getRows().size(), "Should find table in pg_class");
        assertEquals("r", pgClassResp.getRows().get(0).get("relkind"), "relkind should be 'r' for table");
        
        System.out.println("✅ Table created and appears in pg_class");
    }
    
    @Test
    @Order(2)
    public void testCreateIndexUpdatesCatalog() throws Exception {
        System.out.println("\n=== TEST 2: Create Index Updates Catalog ===\n");
        
        // Create table first (test isolation)
        QueryResponse createTableResp = queryService.execute(
            "CREATE TABLE " + testTable1 + " (id INT PRIMARY KEY, name TEXT)"
        );
        assertNull(createTableResp.getError(), "CREATE TABLE should succeed");
        
        // Create index
        QueryResponse createIdxResp = queryService.execute(
            "CREATE INDEX " + testIndex1 + " ON " + testTable1 + " (name)"
        );
        assertNull(createIdxResp.getError(), "CREATE INDEX should succeed: " + createIdxResp.getError());
        
        // Verify in pg_class with relkind='i'
        QueryResponse pgClassResp = queryService.execute(
            "SELECT relname, relkind FROM pg_class WHERE relname = '" + testIndex1 + "'"
        );
        assertNull(pgClassResp.getError(), "pg_class query should succeed");
        assertEquals(1, pgClassResp.getRows().size(), "Should find index in pg_class");
        assertEquals("i", pgClassResp.getRows().get(0).get("relkind"), "relkind should be 'i' for index");
        
        // Verify in pg_index (using subquery)
        QueryResponse pgIndexResp = queryService.execute(
            "SELECT indrelid FROM pg_index WHERE indexrelid IN " +
            "(SELECT oid FROM pg_class WHERE relname = '" + testIndex1 + "')"
        );
        assertNull(pgIndexResp.getError(), "pg_index query should succeed: " + pgIndexResp.getError());
        assertEquals(1, pgIndexResp.getRows().size(), "Should find index in pg_index");
        
        System.out.println("✅ Index created and appears in pg_class and pg_index");
    }
    
    @Test
    @Order(3)
    public void testDropTableRemovesFromCatalog() throws Exception {
        System.out.println("\n=== TEST 3: Drop Table Removes from Catalog ===\n");
        
        // Create second table with index
        queryService.execute(
            "CREATE TABLE " + testTable2 + " (id INT PRIMARY KEY, email TEXT)"
        );
        queryService.execute(
            "CREATE INDEX " + testIndex2 + " ON " + testTable2 + " (email)"
        );
        
        // Verify both exist
        QueryResponse beforeResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname IN ('" + testTable2 + "', '" + testIndex2 + "')"
        );
        assertNull(beforeResp.getError());
        assertEquals(2, beforeResp.getRows().size(), "Should find table and index before drop");
        
        // Drop the table
        QueryResponse dropResp = queryService.execute("DROP TABLE " + testTable2);
        assertNull(dropResp.getError(), "DROP TABLE should succeed");
        
        // Verify table is removed from pg_class
        QueryResponse afterTableResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname = '" + testTable2 + "'"
        );
        assertNull(afterTableResp.getError());
        assertEquals(0, afterTableResp.getRows().size(), "Table should be removed from pg_class");
        
        // Verify index is also removed from pg_class
        QueryResponse afterIndexResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname = '" + testIndex2 + "'"
        );
        assertNull(afterIndexResp.getError());
        assertEquals(0, afterIndexResp.getRows().size(), "Index should be removed from pg_class");
    
        System.out.println("✅ Dropped table and its indexes removed from catalog");
    }
    
    @Test
    @Disabled("Not implemented")
    @Order(4)
    public void testDiCommandShowsOnlyIndexes() throws Exception {
        System.out.println("\n=== TEST 4: \\di Shows Only Indexes ===\n");
        
        // Query pg_class for indexes (what \di does)
        QueryResponse indexesResp = queryService.execute(
            "SELECT i.relname as indexname, t.relname as tablename " +
            "FROM pg_class i " +
            "JOIN pg_index idx ON idx.indexrelid = i.oid " +
            "JOIN pg_class t ON t.oid = idx.indrelid " +
            "WHERE i.relkind = 'i'"
        );
        
        assertNull(indexesResp.getError(), "Index query should succeed");
        assertNotNull(indexesResp.getRows(), "Should have rows");
        
        System.out.println("Found " + indexesResp.getRows().size() + " indexes:");
        for (var row : indexesResp.getRows()) {
            String indexName = (String) row.get("indexname");
            String tableName = (String) row.get("tablename");
            System.out.println("  - " + indexName + " on " + tableName);
            
            // Verify this is actually an index, not a table
            assertNotNull(indexName, "Index name should not be null");
            assertNotNull(tableName, "Table name should not be null");
        }
        
        // Verify our test index is in the list
        boolean foundTestIndex = indexesResp.getRows().stream()
            .anyMatch(row -> testIndex1.equals(row.get("indexname")));
        assertTrue(foundTestIndex, "Should find our test index: " + testIndex1);
        
        // Verify no tables are in the list (all should have relkind='i')
        QueryResponse verifyResp = queryService.execute(
            "SELECT relname, relkind FROM pg_class WHERE oid IN " +
            "(SELECT indexrelid FROM pg_index)"
        );
        assertNull(verifyResp.getError());
        for (var row : verifyResp.getRows()) {
            assertEquals("i", row.get("relkind"), 
                "All entries should be indexes (relkind='i'), not tables");
        }
        
        System.out.println("✅ \\di query shows only indexes, not tables");
    }
    
    @Test
    @Disabled("Not implemented")
    @Order(5)
    public void testDtCommandShowsOnlyTables() throws Exception {
        System.out.println("\n=== TEST 5: \\dt Shows Only Tables ===\n");
        
        // Query pg_class for tables (what \dt does)
        QueryResponse tablesResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relkind = 'r' AND relnamespace = " +
            "(SELECT oid FROM pg_namespace WHERE nspname = 'public')"
        );
        
        assertNull(tablesResp.getError(), "Table query should succeed");
        assertNotNull(tablesResp.getRows(), "Should have rows");
        
        System.out.println("Found " + tablesResp.getRows().size() + " tables:");
        for (var row : tablesResp.getRows()) {
            String tableName = (String) row.get("relname");
            System.out.println("  - " + tableName);
        }
        
        // Verify our test table is in the list
        boolean foundTestTable = tablesResp.getRows().stream()
            .anyMatch(row -> testTable1.equals(row.get("relname")));
        assertTrue(foundTestTable, "Should find our test table: " + testTable1);
        
        // Verify no indexes are in the list
        for (var row : tablesResp.getRows()) {
            String relname = (String) row.get("relname");
            assertFalse(relname.endsWith("_idx"), "Should not find indexes in table list");
        }
        
        System.out.println("✅ \\dt query shows only tables, not indexes");
    }
}

