package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ARRAY type support in KV mode
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ArrayTypeTest {
    
    @Autowired
    private QueryService queryService;
    
    private static final String TEST_TABLE = "array_test_table";
    
    @BeforeAll
    public void setup() throws Exception {
        // Drop and recreate table
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
        
        // Create test table with array column
        queryService.execute(
            "CREATE TABLE " + TEST_TABLE + " (" +
            "    id INT PRIMARY KEY," +
            "    name TEXT," +
            "    tags TEXT[]" +
            ")"
        );
        
        // Insert test data
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, tags) VALUES (1, 'Product A', ARRAY['electronics', 'sale', 'new'])");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, tags) VALUES (2, 'Product B', ARRAY['clothing', 'clearance'])");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, tags) VALUES (3, 'Product C', ARRAY['food'])");
        queryService.execute("INSERT INTO " + TEST_TABLE + " (id, name, tags) VALUES (4, 'Product D', NULL)");
        
        Thread.sleep(100); // Allow data to settle
    }
    
    @AfterAll
    public void teardown() {
        try {
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("SELECT should return arrays as List, not Object references")
    public void testSelectArrayColumn() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT id, name, tags FROM " + TEST_TABLE + " WHERE id = 1"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Map<String, Object> row = response.getRows().get(0);
        Object tagsValue = row.get("tags");
        
        assertNotNull(tagsValue, "Tags should not be null");
        assertFalse(tagsValue.toString().startsWith("[Ljava.lang.Object;"), 
            "Tags should not be a Java object reference: " + tagsValue);
        
        // Should be a List or array
        assertTrue(tagsValue instanceof List || tagsValue.getClass().isArray(),
            "Tags should be a List or array, got: " + tagsValue.getClass());
        
        if (tagsValue instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> tagsList = (List<String>) tagsValue;
            assertEquals(3, tagsList.size(), "Should have 3 tags");
            assertTrue(tagsList.contains("electronics"), "Should contain 'electronics'");
            assertTrue(tagsList.contains("sale"), "Should contain 'sale'");
            assertTrue(tagsList.contains("new"), "Should contain 'new'");
        }
    }
    
    @Test
    @Order(2)
    @DisplayName("SELECT * should properly deserialize arrays")
    public void testSelectStarWithArrays() throws Exception {
        QueryResponse response = queryService.execute(
            "SELECT * FROM " + TEST_TABLE + " ORDER BY id"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(4, response.getRowCount(), "Should return 4 rows");
        
        // Check first row
        Map<String, Object> row1 = response.getRows().get(0);
        Object tags1 = row1.get("tags");
        assertNotNull(tags1, "Tags should not be null for row 1");
        assertFalse(tags1.toString().startsWith("[Ljava.lang.Object;"), 
            "Tags should not be a Java object reference");
        
        // Check row with NULL array
        Map<String, Object> row4 = response.getRows().get(3);
        Object tags4 = row4.get("tags");
        assertTrue(tags4 == null || (tags4 instanceof List && ((List<?>)tags4).isEmpty()),
            "Tags should be null or empty list for row 4");
    }
    
    @Test
    @Order(3)
    @DisplayName("INSERT and SELECT should round-trip arrays correctly")
    public void testArrayRoundTrip() throws Exception {
        // Insert a new row with specific array
        queryService.execute(
            "INSERT INTO " + TEST_TABLE + " (id, name, tags) " +
            "VALUES (100, 'Test Product', ARRAY['tag1', 'tag2', 'tag3'])"
        );
        
        // Select it back
        QueryResponse response = queryService.execute(
            "SELECT tags FROM " + TEST_TABLE + " WHERE id = 100"
        );
        
        assertNotNull(response);
        assertNull(response.getError(), "Query should succeed");
        assertEquals(1, response.getRowCount(), "Should return 1 row");
        
        Object tagsValue = response.getRows().get(0).get("tags");
        assertNotNull(tagsValue, "Tags should not be null");
        assertFalse(tagsValue.toString().startsWith("[Ljava.lang.Object;"), 
            "Tags should not be a Java object reference");
        
        if (tagsValue instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> tagsList = (List<String>) tagsValue;
            assertEquals(Arrays.asList("tag1", "tag2", "tag3"), tagsList,
                "Array should round-trip correctly");
        }
    }
}
