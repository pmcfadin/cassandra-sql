package com.geico.poc.cassandrasql.postgres;

import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PostgreSQL prepared statements
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "spring.cassandra.local-datacenter=datacenter1",
    "spring.cassandra.keyspace-name=cassandra_sql",
    "spring.cassandra.schema-action=create_if_not_exists",
    "spring.cassandra.request.timeout=10s",
    "spring.cassandra.connection.init-query-timeout=10s"
})
@Disabled
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PreparedStatementTest {
    
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/cassandra_sql";
    private static final String USER = "postgres";
    private static final String PASSWORD = "";
    
    @Autowired
    private com.geico.poc.cassandrasql.config.CassandraSqlConfig config;
    
    @Autowired
    private com.geico.poc.cassandrasql.kv.SchemaManager schemaManager;
    
    private Connection conn;
    
    @BeforeEach
    public void setup() throws Exception {
        // Verify KV mode
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            throw new IllegalStateException("Tests must run in KV mode! Current mode: " + config.getStorageMode());
        }
        System.out.println("✅ Running prepared statement tests in KV mode");
        
        // Connect via JDBC
        conn = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        assertNotNull(conn, "Connection should not be null");
        
        // Create test table
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_prep");
            stmt.execute("CREATE TABLE test_prep (id INT PRIMARY KEY, name TEXT, amount INT)");
            
            // Insert test data
            stmt.execute("INSERT INTO test_prep (id, name, amount) VALUES (1, 'Alice', 100)");
            stmt.execute("INSERT INTO test_prep (id, name, amount) VALUES (2, 'Bob', 200)");
            stmt.execute("INSERT INTO test_prep (id, name, amount) VALUES (3, 'Charlie', 300)");
        }
        
        System.out.println("✅ Test table created and populated");
    }
    
    @AfterEach
    public void teardown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS test_prep");
            }
            conn.close();
        }
    }
    
    @Test
    @Order(1)
    public void testBasicPreparedStatement() throws Exception {
        System.out.println("\n🧪 Test 1: Basic prepared statement with single parameter");
        
        String sql = "SELECT * FROM test_prep WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // Execute with id=1
            pstmt.setInt(1, 1);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(1, rs.getInt("id"));
                assertEquals("Alice", rs.getString("name"));
                assertEquals(100, rs.getInt("amount"));
                assertFalse(rs.next(), "Should return exactly one row");
            }
            
            // Reuse with id=2
            pstmt.setInt(1, 2);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(2, rs.getInt("id"));
                assertEquals("Bob", rs.getString("name"));
                assertEquals(200, rs.getInt("amount"));
                assertFalse(rs.next(), "Should return exactly one row");
            }
            
            // Reuse with id=3
            pstmt.setInt(1, 3);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(3, rs.getInt("id"));
                assertEquals("Charlie", rs.getString("name"));
                assertEquals(300, rs.getInt("amount"));
                assertFalse(rs.next(), "Should return exactly one row");
            }
        }
        
        System.out.println("✅ Basic prepared statement test passed");
    }
    
    @Test
    @Order(2)
    public void testMultipleParameters() throws Exception {
        System.out.println("\n🧪 Test 2: Prepared statement with multiple parameters");
        
        String sql = "SELECT * FROM test_prep WHERE id >= ? AND amount <= ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // Execute with id>=1 AND value<=250
            pstmt.setInt(1, 1);
            pstmt.setInt(2, 250);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(1, rs.getInt("id"));
                assertTrue(rs.next(), "Should return at least two rows");
                assertEquals(2, rs.getInt("id"));
                assertFalse(rs.next(), "Should return exactly two rows");
            }
            
            // Reuse with id>=2 AND value<=400
            pstmt.setInt(1, 2);
            pstmt.setInt(2, 400);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(2, rs.getInt("id"));
                assertTrue(rs.next(), "Should return at least two rows");
                assertEquals(3, rs.getInt("id"));
                assertFalse(rs.next(), "Should return exactly two rows");
            }
        }
        
        System.out.println("✅ Multiple parameters test passed");
    }
    
    @Test
    @Order(3)
    public void testStringParameter() throws Exception {
        System.out.println("\n🧪 Test 3: Prepared statement with string parameter");
        
        String sql = "SELECT * FROM test_prep WHERE name = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // Execute with name='Alice'
            pstmt.setString(1, "Alice");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(1, rs.getInt("id"));
                assertEquals("Alice", rs.getString("name"));
                assertFalse(rs.next(), "Should return exactly one row");
            }
            
            // Reuse with name='Bob'
            pstmt.setString(1, "Bob");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(2, rs.getInt("id"));
                assertEquals("Bob", rs.getString("name"));
                assertFalse(rs.next(), "Should return exactly one row");
            }
        }
        
        System.out.println("✅ String parameter test passed");
    }
    
    @Test
    @Order(4)
    public void testInsertWithPreparedStatement() throws Exception {
        System.out.println("\n🧪 Test 4: INSERT with prepared statement");
        
        String insertSql = "INSERT INTO test_prep (id, name, amount) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            
            // Insert row 1
            pstmt.setInt(1, 10);
            pstmt.setString(2, "Dave");
            pstmt.setInt(3, 1000);
            int rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should insert exactly one row");
            
            // Insert row 2 (reuse statement)
            pstmt.setInt(1, 11);
            pstmt.setString(2, "Eve");
            pstmt.setInt(3, 1100);
            rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should insert exactly one row");
        }
        
        // Verify inserts
        String selectSql = "SELECT * FROM test_prep WHERE id >= ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setInt(1, 10);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return at least one row");
                assertEquals(10, rs.getInt("id"));
                assertEquals("Dave", rs.getString("name"));
                assertEquals(1000, rs.getInt("amount"));
                
                assertTrue(rs.next(), "Should return at least two rows");
                assertEquals(11, rs.getInt("id"));
                assertEquals("Eve", rs.getString("name"));
                assertEquals(1100, rs.getInt("amount"));
                
                assertFalse(rs.next(), "Should return exactly two rows");
            }
        }
        
        System.out.println("✅ INSERT with prepared statement test passed");
    }
    
    @Test
    @Order(5)
    public void testUpdateWithPreparedStatement() throws Exception {
        System.out.println("\n🧪 Test 5: UPDATE with prepared statement");
        
        String updateSql = "UPDATE test_prep SET amount = ? WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            
            // Update id=1
            pstmt.setInt(1, 999);
            pstmt.setInt(2, 1);
            int rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should update exactly one row");
            
            // Update id=2 (reuse statement)
            pstmt.setInt(1, 888);
            pstmt.setInt(2, 2);
            rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should update exactly one row");
        }
        
        // Verify updates
        String selectSql = "SELECT * FROM test_prep WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setInt(1, 1);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return one row");
                assertEquals(999, rs.getInt("amount"));
            }
            
            pstmt.setInt(1, 2);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return one row");
                assertEquals(888, rs.getInt("amount"));
            }
        }
        
        System.out.println("✅ UPDATE with prepared statement test passed");
    }
    
    @Test
    @Order(6)
    public void testDeleteWithPreparedStatement() throws Exception {
        System.out.println("\n🧪 Test 6: DELETE with prepared statement");
        
        // First insert some rows to delete
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO test_prep (id, name, amount) VALUES (20, 'ToDelete1', 2000)");
            stmt.execute("INSERT INTO test_prep (id, name, amount) VALUES (21, 'ToDelete2', 2100)");
        }
        
        String deleteSql = "DELETE FROM test_prep WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
            
            // Delete id=20
            pstmt.setInt(1, 20);
            int rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should delete exactly one row");
            
            // Delete id=21 (reuse statement)
            pstmt.setInt(1, 21);
            rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should delete exactly one row");
        }
        
        // Verify deletions
        String selectSql = "SELECT * FROM test_prep WHERE id >= ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setInt(1, 20);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertFalse(rs.next(), "Should return no rows (both deleted)");
            }
        }
        
        System.out.println("✅ DELETE with prepared statement test passed");
    }
    
    @Test
    @Order(7)
    public void testNullParameter() throws Exception {
        System.out.println("\n🧪 Test 7: Prepared statement with NULL parameter");
        
        // Insert a row with NULL value
        String insertSql = "INSERT INTO test_prep (id, name, amount) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setInt(1, 30);
            pstmt.setString(2, "NullValue");
            pstmt.setNull(3, Types.INTEGER);
            int rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should insert exactly one row");
        }
        
        // Query for the NULL value
        String selectSql = "SELECT * FROM test_prep WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setInt(1, 30);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return one row");
                assertEquals(30, rs.getInt("id"));
                assertEquals("NullValue", rs.getString("name"));
                rs.getInt("amount");
                assertTrue(rs.wasNull(), "Value should be NULL");
            }
        }
        
        System.out.println("✅ NULL parameter test passed");
    }
    
    @Test
    @Order(8)
    public void testSpecialCharactersInString() throws Exception {
        System.out.println("\n🧪 Test 8: Prepared statement with special characters (SQL injection prevention)");
        
        // Insert a row with special characters
        String insertSql = "INSERT INTO test_prep (id, name, amount) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setInt(1, 40);
            pstmt.setString(2, "O'Reilly"); // Single quote
            pstmt.setInt(3, 400);
            int rowsAffected = pstmt.executeUpdate();
            assertEquals(1, rowsAffected, "Should insert exactly one row");
        }
        
        // Query for the special character value
        String selectSql = "SELECT * FROM test_prep WHERE name = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, "O'Reilly");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should return one row");
                assertEquals(40, rs.getInt("id"));
                assertEquals("O'Reilly", rs.getString("name"));
                assertEquals(400, rs.getInt("amount"));
            }
        }
        
        // Test potential SQL injection
        String maliciousSql = "'; DROP TABLE test_prep; --";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, maliciousSql);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertFalse(rs.next(), "Should return no rows (injection prevented)");
            }
        }
        
        // Verify table still exists
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM test_prep");
            assertTrue(rs.next(), "Table should still exist");
            assertTrue(rs.getInt("cnt") > 0, "Table should have data");
        }
        
        System.out.println("✅ Special characters and SQL injection prevention test passed");
    }
    
    @Test
    @Order(9)
    public void testMultiplePreparedStatementsSimultaneously() throws Exception {
        System.out.println("\n🧪 Test 9: Multiple prepared statements active simultaneously");
        
        String selectSql1 = "SELECT * FROM test_prep WHERE id = ?";
        String selectSql2 = "SELECT * FROM test_prep WHERE name = ?";
        String selectSql3 = "SELECT * FROM test_prep WHERE value >= ?";
        
        try (PreparedStatement pstmt1 = conn.prepareStatement(selectSql1);
             PreparedStatement pstmt2 = conn.prepareStatement(selectSql2);
             PreparedStatement pstmt3 = conn.prepareStatement(selectSql3)) {
            
            // Execute all three
            pstmt1.setInt(1, 1);
            pstmt2.setString(1, "Bob");
            pstmt3.setInt(1, 200);
            
            try (ResultSet rs1 = pstmt1.executeQuery();
                 ResultSet rs2 = pstmt2.executeQuery();
                 ResultSet rs3 = pstmt3.executeQuery()) {
                
                // Verify rs1
                assertTrue(rs1.next(), "rs1 should return one row");
                assertEquals(1, rs1.getInt("id"));
                assertFalse(rs1.next());
                
                // Verify rs2
                assertTrue(rs2.next(), "rs2 should return one row");
                assertEquals(2, rs2.getInt("id"));
                assertFalse(rs2.next());
                
                // Verify rs3
                assertTrue(rs3.next(), "rs3 should return at least one row");
                assertEquals(2, rs3.getInt("id"));
                assertTrue(rs3.next(), "rs3 should return at least two rows");
                assertEquals(3, rs3.getInt("id"));
            }
        }
        
        System.out.println("✅ Multiple prepared statements test passed");
    }
    
    @Test
    @Order(10)
    public void testBatchExecution() throws Exception {
        System.out.println("\n🧪 Test 10: Batch execution with prepared statement");
        
        String insertSql = "INSERT INTO test_prep (id, name, amount) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            
            // Add batch entries
            for (int i = 50; i < 55; i++) {
                pstmt.setInt(1, i);
                pstmt.setString(2, "Batch" + i);
                pstmt.setInt(3, i * 100);
                pstmt.addBatch();
            }
            
            // Execute batch
            int[] results = pstmt.executeBatch();
            assertEquals(5, results.length, "Should execute 5 statements");
            for (int result : results) {
                assertTrue(result >= 0, "Each statement should succeed");
            }
        }
        
        // Verify batch inserts
        String selectSql = "SELECT * FROM test_prep WHERE id >= ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setInt(1, 50);
            try (ResultSet rs = pstmt.executeQuery()) {
                for (int i = 50; i < 55; i++) {
                    assertTrue(rs.next(), "Should return row for id=" + i);
                    assertEquals(i, rs.getInt("id"));
                    assertEquals("Batch" + i, rs.getString("name"));
                    assertEquals(i * 100, rs.getInt("amount"));
                }
                assertFalse(rs.next(), "Should return exactly 5 rows");
            }
        }
        
        System.out.println("✅ Batch execution test passed");
    }
}



