package com.geico.poc.cassandrasql.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution plan for projecting columns.
 * Selects and renames columns from the input stream.
 */
public class KvProjectPlan extends KvPlan {
    
    private final List<RexNode> projects;
    private final List<String> fieldNames;
    private final KvPlan input;
    
    public KvProjectPlan(RelNode relNode, List<RexNode> projects, List<String> fieldNames, KvPlan input) {
        super(relNode, input.getTable());
        this.projects = projects;
        this.fieldNames = fieldNames;
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
        
        // Apply projection
        List<Map<String, Object>> projectedRows = new ArrayList<>();
        for (Map<String, Object> row : inputResponse.getRows()) {
            Map<String, Object> projectedRow = new LinkedHashMap<>();
            
            for (int i = 0; i < projects.size(); i++) {
                String fieldName = fieldNames.get(i);
                Object value = evaluateProject(projects.get(i), row);
                projectedRow.put(fieldName, value);
            }
            
            projectedRows.add(projectedRow);
        }
        
        QueryResponse response = new QueryResponse();
        response.setColumns(fieldNames);
        response.setRows(projectedRows);
        response.setRowCount(projectedRows.size());
        
        return response;
    }
    
    @Override
    public String describe() {
        return "Project(fields=" + fieldNames + ", input=" + input.describe() + ")";
    }
    
    /**
     * Evaluate projection expression
     * TODO: Implement full RexNode evaluation
     */
    private Object evaluateProject(RexNode project, Map<String, Object> row) {
        // For now, return null
        // This will be enhanced in Phase 4 with proper RexNode evaluation
        return null;
    }
    
    public List<RexNode> getProjects() {
        return projects;
    }
    
    public List<String> getFieldNames() {
        return fieldNames;
    }
    
    public KvPlan getInput() {
        return input;
    }
}


