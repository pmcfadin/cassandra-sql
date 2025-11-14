package com.geico.poc.cassandrasql.transaction;

import java.util.Objects;

/**
 * Represents a row key (table + primary key values)
 */
public class RowKey {
    private final String tableName;
    private final String keyValue;  // Simplified: single key value as string
    
    public RowKey(String tableName, String keyValue) {
        this.tableName = tableName;
        this.keyValue = keyValue;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public String getKeyValue() {
        return keyValue;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RowKey rowKey = (RowKey) o;
        return Objects.equals(tableName, rowKey.tableName) &&
               Objects.equals(keyValue, rowKey.keyValue);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(tableName, keyValue);
    }
    
    @Override
    public String toString() {
        return tableName + ":" + keyValue;
    }
}







