package com.geico.poc.cassandrasql.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for integration tests.
 * Provides common setup, teardown, and utility methods.
 * 
 * Tests run against a real Cassandra instance (must be running on localhost:9042).
 * Each test class should clean up its own tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class IntegrationTestBase {
    
    @Autowired
    protected CqlSession session;
    
    @Autowired
    protected QueryService queryService;
    
    protected static final String KEYSPACE = "cassandra_sql";
    
    // Static reference to session for use in @BeforeAll/@AfterAll methods
    protected static CqlSession staticSession;

    /**
     * Generate a unique table name for this test
     */
    public String uniqueTableName(String prefix) {
        String tableName = prefix + "_" + System.nanoTime();
        return tableName;
    }

    /**
     * Execute SQL query and return response (handles exceptions)
     */
    protected QueryResponse executeQuery(String sql) {
        try {
            return queryService.execute(sql);
        } catch (Exception e) {
            // Print full stack trace for debugging
            System.err.println("Exception in executeQuery:");
            e.printStackTrace();
            // Return error response
            QueryResponse response = new QueryResponse();
            response.setError(e.getMessage());
            return response;
        }
    }
    
    /**
     * Setup method run before all tests in the class
     */
    @BeforeAll
    public static void beforeAll() {
        System.out.println("=".repeat(60));
        System.out.println("Starting Integration Tests");
        System.out.println("=".repeat(60));
    }
    
    /**
     * Teardown method run after all tests in the class
     */
    @AfterAll
    public static void afterAll() {
        System.out.println("=".repeat(60));
        System.out.println("Integration Tests Complete");
        System.out.println("=".repeat(60));
    }
    
    /**
     * Setup method run before each test
     */
    @BeforeEach
    public void setUp() {
        // Initialize static session reference for use in @BeforeAll/@AfterAll
        if (staticSession == null && session != null) {
            staticSession = session;
        }
        // Subclasses can override
    }
    
    /**
     * Teardown method run after each test
     */
    @AfterEach
    public void tearDown() {
        // Subclasses can override
    }
    
    // ========== Utility Methods ==========
    
    /**
     * Execute CQL directly against Cassandra
     */
    protected ResultSet executeCql(String cql) {
        System.out.println("[CQL] " + cql);
        return session.execute(cql);
    }
    
    /**
     * Drop table if exists
     */
    protected void dropTableIfExists(String tableName) {
        try {
            executeCql("DROP TABLE IF EXISTS " + KEYSPACE + "." + tableName);
            System.out.println("Dropped table: " + tableName);
        } catch (Exception e) {
            System.err.println("Error dropping table " + tableName + ": " + e.getMessage());
        }
    }
    
    /**
     * Check if table exists
     */
    protected boolean tableExists(String tableName) {
        try {
            ResultSet rs = executeCql("SELECT * FROM " + KEYSPACE + "." + tableName + " LIMIT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Count rows in a table
     */
    protected long countRows(String tableName) {
        ResultSet rs = executeCql("SELECT COUNT(*) FROM " + KEYSPACE + "." + tableName);
        Row row = rs.one();
        return row != null ? row.getLong(0) : 0;
    }
    
    /**
     * Get all rows from a table
     */
    protected List<Row> getAllRows(String tableName) {
        ResultSet rs = executeCql("SELECT * FROM " + KEYSPACE + "." + tableName);
        List<Row> rows = new ArrayList<>();
        rs.forEach(rows::add);
        return rows;
    }
    
    /**
     * Assert that a QueryResponse is successful
     */
    protected void assertSuccess(QueryResponse response) {
        assertNotNull(response, "Response should not be null");
        assertNull(response.getError(), "Response should not have an error: " + response.getError());
    }
    
    /**
     * Assert that a QueryResponse has an error
     */
    protected void assertError(QueryResponse response) {
        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getError(), "Response should have an error");
    }
    
    /**
     * Assert that a QueryResponse has an error containing a specific message
     */
    protected void assertErrorContains(QueryResponse response, String expectedMessage) {
        assertError(response);
        assertTrue(response.getError().contains(expectedMessage),
            "Error message should contain '" + expectedMessage + "' but was: " + response.getError());
    }
    
    /**
     * Assert row count
     */
    protected void assertRowCount(QueryResponse response, int expectedCount) {
        assertSuccess(response);
        assertEquals(expectedCount, response.getRowCount(),
            "Expected " + expectedCount + " rows but got " + response.getRowCount());
    }
    
    /**
     * Assert that response has at least N rows
     */
    protected void assertMinRowCount(QueryResponse response, int minCount) {
        assertSuccess(response);
        assertTrue(response.getRowCount() >= minCount,
            "Expected at least " + minCount + " rows but got " + response.getRowCount());
    }
    
    /**
     * Assert that response contains a column
     */
    protected void assertHasColumn(QueryResponse response, String columnName) {
        assertSuccess(response);
        assertTrue(response.getColumns().contains(columnName),
            "Response should contain column '" + columnName + "'. Available columns: " + response.getColumns());
    }
    
    /**
     * Assert that a row contains a specific value
     */
    protected void assertRowContains(QueryResponse response, int rowIndex, String columnName, Object expectedValue) {
        assertSuccess(response);
        assertTrue(rowIndex < response.getRows().size(),
            "Row index " + rowIndex + " out of bounds. Total rows: " + response.getRows().size());
        
        Object actualValue = response.getRows().get(rowIndex).get(columnName);
        assertEquals(expectedValue, actualValue,
            "Row " + rowIndex + ", column '" + columnName + "' should be " + expectedValue + " but was " + actualValue);
    }
    
    /**
     * Print response for debugging
     */
    protected void printResponse(QueryResponse response) {
        System.out.println("Response:");
        System.out.println("  Columns: " + response.getColumns());
        System.out.println("  Row Count: " + response.getRowCount());
        if (response.getError() != null) {
            System.out.println("  Error: " + response.getError());
        }
        if (response.getRows() != null && !response.getRows().isEmpty()) {
            System.out.println("  Rows:");
            response.getRows().forEach(row -> System.out.println("    " + row));
        }
    }
    
    /**
     * Wait for a condition to be true (with timeout)
     */
    protected void waitFor(int timeoutMs, int intervalMs, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(intervalMs);
        }
        fail("Condition not met within " + timeoutMs + "ms");
    }
    
    // ========== Static Utility Methods (for @BeforeAll/@AfterAll) ==========
    
    /**
     * Execute CQL directly against Cassandra (static version)
     */
    protected static ResultSet executeCqlStatic(String cql) {
        if (staticSession == null) {
            throw new IllegalStateException("Static session not initialized. Make sure tests run in order.");
        }
        System.out.println("[CQL] " + cql);
        return staticSession.execute(cql);
    }
    
    /**
     * Drop table if exists (static version)
     */
    protected static void dropTableIfExistsStatic(String tableName) {
        try {
            if (staticSession != null) {
                executeCqlStatic("DROP TABLE IF EXISTS " + KEYSPACE + "." + tableName);
                System.out.println("Dropped table: " + tableName);
            }
        } catch (Exception e) {
            System.err.println("Error dropping table " + tableName + ": " + e.getMessage());
        }
    }
}

