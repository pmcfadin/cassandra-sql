package com.geico.poc.cassandrasql;

import java.util.List;

/**
 * Represents an ORDER BY clause
 */
public class OrderByClause {
    
    public enum Direction {
        ASC, DESC
    }
    
    public static class OrderByItem {
        private final String column;
        private final Direction direction;
        
        public OrderByItem(String column, Direction direction) {
            this.column = column;
            this.direction = direction;
        }
        
        public String getColumn() {
            return column;
        }
        
        public Direction getDirection() {
            return direction;
        }
        
        @Override
        public String toString() {
            return column + " " + direction;
        }
    }
    
    private final List<OrderByItem> items;
    
    public OrderByClause(List<OrderByItem> items) {
        this.items = items;
    }
    
    public List<OrderByItem> getItems() {
        return items;
    }
    
    @Override
    public String toString() {
        return "ORDER BY " + items;
    }
}







