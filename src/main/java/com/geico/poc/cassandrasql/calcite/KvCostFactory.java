package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptCostFactory;

/**
 * Factory for creating KV-specific cost estimates.
 * 
 * This factory is used by Calcite's optimizer to create cost objects
 * for different query operations in KV mode.
 */
public class KvCostFactory implements RelOptCostFactory {
    
    // Singleton instance
    public static final KvCostFactory INSTANCE = new KvCostFactory();
    
    private KvCostFactory() {
        // Private constructor for singleton
    }
    
    @Override
    public RelOptCost makeCost(double rowCount, double cpu, double io) {
        return new KvCost(rowCount, cpu, io);
    }
    
    @Override
    public RelOptCost makeHugeCost() {
        return KvCost.INFINITY;
    }
    
    @Override
    public RelOptCost makeInfiniteCost() {
        return KvCost.INFINITY;
    }
    
    @Override
    public RelOptCost makeTinyCost() {
        return KvCost.TINY;
    }
    
    @Override
    public RelOptCost makeZeroCost() {
        return KvCost.ZERO;
    }
    
    /**
     * Estimate cost for a table scan operation.
     * 
     * Cost formula:
     * - rows: number of rows in table
     * - cpu: rows * 0.1 (for filtering and projection)
     * - io: rows * 1.0 (each row requires one I/O)
     */
    public RelOptCost makeTableScanCost(double rowCount) {
        return new KvCost(
            rowCount,           // rows processed
            rowCount * 0.1,     // CPU cost for filtering/projection
            rowCount * 1.0      // I/O cost (one read per row)
        );
    }
    
    /**
     * Estimate cost for an index scan operation.
     * 
     * Cost formula:
     * - rows: number of rows matching index predicate
     * - cpu: log(totalRows) + matchingRows * 0.1 (index lookup + filtering)
     * - io: log(totalRows) + matchingRows * 1.0 (index traversal + row reads)
     * 
     * Index scans are cheaper than table scans when selectivity is low.
     */
    public RelOptCost makeIndexScanCost(double totalRows, double matchingRows) {
        double indexTraversalCost = Math.log(Math.max(totalRows, 1.0)) / Math.log(2.0);
        
        return new KvCost(
            matchingRows,                           // rows processed
            indexTraversalCost + matchingRows * 0.1, // CPU cost
            indexTraversalCost + matchingRows * 1.0  // I/O cost
        );
    }
    
    /**
     * Estimate cost for a point lookup (single row by primary key).
     * 
     * Cost formula:
     * - rows: 1
     * - cpu: 1 (minimal CPU for key lookup)
     * - io: 1 (single I/O operation)
     */
    public RelOptCost makePointLookupCost() {
        return new KvCost(1, 1, 1);
    }
    
    /**
     * Estimate cost for a hash join operation.
     * 
     * Cost formula:
     * - rows: leftRows * rightRows * selectivity
     * - cpu: leftRows + rightRows (build hash table + probe)
     * - io: leftRows + rightRows (read both sides)
     * 
     * Hash joins are efficient when one side fits in memory.
     */
    public RelOptCost makeHashJoinCost(double leftRows, double rightRows, double selectivity) {
        double outputRows = leftRows * rightRows * selectivity;
        
        return new KvCost(
            outputRows,                 // output rows
            leftRows + rightRows,       // CPU to build and probe hash table
            leftRows + rightRows        // I/O to read both sides
        );
    }
    
    /**
     * Estimate cost for a nested loop join operation.
     * 
     * Cost formula:
     * - rows: leftRows * rightRows * selectivity
     * - cpu: leftRows * rightRows (nested iteration)
     * - io: leftRows * rightRows (repeated scans of right side)
     * 
     * Nested loop joins are expensive but work for any join condition.
     */
    public RelOptCost makeNestedLoopJoinCost(double leftRows, double rightRows, double selectivity) {
        double outputRows = leftRows * rightRows * selectivity;
        
        return new KvCost(
            outputRows,                 // output rows
            leftRows * rightRows,       // CPU for nested iteration
            leftRows * rightRows        // I/O for repeated scans
        );
    }
    
    /**
     * Estimate cost for an aggregation operation.
     * 
     * Cost formula:
     * - rows: groupCount (number of groups)
     * - cpu: inputRows * 0.5 (hashing and aggregating)
     * - io: inputRows (read all input rows)
     */
    public RelOptCost makeAggregateCost(double inputRows, double groupCount) {
        return new KvCost(
            groupCount,             // output rows (one per group)
            inputRows * 0.5,        // CPU for hashing and aggregating
            inputRows               // I/O to read input
        );
    }
    
    /**
     * Estimate cost for a sort operation.
     * 
     * Cost formula:
     * - rows: inputRows
     * - cpu: inputRows * log(inputRows) (comparison-based sort)
     * - io: inputRows * 2 (read + write if spills to disk)
     */
    public RelOptCost makeSortCost(double inputRows) {
        double sortCpu = inputRows * Math.log(Math.max(inputRows, 1.0)) / Math.log(2.0);
        
        return new KvCost(
            inputRows,          // output rows
            sortCpu,            // CPU for sorting
            inputRows * 2.0     // I/O (read + potential spill)
        );
    }
    
    /**
     * Estimate cost for a filter operation.
     * 
     * Cost formula:
     * - rows: inputRows * selectivity
     * - cpu: inputRows * 0.1 (evaluate predicates)
     * - io: 0 (no additional I/O, piggybacks on scan)
     */
    public RelOptCost makeFilterCost(double inputRows, double selectivity) {
        return new KvCost(
            inputRows * selectivity,    // output rows
            inputRows * 0.1,            // CPU to evaluate predicates
            0                           // no additional I/O
        );
    }
    
    /**
     * Estimate cost for a projection operation.
     * 
     * Cost formula:
     * - rows: inputRows
     * - cpu: inputRows * 0.05 (column selection)
     * - io: 0 (no additional I/O)
     */
    public RelOptCost makeProjectCost(double inputRows) {
        return new KvCost(
            inputRows,          // output rows
            inputRows * 0.05,   // CPU for projection
            0                   // no additional I/O
        );
    }
    
    /**
     * Estimate selectivity for a predicate.
     * Returns a value between 0.0 and 1.0.
     * 
     * Default estimates:
     * - Equality: 0.1 (10% of rows match)
     * - Range: 0.3 (30% of rows match)
     * - LIKE: 0.2 (20% of rows match)
     * - IS NULL: 0.05 (5% of rows are null)
     * - IN: 0.1 * valueCount (10% per value)
     */
    public double estimateSelectivity(String operator, int valueCount) {
        switch (operator.toUpperCase()) {
            case "=":
                return 0.1;
            case "!=":
            case "<>":
                return 0.9;
            case ">":
            case "<":
            case ">=":
            case "<=":
                return 0.3;
            case "LIKE":
            case "NOT_LIKE":
                return 0.2;
            case "IS_NULL":
                return 0.05;
            case "IS_NOT_NULL":
                return 0.95;
            case "IN":
                return Math.min(0.1 * valueCount, 0.9);
            case "NOT_IN":
                return Math.max(1.0 - 0.1 * valueCount, 0.1);
            case "BETWEEN":
                return 0.25;
            case "NOT_BETWEEN":
                return 0.75;
            default:
                return 0.5; // Unknown operator, assume 50% selectivity
        }
    }
}


