package com.geico.poc.cassandrasql.kv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for ENUM types in KV mode.
 * 
 * ENUM types define a set of allowed string values for a column.
 * Example: CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered')
 */
public class EnumMetadata {
    
    private final String typeName;
    private final List<String> values;
    private final Long creationTimestamp;
    private final Long droppedTimestamp;
    
    @JsonCreator
    public EnumMetadata(
            @JsonProperty("typeName") String typeName,
            @JsonProperty("values") List<String> values,
            @JsonProperty("creationTimestamp") Long creationTimestamp,
            @JsonProperty("droppedTimestamp") Long droppedTimestamp) {
        this.typeName = typeName;
        this.values = values != null ? values : new ArrayList<>();
        this.creationTimestamp = creationTimestamp != null ? creationTimestamp : System.currentTimeMillis();
        this.droppedTimestamp = droppedTimestamp;
    }
    
    // Constructor for new enum type
    public EnumMetadata(String typeName, List<String> values) {
        this(typeName, values, System.currentTimeMillis(), null);
    }
    
    public String getTypeName() {
        return typeName;
    }
    
    public List<String> getValues() {
        return values;
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
     * Check if a value is valid for this enum
     */
    public boolean isValidValue(String value) {
        if (value == null) {
            return false;
        }
        return values.contains(value);
    }
    
    /**
     * Create a new version marked as dropped
     */
    public EnumMetadata withDroppedTimestamp(long droppedTs) {
        return new EnumMetadata(
            typeName,
            values,
            creationTimestamp,
            droppedTs
        );
    }
    
    @Override
    public String toString() {
        return "EnumMetadata{" +
                "typeName='" + typeName + '\'' +
                ", values=" + values +
                ", creationTimestamp=" + creationTimestamp +
                (droppedTimestamp != null ? ", DROPPED" : "") +
                '}';
    }
}



