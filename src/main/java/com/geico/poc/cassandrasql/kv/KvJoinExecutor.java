package com.geico.poc.cassandrasql.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.JoinQuery;
import com.geico.poc.cassandrasql.MultiWayJoinQuery;
import com.geico.poc.cassandrasql.JoinCondition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes JOIN queries in KV mode using hash join algorithm
 */
@Service
public class KvJoinExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(KvJoinExecutor.class);
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    /**
     * Execute a binary JOIN (2 tables)
     */
    public QueryResponse executeBinaryJoin(JoinQuery joinQuery) {
        try {
            log.debug("🔗 Executing " + joinQuery.getJoinType() + " JOIN in KV mode");
            log.debug("   Left table: " + joinQuery.getLeftTable());
            log.debug("   Right table: " + joinQuery.getRightTable());
            log.debug("   Join condition: " + joinQuery.getLeftJoinKey() + " = " + joinQuery.getRightJoinKey());
            
            long readTs = timestampOracle.getCurrentTimestamp();
            
            List<Map<String, Object>> results;
            
            // Execute join based on type
            switch (joinQuery.getJoinType()) {
                case INNER:
                    results = executeInnerJoin(joinQuery, readTs);
                    break;
                case LEFT:
                    results = executeLeftJoin(joinQuery, readTs);
                    break;
                case RIGHT:
                    results = executeRightJoin(joinQuery, readTs);
                    break;
                case FULL:
                    results = executeFullOuterJoin(joinQuery, readTs);
                    break;
                case CROSS:
                    results = executeCrossJoin(joinQuery, readTs);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported join type: " + joinQuery.getJoinType());
            }
            
            log.debug("   Join produced: " + results.size() + " rows");
            
            // Phase 3: Apply post-join filters (WHERE clause)
            String whereClause = joinQuery.getWhereClause();
            if (whereClause != null && !whereClause.isEmpty()) {
                log.debug("   Applying WHERE clause: " + whereClause);
                // Parse WHERE clause into conditions
                List<String> conditions = parseWhereClause(whereClause);
                log.debug("   Parsed conditions: " + conditions);
                results = applyPostJoinFilters(results, conditions);
                log.debug("   After WHERE filter: " + results.size() + " rows");
            }
            
            // Phase 4: Project columns
            log.debug("   Before projection: " + results.size() + " rows");
            if (!results.isEmpty()) {
                log.debug("   Sample row keys: " + results.get(0).keySet());
            }
            log.debug("   Select columns: " + joinQuery.getSelectColumns());
            
            List<Map<String, Object>> projected = projectColumns(results, joinQuery.getSelectColumns());
            
            log.debug("   After projection: " + projected.size() + " rows");
            if (!projected.isEmpty()) {
                log.debug("   Sample projected row: " + projected.get(0));
            }
            
            // Extract column names
            List<String> columns = projected.isEmpty() ?
                Collections.emptyList() : 
                new ArrayList<>(projected.get(0).keySet());
            
            return new QueryResponse(projected, columns);
            
        } catch (Exception e) {
            log.error("❌ JOIN failed: " + e.getMessage());
            e.printStackTrace();
            return QueryResponse.error("JOIN failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute INNER JOIN
     */
    private List<Map<String, Object>> executeInnerJoin(JoinQuery joinQuery, long readTs) {
        // Phase 1: Build hash table from left table
        Map<Object, List<Map<String, Object>>> hashTable = buildHashTable(
            joinQuery.getLeftTable(),
            joinQuery.getLeftJoinKey(),
            readTs
        );
        
        // Phase 2: Probe with right table (INNER JOIN - only matching rows)
        return probeHashTable(
            hashTable,
            joinQuery.getRightTable(),
            joinQuery.getRightJoinKey(),
            joinQuery.getLeftTable(),
            joinQuery.getRightTable(),
            readTs,
            false, // includeLeftUnmatched
            false  // includeRightUnmatched
        );
    }
    
    /**
     * Execute LEFT JOIN (LEFT OUTER JOIN)
     */
    private List<Map<String, Object>> executeLeftJoin(JoinQuery joinQuery, long readTs) {
        // Phase 1: Build hash table from left table
        Map<Object, List<Map<String, Object>>> hashTable = buildHashTable(
            joinQuery.getLeftTable(),
            joinQuery.getLeftJoinKey(),
            readTs
        );
        
        // Phase 2: Probe with right table (LEFT JOIN - include unmatched left rows)
        return probeHashTable(
            hashTable,
            joinQuery.getRightTable(),
            joinQuery.getRightJoinKey(),
            joinQuery.getLeftTable(),
            joinQuery.getRightTable(),
            readTs,
            true,  // includeLeftUnmatched
            false  // includeRightUnmatched
        );
    }
    
    /**
     * Execute RIGHT JOIN (RIGHT OUTER JOIN)
     */
    private List<Map<String, Object>> executeRightJoin(JoinQuery joinQuery, long readTs) {
        // RIGHT JOIN is equivalent to LEFT JOIN with tables swapped
        // Build hash table from RIGHT table
        Map<Object, List<Map<String, Object>>> hashTable = buildHashTable(
            joinQuery.getRightTable(),
            joinQuery.getRightJoinKey(),
            readTs
        );
        
        // Probe with LEFT table
        return probeHashTable(
            hashTable,
            joinQuery.getLeftTable(),
            joinQuery.getLeftJoinKey(),
            joinQuery.getRightTable(), // Note: swapped
            joinQuery.getLeftTable(),  // Note: swapped
            readTs,
            true,  // includeLeftUnmatched (which is actually right table)
            false  // includeRightUnmatched
        );
    }
    
    /**
     * Execute FULL OUTER JOIN
     */
    private List<Map<String, Object>> executeFullOuterJoin(JoinQuery joinQuery, long readTs) {
        // FULL OUTER JOIN includes all rows from both tables
        // Build hash table from left table
        Map<Object, List<Map<String, Object>>> hashTable = buildHashTable(
            joinQuery.getLeftTable(),
            joinQuery.getLeftJoinKey(),
            readTs
        );
        
        // Probe with right table (include both unmatched left and right rows)
        return probeHashTable(
            hashTable,
            joinQuery.getRightTable(),
            joinQuery.getRightJoinKey(),
            joinQuery.getLeftTable(),
            joinQuery.getRightTable(),
            readTs,
            true, // includeLeftUnmatched
            true  // includeRightUnmatched
        );
    }
    
    /**
     * Execute CROSS JOIN (Cartesian Product)
     */
    private List<Map<String, Object>> executeCrossJoin(JoinQuery joinQuery, long readTs) {
        // CROSS JOIN produces cartesian product: every left row × every right row
        List<Map<String, Object>> results = new ArrayList<>();
        
        // Scan both tables
        List<Map<String, Object>> leftRows = scanTable(joinQuery.getLeftTable(), readTs);
        List<Map<String, Object>> rightRows = scanTable(joinQuery.getRightTable(), readTs);
        
        log.debug("   CROSS JOIN: " + leftRows.size() + " × " + rightRows.size() + " = " + 
            (leftRows.size() * rightRows.size()) + " rows");
        log.debug("   Left rows: " + leftRows);
        log.debug("   Right rows: " + rightRows);
        
        // Extract table aliases from SELECT columns
        // E.g., "a.val AS a_val" -> alias is "a"
        String leftAlias = extractTableAliasFromSelectColumns(joinQuery.getSelectColumns(), true);
        String rightAlias = extractTableAliasFromSelectColumns(joinQuery.getSelectColumns(), false);
        
        // Fallback to table names if no aliases found
        if (leftAlias == null) leftAlias = joinQuery.getLeftTable().toLowerCase();
        if (rightAlias == null) rightAlias = joinQuery.getRightTable().toLowerCase();
        
        log.debug("   Using aliases: left=" + leftAlias + ", right=" + rightAlias);
        
        // Nested loop: combine every left row with every right row
        for (Map<String, Object> leftRow : leftRows) {
            for (Map<String, Object> rightRow : rightRows) {
                Map<String, Object> joinedRow = new HashMap<>();
                
                // Add left table columns with alias prefix (e.g., "a.val")
                for (Map.Entry<String, Object> entry : leftRow.entrySet()) {
                    String columnName = entry.getKey();
                    // Store both unprefixed and prefixed versions
                    joinedRow.put(columnName, entry.getValue());
                    joinedRow.put(leftAlias + "." + columnName, entry.getValue());
                }
                
                // Add right table columns with alias prefix (e.g., "b.val")
                for (Map.Entry<String, Object> entry : rightRow.entrySet()) {
                    String columnName = entry.getKey();
                    // For unprefixed, right table overwrites left (last wins)
                    joinedRow.put(columnName, entry.getValue());
                    // But prefixed version is unique
                    joinedRow.put(rightAlias + "." + columnName, entry.getValue());
                }
                
                results.add(joinedRow);
            }
        }
        
        return results;
    }
    
    /**
     * Execute a multi-way JOIN (3+ tables)
     */
    public QueryResponse executeMultiWayJoin(MultiWayJoinQuery joinQuery) {
        try {
            log.debug("🔗🔗 Executing multi-way JOIN in KV mode");
            log.debug("   Tables: " + joinQuery.getTables());
            
            long readTs = timestampOracle.getCurrentTimestamp();
            
            // Start with the first table
            String firstTable = joinQuery.getTables().get(0);
            List<Map<String, Object>> result = scanTable(firstTable, readTs);
            log.debug("   Started with " + firstTable + ": " + result.size() + " rows");
            
            // Join each subsequent table
            for (int i = 1; i < joinQuery.getTables().size(); i++) {
                String rightTable = joinQuery.getTables().get(i);
                JoinCondition condition = joinQuery.getJoinConditions().get(i - 1);
                
                log.debug("   Joining with " + rightTable + " on " + 
                    condition.getLeftColumn() + " = " + condition.getRightColumn());
                
                result = joinWithTable(result, rightTable, condition, readTs);
                log.debug("   After join: " + result.size() + " rows");
            }
            
            // Apply WHERE clause
            String whereClause = joinQuery.getWhereClause();
            if (whereClause != null && !whereClause.isEmpty()) {
                log.debug("   Applying WHERE clause: " + whereClause);
                List<String> conditions = parseWhereClause(whereClause);
                log.debug("   Parsed conditions: " + conditions);
                result = applyPostJoinFilters(result, conditions);
                log.debug("   After WHERE filter: " + result.size() + " rows");
            }
            
            // Project columns
            List<Map<String, Object>> projected = projectColumns(result, joinQuery.getSelectColumns());
            
            // Extract column names
            List<String> columns = projected.isEmpty() ? 
                Collections.emptyList() : 
                new ArrayList<>(projected.get(0).keySet());
            
            return new QueryResponse(projected, columns);
            
        } catch (Exception e) {
            log.error("❌ Multi-way JOIN failed: " + e.getMessage());
            e.printStackTrace();
            return QueryResponse.error("Multi-way JOIN failed: " + e.getMessage());
        }
    }
    
    /**
     * Build hash table from a table
     */
    private Map<Object, List<Map<String, Object>>> buildHashTable(
            String tableName, 
            String joinColumn, 
            long readTs) {
        
        Map<Object, List<Map<String, Object>>> hashTable = new HashMap<>();
        List<Map<String, Object>> rows = scanTable(tableName, readTs);
        
        // Extract just the column name (strip table prefix if present)
        String columnName = extractColumnName(joinColumn);
        
        for (Map<String, Object> row : rows) {
            Object key = row.get(columnName.toLowerCase());
            // Include all rows, even those with NULL join keys
            // NULL keys are stored separately for LEFT/FULL OUTER joins
            hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        
        return hashTable;
    }
    
    /**
     * Probe hash table with right table
     */
    private List<Map<String, Object>> probeHashTable(
            Map<Object, List<Map<String, Object>>> hashTable,
            String rightTable,
            String rightJoinColumn,
            String leftTableName,
            String rightTableName,
            long readTs,
            boolean includeLeftUnmatched,
            boolean includeRightUnmatched) {
        
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> rightRows = scanTable(rightTable, readTs);
        
        // Extract just the column name (strip table prefix if present)
        String columnName = extractColumnName(rightJoinColumn);
        
        // Track which left join keys were matched (for LEFT/FULL OUTER JOIN)
        Set<Object> matchedLeftKeys = new HashSet<>();
        
        // Probe phase: match right rows with left rows
        for (Map<String, Object> rightRow : rightRows) {
            Object key = rightRow.get(columnName.toLowerCase());
            boolean rightRowMatched = false;
            
            if (key != null && hashTable.containsKey(key)) {
                // Match found - add all matching left rows
                for (Map<String, Object> leftRow : hashTable.get(key)) {
                    Map<String, Object> joinedRow = new HashMap<>();
                    joinedRow.putAll(leftRow);
                    joinedRow.putAll(rightRow);
                    results.add(joinedRow);
                    matchedLeftKeys.add(key);  // Track that this key was matched
                    rightRowMatched = true;
                }
            }
            
            // RIGHT/FULL OUTER: Include unmatched right rows with NULL left values
            if (!rightRowMatched && includeRightUnmatched) {
                Map<String, Object> joinedRow = new HashMap<>();
                // Add NULL values for all left table columns
                TableMetadata leftTableMeta = schemaManager.getTable(leftTableName);
                if (leftTableMeta != null) {
                    for (String col : leftTableMeta.getAllColumns()) {
                        joinedRow.put(col.toLowerCase(), null);
                    }
                }
                // Add right row values
                joinedRow.putAll(rightRow);
                results.add(joinedRow);
            }
        }
        
        // LEFT/FULL OUTER: Include unmatched left rows with NULL right values
        if (includeLeftUnmatched) {
            log.debug("   LEFT/FULL OUTER: Checking for unmatched left rows");
            log.debug("   Hash table keys: " + hashTable.keySet());
            log.debug("   Matched keys: " + matchedLeftKeys);
            
            for (Map.Entry<Object, List<Map<String, Object>>> entry : hashTable.entrySet()) {
                Object joinKey = entry.getKey();
                // If this join key was never matched, include all rows with this key
                if (!matchedLeftKeys.contains(joinKey)) {
                    log.debug("   Adding unmatched left rows for key: " + joinKey + " (" + entry.getValue().size() + " rows)");
                    for (Map<String, Object> leftRow : entry.getValue()) {
                        Map<String, Object> joinedRow = new HashMap<>();
                        joinedRow.putAll(leftRow);
                        // Add NULL values for all right table columns
                        TableMetadata rightTableMeta = schemaManager.getTable(rightTableName);
                        if (rightTableMeta != null) {
                            for (String col : rightTableMeta.getAllColumns()) {
                                joinedRow.put(col.toLowerCase(), null);
                            }
                        }
                        results.add(joinedRow);
                    }
                }
            }
        }
        
        return results;
    }
    
    /**
     * Join current result set with another table
     */
    private List<Map<String, Object>> joinWithTable(
            List<Map<String, Object>> currentResult,
            String rightTable,
            JoinCondition condition,
            long readTs) {
        
        JoinCondition.JoinType joinType = condition.getJoinType();
        log.debug("   Join type: " + joinType);
        
        // Dispatch to appropriate join implementation
        switch (joinType) {
            case LEFT:
                return joinWithTableLeft(currentResult, rightTable, condition, readTs);
            case RIGHT:
                return joinWithTableRight(currentResult, rightTable, condition, readTs);
            case FULL:
                return joinWithTableFull(currentResult, rightTable, condition, readTs);
            case INNER:
            default:
                return joinWithTableInner(currentResult, rightTable, condition, readTs);
        }
    }
    
    /**
     * INNER JOIN with table (for multi-way joins)
     */
    private List<Map<String, Object>> joinWithTableInner(
            List<Map<String, Object>> currentResult,
            String rightTable,
            JoinCondition condition,
            long readTs) {
        
        List<Map<String, Object>> newResult = new ArrayList<>();
        List<Map<String, Object>> rightRows = scanTable(rightTable, readTs);
        
        String leftCol = extractColumnName(condition.getLeftColumn()).toLowerCase();
        String rightCol = extractColumnName(condition.getRightColumn()).toLowerCase();
        
        for (Map<String, Object> leftRow : currentResult) {
            Object leftValue = leftRow.get(leftCol);
            if (leftValue == null) continue;
            
            for (Map<String, Object> rightRow : rightRows) {
                Object rightValue = rightRow.get(rightCol);
                if (rightValue != null && leftValue.equals(rightValue)) {
                    Map<String, Object> joinedRow = new HashMap<>();
                    joinedRow.putAll(leftRow);
                    joinedRow.putAll(rightRow);
                    newResult.add(joinedRow);
                }
            }
        }
        
        return newResult;
    }
    
    /**
     * LEFT OUTER JOIN with table (for multi-way joins)
     * Includes all rows from currentResult, with NULLs for unmatched right rows
     */
    private List<Map<String, Object>> joinWithTableLeft(
            List<Map<String, Object>> currentResult,
            String rightTable,
            JoinCondition condition,
            long readTs) {
        
        List<Map<String, Object>> newResult = new ArrayList<>();
        List<Map<String, Object>> rightRows = scanTable(rightTable, readTs);
        
        String leftCol = extractColumnName(condition.getLeftColumn()).toLowerCase();
        String rightCol = extractColumnName(condition.getRightColumn()).toLowerCase();
        
        // Get right table column names for NULL padding
        Set<String> rightColumns = rightRows.isEmpty() ? new HashSet<>() : rightRows.get(0).keySet();
        
        for (Map<String, Object> leftRow : currentResult) {
            Object leftValue = leftRow.get(leftCol);
            boolean foundMatch = false;
            
            if (leftValue != null) {
                for (Map<String, Object> rightRow : rightRows) {
                    Object rightValue = rightRow.get(rightCol);
                    if (rightValue != null && leftValue.equals(rightValue)) {
                        Map<String, Object> joinedRow = new HashMap<>();
                        joinedRow.putAll(leftRow);
                        joinedRow.putAll(rightRow);
                        newResult.add(joinedRow);
                        foundMatch = true;
                    }
                }
            }
            
            // If no match found, include left row with NULLs for right columns
            if (!foundMatch) {
                Map<String, Object> joinedRow = new HashMap<>();
                joinedRow.putAll(leftRow);
                // Add NULL for all right table columns
                for (String rightColumn : rightColumns) {
                    if (!joinedRow.containsKey(rightColumn)) {
                        joinedRow.put(rightColumn, null);
                    }
                }
                newResult.add(joinedRow);
            }
        }
        
        return newResult;
    }
    
    /**
     * RIGHT OUTER JOIN with table (for multi-way joins)
     * Includes all rows from rightTable, with NULLs for unmatched left rows
     */
    private List<Map<String, Object>> joinWithTableRight(
            List<Map<String, Object>> currentResult,
            String rightTable,
            JoinCondition condition,
            long readTs) {
        
        List<Map<String, Object>> newResult = new ArrayList<>();
        List<Map<String, Object>> rightRows = scanTable(rightTable, readTs);
        
        String leftCol = extractColumnName(condition.getLeftColumn()).toLowerCase();
        String rightCol = extractColumnName(condition.getRightColumn()).toLowerCase();
        
        // Get left result column names for NULL padding
        Set<String> leftColumns = currentResult.isEmpty() ? new HashSet<>() : currentResult.get(0).keySet();
        
        // Track which right rows were matched
        Set<Map<String, Object>> matchedRightRows = Collections.newSetFromMap(new IdentityHashMap<>());
        
        for (Map<String, Object> leftRow : currentResult) {
            Object leftValue = leftRow.get(leftCol);
            
            if (leftValue != null) {
                for (Map<String, Object> rightRow : rightRows) {
                    Object rightValue = rightRow.get(rightCol);
                    if (rightValue != null && leftValue.equals(rightValue)) {
                        Map<String, Object> joinedRow = new HashMap<>();
                        joinedRow.putAll(leftRow);
                        joinedRow.putAll(rightRow);
                        newResult.add(joinedRow);
                        matchedRightRows.add(rightRow);
                    }
                }
            }
        }
        
        // Add unmatched right rows with NULLs for left columns
        for (Map<String, Object> rightRow : rightRows) {
            if (!matchedRightRows.contains(rightRow)) {
                Map<String, Object> joinedRow = new HashMap<>();
                // Add NULL for all left columns
                for (String leftColumn : leftColumns) {
                    joinedRow.put(leftColumn, null);
                }
                // Add right row data
                joinedRow.putAll(rightRow);
                newResult.add(joinedRow);
            }
        }
        
        return newResult;
    }
    
    /**
     * FULL OUTER JOIN with table (for multi-way joins)
     * Includes all rows from both sides, with NULLs for unmatched rows
     */
    private List<Map<String, Object>> joinWithTableFull(
            List<Map<String, Object>> currentResult,
            String rightTable,
            JoinCondition condition,
            long readTs) {
        
        List<Map<String, Object>> newResult = new ArrayList<>();
        List<Map<String, Object>> rightRows = scanTable(rightTable, readTs);
        
        String leftCol = extractColumnName(condition.getLeftColumn()).toLowerCase();
        String rightCol = extractColumnName(condition.getRightColumn()).toLowerCase();
        
        // Get column names for NULL padding
        Set<String> leftColumns = currentResult.isEmpty() ? new HashSet<>() : currentResult.get(0).keySet();
        Set<String> rightColumns = rightRows.isEmpty() ? new HashSet<>() : rightRows.get(0).keySet();
        
        // Track which right rows were matched
        Set<Map<String, Object>> matchedRightRows = Collections.newSetFromMap(new IdentityHashMap<>());
        
        // Phase 1: Add all left rows (with matches or NULLs)
        for (Map<String, Object> leftRow : currentResult) {
            Object leftValue = leftRow.get(leftCol);
            boolean foundMatch = false;
            
            if (leftValue != null) {
                for (Map<String, Object> rightRow : rightRows) {
                    Object rightValue = rightRow.get(rightCol);
                    if (rightValue != null && leftValue.equals(rightValue)) {
                        Map<String, Object> joinedRow = new HashMap<>();
                        joinedRow.putAll(leftRow);
                        joinedRow.putAll(rightRow);
                        newResult.add(joinedRow);
                        matchedRightRows.add(rightRow);
                        foundMatch = true;
                    }
                }
            }
            
            // If no match, add left row with NULLs for right columns
            if (!foundMatch) {
                Map<String, Object> joinedRow = new HashMap<>();
                joinedRow.putAll(leftRow);
                for (String rightColumn : rightColumns) {
                    if (!joinedRow.containsKey(rightColumn)) {
                        joinedRow.put(rightColumn, null);
                    }
                }
                newResult.add(joinedRow);
            }
        }
        
        // Phase 2: Add unmatched right rows with NULLs for left columns
        for (Map<String, Object> rightRow : rightRows) {
            if (!matchedRightRows.contains(rightRow)) {
                Map<String, Object> joinedRow = new HashMap<>();
                for (String leftColumn : leftColumns) {
                    joinedRow.put(leftColumn, null);
                }
                joinedRow.putAll(rightRow);
                newResult.add(joinedRow);
            }
        }
        
        return newResult;
    }
    
    /**
     * Scan entire table
     */
    private List<Map<String, Object>> scanTable(String tableName, long readTs) {
        log.debug("   Scanning table: '{}'", tableName);
        TableMetadata table = schemaManager.getTable(tableName);
        if (table == null) {
            log.error("   Table not found: '{}' (case-sensitive check)", tableName);
            log.error("   Available tables: {}", schemaManager.getAllTableNames());
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        log.debug("   Found table: {} (id={})", table.getTableName(), table.getTableId());
        
        byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        
        List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 100000, table.getTruncateTimestamp());
        
        List<Map<String, Object>> rows = new ArrayList<>();
        
        // Get PK column types for decoding keys
        List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return Object.class;
                })
                .collect(Collectors.toList());
        
        // Get non-PK column types for decoding values
        List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
        
        for (KvStore.KvEntry entry : entries) {
            // Decode PK values from key
            List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
            
            // Decode non-PK values from value
            List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
            
            // Build row map with BOTH PK and non-PK columns
            Map<String, Object> row = new HashMap<>();
            
            // Add PK columns
            for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                String colName = table.getPrimaryKeyColumns().get(i);
                row.put(colName.toLowerCase(), pkValues.get(i));
            }
            
            // Add non-PK columns
            List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
            for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                row.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
            }
            
            rows.add(row);
        }
        
        return rows;
    }
    
    /**
     * Apply post-join WHERE filters
     */
    private List<Map<String, Object>> applyPostJoinFilters(
            List<Map<String, Object>> rows,
            List<String> whereConditions) {
        
        // Simple implementation: filter rows based on WHERE conditions
        // For MVP, we'll do string-based matching
        return rows.stream()
            .filter(row -> matchesWhereConditions(row, whereConditions))
            .collect(Collectors.toList());
    }
    
    /**
     * Check if a row matches WHERE conditions
     */
    private boolean matchesWhereConditions(Map<String, Object> row, List<String> conditions) {
        // For MVP: simple equality checks
        // Format: "column = value"
        for (String condition : conditions) {
            if (!matchesCondition(row, condition)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Check if a row matches a single condition
     */
    private boolean matchesCondition(Map<String, Object> row, String condition) {
        // Parse condition: "column op value" where op is =, >, <, >=, <=, !=
        // Support: "`TABLE`.`COLUMN` > value" or "table.column = value"
        
        String operator = null;
        String[] parts = null;
        
        // Try different operators (order matters - check >= before >)
        if (condition.contains(">=")) {
            operator = ">=";
            parts = condition.split(">=");
        } else if (condition.contains("<=")) {
            operator = "<=";
            parts = condition.split("<=");
        } else if (condition.contains("!=") || condition.contains("<>")) {
            operator = "!=";
            parts = condition.split("!=|<>");
        } else if (condition.contains(">")) {
            operator = ">";
            parts = condition.split(">");
        } else if (condition.contains("<")) {
            operator = "<";
            parts = condition.split("<");
        } else if (condition.contains("=")) {
            operator = "=";
            parts = condition.split("=");
        } else {
            return true; // Skip invalid conditions
        }
        
        if (parts.length != 2) return true; // Skip invalid conditions
        
        String columnRef = parts[0].trim();
        String valueStr = parts[1].trim().replace("'", "");
        
        // Remove backticks and convert to lowercase
        columnRef = columnRef.replace("`", "");
        
        // Extract column name (strip table prefix)
        String columnName = extractColumnName(columnRef).toLowerCase();
        
        Object actualValue = row.get(columnName);
        if (actualValue == null) return false;
        
        // Compare based on operator
        try {
            switch (operator) {
                case "=":
                    return actualValue.toString().equals(valueStr);
                case "!=":
                    return !actualValue.toString().equals(valueStr);
                case ">":
                    if (actualValue instanceof Number) {
                        double actual = ((Number) actualValue).doubleValue();
                        double expected = Double.parseDouble(valueStr);
                        return actual > expected;
                    }
                    return actualValue.toString().compareTo(valueStr) > 0;
                case "<":
                    if (actualValue instanceof Number) {
                        double actual = ((Number) actualValue).doubleValue();
                        double expected = Double.parseDouble(valueStr);
                        return actual < expected;
                    }
                    return actualValue.toString().compareTo(valueStr) < 0;
                case ">=":
                    if (actualValue instanceof Number) {
                        double actual = ((Number) actualValue).doubleValue();
                        double expected = Double.parseDouble(valueStr);
                        return actual >= expected;
                    }
                    return actualValue.toString().compareTo(valueStr) >= 0;
                case "<=":
                    if (actualValue instanceof Number) {
                        double actual = ((Number) actualValue).doubleValue();
                        double expected = Double.parseDouble(valueStr);
                        return actual <= expected;
                    }
                    return actualValue.toString().compareTo(valueStr) <= 0;
                default:
                    return true;
            }
        } catch (NumberFormatException e) {
            // If parsing fails, do string comparison
            return actualValue.toString().equals(valueStr);
        }
    }
    
    /**
     * Project specified columns (with alias support and expression evaluation)
     */
    private List<Map<String, Object>> projectColumns(
            List<Map<String, Object>> rows,
            List<String> selectColumns) {
        
        if (selectColumns == null || selectColumns.isEmpty() || selectColumns.contains("*")) {
            return rows; // Return all columns
        }
        
        List<Map<String, Object>> projected = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> projectedRow = new LinkedHashMap<>();
            for (String col : selectColumns) {
                // Check if column has an alias: "column AS alias" or "expression AS alias"
                if (col.toUpperCase().contains(" AS ")) {
                    String[] parts = col.split("(?i)\\s+AS\\s+");
                    if (parts.length == 2) {
                        String sourceExpression = parts[0].trim().replace("`", "");
                        String alias = parts[1].trim().replace("`", "").toLowerCase();
                        
                        // Evaluate the expression (handles concatenation, literals, and column references)
                        Object value = evaluateSelectExpression(sourceExpression, row);
                        
                        if (value != null) {
                            // Use alias as the key in projected row
                            projectedRow.put(alias, value);
                        }
                        continue;
                    }
                }
                
                // No alias - evaluate as expression or simple column reference
                String columnRef = col.trim().replace("`", "");
                Object value = evaluateSelectExpression(columnRef, row);
                
                if (value != null) {
                    String outputKey = extractColumnName(columnRef).toLowerCase();
                    projectedRow.put(outputKey, value);
                }
            }
            projected.add(projectedRow);
        }
        
        return projected;
    }
    
    /**
     * Evaluate a SELECT expression (handles concatenation, literals, and column references)
     */
    private Object evaluateSelectExpression(String expression, Map<String, Object> row) {
        // Check for string concatenation operator ||
        if (expression.contains("||")) {
            return evaluateConcatenation(expression, row);
        }
        
        // Check for literal string (single quotes)
        if (expression.startsWith("'") && expression.endsWith("'")) {
            return expression.substring(1, expression.length() - 1);
        }
        
        // Check for numeric literal
        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException e) {
            // Not a number, continue
        }
        
        // Treat as column reference - try qualified name first, then unqualified
        String columnRef = expression.toLowerCase();
        Object value = row.get(columnRef);
        if (value == null && columnRef.contains(".")) {
            // Try just the column name without table prefix
            String unqualified = extractColumnName(columnRef).toLowerCase();
            value = row.get(unqualified);
        }
        
        return value;
    }
    
    /**
     * Evaluate string concatenation expression (e.g., "first_name || ' ' || last_name")
     */
    private Object evaluateConcatenation(String expression, Map<String, Object> row) {
        // Split by || operator
        String[] parts = expression.split("\\|\\|");
        StringBuilder result = new StringBuilder();
        
        for (String part : parts) {
            part = part.trim();
            
            // Check if it's a literal string (single quotes)
            if (part.startsWith("'") && part.endsWith("'")) {
                result.append(part.substring(1, part.length() - 1));
            } else {
                // It's a column reference - evaluate it
                Object value = evaluateSelectExpression(part, row);
                if (value != null) {
                    result.append(value.toString());
                }
            }
        }
        
        return result.toString();
    }
    
    /**
     * Extract table alias from SELECT columns
     * @param selectColumns List of SELECT column expressions
     * @param isLeft true for left table, false for right table
     * @return table alias or null if not found
     */
    private String extractTableAliasFromSelectColumns(List<String> selectColumns, boolean isLeft) {
        if (selectColumns == null || selectColumns.isEmpty()) {
            return null;
        }
        
        // Look for qualified column references (e.g., "a.val AS a_val")
        // Extract the first alias found
        for (String col : selectColumns) {
            String columnExpr = col;
            
            // Remove "AS alias" part if present
            if (col.toUpperCase().contains(" AS ")) {
                String[] parts = col.split("(?i)\\s+AS\\s+");
                if (parts.length >= 1) {
                    columnExpr = parts[0].trim();
                }
            }
            
            // Check if it's qualified (e.g., "a.val")
            columnExpr = columnExpr.replace("`", "").trim();
            if (columnExpr.contains(".")) {
                String alias = columnExpr.substring(0, columnExpr.indexOf(".")).toLowerCase();
                // Return first alias found (assumes left columns come before right columns)
                if (isLeft) {
                    return alias;
                } else {
                    // For right table, skip the first alias and return the second one
                    // This is a simplification - ideally we'd track which alias belongs to which table
                    // For now, we'll collect all unique aliases and return the appropriate one
                    continue;
                }
            }
        }
        
        // If looking for right alias, do a second pass to find the second unique alias
        if (!isLeft) {
            String firstAlias = extractTableAliasFromSelectColumns(selectColumns, true);
            for (String col : selectColumns) {
                String columnExpr = col;
                if (col.toUpperCase().contains(" AS ")) {
                    String[] parts = col.split("(?i)\\s+AS\\s+");
                    if (parts.length >= 1) {
                        columnExpr = parts[0].trim();
                    }
                }
                columnExpr = columnExpr.replace("`", "").trim();
                if (columnExpr.contains(".")) {
                    String alias = columnExpr.substring(0, columnExpr.indexOf(".")).toLowerCase();
                    if (!alias.equals(firstAlias)) {
                        return alias;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extract column name from qualified reference (e.g., "users.id" -> "id")
     */
    private String extractColumnName(String columnRef) {
        if (columnRef.contains(".")) {
            return columnRef.substring(columnRef.lastIndexOf('.') + 1);
        }
        return columnRef;
    }
    
    /**
     * Parse WHERE clause into individual conditions
     * Simple implementation: split by AND
     */
    private List<String> parseWhereClause(String whereClause) {
        List<String> conditions = new ArrayList<>();
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return conditions;
        }
        
        // Simple split by AND (case-insensitive)
        String[] parts = whereClause.split("(?i)\\s+AND\\s+");
        for (String part : parts) {
            conditions.add(part.trim());
        }
        
        return conditions;
    }
}

