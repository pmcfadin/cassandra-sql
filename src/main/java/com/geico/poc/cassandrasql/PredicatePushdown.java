package com.geico.poc.cassandrasql;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Optimizes queries by pushing predicates down to Cassandra
 */
@Component
public class PredicatePushdown {
    
    /**
     * Apply predicate pushdown optimization to JOIN query
     */
    public OptimizedJoinQuery optimize(JoinQuery joinQuery, QueryAnalysis analysis) {
        System.out.println("=== PREDICATE PUSHDOWN ===");
        
        // Get predicates for each table
        List<Predicate> leftPredicates = analysis.getPredicatesForTable(
            getTableIdentifier(joinQuery.getLeftTable(), analysis)
        );
        
        List<Predicate> rightPredicates = analysis.getPredicatesForTable(
            getTableIdentifier(joinQuery.getRightTable(), analysis)
        );
        
        System.out.println("Left table (" + joinQuery.getLeftTable() + ") predicates: " + leftPredicates.size());
        System.out.println("Right table (" + joinQuery.getRightTable() + ") predicates: " + rightPredicates.size());
        
        // Build optimized queries with WHERE clauses
        String leftQuery = buildOptimizedQuery(
            joinQuery.getLeftTable(),
            leftPredicates,
            joinQuery.getSelectColumns(),
            true  // is left table
        );
        
        String rightQuery = buildOptimizedQuery(
            joinQuery.getRightTable(),
            rightPredicates,
            joinQuery.getSelectColumns(),
            false  // is right table
        );
        
        System.out.println("Optimized left query: " + leftQuery);
        System.out.println("Optimized right query: " + rightQuery);
        System.out.println("==========================\n");
        
        return new OptimizedJoinQuery(joinQuery, leftQuery, rightQuery, analysis);
    }
    
    /**
     * Get table identifier (checking both table name and aliases)
     */
    private String getTableIdentifier(String tableName, QueryAnalysis analysis) {
        // Check if this is an alias
        for (String alias : analysis.getTableAliases().keySet()) {
            if (analysis.getTableAliases().get(alias).equalsIgnoreCase(tableName)) {
                return alias;
            }
        }
        return tableName;
    }
    
    /**
     * Build optimized query with WHERE clause
     */
    private String buildOptimizedQuery(String tableName, List<Predicate> predicates,
                                      List<String> selectColumns, boolean isLeftTable) {
        StringBuilder query = new StringBuilder();
        
        // SELECT clause - always use * to avoid column projection issues
        // Column projection will be done after the join
        query.append("SELECT * FROM ").append(tableName);
        
        // WHERE clause (predicate pushdown)
        if (predicates != null && !predicates.isEmpty()) {
            query.append(" WHERE ");
            
            boolean first = true;
            for (Predicate pred : predicates) {
                if (!pred.canPushDown()) {
                    System.out.println("  Warning: Cannot push down predicate: " + pred);
                    continue;
                }
                
                if (!first) {
                    query.append(" AND ");
                }
                
                query.append(pred.toCql());
                first = false;
            }
        }
        
        // Add ALLOW FILTERING if needed (Cassandra requirement for non-key filters)
        if (predicates != null && !predicates.isEmpty()) {
            query.append(" ALLOW FILTERING");
        }
        
        return query.toString();
    }
    
    /**
     * Optimized JOIN query with pushed-down predicates
     */
    public static class OptimizedJoinQuery {
        private final JoinQuery originalJoin;
        private final String leftQuery;
        private final String rightQuery;
        private final QueryAnalysis analysis;
        
        public OptimizedJoinQuery(JoinQuery originalJoin, String leftQuery, 
                                 String rightQuery, QueryAnalysis analysis) {
            this.originalJoin = originalJoin;
            this.leftQuery = leftQuery;
            this.rightQuery = rightQuery;
            this.analysis = analysis;
        }
        
        public JoinQuery getOriginalJoin() {
            return originalJoin;
        }
        
        public String getLeftQuery() {
            return leftQuery;
        }
        
        public String getRightQuery() {
            return rightQuery;
        }
        
        public QueryAnalysis getAnalysis() {
            return analysis;
        }
    }
}

