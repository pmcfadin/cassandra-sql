package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ALTER TABLE operations (ADD COLUMN, DROP COLUMN, ADD PRIMARY KEY)
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AlterTableTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    private static final String TEST_TABLE = "alter_test_table";
    
    @BeforeEach
    public void setUp() throws Exception {
        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        
        // Create a simple test table
        String createSql = "CREATE TABLE " + TEST_TABLE + " (" +
                          "id INT PRIMARY KEY, " +
                          "name TEXT, " +
                          "age INT)";
        QueryResponse response = queryService.execute(createSql);
        assertNull(response.getError(), "Failed to create test table: " + response.getError());
        
        // Small delay to ensure schema propagation
        Thread.sleep(200);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }
    
    @Test
    @Order(1)
    public void testAddColumn() throws Exception {
        // Add a new column
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " ADD COLUMN email TEXT"
        );
        assertNull(response.getError(), "ADD COLUMN failed: " + response.getError());
        
        // Verify column was added
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        assertNotNull(table);
        assertNotNull(table.getColumn("email"), "Column 'email' should exist");
        assertEquals("TEXT", table.getColumn("email").getType());
        assertTrue(table.getColumn("email").isNullable());
    }
    
    @Test
    @Order(2)
    public void testAddColumnWithoutColumnKeyword() throws Exception {
        // ADD without COLUMN keyword should also work
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " ADD phone TEXT"
        );
        assertNull(response.getError(), "ADD without COLUMN keyword failed: " + response.getError());
        
        // Verify column was added
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        assertNotNull(table.getColumn("phone"));
    }
    
    @Test
    @Order(99)
    public void testAddColumnNotNull() throws Exception {
        // Add a NOT NULL column
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " ADD COLUMN status TEXT NOT NULL"
        );
        assertNull(response.getError(), "ADD COLUMN NOT NULL failed: " + response.getError());
        
        // Verify column was added with NOT NULL constraint
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        assertNotNull(table.getColumn("status"));
        assertFalse(table.getColumn("status").isNullable(), "Column should be NOT NULL");
    }
    
    @Test
    @Order(99)
    public void testAddColumnAlreadyExists() throws Exception {
        // Try to add a column that already exists
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " ADD COLUMN name TEXT"
        );
        assertNotNull(response.getError(), "Should fail when adding existing column");
        assertTrue(response.getError().contains("already exists"));
    }
    
    @Test
    @Order(99)
    public void testDropColumn() throws Exception {
        // Drop an existing column
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " DROP COLUMN age"
        );
        assertNull(response.getError(), "DROP COLUMN failed: " + response.getError());
        
        // Verify column was dropped
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        assertNull(table.getColumn("age"), "Column 'age' should not exist");
    }
    
    @Test
    @Order(99)
    public void testDropColumnWithoutColumnKeyword() throws Exception {
        // DROP without COLUMN keyword should also work
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " DROP age"
        );
        assertNull(response.getError(), "DROP without COLUMN keyword failed: " + response.getError());
        
        // Verify column was dropped
        TableMetadata table = schemaManager.getTable(TEST_TABLE);
        assertNull(table.getColumn("age"));
    }
    
    @Test
    @Order(99)
    public void testDropColumnDoesNotExist() throws Exception {
        // Try to drop a column that doesn't exist
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " DROP COLUMN nonexistent"
        );
        assertNotNull(response.getError(), "Should fail when dropping non-existent column");
        assertTrue(response.getError().contains("does not exist"));
    }
    
    @Test
    @Order(99)
    public void testDropPrimaryKeyColumn() throws Exception {
        // Try to drop a primary key column (should fail)
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " DROP COLUMN id"
        );
        assertNotNull(response.getError(), "Should fail when dropping primary key column");
        assertTrue(response.getError().contains("primary key"));
    }
    
    @Test
    @Order(99)
    public void testAlterColumnType() throws Exception {
        // Try to change column type (should fail)
        QueryResponse response = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " ALTER COLUMN name TYPE VARCHAR(100)"
        );
        assertNotNull(response.getError(), "Should fail when changing column type");
        assertTrue(response.getError().contains("not supported") || response.getError().contains("not allowed"));
    }
    
    @Test
    @Order(99)
    public void testAddAndDropColumn() throws Exception {
        // Add a column
        QueryResponse addResponse = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " ADD COLUMN temp_col INT"
        );
        assertNull(addResponse.getError());
        
        // Insert data with the new column
        QueryResponse insertResponse = queryService.execute(
            "INSERT INTO " + TEST_TABLE + " (id, name, age, temp_col) VALUES (1, 'Alice', 30, 100)"
        );
        assertNull(insertResponse.getError());
        
        // Drop the column
        QueryResponse dropResponse = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " DROP COLUMN temp_col"
        );
        assertNull(dropResponse.getError());
        
        // Verify data is still accessible (without the dropped column)
        QueryResponse selectResponse = queryService.execute(
            "SELECT * FROM " + TEST_TABLE + " WHERE id = 1"
        );
        assertNull(selectResponse.getError());
        assertEquals(1, selectResponse.getRows().size());
        assertEquals("Alice", selectResponse.getRows().get(0).get("name"));
    }
    
    @Test
    @Order(99)
    public void testQueryNonExistentColumn() throws Exception {
        // Try to query a column that doesn't exist
        QueryResponse response = queryService.execute(
            "SELECT nonexistent FROM " + TEST_TABLE
        );
        assertNotNull(response.getError(), "Should fail when querying non-existent column");
        assertTrue(response.getError().contains("does not exist"));
    }
    
    @Test
    @Order(99)
    public void testQueryDroppedColumn() throws Exception {
        // Drop a column
        QueryResponse dropResponse = queryService.execute(
            "ALTER TABLE " + TEST_TABLE + " DROP COLUMN age"
        );
        assertNull(dropResponse.getError());
        
        // Try to query the dropped column
        QueryResponse selectResponse = queryService.execute(
            "SELECT age FROM " + TEST_TABLE
        );
        assertNotNull(selectResponse.getError(), "Should fail when querying dropped column");
        assertTrue(selectResponse.getError().contains("does not exist"));
    }
    
    @Test
    @Order(99)
    public void testAddPrimaryKeyCompound() throws Exception {
        // Create a table without explicit primary key
        String tableName = "pk_test_table";
        try {
            queryService.execute("DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore
        }
        
        String createSql = "CREATE TABLE " + tableName + " (" +
                          "col1 INT, " +
                          "col2 INT, " +
                          "col3 TEXT)";
        QueryResponse createResponse = queryService.execute(createSql);
        assertNull(createResponse.getError());
        
        // Add compound primary key
        QueryResponse alterResponse = queryService.execute(
            "ALTER TABLE " + tableName + " ADD PRIMARY KEY (col1, col2)"
        );
        assertNull(alterResponse.getError(), "ADD PRIMARY KEY failed: " + alterResponse.getError());
        
        // Verify a unique index was created (in KV mode, PK is implemented as unique index)
        TableMetadata table = schemaManager.getTable(tableName);
        assertNotNull(table);
        boolean foundIndex = table.getIndexes().stream()
            .anyMatch(idx -> idx.isUnique() && 
                            idx.getColumns().contains("col1") && 
                            idx.getColumns().contains("col2"));
        assertTrue(foundIndex, "Should have created a unique index for the primary key");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
}

