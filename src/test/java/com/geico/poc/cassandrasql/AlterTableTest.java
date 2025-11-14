package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ALTER TABLE command support.
 * 
 * Tests:
 * - ALTER TABLE ... ADD PRIMARY KEY
 * - ALTER TABLE ... ADD FOREIGN KEY
 * - ALTER TABLE ... ADD CONSTRAINT
 */
@SpringBootTest
@ActiveProfiles("test")
public class AlterTableTest {

    @Autowired
    private QueryService queryService;

    @Autowired
    private SchemaManager schemaManager;

    @BeforeEach
    public void setUp() throws Exception {
        // Clean up any existing test tables
        try {
            queryService.execute("DROP TABLE IF EXISTS alter_test");
            queryService.execute("DROP TABLE IF EXISTS alter_test_ref");
            queryService.execute("DROP TABLE IF EXISTS pgbench_branches");
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    public void testAddPrimaryKey() throws Exception {
        // Create table without explicit primary key (gets hidden rowid)
        QueryResponse createResp = queryService.execute(
            "CREATE TABLE alter_test (id INT, name VARCHAR)");
        assertNull(createResp.getError(), "CREATE TABLE should succeed: " + createResp.getError());

        // Add primary key constraint
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE alter_test ADD PRIMARY KEY (id)");
        assertNull(alterResp.getError(), "ALTER TABLE ADD PRIMARY KEY should succeed: " + alterResp.getError());

        // Verify index was created
        TableMetadata table = schemaManager.getTable("alter_test");
        assertNotNull(table, "Table should exist");
        
        // Should have created a UNIQUE index
        boolean hasIndex = table.getIndexes().stream()
            .anyMatch(idx -> idx.getName().contains("pk_alter_test_id"));
        assertTrue(hasIndex, "Should have created pk_alter_test_id index");
    }

    @Test
    public void testAddPrimaryKeyMultiColumn() throws Exception {
        // Create table
        queryService.execute("CREATE TABLE alter_test (id INT, version INT, name VARCHAR)");

        // Add composite primary key
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE alter_test ADD PRIMARY KEY (id, version)");
        assertNull(alterResp.getError(), "ALTER TABLE ADD PRIMARY KEY should succeed: " + alterResp.getError());

        // Verify index was created with both columns
        TableMetadata table = schemaManager.getTable("alter_test");
        boolean hasIndex = table.getIndexes().stream()
            .anyMatch(idx -> idx.getName().contains("pk_alter_test_id_version"));
        assertTrue(hasIndex, "Should have created composite primary key index");
    }

    @Test
    public void testAddForeignKey() throws Exception {
        // Create referenced table
        queryService.execute("CREATE TABLE alter_test_ref (id INT PRIMARY KEY, name VARCHAR)");
        
        // Create referencing table
        queryService.execute("CREATE TABLE alter_test (id INT, ref_id INT, name VARCHAR)");

        // Add foreign key constraint (treated as no-op in KV mode)
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE alter_test ADD FOREIGN KEY (ref_id) REFERENCES alter_test_ref(id)");
        assertNull(alterResp.getError(), "ALTER TABLE ADD FOREIGN KEY should succeed: " + alterResp.getError());
    }

    @Test
    public void testAddConstraintWithName() throws Exception {
        // Create table
        queryService.execute("CREATE TABLE alter_test (id INT, ref_id INT)");

        // Add named foreign key constraint
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE alter_test ADD CONSTRAINT fk_ref FOREIGN KEY (ref_id) REFERENCES alter_test_ref(id)");
        assertNull(alterResp.getError(), "ALTER TABLE ADD CONSTRAINT FOREIGN KEY should succeed: " + alterResp.getError());
    }

    @Test
    public void testAddPrimaryKeyNonExistentColumn() throws Exception {
        // Create table
        queryService.execute("CREATE TABLE alter_test (id INT, name VARCHAR)");

        // Try to add PK on non-existent column
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE alter_test ADD PRIMARY KEY (nonexistent)");
        assertNotNull(alterResp.getError(), "Should fail for non-existent column");
        assertTrue(alterResp.getError().contains("does not exist"), 
                   "Error should mention column doesn't exist: " + alterResp.getError());
    }

    @Test
    public void testAddPrimaryKeyNonExistentTable() throws Exception {
        // Try to alter non-existent table
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE nonexistent ADD PRIMARY KEY (id)");
        assertNotNull(alterResp.getError(), "Should fail for non-existent table");
        assertTrue(alterResp.getError().contains("does not exist"), 
                   "Error should mention table doesn't exist: " + alterResp.getError());
    }

    @Test
    public void testPgbenchStyleAlterTable() throws Exception {
        // Simulate pgbench's ALTER TABLE command
        queryService.execute("CREATE TABLE pgbench_branches (bid INT, bbalance INT, filler CHAR(88))");
        
        QueryResponse alterResp = queryService.execute(
            "ALTER TABLE pgbench_branches ADD PRIMARY KEY (bid)");
        assertNull(alterResp.getError(), "pgbench-style ALTER TABLE should succeed: " + alterResp.getError());
        
        // Verify the index exists
        TableMetadata table = schemaManager.getTable("pgbench_branches");
        assertNotNull(table, "Table should exist");
        boolean hasIndex = table.getIndexes().stream()
            .anyMatch(idx -> idx.getName().contains("pk_pgbench_branches_bid"));
        assertTrue(hasIndex, "Should have created primary key index for pgbench_branches");
    }
}


