package com.geico.poc.cassandrasql;

import java.util.List;

/**
 * Represents a parsed JOIN query
 */
public class JoinQuery {
    
    public enum JoinType {
        INNER,
        LEFT,
        RIGHT,
        FULL,
        CROSS
    }
    
    private final String leftTable;
    private final String rightTable;
    private final String leftJoinKey;
    private final String rightJoinKey;
    private final JoinType joinType;
    private final List<String> selectColumns;
    private final String whereClause;
    
    public JoinQuery(String leftTable, String rightTable, 
                     String leftJoinKey, String rightJoinKey,
                     JoinType joinType, List<String> selectColumns,
                     String whereClause) {
        this.leftTable = leftTable;
        this.rightTable = rightTable;
        this.leftJoinKey = leftJoinKey;
        this.rightJoinKey = rightJoinKey;
        this.joinType = joinType;
        this.selectColumns = selectColumns;
        this.whereClause = whereClause;
    }
    
    public String getLeftTable() {
        return leftTable;
    }
    
    public String getRightTable() {
        return rightTable;
    }
    
    public String getLeftJoinKey() {
        return leftJoinKey;
    }
    
    public String getRightJoinKey() {
        return rightJoinKey;
    }
    
    public JoinType getJoinType() {
        return joinType;
    }
    
    public List<String> getSelectColumns() {
        return selectColumns;
    }
    
    public String getWhereClause() {
        return whereClause;
    }
    
    @Override
    public String toString() {
        return "JoinQuery{" +
                "leftTable='" + leftTable + '\'' +
                ", rightTable='" + rightTable + '\'' +
                ", leftKey='" + leftJoinKey + '\'' +
                ", rightKey='" + rightJoinKey + '\'' +
                ", type=" + joinType +
                '}';
    }
}





