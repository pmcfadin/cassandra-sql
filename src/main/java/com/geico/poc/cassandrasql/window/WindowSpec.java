package com.geico.poc.cassandrasql.window;

import java.util.List;

/**
 * Specification for a window function.
 * 
 * Represents: FUNCTION_NAME() OVER (PARTITION BY ... ORDER BY ... ROWS BETWEEN ...)
 */
public class WindowSpec {
    
    private final String functionName;
    private final List<String> functionArgs;
    private final List<String> partitionByColumns;
    private final List<OrderByColumn> orderByColumns;
    private final WindowFrame frame;
    private final String alias;
    
    public WindowSpec(String functionName, List<String> functionArgs,
                     List<String> partitionByColumns, List<OrderByColumn> orderByColumns,
                     WindowFrame frame, String alias) {
        this.functionName = functionName;
        this.functionArgs = functionArgs;
        this.partitionByColumns = partitionByColumns;
        this.orderByColumns = orderByColumns;
        this.frame = frame;
        this.alias = alias;
    }
    
    public String getFunctionName() {
        return functionName;
    }
    
    public List<String> getFunctionArgs() {
        return functionArgs;
    }
    
    public List<String> getPartitionByColumns() {
        return partitionByColumns;
    }
    
    public List<OrderByColumn> getOrderByColumns() {
        return orderByColumns;
    }
    
    public WindowFrame getFrame() {
        return frame;
    }
    
    public String getAlias() {
        return alias;
    }
    
    @Override
    public String toString() {
        return String.format("%s() OVER (PARTITION BY %s ORDER BY %s) AS %s",
                functionName, partitionByColumns, orderByColumns, alias);
    }
    
    /**
     * Order BY column specification
     */
    public static class OrderByColumn {
        private final String column;
        private final boolean ascending;
        
        public OrderByColumn(String column, boolean ascending) {
            this.column = column;
            this.ascending = ascending;
        }
        
        public String getColumn() {
            return column;
        }
        
        public boolean isAscending() {
            return ascending;
        }
        
        @Override
        public String toString() {
            return column + (ascending ? " ASC" : " DESC");
        }
    }
}



