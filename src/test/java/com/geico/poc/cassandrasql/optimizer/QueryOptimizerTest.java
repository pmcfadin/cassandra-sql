package com.geico.poc.cassandrasql.optimizer;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.JoinQuery;
import com.geico.poc.cassandrasql.MultiWayJoinQuery;
import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the QueryOptimizer
 */
@SpringBootTest
@ActiveProfiles("test")
public class QueryOptimizerTest {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired(required = false)
    private SchemaManager schemaManager;
    
    private QueryOptimizer optimizer;
    
    @BeforeEach
    public void setUp() {
        optimizer = new QueryOptimizer();
        // Manually inject dependencies for testing
        try {
            java.lang.reflect.Field sessionField = QueryOptimizer.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            sessionField.set(optimizer, session);
            
            java.lang.reflect.Field configField = QueryOptimizer.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(optimizer, config);
            
            if (schemaManager != null) {
                java.lang.reflect.Field schemaManagerField = QueryOptimizer.class.getDeclaredField("schemaManager");
                schemaManagerField.setAccessible(true);
                schemaManagerField.set(optimizer, schemaManager);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up optimizer", e);
        }
    }
    
    @Test
    public void testOptimizeBinaryJoin() {
        // Create a simple join query
        JoinQuery joinQuery = new JoinQuery(
            "USERS",
            "ORDERS",
            "id",
            "user_id",
            JoinQuery.JoinType.INNER,
            Arrays.asList("*"),
            null
        );
        
        ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.JOIN, joinQuery, "SELECT * FROM users JOIN orders ON users.id = orders.user_id");
        
        // Optimize
        QueryOptimizer.OptimizedQuery result = optimizer.optimize(parsedQuery);
        
        // Verify
        assertNotNull(result);
        assertTrue(result.getEstimatedCost() > 0, "Cost should be estimated");
        
        System.out.println("Binary JOIN optimization:");
        System.out.println("  Optimizations: " + result.getOptimizations());
        System.out.println("  Estimated cost: " + result.getEstimatedCost());
        System.out.println("  Should swap: " + result.shouldSwapJoinSides());
    }
    
    @Test
    public void testOptimizeMultiWayJoin() {
        // Create a multi-way join query
        MultiWayJoinQuery multiJoin = new MultiWayJoinQuery(
            Arrays.asList("USERS", "ORDERS", "PRODUCTS"),
            Arrays.asList(),  // Join conditions
            Arrays.asList("*"),  // Select columns
            java.util.Collections.emptyMap()  // Table aliases
        );
        
        ParsedQuery parsedQuery = new ParsedQuery(ParsedQuery.Type.MULTI_WAY_JOIN, multiJoin, "SELECT * FROM users JOIN orders JOIN products");
        
        // Optimize
        QueryOptimizer.OptimizedQuery result = optimizer.optimize(parsedQuery);
        
        // Verify
        assertNotNull(result);
        assertTrue(result.getEstimatedCost() > 0, "Cost should be estimated");
        
        System.out.println("Multi-way JOIN optimization:");
        System.out.println("  Optimizations: " + result.getOptimizations());
        System.out.println("  Estimated cost: " + result.getEstimatedCost());
        if (result.getOptimalTableOrder() != null) {
            System.out.println("  Optimal order: " + result.getOptimalTableOrder());
        }
    }
    
    @Test
    public void testTableStatistics() {
        // Test statistics collection
        QueryOptimizer.TableStatistics stats = optimizer.getTableStatistics("USERS");
        
        assertNotNull(stats);
        assertTrue(stats.getRowCount() > 0, "Row count should be positive");
        assertTrue(stats.getColumnCount() > 0, "Column count should be positive");
        
        System.out.println("Table statistics for USERS:");
        System.out.println("  Row count: " + stats.getRowCount());
        System.out.println("  Column count: " + stats.getColumnCount());
    }
    
    @Test
    public void testClearCache() {
        // Get statistics to populate cache
        optimizer.getTableStatistics("USERS");
        
        // Clear cache
        optimizer.clearCache();
        
        // Should not throw exception
        optimizer.getTableStatistics("USERS");
    }
}

