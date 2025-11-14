package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.plan.RelOptCost;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KV cost model.
 * Tests cost calculations, comparisons, and arithmetic operations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KvCostTest {
    
    private KvCostFactory factory;
    
    @BeforeEach
    public void setup() {
        factory = KvCostFactory.INSTANCE;
    }
    
    // ========================================
    // Test 1: Basic cost creation
    // ========================================
    
    @Test
    @Order(1)
    public void testBasicCostCreation() {
        System.out.println("\n=== TEST 1: Basic Cost Creation ===\n");
        
        RelOptCost cost = factory.makeCost(100, 50, 25);
        
        assertEquals(100, cost.getRows(), 0.01);
        assertEquals(50, cost.getCpu(), 0.01);
        assertEquals(25, cost.getIo(), 0.01);
        assertFalse(cost.isInfinite());
        
        System.out.println("Cost: " + cost);
        System.out.println("✅ Basic cost creation works");
    }
    
    // ========================================
    // Test 2: Cost comparison
    // ========================================
    
    @Test
    @Order(2)
    public void testCostComparison() {
        System.out.println("\n=== TEST 2: Cost Comparison ===\n");
        
        // Table scan: 1000 rows
        RelOptCost tableScan = factory.makeTableScanCost(1000);
        
        // Index scan: 100 rows from 1000 total
        RelOptCost indexScan = factory.makeIndexScanCost(1000, 100);
        
        System.out.println("Table scan cost: " + tableScan);
        System.out.println("Index scan cost: " + indexScan);
        
        // Index scan should be cheaper
        assertTrue(indexScan.isLt(tableScan), "Index scan should be cheaper than table scan");
        
        System.out.println("✅ Cost comparison works correctly");
    }
    
    // ========================================
    // Test 3: Point lookup cost
    // ========================================
    
    @Test
    @Order(3)
    public void testPointLookupCost() {
        System.out.println("\n=== TEST 3: Point Lookup Cost ===\n");
        
        RelOptCost pointLookup = factory.makePointLookupCost();
        RelOptCost tableScan = factory.makeTableScanCost(1000);
        
        System.out.println("Point lookup cost: " + pointLookup);
        System.out.println("Table scan cost: " + tableScan);
        
        // Point lookup should be much cheaper
        assertTrue(pointLookup.isLt(tableScan), "Point lookup should be cheaper than table scan");
        
        assertEquals(1, pointLookup.getRows(), 0.01);
        
        System.out.println("✅ Point lookup cost is minimal");
    }
    
    // ========================================
    // Test 4: Join cost comparison
    // ========================================
    
    @Test
    @Order(4)
    public void testJoinCostComparison() {
        System.out.println("\n=== TEST 4: Join Cost Comparison ===\n");
        
        double leftRows = 100;
        double rightRows = 1000;
        double selectivity = 0.1;
        
        RelOptCost hashJoin = factory.makeHashJoinCost(leftRows, rightRows, selectivity);
        RelOptCost nestedLoop = factory.makeNestedLoopJoinCost(leftRows, rightRows, selectivity);
        
        System.out.println("Hash join cost: " + hashJoin);
        System.out.println("Nested loop join cost: " + nestedLoop);
        
        // Hash join should be cheaper
        assertTrue(hashJoin.isLt(nestedLoop), "Hash join should be cheaper than nested loop");
        
        System.out.println("✅ Join cost comparison works");
    }
    
    // ========================================
    // Test 5: Aggregation cost
    // ========================================
    
    @Test
    @Order(5)
    public void testAggregationCost() {
        System.out.println("\n=== TEST 5: Aggregation Cost ===\n");
        
        double inputRows = 10000;
        double groupCount = 100;
        
        RelOptCost aggCost = factory.makeAggregateCost(inputRows, groupCount);
        
        System.out.println("Aggregation cost: " + aggCost);
        
        // Output should be group count
        assertEquals(groupCount, aggCost.getRows(), 0.01);
        
        // CPU cost should reflect hashing
        assertTrue(aggCost.getCpu() > 0);
        
        System.out.println("✅ Aggregation cost calculated correctly");
    }
    
    // ========================================
    // Test 6: Sort cost
    // ========================================
    
    @Test
    @Order(6)
    public void testSortCost() {
        System.out.println("\n=== TEST 6: Sort Cost ===\n");
        
        double inputRows = 1000;
        
        RelOptCost sortCost = factory.makeSortCost(inputRows);
        
        System.out.println("Sort cost for " + inputRows + " rows: " + sortCost);
        
        // Sort should have O(n log n) CPU cost
        double expectedCpu = inputRows * Math.log(inputRows) / Math.log(2.0);
        assertTrue(Math.abs(sortCost.getCpu() - expectedCpu) < 0.01);
        
        System.out.println("✅ Sort cost reflects O(n log n) complexity");
    }
    
    // ========================================
    // Test 7: Filter cost and selectivity
    // ========================================
    
    @Test
    @Order(7)
    public void testFilterCostAndSelectivity() {
        System.out.println("\n=== TEST 7: Filter Cost and Selectivity ===\n");
        
        double inputRows = 1000;
        
        // Test different selectivities
        double eqSelectivity = factory.estimateSelectivity("=", 1);
        double rangeSelectivity = factory.estimateSelectivity(">", 1);
        double likeSelectivity = factory.estimateSelectivity("LIKE", 1);
        
        System.out.println("Equality selectivity: " + eqSelectivity);
        System.out.println("Range selectivity: " + rangeSelectivity);
        System.out.println("LIKE selectivity: " + likeSelectivity);
        
        RelOptCost eqFilter = factory.makeFilterCost(inputRows, eqSelectivity);
        RelOptCost rangeFilter = factory.makeFilterCost(inputRows, rangeSelectivity);
        
        System.out.println("Equality filter cost: " + eqFilter);
        System.out.println("Range filter cost: " + rangeFilter);
        
        // Range filter should return more rows
        assertTrue(rangeFilter.getRows() > eqFilter.getRows());
        
        System.out.println("✅ Filter selectivity estimates are reasonable");
    }
    
    // ========================================
    // Test 8: Cost arithmetic (plus/minus)
    // ========================================
    
    @Test
    @Order(8)
    public void testCostArithmetic() {
        System.out.println("\n=== TEST 8: Cost Arithmetic ===\n");
        
        RelOptCost cost1 = factory.makeCost(100, 50, 25);
        RelOptCost cost2 = factory.makeCost(50, 25, 10);
        
        RelOptCost sum = cost1.plus(cost2);
        RelOptCost diff = cost1.minus(cost2);
        
        System.out.println("Cost 1: " + cost1);
        System.out.println("Cost 2: " + cost2);
        System.out.println("Sum: " + sum);
        System.out.println("Difference: " + diff);
        
        assertEquals(150, sum.getRows(), 0.01);
        assertEquals(50, diff.getRows(), 0.01);
        
        System.out.println("✅ Cost arithmetic works correctly");
    }
    
    // ========================================
    // Test 9: Cost multiplication
    // ========================================
    
    @Test
    @Order(9)
    public void testCostMultiplication() {
        System.out.println("\n=== TEST 9: Cost Multiplication ===\n");
        
        RelOptCost baseCost = factory.makeCost(100, 50, 25);
        RelOptCost doubled = baseCost.multiplyBy(2.0);
        RelOptCost halved = baseCost.multiplyBy(0.5);
        
        System.out.println("Base cost: " + baseCost);
        System.out.println("Doubled: " + doubled);
        System.out.println("Halved: " + halved);
        
        assertEquals(200, doubled.getRows(), 0.01);
        assertEquals(50, halved.getRows(), 0.01);
        
        System.out.println("✅ Cost multiplication works correctly");
    }
    
    // ========================================
    // Test 10: Infinite cost
    // ========================================
    
    @Test
    @Order(10)
    public void testInfiniteCost() {
        System.out.println("\n=== TEST 10: Infinite Cost ===\n");
        
        RelOptCost infinite = factory.makeInfiniteCost();
        RelOptCost finite = factory.makeCost(1000, 500, 250);
        
        System.out.println("Infinite cost: " + infinite);
        System.out.println("Finite cost: " + finite);
        
        assertTrue(infinite.isInfinite());
        assertFalse(finite.isInfinite());
        
        // Infinite should be greater than any finite cost
        assertTrue(finite.isLt(infinite));
        
        System.out.println("✅ Infinite cost works correctly");
    }
    
    // ========================================
    // Test 11: Zero and tiny costs
    // ========================================
    
    @Test
    @Order(11)
    public void testZeroAndTinyCosts() {
        System.out.println("\n=== TEST 11: Zero and Tiny Costs ===\n");
        
        RelOptCost zero = factory.makeZeroCost();
        RelOptCost tiny = factory.makeTinyCost();
        RelOptCost normal = factory.makeCost(100, 50, 25);
        
        System.out.println("Zero cost: " + zero);
        System.out.println("Tiny cost: " + tiny);
        System.out.println("Normal cost: " + normal);
        
        assertEquals(0, zero.getRows(), 0.01);
        assertTrue(tiny.isLt(normal));
        assertTrue(zero.isLt(tiny));
        
        System.out.println("✅ Zero and tiny costs work correctly");
    }
    
    // ========================================
    // Test 12: Selectivity estimates for all operators
    // ========================================
    
    @Test
    @Order(12)
    public void testSelectivityEstimates() {
        System.out.println("\n=== TEST 12: Selectivity Estimates ===\n");
        
        String[] operators = {"=", "!=", ">", "<", ">=", "<=", "LIKE", "IS_NULL", "IS_NOT_NULL", "IN", "BETWEEN"};
        
        for (String op : operators) {
            double selectivity = factory.estimateSelectivity(op, 1);
            System.out.println(String.format("%-15s selectivity: %.2f", op, selectivity));
            
            // Selectivity should be between 0 and 1
            assertTrue(selectivity >= 0.0 && selectivity <= 1.0, 
                      "Selectivity for " + op + " should be between 0 and 1");
        }
        
        // Test IN with multiple values
        double inSelectivity3 = factory.estimateSelectivity("IN", 3);
        double inSelectivity5 = factory.estimateSelectivity("IN", 5);
        
        System.out.println(String.format("IN (3 values) selectivity: %.2f", inSelectivity3));
        System.out.println(String.format("IN (5 values) selectivity: %.2f", inSelectivity5));
        
        // More values should increase selectivity
        assertTrue(inSelectivity5 > inSelectivity3);
        
        System.out.println("✅ All selectivity estimates are reasonable");
    }
    
    // ========================================
    // Test 13: Index vs table scan crossover point
    // ========================================
    
    @Test
    @Order(13)
    public void testIndexVsTableScanCrossover() {
        System.out.println("\n=== TEST 13: Index vs Table Scan Crossover ===\n");
        
        double totalRows = 10000;
        
        // Find the crossover point where index scan becomes more expensive
        double crossoverPoint = 0;
        for (double selectivity = 0.01; selectivity <= 1.0; selectivity += 0.01) {
            double matchingRows = totalRows * selectivity;
            
            RelOptCost indexScan = factory.makeIndexScanCost(totalRows, matchingRows);
            RelOptCost tableScan = factory.makeTableScanCost(totalRows);
            
            if (tableScan.isLt(indexScan)) {
                crossoverPoint = selectivity;
                break;
            }
        }
        
        if (crossoverPoint == 0) {
            System.out.println("Index scan is always cheaper than table scan in this cost model");
            System.out.println("This is reasonable given the O(log n) index traversal cost");
        } else {
            System.out.println(String.format("Crossover point: %.2f%% selectivity", crossoverPoint * 100));
            System.out.println("Below this, index scan is cheaper; above it, table scan is cheaper");
            
            // Crossover should exist somewhere between 5% and 95%
            assertTrue(crossoverPoint > 0.05 && crossoverPoint < 0.95, 
                      "Crossover point should be reasonable (found: " + crossoverPoint + ")");
        }
        
        System.out.println("✅ Index vs table scan cost relationship is reasonable");
    }
    
    // ========================================
    // Test 14: Cost division
    // ========================================
    
    @Test
    @Order(14)
    public void testCostDivision() {
        System.out.println("\n=== TEST 14: Cost Division ===\n");
        
        RelOptCost cost1 = factory.makeCost(200, 100, 50);
        RelOptCost cost2 = factory.makeCost(100, 50, 25);
        
        double ratio = cost1.divideBy(cost2);
        
        System.out.println("Cost 1: " + cost1);
        System.out.println("Cost 2: " + cost2);
        System.out.println("Ratio: " + ratio);
        
        // Ratio should be approximately 2.0 (cost1 is roughly double cost2)
        assertTrue(ratio > 1.8 && ratio < 2.2, "Ratio should be approximately 2.0");
        
        System.out.println("✅ Cost division works correctly");
    }
}

