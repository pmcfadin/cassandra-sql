package com.geico.poc.cassandrasql;

/**
 * Represents an aggregate function (COUNT, SUM, AVG, MIN, MAX)
 */
public class AggregateFunction {
    
    public enum Type {
        COUNT,
        SUM,
        AVG,
        MIN,
        MAX
    }
    
    private final Type type;
    private final String column;      // Column name or "*" for COUNT(*)
    private final String alias;       // Result column name (e.g., "order_count")
    
    public AggregateFunction(Type type, String column, String alias) {
        this.type = type;
        this.column = column;
        this.alias = alias;
    }
    
    public Type getType() {
        return type;
    }
    
    public String getColumn() {
        return column;
    }
    
    public String getAlias() {
        return alias;
    }
    
    public boolean isCountStar() {
        return type == Type.COUNT && "*".equals(column);
    }
    
    @Override
    public String toString() {
        return String.format("%s(%s) AS %s", type, column, alias);
    }
}







