package com.geico.poc.cassandrasql.kv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for a table in KV storage mode.
 * 
 * Stored in kv_store with key: [0x03][TableID][0x00...00]["schema"]
 */
public class TableMetadata {
    
    private final long tableId;
    private final String tableName;
    private final List<ColumnMetadata> columns;
    private final List<String> primaryKeyColumns;
    private final List<IndexMetadata> indexes;
    private final List<UniqueConstraint> uniqueConstraints;
    private final List<ForeignKeyConstraint> foreignKeyConstraints;
    private final int version;
    private final Long truncateTimestamp;  // Timestamp of last TRUNCATE (null if never truncated)
    private final Long droppedTimestamp;   // Timestamp when table was dropped (null if active)
    
    @JsonCreator
    public TableMetadata(
            @JsonProperty("tableId") long tableId,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("columns") List<ColumnMetadata> columns,
            @JsonProperty("primaryKeyColumns") List<String> primaryKeyColumns,
            @JsonProperty("indexes") List<IndexMetadata> indexes,
            @JsonProperty("uniqueConstraints") List<UniqueConstraint> uniqueConstraints,
            @JsonProperty("foreignKeyConstraints") List<ForeignKeyConstraint> foreignKeyConstraints,
            @JsonProperty("version") int version,
            @JsonProperty("truncateTimestamp") Long truncateTimestamp,
            @JsonProperty("droppedTimestamp") Long droppedTimestamp) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.primaryKeyColumns = primaryKeyColumns != null ? primaryKeyColumns : new ArrayList<>();
        this.indexes = indexes != null ? indexes : new ArrayList<>();
        this.uniqueConstraints = uniqueConstraints != null ? uniqueConstraints : new ArrayList<>();
        this.foreignKeyConstraints = foreignKeyConstraints != null ? foreignKeyConstraints : new ArrayList<>();
        this.version = version;
        this.truncateTimestamp = truncateTimestamp;
        this.droppedTimestamp = droppedTimestamp;
    }
    
    // Constructor without truncateTimestamp and droppedTimestamp (for backward compatibility)
    public TableMetadata(
            long tableId,
            String tableName,
            List<ColumnMetadata> columns,
            List<String> primaryKeyColumns,
            List<IndexMetadata> indexes,
            int version) {
        this(tableId, tableName, columns, primaryKeyColumns, indexes, new ArrayList<>(), new ArrayList<>(), version, null, null);
    }
    
    // Constructor with truncateTimestamp but without droppedTimestamp (for backward compatibility)
    public TableMetadata(
            long tableId,
            String tableName,
            List<ColumnMetadata> columns,
            List<String> primaryKeyColumns,
            List<IndexMetadata> indexes,
            int version,
            Long truncateTimestamp) {
        this(tableId, tableName, columns, primaryKeyColumns, indexes, new ArrayList<>(), new ArrayList<>(), version, truncateTimestamp, null);
    }
    
    // Getters
    
    public long getTableId() {
        return tableId;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public List<ColumnMetadata> getColumns() {
        return columns;
    }
    
    public List<String> getPrimaryKeyColumns() {
        return primaryKeyColumns;
    }
    
    public List<IndexMetadata> getIndexes() {
        return indexes;
    }
    
    public List<UniqueConstraint> getUniqueConstraints() {
        return uniqueConstraints;
    }
    
    public List<ForeignKeyConstraint> getForeignKeyConstraints() {
        return foreignKeyConstraints;
    }
    
    public int getVersion() {
        return version;
    }
    
    public Long getTruncateTimestamp() {
        return truncateTimestamp;
    }
    
    public Long getDroppedTimestamp() {
        return droppedTimestamp;
    }
    
    /**
     * Check if this table has been dropped (lazy drop)
     */
    @JsonIgnore
    public boolean isDropped() {
        return droppedTimestamp != null;
    }
    
    /**
     * Create a copy of this table with droppedTimestamp set (for lazy drop)
     */
    public TableMetadata withDroppedTimestamp(long droppedTs) {
        return new TableMetadata(
            tableId,
            tableName,
            columns,
            primaryKeyColumns,
            indexes,
            uniqueConstraints,
            foreignKeyConstraints,
            version,
            truncateTimestamp,
            droppedTs
        );
    }
    
    // Helper methods
    
    @JsonIgnore
    public ColumnMetadata getColumn(String columnName) {
        return columns.stream()
                .filter(c -> c.getName().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }
    
    @JsonIgnore
    public boolean hasColumn(String columnName) {
        return getColumn(columnName) != null;
    }
    
    @JsonIgnore
    public boolean isPrimaryKeyColumn(String columnName) {
        return primaryKeyColumns.stream()
                .anyMatch(pk -> pk.equalsIgnoreCase(columnName));
    }
    
    @JsonIgnore
    public List<ColumnMetadata> getPrimaryKeyColumnMetadata() {
        List<ColumnMetadata> pkColumns = new ArrayList<>();
        for (String pkName : primaryKeyColumns) {
            ColumnMetadata col = getColumn(pkName);
            if (col != null) {
                pkColumns.add(col);
            }
        }
        return pkColumns;
    }
    
    @JsonIgnore
    public List<ColumnMetadata> getNonPrimaryKeyColumns() {
        List<ColumnMetadata> nonPkColumns = new ArrayList<>();
        for (ColumnMetadata col : columns) {
            if (!isPrimaryKeyColumn(col.getName())) {
                nonPkColumns.add(col);
            }
        }
        return nonPkColumns;
    }
    
    @JsonIgnore
    public List<String> getAllColumns() {
        List<String> allColumns = new ArrayList<>();
        for (ColumnMetadata col : columns) {
            allColumns.add(col.getName());
        }
        return allColumns;
    }
    
    @JsonIgnore
    public IndexMetadata getIndex(String indexName) {
        return indexes.stream()
                .filter(idx -> idx.getName().equalsIgnoreCase(indexName))
                .findFirst()
                .orElse(null);
    }
    
    @JsonIgnore
    public IndexMetadata getIndexById(long indexId) {
        return indexes.stream()
                .filter(idx -> idx.getIndexId() == indexId)
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableMetadata that = (TableMetadata) o;
        return tableId == that.tableId;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(tableId);
    }
    
    @Override
    public String toString() {
        return "TableMetadata{" +
                "tableId=" + tableId +
                ", tableName='" + tableName + '\'' +
                ", columns=" + columns.size() +
                ", primaryKey=" + primaryKeyColumns +
                ", indexes=" + indexes.size() +
                ", version=" + version +
                '}';
    }
    
    /**
     * Column metadata
     */
    public static class ColumnMetadata {
        private final String name;
        private final String type;
        private final boolean nullable;
        private final boolean autoIncrement;
        private final String identityGeneration;  // "ALWAYS" or "BY DEFAULT" for IDENTITY columns
        
        @JsonCreator
        public ColumnMetadata(
                @JsonProperty("name") String name,
                @JsonProperty("type") String type,
                @JsonProperty("nullable") boolean nullable,
                @JsonProperty("autoIncrement") Boolean autoIncrement,
                @JsonProperty("identityGeneration") String identityGeneration) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.autoIncrement = autoIncrement != null ? autoIncrement : false;
            this.identityGeneration = identityGeneration;
        }
        
        // Convenience constructor for backwards compatibility
        public ColumnMetadata(String name, String type, boolean nullable) {
            this(name, type, nullable, false, null);
        }
        
        public String getName() {
            return name;
        }
        
        public String getType() {
            return type;
        }
        
        public boolean isNullable() {
            return nullable;
        }
        
        public boolean isAutoIncrement() {
            return autoIncrement;
        }
        
        public String getIdentityGeneration() {
            return identityGeneration;
        }
        
        @JsonIgnore
        public boolean isGeneratedAlways() {
            return "ALWAYS".equalsIgnoreCase(identityGeneration);
        }
        
        @JsonIgnore
        public Class<?> getJavaType() {
            String typeUpper = type.toUpperCase();
            
            // Handle types with precision/scale (e.g., NUMERIC(10,2), DECIMAL(5,2))
            if (typeUpper.startsWith("NUMERIC") || typeUpper.startsWith("DECIMAL")) {
                return java.math.BigDecimal.class;
            }
            
            // Handle ARRAY types
            if (typeUpper.endsWith("[]") || typeUpper.startsWith("ARRAY")) {
                return Object[].class;  // Generic array type
            }
            
            // Handle ENUM types
            if (typeUpper.startsWith("ENUM")) {
                return String.class;  // ENUMs stored as strings
            }
            
            switch (typeUpper) {
                case "INT":
                case "INTEGER":
                case "SERIAL":
                case "SMALLSERIAL":
                case "SMALLINT":
                    return Integer.class;
                case "BIGINT":
                case "LONG":
                case "BIGSERIAL":
                    return Long.class;
                case "TEXT":
                case "VARCHAR":
                case "CHAR":
                    return String.class;
                case "JSONB":
                case "JSON":
                    return String.class;  // JSON stored as UTF-8 string
                case "BOOLEAN":
                case "BOOL":
                    return Boolean.class;
                case "DOUBLE":
                case "DOUBLE PRECISION":
                    return Double.class;
                case "REAL":
                case "FLOAT":
                    return Float.class;
                case "BLOB":
                case "BYTEA":
                    return byte[].class;
                case "DATE":
                    return java.sql.Date.class;
                case "TIME":
                    return java.sql.Time.class;
                case "TIMESTAMP":
                case "TIMESTAMPTZ":
                    return java.sql.Timestamp.class;
                case "INTERVAL":
                    return String.class;  // Store intervals as ISO-8601 duration strings
                default:
                    return String.class;
            }
        }
        
        @JsonIgnore
        public boolean isJsonType() {
            String typeUpper = type.toUpperCase();
            return typeUpper.equals("JSON") || typeUpper.equals("JSONB");
        }
        
        @Override
        public String toString() {
            return name + " " + type + (nullable ? "" : " NOT NULL");
        }
    }
    
    /**
     * Index metadata
     */
    public static class IndexMetadata {
        private final long indexId;
        private final String name;
        private final List<String> columns;
        private final boolean unique;
        private final boolean building;  // True during backfill, false when ready
        
        // Statistics for cost-based optimization
        private volatile long distinctValues;  // Cardinality (number of distinct values)
        private volatile long totalRows;       // Total rows in index
        private volatile long lastUpdated;     // Timestamp of last statistics update
        private volatile double selectivity;     // Selectivity of the index
        
        @JsonCreator
        public IndexMetadata(
                @JsonProperty("indexId") long indexId,
                @JsonProperty("name") String name,
                @JsonProperty("columns") List<String> columns,
                @JsonProperty("unique") boolean unique,
                @JsonProperty("building") Boolean building,
                @JsonProperty("selectivity") Double selectivity) {
            this.indexId = indexId;
            this.name = name;
            this.columns = columns != null ? columns : new ArrayList<>();
            this.unique = unique;
            this.building = building != null ? building : false;
            this.distinctValues = 0;
            this.totalRows = 0;
            this.lastUpdated = 0;
            this.selectivity = selectivity != null ? selectivity : 0.0;
        }
        
        // Backward-compatible constructor
        public IndexMetadata(long indexId, String name, List<String> columns, boolean unique) {
            this(indexId, name, columns, unique, false, 0.0);
        }
        
        public long getIndexId() {
            return indexId;
        }
        
        public String getName() {
            return name;
        }
        
        public List<String> getColumns() {
            return columns;
        }
        
        public boolean isUnique() {
            return unique;
        }
        
        public boolean isBuilding() {
            return building;
        }
        
        // Statistics getters and setters
        public long getDistinctValues() {
            return distinctValues;
        }
        
        public void setDistinctValues(long distinctValues) {
            this.distinctValues = distinctValues;
        }
        
        public long getTotalRows() {
            return totalRows;
        }
        
        public void setTotalRows(long totalRows) {
            this.totalRows = totalRows;
        }
        
        public long getLastUpdated() {
            return lastUpdated;
        }
        
        public void setLastUpdated(long lastUpdated) {
            this.lastUpdated = lastUpdated;
        }
        
        /**
         * Calculate selectivity (ratio of distinct values to total rows)
         * Returns value between 0.0 (low selectivity) and 1.0 (high selectivity)
         */
        public double getSelectivity() {
            if (totalRows == 0) {
                return 1.0; // Assume high selectivity for empty index
            }
            if (unique) {
                return 1.0; // UNIQUE indexes have perfect selectivity
            }
            return Math.min(1.0, (double) distinctValues / totalRows);
        }
        
        @Override
        public String toString() {
            return "Index{" + name + " on " + columns + (unique ? " UNIQUE" : "") + 
                   (building ? " (BUILDING)" : "") + 
                   ", cardinality=" + distinctValues + "/" + totalRows + "}";
        }
    }
    
    /**
     * UNIQUE constraint metadata
     */
    public static class UniqueConstraint {
        private final String name;
        private final List<String> columns;
        
        @JsonCreator
        public UniqueConstraint(
                @JsonProperty("name") String name,
                @JsonProperty("columns") List<String> columns) {
            this.name = name;
            this.columns = columns != null ? columns : new ArrayList<>();
        }
        
        public String getName() {
            return name;
        }
        
        public List<String> getColumns() {
            return columns;
        }
        
        @Override
        public String toString() {
            return "UNIQUE(" + String.join(", ", columns) + ")";
        }
    }
    
    /**
     * FOREIGN KEY constraint metadata
     */
    public static class ForeignKeyConstraint {
        private final String name;
        private final List<String> columns;
        private final String referencedTable;
        private final List<String> referencedColumns;
        private final String onDelete;  // CASCADE, SET NULL, RESTRICT, NO ACTION
        private final String onUpdate;  // CASCADE, SET NULL, RESTRICT, NO ACTION
        
        @JsonCreator
        public ForeignKeyConstraint(
                @JsonProperty("name") String name,
                @JsonProperty("columns") List<String> columns,
                @JsonProperty("referencedTable") String referencedTable,
                @JsonProperty("referencedColumns") List<String> referencedColumns,
                @JsonProperty("onDelete") String onDelete,
                @JsonProperty("onUpdate") String onUpdate) {
            this.name = name;
            this.columns = columns != null ? columns : new ArrayList<>();
            this.referencedTable = referencedTable;
            this.referencedColumns = referencedColumns != null ? referencedColumns : new ArrayList<>();
            this.onDelete = onDelete != null ? onDelete : "NO ACTION";
            this.onUpdate = onUpdate != null ? onUpdate : "NO ACTION";
        }
        
        public String getName() {
            return name;
        }
        
        public List<String> getColumns() {
            return columns;
        }
        
        public String getReferencedTable() {
            return referencedTable;
        }
        
        public List<String> getReferencedColumns() {
            return referencedColumns;
        }
        
        public String getOnDelete() {
            return onDelete;
        }
        
        public String getOnUpdate() {
            return onUpdate;
        }
        
        @Override
        public String toString() {
            return "FOREIGN KEY(" + String.join(", ", columns) + ") REFERENCES " + 
                   referencedTable + "(" + String.join(", ", referencedColumns) + ")";
        }
    }
}

