package com.geico.poc.cassandrasql;

import java.util.Collections;
import java.util.List;

/**
 * Represents a query with GROUP BY and aggregation
 */
public class AggregationQuery {
    
    private final List<String> groupByColumns;          // ["u.name"]
    private final List<AggregateFunction> aggregates;   // [COUNT(*), SUM(o.amount)]
    private final ParsedQuery baseQuery;                // The underlying SELECT/JOIN
    private final String originalSql;
    private final String havingClause;                  // HAVING condition (e.g., "COUNT(*) > 5")
    private final List<LiteralColumn> literals;         // Literal expressions in SELECT list
    
    public AggregationQuery(List<String> groupByColumns,
                           List<AggregateFunction> aggregates,
                           ParsedQuery baseQuery,
                           String originalSql) {
        this(groupByColumns, aggregates, baseQuery, originalSql, null, Collections.emptyList());
    }
    
    public AggregationQuery(List<String> groupByColumns,
                           List<AggregateFunction> aggregates,
                           ParsedQuery baseQuery,
                           String originalSql,
                           String havingClause) {
        this(groupByColumns, aggregates, baseQuery, originalSql, havingClause, Collections.emptyList());
    }
    
    public AggregationQuery(List<String> groupByColumns,
                           List<AggregateFunction> aggregates,
                           ParsedQuery baseQuery,
                           String originalSql,
                           String havingClause,
                           List<LiteralColumn> literals) {
        this.groupByColumns = groupByColumns;
        this.aggregates = aggregates;
        this.baseQuery = baseQuery;
        this.originalSql = originalSql;
        this.havingClause = havingClause;
        this.literals = literals != null ? literals : Collections.emptyList();
    }
    
    public List<String> getGroupByColumns() {
        return groupByColumns;
    }
    
    public List<AggregateFunction> getAggregates() {
        return aggregates;
    }
    
    public ParsedQuery getBaseQuery() {
        return baseQuery;
    }
    
    public String getOriginalSql() {
        return originalSql;
    }
    
    public String getHavingClause() {
        return havingClause;
    }
    
    public boolean hasHaving() {
        return havingClause != null && !havingClause.isEmpty();
    }
    
    public List<LiteralColumn> getLiterals() {
        return literals;
    }
    
    @Override
    public String toString() {
        return "AggregationQuery{" +
                "groupBy=" + groupByColumns +
                ", aggregates=" + aggregates.size() +
                ", literals=" + literals.size() +
                ", having=" + (havingClause != null ? "yes" : "no") +
                '}';
    }
}





