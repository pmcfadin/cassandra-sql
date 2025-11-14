package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PostgreSQL catalog maintenance in KV mode.
 * 
 * Verifies that:
 * 1. Tables are registered in pg_class when created
 * 2. Columns are registered in pg_attribute when created
 * 3. Indexes are registered in pg_index when created
 * 4. Catalog queries return correct results
 * 5. \dt, \dti, \d commands work correctly
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PgCatalogTest extends KvTestBase {
    
    // ========================================
    // Test 1: Table Creation Updates Catalog
    // ========================================
    
    @Test
    @Order(1)
    public void testTableCreationUpdatesCatalog() throws Exception {
        System.out.println("\n=== TEST 1: Table Creation Updates Catalog ===\n");
        
        String tableName = uniqueTableName("catalog_test");
        
        System.out.println("1. Create table");
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, name TEXT, age INT)"
        );
        assertNull(createResp.getError(), "CREATE TABLE should succeed");
        
        System.out.println("2. Query pg_class for the table");
        QueryResponse pgClassResp = queryService.execute(
            "SELECT relname, relkind, relnatts FROM pg_class WHERE relname = '" + tableName + "'"
        );
        
        assertNull(pgClassResp.getError(), "pg_class query should succeed");
        assertNotNull(pgClassResp.getRows(), "Should have rows");
        assertFalse(pgClassResp.getRows().isEmpty(), "Table should exist in pg_class");
        
        Map<String, Object> tableRow = pgClassResp.getRows().get(0);
        System.out.println("   relname: " + tableRow.get("relname"));
        System.out.println("   relkind: " + tableRow.get("relkind"));
        System.out.println("   relnatts: " + tableRow.get("relnatts"));
        
        assertEquals(tableName, tableRow.get("relname"), "Table name should match");
        assertEquals("r", tableRow.get("relkind"), "relkind should be 'r' (ordinary table)");
        assertEquals(3, tableRow.get("relnatts"), "Should have 3 columns");
        
        System.out.println("\n✅ Table registered in pg_class\n");
    }
    
    // ========================================
    // Test 2: Columns Registered in pg_attribute
    // ========================================
    
    @Test
    @Order(2)
    public void testColumnsInPgAttribute() throws Exception {
        System.out.println("\n=== TEST 2: Columns Registered in pg_attribute ===\n");
        
        String tableName = uniqueTableName("attr_test");
        
        System.out.println("1. Create table with multiple columns");
        queryService.execute(
            "CREATE TABLE " + tableName + " (" +
            "id INT PRIMARY KEY, " +
            "name TEXT, " +
            "email TEXT, " +
            "age INT, " +
            "active BOOLEAN)"
        );
        
        System.out.println("2. Query pg_attribute for columns");
        QueryResponse attrResp = queryService.execute(
            "SELECT a.attname, a.attnum, a.atttypid " +
            "FROM pg_attribute a " +
            "JOIN pg_class c ON a.attrelid = c.oid " +
            "WHERE c.relname = '" + tableName + "' " +
            "ORDER BY a.attnum"
        );
        
        assertNull(attrResp.getError(), "pg_attribute query should succeed");
        assertNotNull(attrResp.getRows(), "Should have rows");
        assertEquals(5, attrResp.getRows().size(), "Should have 5 columns");
        
        System.out.println("   Columns found:");
        for (Map<String, Object> col : attrResp.getRows()) {
            System.out.println("     " + col.get("attnum") + ": " + col.get("attname") + " (type=" + col.get("atttypid") + ")");
        }
        
        // Verify column names
        List<String> columnNames = attrResp.getRows().stream()
            .map(row -> (String) row.get("attname"))
            .toList();
        
        assertTrue(columnNames.contains("id"), "Should have 'id' column");
        assertTrue(columnNames.contains("name"), "Should have 'name' column");
        assertTrue(columnNames.contains("email"), "Should have 'email' column");
        assertTrue(columnNames.contains("age"), "Should have 'age' column");
        assertTrue(columnNames.contains("active"), "Should have 'active' column");
        
        System.out.println("\n✅ All columns registered in pg_attribute\n");
    }
    
    // ========================================
    // Test 3: Indexes Registered in pg_index
    // ========================================
    
    @Test
    @Order(3)
    public void testIndexesInPgIndex() throws Exception {
        System.out.println("\n=== TEST 3: Indexes Registered in pg_index ===\n");
        
        String tableName = uniqueTableName("index_test");
        String indexName = tableName + "_email_idx";
        
        System.out.println("1. Create table");
        queryService.execute(
            "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, email TEXT, name TEXT)"
        );
        
        System.out.println("2. Create index on email column");
        QueryResponse createIndexResp = queryService.execute(
            "CREATE INDEX " + indexName + " ON " + tableName + " (email)"
        );
        assertNull(createIndexResp.getError(), "CREATE INDEX should succeed");
        
        System.out.println("3. Query pg_index for the index");
        QueryResponse indexResp = queryService.execute(
            "SELECT i.indisunique, i.indisprimary, i.indkey " +
            "FROM pg_index i " +
            "JOIN pg_class c ON i.indexrelid = c.oid " +
            "WHERE c.relname = '" + indexName + "'"
        );
        
        assertNull(indexResp.getError(), "pg_index query should succeed");
        assertNotNull(indexResp.getRows(), "Should have rows");
        assertFalse(indexResp.getRows().isEmpty(), "Index should exist in pg_index");
        
        Map<String, Object> indexRow = indexResp.getRows().get(0);
        System.out.println("   indisunique: " + indexRow.get("indisunique"));
        System.out.println("   indisprimary: " + indexRow.get("indisprimary"));
        System.out.println("   indkey: " + indexRow.get("indkey"));
        
        assertEquals(false, indexRow.get("indisunique"), "Should not be unique");
        assertEquals(false, indexRow.get("indisprimary"), "Should not be primary");
        
        System.out.println("\n✅ Index registered in pg_index\n");
    }
    
    // ========================================
    // Test 4: Primary Key Registered as Index
    // ========================================
    
    @Test
    @Order(4)
    public void testPrimaryKeyInPgIndex() throws Exception {
        System.out.println("\n=== TEST 4: Primary Key Registered as Index ===\n");
        
        String tableName = uniqueTableName("pk_test");
        
        System.out.println("1. Create table with primary key");
        queryService.execute(
            "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data TEXT)"
        );
        
        System.out.println("2. Query pg_index for primary key");
        // First, get the table OID from pg_class
        QueryResponse tableResp = queryService.execute(
            "SELECT oid FROM pg_class WHERE relname = '" + tableName + "'"
        );
        assertNull(tableResp.getError(), "pg_class query should succeed");
        assertNotNull(tableResp.getRows(), "Should have table row");
        assertFalse(tableResp.getRows().isEmpty(), "Table should exist in pg_class");
        Long tableOid = (Long) tableResp.getRows().get(0).get("oid");
        assertNotNull(tableOid, "Table OID should not be null");
        
        // Now query pg_index directly using the table OID
        QueryResponse indexResp = queryService.execute(
            "SELECT indisprimary, indisunique FROM pg_index WHERE indrelid = " + tableOid + " AND indisprimary = true"
        );
        
        assertNull(indexResp.getError(), "pg_index query should succeed");
        assertNotNull(indexResp.getRows(), "Should have rows");
        assertFalse(indexResp.getRows().isEmpty(), "Primary key should exist in pg_index");
        
        Map<String, Object> pkRow = indexResp.getRows().get(0);
        System.out.println("   indisprimary: " + pkRow.get("indisprimary"));
        System.out.println("   indisunique: " + pkRow.get("indisunique"));
        
        assertEquals(true, pkRow.get("indisprimary"), "Should be primary");
        assertEquals(true, pkRow.get("indisunique"), "Primary key should be unique");
        
        System.out.println("\n✅ Primary key registered in pg_index\n");
    }
    
    // ========================================
    // Test 5: \dt Command (List Tables)
    // ========================================
    
    @Test
    @Order(5)
    public void testListTablesCommand() throws Exception {
        System.out.println("\n=== TEST 5: \\dt Command (List Tables) ===\n");
        
        String table1 = uniqueTableName("list_test_1");
        String table2 = uniqueTableName("list_test_2");
        
        System.out.println("1. Create two tables");
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        
        System.out.println("2. Query pg_tables (what \\dt uses)");
        QueryResponse tablesResp = queryService.execute(
            "SELECT schemaname, tablename FROM pg_tables WHERE schemaname = 'cassandra_sql'"
        );
        
        assertNull(tablesResp.getError(), "pg_tables query should succeed");
        assertNotNull(tablesResp.getRows(), "Should have rows");
        
        System.out.println("   Tables found: " + tablesResp.getRows().size());
        
        // Check that our tables are in the list
        List<String> tableNames = tablesResp.getRows().stream()
            .map(row -> (String) row.get("tablename"))
            .toList();
        
        System.out.println("   Table names: " + tableNames);
        
        assertTrue(tableNames.contains(table1), "Should find " + table1);
        assertTrue(tableNames.contains(table2), "Should find " + table2);
        
        System.out.println("\n✅ \\dt command works\n");
    }
    
    // ========================================
    // Test 6: \d <table> Command (Describe Table)
    // ========================================
    
    @Test
    @Order(6)
    public void testDescribeTableCommand() throws Exception {
        System.out.println("\n=== TEST 6: \\d <table> Command (Describe Table) ===\n");
        
        String tableName = uniqueTableName("describe_test");
        
        System.out.println("1. Create table");
        queryService.execute(
            "CREATE TABLE " + tableName + " (" +
            "id INT PRIMARY KEY, " +
            "email TEXT, " +
            "age INT)"
        );
        
        System.out.println("2. Query for table description (what \\d uses)");
        QueryResponse descResp = queryService.execute(
            "SELECT a.attname AS column_name, " +
            "       t.typname AS data_type, " +
            "       a.attnotnull AS not_null " +
            "FROM pg_attribute a " +
            "JOIN pg_class c ON a.attrelid = c.oid " +
            "JOIN pg_type t ON a.atttypid = t.oid " +
            "WHERE c.relname = '" + tableName + "' " +
            "ORDER BY a.attnum"
        );
        
        assertNull(descResp.getError(), "Table description query should succeed");
        assertNotNull(descResp.getRows(), "Should have rows");
        assertEquals(3, descResp.getRows().size(), "Should have 3 columns");
        
        System.out.println("   Columns:");
        for (Map<String, Object> col : descResp.getRows()) {
            System.out.println("     " + col.get("column_name") + " " + 
                             col.get("data_type") + 
                             (Boolean.TRUE.equals(col.get("not_null")) ? " NOT NULL" : ""));
        }
        
        System.out.println("\n✅ \\d <table> command works\n");
    }
    
    // ========================================
    // Test 7: \dti Command (List Indexes)
    // ========================================
    
    @Test
    @Order(7)
    public void testListIndexesCommand() throws Exception {
        System.out.println("\n=== TEST 7: \\dti Command (List Indexes) ===\n");
        
        String tableName = uniqueTableName("indexes_test");
        String idx1 = tableName + "_email_idx";
        String idx2 = tableName + "_name_idx";
        
        System.out.println("1. Create table with indexes");
        queryService.execute(
            "CREATE TABLE " + tableName + " (id INT PRIMARY KEY, email TEXT, name TEXT)"
        );
        queryService.execute("CREATE INDEX " + idx1 + " ON " + tableName + " (email)");
        queryService.execute("CREATE INDEX " + idx2 + " ON " + tableName + " (name)");
        
        System.out.println("2. Query pg_indexes (what \\dti uses)");
        QueryResponse indexesResp = queryService.execute(
            "SELECT schemaname, tablename, indexname " +
            "FROM pg_indexes " +
            "WHERE tablename = '" + tableName + "'"
        );
        
        assertNull(indexesResp.getError(), "pg_indexes query should succeed");
        assertNotNull(indexesResp.getRows(), "Should have rows");
        
        System.out.println("   Indexes found: " + indexesResp.getRows().size());
        
        List<String> indexNames = indexesResp.getRows().stream()
            .map(row -> (String) row.get("indexname"))
            .toList();
        
        System.out.println("   Index names: " + indexNames);
        
        // Should have at least our 2 indexes (might also have primary key index)
        assertTrue(indexNames.contains(idx1), "Should find " + idx1);
        assertTrue(indexNames.contains(idx2), "Should find " + idx2);
        
        System.out.println("\n✅ \\dti command works\n");
    }
    
    // ========================================
    // Test 8: Table Deletion Updates Catalog
    // ========================================
    
    @Test
    @Order(8)
    public void testTableDeletionUpdatesCatalog() throws Exception {
        System.out.println("\n=== TEST 8: Table Deletion Updates Catalog ===\n");
        
        String tableName = uniqueTableName("drop_test");
        
        System.out.println("1. Create table");
        queryService.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, data TEXT)");
        
        System.out.println("2. Verify table exists in catalog");
        QueryResponse beforeResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname = '" + tableName + "'"
        );
        assertFalse(beforeResp.getRows().isEmpty(), "Table should exist before DROP");
        
        System.out.println("3. Drop table");
        QueryResponse dropResp = queryService.execute("DROP TABLE " + tableName);
        assertNull(dropResp.getError(), "DROP TABLE should succeed");
        
        System.out.println("4. Verify table removed from catalog");
        QueryResponse afterResp = queryService.execute(
            "SELECT relname FROM pg_class WHERE relname = '" + tableName + "'"
        );
        
        assertNull(afterResp.getError(), "Query should succeed");
        assertTrue(afterResp.getRows().isEmpty(), "Table should NOT exist in catalog after DROP");
        
        System.out.println("\n✅ Table removed from catalog on DROP\n");
    }
    
    // ========================================
    // Test 9: Multiple Tables Don't Interfere
    // ========================================
    
    @Test
    @Order(9)
    public void testMultipleTablesInCatalog() throws Exception {
        System.out.println("\n=== TEST 9: Multiple Tables Don't Interfere ===\n");
        
        String table1 = uniqueTableName("multi_1");
        String table2 = uniqueTableName("multi_2");
        String table3 = uniqueTableName("multi_3");
        
        System.out.println("1. Create three tables");
        queryService.execute("CREATE TABLE " + table1 + " (id INT PRIMARY KEY)");
        queryService.execute("CREATE TABLE " + table2 + " (id INT PRIMARY KEY, name TEXT)");
        queryService.execute("CREATE TABLE " + table3 + " (id INT PRIMARY KEY, email TEXT, age INT)");
        
        System.out.println("2. Query catalog for all three");
        QueryResponse allResp = queryService.execute(
            "SELECT relname, relnatts FROM pg_class " +
            "WHERE relname IN ('" + table1 + "', '" + table2 + "', '" + table3 + "') " +
            "ORDER BY relname"
        );
        
        assertNull(allResp.getError(), "Query should succeed");
        assertEquals(3, allResp.getRows().size(), "Should find all 3 tables");
        
        System.out.println("   Tables found:");
        for (Map<String, Object> row : allResp.getRows()) {
            System.out.println("     " + row.get("relname") + " (" + row.get("relnatts") + " columns)");
        }
        
        // Verify column counts
        Map<String, Integer> columnCounts = Map.of(
            table1, 1,
            table2, 2,
            table3, 3
        );
        
        for (Map<String, Object> row : allResp.getRows()) {
            String name = (String) row.get("relname");
            int expected = columnCounts.get(name);
            assertEquals(expected, row.get("relnatts"), 
                "Table " + name + " should have " + expected + " columns");
        }
        
        System.out.println("\n✅ Multiple tables correctly maintained in catalog\n");
    }
}

