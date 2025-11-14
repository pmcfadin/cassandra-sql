package com.geico.poc.cassandrasql;

import org.apache.calcite.sql.SqlNode;
import com.geico.poc.cassandrasql.window.WindowSpec;
import java.util.List;

public class ParsedQuery {
    public enum Type {
        SELECT, INSERT, CREATE_TABLE, CREATE_INDEX, DROP_TABLE, TRUNCATE, UPDATE, DELETE, JOIN, MULTI_WAY_JOIN, AGGREGATION, SUBQUERY, SELECT_WITH_SUBQUERY, WINDOW_FUNCTION, BEGIN, COMMIT, ROLLBACK, COPY, CREATE_SEQUENCE, DROP_SEQUENCE, VACUUM, ANALYZE, ALTER_TABLE, EXPLAIN, EXPLAIN_ANALYZE, CREATE_VIEW, CREATE_MATERIALIZED_VIEW, DROP_VIEW, REFRESH_MATERIALIZED_VIEW, CREATE_TYPE, DROP_TYPE, SET
    }

    private Type type;
    private String tableName;
    private String rawSql;
    private JoinQuery joinQuery;              // For 2-table JOIN queries
    private MultiWayJoinQuery multiWayJoin;   // For 3+ table JOIN queries
    private AggregationQuery aggregationQuery; // For GROUP BY queries
    private OrderByClause orderBy;            // ORDER BY clause
    private LimitClause limit;                // LIMIT/OFFSET clause
    private List<WindowSpec> windowFunctions; // Window functions
    private List<String> truncateTableNames;  // For TRUNCATE with multiple tables
    private List<String> dropTableNames;      // For DROP TABLE with multiple tables
    private String explainTargetSql;          // For EXPLAIN queries - the SQL being explained
    private boolean explainAnalyze;           // True if EXPLAIN ANALYZE (execute + show stats)
    private SqlNode alterTableNode;           // For ALTER TABLE - parsed Calcite AST node

    public ParsedQuery(Type type, String tableName, String rawSql) {
        this.type = type;
        this.tableName = tableName;
        this.rawSql = rawSql;
        this.joinQuery = null;
        this.multiWayJoin = null;
        this.aggregationQuery = null;
        this.orderBy = null;
        this.limit = null;
    }
    
    public ParsedQuery(Type type, JoinQuery joinQuery, String rawSql) {
        this.type = type;
        this.tableName = null;
        this.rawSql = rawSql;
        this.joinQuery = joinQuery;
        this.multiWayJoin = null;
    }
    
    public ParsedQuery(Type type, MultiWayJoinQuery multiWayJoin, String rawSql) {
        this.type = type;
        this.tableName = null;
        this.rawSql = rawSql;
        this.joinQuery = null;
        this.multiWayJoin = multiWayJoin;
    }

    public Type getType() {
        return type;
    }

    public String getTableName() {
        return tableName;
    }

    public String getRawSql() {
        return rawSql;
    }
    
    public JoinQuery getJoinQuery() {
        return joinQuery;
    }
    
    public MultiWayJoinQuery getMultiWayJoin() {
        return multiWayJoin;
    }
    
    public boolean isJoin() {
        return type == Type.JOIN || type == Type.MULTI_WAY_JOIN;
    }
    
    public boolean isMultiWayJoin() {
        return type == Type.MULTI_WAY_JOIN;
    }
    
    public AggregationQuery getAggregationQuery() {
        return aggregationQuery;
    }
    
    public void setAggregationQuery(AggregationQuery aggregationQuery) {
        this.aggregationQuery = aggregationQuery;
    }
    
    public OrderByClause getOrderBy() {
        return orderBy;
    }
    
    public void setOrderBy(OrderByClause orderBy) {
        this.orderBy = orderBy;
    }
    
    public LimitClause getLimit() {
        return limit;
    }
    
    public void setLimit(LimitClause limit) {
        this.limit = limit;
    }
    
    public boolean hasOrderBy() {
        return orderBy != null;
    }
    
    public boolean hasLimit() {
        return limit != null;
    }
    
    public boolean isAggregation() {
        return type == Type.AGGREGATION || aggregationQuery != null;
    }
    
    public List<WindowSpec> getWindowFunctions() {
        return windowFunctions;
    }
    
    public void setWindowFunctions(List<WindowSpec> windowFunctions) {
        this.windowFunctions = windowFunctions;
    }
    
    public boolean hasWindowFunctions() {
        return windowFunctions != null && !windowFunctions.isEmpty();
    }
    
    public List<String> getTruncateTableNames() {
        return truncateTableNames;
    }
    
    public void setTruncateTableNames(List<String> truncateTableNames) {
        this.truncateTableNames = truncateTableNames;
    }
    
    public List<String> getDropTableNames() {
        return dropTableNames;
    }
    
    public void setDropTableNames(List<String> dropTableNames) {
        this.dropTableNames = dropTableNames;
    }
    
    public String getExplainTargetSql() {
        return explainTargetSql;
    }
    
    public void setExplainTargetSql(String explainTargetSql) {
        this.explainTargetSql = explainTargetSql;
    }
    
    public boolean isExplainAnalyze() {
        return explainAnalyze;
    }
    
    public void setExplainAnalyze(boolean explainAnalyze) {
        this.explainAnalyze = explainAnalyze;
    }
    
    public SqlNode getAlterTableNode() {
        return alterTableNode;
    }
    
    public void setAlterTableNode(SqlNode alterTableNode) {
        this.alterTableNode = alterTableNode;
    }
}

