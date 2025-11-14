package com.geico.poc.cassandrasql.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Execution plan for filtering rows.
 * Applies predicates to filter the input stream.
 */
public class KvFilterPlan extends KvPlan {
    
    private final RexNode condition;
    private final KvPlan input;
    
    public KvFilterPlan(RelNode relNode, RexNode condition, KvPlan input) {
        super(relNode, input.getTable());
        this.condition = condition;
        this.input = input;
        this.inputs.add(input);
    }
    
    @Override
    public QueryResponse execute(KvQueryExecutor executor) {
        // Execute input plan
        QueryResponse inputResponse = input.execute(executor);
        
        if (inputResponse.getError() != null) {
            return inputResponse;
        }
        
        // Apply filter
        List<Map<String, Object>> filteredRows = new ArrayList<>();
        for (Map<String, Object> row : inputResponse.getRows()) {
            if (evaluateCondition(row)) {
                filteredRows.add(row);
            }
        }
        
        QueryResponse response = new QueryResponse();
        response.setColumns(inputResponse.getColumns());
        response.setRows(filteredRows);
        response.setRowCount(filteredRows.size());
        
        return response;
    }
    
    @Override
    public String describe() {
        return "Filter(condition=" + condition + ", input=" + input.describe() + ")";
    }
    
    /**
     * Evaluate condition against a row
     * TODO: Implement full RexNode evaluation
     */
    private boolean evaluateCondition(Map<String, Object> row) {
        // For now, return true (pass all rows)
        // This will be enhanced in Phase 4 with proper RexNode evaluation
        return true;
    }
    
    public RexNode getCondition() {
        return condition;
    }
    
    public KvPlan getInput() {
        return input;
    }
}


