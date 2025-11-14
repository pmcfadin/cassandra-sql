package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ENUM type functionality in KV mode
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
public class EnumTypeTest {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Test
    public void testCreateEnumType() throws Exception {
        // Create ENUM type
        String sql = "CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled')";
        QueryResponse response = queryService.execute(sql);
        
        assertNull(response.getError(), "CREATE TYPE should succeed");
        
        // Verify enum type exists
        EnumMetadata enumType = schemaManager.getEnumType("order_status");
        assertNotNull(enumType, "ENUM type should exist");
        assertEquals("order_status", enumType.getTypeName());
        assertEquals(5, enumType.getValues().size());
        assertTrue(enumType.getValues().contains("pending"));
        assertTrue(enumType.getValues().contains("shipped"));
        
        // Clean up
        queryService.execute("DROP TYPE order_status");
    }
    
    @Test
    public void testDropEnumType() throws Exception {
        // Create ENUM type
        queryService.execute("CREATE TYPE payment_method AS ENUM ('credit_card', 'debit_card', 'paypal')");
        
        // Verify it exists
        EnumMetadata enumType = schemaManager.getEnumType("payment_method");
        assertNotNull(enumType);
        
        // Drop it
        QueryResponse response = queryService.execute("DROP TYPE payment_method");
        assertNull(response.getError(), "DROP TYPE should succeed");
        
        // Verify it's gone
        enumType = schemaManager.getEnumType("payment_method");
        assertNull(enumType, "ENUM type should be dropped");
    }
    
    @Test
    public void testCreateDuplicateEnumType() throws Exception {
        // Create ENUM type
        queryService.execute("CREATE TYPE status AS ENUM ('active', 'inactive')");
        
        // Try to create again
        QueryResponse response = queryService.execute("CREATE TYPE status AS ENUM ('new', 'old')");
        assertNotNull(response.getError(), "Creating duplicate ENUM type should fail");
        assertTrue(response.getError().contains("already exists"));
        
        // Clean up
        queryService.execute("DROP TYPE status");
    }
    
    @Test
    public void testEnumValidation() throws Exception {
        // Create ENUM type
        queryService.execute("CREATE TYPE color AS ENUM ('red', 'green', 'blue')");
        
        EnumMetadata enumType = schemaManager.getEnumType("color");
        assertNotNull(enumType);
        
        // Test valid values
        assertTrue(enumType.isValidValue("red"));
        assertTrue(enumType.isValidValue("green"));
        assertTrue(enumType.isValidValue("blue"));
        
        // Test invalid values
        assertFalse(enumType.isValidValue("yellow"));
        assertFalse(enumType.isValidValue("RED")); // Case sensitive
        assertFalse(enumType.isValidValue(null));
        
        // Clean up
        queryService.execute("DROP TYPE color");
    }
    
    @Test
    public void testMultipleEnumTypes() throws Exception {
        // Create multiple ENUM types
        queryService.execute("CREATE TYPE priority AS ENUM ('low', 'medium', 'high')");
        queryService.execute("CREATE TYPE severity AS ENUM ('minor', 'major', 'critical')");
        
        // Verify both exist
        EnumMetadata priority = schemaManager.getEnumType("priority");
        EnumMetadata severity = schemaManager.getEnumType("severity");
        
        assertNotNull(priority);
        assertNotNull(severity);
        assertEquals(3, priority.getValues().size());
        assertEquals(3, severity.getValues().size());
        
        // Clean up
        queryService.execute("DROP TYPE priority");
        queryService.execute("DROP TYPE severity");
    }
}
