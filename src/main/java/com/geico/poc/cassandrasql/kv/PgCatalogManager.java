package com.geico.poc.cassandrasql.kv;

import com.datastax.oss.driver.api.core.CqlSession;
import com.geico.poc.cassandrasql.config.KeyspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages PostgreSQL catalog tables (pg_catalog) in the KV store.
 * 
 * This implements PostgreSQL 17 system catalogs for compatibility with psql and other PostgreSQL clients.
 * All catalog tables are stored in the KV store in a separate keyspace (pg_catalog) to avoid conflicts
 * with user tables.
 * 
 * Key catalog tables implemented:
 * - pg_namespace: Schemas/namespaces
 * - pg_class: Tables, indexes, views, sequences
 * - pg_attribute: Table columns
 * - pg_type: Data types
 * - pg_index: Index definitions
 * - pg_constraint: Constraints
 * - pg_attrdef: Column defaults
 * - pg_description: Comments
 * - pg_depend: Dependencies between objects
 * - pg_proc: Functions/procedures
 * - pg_operator: Operators
 * - pg_am: Access methods
 * - pg_tablespace: Tablespaces
 * - pg_database: Databases
 * - pg_roles: Roles/users
 */
@Component
public class PgCatalogManager {
    
    private static final Logger log = LoggerFactory.getLogger(PgCatalogManager.class);
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    @Lazy
    private SchemaManager schemaManager;
    
    @Autowired
    private CqlSession session;
    
    @Autowired
    private KeyspaceConfig keyspaceConfig;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    // Keyspace for catalog tables
    private static final String PG_CATALOG_KEYSPACE = "pg_catalog";
    
    // Table IDs for catalog tables (negative to avoid conflicts with user tables)
    private static final long TABLE_PG_NAMESPACE = -1;
    private static final long TABLE_PG_CLASS = -2;
    private static final long TABLE_PG_ATTRIBUTE = -3;
    private static final long TABLE_PG_TYPE = -4;
    private static final long TABLE_PG_INDEX = -5;
    private static final long TABLE_PG_CONSTRAINT = -6;
    private static final long TABLE_PG_ATTRDEF = -7;
    private static final long TABLE_PG_DESCRIPTION = -8;
    private static final long TABLE_PG_DEPEND = -9;
    private static final long TABLE_PG_PROC = -10;
    private static final long TABLE_PG_OPERATOR = -11;
    private static final long TABLE_PG_AM = -12;
    private static final long TABLE_PG_TABLESPACE = -13;
    private static final long TABLE_PG_DATABASE = -14;
    private static final long TABLE_PG_ROLES = -15;
    
    // Standard PostgreSQL OIDs
    private static final long OID_NAMESPACE_PUBLIC = 2200;
    private static final long OID_NAMESPACE_PG_CATALOG = 11;
    private static final long OID_TYPE_BOOL = 16;
    private static final long OID_TYPE_INT8 = 20;
    private static final long OID_TYPE_INT4 = 23;
    private static final long OID_TYPE_TEXT = 25;
    private static final long OID_TYPE_VARCHAR = 1043;
    private static final long OID_TYPE_FLOAT8 = 701;
    private static final long OID_TYPE_TIMESTAMP = 1114;
    
    private volatile boolean initialized = false;
    
    @PostConstruct
    public void initialize() {
        log.info("🔧 Initializing PostgreSQL catalog in KV store...");
        
        try {
            // Create catalog keyspace if needed
            createCatalogKeyspace();
            
            // Note: We defer catalog table creation until first use to avoid circular dependency
            // The tables will be created lazily when first accessed
            
            log.info("✅ PostgreSQL catalog initialized successfully (tables will be created on first use)");
            initialized = true;
        } catch (Exception e) {
            log.error("❌ Failed to initialize PostgreSQL catalog", e);
            throw new RuntimeException("Failed to initialize PostgreSQL catalog", e);
        }
    }
    
    /**
     * Ensure catalog tables are created (lazy initialization)
     */
    private void ensureCatalogTablesCreated() {
        log.debug("ensureCatalogTablesCreated: initialized={}, pg_namespace={}", 
            initialized, schemaManager.getTable("pg_namespace"));
        
        // Check if catalog tables need to be created
        if (schemaManager.getTable("pg_namespace") == null) {
            synchronized (this) {
                // Double-check after acquiring lock
                if (schemaManager.getTable("pg_namespace") == null) {
                    log.info("Creating catalog table metadata...");
                    
                    // If not initialized yet, initialize now
                    if (!initialized) {
                        log.info("Initializing PgCatalogManager during lazy creation");
                        try {
                            createCatalogKeyspace();
                            initialized = true;
                        } catch (Exception e) {
                            log.error("Failed to initialize catalog keyspace", e);
                            throw new RuntimeException("Failed to initialize catalog", e);
                        }
                    }
                    
                    createCatalogTables();
                    populateSystemCatalog();
                    log.info("✅ Catalog tables created and populated");
                }
            }
        } else {
            log.debug("Catalog tables already exist");
        }
    }
    
    /**
     * Create the pg_catalog keyspace if it doesn't exist
     */
    private void createCatalogKeyspace() {
        try {
            String keyspace = keyspaceConfig.getPgCatalogKeyspace();
            String cql = String.format(
                "CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {" +
                "  'class': 'NetworkTopologyStrategy'," +
                "  'datacenter1': 1" +
                "} AND durable_writes = true",
                keyspace
            );
            
            session.execute(cql);
            log.info("  ✓ Created keyspace: {}", keyspace);
            
            // Create KV store tables in pg_catalog keyspace
            createKvTablesInCatalogKeyspace(keyspace);
        } catch (Exception e) {
            log.error("Failed to create pg_catalog keyspace", e);
            throw new RuntimeException("Failed to create pg_catalog keyspace", e);
        }
    }
    
    /**
     * Create kv_store, tx_locks, tx_writes tables in the pg_catalog keyspace
     */
    private void createKvTablesInCatalogKeyspace(String keyspace) {
        // Create kv_store table
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.kv_store (" +
            "  key BLOB," +
            "  ts BIGINT," +
            "  value BLOB," +
            "  tx_id UUID," +
            "  commit_ts BIGINT," +
            "  deleted BOOLEAN," +
            "  PRIMARY KEY (key, ts)" +
            ") WITH CLUSTERING ORDER BY (ts DESC) AND transactional_mode='full'",
            keyspace
        ));
        log.debug("  ✓ Created table: {}.kv_store", keyspace);
        
        // Create tx_locks table
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.tx_locks (" +
            "  key BLOB PRIMARY KEY," +
            "  tx_id UUID," +
            "  start_ts BIGINT," +
            "  primary_key BLOB," +
            "  lock_type TEXT," +
            "  write_type TEXT," +
            "  created_at TIMESTAMP" +
            ") WITH transactional_mode='full'",
            keyspace
        ));
        log.debug("  ✓ Created table: {}.tx_locks", keyspace);
        
        // Create tx_writes table
        session.execute(String.format(
            "CREATE TABLE IF NOT EXISTS %s.tx_writes (" +
            "  key BLOB," +
            "  commit_ts BIGINT," +
            "  tx_id UUID," +
            "  value BLOB," +
            "  deleted BOOLEAN," +
            "  PRIMARY KEY (key, commit_ts)" +
            ") WITH CLUSTERING ORDER BY (commit_ts DESC) AND transactional_mode='full'",
            keyspace
        ));
        log.debug("  ✓ Created table: {}.tx_writes", keyspace);
    }
    
    /**
     * Create metadata for catalog tables in the schema manager
     */
    private void createCatalogTables() {
        log.debug("  📋 Creating catalog table metadata...");
        
        // Register catalog tables with the schema manager
        registerCatalogTable(PgCatalogTable.pgNamespace());
        registerCatalogTable(PgCatalogTable.pgClass());
        registerCatalogTable(PgCatalogTable.pgAttribute());
        registerCatalogTable(PgCatalogTable.pgType());
        registerCatalogTable(PgCatalogTable.pgIndex());
        registerCatalogTable(PgCatalogTable.pgConstraint());
        registerCatalogTable(PgCatalogTable.pgAttrdef());
        registerCatalogTable(PgCatalogTable.pgDescription());
        registerCatalogTable(PgCatalogTable.pgAm());
        registerCatalogTable(PgCatalogTable.pgTables());
        registerCatalogTable(PgCatalogTable.pgIndexes());
        
        log.debug("  ✅ Catalog table metadata created");
    }
    
    /**
     * Register a catalog table with the schema manager
     */
    private void registerCatalogTable(PgCatalogTable catalogTable) {
        List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
        
        for (PgCatalogTable.Column col : catalogTable.getColumns()) {
            columns.add(new TableMetadata.ColumnMetadata(
                col.getName(),
                col.getType(),
                !col.isNotNull()  // nullable = !notNull
            ));
        }
        
        // Create table metadata
        TableMetadata metadata = new TableMetadata(
            catalogTable.getTableId(),
            catalogTable.getTableName(),
            columns,
            catalogTable.getPrimaryKey(),
            new ArrayList<>(),  // No indexes initially
            1  // version
        );
        
        // Register with schema manager (in pg_catalog namespace)
        schemaManager.registerCatalogTable(metadata);
        
        log.debug("    ✅ Registered catalog table: {}", catalogTable.getTableName());
    }
    
    /**
     * Populate catalog with system data (types, namespaces, etc.)
     */
    private void populateSystemCatalog() {
        log.debug("  🌱 Populating system catalog data...");
        
        // Add system namespaces
        addNamespace(OID_NAMESPACE_PG_CATALOG, "pg_catalog", "System catalog schema");
        addNamespace(OID_NAMESPACE_PUBLIC, "public", "Standard public schema");
        
        // Add system types
        addType(OID_TYPE_BOOL, "bool", "boolean", 1);
        addType(OID_TYPE_INT8, "int8", "bigint", 8);
        addType(OID_TYPE_INT4, "int4", "integer", 4);
        addType(OID_TYPE_TEXT, "text", "variable-length string", -1);
        addType(OID_TYPE_VARCHAR, "varchar", "variable-length string with limit", -1);
        addType(OID_TYPE_FLOAT8, "float8", "double precision", 8);
        addType(OID_TYPE_TIMESTAMP, "timestamp", "timestamp without time zone", 8);
        
        // Add access methods
        addAccessMethod(403, "btree", 330, "i");  // B-tree index
        addAccessMethod(405, "hash", 331, "i");   // Hash index
        addAccessMethod(783, "gist", 332, "i");   // GiST index
        addAccessMethod(2742, "heap", 0, "t");    // Heap table access method
        
        log.debug("  ✅ System catalog data populated");
    }
    
    
    // ==================== Catalog Data Manipulation ====================
    
    /**
     * Add a namespace to pg_namespace
     */
    private void addNamespace(long oid, String name, String description) {
        try {
            TableMetadata pgNamespace = schemaManager.getTable("pg_namespace");
            if (pgNamespace == null) {
                log.warn("pg_namespace table not found in schema manager");
                return;
            }
            
            // Create row data
            Map<String, Object> row = new HashMap<>();
            row.put("oid", oid);
            row.put("nspname", name);
            row.put("nspowner", 10L);  // Default owner OID
            row.put("nspacl", null);   // No ACL by default
            
            // Insert into KV store
            insertCatalogRow(pgNamespace, row);
            
            log.debug("    📦 Added namespace: {} (oid={})", name, oid);
        } catch (Exception e) {
            log.error("Failed to add namespace: {}", name, e);
        }
    }
    
    /**
     * Add an access method to pg_am
     */
    private void addAccessMethod(long oid, String amname, long amhandler, String amtype) {
        try {
            TableMetadata pgAm = schemaManager.getTable("pg_am");
            if (pgAm == null) {
                log.warn("pg_am table not found in schema manager");
                return;
            }
            
            // Create row data
            Map<String, Object> row = new HashMap<>();
            row.put("oid", oid);
            row.put("amname", amname);
            row.put("amhandler", amhandler);
            row.put("amtype", amtype);
            
            // Insert into KV store
            insertCatalogRow(pgAm, row);
            
            log.debug("    🔧 Added access method: {} (oid={})", amname, oid);
        } catch (Exception e) {
            log.error("Failed to add access method: {}", amname, e);
        }
    }
    
    /**
     * Add a type to pg_type
     */
    private void addType(long oid, String name, String description, int length) {
        try {
            TableMetadata pgType = schemaManager.getTable("pg_type");
            if (pgType == null) {
                log.warn("pg_type table not found in schema manager");
                return;
            }
            
            // Create row data
            Map<String, Object> row = new HashMap<>();
            row.put("oid", oid);
            row.put("typname", name);
            row.put("typnamespace", OID_NAMESPACE_PG_CATALOG);
            row.put("typowner", 10L);
            row.put("typlen", length);
            row.put("typbyval", length > 0 && length <= 8);
            row.put("typtype", "b");  // base type
            row.put("typcategory", getTypeCategory(name));
            row.put("typispreferred", false);
            row.put("typisdefined", true);
            row.put("typdelim", ",");
            row.put("typrelid", 0L);
            row.put("typsubscript", null);
            row.put("typelem", 0L);
            row.put("typarray", 0L);
            
            // Insert into KV store
            insertCatalogRow(pgType, row);
            
            log.debug("    🔤 Added type: {} (oid={})", name, oid);
        } catch (Exception e) {
            log.error("Failed to add type: {}", name, e);
        }
    }
    
    /**
     * Get PostgreSQL type category for a type name
     */
    private String getTypeCategory(String typeName) {
        switch (typeName.toLowerCase()) {
            case "bool":
                return "B";  // Boolean
            case "int2":
            case "int4":
            case "int8":
                return "N";  // Numeric
            case "float4":
            case "float8":
                return "N";  // Numeric
            case "text":
            case "varchar":
            case "char":
                return "S";  // String
            case "timestamp":
            case "date":
            case "time":
                return "D";  // Date/time
            default:
                return "U";  // User-defined
        }
    }
    
    /**
     * Insert a row into a catalog table
     */
    private void insertCatalogRow(TableMetadata table, Map<String, Object> row) {
        // Build primary key from row data
        List<Object> pkValues = new ArrayList<>();
        for (String pkCol : table.getPrimaryKeyColumns()) {
            pkValues.add(row.get(pkCol));
        }
        
        // Encode key as TABLE DATA (not INDEX)
        // Catalog tables use the same encoding as user tables
        // Use TimestampOracle to get a proper MVCC timestamp (in microseconds)
        long ts = timestampOracle.allocateCommitTimestamp();
        byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), pkValues, ts);
        
        // Encode value (all non-PK columns)
        // Extract non-PK values in column order
        List<Object> nonPkValues = new ArrayList<>();
        for (TableMetadata.ColumnMetadata col : table.getNonPrimaryKeyColumns()) {
            nonPkValues.add(row.get(col.getName()));
        }
        byte[] value = ValueEncoder.encodeRow(nonPkValues);
        
        // Insert with current timestamp
        UUID txId = UUID.randomUUID();
        
        // Write to pg_catalog keyspace instead of default keyspace
        String catalogKeyspace = keyspaceConfig.getPgCatalogKeyspace();
        String insertCql = String.format(
            "INSERT INTO %s.kv_store (key, value, ts, tx_id, commit_ts, deleted) VALUES (?, ?, ?, ?, ?, ?)",
            catalogKeyspace
        );
        
        session.execute(
            session.prepare(insertCql).bind(
                java.nio.ByteBuffer.wrap(key),
                value != null ? java.nio.ByteBuffer.wrap(value) : null,
                ts,
                txId,
                ts,
                false
            )
        );
    }
    
    /**
     * Encode value map as JSON bytes
     */
    private byte[] encodeValue(Map<String, Object> valueMap) {
        try {
            // Simple JSON encoding
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : valueMap.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value == null) {
                    json.append("null");
                } else if (value instanceof String) {
                    json.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"");
                } else if (value instanceof Boolean || value instanceof Number) {
                    json.append(value);
                } else {
                    json.append("\"").append(value.toString()).append("\"");
                }
            }
            json.append("}");
            return json.toString().getBytes("UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode value", e);
        }
    }
    
    /**
     * Public method to ensure catalog tables are initialized
     * Called before querying catalog tables
     */
    public void ensureInitialized() {
        ensureCatalogTablesCreated();
    }
    
    /**
     * Add a table to pg_class when user creates a table
     */
    public void addTable(TableMetadata table) {
        log.info("📝 Adding table to pg_catalog: {} (id={})", table.getTableName(), table.getTableId());
        
        // Ensure catalog tables are created first
        ensureCatalogTablesCreated();
        
        try {
            long oid = table.getTableId() + 100000; // Offset to avoid system OID conflicts
            String tableName = table.getTableName();
            
            TableMetadata pgClass = schemaManager.getTable("pg_class");
            if (pgClass == null) {
                log.warn("pg_class table not found");
                return;
            }
            
            // Add to pg_class
            Map<String, Object> classRow = new HashMap<>();
            classRow.put("oid", oid);
            classRow.put("relname", tableName);
            classRow.put("relnamespace", OID_NAMESPACE_PUBLIC);
            classRow.put("reltype", 0L);
            classRow.put("relowner", 10L);
            classRow.put("relam", 0L);
            classRow.put("relfilenode", oid);
            classRow.put("reltablespace", 0L);
            classRow.put("relpages", 0);
            classRow.put("reltuples", 0.0);
            classRow.put("relallvisible", 0);
            classRow.put("reltoastrelid", 0L);
            classRow.put("relhasindex", !table.getIndexes().isEmpty());
            classRow.put("relisshared", false);
            classRow.put("relpersistence", "p");  // permanent
            classRow.put("relkind", "r");  // ordinary table
            classRow.put("relnatts", table.getColumns().size());
            classRow.put("relchecks", 0);
            classRow.put("relhasrules", false);
            classRow.put("relhastriggers", false);
            classRow.put("relhassubclass", false);
            classRow.put("relrowsecurity", false);
            classRow.put("relforcerowsecurity", false);
            classRow.put("relispopulated", true);
            classRow.put("relreplident", "d");  // default
            classRow.put("relispartition", false);
            classRow.put("relrewrite", 0L);
            classRow.put("relfrozenxid", 0L);
            classRow.put("relminmxid", 0L);
            classRow.put("relacl", null);
            classRow.put("reloptions", null);
            classRow.put("relpartbound", null);
            classRow.put("relpartkey", null);  // NULL for regular tables (not partitioned)
            
            insertCatalogRow(pgClass, classRow);
            
            // Add to pg_tables (simplified view)
            TableMetadata pgTables = schemaManager.getTable("pg_tables");
            if (pgTables != null) {
                Map<String, Object> tablesRow = new HashMap<>();
                // Use the actual keyspace name as the schema name
                tablesRow.put("schemaname", keyspaceConfig.getDefaultKeyspace());
                tablesRow.put("tablename", tableName);
                tablesRow.put("tableowner", "cassandra");
                insertCatalogRow(pgTables, tablesRow);
            }
            
            // Add columns to pg_attribute
            int attnum = 1;
            for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                addColumn(oid, col, attnum++);
            }
            
            // Add primary key as an index (index 0)
            // Note: Primary keys are always added to pg_index with indisprimary=true
            if (!table.getPrimaryKeyColumns().isEmpty()) {
                TableMetadata.IndexMetadata pkIndex = new TableMetadata.IndexMetadata(
                    0L,  // Primary key always has index ID 0
                    tableName + "_pkey",
                    new ArrayList<>(table.getPrimaryKeyColumns()),  // Create a new list to ensure equality works
                    true  // Primary keys are unique
                );
                addIndex(table, pkIndex);
            }
            
            // Add other indexes to pg_index
            for (TableMetadata.IndexMetadata idx : table.getIndexes()) {
                addIndex(table, idx);
            }
            
            log.debug("  ✅ Added table to pg_catalog: {} (oid={})", tableName, oid);
        } catch (Exception e) {
            log.error("Failed to add table to catalog: {}", table.getTableName(), e);
        }
    }
    
    /**
     * Add a column to pg_attribute
     */
    private void addColumn(long tableOid, TableMetadata.ColumnMetadata col, int attnum) {
        try {
            TableMetadata pgAttribute = schemaManager.getTable("pg_attribute");
            if (pgAttribute == null) {
                log.warn("pg_attribute table not found");
                return;
            }
            
            Map<String, Object> attrRow = new HashMap<>();
            attrRow.put("attrelid", tableOid);
            attrRow.put("attname", col.getName());
            attrRow.put("atttypid", getTypeOid(col.getType()));
            attrRow.put("attstattarget", -1);
            attrRow.put("attlen", getTypeLength(col.getType()));
            attrRow.put("attnum", attnum);
            attrRow.put("attndims", 0);
            attrRow.put("attcacheoff", -1);
            attrRow.put("atttypmod", -1);
            attrRow.put("attbyval", false);
            attrRow.put("attalign", "i");
            attrRow.put("attstorage", "p");
            attrRow.put("attcompression", null);
            attrRow.put("attnotnull", !col.isNullable());
            attrRow.put("atthasdef", false);
            attrRow.put("atthasmissing", false);
            attrRow.put("attidentity", "");
            attrRow.put("attgenerated", "");
            attrRow.put("attisdropped", false);
            attrRow.put("attislocal", true);
            attrRow.put("attinhcount", 0);
            attrRow.put("attcollation", 0L);
            attrRow.put("attacl", null);
            attrRow.put("attoptions", null);
            attrRow.put("attfdwoptions", null);
            attrRow.put("attmissingval", null);
            
            insertCatalogRow(pgAttribute, attrRow);
            
            log.debug("    📊 Added column: {} (attnum={})", col.getName(), attnum);
        } catch (Exception e) {
            log.error("Failed to add column: {}", col.getName(), e);
        }
    }
    
    /**
     * Add an index to pg_index
     */
    public void addIndex(TableMetadata table, TableMetadata.IndexMetadata index) {
        try {
            long tableOid = table.getTableId() + 100000;
            long indexOid = index.getIndexId() + 200000; // Offset for index OIDs
            
            // In PostgreSQL, indexes are also entries in pg_class with relkind='i'
            TableMetadata pgClass = schemaManager.getTable("pg_class");
            if (pgClass != null) {
                Map<String, Object> classRow = new HashMap<>();
                classRow.put("oid", indexOid);
                classRow.put("relname", index.getName());
                classRow.put("relnamespace", OID_NAMESPACE_PUBLIC);
                classRow.put("reltype", 0L);
                classRow.put("relowner", 10L);
                classRow.put("relam", 403L);  // btree access method
                classRow.put("relfilenode", indexOid);
                classRow.put("reltablespace", 0L);
                classRow.put("relpages", 0);
                classRow.put("reltuples", 0.0);
                classRow.put("relallvisible", 0);
                classRow.put("reltoastrelid", 0L);
                classRow.put("relhasindex", false);
                classRow.put("relisshared", false);
                classRow.put("relpersistence", "p");
                classRow.put("relkind", "i");  // index
                classRow.put("relnatts", index.getColumns().size());
                classRow.put("relchecks", 0);
                classRow.put("relhasrules", false);
                classRow.put("relhastriggers", false);
                classRow.put("relhassubclass", false);
                classRow.put("relrowsecurity", false);
                classRow.put("relforcerowsecurity", false);
                classRow.put("relispopulated", true);
                classRow.put("relreplident", "n");  // nothing
                classRow.put("relispartition", false);
                classRow.put("relrewrite", 0L);
                classRow.put("relfrozenxid", 0L);
                classRow.put("relminmxid", 0L);
                classRow.put("relacl", null);
                classRow.put("reloptions", null);
                classRow.put("relpartbound", null);
                classRow.put("relpartkey", null);  // NULL for indexes (not partitioned)
                
                insertCatalogRow(pgClass, classRow);
            }
            
            // Add to pg_index
            TableMetadata pgIndex = schemaManager.getTable("pg_index");
            if (pgIndex == null) {
                log.warn("pg_index table not found");
                return;
            }
            
            Map<String, Object> indexRow = new HashMap<>();
            indexRow.put("indexrelid", indexOid);
            indexRow.put("indrelid", tableOid);
            indexRow.put("indnatts", index.getColumns().size());
            indexRow.put("indnkeyatts", index.getColumns().size());
            indexRow.put("indisunique", index.isUnique());
            indexRow.put("indnullsnotdistinct", false);
            // Check if this is a primary key index
            // Primary key index always has index ID 0
            boolean isPrimary = (index.getIndexId() == 0L);
            // Also verify by comparing columns with table's primary key (case-insensitive)
            if (!isPrimary) {
                List<String> indexCols = index.getColumns();
                List<String> pkCols = table.getPrimaryKeyColumns();
                if (indexCols.size() == pkCols.size() && !pkCols.isEmpty()) {
                    // Compare elements case-insensitively
                    isPrimary = true;
                    for (int i = 0; i < indexCols.size(); i++) {
                        if (!indexCols.get(i).equalsIgnoreCase(pkCols.get(i))) {
                            isPrimary = false;
                            break;
                        }
                    }
                }
            }
            indexRow.put("indisprimary", isPrimary);
            indexRow.put("indisexclusion", false);
            indexRow.put("indimmediate", true);
            indexRow.put("indisclustered", false);
            indexRow.put("indisvalid", true);
            indexRow.put("indcheckxmin", false);
            indexRow.put("indisready", true);
            indexRow.put("indislive", true);
            indexRow.put("indisreplident", false);
            indexRow.put("indkey", String.join(",", index.getColumns()));
            indexRow.put("indcollation", null);
            indexRow.put("indclass", null);
            indexRow.put("indoption", null);
            indexRow.put("indexprs", null);
            indexRow.put("indpred", null);
            
            insertCatalogRow(pgIndex, indexRow);
            
            // Add to pg_indexes (simplified view)
            TableMetadata pgIndexes = schemaManager.getTable("pg_indexes");
            if (pgIndexes != null) {
                Map<String, Object> indexesRow = new HashMap<>();
                // Use the actual keyspace name as the schema name
                indexesRow.put("schemaname", keyspaceConfig.getDefaultKeyspace());
                indexesRow.put("tablename", table.getTableName());
                indexesRow.put("indexname", index.getName());
                // Create a simple index definition string
                String indexDef = String.format("CREATE INDEX %s ON %s (%s)",
                    index.getName(),
                    table.getTableName(),
                    String.join(", ", index.getColumns()));
                indexesRow.put("indexdef", indexDef);
                insertCatalogRow(pgIndexes, indexesRow);
            }
            
            log.debug("    🔍 Added index: {} (oid={})", index.getName(), indexOid);
        } catch (Exception e) {
            log.error("Failed to add index: {}", index.getName(), e);
        }
    }
    
    /**
     * Remove a table from pg_class when user drops a table
     */
    public void removeTable(String tableName) {
        log.info("🗑️  Removing table from pg_catalog: {}", tableName);
        
        try {
            TableMetadata pgClass = schemaManager.getTable("pg_class");
            if (pgClass == null) {
                log.warn("pg_class table not found");
                return;
            }
            
            // Find the OID for this table by scanning pg_class
            byte[] startKey = KeyEncoder.createRangeStartKey(pgClass.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(pgClass.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            long readTs = timestampOracle.getCurrentTimestamp();
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000);
            
            log.debug("   Scanning pg_class for table '{}', found {} entries", tableName, entries.size());
            
            List<Long> oidsToDelete = new ArrayList<>();
            
            // Get column types for decoding
            List<Class<?>> pkColumnTypes = pgClass.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : pgClass.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return Object.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = pgClass.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            for (KvStore.KvEntry entry : entries) {
                try {
                    // Decode key to get PK values (oid)
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                    
                    // Decode value to get non-PK values
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                    
                    // Build row map
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < pgClass.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                        row.put(pgClass.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                    }
                    List<TableMetadata.ColumnMetadata> nonPkColumns = pgClass.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    String relname = (String) row.get("relname");
                    log.debug("   Found pg_class entry: relname='{}', oid={}", relname, row.get("oid"));
                    if (tableName.equalsIgnoreCase(relname)) {
                        Long oid = (Long) row.get("oid");
                        if (oid != null) {
                            oidsToDelete.add(oid);
                            log.debug("   ✅ Matched table '{}' with oid={}", relname, oid);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to decode pg_class row: {}", e.getMessage());
                }
            }
            
            log.debug("   Found {} OIDs to delete for table '{}'", oidsToDelete.size(), tableName);
            
            // Delete from pg_class by OID (primary key)
            for (Long oid : oidsToDelete) {
                deleteCatalogRow(pgClass, oid);
                log.debug("   Deleted from pg_class: oid={}", oid);
            }
            
            // Delete from pg_attribute (all columns for this table)
            TableMetadata pgAttribute = schemaManager.getTable("pg_attribute");
            if (pgAttribute != null) {
                for (Long oid : oidsToDelete) {
                    deleteAttributesForTable(pgAttribute, oid, readTs);
                }
            }
            
            // Delete from pg_index (all indexes for this table)
            TableMetadata pgIndex = schemaManager.getTable("pg_index");
            if (pgIndex != null) {
                for (Long oid : oidsToDelete) {
                    deleteIndexesForTable(pgClass, pgIndex, oid, readTs);
                }
            }
            
            log.info("✅ Removed table {} from pg_catalog (deleted {} table entries)", tableName, oidsToDelete.size());
        } catch (Exception e) {
            log.error("Failed to remove table from catalog: {}", tableName, e);
        }
    }
    
    /**
     * Delete all attributes for a table
     */
    private void deleteAttributesForTable(TableMetadata pgAttribute, Long tableOid, long readTs) {
        try {
            byte[] startKey = KeyEncoder.createRangeStartKey(pgAttribute.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(pgAttribute.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000);
            
            // Get column types
            List<Class<?>> pkColumnTypes = pgAttribute.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : pgAttribute.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return Object.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = pgAttribute.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            for (KvStore.KvEntry entry : entries) {
                try {
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                    
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < pgAttribute.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                        row.put(pgAttribute.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                    }
                    List<TableMetadata.ColumnMetadata> nonPkColumns = pgAttribute.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    Long attrelid = (Long) row.get("attrelid");
                    if (tableOid.equals(attrelid)) {
                        // Delete this attribute row
                        kvStore.delete(entry.getKey());
                        log.debug("   Deleted from pg_attribute: attrelid={}", attrelid);
                    }
                } catch (Exception e) {
                    log.warn("Failed to decode pg_attribute row: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to delete attributes: {}", e.getMessage());
        }
    }
    
    /**
     * Delete all indexes for a table
     */
    private void deleteIndexesForTable(TableMetadata pgClass, TableMetadata pgIndex, Long tableOid, long readTs) {
        try {
            byte[] startKey = KeyEncoder.createRangeStartKey(pgIndex.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(pgIndex.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000);
            
            // Get column types
            List<Class<?>> pkColumnTypes = pgIndex.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : pgIndex.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return Object.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = pgIndex.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            for (KvStore.KvEntry entry : entries) {
                try {
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                    
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < pgIndex.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                        row.put(pgIndex.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                    }
                    List<TableMetadata.ColumnMetadata> nonPkColumns = pgIndex.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    Long indrelid = (Long) row.get("indrelid");
                    if (tableOid.equals(indrelid)) {
                        Long indexrelid = (Long) row.get("indexrelid");
                        
                        // Delete from pg_index
                        kvStore.delete(entry.getKey());
                        log.debug("   Deleted from pg_index: indexrelid={}", indexrelid);
                        
                        // Also delete the index entry from pg_class
                        deleteCatalogRow(pgClass, indexrelid);
                        log.debug("   Deleted index from pg_class: oid={}", indexrelid);
                    }
                } catch (Exception e) {
                    log.warn("Failed to decode pg_index row: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to delete indexes: {}", e.getMessage());
        }
    }
    
    /**
     * Delete a row from a catalog table by OID (primary key)
     * Since timestamp is NOT part of the key, we just delete the key once
     * Cassandra will delete all versions with that key
     */
    private void deleteCatalogRow(TableMetadata table, Long oid) {
        try {
            // Build the key (timestamp is not part of the key)
            byte[] key = KeyEncoder.encodeTableDataKey(
                table.getTableId(),
                Arrays.asList(oid),
                0L  // Timestamp is not used in key encoding
            );
            
            // Delete the row (this deletes all MVCC versions)
            kvStore.delete(key);
            
            log.debug("   ✅ Deleted catalog row with oid={} from table {}", oid, table.getTableName());
        } catch (Exception e) {
            log.error("Failed to delete catalog row: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Get type length for a data type
     */
    private int getTypeLength(String dataType) {
        String upperType = dataType.toUpperCase();
        switch (upperType) {
            case "BOOLEAN":
            case "BOOL":
                return 1;
            case "INTEGER":
            case "INT":
            case "INT4":
                return 4;
            case "BIGINT":
            case "INT8":
            case "TIMESTAMP":
            case "DOUBLE":
            case "FLOAT8":
                return 8;
            case "TEXT":
            case "VARCHAR":
                return -1;  // variable length
            default:
                return -1;
        }
    }
    
    /**
     * Get PostgreSQL type OID for a data type
     */
    public static long getTypeOid(String dataType) {
        String upperType = dataType.toUpperCase();
        switch (upperType) {
            case "BOOLEAN":
            case "BOOL":
                return OID_TYPE_BOOL;
            case "BIGINT":
            case "INT8":
                return OID_TYPE_INT8;
            case "INTEGER":
            case "INT":
            case "INT4":
                return OID_TYPE_INT4;
            case "TEXT":
                return OID_TYPE_TEXT;
            case "VARCHAR":
                return OID_TYPE_VARCHAR;
            case "DOUBLE":
            case "FLOAT8":
                return OID_TYPE_FLOAT8;
            case "TIMESTAMP":
                return OID_TYPE_TIMESTAMP;
            default:
                return OID_TYPE_TEXT; // Default to text for unknown types
        }
    }
    
    /**
     * Add a column to pg_attribute catalog (public wrapper for ALTER TABLE ADD COLUMN)
     */
    public void addColumnToCatalog(long tableOid, TableMetadata.ColumnMetadata col, int attnum) {
        addColumn(tableOid, col, attnum);
    }
    
    /**
     * Remove a column from pg_attribute catalog (for ALTER TABLE DROP COLUMN)
     */
    public void removeColumnFromCatalog(long tableOid, String columnName) {
        try {
            TableMetadata pgAttribute = schemaManager.getTable("pg_attribute");
            if (pgAttribute == null) {
                log.warn("pg_attribute table not found");
                return;
            }
            
            long readTs = timestampOracle.getCurrentTimestamp();
            byte[] startKey = KeyEncoder.createRangeStartKey(pgAttribute.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(pgAttribute.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000);
            
            // Get column types
            List<Class<?>> pkColumnTypes = pgAttribute.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : pgAttribute.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return Object.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = pgAttribute.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            for (KvStore.KvEntry entry : entries) {
                try {
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                    
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < pgAttribute.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                        row.put(pgAttribute.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                    }
                    List<TableMetadata.ColumnMetadata> nonPkColumns = pgAttribute.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    Long attrelid = (Long) row.get("attrelid");
                    String attname = (String) row.get("attname");
                    
                    if (tableOid == attrelid && columnName.equalsIgnoreCase(attname)) {
                        // Delete this attribute row
                        kvStore.delete(entry.getKey());
                        log.debug("   Deleted column from pg_attribute: attrelid={}, attname={}", attrelid, attname);
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Failed to decode pg_attribute row: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to remove column from catalog: {}", columnName, e);
        }
    }
}

