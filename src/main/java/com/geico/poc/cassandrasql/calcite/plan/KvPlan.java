package com.geico.poc.cassandrasql.calcite.plan;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;
import com.geico.poc.cassandrasql.kv.TableMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for KV execution plans.
 * Represents an executable query plan that can be executed against the KV store.
 */
public abstract class KvPlan {
    
    protected final RelNode relNode;
    protected final TableMetadata table;
    protected final List<KvPlan> inputs;
    
    protected KvPlan(RelNode relNode, TableMetadata table) {
        this.relNode = relNode;
        this.table = table;
        this.inputs = new ArrayList<>();
    }
    
    protected KvPlan(RelNode relNode, TableMetadata table, List<KvPlan> inputs) {
        this.relNode = relNode;
        this.table = table;
        this.inputs = inputs != null ? new ArrayList<>(inputs) : new ArrayList<>();
    }
    
    /**
     * Execute this plan and return results
     */
    public abstract QueryResponse execute(KvQueryExecutor executor);
    
    /**
     * Get the output row type (schema) of this plan
     */
    public RelDataType getRowType() {
        return relNode.getRowType();
    }
    
    /**
     * Get the underlying RelNode
     */
    public RelNode getRelNode() {
        return relNode;
    }
    
    /**
     * Get the table metadata (may be null for non-table operations)
     */
    public TableMetadata getTable() {
        return table;
    }
    
    /**
     * Get input plans
     */
    public List<KvPlan> getInputs() {
        return inputs;
    }
    
    /**
     * Add an input plan
     */
    public void addInput(KvPlan input) {
        inputs.add(input);
    }
    
    /**
     * Get a human-readable description of this plan
     */
    public abstract String describe();
    
    /**
     * Get the estimated cost of this plan
     */
    public double getEstimatedCost() {
        if (relNode.getCluster() != null && relNode.getCluster().getMetadataQuery() != null) {
            return relNode.getCluster().getMetadataQuery().getCumulativeCost(relNode).getRows();
        }
        return 0.0;
    }
    
    @Override
    public String toString() {
        return describe();
    }
}


