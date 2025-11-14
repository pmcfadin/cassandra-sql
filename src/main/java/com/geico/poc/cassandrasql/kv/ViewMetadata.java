package com.geico.poc.cassandrasql.kv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for SQL views in KV mode.
 * 
 * Supports two types of views:
 * 1. Virtual Views (VIEW): Query is rewritten at runtime, no data stored
 * 2. Materialized Views (MATERIALIZED VIEW): Results are stored and refreshed
 */
public class ViewMetadata {
    
    private final long viewId;
    private final String viewName;
    private final String queryDefinition;
    private final boolean materialized;
    private final List<ColumnInfo> columns;
    private final List<IndexMetadata> indexes;
    private final Long lastRefreshTimestamp;
    private final Long creationTimestamp;
    private final Long droppedTimestamp;
    
    @JsonCreator
    public ViewMetadata(
            @JsonProperty("viewId") long viewId,
            @JsonProperty("viewName") String viewName,
            @JsonProperty("queryDefinition") String queryDefinition,
            @JsonProperty("materialized") boolean materialized,
            @JsonProperty("columns") List<ColumnInfo> columns,
            @JsonProperty("indexes") List<IndexMetadata> indexes,
            @JsonProperty("lastRefreshTimestamp") Long lastRefreshTimestamp,
            @JsonProperty("creationTimestamp") Long creationTimestamp,
            @JsonProperty("droppedTimestamp") Long droppedTimestamp) {
        this.viewId = viewId;
        this.viewName = viewName;
        this.queryDefinition = queryDefinition;
        this.materialized = materialized;
        this.columns = columns != null ? columns : new ArrayList<>();
        this.indexes = indexes != null ? indexes : new ArrayList<>();
        this.lastRefreshTimestamp = lastRefreshTimestamp;
        this.creationTimestamp = creationTimestamp != null ? creationTimestamp : System.currentTimeMillis();
        this.droppedTimestamp = droppedTimestamp;
    }
    
    // Constructor for new view
    public ViewMetadata(long viewId, String viewName, String queryDefinition, 
                       boolean materialized, List<ColumnInfo> columns) {
        this(viewId, viewName, queryDefinition, materialized, columns, null, null,
             System.currentTimeMillis(), null);
    }
    
    public long getViewId() {
        return viewId;
    }
    
    public String getViewName() {
        return viewName;
    }
    
    public String getQueryDefinition() {
        return queryDefinition;
    }
    
    public boolean isMaterialized() {
        return materialized;
    }
    
    public List<ColumnInfo> getColumns() {
        return columns;
    }
    
    public List<IndexMetadata> getIndexes() {
        return indexes;
    }
    
    public Long getLastRefreshTimestamp() {
        return lastRefreshTimestamp;
    }
    
    public Long getCreationTimestamp() {
        return creationTimestamp;
    }
    
    public Long getDroppedTimestamp() {
        return droppedTimestamp;
    }
    
    public boolean isDropped() {
        return droppedTimestamp != null;
    }
    
    /**
     * Create a new version with updated refresh timestamp
     */
    public ViewMetadata withRefreshTimestamp(long refreshTs) {
        return new ViewMetadata(
            viewId,
            viewName,
            queryDefinition,
            materialized,
            columns,
            indexes,
            refreshTs,
            creationTimestamp,
            droppedTimestamp
        );
    }
    
    /**
     * Create a new version marked as dropped
     */
    public ViewMetadata withDroppedTimestamp(long droppedTs) {
        return new ViewMetadata(
            viewId,
            viewName,
            queryDefinition,
            materialized,
            columns,
            indexes,
            lastRefreshTimestamp,
            creationTimestamp,
            droppedTs
        );
    }
    
    /**
     * Create a new version with updated indexes
     */
    public ViewMetadata withIndexes(List<IndexMetadata> newIndexes) {
        return new ViewMetadata(
            viewId,
            viewName,
            queryDefinition,
            materialized,
            columns,
            newIndexes,
            lastRefreshTimestamp,
            creationTimestamp,
            droppedTimestamp
        );
    }
    
    public ViewMetadata withColumns(List<ColumnInfo> newColumns) {
        return new ViewMetadata(
            viewId,
            viewName,
            queryDefinition,
            materialized,
            newColumns,
            indexes,
            lastRefreshTimestamp,
            creationTimestamp,
            droppedTimestamp
        );
    }
    
    @Override
    public String toString() {
        return "ViewMetadata{" +
                "viewId=" + viewId +
                ", viewName='" + viewName + '\'' +
                ", materialized=" + materialized +
                ", columns=" + columns.size() +
                ", lastRefresh=" + lastRefreshTimestamp +
                (droppedTimestamp != null ? ", DROPPED" : "") +
                '}';
    }
    
    /**
     * Column information for a view
     */
    public static class ColumnInfo {
        private final String name;
        private final String type;
        
        @JsonCreator
        public ColumnInfo(
                @JsonProperty("name") String name,
                @JsonProperty("type") String type) {
            this.name = name;
            this.type = type;
        }
        
        public String getName() {
            return name;
        }
        
        public String getType() {
            return type;
        }
        
        @Override
        public String toString() {
            return name + " " + type;
        }
    }
    
    /**
     * Index metadata for materialized views
     */
    public static class IndexMetadata {
        private final long indexId;
        private final String name;
        private final List<String> columns;
        private final boolean building;
        
        @JsonCreator
        public IndexMetadata(
                @JsonProperty("indexId") long indexId,
                @JsonProperty("name") String name,
                @JsonProperty("columns") List<String> columns,
                @JsonProperty("building") Boolean building) {
            this.indexId = indexId;
            this.name = name;
            this.columns = columns != null ? columns : new ArrayList<>();
            this.building = building != null ? building : false;
        }
        
        // Constructor without building flag (defaults to false)
        public IndexMetadata(long indexId, String name, List<String> columns) {
            this(indexId, name, columns, false);
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
        
        public boolean isBuilding() {
            return building;
        }
        
        @Override
        public String toString() {
            return "Index{" +
                    "id=" + indexId +
                    ", name='" + name + '\'' +
                    ", columns=" + columns +
                    (building ? ", BUILDING" : "") +
                    '}';
        }
    }
}

