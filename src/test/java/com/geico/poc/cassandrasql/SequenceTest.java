package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PostgreSQL-compatible SEQUENCE support.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SequenceTest {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @BeforeEach
    public void setup() throws Exception {
        // Clean up any existing test sequences
        try {
            queryService.execute("DROP SEQUENCE IF EXISTS test_seq");
            queryService.execute("DROP SEQUENCE IF EXISTS my_sequence");
            queryService.execute("DROP SEQUENCE IF EXISTS custom_seq");
        } catch (Exception e) {
            // Ignore
        }
        Thread.sleep(100);
    }
    
    // ========================================
    // Test 1: Basic CREATE SEQUENCE
    // ========================================
    
    @Test
    public void testCreateSequenceBasic() throws Exception {
        System.out.println("\n=== Test 1: Basic CREATE SEQUENCE ===");
        
        QueryResponse createResp = queryService.execute(
            "CREATE SEQUENCE test_seq"
        );
        assertNull(createResp.getError(), "CREATE SEQUENCE should succeed");
        
        // Verify sequence was created
        assertNotNull(schemaManager.getSequence("test_seq"), "Sequence should exist");
        
        System.out.println("✅ Basic CREATE SEQUENCE works");
    }
    
    // ========================================
    // Test 2: CREATE SEQUENCE with parameters
    // ========================================
    
    @Test
    public void testCreateSequenceWithParameters() throws Exception {
        System.out.println("\n=== Test 2: CREATE SEQUENCE with parameters ===");
        
        QueryResponse createResp = queryService.execute(
            "CREATE SEQUENCE my_sequence " +
            "INCREMENT BY 5 " +
            "START WITH 100 " +
            "MINVALUE 1 " +
            "MAXVALUE 999999999 " +
            "CACHE 10"
        );
        assertNull(createResp.getError(), "CREATE SEQUENCE should succeed: " + createResp.getError());
        
        // Verify sequence metadata
        var seq = schemaManager.getSequence("my_sequence");
        assertNotNull(seq, "Sequence should exist");
        assertEquals(5, seq.getIncrement());
        assertEquals(100, seq.getStartValue());
        assertEquals(1L, seq.getMinValue());
        assertEquals(999999999L, seq.getMaxValue());
        assertEquals(10, seq.getCache());
        
        System.out.println("✅ CREATE SEQUENCE with parameters works");
    }
    
    // ========================================
    // Test 3: nextval() function
    // ========================================
    
    @Test
    public void testNextval() throws Exception {
        System.out.println("\n=== Test 3: nextval() function ===");
        
        queryService.execute("CREATE SEQUENCE test_seq START WITH 1 INCREMENT BY 1");
        Thread.sleep(100);
        
        // Get next values
        long val1 = schemaManager.nextval("test_seq");
        long val2 = schemaManager.nextval("test_seq");
        long val3 = schemaManager.nextval("test_seq");
        
        assertEquals(1, val1, "First nextval should be 1");
        assertEquals(2, val2, "Second nextval should be 2");
        assertEquals(3, val3, "Third nextval should be 3");
        
        System.out.println("✅ nextval() works correctly");
    }
    
    // ========================================
    // Test 4: currval() function
    // ========================================
    
    @Test
    public void testCurrval() throws Exception {
        System.out.println("\n=== Test 4: currval() function ===");
        
        queryService.execute("CREATE SEQUENCE test_seq START WITH 10");
        Thread.sleep(100);
        
        // Advance sequence
        long val1 = schemaManager.nextval("test_seq");
        assertEquals(10, val1);
        
        // currval should return same value
        long curr1 = schemaManager.currval("test_seq");
        assertEquals(10, curr1, "currval should return current value");
        
        // Advance again
        long val2 = schemaManager.nextval("test_seq");
        assertEquals(11, val2);
        
        long curr2 = schemaManager.currval("test_seq");
        assertEquals(11, curr2, "currval should return updated value");
        
        System.out.println("✅ currval() works correctly");
    }
    
    // ========================================
    // Test 5: setval() function
    // ========================================
    
    @Test
    public void testSetval() throws Exception {
        System.out.println("\n=== Test 5: setval() function ===");
        
        queryService.execute("CREATE SEQUENCE test_seq");
        Thread.sleep(100);
        
        // Set to specific value
        schemaManager.setval("test_seq", 100);
        
        // Next value should be 101
        long next = schemaManager.nextval("test_seq");
        assertEquals(101, next, "After setval(100), nextval should be 101");
        
        System.out.println("✅ setval() works correctly");
    }
    
    // ========================================
    // Test 6: DROP SEQUENCE
    // ========================================
    
    @Test
    public void testDropSequence() throws Exception {
        System.out.println("\n=== Test 6: DROP SEQUENCE ===");
        
        queryService.execute("CREATE SEQUENCE test_seq");
        Thread.sleep(100);
        
        assertNotNull(schemaManager.getSequence("test_seq"), "Sequence should exist");
        
        QueryResponse dropResp = queryService.execute("DROP SEQUENCE test_seq");
        assertNull(dropResp.getError(), "DROP SEQUENCE should succeed");
        
        assertNull(schemaManager.getSequence("test_seq"), "Sequence should not exist after drop");
        
        System.out.println("✅ DROP SEQUENCE works");
    }
    
    // ========================================
    // Test 7: IF NOT EXISTS
    // ========================================
    
    @Test
    public void testCreateSequenceIfNotExists() throws Exception {
        System.out.println("\n=== Test 7: CREATE SEQUENCE IF NOT EXISTS ===");
        
        queryService.execute("CREATE SEQUENCE test_seq");
        Thread.sleep(100);
        
        // Second create with IF NOT EXISTS should succeed
        QueryResponse createResp = queryService.execute("CREATE SEQUENCE IF NOT EXISTS test_seq");
        assertNull(createResp.getError(), "CREATE SEQUENCE IF NOT EXISTS should succeed");
        
        // Without IF NOT EXISTS should fail
        QueryResponse createResp2 = queryService.execute("CREATE SEQUENCE test_seq");
        assertNotNull(createResp2.getError(), "CREATE SEQUENCE without IF NOT EXISTS should fail");
        assertTrue(createResp2.getError().contains("already exists"));
        
        System.out.println("✅ IF NOT EXISTS works");
    }
    
    // ========================================
    // Test 8: IF EXISTS
    // ========================================
    
    @Test
    public void testDropSequenceIfExists() throws Exception {
        System.out.println("\n=== Test 8: DROP SEQUENCE IF EXISTS ===");
        
        // Drop non-existent sequence with IF EXISTS should succeed
        QueryResponse dropResp = queryService.execute("DROP SEQUENCE IF EXISTS nonexistent_seq");
        assertNull(dropResp.getError(), "DROP SEQUENCE IF EXISTS should succeed");
        
        // Without IF EXISTS should fail
        QueryResponse dropResp2 = queryService.execute("DROP SEQUENCE nonexistent_seq");
        assertNotNull(dropResp2.getError(), "DROP SEQUENCE without IF EXISTS should fail");
        assertTrue(dropResp2.getError().contains("does not exist"));
        
        System.out.println("✅ IF EXISTS works");
    }
    
    // ========================================
    // Test 9: Custom increment
    // ========================================
    
    @Test
    public void testCustomIncrement() throws Exception {
        System.out.println("\n=== Test 9: Custom INCREMENT BY ===");
        
        queryService.execute("CREATE SEQUENCE test_seq INCREMENT BY 10 START WITH 5");
        Thread.sleep(100);
        
        long val1 = schemaManager.nextval("test_seq");
        long val2 = schemaManager.nextval("test_seq");
        long val3 = schemaManager.nextval("test_seq");
        
        assertEquals(5, val1, "First value should be 5");
        assertEquals(15, val2, "Second value should be 15");
        assertEquals(25, val3, "Third value should be 25");
        
        System.out.println("✅ Custom INCREMENT BY works");
    }
    
    // ========================================
    // Test 10: Sequence independence
    // ========================================
    
    @Test
    public void testSequenceIndependence() throws Exception {
        System.out.println("\n=== Test 10: Sequence independence ===");
        
        queryService.execute("CREATE SEQUENCE seq1 START WITH 1");
        queryService.execute("CREATE SEQUENCE seq2 START WITH 100");
        Thread.sleep(100);
        
        long seq1_val1 = schemaManager.nextval("seq1");
        long seq2_val1 = schemaManager.nextval("seq2");
        long seq1_val2 = schemaManager.nextval("seq1");
        long seq2_val2 = schemaManager.nextval("seq2");
        
        assertEquals(1, seq1_val1);
        assertEquals(100, seq2_val1);
        assertEquals(2, seq1_val2);
        assertEquals(101, seq2_val2);
        
        // Clean up
        queryService.execute("DROP SEQUENCE seq1");
        queryService.execute("DROP SEQUENCE seq2");
        
        System.out.println("✅ Sequences are independent");
    }
}


