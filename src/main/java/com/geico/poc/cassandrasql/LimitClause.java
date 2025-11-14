package com.geico.poc.cassandrasql;

/**
 * Represents LIMIT and OFFSET clauses
 */
public class LimitClause {
    
    private final int limit;
    private final int offset;
    
    public LimitClause(int limit) {
        this(limit, 0);
    }
    
    public LimitClause(int limit, int offset) {
        this.limit = limit;
        this.offset = offset;
    }
    
    public int getLimit() {
        return limit;
    }
    
    public int getOffset() {
        return offset;
    }
    
    @Override
    public String toString() {
        if (offset > 0) {
            return "LIMIT " + limit + " OFFSET " + offset;
        }
        return "LIMIT " + limit;
    }
}







