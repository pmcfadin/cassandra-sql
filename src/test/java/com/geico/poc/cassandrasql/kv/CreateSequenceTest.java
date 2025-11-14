package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for CREATE SEQUENCE and sequence operations
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CreateSequenceTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    private String TEST_SEQUENCE;
    private String TEST_TABLE;
    
    @BeforeEach
    public void setUp() {
        TEST_SEQUENCE = uniqueTableName("test_seq");
        TEST_TABLE = uniqueTableName("test_table");
        // Drop sequence if it exists
        try {
            queryService.execute("DROP SEQUENCE " + TEST_SEQUENCE);
            queryService.execute("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception e) {
            // Ignore if sequence doesn't exist
        }
    }
    
    @AfterEach
    public void tearDown() {
        try {
            queryService.execute("DROP SEQUENCE " + TEST_SEQUENCE);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @Test
    @Order(99)
    public void testCreateSequenceBasic() throws Exception {
        // Create a basic sequence
        QueryResponse response = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE
        );
        assertNull(response.getError(), "CREATE SEQUENCE failed: " + response.getError());
        
        // Verify sequence was created
        SequenceMetadata seq = schemaManager.getSequence(TEST_SEQUENCE);
        assertNotNull(seq, "Sequence should exist");
        assertEquals(TEST_SEQUENCE, seq.getSequenceName());
        assertEquals(1L, seq.getIncrement());
        assertEquals(1L, seq.getStartValue());
    }
    
    @Test
    @Order(99)
    public void testCreateSequenceWithIncrement() throws Exception {
        // Create sequence with custom increment
        QueryResponse response = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE + " INCREMENT BY 5"
        );
        assertNull(response.getError());
        
        // Verify increment
        SequenceMetadata seq = schemaManager.getSequence(TEST_SEQUENCE);
        assertNotNull(seq);
        assertEquals(5L, seq.getIncrement());
    }
    
    @Test
    @Order(99)
    public void testCreateSequenceWithStartValue() throws Exception {
        // Create sequence with custom start value
        QueryResponse response = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE + " START WITH 100"
        );
        assertNull(response.getError());
        
        // Verify start value
        SequenceMetadata seq = schemaManager.getSequence(TEST_SEQUENCE);
        assertNotNull(seq);
        assertEquals(100L, seq.getStartValue());
    }
    
    @Test
    @Order(99)
    public void testCreateSequenceWithMinMax() throws Exception {
        // Create sequence with min/max values
        QueryResponse response = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE + " MINVALUE 10 MAXVALUE 1000"
        );
        assertNull(response.getError());
        
        // Verify min/max
        SequenceMetadata seq = schemaManager.getSequence(TEST_SEQUENCE);
        assertNotNull(seq);
        assertEquals(10L, seq.getMinValue());
        assertEquals(1000L, seq.getMaxValue());
    }
    
    @Test
    @Order(99)
    public void testCreateSequenceWithCycle() throws Exception {
        // Create sequence with CYCLE
        QueryResponse response = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE + " CYCLE"
        );
        assertNull(response.getError());
        
        // Verify cycle flag
        SequenceMetadata seq = schemaManager.getSequence(TEST_SEQUENCE);
        assertNotNull(seq);
        assertTrue(seq.isCycle());
    }
    
    @Test
    @Order(99)
    public void testCreateSequenceIfNotExists() throws Exception {
        // Create sequence
        QueryResponse response1 = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE
        );
        assertNull(response1.getError());
        
        // Try to create again without IF NOT EXISTS (should fail)
        QueryResponse response2 = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE
        );
        assertNotNull(response2.getError(), "Should fail when sequence already exists");
        assertTrue(response2.getError().contains("already exists"));
        
        // Try with IF NOT EXISTS (should succeed)
        QueryResponse response3 = queryService.execute(
            "CREATE SEQUENCE IF NOT EXISTS " + TEST_SEQUENCE
        );
        assertNull(response3.getError(), "IF NOT EXISTS should not fail");
    }
    
    @Test
    @Order(99)
    public void testDropSequence() throws Exception {
        // Create sequence
        QueryResponse createResponse = queryService.execute(
            "CREATE SEQUENCE " + TEST_SEQUENCE
        );
        assertNull(createResponse.getError());
        
        // Drop sequence
        QueryResponse dropResponse = queryService.execute(
            "DROP SEQUENCE " + TEST_SEQUENCE
        );
        assertNull(dropResponse.getError());
        
        // Verify sequence was dropped
        SequenceMetadata seq = schemaManager.getSequence(TEST_SEQUENCE);
        assertNull(seq, "Sequence should not exist after DROP");
    }
    
    @Test
    @Order(99)
    public void testDropSequenceIfExists() throws Exception {
        // Drop non-existent sequence without IF EXISTS (should fail)
        QueryResponse response1 = queryService.execute(
            "DROP SEQUENCE " + TEST_SEQUENCE
        );
        assertNotNull(response1.getError(), "Should fail when sequence doesn't exist");
        
        // Drop with IF EXISTS (should succeed)
        QueryResponse response2 = queryService.execute(
            "DROP SEQUENCE IF EXISTS " + TEST_SEQUENCE
        );
        assertNull(response2.getError(), "IF EXISTS should not fail");
    }
    
    @Test
    @Order(99)
    public void testSequenceWithTable() throws Exception {
        // Create a table with a SERIAL column (uses sequence internally)
        String tableName = uniqueTableName("test_sequence_table");
        
        queryService.execute("DROP TABLE IF EXISTS " + tableName);

        String createSql = "CREATE TABLE " + tableName + " (" +
                          "id SERIAL PRIMARY KEY, " +
                          "name TEXT)";
        QueryResponse createResponse = queryService.execute(createSql);
        assertNull(createResponse.getError(), "Failed to create table with SERIAL: " + createResponse.getError());
        
        // Insert rows without specifying id
        QueryResponse insert1 = queryService.execute(
            "INSERT INTO " + tableName + " (name) VALUES ('Alice')"
        );
        assertNull(insert1.getError());
        
        QueryResponse insert2 = queryService.execute(
            "INSERT INTO " + tableName + " (name) VALUES ('Bob')"
        );
        assertNull(insert2.getError());
        
        // Query and verify auto-generated IDs
        QueryResponse selectResponse = queryService.execute(
            "SELECT * FROM " + tableName + " ORDER BY id"
        );
        System.out.println("Select Response: " + selectResponse.getRows());
        assertNull(selectResponse.getError());
        assertEquals(2, selectResponse.getRows().size());
        
        // IDs should be auto-generated (likely 1 and 2, but could be higher)
        Object id1 = selectResponse.getRows().get(0).get("id");
        Object id2 = selectResponse.getRows().get(1).get("id");
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2, "IDs should be unique");
        
        // Cleanup
        queryService.execute("DROP TABLE " + tableName);
    }
}

