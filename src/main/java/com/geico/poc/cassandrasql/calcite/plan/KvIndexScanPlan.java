package com.geico.poc.cassandrasql.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;
import com.geico.poc.cassandrasql.kv.TableMetadata;

import java.util.List;

/**
 * Execution plan for an index scan.
 * Uses a secondary index to efficiently locate matching rows.
 */
public class KvIndexScanPlan extends KvPlan {
    
    private final TableMetadata.IndexMetadata index;
    private final RexNode startKey;
    private final RexNode endKey;
    private final List<RexNode> residualFilters;
    private final List<Integer> projections;
    
    public KvIndexScanPlan(RelNode relNode, TableMetadata table, TableMetadata.IndexMetadata index,
                          RexNode startKey, RexNode endKey, 
                          List<RexNode> residualFilters, List<Integer> projections) {
        super(relNode, table);
        this.index = index;
        this.startKey = startKey;
        this.endKey = endKey;
        this.residualFilters = residualFilters;
        this.projections = projections;
    }
    
    @Override
    public QueryResponse execute(KvQueryExecutor executor) {
        // Build SQL query using index
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
        sql.append(" WHERE ");
        
        // Add index predicate
        String indexColumn = index.getColumns().get(0); // Simplified: use first column
        
        if (startKey != null && endKey != null) {
            // Range scan
            sql.append(indexColumn).append(" >= ").append(convertRexToSql(startKey));
            sql.append(" AND ").append(indexColumn).append(" <= ").append(convertRexToSql(endKey));
        } else if (startKey != null) {
            // Point lookup or >= scan
            sql.append(indexColumn).append(" = ").append(convertRexToSql(startKey));
        }
        
        // Add residual filters
        if (residualFilters != null && !residualFilters.isEmpty()) {
            for (RexNode filter : residualFilters) {
                sql.append(" AND ").append(convertRexToSql(filter));
            }
        }
        
        // Execute through existing query executor
        return executor.executeQuery(sql.toString());
    }
    
    @Override
    public String describe() {
        StringBuilder desc = new StringBuilder();
        desc.append("IndexScan(table=").append(table.getTableName());
        desc.append(", index=").append(index.getName());
        
        if (startKey != null) {
            desc.append(", startKey=").append(startKey);
        }
        
        if (endKey != null) {
            desc.append(", endKey=").append(endKey);
        }
        
        if (residualFilters != null && !residualFilters.isEmpty()) {
            desc.append(", residualFilters=").append(residualFilters.size());
        }
        
        desc.append(")");
        return desc.toString();
    }
    
    /**
     * Convert RexNode to SQL string (simplified)
     */
    private String convertRexToSql(RexNode rex) {
        return rex.toString();
    }
    
    public TableMetadata.IndexMetadata getIndex() {
        return index;
    }
    
    public RexNode getStartKey() {
        return startKey;
    }
    
    public RexNode getEndKey() {
        return endKey;
    }
    
    public List<RexNode> getResidualFilters() {
        return residualFilters;
    }
}

