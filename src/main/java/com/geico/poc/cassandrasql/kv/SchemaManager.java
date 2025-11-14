package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schema manager for KV storage mode.
 * 
 * Manages table and index metadata, allocates IDs, and provides schema operations.
 */
@Component
@DependsOn("internalKeyspaceManager")
public class SchemaManager {
    
    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private InternalKeyspaceManager internalKeyspaceManager;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    @Autowired(required = false)
    private PgCatalogManager pgCatalogManager;
    
    @Value("${cassandra-sql.schema.refresh-interval-seconds:10}")
    private int refreshIntervalSeconds;
    
    @Value("${cassandra-sql.schema.always-reload:false}")
    private boolean alwaysReload;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Schema cache
    private final Map<String, TableMetadata> tablesByName = new ConcurrentHashMap<>();
    private final Map<Long, TableMetadata> tablesById = new ConcurrentHashMap<>();
    private final Map<String, SequenceMetadata> sequencesByName = new ConcurrentHashMap<>();
    private final Map<String, ViewMetadata> viewsByName = new ConcurrentHashMap<>();
    private final Map<Long, ViewMetadata> viewsById = new ConcurrentHashMap<>();
    private final Map<String, EnumMetadata> enumsByName = new ConcurrentHashMap<>();
    
    // In-memory cache of dropped tables (for vacuum job)
    // Key: table_id, Value: TableMetadata with drop timestamp
    private Map<Long, TableMetadata> droppedTablesCache = new ConcurrentHashMap<>();
    
    // In-memory cache of truncated tables (for vacuum job)
    // Key: table_id, Value: truncate timestamp
    private Map<Long, Long> truncatedTablesCache = new ConcurrentHashMap<>();
    
    // Background refresh
    private ScheduledExecutorService refreshExecutor;
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private volatile long lastRefreshTime = 0;
    private final AtomicBoolean hasAttemptedLoad = new AtomicBoolean(false);
    
    // Sequence keys
    private static final String TABLE_ID_SEQUENCE = "table_id_seq";
    private static final String VIEW_ID_SEQUENCE = "view_id_seq";
    private static final long GLOBAL_TABLE_ID = 0L;
    private static final long GLOBAL_SEQUENCE_ID = -1L;  // Special ID for global sequences
    private static final long GLOBAL_VIEW_ID = -2L;  // Special ID for views
    
    // View IDs start from a high offset to avoid collision with table IDs
    // This ensures materialized view data keys don't collide with table data keys
    private static final long VIEW_ID_OFFSET = 1000000000L;
    
    // Per-table locks for sequence allocation (to reduce Java-side contention)
    private final Map<Long, Object> sequenceLocks = new ConcurrentHashMap<>();
    // Per-sequence locks for sequence allocation (by sequence key hash)
    private final Map<Integer, Object> sequenceKeyLocks = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void initialize() {
        if (config.getStorageMode() == CassandraSqlConfig.StorageMode.KV) {
            log.info("Initializing Schema Manager for KV mode...");
            log.info("  Schema refresh interval: {} seconds", refreshIntervalSeconds);
            log.info("  Always reload from KV: {}", alwaysReload);
            
            try {
                initializeSequences();
                
                // Ensure PgCatalogManager creates catalog tables
                if (pgCatalogManager != null) {
                    pgCatalogManager.ensureInitialized();
                }
                
                createCatalogTables();
                
                // Force load from KV storage on startup
                log.info("Loading schema from KV storage...");
                refreshSchemaFromKvStorage();
                
                syncCatalogTables();
                cleanupOrphanedCatalogEntries();  // Clean up any stale entries
                
                // Start background refresh if enabled
                if (refreshIntervalSeconds > 0) {
                    startBackgroundRefresh();
                }
                
                log.info("Schema Manager initialized successfully");
            } catch (Exception e) {
                log.error("⚠️  Schema Manager initialization failed: " + e.getMessage());
                log.error("   This is expected on first startup. Tables will be created on first use.");
                // Don't fail startup - tables will be created lazily
            }
        }
    }
    
    @PreDestroy
    public void shutdown() {
        if (refreshExecutor != null) {
            log.info("Shutting down schema refresh executor...");
            refreshExecutor.shutdown();
            try {
                if (!refreshExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    refreshExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                refreshExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Start background schema refresh task
     */
    private void startBackgroundRefresh() {
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "schema-refresh");
            t.setDaemon(true);
            return t;
        });
        
        refreshExecutor.scheduleAtFixedRate(() -> {
            try {
                log.debug("Background schema refresh triggered");
                refreshSchemaFromKvStorage();
            } catch (Exception e) {
                log.error("Background schema refresh failed", e);
            }
        }, refreshIntervalSeconds, refreshIntervalSeconds, TimeUnit.SECONDS);
        
        log.info("Started background schema refresh (interval: {}s)", refreshIntervalSeconds);
    }
    
    /**
     * Force refresh schema from KV storage
     * This is the source of truth - always reads from disk
     */
    public void refreshSchemaFromKvStorage() {
        if (!isRefreshing.compareAndSet(false, true)) {
            log.debug("Schema refresh already in progress, skipping");
            return;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Refreshing schema from KV storage...");
            
            // Clear current cache
            Map<String, TableMetadata> oldTablesByName = new HashMap<>(tablesByName);
            Map<Long, TableMetadata> oldTablesById = new HashMap<>(tablesById);
            
            // tablesByName.clear();
            // tablesById.clear();
            
            // Reload from KV storage
            loadSchemaCache();
            
            // Detect changes
            Set<String> added = new HashSet<>(tablesByName.keySet());
            added.removeAll(oldTablesByName.keySet());
            
            Set<String> removed = new HashSet<>(oldTablesByName.keySet());
            removed.removeAll(tablesByName.keySet());
            
            if (!added.isEmpty() || !removed.isEmpty()) {
                log.info("Schema changes detected: +{} tables, -{} tables", added.size(), removed.size());
                if (!added.isEmpty()) {
                    log.info("  Added: {}", added);
                }
                if (!removed.isEmpty()) {
                    log.info("  Removed: {}", removed);
                }
            }
            
            lastRefreshTime = System.currentTimeMillis();
            long duration = lastRefreshTime - startTime;
            log.debug("Schema refresh completed in {}ms ({} tables)", duration, tablesByName.size());
            
            // Mark that we've attempted to load (successfully or not)
            hasAttemptedLoad.set(true);
            
        } catch (Exception e) {
            log.error("Failed to refresh schema from KV storage", e);
            // Don't set the flag on error - allow retry via lazy loading
        } finally {
            isRefreshing.set(false);
        }
    }
    
    /**
     * Initialize sequence counters
     */
    private void initializeSequences() {
        // Initialize table ID sequence if it doesn't exist
        byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_TABLE_ID, TABLE_ID_SEQUENCE);
        long currentTs = timestampOracle.getCurrentTimestamp();
        
        KvStore.KvEntry entry = kvStore.get(seqKey, currentTs);
        if (entry == null) {
            // Initialize to 1 (0 is reserved for global/system)
            // Regular put is fine - Accord handles transactions via BEGIN TRANSACTION
            byte[] initialValue = ByteBuffer.allocate(8).putLong(1L).array();
            kvStore.put(seqKey, initialValue, currentTs, UUID.randomUUID(), currentTs, false);
            log.debug("Initialized table ID sequence");
        }
    }
    
    /**
     * Load all table schemas into cache
     */
    private void loadSchemaCache() {
        try {
            log.debug("Loading tables from catalog...");
            loadTables();
            log.debug("Tables loaded successfully");
        } catch (Exception e) {
            log.error("  ⚠️  Failed to load schema cache: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Load tables from pg_class catalog table.
     * This queries the pg_class catalog table to find all user tables, then loads
     * their schema metadata. This avoids scanning the entire KV store.
     */
    private void loadTables() {
        try {
            int loadedCount = 0;
            int droppedCount = 0;
            int truncatedCount = 0;
            
            // In-memory cache of dropped tables (for vacuum job)
            // Key: table_id, Value: TableMetadata with drop timestamp
            Map<Long, TableMetadata> tempDroppedTablesCache = new ConcurrentHashMap<>();
            
            // In-memory cache of truncated tables (for vacuum job)
            // Key: table_id, Value: truncate timestamp
            Map<Long, Long> tempTruncatedTablesCache = new ConcurrentHashMap<>();
            
            // Get pg_class catalog table metadata
            // Try cache first, then schema namespace, then create from PgCatalogTable definition
            TableMetadata pgClass = tablesById.get(-2L); // pg_class has table ID -2
            
            if (pgClass == null) {
                // Try to load pg_class from schema namespace
                log.debug("pg_class not in cache, loading from schema namespace...");
                byte[] pgClassSchemaKey = KeyEncoder.encodeSchemaKey(-2L, "schema");
                KvStore.KvEntry pgClassSchemaEntry = kvStore.get(pgClassSchemaKey, timestampOracle.getCurrentTimestamp());
                
                if (pgClassSchemaEntry != null && !pgClassSchemaEntry.isDeleted()) {
                    try {
                        String pgClassJson = new String(pgClassSchemaEntry.getValue(), StandardCharsets.UTF_8);
                        pgClass = objectMapper.readValue(pgClassJson, TableMetadata.class);
                        // Register it in cache (both by ID and by name)
                        registerCatalogTable(pgClass);
                        log.debug("Loaded and registered pg_class catalog table from schema namespace");
                    } catch (Exception e) {
                        log.debug("Failed to load pg_class from schema namespace: {}", e.getMessage());
                    }
                }
            }
            
            // If still not found, create it from PgCatalogTable definition
            if (pgClass == null) {
                log.debug("Creating pg_class metadata from PgCatalogTable definition...");
                try {
                    PgCatalogTable pgCatalogTable = PgCatalogTable.pgClass();
                    List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
                    for (PgCatalogTable.Column col : pgCatalogTable.getColumns()) {
                        columns.add(new TableMetadata.ColumnMetadata(
                            col.getName(),
                            col.getType(),
                            !col.isNotNull()  // nullable = !notNull
                        ));
                    }
                    
                    pgClass = new TableMetadata(
                        pgCatalogTable.getTableId(),
                        pgCatalogTable.getTableName(),
                        columns,
                        pgCatalogTable.getPrimaryKey(),
                        new ArrayList<>(),  // No indexes
                        new ArrayList<>(),   // No unique constraints
                        new ArrayList<>(),   // No foreign key constraints
                        1,  // version
                        null,  // No truncate timestamp
                        null   // Not dropped
                    );
                    
                    // Register it in cache for future use (both by ID and by name)
                    registerCatalogTable(pgClass);
                    log.debug("Created and registered pg_class metadata from PgCatalogTable definition");
                } catch (Exception e) {
                    log.error("Failed to create pg_class metadata: {}", e.getMessage());
                    log.info("Schema cache loaded (0 tables - pg_class not available)");
                    droppedTablesCache = tempDroppedTablesCache;
                    truncatedTablesCache = tempTruncatedTablesCache;
                    return;
                }
            }
            
            long currentTs = timestampOracle.getCurrentTimestamp();
            
            // Scan pg_class to find all user tables (relkind = 'r')
            // pg_class has primary key: oid
            byte[] startKey = KeyEncoder.createRangeStartKey(-2L, KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(-2L, KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> pgClassEntries = kvStore.scan(startKey, endKey, currentTs, 10000);
            
            log.info("Scanned pg_class catalog: found {} entries", pgClassEntries.size());
            
            if (pgClassEntries.isEmpty()) {
                log.warn("pg_class catalog is empty - no tables found. This is normal on first startup.");
                droppedTablesCache = tempDroppedTablesCache;
                truncatedTablesCache = tempTruncatedTablesCache;
                return;
            }
            
            log.info("Processing {} pg_class entries to load user tables...", pgClassEntries.size());
            
            // Process each pg_class entry
            for (KvStore.KvEntry entry : pgClassEntries) {
                try {
                    // Decode pg_class row
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), 
                        Arrays.asList(Long.class)); // oid is BIGINT
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(),
                        pgClass.getNonPrimaryKeyColumns().stream()
                            .map(TableMetadata.ColumnMetadata::getJavaType)
                            .collect(java.util.stream.Collectors.toList()));
                    
                    if (pkValues.isEmpty()) {
                        continue;
                    }
                    
                    Long oid = (Long) pkValues.get(0);
                    if (oid == null) {
                        continue;
                    }
                    
                    // Extract relname and relkind from decoded values
                    String relname = null;
                    String relkind = null;
                    
                    // Find relname and relkind columns
                    List<TableMetadata.ColumnMetadata> nonPkColumns = pgClass.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        String colName = nonPkColumns.get(i).getName().toLowerCase();
                        if ("relname".equals(colName)) {
                            relname = (String) nonPkValues.get(i);
                        } else if ("relkind".equals(colName)) {
                            relkind = (String) nonPkValues.get(i);
                        }
                    }
                    
                    log.debug("pg_class entry: oid={}, relname='{}', relkind='{}'", oid, relname, relkind);
                    
                    // Only process regular tables (relkind = 'r')
                    if (!"r".equals(relkind)) {
                        if (relkind != null) {
                            log.debug("Skipping pg_class entry with relkind='{}' (not a regular table)", relkind);
                        }
                        continue;
                    }
                    
                    if (relname == null) {
                        log.warn("pg_class entry with oid={} has null relname, skipping", oid);
                        continue;
                    }
                    
                    // Convert OID back to table ID (OID = tableId + 100000)
                    long tableId = oid - 100000L;
                    
                    if (tableId < 1) {
                        // Skip system/catalog tables
                        continue;
                    }
                    
                    // Load schema metadata for this table
                    byte[] schemaKey = KeyEncoder.encodeSchemaKey(tableId, "schema");
                    KvStore.KvEntry schemaEntry = kvStore.get(schemaKey, currentTs);
                    
                    if (schemaEntry == null || schemaEntry.isDeleted()) {
                        log.warn("Schema metadata not found for table {} (id={}, oid={})", relname, tableId, oid);
                        continue;
                    }
                    
                    // Decode schema metadata
                    String json = new String(schemaEntry.getValue(), StandardCharsets.UTF_8);
                    TableMetadata metadata = objectMapper.readValue(json, TableMetadata.class);
                    
                    // Verify table name matches (sanity check)
                    if (!metadata.getTableName().equalsIgnoreCase(relname)) {
                        log.warn("Table name mismatch: pg_class says '{}' but schema says '{}' (id={}, oid={})", 
                            relname, metadata.getTableName(), tableId, oid);
                    }
                    
                    // Handle dropped tables
                    if (metadata.isDropped()) {
                        droppedCount++;
                        tempDroppedTablesCache.put(metadata.getTableId(), metadata);
                        log.info("Loaded dropped table: {} (id={}, droppedTs={})", 
                            metadata.getTableName(), metadata.getTableId(), metadata.getDroppedTimestamp());
                        continue;
                    }
                    
                    // Add to active cache
                    tablesByName.put(metadata.getTableName().toLowerCase(), metadata);
                    tablesById.put(metadata.getTableId(), metadata);
                    loadedCount++;
                    
                    log.info("✅ Loaded user table: {} (id={}, oid={})", metadata.getTableName(), metadata.getTableId(), oid);
                    
                    // Track truncated tables
                    if (metadata.getTruncateTimestamp() != null && metadata.getTruncateTimestamp() > 0) {
                        truncatedCount++;
                        tempTruncatedTablesCache.put(metadata.getTableId(), metadata.getTruncateTimestamp());
                        log.debug("Loaded table with truncate: {} (id={}, truncateTs={})", 
                            metadata.getTableName(), metadata.getTableId(), metadata.getTruncateTimestamp());
                    }
                    
                } catch (Exception e) {
                    log.error("  ⚠️  Failed to load table from pg_class entry: {}", e.getMessage());
                    log.debug("  Exception details:", e);
                }
            }
            
            log.info("Schema cache loaded ({} tables, {} dropped, {} truncated)", 
                loadedCount, droppedCount, truncatedCount);
            
            // Swap the cache maps with the rebuilt versions 
            droppedTablesCache = tempDroppedTablesCache;
            truncatedTablesCache = tempTruncatedTablesCache;
            
        } catch (Exception e) {
            log.error("  ⚠️  Failed to load tables from pg_class: {}", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Create PostgreSQL-compatible catalog tables
     * Note: Catalog tables are now managed by PgCatalogManager in KV store
     */
    private void createCatalogTables() {
        // Catalog tables are now managed by PgCatalogManager in KV store
        // They use negative table IDs and are stored like regular tables
        log.info("Using catalog tables from KV store (managed by PgCatalogManager)");
    }
    
    /**
     * Sync catalog tables with KV schema cache
     */
    private void syncCatalogTables() {
        try {
            log.info("Syncing catalog tables...");
            
            String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
            
            // Clear existing entries from internal keyspace (only user tables)
            session.execute("TRUNCATE " + internalKeyspace + ".pg_tables");
            session.execute("TRUNCATE " + internalKeyspace + ".information_schema_tables");
            session.execute("TRUNCATE " + internalKeyspace + ".information_schema_columns");
            
            // Populate from KV schema cache
            int tableCount = 0;
            for (TableMetadata table : tablesByName.values()) {
                // Sync to KV-based catalog only
                if (pgCatalogManager != null) {
                    pgCatalogManager.addTable(table);
                    tableCount++;
                }
            }
            
            log.info("Synced " + tableCount + " tables to catalog");
        } catch (Exception e) {
            log.error("  ⚠️  Failed to sync catalog tables: " + e.getMessage());
        }
    }
    
    /**
     * Clean up orphaned catalog entries (tables in pg_tables but not in schema cache)
     * This can happen if tables are dropped improperly or tests fail
     */
    public void cleanupOrphanedCatalogEntries() {
        try {
            log.info("Cleaning up orphaned catalog entries...");
            
            String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
            String schema = keyspaceConfig.getDefaultKeyspace();
            
            // Get all tables from pg_tables
            ResultSet rs = session.execute(
                "SELECT tablename FROM " + internalKeyspace + ".pg_tables WHERE schemaname = ?",
                schema);
            
            int orphanCount = 0;
            for (Row row : rs) {
                String tableName = row.getString("tablename");
                
                // Skip internal tables
                if (tableName.startsWith("kv_") || tableName.startsWith("tx_") ||
                    tableName.startsWith("pg_") || tableName.startsWith("information_schema_")) {
                    continue;
                }
                
                // Check if exists in schema cache (case-insensitive)
                if (tablesByName.get(tableName.toLowerCase()) == null) {
                    log.debug("Removing orphaned entry: " + tableName);
                    removeTableFromCatalog(tableName);
                    orphanCount++;
                }
            }
            
            if (orphanCount > 0) {
                log.info("Cleaned up " + orphanCount + " orphaned entries");
            } else {
                log.debug("  ✅ No orphaned entries found");
            }
        } catch (Exception e) {
            log.error("  ⚠️  Failed to cleanup orphaned entries: " + e.getMessage());
        }
    }
    
    /**
     * Add a table to the PostgreSQL catalog tables
     */
    private void addTableToCatalog(TableMetadata table) {
        try {
            String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
            String schema = "public";  // Use 'public' schema for PostgreSQL compatibility
            String tableName = table.getTableName();
            long tableOid = table.getTableId() + 100000;  // Offset to avoid conflicts with system OIDs
            long namespaceOid = 2200;  // OID for 'public' schema
            
            // Add to pg_class (core PostgreSQL catalog)
            session.execute(
                "INSERT INTO " + internalKeyspace + ".pg_class " +
                "(oid, relname, relnamespace, relkind, relowner, relhasindex) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                tableOid, tableName, namespaceOid, "r", 10L, !table.getIndexes().isEmpty());
            
            // Add to pg_tables in internal keyspace
            session.execute(
                "INSERT INTO " + internalKeyspace + ".pg_tables (schemaname, tablename, tableowner) " +
                "VALUES (?, ?, ?)",
                schema, tableName, "cassandra");
            
            // Add to information_schema.tables in internal keyspace
            session.execute(
                "INSERT INTO " + internalKeyspace + ".information_schema_tables (table_schema, table_name, table_type) " +
                "VALUES (?, ?, ?)",
                schema, tableName, "BASE TABLE");
            
            // Add columns to information_schema.columns in internal keyspace
            int position = 1;
            for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                session.execute(
                    "INSERT INTO " + internalKeyspace + ".information_schema_columns " +
                    "(table_schema, table_name, column_name, ordinal_position, data_type, is_nullable) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    schema, tableName, col.getName(), position++, 
                    col.getType(), col.isNullable() ? "YES" : "NO");
            }
            
            // Add columns to pg_attribute (for PostgreSQL compatibility)
            position = 1;
            for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                long typeOid = getTypeOidForDataType(col.getType());
                session.execute(
                    "INSERT INTO " + internalKeyspace + ".pg_attribute " +
                    "(attrelid, attname, atttypid, attnum, attnotnull, atthasdef) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    tableOid, col.getName(), typeOid, position++, 
                    !col.isNullable(), false);
            }
            
            log.debug("Added table {} to pg_catalog (oid={})", tableName, tableOid);
        } catch (Exception e) {
            log.error("  ⚠️  Failed to add table to catalog: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Map internal data types to PostgreSQL type OIDs
     */
    private long getTypeOidForDataType(String dataType) {
        String upperType = dataType.toUpperCase();
        switch (upperType) {
            case "INTEGER":
            case "INT":
            case "INT4":
                return 23;  // int4
            case "BIGINT":
            case "INT8":
                return 20;  // int8
            case "TEXT":
                return 25;  // text
            case "VARCHAR":
                return 1043; // varchar
            case "DOUBLE":
            case "FLOAT8":
                return 701; // float8
            case "BOOLEAN":
            case "BOOL":
                return 16;  // bool
            case "DATE":
                return 1082; // date
            case "TIME":
                return 1083; // time
            case "TIMESTAMP":
            case "TIMESTAMPTZ":
                return 1114; // timestamp
            case "INTERVAL":
                return 1186; // interval
            default:
                return 25;  // Default to TEXT for unknown types
        }
    }
    
    /**
     * Remove a table from the PostgreSQL catalog tables
     */
    private void removeTableFromCatalog(String tableName) {
        try {
            String internalKeyspace = internalKeyspaceManager.getInternalKeyspace();
            String schema = "public";  // Use 'public' schema for PostgreSQL compatibility
            
            // Remove from pg_class (need to find OID first, but for simplicity, delete by relname)
            // Note: Cassandra doesn't support DELETE with WHERE on non-PK, so we need to query first
            var rs = session.execute(
                "SELECT oid FROM " + internalKeyspace + ".pg_class WHERE relname = ? ALLOW FILTERING",
                tableName);
            for (var row : rs) {
                long oid = row.getLong("oid");
                session.execute("DELETE FROM " + internalKeyspace + ".pg_class WHERE oid = ?", oid);
            }
            
            session.execute("DELETE FROM " + internalKeyspace + ".pg_tables WHERE schemaname = ? AND tablename = ?", 
                schema, tableName);
            session.execute("DELETE FROM " + internalKeyspace + ".information_schema_tables WHERE table_schema = ? AND table_name = ?",
                schema, tableName);
            session.execute("DELETE FROM " + internalKeyspace + ".information_schema_columns WHERE table_schema = ? AND table_name = ?",
                schema, tableName);
                
            log.debug("Removed table {} from pg_catalog", tableName);
        } catch (Exception e) {
            log.error("  ⚠️  Failed to remove table from catalog: " + e.getMessage());
        }
    }
    
    /**
     * Allocate a new table ID (atomic using LWT)
     */
    public long allocateTableId() {
        byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_TABLE_ID, TABLE_ID_SEQUENCE);
        return allocateSequenceValueAtomically(seqKey, 1L);
    }
    
    /**
     * Allocate a new index ID for a table (atomic using LWT)
     */
    public long allocateIndexId(long tableId) {
        String seqName = "index_id_seq";
        byte[] seqKey = KeyEncoder.encodeSequenceKey(tableId, seqName);
        return allocateSequenceValueAtomically(seqKey, 1L);
    }
    
    /**
     * Allocate a new rowid for a table (used for tables without explicit primary keys).
     * 
     * This uses an Accord-backed sequence stored in the KV store to ensure:
     * - Uniqueness across distributed nodes
     * - Monotonically increasing IDs
     * - Transactional consistency
     * 
     * @param tableId The table ID
     * @return A unique rowid for this table
     */
    /**
     * Allocate next rowid for a table (atomic using LWT).
     * 
     * Uses LWT for atomic sequence allocation, similar to TimestampOracle.
     * This ensures safe sequence allocation even with multiple coordinator instances.
     */
    public long allocateRowId(long tableId) {
        String seqName = "rowid_seq";
        byte[] seqKey = KeyEncoder.encodeSequenceKey(tableId, seqName);
        return allocateSequenceValueAtomically(seqKey, 1L);
    }
    
    /**
     * Get a per-table lock object for sequence allocation.
     * This reduces contention by allowing different tables to allocate sequences concurrently.
     */
    private Object getSequenceLock(long tableId) {
        return sequenceLocks.computeIfAbsent(tableId, k -> new Object());
    }
    
    /**
     * Get a per-sequence lock object for sequence allocation (by sequence key).
     * This prevents concurrent threads from reading the same version and inserting duplicates.
     */
    private Object getSequenceLock(byte[] seqKey) {
        // Use hash code of the key as the lock identifier
        int keyHash = ByteBuffer.wrap(seqKey).hashCode();
        return sequenceKeyLocks.computeIfAbsent(keyHash, k -> new Object());
    }
    
    /**
     * Atomically allocate the next sequence value using Accord transactions for CAS semantics.
     * This ensures safe sequence allocation even with multiple coordinator instances, similar to TimestampOracle.
     * 
     * Uses Accord transactions to atomically:
     * 1. Read the latest committed sequence value
     * 2. Verify it matches what we expect using BLOB value comparison (CAS check)
     * 3. Insert a new version with incremented value
     * 
     * This is more performant and safer than LWT + post-check because:
     * - Everything happens atomically in one transaction
     * - No race condition between insert and verification
     * - No risk of crashing between insert and verification
     * - Uses proper CAS with BLOB value comparison
     * 
     * Note: Uses per-sequence synchronization to prevent concurrent threads from reading the same
     * value and both passing the CAS check. While Accord provides atomicity, without synchronization
     * multiple threads can read the same value, both pass the CAS check, and both insert the same nextValue.
     * 
     * @param seqKey The sequence key (encoded using KeyEncoder.encodeSequenceKey)
     * @param initialValue The initial value to use if the sequence doesn't exist
     * @return The allocated sequence value (the value that was allocated, which is currentValue + 1)
     */
    private long allocateSequenceValueAtomically(byte[] seqKey, long initialValue) {
        // Use per-sequence lock to prevent concurrent threads from reading the same value
        // and both passing the CAS check. This is necessary because even with BLOB comparison,
        // multiple threads can read the same value outside the transaction, then both pass
        // the CAS check inside the transaction (due to snapshot isolation).
        Object lock = getSequenceLock(seqKey);
        
        synchronized (lock) {
            final int MAX_RETRIES = 10;
            int retryCount = 0;
            String keyspace = keyspaceConfig.getDefaultKeyspace();
            String keyHex = bytesToHex(seqKey);
            
            while (retryCount < MAX_RETRIES) {
                try {
                    // Step 1: Read current sequence value (outside transaction for calculation)
                    // We'll verify this value atomically in the Accord transaction using BLOB comparison
                    long readTs = timestampOracle.getCurrentTimestamp();
                    KvStore.KvEntry entry = kvStore.get(seqKey, readTs);
                    
                    long currentValue;
                    boolean needsInit = false;
                    byte[] currentValueBytes = null;
                    
                    if (entry == null) {
                        needsInit = true;
                        currentValue = initialValue - 1; // Will be incremented to initialValue
                    } else {
                        ByteBuffer buffer = ByteBuffer.wrap(entry.getValue());
                        currentValue = buffer.getLong();
                        currentValueBytes = entry.getValue(); // Keep original bytes for comparison
                    }
                    
                    long nextValue = currentValue + 1;
                    long newTs = timestampOracle.allocateStartTimestamp();
                    long commitTs = timestampOracle.allocateCommitTimestamp();
                    UUID txId = UUID.randomUUID();
                    byte[] newValueBytes = ByteBuffer.allocate(8).putLong(nextValue).array();
                    
                    // Step 2: Use Accord transaction with BLOB value comparison for CAS
                    // We read the value INSIDE the transaction and compare it atomically
                    if (needsInit) {
                        // For initialization: use Accord transaction to atomically check and insert
                        StringBuilder accordTx = new StringBuilder("BEGIN TRANSACTION\n");
                        accordTx.append(String.format(
                            "  LET existing = (SELECT ts, value, commit_ts FROM %s.kv_store WHERE key = 0x%s LIMIT 1);\n",
                            keyspace, keyHex
                        ));
                        accordTx.append("  IF existing IS NULL THEN\n");
                        accordTx.append(String.format(
                            "    INSERT INTO %s.kv_store (key, ts, value, tx_id, commit_ts, deleted) " +
                            "    VALUES (0x%s, %d, 0x%s, %s, %d, false);\n",
                            keyspace, keyHex, newTs, bytesToHex(newValueBytes), txId.toString(), commitTs
                        ));
                        accordTx.append("  END IF\n");
                        accordTx.append("COMMIT TRANSACTION");
                        
                        String accordQuery = accordTx.toString();
                        log.debug("Executing Accord transaction for sequence initialization (key={}, value={}):\n{}",
                            keyHex, nextValue, accordQuery);
                        
                        SimpleStatement accordStmt = SimpleStatement.newInstance(accordQuery)
                            .setConsistencyLevel(com.datastax.oss.driver.api.core.ConsistencyLevel.SERIAL);
                        session.execute(accordStmt);
                    } else {
                        // For increment: use Accord transaction with BLOB value comparison for CAS
                        // We read the latest value INSIDE the transaction and compare it to what we expect
                        // This ensures atomicity - if another transaction incremented it, the comparison will fail
                        StringBuilder accordTx = new StringBuilder("BEGIN TRANSACTION\n");
                        accordTx.append(String.format(
                            "  LET current = (SELECT ts, value, commit_ts FROM %s.kv_store WHERE key = 0x%s LIMIT 1);\n",
                            keyspace, keyHex
                        ));
                        // CAS check: verify the current value (read inside transaction) matches what we read outside
                        // This ensures no other transaction incremented it between our read and this transaction
                        // Note: We compare the value read INSIDE the transaction to what we read OUTSIDE
                        // If they match, we can safely increment
                        accordTx.append(String.format(
                            "  IF current IS NOT NULL AND current.value = 0x%s THEN\n",
                            bytesToHex(currentValueBytes)
                        ));
                        // Insert new version with incremented value
                        accordTx.append(String.format(
                            "    INSERT INTO %s.kv_store (key, ts, value, tx_id, commit_ts, deleted) " +
                            "    VALUES (0x%s, %d, 0x%s, %s, %d, false);\n",
                            keyspace, keyHex, newTs, bytesToHex(newValueBytes), txId.toString(), commitTs
                        ));
                        accordTx.append("  END IF\n");
                        accordTx.append("COMMIT TRANSACTION");
                        
                        String accordQuery = accordTx.toString();
                        log.debug("Executing Accord transaction for sequence increment (key={}, current={}, next={}):\n{}",
                            keyHex, currentValue, nextValue, accordQuery);
                        
                        SimpleStatement accordStmt = SimpleStatement.newInstance(accordQuery)
                            .setConsistencyLevel(com.datastax.oss.driver.api.core.ConsistencyLevel.SERIAL);
                        session.execute(accordStmt);
                    }
                    
                    // Step 3: Verify the transaction succeeded by reading the sequence value
                    // Accord transactions don't return [applied] for BEGIN TRANSACTION,
                    // so we verify by reading the sequence value
                    Thread.sleep(10); // Small delay to ensure consistency
                    KvStore.KvEntry verifyEntry = kvStore.get(seqKey, timestampOracle.getCurrentTimestamp());
                    
                    log.debug("Verification read (key={}): verifyEntry={}, expected nextValue={}", 
                        keyHex, verifyEntry != null ? "exists" : "null", nextValue);
                    
                    if (verifyEntry != null) {
                        ByteBuffer verifyBuffer = ByteBuffer.wrap(verifyEntry.getValue());
                        long verifyValue = verifyBuffer.getLong();
                        
                        if (verifyValue == nextValue) {
                            // Success! The Accord transaction atomically verified and inserted
                            log.debug("✅ Sequence allocated successfully: {} -> {}", currentValue, nextValue);
                            return nextValue;
                        } else if (verifyValue > nextValue) {
                            // Another transaction incremented it further - our CAS check failed, retry
                            log.debug("Sequence was incremented by another transaction: expected={}, actual={}, retrying...",
                                nextValue, verifyValue);
                            retryCount++;
                            if (retryCount < MAX_RETRIES) {
                                Thread.sleep(1 + (retryCount * 2));
                            }
                            continue;
                        } else {
                            // Value is less than expected - CAS check failed or our insert didn't succeed, retry
                            log.debug("CAS check failed: expected nextValue={}, actual={}, retrying...",
                                nextValue, verifyValue);
                            retryCount++;
                            if (retryCount < MAX_RETRIES) {
                                Thread.sleep(1 + (retryCount * 2));
                            }
                            continue;
                        }
                    } else if (needsInit) {
                        // Initialization failed - retry
                        log.debug("Sequence initialization failed, retrying...");
                        retryCount++;
                        if (retryCount < MAX_RETRIES) {
                            Thread.sleep(1 + (retryCount * 2));
                        }
                        continue;
                    } else {
                        // No entry found - this shouldn't happen for increment, but retry
                        log.warn("Sequence entry not found after increment, retrying...");
                        retryCount++;
                        if (retryCount < MAX_RETRIES) {
                            Thread.sleep(1 + (retryCount * 2));
                        }
                        continue;
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during sequence allocation", e);
                } catch (Exception e) {
                    log.error("Failed to allocate sequence value (attempt {}): {}", retryCount + 1, e.getMessage(), e);
                    retryCount++;
                    
                    if (retryCount >= MAX_RETRIES) {
                        throw new RuntimeException("Failed to allocate sequence value after " + MAX_RETRIES + " attempts", e);
                    }
                    
                    try {
                        Thread.sleep(1 + (retryCount * 2));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during sequence allocation", ie);
                    }
                }
            }
            
            throw new RuntimeException("Failed to allocate sequence value after " + MAX_RETRIES + " retries");
        }
    }
    
    /**
     * Helper method to convert byte array to hex string for Accord queries
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Update sequence to be at least the given value
     * This is used when explicit values are inserted into SERIAL columns
     */
    public void updateSequenceIfGreater(long tableId, long minValue) {
        String seqName = "rowid_seq";
        byte[] seqKey = KeyEncoder.encodeSequenceKey(tableId, seqName);
        
        int maxRetries = 10;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            long currentTs = timestampOracle.getCurrentTimestamp();
            KvStore.KvEntry entry = kvStore.get(seqKey, currentTs);
            
            long currentValue = 0;
            if (entry != null) {
                ByteBuffer buffer = ByteBuffer.wrap(entry.getValue());
                currentValue = buffer.getLong();
            }
            
            // Only update if the provided value is greater
            if (minValue > currentValue) {
                byte[] newValue = ByteBuffer.allocate(8).putLong(minValue).array();
                kvStore.put(seqKey, newValue, currentTs, UUID.randomUUID(), currentTs, false);
                log.debug("  📈 Updated sequence to " + minValue + " (was " + currentValue + ")");
            }
            
            return;
        }
    }
    
    /**
     * Create a new table
     */
    public TableMetadata createTable(String tableName, List<TableMetadata.ColumnMetadata> columns,
                                    List<String> primaryKeyColumns) {
        return createTable(tableName, columns, primaryKeyColumns, new ArrayList<>(), new ArrayList<>());
    }
    
    /**
     * Create a new table with constraints
     */
    public TableMetadata createTable(String tableName, List<TableMetadata.ColumnMetadata> columns,
                                    List<String> primaryKeyColumns,
                                    List<TableMetadata.UniqueConstraint> uniqueConstraints,
                                    List<TableMetadata.ForeignKeyConstraint> foreignKeyConstraints) {
        // Check if table already exists
        if (tablesByName.containsKey(tableName.toLowerCase())) {
            throw new IllegalArgumentException("Table already exists: " + tableName);
        }
        
        // Validate foreign key constraints
        for (TableMetadata.ForeignKeyConstraint fk : foreignKeyConstraints) {
            TableMetadata referencedTable = getTable(fk.getReferencedTable());
            if (referencedTable == null) {
                throw new IllegalArgumentException("Referenced table does not exist: " + fk.getReferencedTable());
            }
            
            // Validate referenced columns exist
            for (String refCol : fk.getReferencedColumns()) {
                if (referencedTable.getColumn(refCol) == null) {
                    throw new IllegalArgumentException("Referenced column does not exist: " + 
                        fk.getReferencedTable() + "." + refCol);
                }
            }
            
            // Validate column count matches
            if (fk.getColumns().size() != fk.getReferencedColumns().size()) {
                throw new IllegalArgumentException("Foreign key column count mismatch: " + 
                    fk.getColumns().size() + " vs " + fk.getReferencedColumns().size());
            }
        }
        
        // Allocate table ID
        long tableId = allocateTableId();
        
        // Create table metadata
        TableMetadata metadata = new TableMetadata(
            tableId,
            tableName,
            columns,
            primaryKeyColumns,
            new ArrayList<>(),  // No indexes initially
            uniqueConstraints,
            foreignKeyConstraints,
            1,  // Version 1
            null,  // No truncate timestamp
            null   // Not dropped
        );
        
        // Store schema
        storeSchema(metadata);
        
        // Update cache
        tablesByName.put(tableName.toLowerCase(), metadata);
        tablesById.put(tableId, metadata);
        
        // Add to pg_catalog
        if (pgCatalogManager != null) {
            pgCatalogManager.addTable(metadata);
        }
        
        log.info("Created table: " + tableName + " (id=" + tableId + ") with " + 
                 uniqueConstraints.size() + " UNIQUE and " + 
                 foreignKeyConstraints.size() + " FOREIGN KEY constraints");
        return metadata;
    }
    
    /**
     * Drop a table (lazy drop - marks as dropped, vacuum cleans up later)
     * 
     * This is an O(1) operation similar to TRUNCATE:
     * 1. Set droppedTimestamp in metadata
     * 2. Remove from in-memory cache (so getTable() returns null)
     * 3. VacuumJob will eventually delete all data and schema metadata
     */
    public void dropTable(String tableName) {
        TableMetadata metadata = tablesByName.get(tableName.toLowerCase());
        if (metadata == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        // Mark as dropped with current timestamp
        long droppedTs = timestampOracle.allocateCommitTimestamp();
        log.debug("Creating dropped metadata with timestamp: {}", droppedTs);
        TableMetadata droppedMetadata = metadata.withDroppedTimestamp(droppedTs);
        
        // Update schema in KV store (keep metadata for vacuum job)
        storeSchema(droppedMetadata);
        
        // Remove from in-memory active cache (so getTable() returns null)
        tablesByName.remove(tableName.toLowerCase());
        tablesById.remove(metadata.getTableId());
        
        // Add to dropped tables cache (for vacuum job)
        droppedTablesCache.put(metadata.getTableId(), droppedMetadata);
        
        // Remove from old Cassandra catalog
        removeTableFromCatalog(tableName);
        
        // Remove from pg_catalog in KV store
        if (pgCatalogManager != null) {
            pgCatalogManager.removeTable(tableName);
        }
        
        log.info("Dropped table: " + tableName + " (id=" + metadata.getTableId() + ", droppedTs=" + droppedTs + ")");
        log.debug("Table marked as dropped - VacuumJob will clean up data");
    }
    
    /**
     * Truncate a table (fast O(1) operation using table versioning).
     * 
     * Instead of deleting all rows (which would be O(n) and slow), we:
     * 1. Record a truncate timestamp in table metadata
     * 2. Reads will skip data with commit_ts < truncate_ts
     * 3. Vacuum job will eventually clean up old data
     * 
     * This matches PostgreSQL's TRUNCATE behavior - it's fast and creates a new table version.
     * 
     * @param tableName The table to truncate
     * @return The truncate timestamp
     */
    public long truncateTable(String tableName) {
        TableMetadata metadata = tablesByName.get(tableName.toLowerCase());
        if (metadata == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        // Get truncate timestamp (current time)
        long truncateTs = timestampOracle.getCurrentTimestamp();
        
        log.debug("🗑️  Truncating table: " + tableName + " at ts=" + truncateTs);
        
        // Create new metadata with truncate timestamp
        TableMetadata newMetadata = new TableMetadata(
            metadata.getTableId(),
            metadata.getTableName(),
            metadata.getColumns(),
            metadata.getPrimaryKeyColumns(),
            metadata.getIndexes(),
            metadata.getVersion() + 1,  // Increment version
            truncateTs  // Set truncate timestamp
        );
        
        // Store updated schema
        storeSchema(newMetadata);
        
        // Update active cache
        tablesByName.put(tableName.toLowerCase(), newMetadata);
        tablesById.put(metadata.getTableId(), newMetadata);
        
        // Add to truncated tables cache (for vacuum job)
        truncatedTablesCache.put(metadata.getTableId(), truncateTs);
        
        log.info("Truncated table: " + tableName + " (version " + metadata.getVersion() + 
                         " -> " + newMetadata.getVersion() + ", truncate_ts=" + truncateTs + ")");
        
        return truncateTs;
    }
    
    /**
     * Create an index on a table
     */
    public TableMetadata.IndexMetadata createIndex(String tableName, String indexName,
                                                   List<String> columns, boolean unique) {
        TableMetadata table = getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        // Check if index already exists
        if (table.getIndex(indexName) != null) {
            throw new IllegalArgumentException("Index already exists: " + indexName);
        }
        
        // Allocate index ID
        long indexId = allocateIndexId(table.getTableId());
        
        // Create index metadata with building=true
        TableMetadata.IndexMetadata indexMetadata = new TableMetadata.IndexMetadata(
            indexId,
            indexName,
            columns,
            unique,
            true,  // Mark as building during backfill
            0.0    // Selectivity is 0.0 by default
        );
        
        // Update table metadata
        List<TableMetadata.IndexMetadata> newIndexes = new ArrayList<>(table.getIndexes());
        newIndexes.add(indexMetadata);
        
        TableMetadata updatedTable = new TableMetadata(
            table.getTableId(),
            table.getTableName(),
            table.getColumns(),
            table.getPrimaryKeyColumns(),
            newIndexes,
            table.getUniqueConstraints(),
            table.getForeignKeyConstraints(),
            table.getVersion() + 1,
            table.getTruncateTimestamp(),
            table.getDroppedTimestamp()
        );
        
        // Store updated schema
        storeSchema(updatedTable);
        
        // Update cache
        tablesByName.put(tableName.toLowerCase(), updatedTable);
        tablesById.put(table.getTableId(), updatedTable);
        
        // Add to pg_catalog
        if (pgCatalogManager != null) {
            pgCatalogManager.addIndex(updatedTable, indexMetadata);
        }
        
        log.debug("✅ Created index: " + indexName + " on " + tableName + " (id=" + indexId + ", building=true)");
        return indexMetadata;
    }
    
    /**
     * Mark an index as ready (building=false) after backfill completes
     */
    public void markIndexReady(String tableName, String indexName) {
        TableMetadata table = getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        TableMetadata.IndexMetadata oldIndex = table.getIndex(indexName);
        if (oldIndex == null) {
            throw new IllegalArgumentException("Index does not exist: " + indexName);
        }
        
        // Create updated index list with building=false
        List<TableMetadata.IndexMetadata> newIndexes = new ArrayList<>();
        for (TableMetadata.IndexMetadata idx : table.getIndexes()) {
            if (idx.getName().equalsIgnoreCase(indexName)) {
                // Replace with non-building version
                newIndexes.add(new TableMetadata.IndexMetadata(
                    idx.getIndexId(),
                    idx.getName(),
                    idx.getColumns(),
                    idx.isUnique(),
                    false,  // Mark as ready
                    idx.getSelectivity()    // Selectivity is the same as the old index
                ));
            } else {
                newIndexes.add(idx);
            }
        }
        
        TableMetadata updatedTable = new TableMetadata(
            table.getTableId(),
            table.getTableName(),
            table.getColumns(),
            table.getPrimaryKeyColumns(),
            newIndexes,
            table.getUniqueConstraints(),
            table.getForeignKeyConstraints(),
            table.getVersion() + 1,
            table.getTruncateTimestamp(),
            table.getDroppedTimestamp()
        );
        
        // Store updated schema
        storeSchema(updatedTable);
        
        // Update cache
        tablesByName.put(tableName.toLowerCase(), updatedTable);
        tablesById.put(table.getTableId(), updatedTable);
        
        log.debug("✅ Marked index as ready: " + indexName + " on " + tableName);
    }
    
    /**
     * Drop an index
     */
    public void dropIndex(String tableName, String indexName) {
        TableMetadata table = getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        TableMetadata.IndexMetadata index = table.getIndex(indexName);
        if (index == null) {
            throw new IllegalArgumentException("Index does not exist: " + indexName);
        }
        
        // Update table metadata
        List<TableMetadata.IndexMetadata> newIndexes = new ArrayList<>(table.getIndexes());
        newIndexes.removeIf(idx -> idx.getName().equalsIgnoreCase(indexName));
        
        TableMetadata updatedTable = new TableMetadata(
            table.getTableId(),
            table.getTableName(),
            table.getColumns(),
            table.getPrimaryKeyColumns(),
            newIndexes,
            table.getVersion() + 1
        );
        
        // Store updated schema
        storeSchema(updatedTable);
        
        // Update cache
        tablesByName.put(tableName.toLowerCase(), updatedTable);
        tablesById.put(table.getTableId(), updatedTable);
        
        // TODO: Delete all index entries
        
        log.debug("✅ Dropped index: " + indexName + " from " + tableName);
    }
    
    /**
     * Add a column to an existing table
     */
    public void addColumn(String tableName, TableMetadata.ColumnMetadata newColumn) {
        TableMetadata table = getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        // Check if column already exists
        if (table.getColumn(newColumn.getName()) != null) {
            throw new IllegalArgumentException("Column already exists: " + newColumn.getName());
        }
        
        // Create updated column list
        List<TableMetadata.ColumnMetadata> newColumns = new ArrayList<>(table.getColumns());
        newColumns.add(newColumn);
        
        // Create updated table metadata
        TableMetadata updatedTable = new TableMetadata(
            table.getTableId(),
            table.getTableName(),
            newColumns,
            table.getPrimaryKeyColumns(),
            table.getIndexes(),
            table.getUniqueConstraints(),
            table.getForeignKeyConstraints(),
            table.getVersion() + 1,
            table.getTruncateTimestamp(),
            table.getDroppedTimestamp()
        );
        
        // Store updated schema
        storeSchema(updatedTable);
        
        // Update cache
        tablesByName.put(tableName.toLowerCase(), updatedTable);
        tablesById.put(table.getTableId(), updatedTable);
        
        // Update pg_catalog
        if (pgCatalogManager != null) {
            pgCatalogManager.addColumnToCatalog(table.getTableId(), newColumn, newColumns.size());
        }
        
        log.info("Added column {} to table {}", newColumn.getName(), tableName);
    }
    
    /**
     * Drop a column from an existing table
     */
    public void dropColumn(String tableName, String columnName) {
        TableMetadata table = getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table does not exist: " + tableName);
        }
        
        // Check if column exists
        if (table.getColumn(columnName) == null) {
            throw new IllegalArgumentException("Column does not exist: " + columnName);
        }
        
        // Prevent dropping primary key columns
        if (table.getPrimaryKeyColumns().contains(columnName.toLowerCase())) {
            throw new IllegalArgumentException("Cannot drop primary key column: " + columnName);
        }
        
        // Create updated column list
        List<TableMetadata.ColumnMetadata> newColumns = new ArrayList<>();
        for (TableMetadata.ColumnMetadata col : table.getColumns()) {
            if (!col.getName().equalsIgnoreCase(columnName)) {
                newColumns.add(col);
            }
        }
        
        // Create updated table metadata
        TableMetadata updatedTable = new TableMetadata(
            table.getTableId(),
            table.getTableName(),
            newColumns,
            table.getPrimaryKeyColumns(),
            table.getIndexes(),
            table.getUniqueConstraints(),
            table.getForeignKeyConstraints(),
            table.getVersion() + 1,
            table.getTruncateTimestamp(),
            table.getDroppedTimestamp()
        );
        
        // Store updated schema
        storeSchema(updatedTable);
        
        // Update cache
        tablesByName.put(tableName.toLowerCase(), updatedTable);
        tablesById.put(table.getTableId(), updatedTable);
        
        // Update pg_catalog
        if (pgCatalogManager != null) {
            pgCatalogManager.removeColumnFromCatalog(table.getTableId(), columnName);
        }
        
        log.info("Dropped column {} from table {}", columnName, tableName);
    }
    
    /**
     * Store schema metadata
     */
    private void storeSchema(TableMetadata metadata) {
        try {
            // Serialize to JSON
            String json = objectMapper.writeValueAsString(metadata);
            byte[] value = json.getBytes(StandardCharsets.UTF_8);
            
            // Store with schema key - regular put is fine
            // Accord handles transactions via BEGIN TRANSACTION
            byte[] key = KeyEncoder.encodeSchemaKey(metadata.getTableId(), "schema");
            long currentTs = timestampOracle.getCurrentTimestamp();
            
            kvStore.put(key, value, currentTs, UUID.randomUUID(), currentTs, false);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to store schema: " + e.getMessage(), e);
        }
    }
    
    /**
     * Load schema metadata
     */
    private TableMetadata loadSchema(long tableId) {
        try {
            byte[] key = KeyEncoder.encodeSchemaKey(tableId, "schema");
            long currentTs = timestampOracle.getCurrentTimestamp();
            
            KvStore.KvEntry entry = kvStore.get(key, currentTs);
            if (entry == null) {
                return null;
            }
            
            String json = new String(entry.getValue(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, TableMetadata.class);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to load schema: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get table metadata by name
     * If alwaysReload is enabled, this will read directly from KV storage
     */
    public TableMetadata getTable(String tableName) {
        if (alwaysReload) {
            // For debugging - always read from KV storage
            TableMetadata cached = tablesByName.get(tableName.toLowerCase());
            if (cached != null) {
                TableMetadata fresh = loadSchema(cached.getTableId());
                if (fresh != null && !fresh.isDropped()) {
                    return fresh;
                }
            }
            return null;
        }
        return tablesByName.get(tableName.toLowerCase());
    }
    
    /**
     * Get table metadata by ID
     * If alwaysReload is enabled, this will read directly from KV storage
     */
    public TableMetadata getTable(long tableId) {
        if (alwaysReload) {
            // For debugging - always read from KV storage
            TableMetadata fresh = loadSchema(tableId);
            if (fresh != null && !fresh.isDropped()) {
                return fresh;
            }
            return null;
        }
        return tablesById.get(tableId);
    }
    
    /**
     * Check if table exists
     */
    public boolean tableExists(String tableName) {
        return getTable(tableName) != null;
    }
    
    /**
     * Get all table names
     */
    public List<String> getAllTableNames() {
        return new ArrayList<>(tablesByName.keySet());
    }
    
    /**
     * Get all tables (active only, excludes dropped)
     * If cache is empty and we haven't attempted to load yet, trigger a load.
     * Also ensures essential catalog tables are registered.
     */
    public Collection<TableMetadata> getAllTables() {
        // Ensure essential catalog tables are registered (for querying)
        ensureCatalogTablesRegistered();
        
        // Lazy load: if we have no USER tables (only catalog tables) and we haven't tried to load yet, do it now
        long userTableCount = tablesByName.values().stream()
            .filter(t -> t.getTableId() > 0)  // User tables have positive IDs
            .count();
        
        if (userTableCount == 0 && hasAttemptedLoad.compareAndSet(false, true)) {
            log.info("No user tables in cache, triggering schema load from pg_class catalog...");
            try {
                refreshSchemaFromKvStorage();
            } catch (Exception e) {
                log.warn("Failed to load schema during lazy load: {}", e.getMessage());
                // Reset flag so we can try again later
                hasAttemptedLoad.set(false);
            }
        }
        return tablesByName.values();
    }
    
    /**
     * Ensure essential catalog tables are registered so they can be queried.
     * This is a fallback in case PgCatalogManager hasn't registered them yet.
     */
    private void ensureCatalogTablesRegistered() {
        // Only register if not already registered (to avoid overwriting)
        if (tablesById.get(-2L) == null) {
            try {
                PgCatalogTable pgCatalogTable = PgCatalogTable.pgClass();
                List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
                for (PgCatalogTable.Column col : pgCatalogTable.getColumns()) {
                    columns.add(new TableMetadata.ColumnMetadata(
                        col.getName(),
                        col.getType(),
                        !col.isNotNull()
                    ));
                }
                
                TableMetadata pgClass = new TableMetadata(
                    pgCatalogTable.getTableId(),
                    pgCatalogTable.getTableName(),
                    columns,
                    pgCatalogTable.getPrimaryKey(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    1,
                    null,
                    null
                );
                
                registerCatalogTable(pgClass);
                log.debug("Registered pg_class catalog table for querying");
            } catch (Exception e) {
                log.debug("Failed to register pg_class catalog table: {}", e.getMessage());
            }
        }
        
        // Also register pg_tables and pg_indexes if needed
        if (tablesById.get(PgCatalogTable.pgTables().getTableId()) == null) {
            try {
                PgCatalogTable pgTablesDef = PgCatalogTable.pgTables();
                List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
                for (PgCatalogTable.Column col : pgTablesDef.getColumns()) {
                    columns.add(new TableMetadata.ColumnMetadata(
                        col.getName(),
                        col.getType(),
                        !col.isNotNull()
                    ));
                }
                
                TableMetadata pgTables = new TableMetadata(
                    pgTablesDef.getTableId(),
                    pgTablesDef.getTableName(),
                    columns,
                    pgTablesDef.getPrimaryKey(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    1,
                    null,
                    null
                );
                
                registerCatalogTable(pgTables);
                log.debug("Registered pg_tables catalog table for querying");
            } catch (Exception e) {
                log.debug("Failed to register pg_tables catalog table: {}", e.getMessage());
            }
        }
        
        if (tablesById.get(PgCatalogTable.pgIndexes().getTableId()) == null) {
            try {
                PgCatalogTable pgIndexesDef = PgCatalogTable.pgIndexes();
                List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
                for (PgCatalogTable.Column col : pgIndexesDef.getColumns()) {
                    columns.add(new TableMetadata.ColumnMetadata(
                        col.getName(),
                        col.getType(),
                        !col.isNotNull()
                    ));
                }
                
                TableMetadata pgIndexes = new TableMetadata(
                    pgIndexesDef.getTableId(),
                    pgIndexesDef.getTableName(),
                    columns,
                    pgIndexesDef.getPrimaryKey(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    1,
                    null,
                    null
                );
                
                registerCatalogTable(pgIndexes);
                log.debug("Registered pg_indexes catalog table for querying");
            } catch (Exception e) {
                log.debug("Failed to register pg_indexes catalog table: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Get all dropped tables (for vacuum job cleanup).
     * Returns from in-memory cache - no KV store scan needed.
     */
    public List<TableMetadata> getAllDroppedTables() {
        return new ArrayList<>(droppedTablesCache.values());
    }
    
    /**
     * Get truncate timestamp for a table (for vacuum job cleanup).
     * Returns null if table has not been truncated.
     */
    public Long getTruncateTimestamp(long tableId) {
        return truncatedTablesCache.get(tableId);
    }
    
    /**
     * Get all truncated tables (for vacuum job cleanup).
     * Returns map of table_id -> truncate_timestamp.
     */
    public Map<Long, Long> getAllTruncatedTables() {
        return new HashMap<>(truncatedTablesCache);
    }
    
    /**
     * Remove a table from the dropped tables cache after vacuum completes.
     * This should be called by the vacuum job after successfully cleaning up a dropped table.
     */
    public void removeFromDroppedCache(long tableId) {
        TableMetadata removed = droppedTablesCache.remove(tableId);
        if (removed != null) {
            log.debug("Removed table from dropped cache: " + removed.getTableName() + " (id=" + tableId + ")");
        }
    }
    
    /**
     * Remove a table from the truncated tables cache after vacuum completes.
     * This should be called by the vacuum job after successfully cleaning up truncated data.
     */
    public void removeFromTruncatedCache(long tableId) {
        Long removed = truncatedTablesCache.remove(tableId);
        if (removed != null) {
            log.debug("Removed table from truncated cache: id=" + tableId + ", truncateTs=" + removed);
        }
    }
    
    /**
     * Refresh schema cache
     * @deprecated Use {@link #refreshSchemaFromKvStorage()} instead
     */
    @Deprecated
    public void refreshCache() {
        refreshSchemaFromKvStorage();
    }
    
    /**
     * Delete all data and index entries for a table
     */
    private void deleteTableData(TableMetadata metadata) {
        log.debug("🗑️  Deleting data for table: " + metadata.getTableName());
        
        long tableId = metadata.getTableId();
        
        // Delete primary index data (index_id = 0)
        byte[] primaryStartKey = KeyEncoder.createRangeStartKey(tableId, KeyEncoder.PRIMARY_INDEX_ID, null);
        byte[] primaryEndKey = KeyEncoder.createRangeEndKey(tableId, KeyEncoder.PRIMARY_INDEX_ID, null);
        deleteKeyRange(primaryStartKey, primaryEndKey);
        
        // Delete secondary index data
        for (TableMetadata.IndexMetadata index : metadata.getIndexes()) {
            byte[] indexStartKey = KeyEncoder.createRangeStartKey(tableId, index.getIndexId(), null);
            byte[] indexEndKey = KeyEncoder.createRangeEndKey(tableId, index.getIndexId(), null);
            deleteKeyRange(indexStartKey, indexEndKey);
            log.debug("   Deleted index: " + index.getName());
        }
        
        log.debug("   ✅ Table data deleted");
    }
    
    /**
     * Delete all keys in a range
     */
    private void deleteKeyRange(byte[] startKey, byte[] endKey) {
        // Scan all keys in range (using max timestamp to see all versions)
        List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, Long.MAX_VALUE, 100000);
        
        log.debug("   Deleting " + entries.size() + " entries");
        
        // Delete each key
        for (KvStore.KvEntry entry : entries) {
            kvStore.delete(entry.getKey());
        }
    }
    
    // ========================================
    // Sequence Management (PostgreSQL-compatible)
    // ========================================
    
    /**
     * Create a new sequence
     */
    public void createSequence(SequenceMetadata sequence) {
        String seqName = sequence.getSequenceName().toLowerCase();
        
        if (sequencesByName.containsKey(seqName)) {
            throw new IllegalArgumentException("Sequence already exists: " + seqName);
        }
        
        try {
            // Store sequence metadata
            byte[] metadataKey = KeyEncoder.encodeSchemaKey(GLOBAL_SEQUENCE_ID, "sequence_" + seqName);
            String json = objectMapper.writeValueAsString(sequence);
            byte[] value = json.getBytes(StandardCharsets.UTF_8);
            
            long ts = timestampOracle.getCurrentTimestamp();
            kvStore.put(metadataKey, value, ts, UUID.randomUUID(), ts, false);
            
            // Initialize sequence value to startValue - increment
            // (so first nextval() returns startValue)
            byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_SEQUENCE_ID, seqName);
            long initialValue = sequence.getStartValue() - sequence.getIncrement();
            byte[] seqValue = ByteBuffer.allocate(8).putLong(initialValue).array();
            kvStore.put(seqKey, seqValue, ts, UUID.randomUUID(), ts, false);
            
            // Cache it
            sequencesByName.put(seqName, sequence);
            
            log.info("Created sequence: " + seqName);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create sequence: " + e.getMessage(), e);
        }
    }
    
    /**
     * Drop a sequence
     */
    public void dropSequence(String sequenceName) {
        String seqName = sequenceName.toLowerCase();
        
        if (!sequencesByName.containsKey(seqName)) {
            throw new IllegalArgumentException("Sequence does not exist: " + seqName);
        }
        
        try {
            // Delete metadata
            byte[] metadataKey = KeyEncoder.encodeSchemaKey(GLOBAL_SEQUENCE_ID, "sequence_" + seqName);
            kvStore.delete(metadataKey);
            
            // Delete sequence value
            byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_SEQUENCE_ID, seqName);
            kvStore.delete(seqKey);
            
            // Remove from cache
            sequencesByName.remove(seqName);
            
            log.info("Dropped sequence: " + seqName);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to drop sequence: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get sequence metadata
     */
    public SequenceMetadata getSequence(String sequenceName) {
        return sequencesByName.get(sequenceName.toLowerCase());
    }
    
    /**
     * Get next value from a sequence (like PostgreSQL's nextval())
     */
    public long nextval(String sequenceName) {
        String seqName = sequenceName.toLowerCase();
        SequenceMetadata sequence = sequencesByName.get(seqName);
        
        if (sequence == null) {
            throw new IllegalArgumentException("Sequence does not exist: " + seqName);
        }
        
        byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_SEQUENCE_ID, seqName);
        
        // CAS loop for atomic increment
        int maxRetries = 10;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            long currentTs = timestampOracle.getCurrentTimestamp();
            KvStore.KvEntry entry = kvStore.get(seqKey, currentTs);
            
            if (entry == null) {
                // Should not happen - sequence was initialized at creation
                throw new IllegalStateException("Sequence value not found: " + seqName);
            }
            
            ByteBuffer buffer = ByteBuffer.wrap(entry.getValue());
            long currentValue = buffer.getLong();
            
            // Calculate next value
            Long nextValue = sequence.getNextValue(currentValue);
            if (nextValue == null) {
                throw new RuntimeException("Sequence " + seqName + " has reached its " +
                    (sequence.getIncrement() > 0 ? "maximum" : "minimum") + " value");
            }
            
            // Update sequence
            byte[] newValue = ByteBuffer.allocate(8).putLong(nextValue).array();
            kvStore.put(seqKey, newValue, currentTs, UUID.randomUUID(), currentTs, false);
            
            return nextValue;
        }
        
        throw new RuntimeException("Failed to allocate sequence value after " + maxRetries + " attempts");
    }
    
    /**
     * Get current value of a sequence without incrementing (like PostgreSQL's currval())
     */
    public long currval(String sequenceName) {
        String seqName = sequenceName.toLowerCase();
        SequenceMetadata sequence = sequencesByName.get(seqName);
        
        if (sequence == null) {
            throw new IllegalArgumentException("Sequence does not exist: " + seqName);
        }
        
        byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_SEQUENCE_ID, seqName);
        long currentTs = timestampOracle.getCurrentTimestamp();
        KvStore.KvEntry entry = kvStore.get(seqKey, currentTs);
        
        if (entry == null) {
            throw new IllegalStateException("Sequence value not found: " + seqName);
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(entry.getValue());
        return buffer.getLong();
    }
    
    /**
     * Set the current value of a sequence (like PostgreSQL's setval())
     */
    public void setval(String sequenceName, long value) {
        String seqName = sequenceName.toLowerCase();
        SequenceMetadata sequence = sequencesByName.get(seqName);
        
        if (sequence == null) {
            throw new IllegalArgumentException("Sequence does not exist: " + seqName);
        }
        
        // Validate bounds
        if (!sequence.isWithinBounds(value)) {
            throw new IllegalArgumentException("Value " + value + " is out of bounds for sequence " + seqName);
        }
        
        byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_SEQUENCE_ID, seqName);
        byte[] newValue = ByteBuffer.allocate(8).putLong(value).array();
        
        long ts = timestampOracle.getCurrentTimestamp();
        kvStore.put(seqKey, newValue, ts, UUID.randomUUID(), ts, false);
    }
    
    /**
     * List all sequences
     */
    public List<String> getAllSequenceNames() {
        return new ArrayList<>(sequencesByName.keySet());
    }
    
    /**
     * Register a catalog table (pg_catalog tables)
     * These tables have negative IDs and are stored in the KV store
     */
    public void registerCatalogTable(TableMetadata metadata) {
        String tableName = metadata.getTableName().toLowerCase();
        tablesByName.put(tableName, metadata);
        tablesById.put(metadata.getTableId(), metadata);
        log.debug("Registered catalog table: {} (id={})", metadata.getTableName(), metadata.getTableId());
    }
    
    // ========== View Management ==========
    
    /**
     * Create a new view (virtual or materialized)
     */
    public ViewMetadata createView(String viewName, String queryDefinition, 
                                   boolean materialized, List<ViewMetadata.ColumnInfo> columns) {
        viewName = viewName.toLowerCase();
        
        // Check if view already exists
        if (viewsByName.containsKey(viewName)) {
            throw new IllegalArgumentException("View already exists: " + viewName);
        }
        
        // Allocate view ID
        long viewId = allocateViewId();
        
        // Create view metadata
        ViewMetadata view = new ViewMetadata(viewId, viewName, queryDefinition, materialized, columns);
        
        // Store view metadata
        storeViewMetadata(view);
        
        // Update cache
        viewsByName.put(viewName, view);
        viewsById.put(viewId, view);
        
        log.info("✅ Created {} view: {} (id={})", 
                 materialized ? "MATERIALIZED" : "virtual", viewName, viewId);
        log.info("📊 View cache now contains {} views: {}", viewsByName.size(), viewsByName.keySet());
        
        return view;
    }
    
    /**
     * Get view by name
     */
    public ViewMetadata getView(String viewName) {
        String key = viewName.toLowerCase();
        ViewMetadata result = viewsByName.get(key);
        log.info("🔍 getView('{}') -> key='{}', found={}, cache size={}, cache keys={}", 
                 viewName, key, result != null, viewsByName.size(), viewsByName.keySet());
        return result;
    }
    
    /**
     * Get view by ID
     */
    public ViewMetadata getViewById(long viewId) {
        return viewsById.get(viewId);
    }
    
    /**
     * Get all views
     */
    public Collection<ViewMetadata> getAllViews() {
        return new ArrayList<>(viewsByName.values());
    }
    
    /**
     * Update view metadata (e.g., after inferring columns during materialization)
     */
    public void updateViewMetadata(ViewMetadata updatedView) {
        String viewName = updatedView.getViewName().toLowerCase();
        
        // Store updated metadata
        storeViewMetadata(updatedView);
        
        // Update cache
        viewsByName.put(viewName, updatedView);
        viewsById.put(updatedView.getViewId(), updatedView);
        
        log.debug("Updated view metadata: {}", viewName);
    }
    
    /**
     * Update view refresh timestamp
     */
    public void updateViewRefreshTimestamp(String viewName, long refreshTs) {
        viewName = viewName.toLowerCase();
        ViewMetadata view = viewsByName.get(viewName);
        if (view == null) {
            throw new IllegalArgumentException("View does not exist: " + viewName);
        }
        
        ViewMetadata updatedView = view.withRefreshTimestamp(refreshTs);
        storeViewMetadata(updatedView);
        
        // Update cache
        viewsByName.put(viewName, updatedView);
        viewsById.put(view.getViewId(), updatedView);
        
        log.debug("Updated refresh timestamp for view: {}", viewName);
    }
    
    /**
     * Drop a view
     */
    public void dropView(String viewName) {
        viewName = viewName.toLowerCase();
        ViewMetadata view = viewsByName.get(viewName);
        if (view == null) {
            throw new IllegalArgumentException("View does not exist: " + viewName);
        }
        
        // Mark as dropped
        long droppedTs = System.currentTimeMillis();
        ViewMetadata droppedView = view.withDroppedTimestamp(droppedTs);
        storeViewMetadata(droppedView);
        
        // Remove from cache
        viewsByName.remove(viewName);
        viewsById.remove(view.getViewId());
        
        log.info("✅ Dropped view: {} (id={})", viewName, view.getViewId());
    }
    
    /**
     * Allocate a new view ID
     * View IDs are offset by VIEW_ID_OFFSET to avoid collision with table IDs
     */
    private long allocateViewId() {
        byte[] seqKey = KeyEncoder.encodeSequenceKey(GLOBAL_VIEW_ID, VIEW_ID_SEQUENCE);
        
        // Use CAS loop to atomically increment
        int maxRetries = 10;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            long currentTs = timestampOracle.getCurrentTimestamp();
            KvStore.KvEntry entry = kvStore.get(seqKey, currentTs);
            
            if (entry == null) {
                // Lazy initialization: create sequence if it doesn't exist
                log.debug("Lazy initializing view ID sequence (attempt " + (attempt + 1) + ")...");
                try {
                    byte[] initialValue = ByteBuffer.allocate(8).putLong(1L).array();
                    kvStore.put(seqKey, initialValue, currentTs, null, currentTs, false);
                    log.debug("View ID sequence initialized");
                    return VIEW_ID_OFFSET + 1L; // Return first ID with offset
                } catch (Exception e) {
                    log.error("  ⚠️  Failed to initialize view sequence: " + e.getMessage());
                    // Fall back to in-memory counter
                    return VIEW_ID_OFFSET + (System.currentTimeMillis() % 1000000); // Use timestamp as fallback ID
                }
            }
            
            // Decode current value
            ByteBuffer buffer = ByteBuffer.wrap(entry.getValue());
            long currentId = buffer.getLong();
            long nextId = currentId + 1;
            
            log.debug("  🔄 Incrementing view sequence from " + currentId + " to " + nextId);
            
            // Regular put
            byte[] newValue = ByteBuffer.allocate(8).putLong(nextId).array();
            kvStore.put(seqKey, newValue, currentTs, UUID.randomUUID(), currentTs, false);
            
            log.debug("  ✅ Successfully allocated view ID: " + (VIEW_ID_OFFSET + currentId));
            return VIEW_ID_OFFSET + currentId;
        }
        
        // Max retries exceeded
        log.error("  ❌ Failed to allocate view ID after " + maxRetries + " attempts");
        throw new RuntimeException("Failed to allocate view ID: too many CAS conflicts");
    }
    
    /**
     * Store view metadata in KV store
     */
    private void storeViewMetadata(ViewMetadata view) {
        try {
            byte[] key = KeyEncoder.encodeViewMetadataKey(view.getViewId());
            byte[] value = objectMapper.writeValueAsBytes(view);
            
            long ts = timestampOracle.getCurrentTimestamp();
            kvStore.put(key, value, ts, UUID.randomUUID(), ts, false);
            
            log.debug("Stored view metadata: {} (id={})", view.getViewName(), view.getViewId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to store view metadata: " + e.getMessage(), e);
        }
    }
    
    /**
     * Load all views from KV store
     */
    private void loadViews() {
        try {
            byte[] startKey = KeyEncoder.createViewMetadataRangeStart();
            byte[] endKey = KeyEncoder.createViewMetadataRangeEnd();
            
            long readTs = timestampOracle.getCurrentTimestamp();
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, null);
            
            for (KvStore.KvEntry entry : entries) {
                try {
                    ViewMetadata view = objectMapper.readValue(entry.getValue(), ViewMetadata.class);
                    
                    // Skip dropped views
                    if (view.isDropped()) {
                        continue;
                    }
                    
                    viewsByName.put(view.getViewName().toLowerCase(), view);
                    viewsById.put(view.getViewId(), view);
                } catch (Exception e) {
                    log.warn("Failed to load view metadata: {}", e.getMessage());
                }
            }
            
            log.info("Loaded {} views from KV storage", viewsByName.size());
        } catch (Exception e) {
            log.error("Failed to load views: {}", e.getMessage());
        }
    }
    
    /**
     * Create an index on a materialized view
     */
    public ViewMetadata.IndexMetadata createIndexOnView(String viewName, String indexName, List<String> columns) {
        ViewMetadata view = getView(viewName);
        if (view == null) {
            throw new IllegalArgumentException("View does not exist: " + viewName);
        }
        
        if (!view.isMaterialized()) {
            throw new IllegalArgumentException("Cannot create index on non-materialized view: " + viewName);
        }
        
        // Check if index already exists
        for (ViewMetadata.IndexMetadata idx : view.getIndexes()) {
            if (idx.getName().equalsIgnoreCase(indexName)) {
                throw new IllegalArgumentException("Index already exists: " + indexName);
            }
        }
        
        // Allocate index ID
        long indexId = allocateIndexId(view.getViewId());
        
        // Create index metadata (marked as building)
        ViewMetadata.IndexMetadata indexMetadata = new ViewMetadata.IndexMetadata(
            indexId,
            indexName,
            columns,
            true  // Mark as building during backfill
        );
        
        // Add index to view
        List<ViewMetadata.IndexMetadata> newIndexes = new ArrayList<>(view.getIndexes());
        newIndexes.add(indexMetadata);
        ViewMetadata updatedView = view.withIndexes(newIndexes);
        
        // Store updated view metadata
        storeViewMetadata(updatedView);
        
        // Update cache
        viewsByName.put(viewName.toLowerCase(), updatedView);
        viewsById.put(view.getViewId(), updatedView);
        
        log.info("✅ Created index {} on view {} (id={})", indexName, viewName, indexId);
        
        return indexMetadata;
    }
    
    /**
     * Mark a view index as ready (building=false)
     */
    public void markViewIndexReady(String viewName, String indexName) {
        ViewMetadata view = getView(viewName);
        if (view == null) {
            throw new IllegalArgumentException("View does not exist: " + viewName);
        }
        
        // Find the index and mark it as ready
        List<ViewMetadata.IndexMetadata> newIndexes = new ArrayList<>();
        boolean found = false;
        
        for (ViewMetadata.IndexMetadata idx : view.getIndexes()) {
            if (idx.getName().equalsIgnoreCase(indexName)) {
                newIndexes.add(new ViewMetadata.IndexMetadata(
                    idx.getIndexId(),
                    idx.getName(),
                    idx.getColumns(),
                    false  // Mark as ready
                ));
                found = true;
            } else {
                newIndexes.add(idx);
            }
        }
        
        if (!found) {
            throw new IllegalArgumentException("Index not found: " + indexName);
        }
        
        // Update view with new indexes
        ViewMetadata updatedView = view.withIndexes(newIndexes);
        
        // Store updated view metadata
        storeViewMetadata(updatedView);
        
        // Update cache
        viewsByName.put(viewName.toLowerCase(), updatedView);
        viewsById.put(view.getViewId(), updatedView);
        
        log.info("✅ Marked index {} on view {} as READY", indexName, viewName);
    }
    
    // ========================================================================
    // ENUM Type Management
    // ========================================================================
    
    /**
     * Create a new ENUM type
     */
    public EnumMetadata createEnumType(String typeName, List<String> values) {
        log.info("Creating ENUM type: {} with values: {}", typeName, values);
        
        // Check if type already exists
        if (enumsByName.containsKey(typeName.toLowerCase())) {
            throw new IllegalArgumentException("ENUM type already exists: " + typeName);
        }
        
        // Create enum metadata
        EnumMetadata enumMetadata = new EnumMetadata(typeName, values);
        
        // Store in KV
        storeEnumMetadata(enumMetadata);
        
        // Update cache
        enumsByName.put(typeName.toLowerCase(), enumMetadata);
        
        log.info("✅ Created ENUM type: {}", typeName);
        return enumMetadata;
    }
    
    /**
     * Get ENUM type by name
     */
    public EnumMetadata getEnumType(String typeName) {
        if (alwaysReload) {
            return loadEnumMetadata(typeName);
        }
        return enumsByName.get(typeName.toLowerCase());
    }
    
    /**
     * Get all ENUM types
     */
    public Collection<EnumMetadata> getAllEnumTypes() {
        return enumsByName.values();
    }
    
    /**
     * Drop an ENUM type
     */
    public void dropEnumType(String typeName) {
        log.info("Dropping ENUM type: {}", typeName);
        
        EnumMetadata enumType = getEnumType(typeName);
        if (enumType == null) {
            throw new IllegalArgumentException("ENUM type does not exist: " + typeName);
        }
        
        // Mark as dropped
        long droppedTs = System.currentTimeMillis();
        EnumMetadata droppedEnum = enumType.withDroppedTimestamp(droppedTs);
        storeEnumMetadata(droppedEnum);
        
        // Remove from cache
        enumsByName.remove(typeName.toLowerCase());
        
        log.info("✅ Dropped ENUM type: {}", typeName);
    }
    
    /**
     * Store ENUM metadata in KV store
     */
    private void storeEnumMetadata(EnumMetadata enumMetadata) {
        try {
            byte[] key = KeyEncoder.encodeEnumMetadataKey(enumMetadata.getTypeName());
            byte[] value = objectMapper.writeValueAsBytes(enumMetadata);
            
            long timestamp = timestampOracle.getCurrentTimestamp();
            kvStore.put(key, value, timestamp, UUID.randomUUID(), timestamp, false);
            
            log.debug("Stored ENUM metadata: {}", enumMetadata.getTypeName());
        } catch (Exception e) {
            log.error("Failed to store ENUM metadata: {}", e.getMessage());
            throw new RuntimeException("Failed to store ENUM metadata: " + e.getMessage(), e);
        }
    }
    
    /**
     * Load ENUM metadata from KV store
     */
    private EnumMetadata loadEnumMetadata(String typeName) {
        try {
            byte[] key = KeyEncoder.encodeEnumMetadataKey(typeName);
            long timestamp = timestampOracle.getCurrentTimestamp();
            KvStore.KvEntry entry = kvStore.get(key, timestamp);
            
            if (entry == null) {
                return null;
            }
            
            EnumMetadata enumMetadata = objectMapper.readValue(entry.getValue(), EnumMetadata.class);
            
            // Don't return dropped types
            if (enumMetadata.isDropped()) {
                return null;
            }
            
            return enumMetadata;
        } catch (Exception e) {
            log.error("Failed to load ENUM metadata for {}: {}", typeName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Load all ENUM types from KV store
     */
    private void loadAllEnumTypes() {
        try {
            log.info("Loading ENUM types from KV storage...");
            
            // Scan all enum metadata keys
            byte[] startKey = KeyEncoder.encodeEnumMetadataKey("");
            byte[] endKey = KeyEncoder.encodeEnumMetadataKey("\uffff");
            
            long readTs = timestampOracle.getCurrentTimestamp();
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, null);
            
            for (KvStore.KvEntry entry : entries) {
                try {
                    EnumMetadata enumMetadata = objectMapper.readValue(entry.getValue(), EnumMetadata.class);
                    
                    // Skip dropped types
                    if (!enumMetadata.isDropped()) {
                        enumsByName.put(enumMetadata.getTypeName().toLowerCase(), enumMetadata);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load ENUM metadata: {}", e.getMessage());
                }
            }
            
            log.info("Loaded {} ENUM types from KV storage", enumsByName.size());
        } catch (Exception e) {
            log.error("Failed to load ENUM types: {}", e.getMessage());
        }
    }
}


