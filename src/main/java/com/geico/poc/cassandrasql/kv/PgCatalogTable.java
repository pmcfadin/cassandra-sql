package com.geico.poc.cassandrasql.kv;

import java.util.*;

/**
 * Represents a PostgreSQL catalog table structure.
 * These are stored in the KV store like regular tables but with negative table IDs.
 */
public class PgCatalogTable {
    
    private final long tableId;
    private final String tableName;
    private final List<Column> columns;
    private final List<String> primaryKey;
    
    public PgCatalogTable(long tableId, String tableName, List<Column> columns, List<String> primaryKey) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.columns = columns;
        this.primaryKey = primaryKey;
    }
    
    public long getTableId() {
        return tableId;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public List<Column> getColumns() {
        return columns;
    }
    
    public List<String> getPrimaryKey() {
        return primaryKey;
    }
    
    /**
     * Column definition for catalog tables
     */
    public static class Column {
        private final String name;
        private final String type;
        private final boolean notNull;
        
        public Column(String name, String type, boolean notNull) {
            this.name = name;
            this.type = type;
            this.notNull = notNull;
        }
        
        public String getName() {
            return name;
        }
        
        public String getType() {
            return type;
        }
        
        public boolean isNotNull() {
            return notNull;
        }
    }
    
    // ==================== Catalog Table Definitions ====================
    
    /**
     * pg_namespace: Schemas/namespaces
     */
    public static PgCatalogTable pgNamespace() {
        return new PgCatalogTable(
            -1,
            "pg_namespace",
            Arrays.asList(
                new Column("oid", "BIGINT", true),
                new Column("nspname", "TEXT", true),
                new Column("nspowner", "BIGINT", false),
                new Column("nspacl", "TEXT", false)
            ),
            Collections.singletonList("oid")
        );
    }
    
    /**
     * pg_class: Tables, indexes, sequences, views
     */
    public static PgCatalogTable pgClass() {
        return new PgCatalogTable(
            -2,
            "pg_class",
            Arrays.asList(
                new Column("oid", "BIGINT", true),
                new Column("relname", "TEXT", true),
                new Column("relnamespace", "BIGINT", true),
                new Column("reltype", "BIGINT", false),
                new Column("relowner", "BIGINT", false),
                new Column("relam", "BIGINT", false),
                new Column("relfilenode", "BIGINT", false),
                new Column("reltablespace", "BIGINT", false),
                new Column("relpages", "INTEGER", false),
                new Column("reltuples", "DOUBLE", false),
                new Column("relallvisible", "INTEGER", false),
                new Column("reltoastrelid", "BIGINT", false),
                new Column("relhasindex", "BOOLEAN", false),
                new Column("relisshared", "BOOLEAN", false),
                new Column("relpersistence", "TEXT", false),
                new Column("relkind", "TEXT", true),  // 'r' = table, 'i' = index, 'S' = sequence, 'v' = view
                new Column("relnatts", "INTEGER", false),
                new Column("relchecks", "INTEGER", false),
                new Column("relhasrules", "BOOLEAN", false),
                new Column("relhastriggers", "BOOLEAN", false),
                new Column("relhassubclass", "BOOLEAN", false),
                new Column("relrowsecurity", "BOOLEAN", false),
                new Column("relforcerowsecurity", "BOOLEAN", false),
                new Column("relispopulated", "BOOLEAN", false),
                new Column("relreplident", "TEXT", false),
                new Column("relispartition", "BOOLEAN", false),
                new Column("relrewrite", "BIGINT", false),
                new Column("relfrozenxid", "BIGINT", false),
                new Column("relminmxid", "BIGINT", false),
                new Column("relacl", "TEXT", false),
                new Column("reloptions", "TEXT", false),
                new Column("relpartbound", "TEXT", false),
                new Column("relpartkey", "TEXT", false)  // Partitioning key (array of attribute numbers), NULL for non-partitioned tables
            ),
            Collections.singletonList("oid")
        );
    }
    
    /**
     * pg_attribute: Table columns
     */
    public static PgCatalogTable pgAttribute() {
        return new PgCatalogTable(
            -3,
            "pg_attribute",
            Arrays.asList(
                new Column("attrelid", "BIGINT", true),  // OID of the table this column belongs to
                new Column("attname", "TEXT", true),     // Column name
                new Column("atttypid", "BIGINT", true),  // Data type OID
                new Column("attstattarget", "INTEGER", false),
                new Column("attlen", "INTEGER", false),  // Length (-1 for variable)
                new Column("attnum", "INTEGER", true),   // Column number (1-based)
                new Column("attndims", "INTEGER", false),
                new Column("attcacheoff", "INTEGER", false),
                new Column("atttypmod", "INTEGER", false),
                new Column("attbyval", "BOOLEAN", false),
                new Column("attalign", "TEXT", false),
                new Column("attstorage", "TEXT", false),
                new Column("attcompression", "TEXT", false),
                new Column("attnotnull", "BOOLEAN", false),
                new Column("atthasdef", "BOOLEAN", false),
                new Column("atthasmissing", "BOOLEAN", false),
                new Column("attidentity", "TEXT", false),
                new Column("attgenerated", "TEXT", false),
                new Column("attisdropped", "BOOLEAN", false),
                new Column("attislocal", "BOOLEAN", false),
                new Column("attinhcount", "INTEGER", false),
                new Column("attcollation", "BIGINT", false),
                new Column("attacl", "TEXT", false),
                new Column("attoptions", "TEXT", false),
                new Column("attfdwoptions", "TEXT", false),
                new Column("attmissingval", "TEXT", false)
            ),
            Arrays.asList("attrelid", "attnum")
        );
    }
    
    /**
     * pg_type: Data types
     */
    public static PgCatalogTable pgType() {
        return new PgCatalogTable(
            -4,
            "pg_type",
            Arrays.asList(
                new Column("oid", "BIGINT", true),
                new Column("typname", "TEXT", true),
                new Column("typnamespace", "BIGINT", true),
                new Column("typowner", "BIGINT", false),
                new Column("typlen", "INTEGER", false),
                new Column("typbyval", "BOOLEAN", false),
                new Column("typtype", "TEXT", false),
                new Column("typcategory", "TEXT", false),
                new Column("typispreferred", "BOOLEAN", false),
                new Column("typisdefined", "BOOLEAN", false),
                new Column("typdelim", "TEXT", false),
                new Column("typrelid", "BIGINT", false),
                new Column("typsubscript", "TEXT", false),
                new Column("typelem", "BIGINT", false),
                new Column("typarray", "BIGINT", false)
            ),
            Collections.singletonList("oid")
        );
    }
    
    /**
     * pg_index: Index definitions
     */
    public static PgCatalogTable pgIndex() {
        return new PgCatalogTable(
            -5,
            "pg_index",
            Arrays.asList(
                new Column("indexrelid", "BIGINT", true),  // OID of the index
                new Column("indrelid", "BIGINT", true),    // OID of the table
                new Column("indnatts", "INTEGER", false),  // Number of columns
                new Column("indnkeyatts", "INTEGER", false),
                new Column("indisunique", "BOOLEAN", false),
                new Column("indnullsnotdistinct", "BOOLEAN", false),
                new Column("indisprimary", "BOOLEAN", false),
                new Column("indisexclusion", "BOOLEAN", false),
                new Column("indimmediate", "BOOLEAN", false),
                new Column("indisclustered", "BOOLEAN", false),
                new Column("indisvalid", "BOOLEAN", false),
                new Column("indcheckxmin", "BOOLEAN", false),
                new Column("indisready", "BOOLEAN", false),
                new Column("indislive", "BOOLEAN", false),
                new Column("indisreplident", "BOOLEAN", false),
                new Column("indkey", "TEXT", false),  // Array of column numbers
                new Column("indcollation", "TEXT", false),
                new Column("indclass", "TEXT", false),
                new Column("indoption", "TEXT", false),
                new Column("indexprs", "TEXT", false),
                new Column("indpred", "TEXT", false)
            ),
            Collections.singletonList("indexrelid")
        );
    }
    
    /**
     * pg_constraint: Constraints
     */
    public static PgCatalogTable pgConstraint() {
        return new PgCatalogTable(
            -6,
            "pg_constraint",
            Arrays.asList(
                new Column("oid", "BIGINT", true),
                new Column("conname", "TEXT", true),
                new Column("connamespace", "BIGINT", true),
                new Column("contype", "TEXT", true),  // 'c' = check, 'f' = foreign key, 'p' = primary key, 'u' = unique
                new Column("condeferrable", "BOOLEAN", false),
                new Column("condeferred", "BOOLEAN", false),
                new Column("convalidated", "BOOLEAN", false),
                new Column("conrelid", "BIGINT", false),
                new Column("contypid", "BIGINT", false),
                new Column("conindid", "BIGINT", false),
                new Column("conparentid", "BIGINT", false),
                new Column("confrelid", "BIGINT", false),
                new Column("confupdtype", "TEXT", false),
                new Column("confdeltype", "TEXT", false),
                new Column("confmatchtype", "TEXT", false),
                new Column("conislocal", "BOOLEAN", false),
                new Column("coninhcount", "INTEGER", false),
                new Column("connoinherit", "BOOLEAN", false),
                new Column("conkey", "TEXT", false),
                new Column("confkey", "TEXT", false),
                new Column("conpfeqop", "TEXT", false),
                new Column("conppeqop", "TEXT", false),
                new Column("conffeqop", "TEXT", false),
                new Column("confdelsetcols", "TEXT", false),
                new Column("conexclop", "TEXT", false),
                new Column("conbin", "TEXT", false)
            ),
            Collections.singletonList("oid")
        );
    }
    
    /**
     * pg_attrdef: Column defaults
     */
    public static PgCatalogTable pgAttrdef() {
        return new PgCatalogTable(
            -7,
            "pg_attrdef",
            Arrays.asList(
                new Column("oid", "BIGINT", true),
                new Column("adrelid", "BIGINT", true),
                new Column("adnum", "INTEGER", true),
                new Column("adbin", "TEXT", false)
            ),
            Collections.singletonList("oid")
        );
    }
    
    /**
     * pg_description: Object comments
     */
    public static PgCatalogTable pgDescription() {
        return new PgCatalogTable(
            -8,
            "pg_description",
            Arrays.asList(
                new Column("objoid", "BIGINT", true),
                new Column("classoid", "BIGINT", true),
                new Column("objsubid", "INTEGER", true),
                new Column("description", "TEXT", false)
            ),
            Arrays.asList("objoid", "classoid", "objsubid")
        );
    }
    
    /**
     * pg_am - Access methods (heap, btree, hash, etc.)
     */
    public static PgCatalogTable pgAm() {
        return new PgCatalogTable(
            -12,
            "pg_am",
            Arrays.asList(
                new Column("oid", "BIGINT", false),
                new Column("amname", "TEXT", false),
                new Column("amhandler", "BIGINT", false),
                new Column("amtype", "TEXT", false)  // 'i' = index, 't' = table
            ),
            Arrays.asList("oid")
        );
    }
    
    /**
     * pg_tables - Simplified view of user tables (PostgreSQL compatibility)
     */
    public static PgCatalogTable pgTables() {
        return new PgCatalogTable(
            -1000,
            "pg_tables",
            Arrays.asList(
                new Column("schemaname", "TEXT", false),
                new Column("tablename", "TEXT", false),
                new Column("tableowner", "TEXT", false)
            ),
            Arrays.asList("schemaname", "tablename")
        );
    }
    
    /**
     * pg_indexes - Simplified view of indexes (PostgreSQL compatibility)
     */
    public static PgCatalogTable pgIndexes() {
        return new PgCatalogTable(
            -1001,
            "pg_indexes",
            Arrays.asList(
                new Column("schemaname", "TEXT", false),
                new Column("tablename", "TEXT", false),
                new Column("indexname", "TEXT", false),
                new Column("indexdef", "TEXT", false)
            ),
            Arrays.asList("schemaname", "tablename", "indexname")
        );
    }
}

