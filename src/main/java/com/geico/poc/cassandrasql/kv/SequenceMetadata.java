package com.geico.poc.cassandrasql.kv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata for a database sequence (like PostgreSQL sequences).
 * 
 * Sequences are independent of tables and can be used for:
 * - Generating unique IDs
 * - Custom auto-increment logic
 * - Shared sequences across multiple tables
 * 
 * PostgreSQL-compatible features:
 * - INCREMENT BY: Step size for each nextval() call
 * - START WITH: Initial value
 * - MINVALUE/MAXVALUE: Bounds checking
 * - CYCLE: Whether to wrap around at max
 * - CACHE: Number of values to pre-allocate (for performance)
 */
public class SequenceMetadata {
    private final String sequenceName;
    private final long increment;
    private final long startValue;
    private final Long minValue;  // null = no minimum
    private final Long maxValue;  // null = no maximum
    private final boolean cycle;
    private final int cache;
    private final long createdAt;
    
    @JsonCreator
    public SequenceMetadata(
            @JsonProperty("sequenceName") String sequenceName,
            @JsonProperty("increment") long increment,
            @JsonProperty("startValue") long startValue,
            @JsonProperty("minValue") Long minValue,
            @JsonProperty("maxValue") Long maxValue,
            @JsonProperty("cycle") boolean cycle,
            @JsonProperty("cache") int cache,
            @JsonProperty("createdAt") long createdAt) {
        this.sequenceName = sequenceName;
        this.increment = increment;
        this.startValue = startValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.cycle = cycle;
        this.cache = cache;
        this.createdAt = createdAt;
    }
    
    /**
     * Create a sequence with default PostgreSQL values
     */
    public static SequenceMetadata createDefault(String sequenceName) {
        return new SequenceMetadata(
            sequenceName,
            1,              // INCREMENT BY 1
            1,              // START WITH 1
            1L,             // MINVALUE 1
            Long.MAX_VALUE, // MAXVALUE (essentially unlimited)
            false,          // NO CYCLE
            1,              // CACHE 1 (no caching by default)
            System.currentTimeMillis()
        );
    }
    
    public String getSequenceName() {
        return sequenceName;
    }
    
    public long getIncrement() {
        return increment;
    }
    
    public long getStartValue() {
        return startValue;
    }
    
    public Long getMinValue() {
        return minValue;
    }
    
    public Long getMaxValue() {
        return maxValue;
    }
    
    public boolean isCycle() {
        return cycle;
    }
    
    public int getCache() {
        return cache;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    /**
     * Validate that a value is within bounds
     */
    public boolean isWithinBounds(long value) {
        if (minValue != null && value < minValue) {
            return false;
        }
        if (maxValue != null && value > maxValue) {
            return false;
        }
        return true;
    }
    
    /**
     * Get the next value after current, respecting increment and bounds
     */
    public Long getNextValue(long currentValue) {
        long nextValue = currentValue + increment;
        
        // Check bounds
        if (maxValue != null && nextValue > maxValue) {
            if (cycle) {
                // Wrap around to minValue (or startValue if no minValue)
                return minValue != null ? minValue : startValue;
            } else {
                // Sequence exhausted
                return null;
            }
        }
        
        if (minValue != null && nextValue < minValue) {
            if (cycle) {
                // Wrap around to maxValue (or startValue if no maxValue)
                return maxValue != null ? maxValue : startValue;
            } else {
                // Sequence exhausted
                return null;
            }
        }
        
        return nextValue;
    }
    
    @Override
    public String toString() {
        return "Sequence{" +
                "name='" + sequenceName + '\'' +
                ", increment=" + increment +
                ", start=" + startValue +
                ", min=" + minValue +
                ", max=" + maxValue +
                ", cycle=" + cycle +
                ", cache=" + cache +
                '}';
    }
}


