package com.geico.poc.cassandrasql;

import java.util.List;
import java.util.Map;

/**
 * Represents a multi-way join query (3+ tables)
 */
public class MultiWayJoinQuery {
    
    private final List<String> tables;                    // ["users", "orders", "products"]
    private final List<JoinCondition> joinConditions;     // [u.id = o.user_id, o.product_id = p.id]
    private final List<String> selectColumns;             // ["u.name", "o.order_id", "p.product_name"]
    private final Map<String, String> tableAliases;       // {"u" -> "users", "o" -> "orders"}
    private final String whereClause;                     // WHERE clause string (optional)
    
    public MultiWayJoinQuery(List<String> tables,
                            List<JoinCondition> joinConditions,
                            List<String> selectColumns,
                            Map<String, String> tableAliases) {
        this(tables, joinConditions, selectColumns, tableAliases, null);
    }
    
    public MultiWayJoinQuery(List<String> tables,
                            List<JoinCondition> joinConditions,
                            List<String> selectColumns,
                            Map<String, String> tableAliases,
                            String whereClause) {
        this.tables = tables;
        this.joinConditions = joinConditions;
        this.selectColumns = selectColumns;
        this.tableAliases = tableAliases;
        this.whereClause = whereClause;
    }
    
    public List<String> getTables() {
        return tables;
    }
    
    public List<JoinCondition> getJoinConditions() {
        return joinConditions;
    }
    
    public List<String> getSelectColumns() {
        return selectColumns;
    }
    
    public Map<String, String> getTableAliases() {
        return tableAliases;
    }
    
    public String getWhereClause() {
        return whereClause;
    }
    
    /**
     * Get the number of tables in the join
     */
    public int getTableCount() {
        return tables.size();
    }
    
    /**
     * Check if this is a multi-way join (3+ tables)
     */
    public boolean isMultiWay() {
        return tables.size() >= 3;
    }
    
    @Override
    public String toString() {
        return "MultiWayJoinQuery{" +
                "tables=" + tables +
                ", joinConditions=" + joinConditions.size() +
                ", selectColumns=" + selectColumns.size() +
                '}';
    }
}





