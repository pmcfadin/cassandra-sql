package com.geico.poc.cassandrasql;

/**
 * Represents a WHERE clause predicate
 */
public class Predicate {
    
    public enum Operator {
        EQUALS,           // =
        NOT_EQUALS,       // !=, <>
        GREATER_THAN,     // >
        GREATER_EQUAL,    // >=
        LESS_THAN,        // <
        LESS_EQUAL,       // <=
        IN,               // IN (...)
        LIKE,             // LIKE
        IS_NULL,          // IS NULL
        IS_NOT_NULL       // IS NOT NULL
    }
    
    private final String tableName;      // e.g., "users" or "u" (alias)
    private final String columnName;     // e.g., "name"
    private final Operator operator;
    private final Object value;          // The comparison value
    private final String originalSql;    // Original SQL fragment
    
    public Predicate(String tableName, String columnName, Operator operator, 
                     Object value, String originalSql) {
        this.tableName = tableName;
        this.columnName = columnName;
        this.operator = operator;
        this.value = value;
        this.originalSql = originalSql;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public String getColumnName() {
        return columnName;
    }
    
    public Operator getOperator() {
        return operator;
    }
    
    public Object getValue() {
        return value;
    }
    
    public String getOriginalSql() {
        return originalSql;
    }
    
    /**
     * Check if this predicate can be pushed down to Cassandra
     */
    public boolean canPushDown() {
        // Cassandra supports most basic operators
        switch (operator) {
            case EQUALS:
            case GREATER_THAN:
            case GREATER_EQUAL:
            case LESS_THAN:
            case LESS_EQUAL:
            case IN:
                return true;
            case LIKE:
            case NOT_EQUALS:
            case IS_NULL:
            case IS_NOT_NULL:
                // These can be pushed down but with limitations
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Convert to CQL WHERE clause fragment
     */
    public String toCql() {
        StringBuilder cql = new StringBuilder();
        cql.append(columnName);
        
        switch (operator) {
            case EQUALS:
                cql.append(" = ");
                appendValue(cql, value);
                break;
            case NOT_EQUALS:
                cql.append(" != ");
                appendValue(cql, value);
                break;
            case GREATER_THAN:
                cql.append(" > ");
                appendValue(cql, value);
                break;
            case GREATER_EQUAL:
                cql.append(" >= ");
                appendValue(cql, value);
                break;
            case LESS_THAN:
                cql.append(" < ");
                appendValue(cql, value);
                break;
            case LESS_EQUAL:
                cql.append(" <= ");
                appendValue(cql, value);
                break;
            case IN:
                cql.append(" IN ");
                appendValue(cql, value);
                break;
            case LIKE:
                cql.append(" LIKE ");
                appendValue(cql, value);
                break;
            case IS_NULL:
                cql.append(" IS NULL");
                break;
            case IS_NOT_NULL:
                cql.append(" IS NOT NULL");
                break;
        }
        
        return cql.toString();
    }
    
    private void appendValue(StringBuilder cql, Object value) {
        if (value instanceof String) {
            cql.append("'").append(value).append("'");
        } else if (value instanceof Number) {
            cql.append(value);
        } else {
            cql.append(value);
        }
    }
    
    @Override
    public String toString() {
        return "Predicate{" +
                "table='" + tableName + '\'' +
                ", column='" + columnName + '\'' +
                ", operator=" + operator +
                ", value=" + value +
                '}';
    }
}







