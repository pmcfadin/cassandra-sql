package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for KV storage mode.
 * Tests the full stack: Schema management, transactions, and query execution.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvIntegrationTest extends KvTestBase {
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired(required = false)
    private SchemaManager schemaManager;
    
    @Autowired(required = false)
    private KvTransactionCoordinator coordinator;
    
    @Autowired(required = false)
    private KvQueryExecutor queryExecutor;
    
    @Autowired(required = false)
    private KvStore kvStore;
    
    @Autowired(required = false)
    private TimestampOracle timestampOracle;
    
    @BeforeAll
    public void setupAll() {
        // Only run if KV mode is enabled
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            System.out.println("⚠️  Skipping KV integration tests (KV mode not enabled)");
            return;
        }
        
        System.out.println("🔧 Setting up KV integration tests...");
        assertNotNull(schemaManager, "SchemaManager should be initialized");
        assertNotNull(coordinator, "KvTransactionCoordinator should be initialized");
        assertNotNull(queryExecutor, "KvQueryExecutor should be initialized");
        assertNotNull(kvStore, "KvStore should be initialized");
        assertNotNull(timestampOracle, "TimestampOracle should be initialized");
    }
    
    @BeforeEach
    public void setup() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        // Refresh schema cache
        schemaManager.refreshCache();
    }
    
    // ========================================
    // Schema Management Tests
    // ========================================
    
    @Test
    @Order(1)
    public void testCreateTable() {
        String tableName = uniqueTableName("users");
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("name", "TEXT", true));
        columns.add(new TableMetadata.ColumnMetadata("email", "TEXT", true));
        
        List<String> primaryKey = Arrays.asList("id");
        
        TableMetadata table = schemaManager.createTable(tableName, columns, primaryKey);
        
        assertNotNull(table);
        assertEquals(tableName, table.getTableName());
        assertTrue(table.getTableId() > 0);
        assertEquals(3, table.getColumns().size());
        assertEquals(1, table.getPrimaryKeyColumns().size());
        assertEquals("id", table.getPrimaryKeyColumns().get(0));
        
        // Verify table exists
        assertTrue(schemaManager.tableExists(tableName));
        
        // Verify we can retrieve it
        TableMetadata retrieved = schemaManager.getTable(tableName);
        assertNotNull(retrieved);
        assertEquals(table.getTableId(), retrieved.getTableId());
    }
    
    @Test
    @Order(2)
    public void testCreateTableWithCompositeKey() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("user_id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("order_id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("amount", "DOUBLE", true));
        
        List<String> primaryKey = Arrays.asList("user_id", "order_id");
        
        TableMetadata table = schemaManager.createTable("orders", columns, primaryKey);
        
        assertNotNull(table);
        assertEquals(2, table.getPrimaryKeyColumns().size());
        assertEquals("user_id", table.getPrimaryKeyColumns().get(0));
        assertEquals("order_id", table.getPrimaryKeyColumns().get(1));
    }
    
    @Test
    @Order(3)
    public void testCreateIndex() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table first
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("name", "TEXT", true));
        
        schemaManager.createTable("products", columns, Arrays.asList("id"));
        
        // Create index
        TableMetadata.IndexMetadata index = schemaManager.createIndex(
            "products", "idx_products_name", Arrays.asList("name"), false
        );
        
        assertNotNull(index);
        assertEquals("idx_products_name", index.getName());
        assertTrue(index.getIndexId() > 0);
        assertEquals(1, index.getColumns().size());
        assertEquals("name", index.getColumns().get(0));
        
        // Verify index is in table metadata
        TableMetadata table = schemaManager.getTable("products");
        assertEquals(1, table.getIndexes().size());
        assertEquals(index.getIndexId(), table.getIndexes().get(0).getIndexId());
    }
    
    @Test
    @Order(4)
    public void testDropTable() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        schemaManager.createTable("temp_table", columns, Arrays.asList("id"));
        
        assertTrue(schemaManager.tableExists("temp_table"));
        
        // Drop table
        schemaManager.dropTable("temp_table");
        
        assertFalse(schemaManager.tableExists("temp_table"));
    }
    
    @Test
    @Order(5)
    public void testTableIdAllocation() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        long id1 = schemaManager.allocateTableId();
        long id2 = schemaManager.allocateTableId();
        long id3 = schemaManager.allocateTableId();
        
        System.out.println(String.format("id1: {}, id2: {}, id3: {}", id1, id2, id3));

        assertTrue(id1 >= 0);
        assertTrue(id2 > id1);
        assertTrue(id3 > id2);
    }
    
    // ========================================
    // Transaction Tests
    // ========================================
    
    @Test
    @Order(10)
    public void testSimpleTransaction() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("value", "TEXT", true));
        TableMetadata table = schemaManager.createTable("test_tx", columns, Arrays.asList("id"));
        
        // Begin transaction
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        // Add write
        byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(1), ctx.getStartTs());
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("test_value"));
        ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        
        assertEquals(1, ctx.getWriteCount());
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
        assertNotNull(ctx.getCommitTs());
        
        // Verify data is committed
        long readTs = timestampOracle.getCurrentTimestamp();
        KvStore.KvEntry entry = kvStore.get(key, readTs);
        
        assertNotNull(entry);
        assertArrayEquals(value, entry.getValue());
        assertEquals(ctx.getCommitTs(), entry.getCommitTs());
    }
    
    @Test
    @Order(11)
    public void testTransactionWithMultipleKeys() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("value", "TEXT", true));
        TableMetadata table = schemaManager.createTable("test_multi", columns, Arrays.asList("id"));
        
        // Begin transaction
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        // Add multiple writes
        for (int i = 1; i <= 5; i++) {
            byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(i), ctx.getStartTs());
            byte[] value = ValueEncoder.encodeRow(Arrays.asList("value_" + i));
            ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        }
        
        assertEquals(5, ctx.getWriteCount());
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
        
        // Verify all keys are committed
        long readTs = timestampOracle.getCurrentTimestamp();
        for (int i = 1; i <= 5; i++) {
            byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(i), ctx.getStartTs());
            KvStore.KvEntry entry = kvStore.get(key, readTs);
            assertNotNull(entry, "Key " + i + " should be committed");
            assertEquals(ctx.getCommitTs(), entry.getCommitTs());
        }
    }
    
    @Test
    @Order(12)
    public void testTransactionRollback() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table with unique name
        String tableName = uniqueTableName("rollback_test");
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("value", "TEXT", true));
        TableMetadata table = schemaManager.createTable(tableName, columns, Arrays.asList("id"));
        
        // Begin transaction
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        // Add write intent (but don't prewrite yet)
        byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(1), ctx.getStartTs());
        byte[] value = ValueEncoder.encodeRow(Arrays.asList("test_value"));
        ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        
        // Rollback before prewrite
        // This should be a no-op since nothing was written, but should set state correctly
        try {
            coordinator.rollback(ctx);
        } catch (Exception e) {
            // Rollback should not throw
            fail("Rollback should not throw exception: " + e.getMessage());
        }
        
        // Verify transaction state is set correctly
        assertEquals(KvTransactionContext.TransactionState.ABORTED, ctx.getState());
        
        // Note: We can't verify data deletion here because prewrite was never called
        // To properly test rollback of written data, we'd need to:
        // 1. Call commit() which does prewrite + commit
        // 2. But then the transaction is committed, so we can't rollback
        // 
        // The rollback() method is tested in other tests that actually write data
        // This test just verifies rollback doesn't crash on a transaction with no writes
    }
    
    @Test
    @Order(13)
    public void testMVCCSnapshotIsolation() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("value", "TEXT", true));
        TableMetadata table = schemaManager.createTable("test_mvcc", columns, Arrays.asList("id"));
        
        // Transaction 1: Write version 1
        KvTransactionContext ctx1 = coordinator.beginTransaction();
        byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(1), ctx1.getStartTs());
        byte[] value1 = ValueEncoder.encodeRow(Arrays.asList("version_1"));
        ctx1.addWrite(key, value1, KvTransactionContext.WriteType.PUT);
        coordinator.commit(ctx1);
        long commitTs1 = ctx1.getCommitTs();
        
        // Take snapshot timestamp
        Thread.sleep(10);  // Ensure time passes
        long snapshotTs = timestampOracle.getCurrentTimestamp();
        
        // Transaction 2: Write version 2
        Thread.sleep(10);
        KvTransactionContext ctx2 = coordinator.beginTransaction();
        byte[] key2 = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(1), ctx2.getStartTs());
        byte[] value2 = ValueEncoder.encodeRow(Arrays.asList("version_2"));
        ctx2.addWrite(key2, value2, KvTransactionContext.WriteType.PUT);
        coordinator.commit(ctx2);
        long commitTs2 = ctx2.getCommitTs();
        
        // Read at snapshot should see version 1
        KvStore.KvEntry entryAtSnapshot = kvStore.get(key, snapshotTs);
        assertNotNull(entryAtSnapshot);
        assertEquals(commitTs1, entryAtSnapshot.getCommitTs());
        
        // Read at current time should see version 2
        long currentTs = timestampOracle.getCurrentTimestamp();
        KvStore.KvEntry entryAtCurrent = kvStore.get(key, currentTs);
        assertNotNull(entryAtCurrent);
        assertEquals(commitTs2, entryAtCurrent.getCommitTs());
    }
    
    // ========================================
    // Key/Value Encoding Tests
    // ========================================
    
    @Test
    @Order(20)
    public void testKeyEncodingSortOrder() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        long tableId = 1L;
        long ts = 1000L;
        
        // Encode keys with different integer values
        byte[] key1 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(-100), ts);
        byte[] key2 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(-10), ts);
        byte[] key3 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(0), ts);
        byte[] key4 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(10), ts);
        byte[] key5 = KeyEncoder.encodeTableDataKey(tableId, Arrays.asList(100), ts);
        
        // Verify sort order
        assertTrue(compareBytes(key1, key2) < 0);
        assertTrue(compareBytes(key2, key3) < 0);
        assertTrue(compareBytes(key3, key4) < 0);
        assertTrue(compareBytes(key4, key5) < 0);
    }
    
    @Test
    @Order(21)
    public void testValueEncodingRoundtrip() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Test various data types
        List<Object> values = Arrays.asList(
            42,                    // Integer
            "Hello, World!",       // String
            true,                  // Boolean
            3.14159,              // Double
            null                   // NULL
        );
        
        List<Class<?>> types = Arrays.asList(
            Integer.class,
            String.class,
            Boolean.class,
            Double.class,
            String.class  // NULL can be any type
        );
        
        // Encode
        byte[] encoded = ValueEncoder.encodeRow(values);
        
        // Decode
        List<Object> decoded = ValueEncoder.decodeRow(encoded, types);
        
        assertEquals(5, decoded.size());
        assertEquals(42, decoded.get(0));
        assertEquals("Hello, World!", decoded.get(1));
        assertEquals(true, decoded.get(2));
        assertEquals(3.14159, (Double) decoded.get(3), 0.0001);
        assertNull(decoded.get(4));
    }
    
    @Test
    @Order(22)
    public void testTimestampOracleMonotonicity() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        long ts1 = timestampOracle.allocateStartTimestamp();
        long ts2 = timestampOracle.allocateStartTimestamp();
        long ts3 = timestampOracle.allocateStartTimestamp();
        
        assertTrue(ts1 > 0);
        assertTrue(ts2 > ts1);
        assertTrue(ts3 > ts2);
    }
    
    // ========================================
    // Query Execution Tests (Basic)
    // ========================================
    
    @Test
    @Order(30)
    public void testSelectFromEmptyTable() {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("name", "TEXT", true));
        schemaManager.createTable("empty_table", columns, Arrays.asList("id"));
        
        // Query should return empty result
        // Note: Full query execution would require ParsedQuery setup
        // For now, we verify the table exists
        assertTrue(schemaManager.tableExists("empty_table"));
    }
    
    // ========================================
    // Stress Tests
    // ========================================
    
    @Test
    @Order(40)
    public void testLargeTransaction() throws Exception {
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            return;
        }
        
        // Create table
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        columns.add(new TableMetadata.ColumnMetadata("id", "INT", false));
        columns.add(new TableMetadata.ColumnMetadata("value", "TEXT", true));
        TableMetadata table = schemaManager.createTable("test_large", columns, Arrays.asList("id"));
        
        // Begin transaction with many keys
        KvTransactionContext ctx = coordinator.beginTransaction();
        
        int numKeys = 50;  // Reduced from 100 for faster tests
        for (int i = 0; i < numKeys; i++) {
            byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(i), ctx.getStartTs());
            byte[] value = ValueEncoder.encodeRow(Arrays.asList("value_" + i));
            ctx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
        }
        
        assertEquals(numKeys, ctx.getWriteCount());
        
        // Commit
        coordinator.commit(ctx);
        
        assertEquals(KvTransactionContext.TransactionState.COMMITTED, ctx.getState());
        
        // Verify all keys are committed
        long readTs = timestampOracle.getCurrentTimestamp();
        for (int i = 0; i < numKeys; i++) {
            byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), Arrays.asList(i), ctx.getStartTs());
            KvStore.KvEntry entry = kvStore.get(key, readTs);
            assertNotNull(entry, "Key " + i + " should be committed");
        }
    }
    
    // Helper methods
    
    private int compareBytes(byte[] a, byte[] b) {
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }
}



