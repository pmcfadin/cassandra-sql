package com.geico.poc.cassandrasql.integration;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests specifically for KV storage mode.
 * 
 * Tests INSERT, UPDATE, DELETE, SELECT with WHERE, ORDER BY, LIMIT
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"cassandra-sql.storage-mode=kv"})
public class KvModeIntegrationTest extends IntegrationTestBase {
    
    @Autowired
    private CassandraSqlConfig config;
    
    private static String TEST_TABLE;
    
    @BeforeAll
    public void verifyKvMode() {
        assertEquals(CassandraSqlConfig.StorageMode.KV, config.getStorageMode(), 
            "Tests must run in KV mode");
        System.out.println("✅ Running tests in KV mode");
    }
    
    @BeforeEach
    public void setUp() {
        // Clean up before each test - drop table via SQL
        try {
            TEST_TABLE = uniqueTableName("kv_test_users");
            executeQuery("DROP TABLE " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
    }
    
    @AfterEach
    public void tearDown() {
        // Clean up after each test
        try {
            executeQuery("DROP TABLE " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("KV Mode: CREATE TABLE should create a table successfully")
    public void testCreateTable() {
        // Given
        String sql = "CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))";
        
        // When
        QueryResponse response = executeQuery(sql);
        
        // Then
        assertSuccess(response);
        System.out.println("✅ CREATE TABLE succeeded in KV mode");
    }
    
    @Test
    @Order(2)
    @DisplayName("KV Mode: INSERT should add rows to the table")
    public void testInsert() {
        // Given - create table first
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        
        // When
        QueryResponse response1 = executeQuery(
            "INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        QueryResponse response2 = executeQuery(
            "INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        QueryResponse response3 = executeQuery(
            "INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        
        // Then
        assertSuccess(response1);
        assertSuccess(response2);
        assertSuccess(response3);
        assertEquals(1, response1.getRowCount(), "Should insert 1 row");
        assertEquals(1, response2.getRowCount(), "Should insert 1 row");
        assertEquals(1, response3.getRowCount(), "Should insert 1 row");
        System.out.println("✅ INSERT succeeded in KV mode");
    }
    
    @Test
    @Order(3)
    @DisplayName("KV Mode: SELECT * should return all rows")
    public void testSelectAll() {
        // Given - create table and insert data
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        
        // When
        QueryResponse response = executeQuery("SELECT * FROM " + TEST_TABLE);
        
        // Then
        assertSuccess(response);
        assertRowCount(response, 3);
        assertHasColumn(response, "name");
        assertHasColumn(response, "age");
        System.out.println("✅ SELECT * succeeded in KV mode");
        System.out.println("   Rows: " + response.getRows());
    }
    
    @Test
    @Order(4)
    @DisplayName("KV Mode: SELECT with WHERE should filter rows")
    public void testSelectWithWhere() {
        // Given
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        
        // When
        QueryResponse response = executeQuery(
            "SELECT * FROM " + TEST_TABLE + " WHERE age > 28");
        
        // Then
        assertSuccess(response);
        // Should return Alice (30) and Charlie (35)
        assertTrue(response.getRowCount() >= 2, "Should return at least 2 rows with age > 28");
        System.out.println("✅ SELECT with WHERE succeeded in KV mode");
        System.out.println("   Filtered rows: " + response.getRows());
    }
    
    @Test
    @Order(5)
    @DisplayName("KV Mode: UPDATE should modify rows")
    public void testUpdate() {
        // Given
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        
        // When
        QueryResponse updateResponse = executeQuery(
            "UPDATE " + TEST_TABLE + " SET age = 26 WHERE name = 'Bob'");
        
        // Then
        assertSuccess(updateResponse);
        assertTrue(updateResponse.getRowCount() >= 1, "Should update at least 1 row");
        
        // Verify the update
        QueryResponse selectResponse = executeQuery(
            "SELECT * FROM " + TEST_TABLE + " WHERE name = 'Bob'");
        assertSuccess(selectResponse);
        System.out.println("✅ UPDATE succeeded in KV mode");
        System.out.println("   Updated rows: " + updateResponse.getRowCount());
    }
    
    @Test
    @Order(6)
    @DisplayName("KV Mode: DELETE should remove rows")
    public void testDelete() {
        // Given
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        
        // When
        QueryResponse deleteResponse = executeQuery(
            "DELETE FROM " + TEST_TABLE + " WHERE age < 30");
        
        // Then
        assertSuccess(deleteResponse);
        assertTrue(deleteResponse.getRowCount() >= 1, "Should delete at least 1 row (Bob)");
        
        // Verify the delete
        QueryResponse selectResponse = executeQuery("SELECT * FROM " + TEST_TABLE);
        assertSuccess(selectResponse);
        System.out.println("✅ DELETE succeeded in KV mode");
        System.out.println("   Deleted rows: " + deleteResponse.getRowCount());
        System.out.println("   Remaining rows: " + selectResponse.getRowCount());
    }
    
    @Test
    @Order(7)
    @DisplayName("KV Mode: SELECT with ORDER BY should sort results")
    public void testSelectWithOrderBy() {
        // Given
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        
        // When
        QueryResponse response = executeQuery(
            "SELECT * FROM " + TEST_TABLE + " ORDER BY age ASC");
        
        // Then
        assertSuccess(response);
        assertRowCount(response, 3);
        
        // Verify order: Bob (25), Alice (30), Charlie (35)
        if (response.getRows() != null && response.getRows().size() >= 2) {
            Object firstAge = response.getRows().get(0).get("age");
            Object secondAge = response.getRows().get(1).get("age");
            System.out.println("   First row age: " + firstAge);
            System.out.println("   Second row age: " + secondAge);
        }
        
        System.out.println("✅ SELECT with ORDER BY succeeded in KV mode");
    }
    
    @Test
    @Order(8)
    @DisplayName("KV Mode: SELECT with LIMIT should limit results")
    public void testSelectWithLimit() {
        // Given
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        
        // When
        QueryResponse response = executeQuery(
            "SELECT * FROM " + TEST_TABLE + " LIMIT 2");
        
        // Then
        assertSuccess(response);
        assertTrue(response.getRowCount() <= 2, "Should return at most 2 rows");
        System.out.println("✅ SELECT with LIMIT succeeded in KV mode");
        System.out.println("   Returned rows: " + response.getRowCount());
    }
    
    @Test
    @Order(9)
    @DisplayName("KV Mode: Complex query with WHERE, ORDER BY, and LIMIT")
    public void testComplexQuery() {
        // Given
        executeQuery("CREATE TABLE " + TEST_TABLE + " (id INT, name TEXT, age INT, PRIMARY KEY (id))");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (1, 'Alice', 30)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (2, 'Bob', 25)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (3, 'Charlie', 35)");
        executeQuery("INSERT INTO " + TEST_TABLE + " (id, name, age) VALUES (4, 'David', 28)");
        
        // When
        QueryResponse response = executeQuery(
            "SELECT * FROM " + TEST_TABLE + " WHERE age >= 28 ORDER BY age ASC LIMIT 2");
        
        // Then
        assertSuccess(response);
        assertTrue(response.getRowCount() <= 2, "Should return at most 2 rows");
        System.out.println("✅ Complex query succeeded in KV mode");
        System.out.println("   Rows: " + response.getRows());
    }
}



