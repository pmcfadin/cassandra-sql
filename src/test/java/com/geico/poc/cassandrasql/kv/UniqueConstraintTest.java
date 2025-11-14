package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UNIQUE constraints in KV mode
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UniqueConstraintTest extends KvTestBase {

    @Autowired
    private QueryService queryService;

    @Test
    @Order(1)
    public void testInlineUniqueConstraint() throws Exception {
        String tableName = uniqueTableName("inline_unique_test");

        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with inline UNIQUE constraint
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, email TEXT UNIQUE)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        // Small delay to ensure schema propagation
        Thread.sleep(200);

        // Insert first row
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (1, 'test@example.com')",
            tableName
        ));
        assertNull(insert1.getError(), "First INSERT failed: " + insert1.getError());

        // Try to insert duplicate email - should fail
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (2, 'test@example.com')",
            tableName
        ));
        assertNotNull(insert2.getError(), "Expected UNIQUE constraint violation");
        assertTrue(insert2.getError().contains("UNIQUE constraint violation"), 
            "Error should mention UNIQUE constraint: " + insert2.getError());

        // Insert with different email - should succeed
        QueryResponse insert3 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (2, 'other@example.com')",
            tableName
        ));
        assertNull(insert3.getError(), "INSERT with different email failed: " + insert3.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(2)
    public void testTableLevelUniqueConstraint() throws Exception {
        String tableName = uniqueTableName("table_unique_test");

        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with table-level UNIQUE constraint
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, email TEXT, UNIQUE(email))",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        // Small delay to ensure schema propagation
        Thread.sleep(200);

        // Insert first row
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (1, 'alice@example.com')",
            tableName
        ));
        assertNull(insert1.getError(), "First INSERT failed: " + insert1.getError());

        // Try to insert duplicate email - should fail
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (2, 'alice@example.com')",
            tableName
        ));
        assertNotNull(insert2.getError(), "Expected UNIQUE constraint violation");
        assertTrue(insert2.getError().contains("UNIQUE constraint violation"), 
            "Error should mention UNIQUE constraint: " + insert2.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(3)
    public void testMultiColumnUniqueConstraint() throws Exception {
        String tableName = uniqueTableName("multi_unique_test");

        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with multi-column UNIQUE constraint
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, first_name TEXT, last_name TEXT, UNIQUE(first_name, last_name))",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        // Small delay to ensure schema propagation
        Thread.sleep(200);

        // Insert first row
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, first_name, last_name) VALUES (1, 'John', 'Doe')",
            tableName
        ));
        assertNull(insert1.getError(), "First INSERT failed: " + insert1.getError());

        // Insert with same first name but different last name - should succeed
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, first_name, last_name) VALUES (2, 'John', 'Smith')",
            tableName
        ));
        assertNull(insert2.getError(), "INSERT with different last name failed: " + insert2.getError());

        // Insert with same last name but different first name - should succeed
        QueryResponse insert3 = queryService.execute(String.format(
            "INSERT INTO %s (id, first_name, last_name) VALUES (3, 'Jane', 'Doe')",
            tableName
        ));
        assertNull(insert3.getError(), "INSERT with different first name failed: " + insert3.getError());

        // Try to insert duplicate combination - should fail
        QueryResponse insert4 = queryService.execute(String.format(
            "INSERT INTO %s (id, first_name, last_name) VALUES (4, 'John', 'Doe')",
            tableName
        ));
        assertNotNull(insert4.getError(), "Expected UNIQUE constraint violation");
        assertTrue(insert4.getError().contains("UNIQUE constraint violation"), 
            "Error should mention UNIQUE constraint: " + insert4.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(4)
    public void testUniqueConstraintWithNulls() throws Exception {
        String tableName = uniqueTableName("unique_null_test");

        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with UNIQUE constraint
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, email TEXT UNIQUE)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        // Small delay to ensure schema propagation
        Thread.sleep(200);

        // Insert row with NULL email - should succeed
        QueryResponse insert1 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (1, NULL)",
            tableName
        ));
        assertNull(insert1.getError(), "First INSERT with NULL failed: " + insert1.getError());

        // Insert another row with NULL email - should succeed (NULLs don't violate UNIQUE)
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (2, NULL)",
            tableName
        ));
        assertNull(insert2.getError(), "Second INSERT with NULL failed: " + insert2.getError());

        // Insert row with non-NULL email - should succeed
        QueryResponse insert3 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (3, 'test@example.com')",
            tableName
        ));
        assertNull(insert3.getError(), "INSERT with non-NULL failed: " + insert3.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(5)
    public void testUniqueConstraintOnUpdate() throws Exception {
        String tableName = uniqueTableName("unique_update_test");

        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with UNIQUE constraint
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, email TEXT UNIQUE)",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        // Small delay to ensure schema propagation
        Thread.sleep(200);

        // Insert two rows
        queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (1, 'alice@example.com')",
            tableName
        ));
        queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (2, 'bob@example.com')",
            tableName
        ));

        Thread.sleep(100);

        // Try to update row 2 to have the same email as row 1 - should fail
        QueryResponse update1 = queryService.execute(String.format(
            "UPDATE %s SET email = 'alice@example.com' WHERE id = 2",
            tableName
        ));
        assertNotNull(update1.getError(), "Expected UNIQUE constraint violation on UPDATE");
        assertTrue(update1.getError().contains("UNIQUE constraint violation"), 
            "Error should mention UNIQUE constraint: " + update1.getError());

        // Update row 2 to a different email - should succeed
        QueryResponse update2 = queryService.execute(String.format(
            "UPDATE %s SET email = 'charlie@example.com' WHERE id = 2",
            tableName
        ));
        assertNull(update2.getError(), "UPDATE to different email failed: " + update2.getError());

        // Update row 1 to keep the same email (no change) - should succeed
        QueryResponse update3 = queryService.execute(String.format(
            "UPDATE %s SET email = 'alice@example.com' WHERE id = 1",
            tableName
        ));
        assertNull(update3.getError(), "UPDATE to same email failed: " + update3.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }

    @Test
    @Order(6)
    public void testNamedUniqueConstraint() throws Exception {
        String tableName = uniqueTableName("named_unique_test");

        // Drop table if it exists from previous run
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        // Create table with named UNIQUE constraint
        QueryResponse createResp = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, email TEXT, CONSTRAINT uk_email UNIQUE(email))",
            tableName
        ));
        assertNull(createResp.getError(), "CREATE TABLE failed: " + createResp.getError());

        // Small delay to ensure schema propagation
        Thread.sleep(200);

        // Insert first row
        queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (1, 'test@example.com')",
            tableName
        ));

        // Try to insert duplicate - should fail with constraint name
        QueryResponse insert2 = queryService.execute(String.format(
            "INSERT INTO %s (id, email) VALUES (2, 'test@example.com')",
            tableName
        ));
        assertNotNull(insert2.getError(), "Expected UNIQUE constraint violation");
        assertTrue(insert2.getError().contains("uk_email"), 
            "Error should mention constraint name: " + insert2.getError());

        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
}

