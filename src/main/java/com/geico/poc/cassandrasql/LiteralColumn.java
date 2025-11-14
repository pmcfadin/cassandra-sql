package com.geico.poc.cassandrasql;

/**
 * Represents a literal expression in a SELECT list
 * Example: SELECT 'Branches' as table_name, COUNT(*) ...
 */
public class LiteralColumn {
    private final String alias;
    private final Object value;
    private final int position; // Position in SELECT list
    
    public LiteralColumn(String alias, Object value, int position) {
        this.alias = alias;
        this.value = value;
        this.position = position;
    }
    
    public String getAlias() {
        return alias;
    }
    
    public Object getValue() {
        return value;
    }
    
    public int getPosition() {
        return position;
    }
    
    @Override
    public String toString() {
        return "LiteralColumn{" +
                "alias='" + alias + '\'' +
                ", value=" + value +
                ", position=" + position +
                '}';
    }
}

