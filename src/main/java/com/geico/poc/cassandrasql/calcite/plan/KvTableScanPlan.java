package com.geico.poc.cassandrasql.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;
import com.geico.poc.cassandrasql.kv.TableMetadata;

import java.util.List;

/**
 * Execution plan for a full table scan.
 * Reads all rows from the table (subject to filters).
 */
public class KvTableScanPlan extends KvPlan {
    
    private final List<RexNode> filters;
    private final List<Integer> projections;
    private final Integer limit;
    
    public KvTableScanPlan(RelNode relNode, TableMetadata table, 
                          List<RexNode> filters, List<Integer> projections, Integer limit) {
        super(relNode, table);
        this.filters = filters;
        this.projections = projections;
        this.limit = limit;
    }
    
    @Override
    public QueryResponse execute(KvQueryExecutor executor) {
        // Build SQL query for table scan
        StringBuilder sql = new StringBuilder("SELECT ");
        
        // Add projections
        if (projections != null && !projections.isEmpty()) {
            List<TableMetadata.ColumnMetadata> columns = table.getColumns();
            for (int i = 0; i < projections.size(); i++) {
                if (i > 0) sql.append(", ");
                int colIndex = projections.get(i);
                sql.append(columns.get(colIndex).getName());
            }
        } else {
            sql.append("*");
        }
        
        sql.append(" FROM ").append(table.getTableName());
        
        // Add filters (WHERE clause)
        if (filters != null && !filters.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < filters.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append(convertRexToSql(filters.get(i)));
            }
        }
        
        // Add limit
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
        
        // Execute through existing query executor
        return executor.executeQuery(sql.toString());
    }
    
    @Override
    public String describe() {
        StringBuilder desc = new StringBuilder();
        desc.append("TableScan(table=").append(table.getTableName());
        
        if (filters != null && !filters.isEmpty()) {
            desc.append(", filters=").append(filters.size());
        }
        
        if (projections != null && !projections.isEmpty()) {
            desc.append(", projections=").append(projections.size());
        }
        
        if (limit != null) {
            desc.append(", limit=").append(limit);
        }
        
        desc.append(")");
        return desc.toString();
    }
    
    /**
     * Convert RexNode to SQL string (simplified)
     * TODO: Implement full RexNode to SQL conversion
     */
    private String convertRexToSql(RexNode rex) {
        // For now, use toString() - this will be enhanced in Phase 4
        return rex.toString();
    }
    
    public List<RexNode> getFilters() {
        return filters;
    }
    
    public List<Integer> getProjections() {
        return projections;
    }
    
    public Integer getLimit() {
        return limit;
    }
}


