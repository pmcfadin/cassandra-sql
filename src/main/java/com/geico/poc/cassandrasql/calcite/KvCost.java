package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptUtil;

/**
 * Cost model for KV storage operations.
 * 
 * Cost components:
 * - rows: Number of rows processed
 * - cpu: CPU cost (operations, comparisons, etc.)
 * - io: I/O cost (disk reads, network transfers)
 * 
 * Cost formula:
 * totalCost = rows * 1.0 + cpu * 0.5 + io * 2.0
 * 
 * I/O is weighted highest because KV operations are I/O bound.
 */
public class KvCost implements RelOptCost {
    
    private final double rows;
    private final double cpu;
    private final double io;
    
    // Cost weights
    private static final double ROW_WEIGHT = 1.0;
    private static final double CPU_WEIGHT = 0.5;
    private static final double IO_WEIGHT = 2.0;
    
    public KvCost(double rows, double cpu, double io) {
        this.rows = rows;
        this.cpu = cpu;
        this.io = io;
    }
    
    @Override
    public double getRows() {
        return rows;
    }
    
    @Override
    public double getCpu() {
        return cpu;
    }
    
    @Override
    public double getIo() {
        return io;
    }
    
    /**
     * Calculate total weighted cost
     */
    private double getTotalCost() {
        return rows * ROW_WEIGHT + cpu * CPU_WEIGHT + io * IO_WEIGHT;
    }
    
    @Override
    public boolean isInfinite() {
        return Double.isInfinite(rows) || Double.isInfinite(cpu) || Double.isInfinite(io);
    }
    
    @Override
    public boolean isLe(RelOptCost other) {
        KvCost that = (KvCost) other;
        return this.getTotalCost() <= that.getTotalCost();
    }
    
    @Override
    public boolean isLt(RelOptCost other) {
        KvCost that = (KvCost) other;
        return this.getTotalCost() < that.getTotalCost();
    }
    
    @Override
    public boolean equals(RelOptCost other) {
        return isEqWithEpsilon(other);
    }
    
    @Override
    public boolean isEqWithEpsilon(RelOptCost other) {
        if (!(other instanceof KvCost)) {
            return false;
        }
        KvCost that = (KvCost) other;
        return Math.abs(this.getTotalCost() - that.getTotalCost()) < RelOptUtil.EPSILON;
    }
    
    @Override
    public RelOptCost minus(RelOptCost other) {
        if (this == INFINITY || other == INFINITY) {
            return INFINITY;
        }
        KvCost that = (KvCost) other;
        return new KvCost(
            this.rows - that.rows,
            this.cpu - that.cpu,
            this.io - that.io
        );
    }
    
    @Override
    public RelOptCost plus(RelOptCost other) {
        if (this == INFINITY || other == INFINITY) {
            return INFINITY;
        }
        KvCost that = (KvCost) other;
        return new KvCost(
            this.rows + that.rows,
            this.cpu + that.cpu,
            this.io + that.io
        );
    }
    
    @Override
    public RelOptCost multiplyBy(double factor) {
        if (this == INFINITY) {
            return INFINITY;
        }
        return new KvCost(
            rows * factor,
            cpu * factor,
            io * factor
        );
    }
    
    @Override
    public double divideBy(RelOptCost cost) {
        KvCost that = (KvCost) cost;
        double thisCost = this.getTotalCost();
        double thatCost = that.getTotalCost();
        
        if (thatCost == 0.0) {
            if (thisCost == 0.0) {
                return 1.0;
            } else {
                return Double.POSITIVE_INFINITY;
            }
        }
        return thisCost / thatCost;
    }
    
    @Override
    public String toString() {
        return String.format("{rows=%.1f, cpu=%.1f, io=%.1f, total=%.1f}", 
                           rows, cpu, io, getTotalCost());
    }
    
    /**
     * Infinite cost constant
     */
    public static final KvCost INFINITY = new KvCost(
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY
    ) {
        @Override
        public String toString() {
            return "{infinite}";
        }
    };
    
    /**
     * Zero cost constant
     */
    public static final KvCost ZERO = new KvCost(0, 0, 0);
    
    /**
     * Tiny cost constant (for operations that are essentially free)
     */
    public static final KvCost TINY = new KvCost(1, 1, 0);
}


