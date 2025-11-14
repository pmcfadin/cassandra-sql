package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.util.ImmutableBitSet;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Calcite table adapter for KV storage mode.
 * Represents a single table in the KV store.
 */
public class KvTable extends AbstractQueryableTable {
    
    private static final Logger log = LoggerFactory.getLogger(KvTable.class);
    
    private final TableMetadata metadata;
    private final SchemaManager schemaManager;
    private final KvStatistic statistic;
    
    public KvTable(TableMetadata metadata, SchemaManager schemaManager) {
        super(Object[].class);
        this.metadata = metadata;
        this.schemaManager = schemaManager;
        this.statistic = new KvStatistic(metadata, schemaManager);
    }
    
    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        
        // Add all columns with their types
        for (TableMetadata.ColumnMetadata column : metadata.getColumns()) {
            RelDataType columnType = KvTypeFactory.toCalciteType(typeFactory, column.getType());
            // Use uppercase to match Calcite's case-insensitive behavior
            builder.add(column.getName().toUpperCase(), columnType);
        }
        
        return builder.build();
    }
    
    @Override
    public Statistic getStatistic() {
        return statistic;
    }
    
    @Override
    public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
        throw new UnsupportedOperationException("KvTable does not support LINQ queries");
    }
    
    /**
     * Get the underlying table metadata
     */
    public TableMetadata getMetadata() {
        return metadata;
    }
    
    /**
     * Statistics for cost-based optimization
     */
    private static class KvStatistic implements Statistic {
        private final TableMetadata metadata;
        private final SchemaManager schemaManager;
        private Double rowCount;
        
        public KvStatistic(TableMetadata metadata, SchemaManager schemaManager) {
            this.metadata = metadata;
            this.schemaManager = schemaManager;
        }
        
        @Override
        public Double getRowCount() {
            if (rowCount == null) {
                // TODO: Get actual row count from StatisticsCollectorJob
                // For now, use a default estimate
                rowCount = 1000.0;
                log.debug("Using default row count estimate for table {}: {}", 
                         metadata.getTableName(), rowCount);
            }
            return rowCount;
        }
        
        @Override
        public boolean isKey(ImmutableBitSet columns) {
            // Check if the columns form a primary key or unique index
            List<String> pkColumns = metadata.getPrimaryKeyColumns();
            
            // Convert column indices to names
            List<String> columnNames = new ArrayList<>();
            int index = 0;
            for (TableMetadata.ColumnMetadata col : metadata.getColumns()) {
                if (columns.get(index)) {
                    columnNames.add(col.getName());
                }
                index++;
            }
            
            // Check if it matches the primary key
            if (columnNames.size() == pkColumns.size() && 
                columnNames.containsAll(pkColumns)) {
                return true;
            }
            
            // TODO: Check unique indexes
            return false;
        }
        
        @Override
        public List<ImmutableBitSet> getKeys() {
            // Return primary key columns as a key
            List<ImmutableBitSet> keys = new ArrayList<>();
            
            List<String> pkColumns = metadata.getPrimaryKeyColumns();
            ImmutableBitSet.Builder builder = ImmutableBitSet.builder();
            
            int index = 0;
            for (TableMetadata.ColumnMetadata col : metadata.getColumns()) {
                if (pkColumns.contains(col.getName())) {
                    builder.set(index);
                }
                index++;
            }
            
            keys.add(builder.build());
            return keys;
        }
        
        @Override
        public List<RelReferentialConstraint> getReferentialConstraints() {
            // KV mode doesn't support foreign keys yet
            return new ArrayList<>();
        }
        
        @Override
        public List<RelCollation> getCollations() {
            // Return collations for indexes
            // Primary key is always sorted
            List<RelCollation> collations = new ArrayList<>();
            
            // TODO: Add collations for secondary indexes
            
            return collations;
        }
        
        @Override
        public RelDistribution getDistribution() {
            // KV is hash-distributed by primary key
            // Return null to indicate default distribution
            return null;
        }
    }
}

