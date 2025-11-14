package com.geico.poc.cassandrasql.kv;

import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.QueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SQL VIEWs in KV mode
 */
@SpringBootTest
@TestPropertySource(properties = {
    "cassandra-sql.storage-mode=kv",
    "cassandra-sql.use-calcite-cbo=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ViewTest extends KvTestBase {
    
    @Autowired
    private QueryService queryService;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Test
    @Order(1)
    public void testCreateSimpleView() throws Exception {
        String tableName = uniqueTableName("view_base");
        String viewName = uniqueTableName("simple_view");
        
        // Create base table
        QueryResponse createTable = queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT, active BOOLEAN)",
            tableName
        ));
        assertNull(createTable.getError());
        Thread.sleep(200);
        
        // Insert test data
        queryService.execute(String.format("INSERT INTO %s (id, name, active) VALUES (1, 'Alice', true)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, active) VALUES (2, 'Bob', false)", tableName));
        queryService.execute(String.format("INSERT INTO %s (id, name, active) VALUES (3, 'Charlie', true)", tableName));
        
        // Create view
        QueryResponse createView = queryService.execute(String.format(
            "CREATE VIEW %s AS SELECT id, name FROM %s WHERE active = true",
            viewName, tableName
        ));
        assertNull(createView.getError(), "CREATE VIEW failed: " + createView.getError());
        
        // Verify view exists in schema manager
        ViewMetadata view = schemaManager.getView(viewName);
        assertNotNull(view);
        assertEquals(viewName, view.getViewName());
        assertFalse(view.isMaterialized());
        
        // Cleanup
        queryService.execute("DROP VIEW IF EXISTS " + viewName);
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(2)
    public void testDropView() throws Exception {
        String tableName = uniqueTableName("drop_base");
        String viewName = uniqueTableName("drop_view");
        
        // Create base table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, value INT)",
            tableName
        ));
        Thread.sleep(200);
        
        // Create view
        QueryResponse createView = queryService.execute(String.format(
            "CREATE VIEW %s AS SELECT * FROM %s",
            viewName, tableName
        ));
        assertNull(createView.getError());
        
        // Verify view exists
        ViewMetadata view = schemaManager.getView(viewName);
        assertNotNull(view);
        
        // Drop view
        QueryResponse dropView = queryService.execute("DROP VIEW " + viewName);
        assertNull(dropView.getError(), "DROP VIEW failed: " + dropView.getError());
        
        // Verify view is gone
        view = schemaManager.getView(viewName);
        assertNull(view);
        
        // Cleanup
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(3)
    public void testDropViewIfExists() throws Exception {
        String viewName = uniqueTableName("nonexistent_view");
        
        // Drop non-existent view with IF EXISTS (should not error)
        QueryResponse dropView = queryService.execute("DROP VIEW IF EXISTS " + viewName);
        assertNull(dropView.getError(), "DROP VIEW IF EXISTS failed: " + dropView.getError());
    }
    
    @Test
    @Order(4)
    public void testDropNonExistentView() throws Exception {
        String viewName = uniqueTableName("missing_view");
        
        // Drop non-existent view without IF EXISTS (should error)
        QueryResponse dropView = queryService.execute("DROP VIEW " + viewName);
        assertNotNull(dropView.getError());
        assertTrue(dropView.getError().contains("does not exist"));
    }
    
    @Test
    @Order(5)
    public void testCreateDuplicateView() throws Exception {
        String tableName = uniqueTableName("dup_base");
        String viewName = uniqueTableName("dup_view");
        
        // Create base table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY)",
            tableName
        ));
        Thread.sleep(200);
        
        // Create view
        QueryResponse create1 = queryService.execute(String.format(
            "CREATE VIEW %s AS SELECT * FROM %s",
            viewName, tableName
        ));
        assertNull(create1.getError());
        
        // Try to create duplicate view (should error)
        QueryResponse create2 = queryService.execute(String.format(
            "CREATE VIEW %s AS SELECT * FROM %s",
            viewName, tableName
        ));
        assertNotNull(create2.getError());
        assertTrue(create2.getError().contains("already exists"));
        
        // Cleanup
        queryService.execute("DROP VIEW IF EXISTS " + viewName);
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
    
    @Test
    @Order(6)
    public void testCreateViewWithJoin() throws Exception {
        String table1 = uniqueTableName("join_table1");
        String table2 = uniqueTableName("join_table2");
        String viewName = uniqueTableName("join_view");
        
        // Create base tables
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, name TEXT)",
            table1
        ));
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, user_id INT, score INT)",
            table2
        ));
        Thread.sleep(200);
        
        // Create view with JOIN
        QueryResponse createView = queryService.execute(String.format(
            "CREATE VIEW %s AS SELECT t1.name, t2.score FROM %s t1 INNER JOIN %s t2 ON t1.id = t2.user_id",
            viewName, table1, table2
        ));
        assertNull(createView.getError(), "CREATE VIEW with JOIN failed: " + createView.getError());
        
        // Verify view exists
        ViewMetadata view = schemaManager.getView(viewName);
        assertNotNull(view);
        assertFalse(view.isMaterialized());
        
        // Cleanup
        queryService.execute("DROP VIEW IF EXISTS " + viewName);
        queryService.execute("DROP TABLE IF EXISTS " + table2);
        queryService.execute("DROP TABLE IF EXISTS " + table1);
    }
    
    @Test
    @Order(7)
    public void testCreateViewWithAggregation() throws Exception {
        String tableName = uniqueTableName("agg_base");
        String viewName = uniqueTableName("agg_view");
        
        // Create base table
        queryService.execute(String.format(
            "CREATE TABLE %s (id INT PRIMARY KEY, category TEXT, amount INT)",
            tableName
        ));
        Thread.sleep(200);
        
        // Create view with aggregation
        QueryResponse createView = queryService.execute(String.format(
            "CREATE VIEW %s AS SELECT category, SUM(amount) as total FROM %s GROUP BY category",
            viewName, tableName
        ));
        assertNull(createView.getError(), "CREATE VIEW with aggregation failed: " + createView.getError());
        
        // Verify view exists
        ViewMetadata view = schemaManager.getView(viewName);
        assertNotNull(view);
        
        // Cleanup
        queryService.execute("DROP VIEW IF EXISTS " + viewName);
        queryService.execute("DROP TABLE IF EXISTS " + tableName);
    }
}



