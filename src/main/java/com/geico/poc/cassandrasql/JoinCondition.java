package com.geico.poc.cassandrasql;

/**
 * Represents a single join condition between two tables
 */
public class JoinCondition {
    
    private final String leftTable;      // Table name or alias (e.g., "u" or "users")
    private final String leftColumn;     // Column name (e.g., "id")
    private final String rightTable;     // Table name or alias (e.g., "o" or "orders")
    private final String rightColumn;    // Column name (e.g., "user_id")
    private final JoinType joinType;     // INNER, LEFT, RIGHT
    
    public enum JoinType {
        INNER,
        LEFT,
        RIGHT,
        FULL
    }
    
    public JoinCondition(String leftTable, String leftColumn, 
                        String rightTable, String rightColumn, 
                        JoinType joinType) {
        this.leftTable = leftTable;
        this.leftColumn = leftColumn;
        this.rightTable = rightTable;
        this.rightColumn = rightColumn;
        this.joinType = joinType;
    }
    
    public String getLeftTable() {
        return leftTable;
    }
    
    public String getLeftColumn() {
        return leftColumn;
    }
    
    public String getRightTable() {
        return rightTable;
    }
    
    public String getRightColumn() {
        return rightColumn;
    }
    
    public JoinType getJoinType() {
        return joinType;
    }
    
    /**
     * Get the full qualified left key (table.column)
     */
    public String getLeftKey() {
        return leftTable + "." + leftColumn;
    }
    
    /**
     * Get the full qualified right key (table.column)
     */
    public String getRightKey() {
        return rightTable + "." + rightColumn;
    }
    
    @Override
    public String toString() {
        return String.format("%s.%s = %s.%s (%s)", 
            leftTable, leftColumn, rightTable, rightColumn, joinType);
    }
}







