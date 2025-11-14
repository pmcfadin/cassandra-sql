package com.geico.poc.cassandrasql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a GROUP BY key (combination of column values)
 */
public class GroupKey {
    
    private final Map<String, Object> values;  // Column name -> value
    
    public GroupKey(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
    }
    
    public Map<String, Object> getValues() {
        return values;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupKey groupKey = (GroupKey) o;
        return Objects.equals(values, groupKey.values);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(values);
    }
    
    @Override
    public String toString() {
        return "GroupKey" + values;
    }
}







