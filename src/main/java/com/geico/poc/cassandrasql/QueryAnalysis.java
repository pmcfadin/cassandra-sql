package com.geico.poc.cassandrasql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of query analysis containing extracted information
 */
public class QueryAnalysis {
    
    private final List<String> tables;
    private final Map<String, String> tableAliases;  // alias -> actual table name
    private final List<Predicate> predicates;
    private final List<String> selectColumns;
    private final JoinQuery joinQuery;
    
    public QueryAnalysis() {
        this.tables = new ArrayList<>();
        this.tableAliases = new HashMap<>();
        this.predicates = new ArrayList<>();
        this.selectColumns = new ArrayList<>();
        this.joinQuery = null;
    }
    
    public QueryAnalysis(List<String> tables, Map<String, String> tableAliases,
                        List<Predicate> predicates, List<String> selectColumns,
                        JoinQuery joinQuery) {
        this.tables = tables;
        this.tableAliases = tableAliases;
        this.predicates = predicates;
        this.selectColumns = selectColumns;
        this.joinQuery = joinQuery;
    }
    
    public List<String> getTables() {
        return tables;
    }
    
    public Map<String, String> getTableAliases() {
        return tableAliases;
    }
    
    public List<Predicate> getPredicates() {
        return predicates;
    }
    
    public List<String> getSelectColumns() {
        return selectColumns;
    }
    
    public JoinQuery getJoinQuery() {
        return joinQuery;
    }
    
    /**
     * Get predicates for a specific table (by name or alias)
     */
    public List<Predicate> getPredicatesForTable(String tableOrAlias) {
        List<Predicate> result = new ArrayList<>();
        
        for (Predicate pred : predicates) {
            // Skip predicates without table qualification (will be applied after join)
            if (pred.getTableName() == null) {
                continue;
            }
            
            if (pred.getTableName().equalsIgnoreCase(tableOrAlias)) {
                result.add(pred);
            }
            // Also check if it's an alias
            String actualTable = tableAliases.get(tableOrAlias);
            if (actualTable != null && pred.getTableName().equalsIgnoreCase(actualTable)) {
                result.add(pred);
            }
        }
        
        return result;
    }
    
    /**
     * Resolve table alias to actual table name
     */
    public String resolveTableName(String aliasOrName) {
        String resolved = tableAliases.get(aliasOrName);
        return resolved != null ? resolved : aliasOrName;
    }
    
    @Override
    public String toString() {
        return "QueryAnalysis{" +
                "tables=" + tables +
                ", aliases=" + tableAliases +
                ", predicates=" + predicates.size() +
                ", selectColumns=" + selectColumns.size() +
                '}';
    }
}

