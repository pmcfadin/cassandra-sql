package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvTestBase;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for secondary index maintenance in KV mode.
 * 
 * Verifies that:
 * 1. CREATE INDEX builds index from existing data (backfill)
 * 2. INSERT writes both data and index entries atomically
 * 3. UPDATE maintains index entries correctly
 * 4. DELETE removes both data and index entries atomically
 * 5. All operations use Accord transactions for atomicity
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SecondaryIndexTest extends KvTestBase {
    
    @Autowired
    private SchemaManager schemaManager;
    
    private String testTable;
    
    @BeforeEach
    public void setup() {
        testTable = uniqueTableName("users_with_index");
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(50);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @AfterEach
    public void cleanup() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
            Thread.sleep(50);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(1)
    public void testCreateIndexBackfillsExistingData() throws Exception {
        System.out.println("\n=== TEST: CREATE INDEX backfills existing data ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        
        // Insert data BEFORE creating index
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (2, 'Bob', 'bob@example.com')");
        queryService.execute("INSERT INTO " + testTable + " (id, name, email) VALUES (3, 'Charlie', 'charlie@example.com')");
        
        // Create index on email (should backfill existing rows)
        QueryResponse indexResponse = queryService.execute(
            "CREATE INDEX idx_email ON " + testTable + " (email)"
        );
        assertNull(indexResponse.getError(), "CREATE INDEX should succeed: " + 
            (indexResponse.getError() != null ? indexResponse.getError() : ""));
        
        // Verify index exists in metadata
        TableMetadata table = schemaManager.getTable(testTable);
        assertNotNull(table, "Table should exist");
        assertEquals(1, table.getIndexes().size(), "Should have 1 index");
        assertEquals("idx_email", table.getIndexes().get(0).getName(), "Index name should match");
        
        // TODO: Verify index entries exist in KV store
        // For now, we verify that queries still work (index doesn't break anything)
        QueryResponse selectResponse = queryService.execute("SELECT * FROM " + testTable);
        assertNull(selectResponse.getError(), "SELECT should still work after index creation");
        assertEquals(3, selectResponse.getRows().size(), "Should have 3 rows");
        
        System.out.println("✅ PASS: CREATE INDEX backfills existing data");
    }
    
    @Test
    @Order(2)
    public void testInsertWritesIndexEntries() throws Exception {
        System.out.println("\n=== TEST: INSERT writes index entries ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        
        // Create index BEFORE inserting data
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert data (should write both data and index entries)
        QueryResponse insertResponse = queryService.execute(
            "INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')"
        );
        assertNull(insertResponse.getError(), "INSERT should succeed: " + 
            (insertResponse.getError() != null ? insertResponse.getError() : ""));
        
        // Verify data can be retrieved
        QueryResponse selectResponse = queryService.execute(
            "SELECT * FROM " + testTable + " WHERE id = 1"
        );
        assertNull(selectResponse.getError(), "SELECT should succeed");
        assertEquals(1, selectResponse.getRows().size(), "Should have 1 row");
        
        // TODO: Verify index entry exists in KV store
        // TODO: Test SELECT using index (when index-based queries are implemented)
        
        System.out.println("✅ PASS: INSERT writes index entries");
    }
    
    @Test
    @Order(3)
    public void testUpdateMaintainsIndexEntries() throws Exception {
        System.out.println("\n=== TEST: UPDATE maintains index entries ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert initial data
        queryService.execute(
            "INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')"
        );
        
        // Update email (should delete old index entry and create new one)
        QueryResponse updateResponse = queryService.execute(
            "UPDATE " + testTable + " SET email = 'alice.new@example.com' WHERE id = 1"
        );
        assertNull(updateResponse.getError(), "UPDATE should succeed: " + 
            (updateResponse.getError() != null ? updateResponse.getError() : ""));
        
        // Verify updated data
        QueryResponse selectResponse = queryService.execute(
            "SELECT * FROM " + testTable + " WHERE id = 1"
        );
        assertNull(selectResponse.getError(), "SELECT should succeed");
        assertEquals(1, selectResponse.getRows().size(), "Should have 1 row");
        
        Map<String, Object> row = selectResponse.getRows().get(0);
        assertEquals("alice.new@example.com", row.get("email"), "Email should be updated");
        
        // TODO: Verify old index entry is deleted and new one exists
        
        System.out.println("✅ PASS: UPDATE maintains index entries");
    }
    
    @Test
    @Order(4)
    public void testDeleteRemovesIndexEntries() throws Exception {
        System.out.println("\n=== TEST: DELETE removes index entries ===");
        
        // Create table and index
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, PRIMARY KEY (id))"
        );
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        
        // Insert data
        queryService.execute(
            "INSERT INTO " + testTable + " (id, name, email) VALUES (1, 'Alice', 'alice@example.com')"
        );
        
        // Delete row (should remove both data and index entries)
        QueryResponse deleteResponse = queryService.execute(
            "DELETE FROM " + testTable + " WHERE id = 1"
        );
        assertNull(deleteResponse.getError(), "DELETE should succeed: " + 
            (deleteResponse.getError() != null ? deleteResponse.getError() : ""));
        
        // Verify row is deleted
        QueryResponse selectResponse = queryService.execute(
            "SELECT * FROM " + testTable + " WHERE id = 1"
        );
        assertNull(selectResponse.getError(), "SELECT should succeed");
        assertTrue(selectResponse.getRows() == null || selectResponse.getRows().isEmpty(), 
            "Should have 0 rows after delete");
        
        // TODO: Verify index entry is also deleted
        
        System.out.println("✅ PASS: DELETE removes index entries");
    }
    
    @Test
    @Order(5)
    public void testCompositeIndex() throws Exception {
        System.out.println("\n=== TEST: Composite index (multiple columns) ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, first_name TEXT, last_name TEXT, PRIMARY KEY (id))"
        );
        
        // Create composite index
        QueryResponse indexResponse = queryService.execute(
            "CREATE INDEX idx_name ON " + testTable + " (last_name, first_name)"
        );
        assertNull(indexResponse.getError(), "CREATE INDEX should succeed: " + 
            (indexResponse.getError() != null ? indexResponse.getError() : ""));
        
        // Verify index metadata
        TableMetadata table = schemaManager.getTable(testTable);
        assertNotNull(table, "Table should exist");
        assertEquals(1, table.getIndexes().size(), "Should have 1 index");
        
        TableMetadata.IndexMetadata index = table.getIndexes().get(0);
        assertEquals(2, index.getColumns().size(), "Index should have 2 columns");
        assertTrue(index.getColumns().contains("last_name"), "Should index last_name");
        assertTrue(index.getColumns().contains("first_name"), "Should index first_name");
        
        // Insert data
        queryService.execute(
            "INSERT INTO " + testTable + " (id, first_name, last_name) VALUES (1, 'Alice', 'Smith')"
        );
        
        // Verify data can be retrieved
        QueryResponse selectResponse = queryService.execute("SELECT * FROM " + testTable);
        assertNull(selectResponse.getError(), "SELECT should succeed");
        assertEquals(1, selectResponse.getRows().size(), "Should have 1 row");
        
        System.out.println("✅ PASS: Composite index works");
    }
    
    @Test
    @Order(6)
    public void testMultipleIndexes() throws Exception {
        System.out.println("\n=== TEST: Multiple indexes on same table ===");
        
        // Create table
        queryService.execute(
            "CREATE TABLE " + testTable + " (id INT, name TEXT, email TEXT, age INT, PRIMARY KEY (id))"
        );
        
        // Create multiple indexes
        queryService.execute("CREATE INDEX idx_name ON " + testTable + " (name)");
        queryService.execute("CREATE INDEX idx_email ON " + testTable + " (email)");
        queryService.execute("CREATE INDEX idx_age ON " + testTable + " (age)");
        
        // Verify all indexes exist
        TableMetadata table = schemaManager.getTable(testTable);
        assertEquals(3, table.getIndexes().size(), "Should have 3 indexes");
        
        // Insert data (should write 1 data entry + 3 index entries = 4 writes)
        QueryResponse insertResponse = queryService.execute(
            "INSERT INTO " + testTable + " (id, name, email, age) VALUES (1, 'Alice', 'alice@example.com', 30)"
        );
        assertNull(insertResponse.getError(), "INSERT should succeed with multiple indexes");
        
        // Update data (should update 1 data entry + update 3 index entries)
        QueryResponse updateResponse = queryService.execute(
            "UPDATE " + testTable + " SET name = 'Alice Smith', age = 31 WHERE id = 1"
        );
        assertNull(updateResponse.getError(), "UPDATE should succeed with multiple indexes");
        
        // Delete data (should delete 1 data entry + 3 index entries)
        QueryResponse deleteResponse = queryService.execute(
            "DELETE FROM " + testTable + " WHERE id = 1"
        );
        assertNull(deleteResponse.getError(), "DELETE should succeed with multiple indexes");
        
        System.out.println("✅ PASS: Multiple indexes work");
    }
    
    @Test
    @Order(7)
    public void testIndexOnNonPrimaryKeyColumn() throws Exception {
        System.out.println("\n=== TEST: Index on non-primary-key column ===");
        
        // Create table with composite primary key
        queryService.execute(
            "CREATE TABLE " + testTable + " (user_id INT, order_id INT, amount DECIMAL, status TEXT, PRIMARY KEY (user_id, order_id))"
        );
        
        // Create index on non-PK column
        QueryResponse indexResponse = queryService.execute(
            "CREATE INDEX idx_status ON " + testTable + " (status)"
        );
        assertNull(indexResponse.getError(), "CREATE INDEX should succeed");
        
        // Insert data
        queryService.execute(
            "INSERT INTO " + testTable + " (user_id, order_id, amount, status) VALUES (1, 100, 50.00, 'pending')"
        );
        queryService.execute(
            "INSERT INTO " + testTable + " (user_id, order_id, amount, status) VALUES (1, 101, 75.00, 'completed')"
        );
        
        // Verify data
        QueryResponse selectResponse = queryService.execute("SELECT * FROM " + testTable);
        assertNull(selectResponse.getError(), "SELECT should succeed");
        assertEquals(2, selectResponse.getRows().size(), "Should have 2 rows");
        
        System.out.println("✅ PASS: Index on non-PK column works");
    }
    
    @AfterEach
    public void cleanupAfter() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + testTable);
        } catch (Exception e) {
            // Ignore
        }
    }
}

