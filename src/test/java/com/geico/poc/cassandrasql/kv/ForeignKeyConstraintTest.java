package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FOREIGN KEY constraints in KV mode
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ForeignKeyConstraintTest extends KvTestBase {

    @Autowired
    private QueryService queryService;

    @Test
    @Order(1)
    public void testBasicForeignKeyConstraint() throws Exception {
        String parentTable = uniqueTableName("parent");
        String childTable = uniqueTableName("child");

        // Drop tables if they exist
        queryService.execute("DROP TABLE IF EXISTS " + childTable);
        queryService.execute("DROP TABLE IF EXISTS " + parentTable);

        // Create parent table
        QueryResponse createParent = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            parentTable
        ));
        assertNull(createParent.getError(), "CREATE parent table failed: " + createParent.getError());

        Thread.sleep(200);

        // Create child table with foreign key
        QueryResponse createChild = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES %s(id))",
            childTable, parentTable
        ));
        assertNull(createChild.getError(), "CREATE child table failed: " + createChild.getError());

        Thread.sleep(200);

        // Insert into parent
        QueryResponse insertParent = queryService.execute(String.format(
            "INSERT INTO %s (id, name) VALUES (1, 'Parent 1')",
            parentTable
        ));
        assertNull(insertParent.getError(), "INSERT into parent failed: " + insertParent.getError());

        Thread.sleep(100);

        // Insert into child with valid parent_id - should succeed
        QueryResponse insertChild1 = queryService.execute(String.format(
            "INSERT INTO %s (id, parent_id) VALUES (1, 1)",
            childTable
        ));
        assertNull(insertChild1.getError(), "INSERT into child with valid FK failed: " + insertChild1.getError());

        // Try to insert into child with invalid parent_id - should fail
        QueryResponse insertChild2 = queryService.execute(String.format(
            "INSERT INTO %s (id, parent_id) VALUES (2, 999)",
            childTable
        ));
        assertNotNull(insertChild2.getError(), "Expected FOREIGN KEY constraint violation");
        assertTrue(insertChild2.getError().contains("FOREIGN KEY constraint violation"), 
            "Error should mention FOREIGN KEY constraint: " + insertChild2.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + childTable);
        queryService.execute("DROP TABLE " + parentTable);
    }

    @Test
    @Order(2)
    public void testForeignKeyWithNull() throws Exception {
        String parentTable = uniqueTableName("parent");
        String childTable = uniqueTableName("child");

        // Drop tables if they exist
        queryService.execute("DROP TABLE IF EXISTS " + childTable);
        queryService.execute("DROP TABLE IF EXISTS " + parentTable);

        // Create parent table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            parentTable
        ));

        Thread.sleep(200);

        // Create child table with foreign key
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES %s(id))",
            childTable, parentTable
        ));

        Thread.sleep(200);

        // Insert into child with NULL parent_id - should succeed (FK allows NULL)
        QueryResponse insertChild = queryService.execute(String.format(
            "INSERT INTO %s (id, parent_id) VALUES (1, NULL)",
            childTable
        ));
        assertNull(insertChild.getError(), "INSERT with NULL FK failed: " + insertChild.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + childTable);
        queryService.execute("DROP TABLE " + parentTable);
    }

    @Test
    @Order(3)
    public void testForeignKeyOnDelete() throws Exception {
        String parentTable = uniqueTableName("parent");
        String childTable = uniqueTableName("child");

        // Drop tables if they exist
        queryService.execute("DROP TABLE IF EXISTS " + childTable);
        queryService.execute("DROP TABLE IF EXISTS " + parentTable);

        // Create parent table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            parentTable
        ));

        Thread.sleep(200);

        // Create child table with foreign key (default ON DELETE NO ACTION)
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES %s(id))",
            childTable, parentTable
        ));

        Thread.sleep(200);

        // Insert into parent
        queryService.execute(String.format(
            "INSERT INTO %s (id, name) VALUES (1, 'Parent 1')",
            parentTable
        ));

        // Insert into child
        queryService.execute(String.format(
            "INSERT INTO %s (id, parent_id) VALUES (1, 1)",
            childTable
        ));

        Thread.sleep(100);

        // Try to delete from parent - should fail (child references it)
        QueryResponse deleteParent = queryService.execute(String.format(
            "DELETE FROM %s WHERE id = 1",
            parentTable
        ));
        assertNotNull(deleteParent.getError(), "Expected FOREIGN KEY constraint violation on DELETE");
        assertTrue(deleteParent.getError().contains("FOREIGN KEY constraint violation") || 
                   deleteParent.getError().contains("referenced by"), 
            "Error should mention FOREIGN KEY constraint: " + deleteParent.getError());

        // Delete child first - should succeed
        QueryResponse deleteChild = queryService.execute(String.format(
            "DELETE FROM %s WHERE id = 1",
            childTable
        ));
        assertNull(deleteChild.getError(), "DELETE child failed: " + deleteChild.getError());

        Thread.sleep(100);

        // Now delete parent - should succeed
        QueryResponse deleteParent2 = queryService.execute(String.format(
            "DELETE FROM %s WHERE id = 1",
            parentTable
        ));
        assertNull(deleteParent2.getError(), "DELETE parent after child failed: " + deleteParent2.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + childTable);
        queryService.execute("DROP TABLE " + parentTable);
    }

    @Test
    @Order(4)
    public void testMultiColumnForeignKey() throws Exception {
        String parentTable = uniqueTableName("parent");
        String childTable = uniqueTableName("child");

        // Drop tables if they exist
        queryService.execute("DROP TABLE IF EXISTS " + childTable);
        queryService.execute("DROP TABLE IF EXISTS " + parentTable);

        // Create parent table with composite key
        queryService.execute(String.format(
            "CREATE TABLE %s (dept_id INT, emp_id INT, name TEXT, PRIMARY KEY (dept_id, emp_id))",
            parentTable
        ));

        Thread.sleep(200);

        // Create child table with multi-column foreign key
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, dept_id INT, emp_id INT, " +
            "FOREIGN KEY (dept_id, emp_id) REFERENCES %s(dept_id, emp_id))",
            childTable, parentTable
        ));

        Thread.sleep(200);

        // Insert into parent
        queryService.execute(String.format(
            "INSERT INTO %s (dept_id, emp_id, name) VALUES (1, 100, 'Alice')",
            parentTable
        ));

        Thread.sleep(100);

        // Insert into child with valid FK - should succeed
        QueryResponse insertChild1 = queryService.execute(String.format(
            "INSERT INTO %s (id, dept_id, emp_id) VALUES (1, 1, 100)",
            childTable
        ));
        assertNull(insertChild1.getError(), "INSERT with valid multi-column FK failed: " + insertChild1.getError());

        // Try to insert with invalid FK - should fail
        QueryResponse insertChild2 = queryService.execute(String.format(
            "INSERT INTO %s (id, dept_id, emp_id) VALUES (2, 1, 999)",
            childTable
        ));
        assertNotNull(insertChild2.getError(), "Expected FOREIGN KEY constraint violation");
        assertTrue(insertChild2.getError().contains("FOREIGN KEY constraint violation"), 
            "Error should mention FOREIGN KEY constraint: " + insertChild2.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + childTable);
        queryService.execute("DROP TABLE " + parentTable);
    }

    @Test
    @Order(5)
    public void testForeignKeyOnUpdate() throws Exception {
        String parentTable = uniqueTableName("parent");
        String childTable = uniqueTableName("child");

        // Drop tables if they exist
        queryService.execute("DROP TABLE IF EXISTS " + childTable);
        queryService.execute("DROP TABLE IF EXISTS " + parentTable);

        // Create parent table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            parentTable
        ));

        Thread.sleep(200);

        // Create child table with foreign key
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES %s(id))",
            childTable, parentTable
        ));

        Thread.sleep(200);

        // Insert into parent
        queryService.execute(String.format(
            "INSERT INTO %s (id, name) VALUES (1, 'Parent 1')",
            parentTable
        ));

        Thread.sleep(200);  // Wait for parent insert to be visible

        // Insert into child
        queryService.execute(String.format(
            "INSERT INTO %s (id, parent_id) VALUES (1, 1)",
            childTable
        ));

        Thread.sleep(200);  // Wait for child insert to be visible

        // Try to update child to reference non-existent parent - should fail
        QueryResponse updateChild = queryService.execute(String.format(
            "UPDATE %s SET parent_id = 999 WHERE id = 1",
            childTable
        ));
        assertNotNull(updateChild.getError(), "Expected FOREIGN KEY constraint violation on UPDATE");
        assertTrue(updateChild.getError().contains("FOREIGN KEY constraint violation"), 
            "Error should mention FOREIGN KEY constraint: " + updateChild.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + childTable);
        queryService.execute("DROP TABLE " + parentTable);
    }

    @Test
    @Order(6)
    public void testNamedForeignKeyConstraint() throws Exception {
        String parentTable = uniqueTableName("parent");
        String childTable = uniqueTableName("child");

        // Drop tables if they exist
        queryService.execute("DROP TABLE IF EXISTS " + childTable);
        queryService.execute("DROP TABLE IF EXISTS " + parentTable);

        // Create parent table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            parentTable
        ));

        Thread.sleep(200);

        // Create child table with named foreign key
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, parent_id INT, " +
            "CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES %s(id))",
            childTable, parentTable
        ));

        Thread.sleep(200);

        // Try to insert with invalid FK - should fail with constraint name
        QueryResponse insertChild = queryService.execute(String.format(
            "INSERT INTO %s (id, parent_id) VALUES (1, 999)",
            childTable
        ));
        assertNotNull(insertChild.getError(), "Expected FOREIGN KEY constraint violation");
        assertTrue(insertChild.getError().contains("fk_parent"), 
            "Error should mention constraint name: " + insertChild.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + childTable);
        queryService.execute("DROP TABLE " + parentTable);
    }

    @Test
    @Order(7)
    public void testForeignKeyReferencesNonExistentTable() throws Exception {
        String childTable = uniqueTableName("child");

        // Drop table if it exists
        queryService.execute("DROP TABLE IF EXISTS " + childTable);

        // Try to create child table referencing non-existent parent - should fail
        QueryResponse createChild = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, parent_id INT, " +
            "FOREIGN KEY (parent_id) REFERENCES nonexistent_table(id))",
            childTable
        ));
        assertNotNull(createChild.getError(), "Expected error for non-existent referenced table");
        assertTrue(createChild.getError().contains("does not exist"), 
            "Error should mention table doesn't exist: " + createChild.getError());
    }
}

