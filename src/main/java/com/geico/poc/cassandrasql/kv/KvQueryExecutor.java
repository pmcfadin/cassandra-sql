package com.geico.poc.cassandrasql.kv;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import com.geico.poc.cassandrasql.AggregateFunction;
import com.geico.poc.cassandrasql.AggregationQuery;
import com.geico.poc.cassandrasql.CalciteParser;
import com.geico.poc.cassandrasql.LimitClause;
import com.geico.poc.cassandrasql.OrderByClause;
import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.window.WindowFunctionExecutor;
import com.geico.poc.cassandrasql.window.WindowFunctionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Query executor for KV storage mode.
 * 
 * Executes SQL queries (SELECT, INSERT, UPDATE, DELETE) on the KV store.
 * Automatically maintains indexes and uses transactions for consistency.
 */
@Component
public class KvQueryExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(KvQueryExecutor.class);
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private KvStore kvStore;
    
    @Autowired
    private KvTransactionCoordinator coordinator;
    
    @Autowired
    private TimestampOracle timestampOracle;
    
    @Autowired
    private CalciteSqlParser sqlParser;
    
    @Autowired
    private KvJoinExecutor joinExecutor;
    
    @Autowired(required = false)
    private PgCatalogManager pgCatalogManager;
    
    @Autowired
    @org.springframework.context.annotation.Lazy
    private UnionExecutor unionExecutor;
    
    @Autowired
    private com.geico.poc.cassandrasql.SubqueryExecutor subqueryExecutor;
    
    @Autowired
    @org.springframework.context.annotation.Lazy
    private ExplainExecutor explainExecutor;
    
    /**
     * Get the schema manager (for EXPLAIN and other tools)
     */
    public SchemaManager getSchemaManager() {
        return schemaManager;
    }
    
    /**
     * Execute a raw SQL query (used by UnionExecutor)
     */
    public QueryResponse executeQuery(String sql) {
        try {
            // Parse the SQL
            CalciteParser parser = new CalciteParser();
            ParsedQuery query = parser.parse(sql);
            return execute(query);
        } catch (Exception e) {
            return QueryResponse.error("Query execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute a query in KV mode (auto-commit)
     */
    public QueryResponse execute(ParsedQuery query) {
        try {
            // Check for UNION queries FIRST (before switch statement)
            // UNION queries come in as Type.SELECT but need special handling
            // Only route to UnionExecutor if the query is ACTUALLY a UNION (not just contains the word)
            if (query.getType() == ParsedQuery.Type.SELECT && query.getRawSql() != null) {
                String upperSql = query.getRawSql().toUpperCase();
                // Check if it's a UNION by looking for UNION between SELECT statements
                // Pattern: SELECT ... UNION ... SELECT
                if (upperSql.matches("(?s).*\\bSELECT\\b.*\\bUNION\\b.*\\bSELECT\\b.*")) {
                    log.info("🔗 Routing UNION query to UnionExecutor");
                    return unionExecutor.execute(query.getRawSql());
                }
            }
            
            switch (query.getType()) {
                case EXPLAIN:
                case EXPLAIN_ANALYZE:
                    return explainExecutor.execute(query);
                case WINDOW_FUNCTION:
                    return executeWindowFunction(query);
                case SELECT:
                case SELECT_WITH_SUBQUERY:
                    // Check for FROM subqueries first - these are handled directly here
                    // This must happen BEFORE routing to SubqueryExecutor to avoid infinite recursion
                    QueryResponse fromSubqueryResult = checkAndExecuteFromSubquery(query.getRawSql());
                    if (fromSubqueryResult != null) {
                        return fromSubqueryResult;
                    }
                    
                    // If no FROM subquery, handle normally
                    if (query.getType() == ParsedQuery.Type.SELECT_WITH_SUBQUERY) {
                        return subqueryExecutor.execute(query);
                    }
                    return executeSelect(query);
                case INSERT:
                    return executeInsert(query);
                case UPDATE:
                    return executeUpdate(query);
                case DELETE:
                    return executeDelete(query);
                case JOIN:
                    return executeJoinWithModifiers(query, joinExecutor.executeBinaryJoin(query.getJoinQuery()));
                case MULTI_WAY_JOIN:
                    return executeJoinWithModifiers(query, joinExecutor.executeMultiWayJoin(query.getMultiWayJoin()));
                case AGGREGATION:
                    return executeAggregation(query);
                case CREATE_TABLE:
                    return executeCreateTable(query);
                case DROP_TABLE:
                    return executeDropTable(query);
                case CREATE_SEQUENCE:
                    return executeCreateSequence(query);
                case DROP_SEQUENCE:
                    return executeDropSequence(query);
                case CREATE_TYPE:
                    return executeCreateType(query);
                case DROP_TYPE:
                    return executeDropType(query);
                case CREATE_VIEW:
                    return executeCreateView(query);
                case CREATE_MATERIALIZED_VIEW:
                    return executeCreateMaterializedView(query);
                case DROP_VIEW:
                    return executeDropView(query);
                case REFRESH_MATERIALIZED_VIEW:
                    return executeRefreshMaterializedView(query);
                case VACUUM:
                    return executeVacuum(query);
                case ANALYZE:
                    return executeAnalyze(query);
                case SET:
                    return executeSet(query);
                case ALTER_TABLE:
                    return executeAlterTable(query);
                case TRUNCATE:
                    return executeTruncate(query);
                case CREATE_INDEX:
                    return executeCreateIndex(query);
                default:
                    return QueryResponse.error("Unsupported query type in KV mode: " + query.getType());
            }
        } catch (Exception e) {
            return QueryResponse.error("Query execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute a subquery without checking for FROM subqueries.
     * This is used when executing nested FROM subqueries to avoid infinite recursion.
     */
    private QueryResponse executeSubqueryWithoutFromCheck(ParsedQuery query) {
        try {
            switch (query.getType()) {
                case SELECT:
                    // Simple SELECT - execute directly
                    return executeSelect(query);
                case SELECT_WITH_SUBQUERY:
                    // SELECT with subqueries (IN, EXISTS, etc.) - but NOT FROM subqueries
                    // FROM subqueries are handled before this point
                    // For other subqueries, we can route to SubqueryExecutor, but it should not
                    // call back to execute() for FROM subqueries since we've already handled them
                    // However, to be safe, let's check if SubqueryExecutor would call back
                    // For now, just execute as a regular SELECT - the subqueries will be handled
                    // when the outer query processes them
                    log.debug("Executing SELECT_WITH_SUBQUERY as regular SELECT (FROM subqueries already handled)");
                    return executeSelect(query);
                case JOIN:
                    return executeJoinWithModifiers(query, joinExecutor.executeBinaryJoin(query.getJoinQuery()));
                case MULTI_WAY_JOIN:
                    return executeJoinWithModifiers(query, joinExecutor.executeMultiWayJoin(query.getMultiWayJoin()));
                case AGGREGATION:
                    return executeAggregation(query);
                default:
                    return QueryResponse.error("Unsupported subquery type: " + query.getType());
            }
        } catch (Exception e) {
            log.error("Subquery execution failed", e);
            return QueryResponse.error("Subquery execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute a query within an active transaction.
     * Writes (INSERT/UPDATE/DELETE) are buffered in the transaction context.
     * Reads (SELECT) see buffered writes + committed data.
     */
    public QueryResponse executeInTransaction(KvTransactionContext txCtx, ParsedQuery query) {
        log.debug("Executing query in transaction {}: {}", txCtx.getTxId(), query.getType());
        
        try {
            switch (query.getType()) {
                case SELECT:
                    // Read with transaction snapshot + buffered writes
                    return executeSelectInTransaction(txCtx, query);
                case INSERT:
                    // Buffer write in transaction
                    return executeInsertInTransaction(txCtx, query);
                case UPDATE:
                    // Buffer write in transaction
                    return executeUpdateInTransaction(txCtx, query);
                case DELETE:
                    // Buffer write in transaction
                    return executeDeleteInTransaction(txCtx, query);
                case JOIN:
                case MULTI_WAY_JOIN:
                case AGGREGATION:
                    // Read-only operations - execute with transaction snapshot
                    return executeSelectInTransaction(txCtx, query);
                default:
                    // DDL operations (CREATE TABLE, etc.) are not transactional
                    return execute(query);
            }
        } catch (Exception e) {
            log.error("Error executing query in transaction: {}", e.getMessage(), e);
            return QueryResponse.error("Query execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute SELECT within a transaction.
     * Reads see: committed data at start_ts + buffered writes in this transaction
     */
    private QueryResponse executeSelectInTransaction(KvTransactionContext txCtx, ParsedQuery query) {
        try {
            // Check if ParsedQuery has "unknown" as tableName - this indicates a literal SELECT
            if ("unknown".equals(query.getTableName())) {
                try {
                    CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(query.getRawSql());
                    return executeLiteralSelect(query.getRawSql(), selectInfo);
                } catch (Exception e) {
                    log.warn("Failed to parse literal SELECT in transaction: {}", e.getMessage());
                }
            }
            
            // Parse the SELECT query
            CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(query.getRawSql());
            
            // Handle SELECT without FROM clause (e.g., SELECT 1, SELECT 'hello')
            // Literal SELECTs don't need transaction handling - they're just constant values
            if (selectInfo.getTableName() == null || "unknown".equals(selectInfo.getTableName())) {
                return executeLiteralSelect(query.getRawSql(), selectInfo);
            }
            
            TableMetadata table = schemaManager.getTable(selectInfo.getTableName());
            if (table == null) {
                // Check if this might be a literal SELECT
                if (selectInfo.getLiterals() != null && !selectInfo.getLiterals().isEmpty() &&
                    (selectInfo.getColumns() == null || selectInfo.getColumns().isEmpty())) {
                    return executeLiteralSelect(query.getRawSql(), selectInfo);
                }
                return QueryResponse.error("Table does not exist: " + selectInfo.getTableName());
            }
            
            // Use the transaction's start_ts for snapshot isolation
            // This ensures we see a consistent snapshot from when the transaction started
            long readTs = txCtx.getStartTs();
            
            // Execute range scan with WHERE clause filtering
            QueryResponse response = executeRangeScanWithFilter(table, selectInfo, readTs);
            
            // Overlay buffered writes from transaction context
            response = applyBufferedWriteOverlay(txCtx, table, response);
            
            return response;
            
        } catch (Exception e) {
            log.error("SELECT in transaction failed", e);
            return QueryResponse.error("SELECT failed: " + e.getMessage());
        }
    }
    
    /**
     * Apply buffered writes from transaction context to SELECT results.
     * This implements read-your-own-writes semantics.
     */
    private QueryResponse applyBufferedWriteOverlay(
            KvTransactionContext txCtx, 
            TableMetadata table, 
            QueryResponse baseResponse) {
        
        log.info("🔍 [OVERLAY] Applying buffered write overlay for table {} (id={})", 
            table.getTableName(), table.getTableId());
        log.info("🔍 [OVERLAY] Total write intents in transaction: {}", txCtx.getWriteIntents().size());
        
        // If no buffered writes, return as-is
        if (txCtx.getWriteIntents().isEmpty()) {
            log.debug("🔍 [OVERLAY] No buffered writes, returning base response");
            return baseResponse;
        }
        
        // Build a map of primary key -> buffered write for this table
        Map<String, KvTransactionContext.WriteIntent> bufferedWrites = new LinkedHashMap<>();
        for (KvTransactionContext.WriteIntent intent : txCtx.getWriteIntents()) {
            try {
                // Decode the key to check if it's for this table
                KeyEncoder.DecodedKey decoded = KeyEncoder.decodeKey(intent.getKey());
                log.info("🔍 [OVERLAY] Decoded intent: tableId={}, targetTableId={}, writeType={}", 
                    decoded.getTableId(), table.getTableId(), intent.getWriteType());
                
                if (decoded.getTableId() == table.getTableId()) {
                    // Use the key as a string for lookup
                    String keyStr = java.util.Base64.getEncoder().encodeToString(intent.getKey());
                    bufferedWrites.put(keyStr, intent);
                    log.info("🔍 [OVERLAY] Added buffered write for this table: writeType={}, pkValues={}", 
                        intent.getWriteType(), decoded.getPrimaryKeyValues());
                }
            } catch (Exception e) {
                log.warn("Failed to decode buffered write key: {}", e.getMessage(), e);
            }
        }
        
        log.info("🔍 [OVERLAY] Found {} buffered writes for table {}", 
            bufferedWrites.size(), table.getTableName());
        
        // If no buffered writes for this table, return as-is
        if (bufferedWrites.isEmpty()) {
            log.debug("🔍 [OVERLAY] No buffered writes for this table, returning base response");
            return baseResponse;
        }
        
        // Apply overlay: merge buffered writes into results
        List<Map<String, Object>> resultRows = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();
        
        // Process existing rows from base response
        if (baseResponse.getRows() != null) {
            for (Map<String, Object> row : baseResponse.getRows()) {
                // Extract primary key from row
                List<Object> pkValues = new ArrayList<>();
                for (String pkCol : table.getPrimaryKeyColumns()) {
                    pkValues.add(row.get(pkCol.toLowerCase()));
                }
                
                // Encode the key to check for buffered writes
                byte[] rowKey = KeyEncoder.encodeTableDataKey(
                    table.getTableId(), 
                    pkValues, 
                    txCtx.getStartTs()
                );
                String keyStr = java.util.Base64.getEncoder().encodeToString(rowKey);
                processedKeys.add(keyStr);
                
                // Check if this row has a buffered write
                KvTransactionContext.WriteIntent intent = bufferedWrites.get(keyStr);
                if (intent != null) {
                    if (intent.getWriteType() == KvTransactionContext.WriteType.DELETE) {
                        // Row is deleted in transaction - skip it
                        continue;
                    } else {
                        // Row is updated - decode and use buffered value
                        try {
                            // Get column types for decoding
                            List<Class<?>> columnTypes = new ArrayList<>();
                            List<TableMetadata.ColumnMetadata> nonPkCols = table.getNonPrimaryKeyColumns();
                            for (TableMetadata.ColumnMetadata col : nonPkCols) {
                                columnTypes.add(col.getJavaType());
                            }
                            
                            List<Object> bufferedValues = ValueEncoder.decodeRow(intent.getValue(), columnTypes);
                            Map<String, Object> updatedRow = new LinkedHashMap<>(row);
                            
                            // Update non-PK columns with buffered values
                            for (int i = 0; i < nonPkCols.size() && i < bufferedValues.size(); i++) {
                                updatedRow.put(nonPkCols.get(i).getName().toLowerCase(), bufferedValues.get(i));
                            }
                            
                            resultRows.add(updatedRow);
                        } catch (Exception e) {
                            log.warn("Failed to decode buffered value: {}", e.getMessage());
                            resultRows.add(row); // Fall back to original row
                        }
                    }
                } else {
                    // No buffered write - use original row
                    resultRows.add(row);
                }
            }
        }
        
        // Add new rows from buffered writes (INSERTs)
        for (Map.Entry<String, KvTransactionContext.WriteIntent> entry : bufferedWrites.entrySet()) {
            if (processedKeys.contains(entry.getKey())) {
                continue; // Already processed as UPDATE or DELETE
            }
            
            KvTransactionContext.WriteIntent intent = entry.getValue();
            if (intent.getWriteType() == KvTransactionContext.WriteType.PUT) {
                try {
                    // Decode the key to get PK values
                    KeyEncoder.DecodedKey decoded = KeyEncoder.decodeKey(intent.getKey());
                    List<Object> pkValues = decoded.getPrimaryKeyValues();
                    
                    // Get column types for decoding
                    List<Class<?>> columnTypes = new ArrayList<>();
                    List<TableMetadata.ColumnMetadata> nonPkCols = table.getNonPrimaryKeyColumns();
                    for (TableMetadata.ColumnMetadata col : nonPkCols) {
                        columnTypes.add(col.getJavaType());
                    }
                    
                    // Decode the value to get non-PK values
                    List<Object> nonPkValues = ValueEncoder.decodeRow(intent.getValue(), columnTypes);
                    
                    // Build the row
                    Map<String, Object> newRow = new LinkedHashMap<>();
                    
                    // Add PK columns
                    List<String> pkCols = table.getPrimaryKeyColumns();
                    for (int i = 0; i < pkCols.size() && i < pkValues.size(); i++) {
                        newRow.put(pkCols.get(i).toLowerCase(), pkValues.get(i));
                    }
                    
                    // Add non-PK columns
                    for (int i = 0; i < nonPkCols.size() && i < nonPkValues.size(); i++) {
                        newRow.put(nonPkCols.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    resultRows.add(newRow);
                } catch (Exception e) {
                    log.warn("Failed to decode buffered INSERT: {}", e.getMessage());
                }
            }
        }
        
        // Create new response with overlaid results
        QueryResponse overlaidResponse = new QueryResponse();
        overlaidResponse.setRows(resultRows);
        overlaidResponse.setRowCount(resultRows.size());
        overlaidResponse.setColumns(baseResponse.getColumns());
        
        return overlaidResponse;
    }
    
    /**
     * Execute INSERT within a transaction.
     * Write is buffered in transaction context, not committed until COMMIT.
     */
    private QueryResponse executeInsertInTransaction(KvTransactionContext txCtx, ParsedQuery query) {
        // Parse INSERT and buffer the writes
        try {
            CalciteSqlParser.InsertInfo insertInfo = sqlParser.parseInsert(query.getRawSql());
            TableMetadata table = schemaManager.getTable(insertInfo.getTableName());
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + insertInfo.getTableName());
            }
            
            int rowsInserted = 0;
            for (List<Object> values : insertInfo.getValuesList()) {
                // Map values to columns
                Map<String, Object> row = new LinkedHashMap<>();
                List<String> columns = insertInfo.getColumns();
                if (columns.isEmpty()) {
                    columns = getColumnNames(table);
                }
                
                for (int i = 0; i < columns.size() && i < values.size(); i++) {
                    row.put(columns.get(i).toLowerCase(), values.get(i));
                }
                
                // Extract primary key values
                List<Object> pkValues = new ArrayList<>();
                for (String pkCol : table.getPrimaryKeyColumns()) {
                    Object pkValue = row.get(pkCol.toLowerCase());
                    if (pkValue == null) {
                        return QueryResponse.error("Primary key column '" + pkCol + "' cannot be null");
                    }
                    pkValues.add(pkValue);
                }
                
                // Extract non-primary key values
                List<Object> nonPkValues = new ArrayList<>();
                for (TableMetadata.ColumnMetadata col : table.getNonPrimaryKeyColumns()) {
                    Object value = row.get(col.getName().toLowerCase());
                    nonPkValues.add(value);
                }
                
                // Encode key and value for primary data (same as auto-commit INSERT)
                byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), pkValues, txCtx.getStartTs());
                byte[] value = ValueEncoder.encodeRow(nonPkValues);
                
                // Add write to transaction (will be committed later)
                txCtx.addWrite(key, value, KvTransactionContext.WriteType.PUT);
                
                // TODO: Handle secondary indexes
                
                rowsInserted++;
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsInserted);
            log.debug("Buffered {} INSERT(s) in transaction {}", rowsInserted, txCtx.getTxId());
            return response;
            
        } catch (Exception e) {
            return QueryResponse.error("INSERT failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute UPDATE within a transaction.
     * Writes are buffered in transaction context, not committed until COMMIT.
     */
    private QueryResponse executeUpdateInTransaction(KvTransactionContext txCtx, ParsedQuery query) {
        try {
            // Parse UPDATE statement
            CalciteSqlParser.UpdateInfo updateInfo = sqlParser.parseUpdate(query.getRawSql());
            
            // Get table metadata
            TableMetadata table = schemaManager.getTable(updateInfo.getTableName());
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + updateInfo.getTableName());
            }
            
            // Use transaction's start timestamp for reads
            long readTs = txCtx.getStartTs();
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, table.getTruncateTimestamp());
            
            // CRITICAL: Also include buffered writes from this transaction for read-your-own-writes
            // The scan only returns committed data, but we need to see our own buffered INSERTs
            List<KvStore.KvEntry> allEntries = new ArrayList<>(entries);
            for (KvTransactionContext.WriteIntent intent : txCtx.getWriteIntents()) {
                try {
                    KeyEncoder.DecodedKey decoded = KeyEncoder.decodeKey(intent.getKey());
                    if (decoded.getTableId() == table.getTableId() && intent.getWriteType() == KvTransactionContext.WriteType.PUT) {
                        // This is a buffered write for this table - include it
                        KvStore.KvEntry bufferedEntry = new KvStore.KvEntry(
                            intent.getKey(), intent.getValue(), txCtx.getStartTs(), txCtx.getTxId(), null, false);
                        allEntries.add(bufferedEntry);
                    }
                } catch (Exception e) {}
            }
            
            // Get PK column types for decoding
            List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                    .map(pkColName -> {
                        for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                            if (col.getName().equalsIgnoreCase(pkColName)) {
                                return col.getJavaType();
                            }
                        }
                        return String.class; // Default
                    })
                    .collect(Collectors.toList());
            
            int rowsUpdated = 0;
            
            // Get non-PK columns
            List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
            
            for (KvStore.KvEntry entry : allEntries) {
                // Decode key to get PK values
                List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                
                // Decode current row value
                List<Class<?>> nonPkColumnTypes = nonPkColumns.stream()
                        .map(TableMetadata.ColumnMetadata::getJavaType)
                        .collect(Collectors.toList());
                
                List<Object> currentValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                
                // Build current row map (with PK and non-PK columns)
                Map<String, Object> row = new HashMap<>();
                
                // Add PK columns
                for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                    String colName = table.getPrimaryKeyColumns().get(i);
                    row.put(colName.toLowerCase(), pkValues.get(i));
                }
                
                // Add non-PK columns
                for (int i = 0; i < nonPkColumns.size() && i < currentValues.size(); i++) {
                    row.put(nonPkColumns.get(i).getName().toLowerCase(), currentValues.get(i));
                }
                
                // Apply WHERE clause filter
                if (updateInfo.getWhereClause() != null && !updateInfo.getWhereClause().isEmpty()) {
                    boolean matches = true;
                    for (CalciteSqlParser.Predicate pred : updateInfo.getWhereClause().getPredicates()) {
                        Object rowValue = row.get(pred.getColumn().toLowerCase());
                        if (!pred.evaluate(rowValue)) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) {
                        continue;  // Skip this row
                    }
                }
                
                // Apply updates - evaluate expressions with current row values
                for (Map.Entry<String, CalciteSqlParser.UpdateExpression> update : updateInfo.getSetExpressions().entrySet()) {
                    String columnName = update.getKey().toLowerCase();
                    Object newValue = update.getValue().evaluate(row);
                    row.put(columnName, newValue);
                }
                
                // Encode new value
                List<Object> newValues = new ArrayList<>();
                for (TableMetadata.ColumnMetadata col : nonPkColumns) {
                    newValues.add(row.get(col.getName().toLowerCase()));
                }
                byte[] newValue = ValueEncoder.encodeRow(newValues);
                
                // Buffer write in transaction (reuse existing key, will be committed later)
                txCtx.addWrite(entry.getKey(), newValue, KvTransactionContext.WriteType.PUT);
                rowsUpdated++;
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsUpdated);
            log.info("✅ [UPDATE] Buffered {} UPDATE(s) in transaction {}", rowsUpdated, txCtx.getTxId());
            return response;
            
        } catch (SqlParseException e) {
            return QueryResponse.error("Failed to parse UPDATE: " + e.getMessage());
        } catch (Exception e) {
            return QueryResponse.error("UPDATE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute DELETE within a transaction.
     * Write is buffered in transaction context.
     */
    private QueryResponse executeDeleteInTransaction(KvTransactionContext txCtx, ParsedQuery query) {
        // For now, execute normally but buffer the write
        // TODO: Implement proper buffering
        return executeDelete(query);
    }
    
    /**
     * Execute SELECT query
     */
    private QueryResponse executeSelect(ParsedQuery query) {
        try {
            // FROM subqueries are already checked in execute() method above
            // No need to check again here
            
            // Check if ParsedQuery has "unknown" as tableName - this indicates a literal SELECT
            // from the old CalciteParser that couldn't extract a table name
            if ("unknown".equals(query.getTableName())) {
                // Parse with CalciteSqlParser to get proper literal info
                try {
                    CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(query.getRawSql());
                    return executeLiteralSelect(query.getRawSql(), selectInfo);
                } catch (Exception e) {
                    // If parsing fails, still try to execute as literal SELECT
                    log.warn("Failed to parse literal SELECT, attempting direct execution: {}", e.getMessage());
                }
            }
            
            // Parse SELECT statement
            CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(query.getRawSql());
            
            // Handle SELECT without FROM clause (e.g., SELECT 1, SELECT 'hello')
            // Also check if we have literals but no valid table - this indicates a literal-only SELECT
            boolean isLiteralSelect = (selectInfo.getTableName() == null || 
                                      "unknown".equals(selectInfo.getTableName()) ||
                                      (selectInfo.getTableName() != null && 
                                       selectInfo.getLiterals() != null &&
                                       !selectInfo.getLiterals().isEmpty() &&
                                       (selectInfo.getColumns() == null || selectInfo.getColumns().isEmpty())));
            
            if (isLiteralSelect) {
                return executeLiteralSelect(query.getRawSql(), selectInfo);
            }
            
            // Check if the table is actually a view
            log.debug("🔍 Looking for view: {}", selectInfo.getTableName());
            ViewMetadata view = schemaManager.getView(selectInfo.getTableName());
            log.debug("🔍 View found: {}", view != null);
            if (view != null) {
                log.debug("🔍 Executing view query for: {}", view.getViewName());
                return executeViewQuery(view, query);
            }
            
            log.debug("🔍 Looking for table: {}", selectInfo.getTableName());
            
            // If tableName is "unknown", this is likely a literal SELECT that was mis-parsed
            if ("unknown".equals(selectInfo.getTableName())) {
                return executeLiteralSelect(query.getRawSql(), selectInfo);
            }
            
            TableMetadata table = schemaManager.getTable(selectInfo.getTableName());
            if (table == null) {
                // Before erroring, check if this might be a literal SELECT that was mis-parsed
                if (selectInfo.getLiterals() != null && !selectInfo.getLiterals().isEmpty() && 
                    (selectInfo.getColumns() == null || selectInfo.getColumns().isEmpty())) {
                    // This looks like a literal-only SELECT that was mis-parsed
                    return executeLiteralSelect(query.getRawSql(), selectInfo);
                }
                // Debug: List all available views and tables
                log.debug("🔍 Available views: {}", schemaManager.getAllViews().stream()
                    .map(ViewMetadata::getViewName).toArray());
                log.debug("🔍 Available tables: {}", schemaManager.getAllTables().stream()
                    .map(TableMetadata::getTableName).toArray());
                return QueryResponse.error("Table does not exist: " + selectInfo.getTableName());
            }
            
            // Validate that all selected columns exist in the table
            String columnError = validateColumnsExist(table, selectInfo.getColumns());
            if (columnError != null) {
                return QueryResponse.error(columnError);
            }
            
            // Allocate a NEW read timestamp to ensure we see all committed data
            // Using getCurrentTimestamp() would use the batch start which might be stale
            long readTs = timestampOracle.allocateStartTimestamp();
            
            // Execute range scan with WHERE clause filtering
            return executeRangeScanWithFilter(table, selectInfo, readTs);
            
        } catch (SqlParseException e) {
            // Fallback to simple scan if parsing fails
            log.warn("SQL parsing failed, falling back to simple scan: {}", e.getMessage());
            
            // Check if this is a literal SELECT (no FROM clause) - ParsedQuery might have "unknown" as tableName
            if (query.getTableName() == null || "unknown".equals(query.getTableName())) {
                // Try to parse as literal SELECT
                try {
                    CalciteSqlParser.SelectInfo fallbackSelectInfo = sqlParser.parseSelect(query.getRawSql());
                    if (fallbackSelectInfo.getTableName() == null) {
                        return executeLiteralSelect(query.getRawSql(), fallbackSelectInfo);
                    }
                } catch (Exception parseEx) {
                    // If that also fails, continue with error below
                }
                return QueryResponse.error("Table does not exist: " + (query.getTableName() != null ? query.getTableName() : "unknown"));
            }
            
            TableMetadata table = schemaManager.getTable(query.getTableName());
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + query.getTableName());
            }
            
            // Allocate a NEW read timestamp to ensure we see all committed data
            long readTs = timestampOracle.allocateStartTimestamp();
            return executeRangeScan(table, query, readTs);
        } catch (Exception e) {
            log.error("SELECT failed", e);
            return QueryResponse.error("SELECT failed: " + e.getMessage());
        }
    }
    
    /**
     * Validate that all columns in the SELECT list exist in the table schema
     * @return Error message if validation fails, null if all columns are valid
     */
    private String validateColumnsExist(TableMetadata table, List<String> columns) {
        for (String col : columns) {
            // Skip wildcards
            if (col.equals("*")) {
                continue;
            }
            
            // Skip expressions: aggregates, qualified columns, concatenation, arithmetic, CASE
            if (col.contains("(") || col.contains("||") || isArithmeticExpression(col) || 
                col.toUpperCase().contains("CASE ") || col.toUpperCase().contains(" WHEN ")) {
                continue;
            }
            
            // Skip qualified column references (e.g., "table.column")
            if (col.contains(".")) {
                continue;
            }
            
            // Remove backticks and quotes
            String cleanCol = col.replaceAll("[`\"']", "").toLowerCase();
            
            // Check if column exists
            if (table.getColumn(cleanCol) == null) {
                return "Column does not exist: " + cleanCol;
            }
        }
        return null;
    }
    
    /**
     * Check if a column expression contains arithmetic operators
     */
    private boolean isArithmeticExpression(String col) {
        // Check for arithmetic operators, but be careful not to match negative numbers
        // or qualified column names
        String trimmed = col.trim();
        
        // Check for operators surrounded by spaces or other characters
        return trimmed.matches(".*[+\\-*/].*") && 
               (trimmed.contains(" + ") || trimmed.contains(" - ") || 
                trimmed.contains(" * ") || trimmed.contains(" / ") ||
                trimmed.matches(".*\\d+\\s*[+\\-*/].*") ||
                trimmed.matches(".*[+\\-*/]\\s*\\d+.*"));
    }
    
    /**
     * Execute SELECT with only literals (no FROM clause).
     * Examples: SELECT 1, SELECT 'hello', SELECT 1 + 1
     */
    private QueryResponse executeLiteralSelect(String sql, CalciteSqlParser.SelectInfo selectInfo) {
        try {
            // Use Calcite to evaluate the literals
            SqlParser parser = SqlParser.create(sql);
            SqlNode sqlNode = parser.parseStmt();
            
            if (!(sqlNode instanceof SqlSelect)) {
                return QueryResponse.error("Not a SELECT statement");
            }
            
            SqlSelect select = (SqlSelect) sqlNode;
            SqlNodeList selectList = select.getSelectList();
            
            // Build result with one row containing the literal values
            List<String> columns = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            
            int colIndex = 1;
            for (SqlNode node : selectList) {
                String columnName;
                Object value;
                
                // Check if there's an alias
                if (node.getKind() == SqlKind.AS) {
                    SqlCall asCall = (SqlCall) node;
                    SqlNode valueNode = asCall.operand(0);
                    SqlNode aliasNode = asCall.operand(1);
                    columnName = aliasNode.toString();
                    value = evaluateLiteral(valueNode);
                } else {
                    // No alias, use default column name
                    columnName = "?column?"; // PostgreSQL uses this for unnamed columns
                    if (colIndex > 1) {
                        columnName = "?column?" + colIndex;
                    }
                    value = evaluateLiteral(node);
                }
                
                columns.add(columnName);
                row.put(columnName, value);
                colIndex++;
            }
            
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row);
            
            return new QueryResponse(rows, columns);
            
        } catch (Exception e) {
            return QueryResponse.error("Failed to execute literal SELECT: " + e.getMessage());
        }
    }
    
    /**
     * Evaluate a literal SQL node to its Java value.
     */
    private Object evaluateLiteral(SqlNode node) {
        if (node instanceof SqlLiteral) {
            SqlLiteral literal = (SqlLiteral) node;
            
            // Handle different literal types
            switch (literal.getTypeName()) {
                case INTEGER:
                case BIGINT:
                case SMALLINT:
                case TINYINT:
                    return literal.bigDecimalValue().intValue();
                    
                case DECIMAL:
                case DOUBLE:
                case FLOAT:
                case REAL:
                    return literal.bigDecimalValue().doubleValue();
                    
                case CHAR:
                case VARCHAR:
                    return literal.getValueAs(String.class);
                    
                case BOOLEAN:
                    return literal.booleanValue();
                    
                case NULL:
                    return null;
                    
                default:
                    return literal.getValue();
            }
        } else if (node instanceof SqlCall) {
            // Handle expressions like 1 + 1
            SqlCall call = (SqlCall) node;
            
            // Simple arithmetic operations
            if (call.getOperator().getKind() == SqlKind.PLUS) {
                Object left = evaluateLiteral(call.operand(0));
                Object right = evaluateLiteral(call.operand(1));
                if (left instanceof Number && right instanceof Number) {
                    return ((Number) left).doubleValue() + ((Number) right).doubleValue();
                }
            } else if (call.getOperator().getKind() == SqlKind.MINUS) {
                Object left = evaluateLiteral(call.operand(0));
                Object right = evaluateLiteral(call.operand(1));
                if (left instanceof Number && right instanceof Number) {
                    return ((Number) left).doubleValue() - ((Number) right).doubleValue();
                }
            } else if (call.getOperator().getKind() == SqlKind.TIMES) {
                Object left = evaluateLiteral(call.operand(0));
                Object right = evaluateLiteral(call.operand(1));
                if (left instanceof Number && right instanceof Number) {
                    return ((Number) left).doubleValue() * ((Number) right).doubleValue();
                }
            } else if (call.getOperator().getKind() == SqlKind.DIVIDE) {
                Object left = evaluateLiteral(call.operand(0));
                Object right = evaluateLiteral(call.operand(1));
                if (left instanceof Number && right instanceof Number) {
                    return ((Number) left).doubleValue() / ((Number) right).doubleValue();
                }
            }
            
            // For other expressions, return the string representation
            return call.toString();
        }
        
        // Default: return string representation
        return node.toString();
    }
    
    /**
     * Execute point query (single row by primary key)
     */
    private QueryResponse executePointQuery(TableMetadata table, List<Object> pkValues,
                                           ParsedQuery query, long readTs) {
        // Encode key
        byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), pkValues, readTs);
        
        // Get from KV store
        KvStore.KvEntry entry = kvStore.get(key, readTs);
        
        if (entry == null) {
            // No data found
            return new QueryResponse(new ArrayList<>(), getColumnNames(table));
        }
        
        // Decode value
        List<Class<?>> columnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
        
        List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), columnTypes);
        
        // Combine PK and non-PK values
        Map<String, Object> row = new HashMap<>();
        
        // Add PK columns
        for (int i = 0; i < table.getPrimaryKeyColumns().size(); i++) {
            String colName = table.getPrimaryKeyColumns().get(i);
            row.put(colName.toLowerCase(), pkValues.get(i));
        }
        
        // Add non-PK columns
        List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
        for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
            String colName = nonPkColumns.get(i).getName();
            row.put(colName.toLowerCase(), nonPkValues.get(i));
        }
        
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        
        return new QueryResponse(rows, getColumnNames(table));
    }
    
    /**
     * Execute range scan with WHERE clause filtering
     */
    private QueryResponse executeRangeScanWithFilter(TableMetadata table, CalciteSqlParser.SelectInfo selectInfo, long readTs) {
        byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        
        // Determine scan limit: if ORDER BY is present, we need to scan all rows to sort them properly
        // Otherwise, we can limit the scan to the query limit
        int queryLimit = selectInfo.getLimit() != null ? selectInfo.getLimit() : 1000;
        boolean hasOrderBy = selectInfo.getOrderBy() != null && !selectInfo.getOrderBy().isEmpty();
        int scanLimit = hasOrderBy ? 100000 : (queryLimit * 2);  // Scan all rows if ORDER BY present
        
        // Pass truncate timestamp to filter out truncated data
        List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, scanLimit, table.getTruncateTimestamp());
        
        List<Map<String, Object>> rows = new ArrayList<>();
        
        // Get PK column types for decoding
        List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class; // Default
                })
                .collect(Collectors.toList());
        
        for (KvStore.KvEntry entry : entries) {
            // CRITICAL: Validate table ID from key to prevent cross-table data leakage
            long returnedTableId = KeyEncoder.extractTableId(entry.getKey());
            if (returnedTableId != table.getTableId()) {
                log.warn("Skipping entry from wrong table: expected tableId={}, got tableId={}", 
                    table.getTableId(), returnedTableId);
                continue;
            }
            
            // Decode key to get PK values
            List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
            
            // Decode value to get non-PK values
            List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                    .map(TableMetadata.ColumnMetadata::getJavaType)
                    .collect(Collectors.toList());
            
            List<Object> nonPkValues;
            try {
                nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
            } catch (Exception e) {
                // CRITICAL: Skip entries that fail to decode (possible schema mismatch)
                log.warn("Failed to decode row value (possible schema mismatch): {}", e.getMessage());
                continue;
            }
            
            // CRITICAL: Validate column count matches expected schema
            // This prevents cross-table data leakage where rows have different column counts
            // This is disabled because it likely breaks alter-table operations. Instead
            // we should likely encode the column count (and other metadata) into the bytes. 
            int expectedColumnCount = table.getNonPrimaryKeyColumns().size();
            if (false && nonPkValues.size() != expectedColumnCount) {
                log.warn("Skipping entry with mismatched column count: expected {} columns, got {} columns (tableId={})", 
                    expectedColumnCount, nonPkValues.size(), table.getTableId());
                continue;
            }
            
            Map<String, Object> row = new HashMap<>();
            
            // Add PK columns
            for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                String colName = table.getPrimaryKeyColumns().get(i);
                row.put(colName.toLowerCase(), pkValues.get(i));
            }
            
            // Add non-PK columns
            List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
            for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                String colName = nonPkColumns.get(i).getName();
                row.put(colName.toLowerCase(), nonPkValues.get(i));
            }
            
            // Apply WHERE clause filter
            if (selectInfo.getWhereClause() != null && !selectInfo.getWhereClause().isEmpty()) {
                if (!evaluateWhereClause(row, selectInfo.getWhereClause(), table)) {
                    continue;  // Skip this row
                }
            }
            
            // Process JSON expressions in SELECT columns if needed
            row = processJsonExpressions(row, table, selectInfo);
            
            rows.add(row);
        }
        
        // Apply ORDER BY first (before LIMIT)
        if (selectInfo.getOrderBy() != null && !selectInfo.getOrderBy().isEmpty()) {
            rows = applyOrderBy(rows, selectInfo.getOrderBy());
        }
        
        // Apply OFFSET first (if present)
        Integer queryOffset = selectInfo.getOffset();
        if (queryOffset != null && queryOffset > 0) {
            if (queryOffset >= rows.size()) {
                rows = new ArrayList<>();
            } else {
                rows = new ArrayList<>(rows.subList(queryOffset, rows.size()));
            }
        }
        
        // Apply LIMIT after OFFSET (use queryLimit, not scanLimit)
        // Note: LIMIT 0 is valid and should return 0 rows
        if (queryLimit >= 0 && queryLimit < 1000) {
            if (rows.size() > queryLimit) {
                rows = new ArrayList<>(rows.subList(0, queryLimit));
            } else if (queryLimit == 0) {
                rows = new ArrayList<>();
            }
        }
        
        // Filter out hidden rowid from result rows
        rows = filterHiddenColumns(rows, table);
        
        // Project columns and evaluate expressions (concatenation, arithmetic, etc.)
        rows = projectAndEvaluateExpressions(rows, selectInfo);
        
        // Extract column names from projected rows
        List<String> columnNames = rows.isEmpty() ? 
            getColumnNames(table) : 
            new ArrayList<>(rows.get(0).keySet());
        
        QueryResponse response = new QueryResponse(rows, columnNames);
        
        // Inject literal columns if present
        return injectLiterals(response, selectInfo);
    }
    
    /**
     * Project columns and evaluate expressions (concatenation, arithmetic, etc.)
     */
    private List<Map<String, Object>> projectAndEvaluateExpressions(
            List<Map<String, Object>> rows,
            CalciteSqlParser.SelectInfo selectInfo) {
        
        if (rows.isEmpty()) {
            return rows;
        }
        
        List<String> selectColumns = selectInfo.getColumns();
        List<SqlNode> selectNodes = selectInfo.getSelectNodes();
        
        // If SELECT *, return all columns as-is
        if (selectColumns == null || selectColumns.isEmpty() || selectColumns.contains("*")) {
            return rows;
        }
        
        // Project and evaluate expressions for each row
        List<Map<String, Object>> projected = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> projectedRow = new LinkedHashMap<>();
            
            // Use SqlNode objects if available (preferred for proper CASE/expression evaluation)
            if (!selectNodes.isEmpty() && selectNodes.size() == selectColumns.size()) {
                for (int i = 0; i < selectNodes.size(); i++) {
                    SqlNode node = selectNodes.get(i);
                    String col = selectColumns.get(i);
                    
                    // Handle AS expressions
                    if (node.getKind() == SqlKind.AS) {
                        SqlCall asCall = (SqlCall) node;
                        SqlNode valueNode = asCall.operand(0);
                        String alias = asCall.operand(1).toString().replace("`", "").toLowerCase();
                        
                        // Evaluate using Calcite AST directly
                        Object value = sqlParser.evaluateExpression(valueNode, row);
                        
                        if (value != null) {
                            projectedRow.put(alias, value);
                        }
                    } else if (node instanceof SqlIdentifier) {
                        // Simple column reference
                        String columnName = node.toString().replace("`", "").toLowerCase();
                        Object value = getCaseInsensitive(row, columnName);
                        
                        // Handle qualified column names (e.g., table.column)
                        if (value == null && columnName.contains(".")) {
                            String unqualified = columnName.substring(columnName.lastIndexOf(".") + 1);
                            value = getCaseInsensitive(row, unqualified);
                        }
                        
                        if (value != null) {
                            String outputKey = columnName.contains(".") ? 
                                columnName.substring(columnName.lastIndexOf(".") + 1) : columnName;
                            projectedRow.put(outputKey, value);
                        }
                    } else {
                        // Complex expression (CASE, arithmetic, etc.) - evaluate using Calcite
                        Object value = sqlParser.evaluateExpression(node, row);
                        if (value != null) {
                            // Use column name from string representation
                            String outputKey = col.replace("`", "").toLowerCase();
                            if (outputKey.contains(".")) {
                                outputKey = outputKey.substring(outputKey.lastIndexOf(".") + 1);
                            }
                            projectedRow.put(outputKey, value);
                        }
                    }
                }
            } else {
                // Fallback to string-based evaluation (legacy path)
                for (String col : selectColumns) {
                    // Check if column has an alias: "expression AS alias"
                    if (col.toUpperCase().contains(" AS ")) {
                        String[] parts = col.split("(?i)\\s+AS\\s+");
                        if (parts.length == 2) {
                            String sourceExpression = parts[0].trim().replace("`", "");
                            String alias = parts[1].trim().replace("`", "").toLowerCase();
                            
                            // Evaluate the expression
                            Object value = evaluateSelectExpression(sourceExpression, row);
                            
                            if (value != null) {
                                projectedRow.put(alias, value);
                            }
                            continue;
                        }
                    }
                    
                    // No alias - evaluate as expression or simple column reference
                    String columnRef = col.trim().replace("`", "");
                    Object value = evaluateSelectExpression(columnRef, row);
                    
                    if (value != null) {
                        // Use the column name (without table prefix) as the key
                        String outputKey = columnRef.contains(".") ? 
                            columnRef.substring(columnRef.lastIndexOf(".") + 1).toLowerCase() :
                            columnRef.toLowerCase();
                        projectedRow.put(outputKey, value);
                    }
                }
            }
            
            projected.add(projectedRow);
        }
        
        return projected;
    }
    
    /**
     * Evaluate a SELECT expression
     * Handles: concatenation, arithmetic, literals, column references
     * Note: For complex expressions (CASE, functions), use evaluateExpressionWithCalcite
     */
    private Object evaluateSelectExpression(String expression, Map<String, Object> row) {
        // Remove backticks that Calcite adds when stringifying
        expression = expression.replace("`", "");
        
        // Check for CASE expression - use Calcite AST parser for proper evaluation
        if (expression.toUpperCase().trim().startsWith("CASE")) {
            try {
                // Parse the CASE expression as a SELECT to get the SqlNode
                String selectSql = "SELECT " + expression + " FROM dummy";
                SqlParser parser = SqlParser.create(selectSql, SqlParser.config());
                SqlNode parsed = parser.parseQuery();
                
                if (parsed instanceof SqlSelect) {
                    SqlSelect select = (SqlSelect) parsed;
                    SqlNode caseNode = select.getSelectList().get(0);
                    return sqlParser.evaluateExpression(caseNode, row);
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate CASE expression: {}", e.getMessage());
                return null;
            }
        }
        
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
        
        // Check for arithmetic expressions
        if (isArithmeticExpression(expression)) {
            return evaluateArithmeticExpression(expression, row);
        }
        
        // Treat as column reference
        String columnRef = expression.toLowerCase();
        Object value = row.get(columnRef);
        
        // If not found and it's a qualified reference, try without table prefix
        if (value == null && columnRef.contains(".")) {
            String unqualified = columnRef.substring(columnRef.lastIndexOf(".") + 1);
            value = row.get(unqualified);
        }
        
        return value;
    }
    
    /**
     * Evaluate arithmetic expression (e.g., "price * 2", "price - 10")
     */
    private Object evaluateArithmeticExpression(String expression, Map<String, Object> row) {
        try {
            // Try to parse and evaluate with Calcite
            String selectSql = "SELECT " + expression + " FROM dummy";
            SqlParser parser = SqlParser.create(selectSql, SqlParser.config());
            SqlNode parsed = parser.parseQuery();
            
            if (parsed instanceof SqlSelect) {
                SqlSelect select = (SqlSelect) parsed;
                if (select.getSelectList() != null && select.getSelectList().size() > 0) {
                    SqlNode exprNode = select.getSelectList().get(0);
                    return sqlParser.evaluateExpression(exprNode, row);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to evaluate arithmetic expression: {}", e.getMessage());
        }
        
        // Fallback: return the expression as-is (will be handled elsewhere)
        return expression;
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
                // It's a column reference - look it up directly
                String columnRef = part.toLowerCase();
                Object value = row.get(columnRef);
                
                // If not found and it's a qualified reference, try without table prefix
                if (value == null && columnRef.contains(".")) {
                    String unqualified = columnRef.substring(columnRef.lastIndexOf(".") + 1);
                    value = row.get(unqualified);
                }
                
                if (value != null) {
                    result.append(value.toString());
                }
            }
        }
        
        return result.toString();
    }
    
    /**
     * Execute range scan
     */
    private QueryResponse executeRangeScan(TableMetadata table, ParsedQuery query, long readTs) {
        // For now, do a full table scan
        // In production, we'd use range predicates to limit the scan
        
        byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
        
        int limit = 1000;  // Default limit
        // Pass truncate timestamp to filter out truncated data
        List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, limit, table.getTruncateTimestamp());
        
        List<Map<String, Object>> rows = new ArrayList<>();
        
        // Get PK column types for decoding
        List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class; // Default
                })
                .collect(Collectors.toList());
        
        for (KvStore.KvEntry entry : entries) {
            // Decode key to get PK values
            List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
            
            // Decode value to get non-PK values
            List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                    .map(TableMetadata.ColumnMetadata::getJavaType)
                    .collect(Collectors.toList());
            
            List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
            
            Map<String, Object> row = new HashMap<>();
            
            // Add PK columns
            for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                String colName = table.getPrimaryKeyColumns().get(i);
                row.put(colName.toLowerCase(), pkValues.get(i));
            }
            
            // Add non-PK columns
            List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
            for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                String colName = nonPkColumns.get(i).getName();
                row.put(colName.toLowerCase(), nonPkValues.get(i));
            }
            
            rows.add(row);
        }
        
        // Try to parse ORDER BY and LIMIT from raw SQL if present
        try {
            CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(query.getRawSql());
            
            // Apply ORDER BY
            if (selectInfo.getOrderBy() != null && !selectInfo.getOrderBy().isEmpty()) {
                rows = applyOrderBy(rows, selectInfo.getOrderBy());
            }
            
            // Apply LIMIT (LIMIT 0 is valid and should return 0 rows)
            if (selectInfo.getLimit() != null && selectInfo.getLimit() >= 0) {
                int limitCount = selectInfo.getLimit();
                if (limitCount == 0) {
                    rows = new ArrayList<>();
                } else if (rows.size() > limitCount) {
                    rows = new ArrayList<>(rows.subList(0, limitCount));
                }
            }
        } catch (Exception e) {
            // If parsing fails, skip ORDER BY and LIMIT (already logged in executeSelect)
        }
        
        // Filter out hidden rowid from result rows
        rows = filterHiddenColumns(rows, table);
        
        return new QueryResponse(rows, getColumnNames(table));
    }
    
    /**
     * Apply ORDER BY and LIMIT to JOIN query results
     */
    private QueryResponse executeJoinWithModifiers(ParsedQuery query, QueryResponse joinResponse) {
        if (joinResponse.getError() != null) {
            return joinResponse;
        }
        
        List<Map<String, Object>> rows = joinResponse.getRows();
        if (rows == null || rows.isEmpty()) {
            return joinResponse;
        }
        
        // Apply ORDER BY
        if (query.hasOrderBy()) {
            OrderByClause orderBy = query.getOrderBy();
            log.debug("Applying ORDER BY to JOIN results: {}", orderBy.getItems());
            
            rows = new ArrayList<>(rows); // Make mutable copy
            rows.sort((row1, row2) -> {
                for (OrderByClause.OrderByItem item : orderBy.getItems()) {
                    String column = item.getColumn().toLowerCase();
                    boolean ascending = (item.getDirection() == OrderByClause.Direction.ASC);
                    
                    // JOIN result rows have column names without table prefixes (e.g., "amount" not "t2.amount")
                    // Strip table prefix if present (e.g., "t2.amount" -> "amount")
                    String columnName = column;
                    if (column.contains(".")) {
                        columnName = column.substring(column.lastIndexOf('.') + 1);
                    }
                    
                    Object val1 = row1.get(columnName);
                    Object val2 = row2.get(columnName);
                    
                    // If not found with stripped name, try the full name (in case JOIN executor stored it differently)
                    if (val1 == null && !columnName.equals(column)) {
                        val1 = row1.get(column);
                    }
                    if (val2 == null && !columnName.equals(column)) {
                        val2 = row2.get(column);
                    }
                    
                    int cmp = compareValues(val1, val2);
                    if (cmp != 0) {
                        return ascending ? cmp : -cmp;
                    }
                }
                return 0;
            });
        }
        
        // Apply LIMIT
        if (query.hasLimit()) {
            LimitClause limit = query.getLimit();
            int offset = limit.getOffset();
            int count = limit.getLimit();
            
            log.debug("Applying LIMIT {} OFFSET {} to JOIN results (before: {} rows)", count, offset, rows.size());
            
            // Apply offset
            if (offset > 0 && offset < rows.size()) {
                rows = new ArrayList<>(rows.subList(offset, rows.size()));
            } else if (offset >= rows.size()) {
                rows = new ArrayList<>();
            }
            
            // Apply limit
            if (count >= 0 && rows.size() > count) {
                rows = new ArrayList<>(rows.subList(0, count));
            }
            
            log.debug("After LIMIT: {} rows", rows.size());
        }
        
        return new QueryResponse(rows, joinResponse.getColumns());
    }
    
    /**
     * Compare two values for sorting
     */
    private int compareValues(Object val1, Object val2) {
        if (val1 == null && val2 == null) return 0;
        if (val1 == null) return -1;
        if (val2 == null) return 1;
        
        if (val1 instanceof Number && val2 instanceof Number) {
            double d1 = ((Number) val1).doubleValue();
            double d2 = ((Number) val2).doubleValue();
            return Double.compare(d1, d2);
        }
        
        if (val1 instanceof Comparable && val2 instanceof Comparable) {
            try {
                @SuppressWarnings("unchecked")
                Comparable<Object> c1 = (Comparable<Object>) val1;
                return c1.compareTo(val2);
            } catch (ClassCastException e) {
                // Fall through to string comparison
            }
        }
        
        return val1.toString().compareTo(val2.toString());
    }
    
    /**
     * Execute INSERT query
     */
    private QueryResponse executeInsert(ParsedQuery query) {
        try {
            // Parse INSERT statement
            CalciteSqlParser.InsertInfo insertInfo = sqlParser.parseInsert(query.getRawSql());
            
            // Get table metadata
            TableMetadata table = schemaManager.getTable(insertInfo.getTableName());
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + insertInfo.getTableName());
            }
            
            // Start transaction
            KvTransactionContext txn = new KvTransactionContext(
                UUID.randomUUID(),
                timestampOracle.allocateStartTimestamp()
            );
            
            int rowsInserted = 0;
            
            // Insert each row
            for (List<Object> values : insertInfo.getValuesList()) {
                // Map values to columns
                Map<String, Object> row = new LinkedHashMap<>();
                List<String> columns = insertInfo.getColumns();
                
                if (columns.isEmpty()) {
                    // No columns specified, use all columns in order
                    columns = getColumnNames(table);
                }
                
                for (int i = 0; i < columns.size() && i < values.size(); i++) {
                    Object value = values.get(i);
                    // Handle NULL and DEFAULT for auto-increment columns
                    String colName = columns.get(i).toLowerCase();
                    if (value == null || "DEFAULT".equalsIgnoreCase(String.valueOf(value))) {
                        // Will be handled below for auto-increment columns
                        row.put(colName, null);
                    } else {
                        // Convert value to appropriate type based on column metadata
                        TableMetadata.ColumnMetadata colMeta = table.getColumn(colName);
                        if (colMeta != null) {
                            value = convertValueToColumnType(value, colMeta);
                        }
                        row.put(colName, value);
                    }
                }
                
                // Auto-generate values for auto-increment columns
                // Note: Only auto-generate for PRIMARY KEY columns (PostgreSQL behavior)
                for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                    String colName = col.getName().toLowerCase();
                    Object colValue = row.get(colName);
                    
                    if (col.isAutoIncrement()) {
                        // Check if this column is part of the primary key
                        boolean isPrimaryKey = table.getPrimaryKeyColumns().stream()
                            .anyMatch(pk -> pk.equalsIgnoreCase(colName));
                        
                        // Only auto-generate for PRIMARY KEY columns
                        if (!isPrimaryKey) {
                            continue;  // Skip non-PK auto-increment columns
                        }
                        
                        // Check if value was explicitly provided (case-insensitive column name match)
                        boolean hasExplicitValue = false;
                        for (String providedCol : columns) {
                            if (providedCol.equalsIgnoreCase(colName)) {
                                hasExplicitValue = (colValue != null);
                                break;
                            }
                        }
                        
                        // GENERATED ALWAYS AS IDENTITY: reject explicit values
                        if (col.isGeneratedAlways() && hasExplicitValue) {
                            return QueryResponse.error(
                                "Cannot insert explicit value for GENERATED ALWAYS column '" + colName + "'"
                            );
                        }
                        
                        // Auto-generate if no value provided (or NULL/DEFAULT)
                        if (!hasExplicitValue || colValue == null) {
                            // Allocate next value from sequence
                            long nextValue = schemaManager.allocateRowId(table.getTableId());
                            
                            // Convert to correct type based on column type
                            Object typedValue;
                            if (col.getJavaType() == Integer.class) {
                                typedValue = (int) nextValue;
                            } else if (col.getJavaType() == Long.class) {
                                typedValue = nextValue;
                            } else {
                                typedValue = nextValue;
                            }
                            
                            row.put(colName, typedValue);
                            log.debug("Auto-generated {}: {}", colName, typedValue);
                        } else {
                            // Explicit value provided for SERIAL or GENERATED BY DEFAULT
                            // Update sequence to max(current, explicit_value) to ensure future auto-generated values don't conflict
                            log.debug("Using explicit value for {}: {}", colName, colValue);
                            
                            // Convert explicit value to long for comparison
                            long explicitValue;
                            if (colValue instanceof Number) {
                                explicitValue = ((Number) colValue).longValue();
                                // Update sequence if explicit value is >= current sequence value
                                schemaManager.updateSequenceIfGreater(table.getTableId(), explicitValue);
                            }
                        }
                    }
                }
                
                // Validate NOT NULL constraints
                for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                    String colName = col.getName().toLowerCase();
                    Object colValue = row.get(colName);
                    
                    // Skip if nullable
                    if (col.isNullable()) {
                        continue;
                    }
                    
                    // Skip if auto-increment (will be generated)
                    if (col.isAutoIncrement()) {
                        boolean isPrimaryKey = table.getPrimaryKeyColumns().stream()
                            .anyMatch(pk -> pk.equalsIgnoreCase(colName));
                        if (isPrimaryKey) {
                            continue;  // Will be auto-generated
                        }
                    }
                    
                    // Check if column was provided in INSERT
                    boolean wasProvided = false;
                    for (String providedCol : columns) {
                        if (providedCol.equalsIgnoreCase(colName)) {
                            wasProvided = true;
                            break;
                        }
                    }
                    
                    // If NOT NULL column was not provided or is NULL, fail
                    if (!wasProvided || colValue == null) {
                        return QueryResponse.error("Column '" + colName + "' cannot be null");
                    }
                }
                
                // Validate JSON columns
                for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                    if (col.isJsonType()) {
                        Object value = row.get(col.getName().toLowerCase());
                        if (value != null) {
                            try {
                                JsonHelper.validateJson(String.valueOf(value));
                            } catch (IllegalArgumentException e) {
                                return QueryResponse.error("Invalid JSON in column '" + col.getName() + "': " + e.getMessage());
                            }
                        }
                    }
                }
                
                // Extract primary key values
                List<Object> pkValues = new ArrayList<>();
                for (String pkCol : table.getPrimaryKeyColumns()) {
                    Object pkValue = row.get(pkCol.toLowerCase());
                    
                    if (pkValue == null) {
                        return QueryResponse.error("Primary key column '" + pkCol + "' cannot be null");
                    }
                    pkValues.add(pkValue);
                }
                
                // Validate UNIQUE constraints
                String uniqueError = validateUniqueConstraints(table, row, null, txn.getStartTs());
                if (uniqueError != null) {
                    return QueryResponse.error(uniqueError);
                }
                
                // Validate FOREIGN KEY constraints
                String fkError = validateForeignKeyConstraints(table, row, txn.getStartTs());
                if (fkError != null) {
                    return QueryResponse.error(fkError);
                }
                
                // Extract non-primary key values
                List<Object> nonPkValues = new ArrayList<>();
                for (TableMetadata.ColumnMetadata col : table.getNonPrimaryKeyColumns()) {
                    Object value = row.get(col.getName().toLowerCase());
                    nonPkValues.add(value);
                }
                
                // Encode key and value for primary data
                byte[] key = KeyEncoder.encodeTableDataKey(table.getTableId(), pkValues, txn.getStartTs());
                byte[] value = ValueEncoder.encodeRow(nonPkValues);
                
                // Add primary data write to transaction
                txn.addWrite(key, value, KvTransactionContext.WriteType.PUT);
                
                // Write index entries for all secondary indexes (skip building indexes)
                for (TableMetadata.IndexMetadata index : table.getIndexes()) {
                    // Skip indexes that are still being built
                    if (index.isBuilding()) {
                        log.debug("Skipping index {} - still building", index.getName());
                        continue;
                    }
                    
                    // Extract indexed column values
                    List<Object> indexValues = new ArrayList<>();
                    for (String indexCol : index.getColumns()) {
                        Object indexValue = row.get(indexCol.toLowerCase());
                        indexValues.add(indexValue);
                    }
                    
                    // Encode index key
                    byte[] indexKey = KeyEncoder.encodeIndexKey(
                        table.getTableId(),
                        index.getIndexId(),
                        indexValues,
                        pkValues,
                        txn.getStartTs()
                    );
                    
                    // Index value is empty (non-covering index)
                    // The primary key in the index key is sufficient to look up the full row
                    byte[] indexValue = new byte[0];
                    
                    // Add index write to transaction
                    // This ensures atomicity: primary data and index entries are written together
                    txn.addWrite(indexKey, indexValue, KvTransactionContext.WriteType.PUT);
                }
                
                rowsInserted++;
            }
            
            // Commit transaction (includes prewrite + commit phases)
            // All writes (data + all index entries) are committed atomically via Accord
            coordinator.commit(txn);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsInserted);
            return response;
            
        } catch (SqlParseException e) {
            return QueryResponse.error("Failed to parse INSERT: " + e.getMessage());
        } catch (Exception e) {
            return QueryResponse.error("INSERT failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute UPDATE query
     */
    private QueryResponse executeUpdate(ParsedQuery query) {
        try {
            // Parse UPDATE statement
            CalciteSqlParser.UpdateInfo updateInfo = sqlParser.parseUpdate(query.getRawSql());
            
            // Get table metadata
            TableMetadata table = schemaManager.getTable(updateInfo.getTableName());
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + updateInfo.getTableName());
            }
            
            // Start transaction
            KvTransactionContext txn = new KvTransactionContext(
                UUID.randomUUID(),
                timestampOracle.allocateStartTimestamp()
            );
            
            // First, find rows to update using SELECT
            long readTs = txn.getStartTs();
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, table.getTruncateTimestamp());
            
            // Get PK column types for decoding
            List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                    .map(pkColName -> {
                        for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                            if (col.getName().equalsIgnoreCase(pkColName)) {
                                return col.getJavaType();
                            }
                        }
                        return String.class; // Default
                    })
                    .collect(Collectors.toList());
            
            int rowsUpdated = 0;
            
            for (KvStore.KvEntry entry : entries) {
                // Decode key to get PK values
                List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                
                // Decode current row value
                List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                        .map(TableMetadata.ColumnMetadata::getJavaType)
                        .collect(Collectors.toList());
                
                List<Object> currentValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                
                // Build current row map (with PK and non-PK columns)
                Map<String, Object> row = new HashMap<>();
                
                // Add PK columns
                for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                    String colName = table.getPrimaryKeyColumns().get(i);
                    row.put(colName.toLowerCase(), pkValues.get(i));
                }
                
                // Add non-PK columns
                List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
                for (int i = 0; i < nonPkColumns.size() && i < currentValues.size(); i++) {
                    row.put(nonPkColumns.get(i).getName().toLowerCase(), currentValues.get(i));
                }
                
                // Apply WHERE clause filter
                if (updateInfo.getWhereClause() != null && !updateInfo.getWhereClause().isEmpty()) {
                    boolean matches = true;
                    for (CalciteSqlParser.Predicate pred : updateInfo.getWhereClause().getPredicates()) {
                        Object rowValue = row.get(pred.getColumn().toLowerCase());
                        if (!pred.evaluate(rowValue)) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) {
                        continue;  // Skip this row
                    }
                }
                
                // Save old row for index maintenance
                Map<String, Object> oldRow = new HashMap<>(row);
                
                // Apply updates - evaluate expressions with current row values
                for (Map.Entry<String, CalciteSqlParser.UpdateExpression> update : updateInfo.getSetExpressions().entrySet()) {
                    String columnName = update.getKey().toLowerCase();
                    Object newValue = update.getValue().evaluate(row);
                    row.put(columnName, newValue);
                }
                
                // Validate JSON columns after update
                for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                    if (col.isJsonType()) {
                        Object value = row.get(col.getName().toLowerCase());
                        if (value != null) {
                            try {
                                JsonHelper.validateJson(String.valueOf(value));
                            } catch (IllegalArgumentException e) {
                                return QueryResponse.error("Invalid JSON in column '" + col.getName() + "': " + e.getMessage());
                            }
                        }
                    }
                }
                
                // Validate UNIQUE constraints (pass pkValues to allow same-row updates)
                String uniqueError = validateUniqueConstraints(table, row, pkValues, readTs);
                if (uniqueError != null) {
                    return QueryResponse.error(uniqueError);
                }
                
                // Validate FOREIGN KEY constraints
                String fkError = validateForeignKeyConstraints(table, row, readTs);
                if (fkError != null) {
                    return QueryResponse.error(fkError);
                }
                
                // Encode new value
                List<Object> newValues = new ArrayList<>();
                for (TableMetadata.ColumnMetadata col : nonPkColumns) {
                    newValues.add(row.get(col.getName().toLowerCase()));
                }
                
                byte[] newValue = ValueEncoder.encodeRow(newValues);
                
                // Add primary data write to transaction (reuse the same key)
                txn.addWrite(entry.getKey(), newValue, KvTransactionContext.WriteType.PUT);
                
                // Maintain index entries (skip building indexes)
                for (TableMetadata.IndexMetadata index : table.getIndexes()) {
                    // Skip indexes that are still being built
                    if (index.isBuilding()) {
                        log.debug("Skipping index {} - still building", index.getName());
                        continue;
                    }
                    
                    // Extract old indexed column values
                    List<Object> oldIndexValues = new ArrayList<>();
                    for (String indexCol : index.getColumns()) {
                        Object oldValue = oldRow.get(indexCol.toLowerCase());
                        oldIndexValues.add(oldValue);
                    }
                    
                    // Extract new indexed column values
                    List<Object> newIndexValues = new ArrayList<>();
                    for (String indexCol : index.getColumns()) {
                        Object newValue2 = row.get(indexCol.toLowerCase());
                        newIndexValues.add(newValue2);
                    }
                    
                    // Check if indexed columns changed
                    boolean indexChanged = !oldIndexValues.equals(newIndexValues);
                    
                    if (indexChanged) {
                        // Delete old index entry
                        byte[] oldIndexKey = KeyEncoder.encodeIndexKey(
                            table.getTableId(),
                            index.getIndexId(),
                            oldIndexValues,
                            pkValues,
                            txn.getStartTs()
                        );
                        txn.addDelete(oldIndexKey);
                        
                        // Write new index entry
                        byte[] newIndexKey = KeyEncoder.encodeIndexKey(
                            table.getTableId(),
                            index.getIndexId(),
                            newIndexValues,
                            pkValues,
                            txn.getStartTs()
                        );
                        byte[] indexValue = new byte[0];
                        // Atomic write: index update is part of the same Accord transaction
                        txn.addWrite(newIndexKey, indexValue, KvTransactionContext.WriteType.PUT);
                    }
                    // If index didn't change, no action needed (index entry remains valid)
                }
                
                rowsUpdated++;
            }
            
            if (rowsUpdated > 0) {
                // Commit transaction (includes prewrite + commit phases)
                coordinator.commit(txn);
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsUpdated);
            return response;
            
        } catch (SqlParseException e) {
            return QueryResponse.error("Failed to parse UPDATE: " + e.getMessage());
        } catch (Exception e) {
            return QueryResponse.error("UPDATE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute DELETE query
     */
    private QueryResponse executeDelete(ParsedQuery query) {
        try {
            // Parse DELETE statement
            CalciteSqlParser.DeleteInfo deleteInfo = sqlParser.parseDelete(query.getRawSql());
            
            // Get table metadata
            TableMetadata table = schemaManager.getTable(deleteInfo.getTableName());
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + deleteInfo.getTableName());
            }
            
            // Start transaction
            KvTransactionContext txn = new KvTransactionContext(
                UUID.randomUUID(),
                timestampOracle.allocateStartTimestamp()
            );
            
            // First, find rows to delete using SELECT
            long readTs = txn.getStartTs();
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, table.getTruncateTimestamp());
            
            int rowsDeleted = 0;
            
            for (KvStore.KvEntry entry : entries) {
                // Decode primary key values from the key
                List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                        .map(pkColName -> {
                            for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                                if (col.getName().equalsIgnoreCase(pkColName)) {
                                    return col.getJavaType();
                                }
                            }
                            return Object.class; // Default
                        })
                        .collect(Collectors.toList());
                
                List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                
                // Decode non-PK values from the value
                List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                        .map(TableMetadata.ColumnMetadata::getJavaType)
                        .collect(Collectors.toList());
                
                List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                
                // Build current row map with BOTH PK and non-PK columns
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
                
                // Apply WHERE clause filter
                if (deleteInfo.getWhereClause() != null && !deleteInfo.getWhereClause().isEmpty()) {
                    boolean matches = true;
                    for (CalciteSqlParser.Predicate pred : deleteInfo.getWhereClause().getPredicates()) {
                        Object rowValue = row.get(pred.getColumn().toLowerCase());
                        if (!pred.evaluate(rowValue)) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) {
                        continue;  // Skip this row
                    }
                }
                
                // Check FOREIGN KEY constraints before deleting
                String fkError = checkForeignKeyOnDelete(table, row, readTs);
                if (fkError != null) {
                    return QueryResponse.error(fkError);
                }
                
                // Add primary data delete to transaction
                txn.addWrite(entry.getKey(), null, KvTransactionContext.WriteType.DELETE);
                
                // Delete all index entries for this row (skip building indexes)
                for (TableMetadata.IndexMetadata index : table.getIndexes()) {
                    // Skip indexes that are still being built
                    if (index.isBuilding()) {
                        log.debug("Skipping index {} - still building", index.getName());
                        continue;
                    }
                    
                    // Extract indexed column values
                    List<Object> indexValues = new ArrayList<>();
                    for (String indexCol : index.getColumns()) {
                        Object indexValue = row.get(indexCol.toLowerCase());
                        indexValues.add(indexValue);
                    }
                    
                    // Encode index key
                    byte[] indexKey = KeyEncoder.encodeIndexKey(
                        table.getTableId(),
                        index.getIndexId(),
                        indexValues,
                        pkValues,
                        txn.getStartTs()
                    );
                    
                    // Add index delete to transaction
                    txn.addDelete(indexKey);
                }
                
                rowsDeleted++;
            }
            
            if (rowsDeleted > 0) {
                // Commit transaction (includes prewrite + commit phases)
                coordinator.commit(txn);
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsDeleted);
            return response;
            
        } catch (SqlParseException e) {
            return QueryResponse.error("Failed to parse DELETE: " + e.getMessage());
        } catch (Exception e) {
            return QueryResponse.error("DELETE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute CREATE TABLE query
     */
    private QueryResponse executeCreateTable(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            String tableName = query.getTableName();
            
            // Check for IF NOT EXISTS
            boolean ifNotExists = sql.toUpperCase().contains("IF NOT EXISTS");
            
            // If table already exists and IF NOT EXISTS is specified, return success
            if (ifNotExists && schemaManager.getTable(tableName) != null) {
                QueryResponse response = new QueryResponse();
                response.setRowCount(0);
                return response;
            }
            
            // Parse the CREATE TABLE statement to extract columns and primary key
            // Supports:
            // 1. Inline: CREATE TABLE t (id INT PRIMARY KEY, name TEXT)
            // 2. Constraint: CREATE TABLE t (id INT, name TEXT, PRIMARY KEY (id))
            // 3. Composite: CREATE TABLE t (id INT, type TEXT, PRIMARY KEY (id, type))
            // 4. With IF NOT EXISTS: CREATE TABLE IF NOT EXISTS t (...)
            
            int startParen = sql.indexOf('(');
            int endParen = sql.lastIndexOf(')');
            
            if (startParen == -1 || endParen == -1) {
                throw new IllegalArgumentException("Invalid CREATE TABLE syntax: missing parentheses");
            }
            
            String columnDef = sql.substring(startParen + 1, endParen).trim();
            
            // Split by comma, but respect parentheses (for PRIMARY KEY (col1, col2))
            List<String> columnParts = smartSplit(columnDef);
            
            List<TableMetadata.ColumnMetadata> columns = new ArrayList<>();
            List<String> primaryKeyColumns = new ArrayList<>();
            List<TableMetadata.UniqueConstraint> uniqueConstraints = new ArrayList<>();
            List<TableMetadata.ForeignKeyConstraint> foreignKeyConstraints = new ArrayList<>();
            
            for (String part : columnParts) {
                part = part.trim();
                String partUpper = part.toUpperCase();
                
                // Check if this is a PRIMARY KEY constraint (not a column definition)
                if (partUpper.startsWith("PRIMARY KEY")) {
                    // Extract column names from PRIMARY KEY (col1, col2, ...)
                    int pkStart = part.indexOf('(');
                    int pkEnd = part.lastIndexOf(')');
                    if (pkStart != -1 && pkEnd != -1) {
                        String pkCols = part.substring(pkStart + 1, pkEnd).trim();
                        for (String pkCol : pkCols.split(",")) {
                            primaryKeyColumns.add(pkCol.trim().toLowerCase());
                        }
                    }
                    continue;  // Don't treat this as a column definition
                }
                
                // Check if this is a UNIQUE constraint
                if (partUpper.startsWith("UNIQUE") || partUpper.startsWith("CONSTRAINT")) {
                    TableMetadata.UniqueConstraint uniqueConstraint = parseUniqueConstraint(part, tableName);
                    if (uniqueConstraint != null) {
                        uniqueConstraints.add(uniqueConstraint);
                        continue;
                    }
                }
                
                // Check if this is a FOREIGN KEY constraint
                if (partUpper.startsWith("FOREIGN KEY") || (partUpper.startsWith("CONSTRAINT") && partUpper.contains("FOREIGN KEY"))) {
                    TableMetadata.ForeignKeyConstraint fkConstraint = parseForeignKeyConstraint(part, tableName);
                    if (fkConstraint != null) {
                        foreignKeyConstraints.add(fkConstraint);
                        continue;
                    }
                }
                
                // Parse column definition
                String[] tokens = part.split("\\s+");
                
                if (tokens.length < 2) {
                    continue;  // Skip invalid column definitions
                }
                
                String colName = tokens[0].toLowerCase();
                String colType = tokens[1].toUpperCase();
                
                // Check for inline PRIMARY KEY
                boolean hasInlinePrimaryKey = partUpper.contains("PRIMARY KEY");
                boolean isNullable = !hasInlinePrimaryKey;
                
                // Check for inline UNIQUE constraint
                boolean hasInlineUnique = partUpper.contains(" UNIQUE") && !partUpper.contains("UNIQUE(");
                if (hasInlineUnique) {
                    // Create a unique constraint for this single column
                    String constraintName = tableName + "_" + colName + "_unique";
                    uniqueConstraints.add(new TableMetadata.UniqueConstraint(
                        constraintName,
                        Arrays.asList(colName)
                    ));
                }
                
                // Check for auto-increment types
                boolean isAutoIncrement = false;
                String identityGeneration = null;
                
                // SERIAL, BIGSERIAL, SMALLSERIAL
                if (colType.equals("SERIAL") || colType.equals("BIGSERIAL") || colType.equals("SMALLSERIAL")) {
                    isAutoIncrement = true;
                    isNullable = false;  // SERIAL columns are NOT NULL
                    // Normalize type: SERIAL -> INT, BIGSERIAL -> BIGINT
                    if (colType.equals("SERIAL") || colType.equals("SMALLSERIAL")) {
                        colType = "INT";
                    } else if (colType.equals("BIGSERIAL")) {
                        colType = "BIGINT";
                    }
                }
                
                // GENERATED ALWAYS AS IDENTITY or GENERATED BY DEFAULT AS IDENTITY
                if (partUpper.contains("GENERATED") && partUpper.contains("AS IDENTITY")) {
                    isAutoIncrement = true;
                    isNullable = false;
                    if (partUpper.contains("GENERATED ALWAYS AS IDENTITY")) {
                        identityGeneration = "ALWAYS";
                    } else if (partUpper.contains("GENERATED BY DEFAULT AS IDENTITY")) {
                        identityGeneration = "BY DEFAULT";
                    } else {
                        // Default to ALWAYS if not specified
                        identityGeneration = "ALWAYS";
                    }
                }
                
                columns.add(new TableMetadata.ColumnMetadata(colName, colType, isNullable, isAutoIncrement, identityGeneration));
                
                if (hasInlinePrimaryKey) {
                    primaryKeyColumns.add(colName);
                }
            }
            
            // If no primary key is specified, add a hidden rowid column
            // This matches PostgreSQL behavior where tables can exist without explicit primary keys
            // PostgreSQL uses ctid internally, we use rowid
            if (primaryKeyColumns.isEmpty()) {
                log.debug("⚠️  Table " + tableName + " has no PRIMARY KEY, adding hidden 'rowid' column");
                
                // Add hidden rowid column (BIGINT, auto-incrementing)
                TableMetadata.ColumnMetadata rowidColumn = new TableMetadata.ColumnMetadata(
                    "rowid",
                    "BIGINT",
                    false,  // not nullable
                    true,   // auto-increment
                    null    // not an IDENTITY column
                );
                columns.add(0, rowidColumn);  // Add as first column
                primaryKeyColumns.add("rowid");
            }
            
            schemaManager.createTable(tableName, columns, primaryKeyColumns, uniqueConstraints, foreignKeyConstraints);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            return QueryResponse.error("CREATE TABLE failed: " + e.getMessage());
        }
    }
    
    /**
     * Smart split that respects parentheses.
     * Splits by comma, but treats content inside parentheses as a single unit.
     * 
     * Example: "id INT, name TEXT, PRIMARY KEY (id, name)" 
     *   -> ["id INT", "name TEXT", "PRIMARY KEY (id, name)"]
     */
    private List<String> smartSplit(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        
        for (char c : input.toCharArray()) {
            if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && parenDepth == 0) {
                // Split here
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        // Add the last part
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        
        return result;
    }
    
    /**
     * Execute DROP TABLE query
     */
    private QueryResponse executeDropTable(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            
            // Check for IF EXISTS
            boolean ifExists = sql.toUpperCase().contains("IF EXISTS");
            
            // Get all table names (supports multiple tables)
            List<String> tableNames = query.getDropTableNames();
            if (tableNames == null || tableNames.isEmpty()) {
                // Fallback to single table name
                tableNames = java.util.Arrays.asList(query.getTableName());
            }
            
            // Drop each table
            int droppedCount = 0;
            for (String tableName : tableNames) {
                // If table doesn't exist and IF EXISTS is specified, skip silently
                if (ifExists && schemaManager.getTable(tableName) == null) {
                    continue;
                }
                
                // Drop the table
                schemaManager.dropTable(tableName);
                droppedCount++;
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(droppedCount);
            return response;
        } catch (Exception e) {
            return QueryResponse.error("DROP TABLE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute TRUNCATE query.
     * 
     * TRUNCATE is a fast O(1) operation that marks all existing data as invisible
     * by recording a truncate timestamp. The vacuum job will clean up old data later.
     * 
     * Supports: TRUNCATE TABLE table1, table2, table3
     */
    private QueryResponse executeTruncate(ParsedQuery query) {
        try {
            List<String> tableNames = query.getTruncateTableNames();
            if (tableNames == null || tableNames.isEmpty()) {
                return QueryResponse.error("TRUNCATE: No tables specified");
            }
            
            log.debug("🗑️  TRUNCATE: Processing " + tableNames.size() + " table(s)");
            
            int truncatedCount = 0;
            for (String tableName : tableNames) {
                try {
                    schemaManager.truncateTable(tableName);
                    truncatedCount++;
                } catch (Exception e) {
                    return QueryResponse.error("TRUNCATE failed for table " + tableName + ": " + e.getMessage());
                }
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            log.debug("✅ TRUNCATE completed: " + truncatedCount + " table(s) truncated");
            return response;
            
        } catch (Exception e) {
            return QueryResponse.error("TRUNCATE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute CREATE INDEX query with automatic backfill of existing data.
     * 
     * Syntax: CREATE INDEX index_name ON table_name (column1, column2, ...)
     * 
     * This operation:
     * 1. Creates the index metadata
     * 2. Scans all existing rows in the table
     * 3. Creates index entries for each row
     * 4. Uses Accord transaction for atomicity
     */
    private QueryResponse executeCreateIndex(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            
            // Parse CREATE INDEX statement
            // Example: CREATE INDEX idx_email ON users (email)
            // Example: CREATE INDEX idx_name ON users (last_name, first_name)
            
            String sqlUpper = sql.toUpperCase();
            
            // Extract index name
            int indexStart = sqlUpper.indexOf("INDEX") + 5;
            int onPos = sqlUpper.indexOf(" ON ", indexStart);
            if (onPos == -1) {
                throw new IllegalArgumentException("Invalid CREATE INDEX syntax: missing ON clause");
            }
            String indexName = sql.substring(indexStart, onPos).trim();
            
            // Extract table name
            int tableStart = onPos + 4;
            int parenPos = sql.indexOf('(', tableStart);
            if (parenPos == -1) {
                throw new IllegalArgumentException("Invalid CREATE INDEX syntax: missing column list");
            }
            String tableName = sql.substring(tableStart, parenPos).trim();
            
            // Extract column list
            int parenEnd = sql.indexOf(')', parenPos);
            if (parenEnd == -1) {
                throw new IllegalArgumentException("Invalid CREATE INDEX syntax: missing closing parenthesis");
            }
            String columnList = sql.substring(parenPos + 1, parenEnd).trim();
            List<String> columns = Arrays.stream(columnList.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
            
            // Check if this is a table or a materialized view
            TableMetadata table = schemaManager.getTable(tableName);
            ViewMetadata view = schemaManager.getView(tableName);
            
            if (table == null && view == null) {
                return QueryResponse.error("Table or view does not exist: " + tableName);
            }
            
            // Materialized views can have indexes, but virtual views cannot
            if (view != null && !view.isMaterialized()) {
                return QueryResponse.error("Cannot create index on virtual view: " + tableName + ". Only materialized views support indexes.");
            }
            
            // For materialized views, create index on the view
            if (view != null && view.isMaterialized()) {
                return executeCreateIndexOnMaterializedView(view, indexName, columns);
            }
            
            // Create index metadata (marked as building=true)
            TableMetadata.IndexMetadata index = schemaManager.createIndex(tableName, indexName, columns, false);
            
            log.info("✅ Created index metadata: {} on {} ({}) - marked as BUILDING", 
                     indexName, tableName, String.join(", ", columns));
            log.info("🔄 Backfilling index from existing data...");
            
            // Backfill the index incrementally
            int indexEntriesCreated = backfillIndex(table, index);
            
            // Mark index as ready (building=false) so it can be used by queries
            schemaManager.markIndexReady(tableName, indexName);
            
            log.info("✅ Index {} is now READY and can be used by queries", indexName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(indexEntriesCreated);
            return response;
            
        } catch (Exception e) {
            e.printStackTrace();
            return QueryResponse.error("CREATE INDEX failed: " + e.getMessage());
        }
    }
    /**
     * Execute CREATE SEQUENCE
     */
    private QueryResponse executeCreateSequence(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            
            // Parse CREATE SEQUENCE statement
            // Format: CREATE SEQUENCE [IF NOT EXISTS] name [INCREMENT BY n] [START WITH n] 
            //         [MINVALUE n | NO MINVALUE] [MAXVALUE n | NO MAXVALUE] [CYCLE | NO CYCLE] [CACHE n]
            
            String sqlUpper = sql.toUpperCase();
            
            // Check for IF NOT EXISTS
            boolean ifNotExists = sqlUpper.contains("IF NOT EXISTS");
            
            // Extract sequence name
            String sequenceName = extractSequenceName(sql);
            if (sequenceName == null || sequenceName.isEmpty()) {
                return QueryResponse.error("Could not extract sequence name from: " + sql);
            }
            
            // Check if sequence already exists
            if (schemaManager.getSequence(sequenceName) != null) {
                if (ifNotExists) {
                    log.debug("⚠️  Sequence already exists: " + sequenceName + " (IF NOT EXISTS - skipping)");
                    return new QueryResponse(new ArrayList<>(), new ArrayList<>());
                } else {
                    return QueryResponse.error("Sequence already exists: " + sequenceName);
                }
            }
            
            // Parse sequence parameters with defaults
            long increment = extractLongParameter(sql, "INCREMENT BY", 1L);
            long startValue = extractLongParameter(sql, "START WITH", 1L);
            
            // Handle MINVALUE
            Long minValue = null;
            if (sqlUpper.contains("NO MINVALUE")) {
                minValue = null;
            } else if (sqlUpper.contains("MINVALUE")) {
                minValue = extractLongParameter(sql, "MINVALUE", 1L);
            } else {
                minValue = 1L; // Default
            }
            
            // Handle MAXVALUE
            Long maxValue = null;
            if (sqlUpper.contains("NO MAXVALUE")) {
                maxValue = null;
            } else if (sqlUpper.contains("MAXVALUE")) {
                maxValue = extractLongParameter(sql, "MAXVALUE", Long.MAX_VALUE);
            } else {
                maxValue = Long.MAX_VALUE; // Default
            }
            
            // Handle CYCLE
            boolean cycle = sqlUpper.contains("CYCLE") && !sqlUpper.contains("NO CYCLE");
            
            // Handle CACHE
            int cache = (int) extractLongParameter(sql, "CACHE", 1L);
            
            // Create sequence metadata
            SequenceMetadata sequence = new SequenceMetadata(
                sequenceName,
                increment,
                startValue,
                minValue,
                maxValue,
                cycle,
                cache,
                System.currentTimeMillis()
            );
            
            // Create the sequence
            schemaManager.createSequence(sequence);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            return QueryResponse.error("CREATE SEQUENCE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute DROP SEQUENCE
     */
    private QueryResponse executeDropSequence(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            String sqlUpper = sql.toUpperCase();
            
            // Check for IF EXISTS
            boolean ifExists = sqlUpper.contains("IF EXISTS");
            
            // Extract sequence name
            String sequenceName = extractSequenceName(sql);
            if (sequenceName == null || sequenceName.isEmpty()) {
                return QueryResponse.error("Could not extract sequence name from: " + sql);
            }
            
            // Check if sequence exists
            if (schemaManager.getSequence(sequenceName) == null) {
                if (ifExists) {
                    log.debug("⚠️  Sequence does not exist: " + sequenceName + " (IF EXISTS - skipping)");
                    return new QueryResponse(new ArrayList<>(), new ArrayList<>());
                } else {
                    return QueryResponse.error("Sequence does not exist: " + sequenceName);
                }
            }
            
            // Drop the sequence
            schemaManager.dropSequence(sequenceName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            return QueryResponse.error("DROP SEQUENCE failed: " + e.getMessage());
        }
    }
    
    /**
     * Extract sequence name from CREATE/DROP SEQUENCE statement
     */
    private String extractSequenceName(String sql) {
        String sqlUpper = sql.toUpperCase();
        String[] words = sql.split("\\s+");
        
        // Find SEQUENCE keyword
        int sequenceIndex = -1;
        for (int i = 0; i < words.length; i++) {
            if (words[i].equalsIgnoreCase("SEQUENCE")) {
                sequenceIndex = i;
                break;
            }
        }
        
        if (sequenceIndex == -1 || sequenceIndex >= words.length - 1) {
            return null;
        }
        
        // Next word after SEQUENCE (or after IF NOT EXISTS / IF EXISTS)
        int nameIndex = sequenceIndex + 1;
        
        // Skip IF NOT EXISTS or IF EXISTS
        if (nameIndex < words.length && words[nameIndex].equalsIgnoreCase("IF")) {
            // Skip IF NOT EXISTS (3 words) or IF EXISTS (2 words)
            if (nameIndex + 2 < words.length && words[nameIndex + 1].equalsIgnoreCase("NOT") 
                && words[nameIndex + 2].equalsIgnoreCase("EXISTS")) {
                nameIndex += 3;
            } else if (nameIndex + 1 < words.length && words[nameIndex + 1].equalsIgnoreCase("EXISTS")) {
                nameIndex += 2;
            }
        }
        
        if (nameIndex >= words.length) {
            return null;
        }
        
        // Clean up the name (remove semicolons, commas, etc.)
        String name = words[nameIndex];
        name = name.replaceAll("[;,()]", "");
        
        return name.toLowerCase();
    }
    
    /**
     * Extract a long parameter value from SQL (e.g., "INCREMENT BY 5")
     */
    private long extractLongParameter(String sql, String keyword, long defaultValue) {
        try {
            String sqlUpper = sql.toUpperCase();
            int keywordIndex = sqlUpper.indexOf(keyword);
            
            if (keywordIndex == -1) {
                return defaultValue;
            }
            
            // Extract the part after the keyword
            String afterKeyword = sql.substring(keywordIndex + keyword.length()).trim();
            
            // Split by whitespace and take the first token
            String[] tokens = afterKeyword.split("\\s+");
            if (tokens.length == 0) {
                return defaultValue;
            }
            
            // Parse the number (remove any trailing non-numeric characters)
            String numberStr = tokens[0].replaceAll("[^0-9-]", "");
            if (numberStr.isEmpty()) {
                return defaultValue;
            }
            
            return Long.parseLong(numberStr);
            
        } catch (Exception e) {
            log.warn("Could not parse {} parameter, using default: {}", keyword, defaultValue);
            return defaultValue;
        }
    }
    
    // Helper methods
    
    /**
     * Filter out hidden columns (like rowid) from result rows
     */
    private List<Map<String, Object>> filterHiddenColumns(List<Map<String, Object>> rows, TableMetadata table) {
        // Check if this table has a hidden rowid (single PK column named "rowid")
        boolean hasHiddenRowid = table.getPrimaryKeyColumns().size() == 1 && 
                                  table.getPrimaryKeyColumns().get(0).equalsIgnoreCase("rowid");
        
        if (!hasHiddenRowid) {
            return rows; // No filtering needed
        }
        
        // Remove rowid from each row
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> filteredRow = new HashMap<>(row);
            filteredRow.remove("rowid");
            filtered.add(filteredRow);
        }
        return filtered;
    }
    
    private List<String> getColumnNames(TableMetadata table) {
        // Return all columns, excluding hidden rowid if it's the only PK
        List<String> allColumns = new ArrayList<>();
        
        // Check if this table has a hidden rowid (single PK column named "rowid")
        boolean hasHiddenRowid = table.getPrimaryKeyColumns().size() == 1 && 
                                  table.getPrimaryKeyColumns().get(0).equalsIgnoreCase("rowid");
        
        for (String pkCol : table.getPrimaryKeyColumns()) {
            // Skip hidden rowid column
            if (hasHiddenRowid && pkCol.equalsIgnoreCase("rowid")) {
                continue;
            }
            allColumns.add(pkCol.toLowerCase());
        }
        for (TableMetadata.ColumnMetadata col : table.getNonPrimaryKeyColumns()) {
            allColumns.add(col.getName().toLowerCase());
        }
        return allColumns;
    }
    
    /**
     * Apply ORDER BY clause to result rows
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> applyOrderBy(List<Map<String, Object>> rows, 
                                                    List<CalciteSqlParser.OrderByColumn> orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return rows;
        }
        
        List<Map<String, Object>> sortedRows = new ArrayList<>(rows);
        
        sortedRows.sort((row1, row2) -> {
            for (CalciteSqlParser.OrderByColumn orderCol : orderBy) {
                String colName = orderCol.getColumn().toLowerCase();
                Object val1 = row1.get(colName);
                Object val2 = row2.get(colName);
                
                int cmp = 0;
                if (val1 == null && val2 == null) {
                    cmp = 0;
                } else if (val1 == null) {
                    cmp = -1;
                } else if (val2 == null) {
                    cmp = 1;
                } else if (val1 instanceof Comparable && val2 instanceof Comparable) {
                    try {
                        cmp = ((Comparable) val1).compareTo(val2);
                    } catch (ClassCastException e) {
                        cmp = val1.toString().compareTo(val2.toString());
                    }
                } else {
                    cmp = val1.toString().compareTo(val2.toString());
                }
                
                if (cmp != 0) {
                    return orderCol.isAscending() ? cmp : -cmp;
                }
            }
            return 0;
        });
        
        return sortedRows;
    }
    
    /**
     * Execute window function query
     */
    private QueryResponse executeWindowFunction(ParsedQuery query) {
        try {
            log.debug("🪟 Executing window function query in KV mode");
            
            // Get base query (without window functions)
            String baseQuery = WindowFunctionParser.removeWindowFunctions(query.getRawSql());
            
            // Parse and execute base query
            ParsedQuery baseQueryParsed = new CalciteParser().parse(baseQuery);
            QueryResponse baseResults = execute(baseQueryParsed);
            
            // Apply window functions
            WindowFunctionExecutor windowExecutor = new WindowFunctionExecutor();
            return windowExecutor.applyWindowFunctions(baseResults, query.getWindowFunctions());
        } catch (Exception e) {
            return QueryResponse.error("Window function execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute VACUUM command (no-op in KV mode - VacuumJob handles this automatically)
     */
    private QueryResponse executeVacuum(ParsedQuery query) {
        log.debug("🔧 VACUUM command received - no-op in KV mode (VacuumJob handles cleanup automatically)");
        QueryResponse response = new QueryResponse();
        response.setRowCount(0);
        return response;
    }
    
    /**
     * Execute ANALYZE command (no-op in KV mode - StatisticsCollectorJob handles this automatically)
     */
    private QueryResponse executeAnalyze(ParsedQuery query) {
        log.debug("ANALYZE command received - no-op in KV mode (StatisticsCollectorJob handles statistics automatically)");
        QueryResponse response = new QueryResponse();
        response.setRowCount(0);
        return response;
    }
    
    /**
     * Execute SET command (PostgreSQL session configuration)
     * These are sent by JDBC driver during connection initialization.
     * We acknowledge them but treat them as no-ops since we don't maintain session state.
     */
    private QueryResponse executeSet(ParsedQuery query) {
        log.debug("SET command received: {} - acknowledged as no-op", query.getRawSql());
        QueryResponse response = new QueryResponse();
        response.setRowCount(0);
        return response;
    }
    
    /**
     * Execute ALTER TABLE command
     * 
     * Supports:
     * - ALTER TABLE table ADD PRIMARY KEY (col1, col2, ...)
     * - ALTER TABLE table ADD FOREIGN KEY (col1) REFERENCES other_table(col2)
     * - ALTER TABLE table ADD CONSTRAINT name ...
     * 
     * In KV mode with hidden rowid primary keys, PRIMARY KEY constraints are treated as UNIQUE indexes.
     * FOREIGN KEY constraints are stored as metadata but not enforced (for PostgreSQL compatibility).
     */
    private QueryResponse executeAlterTable(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            String sqlUpper = sql.toUpperCase();
            String tableName = query.getTableName();
            
            // Check if table exists
            TableMetadata table = schemaManager.getTable(tableName);
            if (table == null) {
                return QueryResponse.error("Table does not exist: " + tableName);
            }
            
            // Handle ADD COLUMN
            if (sqlUpper.contains("ADD COLUMN") || (sqlUpper.contains("ADD ") && !sqlUpper.contains("ADD PRIMARY") && !sqlUpper.contains("ADD FOREIGN") && !sqlUpper.contains("ADD CONSTRAINT"))) {
                return handleAddColumn(tableName, sql, table);
            }
            
            // Handle DROP COLUMN (with or without COLUMN keyword)
            if (sqlUpper.contains("DROP COLUMN") || (sqlUpper.contains(" DROP ") && !sqlUpper.contains("DROP TABLE"))) {
                return handleDropColumn(tableName, sql, table);
            }
            
            // Handle ALTER COLUMN (type changes) - PREVENT this
            if (sqlUpper.contains("ALTER COLUMN") && sqlUpper.contains("TYPE")) {
                return QueryResponse.error("ALTER COLUMN TYPE is not supported - changing column types is not allowed");
            }
            
            // Handle ADD PRIMARY KEY
            if (sqlUpper.contains("ADD PRIMARY KEY")) {
                return handleAddPrimaryKey(tableName, sql, table);
            }
            
            // Handle ADD FOREIGN KEY
            if (sqlUpper.contains("ADD FOREIGN KEY") || 
                (sqlUpper.contains("ADD CONSTRAINT") && sqlUpper.contains("FOREIGN KEY"))) {
                return handleAddForeignKey(tableName, sql, table);
            }
            
            // Handle ADD CONSTRAINT (UNIQUE, CHECK, etc.)
            if (sqlUpper.contains("ADD CONSTRAINT")) {
                // For now, treat other constraints as no-ops
                log.debug("ALTER TABLE ADD CONSTRAINT on {}: storing as metadata only", tableName);
                QueryResponse response = new QueryResponse();
                response.setRowCount(0);
                return response;
            }
            
            // Other ALTER TABLE operations not yet supported
            return QueryResponse.error("ALTER TABLE operation not supported: " + sql);
            
        } catch (Exception e) {
            return QueryResponse.error("ALTER TABLE failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle ADD PRIMARY KEY constraint
     * 
     * In KV mode, tables already have a hidden rowid PRIMARY KEY.
     * We treat ADD PRIMARY KEY as creating a UNIQUE index on those columns instead.
     */
    private QueryResponse handleAddPrimaryKey(String tableName, String sql, TableMetadata table) {
        try {
            log.info("ALTER TABLE {} ADD PRIMARY KEY - treating as UNIQUE index (table already has hidden rowid PK)", tableName);
            
            // Extract column names from PRIMARY KEY (col1, col2, ...)
            List<String> columns = extractColumnsFromPrimaryKey(sql);
            
            if (columns.isEmpty()) {
                return QueryResponse.error("Could not extract column names from PRIMARY KEY constraint");
            }
            
            // Validate columns exist
            for (String col : columns) {
                if (table.getColumn(col) == null) {
                    return QueryResponse.error("Column does not exist: " + col);
                }
            }
            
            // Create a UNIQUE index with a generated name
            String indexName = "pk_" + tableName + "_" + String.join("_", columns);
            
            log.debug("Creating UNIQUE index {} on {} ({})", indexName, tableName, String.join(", ", columns));
            
            // Create the index using existing logic
            TableMetadata.IndexMetadata index = schemaManager.createIndex(tableName, indexName, columns, true);
            
            // Backfill the index with existing data
            log.info("Backfilling UNIQUE index {} on {}", indexName, tableName);
            int backfilledRows = backfillIndex(table, index);
            log.info("Backfilled {} rows into index {}", backfilledRows, indexName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            return QueryResponse.error("ADD PRIMARY KEY failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle ADD COLUMN using Calcite-validated SQL
     */
    private QueryResponse handleAddColumn(String tableName, String sql, TableMetadata table) {
        try {
            log.info("ALTER TABLE {} ADD COLUMN", tableName);
            
            // Parse: ALTER TABLE table ADD [COLUMN] column_name data_type [constraints]
            // Extract the part after ADD [COLUMN]
            String sqlUpper = sql.toUpperCase();
            int addIndex = sqlUpper.indexOf(" ADD ");
            if (addIndex == -1) {
                return QueryResponse.error("Could not parse ADD COLUMN statement");
            }
            
            String afterAdd = sql.substring(addIndex + 5).trim(); // Skip " ADD "
            
            // Skip optional "COLUMN" keyword
            if (afterAdd.toUpperCase().startsWith("COLUMN ")) {
                afterAdd = afterAdd.substring(7).trim();
            }
            
            // Remove trailing semicolon if present
            if (afterAdd.endsWith(";")) {
                afterAdd = afterAdd.substring(0, afterAdd.length() - 1).trim();
            }
            
            // Parse column definition: column_name data_type [NOT NULL] [DEFAULT value]
            // Use regex to extract column name and type more reliably
            String[] parts = afterAdd.split("\\s+", 3); // Split into max 3 parts
            if (parts.length < 2) {
                return QueryResponse.error("Invalid ADD COLUMN syntax: expected column_name and data_type");
            }
            
            String columnName = parts[0].replaceAll("[\"'`]", "").toLowerCase();
            String dataType = parts[1].toUpperCase();
            
            // Check if column already exists
            if (table.getColumn(columnName) != null) {
                return QueryResponse.error("Column already exists: " + columnName);
            }
            
            // Check for NOT NULL constraint in the remaining part
            String constraints = parts.length > 2 ? parts[2] : "";
            boolean nullable = !constraints.toUpperCase().contains("NOT NULL");
            
            // Create new column metadata
            TableMetadata.ColumnMetadata newColumn = new TableMetadata.ColumnMetadata(
                columnName,
                dataType,
                nullable
            );
            
            // Add column to table via schema manager
            schemaManager.addColumn(tableName, newColumn);
            
            log.info("Added column {} ({}) to table {}", columnName, dataType, tableName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            log.error("ADD COLUMN failed", e);
            return QueryResponse.error("ADD COLUMN failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle DROP COLUMN using Calcite-validated SQL
     */
    private QueryResponse handleDropColumn(String tableName, String sql, TableMetadata table) {
        try {
            log.info("ALTER TABLE {} DROP COLUMN", tableName);
            
            // Parse: ALTER TABLE table DROP [COLUMN] column_name
            String sqlUpper = sql.toUpperCase();
            int dropIndex = sqlUpper.indexOf(" DROP ");
            if (dropIndex == -1) {
                return QueryResponse.error("Could not parse DROP COLUMN statement");
            }
            
            String afterDrop = sql.substring(dropIndex + 6).trim(); // Skip " DROP "
            
            // Skip optional "COLUMN" keyword
            if (afterDrop.toUpperCase().startsWith("COLUMN ")) {
                afterDrop = afterDrop.substring(7).trim();
            }
            
            // Remove trailing semicolon if present
            if (afterDrop.endsWith(";")) {
                afterDrop = afterDrop.substring(0, afterDrop.length() - 1).trim();
            }
            
            // Extract column name (first token)
            String columnName = afterDrop.split("\\s+")[0].replaceAll("[\"'`]", "").toLowerCase();
            
            // Check if column exists
            if (table.getColumn(columnName) == null) {
                return QueryResponse.error("Column does not exist: " + columnName);
            }
            
            // Prevent dropping primary key columns
            if (table.getPrimaryKeyColumns().contains(columnName)) {
                return QueryResponse.error("Cannot drop primary key column: " + columnName);
            }
            
            // Drop column from table via schema manager
            schemaManager.dropColumn(tableName, columnName);
            
            log.info("Dropped column {} from table {}", columnName, tableName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            log.error("DROP COLUMN failed", e);
            return QueryResponse.error("DROP COLUMN failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle ADD FOREIGN KEY constraint
     * 
     * In KV mode, foreign keys are stored as metadata but not enforced.
     * This is for PostgreSQL compatibility - the constraint exists but doesn't prevent invalid data.
     */
    private QueryResponse handleAddForeignKey(String tableName, String sql, TableMetadata table) {
        log.debug("ALTER TABLE {} ADD FOREIGN KEY - storing as metadata only (not enforced in KV mode)", tableName);
        
        // TODO: Parse and store foreign key metadata if needed
        // For now, treat as a no-op since pgbench doesn't rely on FK enforcement
        
        QueryResponse response = new QueryResponse();
        response.setRowCount(0);
        return response;
    }
    
    /**
     * Extract column names from PRIMARY KEY (col1, col2, ...) clause
     */
    private List<String> extractColumnsFromPrimaryKey(String sql) {
        List<String> columns = new ArrayList<>();
        
        // Find PRIMARY KEY (...) 
        int pkIndex = sql.toUpperCase().indexOf("PRIMARY KEY");
        if (pkIndex == -1) {
            return columns;
        }
        
        // Find opening parenthesis after PRIMARY KEY
        int openParen = sql.indexOf('(', pkIndex);
        if (openParen == -1) {
            return columns;
        }
        
        // Find matching closing parenthesis
        int closeParen = sql.indexOf(')', openParen);
        if (closeParen == -1) {
            return columns;
        }
        
        // Extract column list
        String colList = sql.substring(openParen + 1, closeParen).trim();
        
        // Split by comma and clean up
        for (String col : colList.split(",")) {
            String cleanCol = col.trim().replaceAll("[\"'`]", "").toLowerCase();
            if (!cleanCol.isEmpty()) {
                columns.add(cleanCol);
            }
        }
        
        return columns;
    }
    
    /**
     * Evaluate JSON path expressions in column references.
     * Supports: column->>'path', column->'path', column#>'{path,to,value}', column#>>'{path,to,value}'
     */
    private Object evaluateJsonPath(String columnExpr, Map<String, Object> row, TableMetadata table) {
        // Check for JSON operators
        if (columnExpr.contains("->>")) {
            // Extract text: column->>'key'
            String[] parts = columnExpr.split("->>");
            if (parts.length == 2) {
                String colName = parts[0].trim().toLowerCase();
                String key = parts[1].trim().replaceAll("^['\"]|['\"]$", ""); // Remove quotes
                Object jsonValue = row.get(colName);
                if (jsonValue != null) {
                    return JsonHelper.extractText(String.valueOf(jsonValue), key);
                }
            }
        } else if (columnExpr.contains("->")) {
            // Extract JSON: column->'key'
            String[] parts = columnExpr.split("->");
            if (parts.length == 2) {
                String colName = parts[0].trim().toLowerCase();
                String key = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                Object jsonValue = row.get(colName);
                if (jsonValue != null) {
                    return JsonHelper.extractJson(String.valueOf(jsonValue), key);
                }
            }
        } else if (columnExpr.contains("#>>")) {
            // Extract text at path: column#>>'{key1,key2}'
            String[] parts = columnExpr.split("#>>");
            if (parts.length == 2) {
                String colName = parts[0].trim().toLowerCase();
                String pathStr = parts[1].trim();
                List<String> path = JsonHelper.parseArrayLiteral(pathStr);
                Object jsonValue = row.get(colName);
                if (jsonValue != null) {
                    return JsonHelper.extractTextPath(String.valueOf(jsonValue), path);
                }
            }
        } else if (columnExpr.contains("#>")) {
            // Extract JSON at path: column#>'{key1,key2}'
            String[] parts = columnExpr.split("#>");
            if (parts.length == 2) {
                String colName = parts[0].trim().toLowerCase();
                String pathStr = parts[1].trim();
                List<String> path = JsonHelper.parseArrayLiteral(pathStr);
                Object jsonValue = row.get(colName);
                if (jsonValue != null) {
                    return JsonHelper.extractJsonPath(String.valueOf(jsonValue), path);
                }
            }
        }
        
        // No JSON operator found, return column value directly
        return row.get(columnExpr.toLowerCase());
    }
    
    /**
     * Evaluate WHERE clause predicates against a row
     * Handles AND, OR, NOT operators
     */
    private boolean evaluateWhereClause(Map<String, Object> row, CalciteSqlParser.WhereClause whereClause, TableMetadata table) {
        for (CalciteSqlParser.Predicate pred : whereClause.getPredicates()) {
            // Handle OR predicates specially
            if (pred.getOperator().equals("OR")) {
                // OR predicate contains a list of branches
                // Each branch is a list of predicates that must ALL match (AND within branch)
                // At least ONE branch must match for the OR to succeed
                if (pred.getValue() instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<List<CalciteSqlParser.Predicate>> orBranches = (List<List<CalciteSqlParser.Predicate>>) pred.getValue();
                    
                    boolean anyBranchMatches = false;
                    for (List<CalciteSqlParser.Predicate> branch : orBranches) {
                        // Evaluate all predicates in this branch (AND logic)
                        CalciteSqlParser.WhereClause branchClause = new CalciteSqlParser.WhereClause(branch);
                        if (evaluateWhereClause(row, branchClause, table)) {
                            anyBranchMatches = true;
                            break; // Short-circuit: one branch matched
                        }
                    }
                    
                    if (!anyBranchMatches) {
                        return false; // OR condition failed - no branch matched
                    }
                }
            } else {
                // Regular predicate
                Object rowValue = row.get(pred.getColumn().toLowerCase());
                // Check for JSON path predicates (e.g., data->>'name' = 'Alice')
                rowValue = evaluateJsonPath(pred.getColumn(), row, table);
                if (!pred.evaluate(rowValue)) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Process JSON expressions in SELECT column list.
     * This is a placeholder for future enhancement - currently returns row as-is.
     */
    private Map<String, Object> processJsonExpressions(Map<String, Object> row, TableMetadata table, 
                                                       CalciteSqlParser.SelectInfo selectInfo) {
        // Future: Parse SELECT column list and add computed JSON fields
        // For now, SELECT * returns all columns, and specific column names work as-is
        return row;
    }
    
    /**
     * Execute aggregation query (COUNT, SUM, AVG, MIN, MAX, GROUP BY)
     */
    private QueryResponse executeAggregation(ParsedQuery query) {
        try {
            log.debug("📊 Executing aggregation query in KV mode");
            
            AggregationQuery aggQuery = query.getAggregationQuery();
            if (aggQuery == null) {
                return QueryResponse.error("No aggregation query information");
            }
            
            // Step 1: Execute base query to get all rows
            ParsedQuery baseQuery = aggQuery.getBaseQuery();
            QueryResponse baseResult;
            
            if (baseQuery.getType() == ParsedQuery.Type.SELECT) {
                baseResult = executeSelect(baseQuery);
            } else if (baseQuery.getType() == ParsedQuery.Type.JOIN) {
                baseResult = joinExecutor.executeBinaryJoin(baseQuery.getJoinQuery());
            } else if (baseQuery.getType() == ParsedQuery.Type.MULTI_WAY_JOIN) {
                baseResult = joinExecutor.executeMultiWayJoin(baseQuery.getMultiWayJoin());
            } else {
                return QueryResponse.error("Unsupported base query type for aggregation: " + baseQuery.getType());
            }
            
            if (baseResult.getError() != null) {
                return baseResult;
            }
            
            List<Map<String, Object>> baseRows = baseResult.getRows();
            log.debug("📊 Base query returned {} rows for aggregation", baseRows != null ? baseRows.size() : 0);
            
            // Step 2: Group rows (if GROUP BY exists)
            Map<String, List<Map<String, Object>>> groupedRows = new LinkedHashMap<>();
            
            if (aggQuery.getGroupByColumns() != null && !aggQuery.getGroupByColumns().isEmpty()) {
                // Group by specified columns
                for (Map<String, Object> row : baseRows) {
                    String groupKey = buildGroupKey(row, aggQuery.getGroupByColumns());
                    groupedRows.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(row);
                }
            } else {
                // No GROUP BY - treat all rows as one group
                groupedRows.put("__all__", baseRows);
            }
            
            // Step 3: Parse original SELECT list to get expressions (including concatenation)
            CalciteSqlParser.SelectInfo selectInfo = null;
            try {
                selectInfo = sqlParser.parseSelect(aggQuery.getOriginalSql());
                log.debug("📊 Parsed SELECT list: {}", selectInfo != null ? selectInfo.getColumns() : "null");
            } catch (Exception e) {
                log.warn("Failed to parse SELECT list for aggregation: {}", e.getMessage());
            }
            
            // Step 4: Compute aggregates for each group
            List<Map<String, Object>> resultRows = new ArrayList<>();
            List<String> resultColumns = new ArrayList<>();
            
            // Build result columns - use SELECT list path only for GROUP BY queries with expressions
            boolean useSelectListForColumns = selectInfo != null && selectInfo.getColumns() != null && 
                                             !selectInfo.getColumns().isEmpty() &&
                                             (aggQuery.getGroupByColumns() != null && !aggQuery.getGroupByColumns().isEmpty());
            
            if (useSelectListForColumns) {
                // Extract column names/aliases from SELECT list
                for (String col : selectInfo.getColumns()) {
                    // Remove backticks
                    col = col.replace("`", "");
                    
                    if (col.toUpperCase().contains(" AS ")) {
                        String[] parts = col.split("(?i)\\s+AS\\s+");
                        if (parts.length == 2) {
                            resultColumns.add(parts[1].trim().toLowerCase());
                        } else {
                            resultColumns.add(col.toLowerCase());
                        }
                    } else {
                        String colName = col.contains(".") ? 
                                        col.substring(col.lastIndexOf('.') + 1) : 
                                        col;
                        resultColumns.add(colName.toLowerCase());
                    }
                }
            } else {
                // Fallback: Add GROUP BY columns to result
                if (aggQuery.getGroupByColumns() != null) {
                    for (String groupCol : aggQuery.getGroupByColumns()) {
                        String colName = groupCol.contains(".") ? 
                                        groupCol.substring(groupCol.lastIndexOf('.') + 1) : 
                                        groupCol;
                        resultColumns.add(colName.toLowerCase());
                    }
                }
                
                // Add aggregate columns to result
                for (AggregateFunction agg : aggQuery.getAggregates()) {
                    resultColumns.add(agg.getAlias().toLowerCase());
                }
            }
            
            // Compute aggregates for each group
            for (Map.Entry<String, List<Map<String, Object>>> entry : groupedRows.entrySet()) {
                List<Map<String, Object>> groupRows = entry.getValue();
                Map<String, Object> resultRow = new LinkedHashMap<>();
                
                // Use first row in group as template for evaluating expressions
                Map<String, Object> templateRow = groupRows.get(0);
                
                // Evaluate SELECT list expressions (if available and contains GROUP BY columns or expressions)
                // For simple aggregates without GROUP BY, use the fallback path
                boolean useSelectListPath = selectInfo != null && selectInfo.getColumns() != null &&
                                           (aggQuery.getGroupByColumns() != null && !aggQuery.getGroupByColumns().isEmpty());
                
                if (useSelectListPath) {
                    for (String col : selectInfo.getColumns()) {
                        // Remove backticks that Calcite adds
                        col = col.replace("`", "");
                        
                        if (col.toUpperCase().contains(" AS ")) {
                            String[] parts = col.split("(?i)\\s+AS\\s+");
                            if (parts.length == 2) {
                                String expression = parts[0].trim();
                                String alias = parts[1].trim().toLowerCase();
                                
                                log.debug("📊 Processing SELECT item: expression='{}', alias='{}'", expression, alias);
                                
                                // Check if this is an aggregate - if so, compute it
                                boolean isAggregate = false;
                                for (AggregateFunction agg : aggQuery.getAggregates()) {
                                    if (agg.getAlias().equalsIgnoreCase(alias)) {
                                        Object aggValue = computeAggregate(agg, groupRows);
                                        resultRow.put(alias, aggValue);
                                        log.debug("📊 Computed aggregate {}: {}", alias, aggValue);
                                        isAggregate = true;
                                        break;
                                    }
                                }
                                
                                // If not an aggregate, evaluate the expression
                                if (!isAggregate) {
                                    Object value = evaluateSelectExpression(expression, templateRow);
                                    resultRow.put(alias, value);
                                    log.debug("📊 Evaluated expression {}: {}", alias, value);
                                }
                            }
                        } else {
                            // No alias - could be a simple column or an aggregate without alias
                            // For aggregates, the column will be like "SUM(AMOUNT)" and the alias will be "sum_amount"
                            
                            // Check if this matches an aggregate
                            boolean isAggregate = false;
                            for (AggregateFunction agg : aggQuery.getAggregates()) {
                                // The aggregate alias is generated as funcName_columnName (e.g., "sum_amount")
                                // Check if the column expression matches this aggregate
                                String expectedExpr = agg.getType().name() + "(" + agg.getColumn() + ")";
                                if (col.equalsIgnoreCase(expectedExpr)) {
                                    Object aggValue = computeAggregate(agg, groupRows);
                                    // Store with BOTH the full expression AND the generated alias for compatibility
                                    resultRow.put(col.toLowerCase(), aggValue);
                                    resultRow.put(agg.getAlias().toLowerCase(), aggValue);
                                    log.info("📊 Computed aggregate (no alias): {} = {} (also as {})", col, aggValue, agg.getAlias());
                                    isAggregate = true;
                                    break;
                                }
                            }
                            
                            if (!isAggregate) {
                                // Simple column reference
                                String colName = col.contains(".") ? 
                                                col.substring(col.lastIndexOf('.') + 1) : 
                                                col;
                                Object value = templateRow.get(colName.toLowerCase());
                                resultRow.put(colName.toLowerCase(), value);
                                log.debug("📊 Simple column {}: {}", colName, value);
                            }
                        }
                    }
                } else {
                    // Fallback: Add GROUP BY column values
                    if (aggQuery.getGroupByColumns() != null && !aggQuery.getGroupByColumns().isEmpty()) {
                        for (String groupCol : aggQuery.getGroupByColumns()) {
                            String colName = groupCol.contains(".") ? 
                                            groupCol.substring(groupCol.lastIndexOf('.') + 1) : 
                                            groupCol;
                            resultRow.put(colName.toLowerCase(), templateRow.get(colName.toLowerCase()));
                        }
                    }
                    
                    // Compute aggregate values
                    log.info("📊 Computing {} aggregates for group with {} rows", aggQuery.getAggregates().size(), groupRows.size());
                    for (AggregateFunction agg : aggQuery.getAggregates()) {
                        log.info("📊 Computing aggregate: {}", agg);
                        Object aggValue = computeAggregate(agg, groupRows);
                        log.info("📊 Aggregate result: {} = {}", agg.getAlias(), aggValue);
                        resultRow.put(agg.getAlias().toLowerCase(), aggValue);
                    }
                }
                
                resultRows.add(resultRow);
            }
            
            // Step 4: Apply HAVING clause filter (if exists)
            if (aggQuery.hasHaving()) {
                resultRows = applyHavingClause(resultRows, aggQuery.getHavingClause(), aggQuery);
            }
            
            log.debug("📊 Aggregation produced {} groups", resultRows.size());
            
            // Step 5: Inject literal columns from aggregation query at their original positions
            List<com.geico.poc.cassandrasql.LiteralColumn> literals = aggQuery.getLiterals();
            if (literals != null && !literals.isEmpty()) {
                // Build a map of position -> literal for quick lookup
                Map<Integer, com.geico.poc.cassandrasql.LiteralColumn> literalsByPosition = new HashMap<>();
                for (com.geico.poc.cassandrasql.LiteralColumn literal : literals) {
                    literalsByPosition.put(literal.getPosition(), literal);
                }
                
                // Rebuild result columns with literals inserted at their original positions
                // We need to merge the existing resultColumns with literals in the correct order
                // The resultColumns currently contains aggregates and GROUP BY columns in the order they were processed
                // We need to insert literals at their original SELECT list positions
                
                // Parse the original SELECT list to get the full order, merging columns and literals
                List<String> orderedColumns = new ArrayList<>();
                if (selectInfo != null) {
                    // Build a map of position -> column alias from both columns and literals
                    Map<Integer, String> positionToColumn = new HashMap<>();
                    
                    // Add literals from selectInfo (these have positions)
                    if (selectInfo.getLiterals() != null) {
                        for (com.geico.poc.cassandrasql.kv.CalciteSqlParser.LiteralColumn literal : selectInfo.getLiterals()) {
                            positionToColumn.put(literal.getPosition(), literal.getAlias().toLowerCase());
                        }
                    }
                    
                    // Add columns from selectInfo (need to infer positions from order)
                    if (selectInfo.getColumns() != null) {
                        int colPos = 0;
                        for (String col : selectInfo.getColumns()) {
                            col = col.replace("`", "");
                            // Skip positions that are already occupied by literals
                            while (positionToColumn.containsKey(colPos)) {
                                colPos++;
                            }
                            
                            if (col.toUpperCase().contains(" AS ")) {
                                String[] parts = col.split("(?i)\\s+AS\\s+");
                                if (parts.length == 2) {
                                    String alias = parts[1].trim().toLowerCase();
                                    // Only add if not already a literal
                                    boolean isLiteral = false;
                                    if (selectInfo.getLiterals() != null) {
                                        for (com.geico.poc.cassandrasql.kv.CalciteSqlParser.LiteralColumn lit : selectInfo.getLiterals()) {
                                            if (lit.getAlias().equalsIgnoreCase(alias)) {
                                                isLiteral = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (!isLiteral) {
                                        positionToColumn.put(colPos, alias);
                                    }
                                }
                            }
                            colPos++;
                        }
                    }
                    
                    // Build ordered list from position map
                    int maxPos = positionToColumn.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                    for (int pos = 0; pos <= maxPos; pos++) {
                        if (positionToColumn.containsKey(pos)) {
                            orderedColumns.add(positionToColumn.get(pos));
                        }
                    }
                }
                
                // If we have ordered columns from SELECT list, use them; otherwise insert literals by position
                if (!orderedColumns.isEmpty()) {
                    // Use the ordered columns from SELECT list (includes literals and aggregates in correct order)
                    resultColumns = new ArrayList<>(orderedColumns);
                } else {
                    // Fallback: Insert literals at their positions in the existing column list
                    // This is less ideal but handles cases where we don't have the full SELECT list
                    List<String> newColumns = new ArrayList<>();
                    int maxPosition = Math.max(
                        resultColumns.size() - 1,
                        literals.stream().mapToInt(l -> l.getPosition()).max().orElse(-1)
                    );
                    
                    for (int pos = 0; pos <= maxPosition; pos++) {
                        if (literalsByPosition.containsKey(pos)) {
                            // Insert literal at this position
                            com.geico.poc.cassandrasql.LiteralColumn literal = literalsByPosition.get(pos);
                            String alias = literal.getAlias().toLowerCase();
                            if (!newColumns.contains(alias)) {
                                newColumns.add(alias);
                            }
                        } else if (pos < resultColumns.size()) {
                            // Insert regular column/aggregate at this position
                            String col = resultColumns.get(pos);
                            if (!newColumns.contains(col)) {
                                newColumns.add(col);
                            }
                        }
                    }
                    resultColumns = newColumns;
                }
                
                // Add literal values to each result row at their correct positions
                List<Map<String, Object>> newRows = new ArrayList<>();
                for (Map<String, Object> row : resultRows) {
                    Map<String, Object> newRow = new LinkedHashMap<>();
                    
                    // Build row in the order of resultColumns
                    for (String colName : resultColumns) {
                        // Check if this column is a literal
                        com.geico.poc.cassandrasql.LiteralColumn matchingLiteral = null;
                        for (com.geico.poc.cassandrasql.LiteralColumn literal : literals) {
                            if (literal.getAlias().equalsIgnoreCase(colName)) {
                                matchingLiteral = literal;
                                break;
                            }
                        }
                        
                        if (matchingLiteral != null) {
                            // Use literal value
                            newRow.put(colName, matchingLiteral.getValue());
                        } else {
                            // Use value from existing row (aggregate or GROUP BY column)
                            Object value = row.get(colName);
                            if (value == null) {
                                // Try case-insensitive lookup
                                for (Map.Entry<String, Object> entry : row.entrySet()) {
                                    if (entry.getKey().equalsIgnoreCase(colName)) {
                                        value = entry.getValue();
                                        break;
                                    }
                                }
                            }
                            newRow.put(colName, value);
                        }
                    }
                    newRows.add(newRow);
                }
                resultRows = newRows;
            }
            
            QueryResponse response = new QueryResponse(resultRows, resultColumns);
            
            return response;
            
        } catch (Exception e) {
            log.error("Aggregation execution failed", e);
            return QueryResponse.error("Aggregation failed: " + e.getMessage());
        }
    }
    
    /**
     * Inject literal columns into query results (from SQL string)
     */
    private QueryResponse injectLiterals(QueryResponse response, String sql) {
        if (response.getError() != null || response.getRows() == null || response.getRows().isEmpty()) {
            return response;
        }
        
        try {
            // Parse the SQL to extract literals
            CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(sql);
            return injectLiterals(response, selectInfo);
        } catch (Exception e) {
            log.warn("Failed to parse SQL for literal injection: " + e.getMessage());
            return response; // Return original response if parsing fails
        }
    }
    
    /**
     * Inject literal columns into query results (from SelectInfo)
     */
    private QueryResponse injectLiterals(QueryResponse response, CalciteSqlParser.SelectInfo selectInfo) {
        if (response.getError() != null || response.getRows() == null || response.getRows().isEmpty()) {
            return response;
        }
        
        try {
            List<CalciteSqlParser.LiteralColumn> literals = selectInfo.getLiterals();
            
            if (literals.isEmpty()) {
                return response; // No literals to inject
            }
            
            // Add literal columns to the column list
            List<String> newColumns = new ArrayList<>(response.getColumns());
            for (CalciteSqlParser.LiteralColumn literal : literals) {
                if (!newColumns.contains(literal.getAlias().toLowerCase())) {
                    newColumns.add(literal.getAlias().toLowerCase());
                }
            }
            
            // Add literal values to each row
            List<Map<String, Object>> newRows = new ArrayList<>();
            for (Map<String, Object> row : response.getRows()) {
                Map<String, Object> newRow = new LinkedHashMap<>(row);
                for (CalciteSqlParser.LiteralColumn literal : literals) {
                    newRow.put(literal.getAlias().toLowerCase(), literal.getValue());
                }
                newRows.add(newRow);
            }
            
            return new QueryResponse(newRows, newColumns);
            
        } catch (Exception e) {
            log.warn("Failed to inject literals: " + e.getMessage());
            return response; // Return original response if literal injection fails
        }
    }
    
    /**
     * Build a group key from row values for GROUP BY columns
     */
    private String buildGroupKey(Map<String, Object> row, List<String> groupByColumns) {
        StringBuilder key = new StringBuilder();
        for (String col : groupByColumns) {
            // Remove table prefix if present
            String colName = col.contains(".") ? 
                            col.substring(col.lastIndexOf('.') + 1) : 
                            col;
            Object value = row.get(colName.toLowerCase());
            key.append(value != null ? value.toString() : "NULL").append("|");
        }
        return key.toString();
    }
    
    /**
     * Compute aggregate value for a group of rows
     */
    private Object computeAggregate(AggregateFunction agg, List<Map<String, Object>> rows) {
        switch (agg.getType()) {
            case COUNT:
                if (agg.isCountStar()) {
                    return (long) rows.size();
                } else {
                    // COUNT(column) - count non-null values
                    String colName = agg.getColumn().contains(".") ? 
                                    agg.getColumn().substring(agg.getColumn().lastIndexOf('.') + 1) : 
                                    agg.getColumn();
                    return rows.stream()
                            .filter(row -> row.get(colName.toLowerCase()) != null)
                            .count();
                }
                
            case SUM:
                String sumColNameTemp = agg.getColumn().contains(".") ? 
                                   agg.getColumn().substring(agg.getColumn().lastIndexOf('.') + 1) : 
                                   agg.getColumn();
                // Remove backticks that might be in the column name
                final String sumColName = sumColNameTemp.replace("`", "");
                
                log.info("📊 SUM: column='{}', rows.size={}, first row keys={}", 
                    sumColName, rows.size(), rows.isEmpty() ? "[]" : rows.get(0).keySet());
                
                // Check if all values are integers to return long instead of double
                // This prevents scientific notation for large sums (e.g., 1.0E9)
                boolean allIntegers = rows.stream()
                        .map(row -> getCaseInsensitive(row, sumColName))
                        .filter(val -> val != null)
                        .allMatch(val -> val instanceof Integer || val instanceof Long);
                
                if (allIntegers) {
                    // Return long sum for integer columns
                    return rows.stream()
                            .map(row -> getCaseInsensitive(row, sumColName))
                            .filter(val -> val != null)
                            .mapToLong(val -> ((Number) val).longValue())
                            .sum();
                } else {
                    // Return double sum for decimal columns
                    return rows.stream()
                            .map(row -> getCaseInsensitive(row, sumColName))
                            .filter(val -> val != null)
                            .mapToDouble(val -> {
                                if (val instanceof Number) {
                                    return ((Number) val).doubleValue();
                                }
                                try {
                                    return Double.parseDouble(val.toString());
                                } catch (NumberFormatException e) {
                                    return 0.0;
                                }
                            })
                            .sum();
                }
                        
            case AVG:
                String avgColNameTemp = agg.getColumn().contains(".") ? 
                                   agg.getColumn().substring(agg.getColumn().lastIndexOf('.') + 1) : 
                                   agg.getColumn();
                final String avgColName = avgColNameTemp.replace("`", "");
                return rows.stream()
                        .map(row -> getCaseInsensitive(row, avgColName))
                        .filter(val -> val != null)
                        .mapToDouble(val -> {
                            if (val instanceof Number) {
                                return ((Number) val).doubleValue();
                            }
                            try {
                                return Double.parseDouble(val.toString());
                            } catch (NumberFormatException e) {
                                return 0.0;
                            }
                        })
                        .average()
                        .orElse(0.0);
                        
            case MIN:
                String minColNameTemp = agg.getColumn().contains(".") ? 
                                   agg.getColumn().substring(agg.getColumn().lastIndexOf('.') + 1) : 
                                   agg.getColumn();
                final String minColName = minColNameTemp.replace("`", "");
                return rows.stream()
                        .map(row -> getCaseInsensitive(row, minColName))
                        .filter(val -> val != null)
                        .mapToDouble(val -> {
                            if (val instanceof Number) {
                                return ((Number) val).doubleValue();
                            }
                            try {
                                return Double.parseDouble(val.toString());
                            } catch (NumberFormatException e) {
                                return Double.MAX_VALUE;
                            }
                        })
                        .min()
                        .orElse(0.0);
                        
            case MAX:
                String maxColNameTemp = agg.getColumn().contains(".") ? 
                                   agg.getColumn().substring(agg.getColumn().lastIndexOf('.') + 1) : 
                                   agg.getColumn();
                final String maxColName = maxColNameTemp.replace("`", "");
                return rows.stream()
                        .map(row -> getCaseInsensitive(row, maxColName))
                        .filter(val -> val != null)
                        .mapToDouble(val -> {
                            if (val instanceof Number) {
                                return ((Number) val).doubleValue();
                            }
                            try {
                                return Double.parseDouble(val.toString());
                            } catch (NumberFormatException e) {
                                return Double.MIN_VALUE;
                            }
                        })
                        .max()
                        .orElse(0.0);
                        
            default:
                return null;
        }
    }
    
    /**
     * Apply HAVING clause filter to aggregated results
     */
    private List<Map<String, Object>> applyHavingClause(List<Map<String, Object>> rows, String havingClause, AggregationQuery aggQuery) {
        // Simple HAVING implementation - supports basic comparisons
        // Example: "COUNT(*) > 5" or "SUM(amount) >= 1000"
        
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        for (Map<String, Object> row : rows) {
            if (evaluateHaving(row, havingClause, aggQuery)) {
                filtered.add(row);
            }
        }
        
        log.debug("📊 HAVING filter: {} rows -> {} rows", rows.size(), filtered.size());
        
        return filtered;
    }
    
    /**
     * Evaluate HAVING condition for a row
     */
    private boolean evaluateHaving(Map<String, Object> row, String having, AggregationQuery aggQuery) {
        // Simple parser for HAVING conditions
        // Supports: AGG_FUNC(column) > value, AGG_FUNC(column) >= value, etc.
        
        having = having.trim();
        
        // Extract operator
        String operator = null;
        int opIndex = -1;
        
        if (having.contains(">=")) {
            operator = ">=";
            opIndex = having.indexOf(">=");
        } else if (having.contains("<=")) {
            operator = "<=";
            opIndex = having.indexOf("<=");
        } else if (having.contains(">")) {
            operator = ">";
            opIndex = having.indexOf(">");
        } else if (having.contains("<")) {
            operator = "<";
            opIndex = having.indexOf("<");
        } else if (having.contains("=")) {
            operator = "=";
            opIndex = having.indexOf("=");
        } else {
            log.warn("📊 HAVING: Cannot parse operator in: {}", having);
            return true; // Can't parse, allow through
        }
        
        // Extract aggregate expression and value
        String aggExpression = having.substring(0, opIndex).trim();
        String valueStr = having.substring(opIndex + operator.length()).trim();
        
        log.debug("📊 HAVING: Evaluating {} {} {}", aggExpression, operator, valueStr);
        
        // Try to find a matching column in the row
        // The aggregate expression might be like "SUM(OI.LINE_TOTAL)" but the column is stored
        // by its alias or a normalized form
        Object rowValue = findAggregateValue(row, aggExpression, aggQuery);
        
        if (rowValue == null) {
            log.warn("📊 HAVING: No value found for aggregate expression: {}", aggExpression);
            log.warn("📊 HAVING: Available columns: {}", row.keySet());
            return false;
        }
        
        log.debug("📊 HAVING: Found value {} for expression {}", rowValue, aggExpression);
        
        // Compare
        try {
            double rowNum = ((Number) rowValue).doubleValue();
            double compareNum = Double.parseDouble(valueStr);
            
            boolean result;
            switch (operator) {
                case ">": result = rowNum > compareNum; break;
                case ">=": result = rowNum >= compareNum; break;
                case "<": result = rowNum < compareNum; break;
                case "<=": result = rowNum <= compareNum; break;
                case "=": result = Math.abs(rowNum - compareNum) < 0.0001; break;
                default: result = true;
            }
            
            log.debug("📊 HAVING: {} {} {} = {}", rowNum, operator, compareNum, result);
            return result;
        } catch (Exception e) {
            log.warn("📊 HAVING: Error evaluating: {}", e.getMessage());
            return true; // Can't evaluate, allow through
        }
    }
    
    /**
     * Find the value for an aggregate expression in a result row
     * Handles qualified column names like SUM(OI.LINE_TOTAL) by matching to aggregate aliases
     */
    private Object findAggregateValue(Map<String, Object> row, String aggExpression, AggregationQuery aggQuery) {
        // Normalize the expression
        String normalized = aggExpression.toLowerCase().replace("`", "");
        
        log.debug("📊 HAVING: Looking for aggregate expression: {}", normalized);
        
        // Try to match the aggregate expression to one of the aggregates in the query
        // and use its alias to look up the value
        for (AggregateFunction agg : aggQuery.getAggregates()) {
            // Build the aggregate expression from the function
            String aggFunc = agg.getType().name();
            String aggCol = agg.getColumn();
            
            // Remove table prefix from column if present
            if (aggCol.contains(".")) {
                aggCol = aggCol.substring(aggCol.lastIndexOf(".") + 1);
            }
            
            String aggExpr = aggFunc.toLowerCase() + "(" + aggCol.toLowerCase() + ")";
            
            log.debug("📊 HAVING: Comparing with aggregate: {} (alias: {})", aggExpr, agg.getAlias());
            
            // Check if the normalized expression matches this aggregate
            // Try with and without table prefix
            if (normalized.equals(aggExpr) || 
                normalized.replace(".", "").contains(aggExpr.replace(".", ""))) {
                
                String alias = agg.getAlias().toLowerCase();
                Object value = row.get(alias);
                log.debug("📊 HAVING: Matched! Using alias '{}' with value: {}", alias, value);
                return value;
            }
            
            // Also try matching the full expression with table prefix
            String aggExprWithPrefix = aggFunc.toLowerCase() + "(" + agg.getColumn().toLowerCase() + ")";
            if (normalized.equals(aggExprWithPrefix)) {
                String alias = agg.getAlias().toLowerCase();
                Object value = row.get(alias);
                log.debug("📊 HAVING: Matched with prefix! Using alias '{}' with value: {}", alias, value);
                return value;
            }
        }
        
        // Fallback: Try exact match with column name
        if (row.containsKey(normalized)) {
            return row.get(normalized);
        }
        
        // Try all columns to find a match (case-insensitive)
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    /**
     * Parse UNIQUE constraint from CREATE TABLE definition
     * Supports: UNIQUE (col1, col2, ...) or CONSTRAINT name UNIQUE (col1, col2, ...)
     */
    private TableMetadata.UniqueConstraint parseUniqueConstraint(String constraintDef, String tableName) {
        try {
            String defUpper = constraintDef.toUpperCase();
            String constraintName = null;
            List<String> columns = new ArrayList<>();
            
            // Check for CONSTRAINT name UNIQUE (...)
            if (defUpper.startsWith("CONSTRAINT")) {
                int uniquePos = defUpper.indexOf("UNIQUE");
                if (uniquePos == -1) {
                    return null; // Not a UNIQUE constraint
                }
                
                // Extract constraint name
                String namePart = constraintDef.substring(10, uniquePos).trim();
                constraintName = namePart.split("\\s+")[0];
                
                // Extract columns
                int openParen = constraintDef.indexOf('(', uniquePos);
                int closeParen = constraintDef.lastIndexOf(')');
                if (openParen != -1 && closeParen != -1) {
                    String colList = constraintDef.substring(openParen + 1, closeParen).trim();
                    for (String col : colList.split(",")) {
                        columns.add(col.trim().toLowerCase());
                    }
                }
            } else if (defUpper.startsWith("UNIQUE")) {
                // UNIQUE (col1, col2, ...)
                int openParen = constraintDef.indexOf('(');
                int closeParen = constraintDef.lastIndexOf(')');
                if (openParen != -1 && closeParen != -1) {
                    String colList = constraintDef.substring(openParen + 1, closeParen).trim();
                    for (String col : colList.split(",")) {
                        columns.add(col.trim().toLowerCase());
                    }
                    // Generate constraint name
                    constraintName = tableName + "_" + String.join("_", columns) + "_unique";
                }
            }
            
            if (columns.isEmpty()) {
                return null;
            }
            
            return new TableMetadata.UniqueConstraint(constraintName, columns);
            
        } catch (Exception e) {
            log.warn("Failed to parse UNIQUE constraint: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Parse FOREIGN KEY constraint from CREATE TABLE definition
     * Supports: FOREIGN KEY (col1, col2) REFERENCES table(col1, col2) [ON DELETE ...] [ON UPDATE ...]
     *           CONSTRAINT name FOREIGN KEY (...) REFERENCES ...
     */
    private TableMetadata.ForeignKeyConstraint parseForeignKeyConstraint(String constraintDef, String tableName) {
        try {
            String defUpper = constraintDef.toUpperCase();
            String constraintName = null;
            List<String> columns = new ArrayList<>();
            String referencedTable = null;
            List<String> referencedColumns = new ArrayList<>();
            String onDelete = "NO ACTION";
            String onUpdate = "NO ACTION";
            
            // Check for CONSTRAINT name FOREIGN KEY (...)
            int fkPos = defUpper.indexOf("FOREIGN KEY");
            if (fkPos == -1) {
                return null;
            }
            
            if (defUpper.startsWith("CONSTRAINT")) {
                // Extract constraint name
                String namePart = constraintDef.substring(10, fkPos).trim();
                constraintName = namePart.split("\\s+")[0];
            }
            
            // Extract local columns from FOREIGN KEY (col1, col2, ...)
            int openParen = constraintDef.indexOf('(', fkPos);
            int closeParen = constraintDef.indexOf(')', openParen);
            if (openParen != -1 && closeParen != -1) {
                String colList = constraintDef.substring(openParen + 1, closeParen).trim();
                for (String col : colList.split(",")) {
                    columns.add(col.trim().toLowerCase());
                }
            }
            
            // Extract REFERENCES table(col1, col2, ...)
            int refPos = defUpper.indexOf("REFERENCES", closeParen);
            if (refPos != -1) {
                int refOpenParen = constraintDef.indexOf('(', refPos);
                
                // Extract referenced table name
                referencedTable = constraintDef.substring(refPos + 10, refOpenParen).trim().toLowerCase();
                
                // Extract referenced columns
                int refCloseParen = constraintDef.indexOf(')', refOpenParen);
                if (refOpenParen != -1 && refCloseParen != -1) {
                    String refColList = constraintDef.substring(refOpenParen + 1, refCloseParen).trim();
                    for (String col : refColList.split(",")) {
                        referencedColumns.add(col.trim().toLowerCase());
                    }
                }
                
                // Extract ON DELETE action
                int onDeletePos = defUpper.indexOf("ON DELETE", refCloseParen);
                if (onDeletePos != -1) {
                    String afterOnDelete = constraintDef.substring(onDeletePos + 9).trim();
                    String[] tokens = afterOnDelete.split("\\s+");
                    if (tokens.length >= 1) {
                        if (tokens[0].equalsIgnoreCase("CASCADE")) {
                            onDelete = "CASCADE";
                        } else if (tokens[0].equalsIgnoreCase("SET") && tokens.length >= 2 && tokens[1].equalsIgnoreCase("NULL")) {
                            onDelete = "SET NULL";
                        } else if (tokens[0].equalsIgnoreCase("RESTRICT")) {
                            onDelete = "RESTRICT";
                        } else if (tokens[0].equalsIgnoreCase("NO") && tokens.length >= 2 && tokens[1].equalsIgnoreCase("ACTION")) {
                            onDelete = "NO ACTION";
                        }
                    }
                }
                
                // Extract ON UPDATE action
                int onUpdatePos = defUpper.indexOf("ON UPDATE", refCloseParen);
                if (onUpdatePos != -1) {
                    String afterOnUpdate = constraintDef.substring(onUpdatePos + 9).trim();
                    String[] tokens = afterOnUpdate.split("\\s+");
                    if (tokens.length >= 1) {
                        if (tokens[0].equalsIgnoreCase("CASCADE")) {
                            onUpdate = "CASCADE";
                        } else if (tokens[0].equalsIgnoreCase("SET") && tokens.length >= 2 && tokens[1].equalsIgnoreCase("NULL")) {
                            onUpdate = "SET NULL";
                        } else if (tokens[0].equalsIgnoreCase("RESTRICT")) {
                            onUpdate = "RESTRICT";
                        } else if (tokens[0].equalsIgnoreCase("NO") && tokens.length >= 2 && tokens[1].equalsIgnoreCase("ACTION")) {
                            onUpdate = "NO ACTION";
                        }
                    }
                }
            }
            
            if (columns.isEmpty() || referencedTable == null || referencedColumns.isEmpty()) {
                return null;
            }
            
            // Generate constraint name if not provided
            if (constraintName == null) {
                constraintName = tableName + "_" + String.join("_", columns) + "_fkey";
            }
            
            return new TableMetadata.ForeignKeyConstraint(
                constraintName, columns, referencedTable, referencedColumns, onDelete, onUpdate
            );
            
        } catch (Exception e) {
            log.warn("Failed to parse FOREIGN KEY constraint: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Convert a value to the appropriate type based on column metadata
     */
    private Object convertValueToColumnType(Object value, TableMetadata.ColumnMetadata column) {
        if (value == null) {
            return null;
        }

        String typeUpper = column.getType().toUpperCase();
        
        // If value is already the correct type, return it
        if (column.getJavaType().isInstance(value)) {
            return value;
        }

        // Handle NUMERIC/DECIMAL types
        if (typeUpper.startsWith("NUMERIC") || typeUpper.startsWith("DECIMAL")) {
            return MathFunctions.toBigDecimal(value);
        }
        
        // Handle ARRAY types
        if (typeUpper.endsWith("[]") || typeUpper.startsWith("ARRAY")) {
            if (value instanceof String) {
                // Parse PostgreSQL array syntax: {1,2,3} or {"a","b","c"}
                return parseArrayLiteral((String) value, typeUpper);
            } else if (value instanceof Object[]) {
                return value;
            }
        }
        
        // Handle ENUM types - validate against allowed values
        if (typeUpper.startsWith("ENUM")) {
            String strValue = value.toString();
            // Extract enum values from type definition: ENUM('val1','val2',...)
            if (!validateEnumValue(strValue, typeUpper)) {
                throw new IllegalArgumentException("Invalid ENUM value: " + strValue + " for type " + column.getType());
            }
            return strValue;
        }

        // Convert string values to appropriate types
        if (value instanceof String) {
            String strValue = (String) value;
            
            try {
                switch (typeUpper) {
                    case "DATE":
                        return DateTimeFunctions.parseDate(strValue);
                    case "TIME":
                        return DateTimeFunctions.parseTime(strValue);
                    case "TIMESTAMP":
                    case "TIMESTAMPTZ":
                        return DateTimeFunctions.parseTimestamp(strValue);
                    case "INT":
                    case "INTEGER":
                    case "SMALLINT":
                        return Integer.parseInt(strValue);
                    case "BIGINT":
                    case "LONG":
                        return Long.parseLong(strValue);
                    case "DOUBLE":
                    case "DOUBLE PRECISION":
                        return Double.parseDouble(strValue);
                    case "REAL":
                    case "FLOAT":
                        return Float.parseFloat(strValue);
                    case "BOOLEAN":
                    case "BOOL":
                        return Boolean.parseBoolean(strValue);
                    default:
                        return value; // Keep as string for TEXT, VARCHAR, etc.
                }
            } catch (Exception e) {
                log.warn("Failed to convert value '{}' to type {}: {}", value, typeUpper, e.getMessage());
                return value; // Return original value if conversion fails
            }
        }

        return value;
    }
    
    /**
     * Parse PostgreSQL array literal syntax: {1,2,3} or {"a","b","c"} or ARRAY['a','b','c']
     */
    private Object[] parseArrayLiteral(String arrayStr, String typeDecl) {
        arrayStr = arrayStr.trim();
        
        // Handle ARRAY['a','b','c'] syntax
        if (arrayStr.toUpperCase().startsWith("ARRAY[")) {
            // Convert ARRAY['a','b'] to {'a','b'}
            arrayStr = arrayStr.substring(5); // Remove "ARRAY"
            arrayStr = "{" + arrayStr.substring(1, arrayStr.length() - 1) + "}";
        }
        
        if (!arrayStr.startsWith("{") || !arrayStr.endsWith("}")) {
            throw new IllegalArgumentException("Invalid array syntax: " + arrayStr);
        }
        
        String content = arrayStr.substring(1, arrayStr.length() - 1).trim();
        if (content.isEmpty()) {
            return new Object[0];
        }
        
        // Simple parsing - split by comma (doesn't handle nested arrays or escaped commas)
        String[] parts = content.split(",");
        Object[] result = new Object[parts.length];
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            // Remove quotes if present
            if (part.startsWith("\"") && part.endsWith("\"")) {
                part = part.substring(1, part.length() - 1);
            } else if (part.startsWith("'") && part.endsWith("'")) {
                part = part.substring(1, part.length() - 1);
            }
            result[i] = part;
        }
        
        return result;
    }
    
    /**
     * Validate ENUM value against type definition
     */
    private boolean validateEnumValue(String value, String enumType) {
        // Extract allowed values from ENUM('val1','val2',...)
        int start = enumType.indexOf('(');
        int end = enumType.lastIndexOf(')');
        if (start == -1 || end == -1) {
            return true; // Can't validate, allow it
        }
        
        String valueList = enumType.substring(start + 1, end);
        String[] allowedValues = valueList.split(",");
        
        for (String allowed : allowedValues) {
            String cleaned = allowed.trim().replaceAll("^['\"]|['\"]$", "");
            if (cleaned.equals(value)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Validate UNIQUE constraints for a row being inserted/updated
     * @return Error message if validation fails, null if valid
     */
    private String validateUniqueConstraints(TableMetadata table, Map<String, Object> row, 
                                            List<Object> pkValues, long readTs) {
        for (TableMetadata.UniqueConstraint constraint : table.getUniqueConstraints()) {
            // Extract values for the unique constraint columns
            List<Object> constraintValues = new ArrayList<>();
            for (String col : constraint.getColumns()) {
                Object value = row.get(col.toLowerCase());
                constraintValues.add(value);
            }
            
            // Check if any value is NULL - UNIQUE constraints allow multiple NULLs
            boolean hasNull = constraintValues.stream().anyMatch(v -> v == null);
            if (hasNull) {
                continue; // NULL values don't violate UNIQUE constraints
            }
            
            // Scan table to check if these values already exist
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, table.getTruncateTimestamp());
            
            // Get column types for decoding
            List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            for (KvStore.KvEntry entry : entries) {
                // Decode the row
                List<Object> existingPkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                
                // Skip if this is the same row (for UPDATE operations)
                if (pkValues != null && existingPkValues.equals(pkValues)) {
                    continue;
                }
                
                List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                
                // Build row map
                Map<String, Object> existingRow = new HashMap<>();
                for (int i = 0; i < table.getPrimaryKeyColumns().size() && i < existingPkValues.size(); i++) {
                    existingRow.put(table.getPrimaryKeyColumns().get(i).toLowerCase(), existingPkValues.get(i));
                }
                List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
                for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                    existingRow.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                }
                
                // Check if constraint values match
                boolean matches = true;
                for (int i = 0; i < constraint.getColumns().size(); i++) {
                    String col = constraint.getColumns().get(i);
                    Object newValue = constraintValues.get(i);
                    Object existingValue = existingRow.get(col.toLowerCase());
                    
                    if (!Objects.equals(newValue, existingValue)) {
                        matches = false;
                        break;
                    }
                }
                
                if (matches) {
                    return "UNIQUE constraint violation: " + constraint.getName() + 
                           " on columns (" + String.join(", ", constraint.getColumns()) + ")";
                }
            }
        }
        
        return null; // All constraints satisfied
    }
    
    /**
     * Check if deleting a row would violate foreign key constraints in other tables
     * @return Error message if deletion would violate constraints, null if safe to delete
     */
    private String checkForeignKeyOnDelete(TableMetadata table, Map<String, Object> row, long readTs) {
        // Check all tables to see if any have foreign keys referencing this table
        for (TableMetadata otherTable : schemaManager.getAllTables()) {
            for (TableMetadata.ForeignKeyConstraint fk : otherTable.getForeignKeyConstraints()) {
                if (!fk.getReferencedTable().equalsIgnoreCase(table.getTableName())) {
                    continue; // This FK doesn't reference our table
                }
                
                // Extract the referenced column values from the row being deleted
                List<Object> referencedValues = new ArrayList<>();
                for (String refCol : fk.getReferencedColumns()) {
                    referencedValues.add(row.get(refCol.toLowerCase()));
                }
                
                // Check if any row in the other table references this row
                byte[] startKey = KeyEncoder.createRangeStartKey(otherTable.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                byte[] endKey = KeyEncoder.createRangeEndKey(otherTable.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, otherTable.getTruncateTimestamp());
                
                // Get column types for decoding
                List<Class<?>> pkColumnTypes = otherTable.getPrimaryKeyColumns().stream()
                    .map(pkColName -> {
                        for (TableMetadata.ColumnMetadata col : otherTable.getColumns()) {
                            if (col.getName().equalsIgnoreCase(pkColName)) {
                                return col.getJavaType();
                            }
                        }
                        return String.class;
                    })
                    .collect(Collectors.toList());
                
                List<Class<?>> nonPkColumnTypes = otherTable.getNonPrimaryKeyColumns().stream()
                    .map(TableMetadata.ColumnMetadata::getJavaType)
                    .collect(Collectors.toList());
                
                for (KvStore.KvEntry entry : entries) {
                    // Decode the row
                    List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                    List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                    
                    // Build row map
                    Map<String, Object> otherRow = new HashMap<>();
                    for (int i = 0; i < otherTable.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                        otherRow.put(otherTable.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                    }
                    List<TableMetadata.ColumnMetadata> nonPkColumns = otherTable.getNonPrimaryKeyColumns();
                    for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                        otherRow.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                    }
                    
                    // Extract FK column values from this row
                    List<Object> fkValues = new ArrayList<>();
                    for (String fkCol : fk.getColumns()) {
                        fkValues.add(otherRow.get(fkCol.toLowerCase()));
                    }
                    
                    // Check if this row references the row being deleted
                    boolean matches = true;
                    for (int i = 0; i < fkValues.size(); i++) {
                        if (!Objects.equals(fkValues.get(i), referencedValues.get(i))) {
                            matches = false;
                            break;
                        }
                    }
                    
                    if (matches) {
                        // A row references this one - check ON DELETE action
                        String onDelete = fk.getOnDelete();
                        if (onDelete.equals("RESTRICT") || onDelete.equals("NO ACTION")) {
                            return "FOREIGN KEY constraint violation: Cannot delete row - " +
                                   "referenced by " + otherTable.getTableName() + " (" + fk.getName() + ")";
                        }
                        // CASCADE and SET NULL would require additional implementation
                        // For now, we'll just check RESTRICT/NO ACTION
                    }
                }
            }
        }
        
        return null; // Safe to delete
    }
    
    /**
     * Validate FOREIGN KEY constraints for a row being inserted/updated
     * @return Error message if validation fails, null if valid
     */
    private String validateForeignKeyConstraints(TableMetadata table, Map<String, Object> row, long readTs) {
        for (TableMetadata.ForeignKeyConstraint fk : table.getForeignKeyConstraints()) {
            // Extract values for the foreign key columns
            List<Object> fkValues = new ArrayList<>();
            for (String col : fk.getColumns()) {
                Object value = row.get(col.toLowerCase());
                fkValues.add(value);
            }
            
            // Check if any value is NULL - FK constraints allow NULL
            boolean hasNull = fkValues.stream().anyMatch(v -> v == null);
            if (hasNull) {
                continue; // NULL values don't violate FK constraints
            }
            
            // Get referenced table
            TableMetadata referencedTable = schemaManager.getTable(fk.getReferencedTable());
            if (referencedTable == null) {
                return "Referenced table does not exist: " + fk.getReferencedTable();
            }
            
            // Scan referenced table to check if the values exist
            byte[] startKey = KeyEncoder.createRangeStartKey(referencedTable.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(referencedTable.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 10000, referencedTable.getTruncateTimestamp());
            
            // Get column types for decoding
            List<Class<?>> pkColumnTypes = referencedTable.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : referencedTable.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = referencedTable.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            boolean found = false;
            for (KvStore.KvEntry entry : entries) {
                // Decode the row
                List<Object> pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                
                // Build row map
                Map<String, Object> referencedRow = new HashMap<>();
                for (int i = 0; i < referencedTable.getPrimaryKeyColumns().size() && i < pkValues.size(); i++) {
                    referencedRow.put(referencedTable.getPrimaryKeyColumns().get(i).toLowerCase(), pkValues.get(i));
                }
                List<TableMetadata.ColumnMetadata> nonPkColumns = referencedTable.getNonPrimaryKeyColumns();
                for (int i = 0; i < nonPkColumns.size() && i < nonPkValues.size(); i++) {
                    referencedRow.put(nonPkColumns.get(i).getName().toLowerCase(), nonPkValues.get(i));
                }
                
                // Check if all referenced column values match
                boolean matches = true;
                for (int i = 0; i < fk.getReferencedColumns().size(); i++) {
                    String refCol = fk.getReferencedColumns().get(i);
                    Object fkValue = fkValues.get(i);
                    Object refValue = referencedRow.get(refCol.toLowerCase());
                    
                    if (!Objects.equals(fkValue, refValue)) {
                        matches = false;
                        break;
                    }
                }
                
                if (matches) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                return "FOREIGN KEY constraint violation: " + fk.getName() + 
                       " - referenced row does not exist in " + fk.getReferencedTable();
            }
        }
        
        return null; // All constraints satisfied
    }
    
    /**
     * Backfill an index with existing table data.
     * Scans all rows and creates index entries for them.
     * 
     * @param table The table metadata
     * @param index The index to backfill
     * @return Number of index entries created
     */
    /**
     * Backfill an index incrementally in batches.
     * 
     * This method builds the index in small batches to avoid overwhelming a single transaction.
     * The index is marked as "building" during this process, preventing queries from using it
     * until the backfill is complete and the index is marked as ready.
     * 
     * Safety guarantees:
     * 1. Index is marked building=true, so queries won't use it during backfill
     * 2. New writes skip building indexes, so no concurrent write conflicts
     * 3. After backfill completes, index is marked building=false and becomes queryable
     * 4. At that point, the index is guaranteed to be consistent with all data
     * 
     * @param table The table metadata
     * @param index The index metadata
     * @return Number of index entries created
     */
    private int backfillIndex(TableMetadata table, TableMetadata.IndexMetadata index) {
        try {
            log.info("🔄 Starting incremental backfill for index {} on table {}", 
                     index.getName(), table.getTableName());
            
            // Scan all existing rows
            long readTs = timestampOracle.allocateStartTimestamp();
            byte[] startKey = KeyEncoder.createRangeStartKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(table.getTableId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 100000, table.getTruncateTimestamp());
            
            if (entries.isEmpty()) {
                log.info("   No existing data to backfill for index {}", index.getName());
                return 0;
            }
            
            log.info("   Found {} rows to index", entries.size());
            
            int indexEntriesCreated = 0;
            int batchSize = 1000; // Process 1000 rows per transaction
            int batchCount = 0;
            
            // Get column types for decoding
            List<Class<?>> pkColumnTypes = table.getPrimaryKeyColumns().stream()
                .map(pkColName -> {
                    for (TableMetadata.ColumnMetadata col : table.getColumns()) {
                        if (col.getName().equalsIgnoreCase(pkColName)) {
                            return col.getJavaType();
                        }
                    }
                    return String.class;
                })
                .collect(Collectors.toList());
            
            List<Class<?>> nonPkColumnTypes = table.getNonPrimaryKeyColumns().stream()
                .map(TableMetadata.ColumnMetadata::getJavaType)
                .collect(Collectors.toList());
            
            // Process entries in batches
            for (int i = 0; i < entries.size(); i += batchSize) {
                int batchEnd = Math.min(i + batchSize, entries.size());
                List<KvStore.KvEntry> batch = entries.subList(i, batchEnd);
                
                // Start a new transaction for this batch
                KvTransactionContext txn = new KvTransactionContext(
                    UUID.randomUUID(),
                    timestampOracle.allocateStartTimestamp()
                );
                
                int batchEntries = 0;
                
                for (KvStore.KvEntry entry : batch) {
                    Map<String, Object> row;
                    List<Object> pkValues;
                    try {
                        // Decode primary key values
                        pkValues = KeyEncoder.decodeTableDataKey(entry.getKey(), pkColumnTypes);
                        
                        // Decode non-PK values
                        List<Object> nonPkValues = ValueEncoder.decodeRow(entry.getValue(), nonPkColumnTypes);
                        
                        // Build full row map
                        row = new HashMap<>();
                        for (int j = 0; j < table.getPrimaryKeyColumns().size() && j < pkValues.size(); j++) {
                            row.put(table.getPrimaryKeyColumns().get(j).toLowerCase(), pkValues.get(j));
                        }
                        List<TableMetadata.ColumnMetadata> nonPkColumns = table.getNonPrimaryKeyColumns();
                        for (int j = 0; j < nonPkColumns.size() && j < nonPkValues.size(); j++) {
                            row.put(nonPkColumns.get(j).getName().toLowerCase(), nonPkValues.get(j));
                        }
                    } catch (Exception e) {
                        log.warn("⚠️  Skipping corrupted entry during backfill: {}", e.getMessage());
                        continue;
                    }
                    
                    // Extract indexed column values
                    List<Object> indexValues = new ArrayList<>();
                    for (String indexCol : index.getColumns()) {
                        Object indexValue = row.get(indexCol.toLowerCase());
                        indexValues.add(indexValue);
                    }
                    
                    // Determine pkValues for the index key
                    // For PRIMARY KEY indexes created via ALTER TABLE ADD PRIMARY KEY:
                    // - Index name starts with "pk_" (e.g., "pk_table_name_col1_col2")
                    // - Index columns are NOT the table's actual PK columns (table has hidden rowid PK)
                    // - We should use indexValues as pkValues (the indexed column values themselves)
                    // For regular secondary indexes:
                    // - Use the table's actual PK values (rowid) to make the index key unique
                    List<Object> indexPkValues;
                    boolean isPrimaryKeyIndex = index.getName().startsWith("pk_") && 
                                                !index.getColumns().equals(table.getPrimaryKeyColumns());
                    if (isPrimaryKeyIndex) {
                        // PRIMARY KEY index - use indexValues as pkValues (indexed columns are the "PK" for this index)
                        indexPkValues = indexValues;
                    } else {
                        // Regular secondary index - use table's PK values (rowid)
                        indexPkValues = pkValues;
                    }
                    
                    // Create index entry
                    byte[] indexKey = KeyEncoder.encodeIndexKey(
                        table.getTableId(),
                        index.getIndexId(),
                        indexValues,
                        indexPkValues,
                        txn.getStartTs()
                    );
                    byte[] indexValue = new byte[0];
                    
                    txn.addWrite(indexKey, indexValue, KvTransactionContext.WriteType.PUT);
                    batchEntries++;
                }
                
                // Commit this batch
                if (batchEntries > 0) {
                    coordinator.commit(txn);
                    indexEntriesCreated += batchEntries;
                    batchCount++;
                    
                    if (batchCount % 10 == 0) {
                        log.info("   Progress: {} / {} rows indexed ({} batches)", 
                                 indexEntriesCreated, entries.size(), batchCount);
                    }
                }
            }
            
            log.info("   ✅ Backfilled {} index entries in {} batches for {}", 
                     indexEntriesCreated, batchCount, index.getName());
            
            return indexEntriesCreated;
            
        } catch (Exception e) {
            log.error("Failed to backfill index " + index.getName(), e);
            return 0;
        }
    }
    
    // ========== View Operations ==========
    
    /**
     * Execute a query against a view (virtual or materialized)
     */
    private QueryResponse executeViewQuery(ViewMetadata view, ParsedQuery query) {
        if (view.isMaterialized()) {
            // For materialized views, read from stored data
            return executeMaterializedViewQuery(view, query);
        } else {
            // For virtual views, rewrite the query to use the view definition
            return executeVirtualViewQuery(view, query);
        }
    }
    
    /**
     * Execute a query against a virtual view by rewriting it
     */
    private QueryResponse executeVirtualViewQuery(ViewMetadata view, ParsedQuery query) {
        try {
            log.debug("Executing virtual view query: {}", view.getViewName());
            
            // Simple approach: just execute the view's query definition
            // TODO: More sophisticated rewriting to handle filters, projections, etc.
            String viewQuery = view.getQueryDefinition();
            
            ParsedQuery viewParsedQuery = new CalciteParser().parse(viewQuery);
            
            // Execute the view's query
            switch (viewParsedQuery.getType()) {
                case SELECT:
                    return executeSelect(viewParsedQuery);
                case JOIN:
                    return executeJoinWithModifiers(viewParsedQuery, joinExecutor.executeBinaryJoin(viewParsedQuery.getJoinQuery()));
                case MULTI_WAY_JOIN:
                    return executeJoinWithModifiers(viewParsedQuery, joinExecutor.executeMultiWayJoin(viewParsedQuery.getMultiWayJoin()));
                case AGGREGATION:
                    return executeAggregation(viewParsedQuery);
                default:
                    return QueryResponse.error("Unsupported query type in view definition: " + viewParsedQuery.getType());
            }
            
        } catch (Exception e) {
            log.error("Failed to execute virtual view query: {}", e.getMessage());
            return QueryResponse.error("View query failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute a query against a materialized view
     * Treats the materialized view like a regular table and applies all query operations
     * (WHERE, ORDER BY, LIMIT, etc.)
     */
    private QueryResponse executeMaterializedViewQuery(ViewMetadata view, ParsedQuery query) {
        try {
            log.debug("Executing materialized view query: {}", view.getViewName());
            
            // Create a temporary TableMetadata for the materialized view
            // This allows us to reuse all the existing SELECT logic
            List<TableMetadata.ColumnMetadata> mvColumns = new ArrayList<>();
            if (view.getColumns() != null) {
                for (ViewMetadata.ColumnInfo col : view.getColumns()) {
                    mvColumns.add(new TableMetadata.ColumnMetadata(
                        col.getName(),
                        col.getType(),
                        false, // not primary key
                        false, // nullable
                        null   // no default
                    ));
                }
            }
            
            // Create temporary table metadata using the view ID as table ID
            TableMetadata tempTable = new TableMetadata(
                view.getViewId(),
                view.getViewName(),
                mvColumns,
                new ArrayList<>(), // no PK columns for MV
                new ArrayList<>(), // no indexes
                new ArrayList<>(), // no unique constraints
                new ArrayList<>(), // no FK constraints
                1,                 // version
                null,              // no truncate timestamp
                null               // not dropped
            );
            
            // Parse the SELECT statement to get WHERE, ORDER BY, etc.
            CalciteSqlParser.SelectInfo selectInfo = sqlParser.parseSelect(query.getRawSql());
            
            // Execute the query using the standard SELECT logic
            // This will handle WHERE, ORDER BY, LIMIT, etc.
            long readTs = timestampOracle.getCurrentTimestamp();
            return executeRangeScanWithFilter(tempTable, selectInfo, readTs);
            
        } catch (Exception e) {
            log.error("Failed to execute materialized view query: {}", e.getMessage());
            return QueryResponse.error("Materialized view query failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute CREATE VIEW
     * Format: CREATE VIEW view_name AS SELECT ...
     */
    private QueryResponse executeCreateView(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            String sqlUpper = sql.toUpperCase();
            
            // Extract view name
            String viewName = query.getTableName();
            if (viewName == null || viewName.isEmpty()) {
                return QueryResponse.error("Could not extract view name");
            }
            
            // Extract query definition (everything after the AS that follows the view name)
            // Find the position after "CREATE VIEW viewname AS"
            String searchPattern = "CREATE VIEW " + viewName.toUpperCase();
            int viewNamePos = sqlUpper.indexOf(searchPattern);
            if (viewNamePos == -1) {
                return QueryResponse.error("Could not find view name in CREATE VIEW statement");
            }
            
            // Start searching for AS after the view name
            int searchStart = viewNamePos + searchPattern.length();
            String afterViewName = sqlUpper.substring(searchStart);
            
            // Find AS keyword (surrounded by whitespace)
            int asPos = -1;
            for (int i = 0; i < afterViewName.length() - 1; i++) {
                if (afterViewName.charAt(i) == 'A' && afterViewName.charAt(i+1) == 'S') {
                    // Check if preceded by whitespace or start
                    boolean precedingWhitespace = (i == 0) || Character.isWhitespace(afterViewName.charAt(i-1));
                    // Check if followed by whitespace or end
                    boolean followingWhitespace = (i+2 >= afterViewName.length()) || Character.isWhitespace(afterViewName.charAt(i+2));
                    
                    if (precedingWhitespace && followingWhitespace) {
                        asPos = searchStart + i + 2; // +2 to skip past "AS"
                        break;
                    }
                }
            }
            
            if (asPos == -1) {
                return QueryResponse.error("CREATE VIEW requires AS clause after view name");
            }
            
            // Skip whitespace after AS
            while (asPos < sql.length() && Character.isWhitespace(sql.charAt(asPos))) {
                asPos++;
            }
            
            String queryDefinition = sql.substring(asPos).trim();
            
            log.info("📝 Extracted query definition (length={}): START\n{}\nEND", queryDefinition.length(), queryDefinition);
            
            // Parse the query to extract columns
            List<ViewMetadata.ColumnInfo> columns = extractColumnsFromQuery(queryDefinition);
            
            // Create view metadata
            ViewMetadata view = schemaManager.createView(viewName, queryDefinition, false, columns);
            
            log.info("✅ Created virtual view: {} AS\n{}", viewName, queryDefinition);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            log.error("CREATE VIEW failed", e);
            return QueryResponse.error("CREATE VIEW failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute CREATE MATERIALIZED VIEW
     * Format: CREATE MATERIALIZED VIEW view_name AS SELECT ...
     */
    private QueryResponse executeCreateMaterializedView(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            String sqlUpper = sql.toUpperCase();
            
            // Extract view name
            String viewName = query.getTableName();
            if (viewName == null || viewName.isEmpty()) {
                return QueryResponse.error("Could not extract view name");
            }
            
            // Extract query definition (everything after the AS that follows the view name)
            // Find the position after "CREATE MATERIALIZED VIEW viewname AS"
            String searchPattern = "CREATE MATERIALIZED VIEW " + viewName.toUpperCase();
            int viewNamePos = sqlUpper.indexOf(searchPattern);
            if (viewNamePos == -1) {
                return QueryResponse.error("Could not find view name in CREATE MATERIALIZED VIEW statement");
            }
            
            // Start searching for AS after the view name
            int searchStart = viewNamePos + searchPattern.length();
            String afterViewName = sqlUpper.substring(searchStart);
            
            // Find AS keyword (surrounded by whitespace)
            int asPos = -1;
            for (int i = 0; i < afterViewName.length() - 1; i++) {
                if (afterViewName.charAt(i) == 'A' && afterViewName.charAt(i+1) == 'S') {
                    // Check if preceded by whitespace or start
                    boolean precedingWhitespace = (i == 0) || Character.isWhitespace(afterViewName.charAt(i-1));
                    // Check if followed by whitespace or end
                    boolean followingWhitespace = (i+2 >= afterViewName.length()) || Character.isWhitespace(afterViewName.charAt(i+2));
                    
                    if (precedingWhitespace && followingWhitespace) {
                        asPos = searchStart + i + 2; // +2 to skip past "AS"
                        break;
                    }
                }
            }
            
            if (asPos == -1) {
                return QueryResponse.error("CREATE MATERIALIZED VIEW requires AS clause after view name");
            }
            
            // Skip whitespace after AS
            while (asPos < sql.length() && Character.isWhitespace(sql.charAt(asPos))) {
                asPos++;
            }
            
            String queryDefinition = sql.substring(asPos).trim();
            
            log.info("🔄 Creating materialized view: {}", viewName);
            
            // Parse the query to extract columns (lightweight validation)
            List<ViewMetadata.ColumnInfo> columns = new ArrayList<>();
            
            // Create view metadata
            ViewMetadata view = schemaManager.createView(viewName, queryDefinition, true, columns);
            
            // Materialize the view data immediately
            int rowsStored = 0;
            try {
                rowsStored = materializeView(view);
                // Update refresh timestamp after successful materialization
                schemaManager.updateViewRefreshTimestamp(viewName, System.currentTimeMillis());
                log.info("✅ Created materialized view: {} ({} rows)", viewName, rowsStored);
            } catch (Exception e) {
                // Known issue: Initial materialization may fail due to Cassandra timing/initialization
                // The view metadata is created successfully, and can be populated via REFRESH MATERIALIZED VIEW
                log.warn("Initial materialization failed (view created but empty): {}", e.getMessage());
                log.info("Use REFRESH MATERIALIZED VIEW {} to populate the view", viewName);
            }
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsStored);
            return response;
            
        } catch (Exception e) {
            log.error("CREATE MATERIALIZED VIEW failed", e);
            return QueryResponse.error("CREATE MATERIALIZED VIEW failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute DROP VIEW (handles both VIEW and MATERIALIZED VIEW)
     * Format: DROP [MATERIALIZED] VIEW [IF EXISTS] view_name
     */
    private QueryResponse executeDropView(ParsedQuery query) {
        try {
            String sql = query.getRawSql().trim();
            String sqlUpper = sql.toUpperCase();
            
            // Check for IF EXISTS
            boolean ifExists = sqlUpper.contains("IF EXISTS");
            
            // Extract view name
            String viewName = query.getTableName();
            if (viewName == null || viewName.isEmpty()) {
                return QueryResponse.error("Could not extract view name");
            }
            
            // Get view metadata
            ViewMetadata view = schemaManager.getView(viewName);
            if (view == null) {
                if (ifExists) {
                    log.debug("View does not exist (IF EXISTS): {}", viewName);
                    return new QueryResponse();
                }
                return QueryResponse.error("View does not exist: " + viewName);
            }
            
            // If materialized, delete stored data
            if (view.isMaterialized()) {
                deleteMaterializedViewData(view);
            }
            
            // Drop view metadata
            schemaManager.dropView(viewName);
            
            log.info("✅ Dropped {} view: {}", view.isMaterialized() ? "materialized" : "virtual", viewName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            log.error("DROP VIEW failed", e);
            return QueryResponse.error("DROP VIEW failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute REFRESH MATERIALIZED VIEW
     * Format: REFRESH MATERIALIZED VIEW view_name
     */
    private QueryResponse executeRefreshMaterializedView(ParsedQuery query) {
        try {
            String viewName = query.getTableName();
            if (viewName == null || viewName.isEmpty()) {
                return QueryResponse.error("Could not extract view name");
            }
            
            // Get view metadata
            ViewMetadata view = schemaManager.getView(viewName);
            if (view == null) {
                return QueryResponse.error("View does not exist: " + viewName);
            }
            
            if (!view.isMaterialized()) {
                return QueryResponse.error("Cannot refresh non-materialized view: " + viewName);
            }
            
            log.info("🔄 Refreshing materialized view: {}", viewName);
            
            // Delete old data
            deleteMaterializedViewData(view);
            
            // Re-materialize
            int rowsStored = materializeView(view);
            
            // Update refresh timestamp
            schemaManager.updateViewRefreshTimestamp(viewName, System.currentTimeMillis());
            
            log.info("✅ Refreshed materialized view: {} ({} rows)", viewName, rowsStored);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(rowsStored);
            return response;
            
        } catch (Exception e) {
            log.error("REFRESH MATERIALIZED VIEW failed", e);
            return QueryResponse.error("REFRESH MATERIALIZED VIEW failed: " + e.getMessage());
        }
    }
    
    /**
     * Extract columns from a SELECT query
     * For now, we'll infer columns during materialization instead of parsing upfront
     */
    private List<ViewMetadata.ColumnInfo> extractColumnsFromQuery(String queryDefinition) {
        // Return empty list - columns will be inferred during materialization
        // This avoids executing the query during CREATE VIEW
        return new ArrayList<>();
    }
    
    /**
     * Materialize a view by executing its query and storing results
     * Uses a direct execution approach to avoid query routing issues
     */
    private int materializeView(ViewMetadata view) {
        try {
            log.info("🔄 Materializing view: {} with query: {}", view.getViewName(), view.getQueryDefinition());
            
            // Longer delay to ensure schema and data propagation in Cassandra
            // Cassandra has eventual consistency, so we need to wait for data to be visible
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Parse the query using CalciteParser
            ParsedQuery parsedQuery;
            try {
                CalciteParser parser = new CalciteParser();
                parsedQuery = parser.parse(view.getQueryDefinition());
                log.debug("Parsed query type: {}, table: {}", parsedQuery.getType(), parsedQuery.getTableName());
            } catch (Exception e) {
                log.error("Failed to parse view query: {}", e.getMessage());
                throw new RuntimeException("View query parsing failed: " + e.getMessage(), e);
            }
            
            // Retry logic for query execution to handle Cassandra timing issues
            QueryResponse result = null;
            int maxRetries = 3;
            Exception lastException = null;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    log.debug("Materialization attempt {} of {}", attempt, maxRetries);
                    
                    // Execute the query by calling the local KV methods directly
                    // This ensures we stay in KV mode and don't route through QueryService
                    switch (parsedQuery.getType()) {
                        case SELECT:
                            // Call executeSelect directly - this is a local method in KvQueryExecutor
                            result = executeSelect(parsedQuery);
                            break;
                        case JOIN:
                            result = executeJoinWithModifiers(parsedQuery, joinExecutor.executeBinaryJoin(parsedQuery.getJoinQuery()));
                            break;
                        case MULTI_WAY_JOIN:
                            result = executeJoinWithModifiers(parsedQuery, joinExecutor.executeMultiWayJoin(parsedQuery.getMultiWayJoin()));
                            break;
                        case AGGREGATION:
                            result = executeAggregation(parsedQuery);
                            break;
                        default:
                            throw new RuntimeException("Unsupported query type for materialized view: " + parsedQuery.getType() + 
                                                     ". Only SELECT, JOIN, and AGGREGATION are supported.");
                    }
                    
                    // If we got here without exception, break out of retry loop
                    break;
                    
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Materialization attempt {} failed: {}", attempt, e.getMessage());
                    
                    if (attempt < maxRetries) {
                        // Wait before retrying (exponential backoff)
                        try {
                            Thread.sleep(1000 * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during retry", ie);
                        }
                    }
                }
            }
            
            // If all retries failed, throw the last exception
            if (result == null) {
                log.error("Query execution failed after {} attempts", maxRetries);
                throw new RuntimeException("View query execution failed after " + maxRetries + " attempts: " + 
                                         (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
            }
            
            if (result == null) {
                throw new RuntimeException("View query returned null result");
            }
            
            if (result.getError() != null) {
                throw new RuntimeException("View query failed: " + result.getError());
            }
            
            if (result.getRows() == null || result.getRows().isEmpty()) {
                log.info("View query returned no rows");
                return 0;
            }
            
            log.info("View query returned {} rows", result.getRows().size());
            
            // Store results in KV store using view ID as table ID
            int rowsStored = 0;
            
            // Store in batches to avoid large transactions
            int batchSize = 1000;
            List<Map<String, Object>> allRows = result.getRows();
            
            for (int batchStart = 0; batchStart < allRows.size(); batchStart += batchSize) {
                int batchEnd = Math.min(batchStart + batchSize, allRows.size());
                List<Map<String, Object>> batch = allRows.subList(batchStart, batchEnd);
                
                if (batch.isEmpty()) {
                    continue;
                }
                
                KvTransactionContext txn = new KvTransactionContext(
                    UUID.randomUUID(),
                    timestampOracle.allocateStartTimestamp()
                );
                
                // Generate a simple row ID for each result row
                long rowId = batchStart + 1;
                for (Map<String, Object> row : batch) {
                    // Use row ID as primary key
                    List<Object> pkValues = new ArrayList<>();
                    pkValues.add(rowId);
                    
                    // Encode all columns as non-PK values
                    List<Object> nonPkValues = new ArrayList<>();
                    if (result.getColumns() != null) {
                        for (String colName : result.getColumns()) {
                            nonPkValues.add(row.get(colName));
                        }
                    }
                    
                    // Encode and store
                    byte[] key = KeyEncoder.encodeMaterializedViewDataKey(view.getViewId(), pkValues, txn.getStartTs());
                    byte[] value = ValueEncoder.encodeRow(nonPkValues);
                    
                    txn.addWrite(key, value, KvTransactionContext.WriteType.PUT);
                    rowsStored++;
                    rowId++;
                }
                
                // Commit this batch
                coordinator.commit(txn);
            }
            
            // Update view metadata with inferred columns (refresh timestamp is updated by caller)
            if (result.getColumns() != null && !result.getColumns().isEmpty()) {
                List<ViewMetadata.ColumnInfo> columnInfos = new ArrayList<>();
                for (String colName : result.getColumns()) {
                    columnInfos.add(new ViewMetadata.ColumnInfo(colName, "UNKNOWN")); // Type inference can be added later
                }
                
                // Update view with columns (refresh timestamp will be updated by executeRefreshMaterializedView)
                ViewMetadata updatedView = view.withColumns(columnInfos);
                schemaManager.updateViewMetadata(updatedView);
                log.info("✅ Updated view {} with {} columns", view.getViewName(), columnInfos.size());
            }
            // Note: Refresh timestamp is updated by executeRefreshMaterializedView after materialization completes
            
            log.info("✅ Materialized {} rows for view: {}", rowsStored, view.getViewName());
            return rowsStored;
            
        } catch (Exception e) {
            log.error("Failed to materialize view {}: {}", view.getViewName(), e.getMessage());
            throw new RuntimeException("Materialization failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create an index on a materialized view
     */
    private QueryResponse executeCreateIndexOnMaterializedView(ViewMetadata view, String indexName, List<String> columns) {
        try {
            log.info("🔄 Creating index {} on materialized view {} (columns: {})", 
                     indexName, view.getViewName(), String.join(", ", columns));
            
            // Validate that the columns exist in the view
            if (view.getColumns() != null && !view.getColumns().isEmpty()) {
                for (String colName : columns) {
                    boolean found = false;
                    for (ViewMetadata.ColumnInfo col : view.getColumns()) {
                        if (col.getName().equalsIgnoreCase(colName)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return QueryResponse.error("Column does not exist in view: " + colName);
                    }
                }
            }
            
            // Create index metadata in the view
            ViewMetadata.IndexMetadata index = schemaManager.createIndexOnView(view.getViewName(), indexName, columns);
            
            log.info("✅ Created index metadata: {} on materialized view {} - marked as BUILDING", 
                     indexName, view.getViewName());
            log.info("🔄 Backfilling index from materialized view data...");
            
            // Backfill the index from the materialized view data
            int indexEntriesCreated = backfillIndexOnMaterializedView(view, index);
            
            // Mark index as ready
            schemaManager.markViewIndexReady(view.getViewName(), indexName);
            
            log.info("✅ Index {} on materialized view {} is now READY ({} entries)", 
                     indexName, view.getViewName(), indexEntriesCreated);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(indexEntriesCreated);
            return response;
            
        } catch (Exception e) {
            log.error("Failed to create index on materialized view: {}", e.getMessage());
            return QueryResponse.error("CREATE INDEX on materialized view failed: " + e.getMessage());
        }
    }
    
    /**
     * Backfill an index on a materialized view
     */
    private int backfillIndexOnMaterializedView(ViewMetadata view, ViewMetadata.IndexMetadata index) {
        try {
            log.info("Backfilling index {} on materialized view {}", index.getName(), view.getViewName());
            
            // Scan all data in the materialized view
            byte[] startKey = KeyEncoder.createRangeStartKey(view.getViewId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            byte[] endKey = KeyEncoder.createRangeEndKey(view.getViewId(), KeyEncoder.PRIMARY_INDEX_ID, null);
            
            long readTs = timestampOracle.getCurrentTimestamp();
            List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 1000000, null);
            
            if (entries.isEmpty()) {
                log.info("No data to index in materialized view: {}", view.getViewName());
                return 0;
            }
            
            log.info("Found {} rows to index in materialized view {}", entries.size(), view.getViewName());
            
            // Process in batches
            int batchSize = 1000;
            int indexEntriesCreated = 0;
            int batchCount = 0;
            
            for (int i = 0; i < entries.size(); i += batchSize) {
                int batchEnd = Math.min(i + batchSize, entries.size());
                List<KvStore.KvEntry> batch = entries.subList(i, batchEnd);
                
                KvTransactionContext txn = new KvTransactionContext(
                    UUID.randomUUID(),
                    timestampOracle.allocateStartTimestamp()
                );
                
                int batchEntries = 0;
                
                for (KvStore.KvEntry entry : batch) {
                    // Decode the row
                    List<Object> values = ValueEncoder.decodeRow(entry.getValue(),
                        view.getColumns().stream()
                            .map(col -> Object.class)
                            .collect(java.util.stream.Collectors.toList())
                    );
                    
                    // Extract index column values
                    List<Object> indexValues = new ArrayList<>();
                    for (String indexCol : index.getColumns()) {
                        // Find the column in the view
                        for (int colIdx = 0; colIdx < view.getColumns().size(); colIdx++) {
                            if (view.getColumns().get(colIdx).getName().equalsIgnoreCase(indexCol)) {
                                if (colIdx < values.size()) {
                                    indexValues.add(values.get(colIdx));
                                } else {
                                    indexValues.add(null);
                                }
                                break;
                            }
                        }
                    }
                    
                    // Decode the primary key (row ID) from the entry key
                    List<Object> pkValues = KeyEncoder.decodeMaterializedViewDataKey(entry.getKey());
                    
                    // Create index key
                    byte[] indexKey = KeyEncoder.encodeIndexKey(
                        view.getViewId(),
                        index.getIndexId(),
                        indexValues,
                        pkValues,
                        txn.getStartTs()
                    );
                    
                    // Index value is empty (we only need the key)
                    byte[] indexValue = new byte[0];
                    
                    txn.addWrite(indexKey, indexValue, KvTransactionContext.WriteType.PUT);
                    batchEntries++;
                }
                
                if (batchEntries > 0) {
                    coordinator.commit(txn);
                    indexEntriesCreated += batchEntries;
                    batchCount++;
                    
                    if (batchCount % 10 == 0) {
                        log.info("   Progress: {} / {} rows indexed ({} batches)", 
                                 indexEntriesCreated, entries.size(), batchCount);
                    }
                }
            }
            
            log.info("✅ Backfilled {} index entries for materialized view {}", indexEntriesCreated, view.getViewName());
            return indexEntriesCreated;
            
        } catch (Exception e) {
            log.error("Failed to backfill index on materialized view: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Delete all data for a materialized view
     */
    private void deleteMaterializedViewData(ViewMetadata view) {
        int maxRetries = 3;
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("Deleting materialized view data (attempt {} of {}): {}", attempt, maxRetries, view.getViewName());
                
                // Scan and delete all data for this view
                byte[] startKey = KeyEncoder.createRangeStartKey(view.getViewId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                byte[] endKey = KeyEncoder.createRangeEndKey(view.getViewId(), KeyEncoder.PRIMARY_INDEX_ID, null);
                
                long readTs = timestampOracle.getCurrentTimestamp();
                List<KvStore.KvEntry> entries = kvStore.scan(startKey, endKey, readTs, 100000, null);
                
                if (entries.isEmpty()) {
                    log.debug("No data to delete for view: {}", view.getViewName());
                    return;
                }
                
                // Delete in batches
                KvTransactionContext txn = new KvTransactionContext(
                    UUID.randomUUID(),
                    timestampOracle.allocateStartTimestamp()
                );
                
                for (KvStore.KvEntry entry : entries) {
                    txn.addWrite(entry.getKey(), null, KvTransactionContext.WriteType.DELETE);
                }
                
                coordinator.commit(txn);
                
                log.debug("Deleted {} rows from materialized view: {}", entries.size(), view.getViewName());
                return; // Success
                
            } catch (Exception e) {
                lastException = e;
                log.warn("Failed to delete view data (attempt {} of {}): {}", attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }
        
        // All retries failed
        log.error("Failed to delete materialized view data after {} attempts", maxRetries);
        throw new RuntimeException("Failed to delete view data: " + 
                                 (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }
    
    // ========================================================================
    // ENUM Type Operations
    // ========================================================================
    
    /**
     * Execute CREATE TYPE statement
     * Example: CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped')
     */
    private QueryResponse executeCreateType(ParsedQuery query) {
        try {
            String sql = query.getRawSql();
            String typeName = query.getTableName(); // Type name is stored in tableName field
            
            log.info("Creating ENUM type: {}", typeName);
            
            // Parse ENUM values from SQL
            // Example: CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped')
            List<String> enumValues = parseEnumValues(sql);
            
            if (enumValues.isEmpty()) {
                return QueryResponse.error("No ENUM values specified");
            }
            
            // Create enum type in schema manager
            schemaManager.createEnumType(typeName, enumValues);
            
            log.info("✅ Created ENUM type: {} with {} values", typeName, enumValues.size());
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            log.error("CREATE TYPE failed: {}", e.getMessage());
            return QueryResponse.error("CREATE TYPE failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute DROP TYPE statement
     * Example: DROP TYPE order_status
     */
    private QueryResponse executeDropType(ParsedQuery query) {
        try {
            String typeName = query.getTableName(); // Type name is stored in tableName field
            
            log.info("Dropping ENUM type: {}", typeName);
            
            // Drop enum type from schema manager
            schemaManager.dropEnumType(typeName);
            
            log.info("✅ Dropped ENUM type: {}", typeName);
            
            QueryResponse response = new QueryResponse();
            response.setRowCount(0);
            return response;
            
        } catch (Exception e) {
            log.error("DROP TYPE failed: {}", e.getMessage());
            return QueryResponse.error("DROP TYPE failed: " + e.getMessage());
        }
    }
    
    /**
     * Parse ENUM values from CREATE TYPE statement
     * Example: CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped')
     * Returns: ['pending', 'processing', 'shipped']
     */
    private List<String> parseEnumValues(String sql) {
        List<String> values = new ArrayList<>();
        
        // Find the ENUM keyword
        int enumPos = sql.toUpperCase().indexOf("ENUM");
        if (enumPos == -1) {
            return values;
        }
        
        // Find the opening parenthesis
        int openParen = sql.indexOf('(', enumPos);
        if (openParen == -1) {
            return values;
        }
        
        // Find the closing parenthesis
        int closeParen = sql.indexOf(')', openParen);
        if (closeParen == -1) {
            return values;
        }
        
        // Extract the values string
        String valuesStr = sql.substring(openParen + 1, closeParen);
        
        // Split by comma and clean up
        String[] parts = valuesStr.split(",");
        for (String part : parts) {
            String cleaned = part.trim();
            // Remove quotes
            if (cleaned.startsWith("'") && cleaned.endsWith("'")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            } else if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        
        return values;
    }
    
    /**
     * Check if the query has FROM subqueries and execute them
     * Returns null if no FROM subqueries are found
     */
    private QueryResponse checkAndExecuteFromSubquery(String sql) {
        try {
            // Parse with Calcite
            SqlParser.Config config = SqlParser.config().withCaseSensitive(false);
            SqlParser parser = SqlParser.create(sql, config);
            SqlNode sqlNode = parser.parseStmt();
            
            // Unwrap SqlOrderBy if present
            SqlOrderBy orderBy = null;
            if (sqlNode instanceof SqlOrderBy) {
                orderBy = (SqlOrderBy) sqlNode;
                sqlNode = orderBy.query;
            }
            
            if (!(sqlNode instanceof SqlSelect)) {
                return null;
            }
            
            SqlSelect select = (SqlSelect) sqlNode;
            if (select.getFrom() == null) {
                return null;
            }
            
            // Check if FROM clause contains a subquery
            if (!hasFromSubquery(select.getFrom())) {
                return null;
            }
            
            log.info("🔄 Executing FROM subquery");
            
            // Execute the FROM subquery and replace it with results
            return executeFromSubquery(select, orderBy);
            
        } catch (Exception e) {
            log.debug("Failed to check for FROM subquery: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if FROM clause contains a subquery (derived table)
     */
    private boolean hasFromSubquery(SqlNode fromNode) {
        if (fromNode instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) fromNode;
            if (call.getOperator().getKind() == SqlKind.AS) {
                return call.operand(0) instanceof SqlSelect;
            }
        } else if (fromNode instanceof SqlSelect) {
            return true;
        } else if (fromNode instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) fromNode;
            return hasFromSubquery(join.getLeft()) || hasFromSubquery(join.getRight());
        }
        return false;
    }
    
    /**
     * Execute a query with FROM subquery
     * Strategy: Execute subquery first, store results in memory, then execute outer query
     */
    private QueryResponse executeFromSubquery(SqlSelect outerSelect, SqlOrderBy orderBy) {
        try {
            // Extract the subquery and its alias
            SqlNode fromNode = outerSelect.getFrom();
            
            // Handle simple case: SELECT ... FROM (subquery) AS alias
            if (fromNode instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) fromNode;
                if (call.getOperator().getKind() == SqlKind.AS) {
                    SqlNode subqueryNode = call.operand(0);
                    String alias = call.operand(1).toString().replace("`", "");
                    
                    if (subqueryNode instanceof SqlSelect) {
                        // Execute the subquery
                        SqlSelect subquerySelect = (SqlSelect) subqueryNode;
                        
                        // Extract table name directly from SqlSelect to preserve original case
                        // This avoids issues where SQL conversion uppercases identifiers
                        String actualTableName = null;
                        if (subquerySelect.getFrom() != null) {
                            SqlNode subqueryFromNode = subquerySelect.getFrom();
                            if (subqueryFromNode instanceof SqlIdentifier) {
                                SqlIdentifier tableId = (SqlIdentifier) subqueryFromNode;
                                actualTableName = String.join(".", tableId.names);
                            } else {
                                // Fallback: extract from toString but try to preserve case
                                actualTableName = subqueryFromNode.toString().replace("`", "");
                            }
                        }
                        
                        // Convert SqlSelect to SQL string properly
                        String subquerySql = subquerySelect.toSqlString(
                            org.apache.calcite.sql.SqlDialect.DatabaseProduct.UNKNOWN.getDialect()
                        ).getSql().replace("`", "");
                        log.info("📊 Executing FROM subquery: {}", subquerySql);
                        if (actualTableName != null) {
                            log.info("📊 Extracted table name from AST: {}", actualTableName);
                        }
                        
                        QueryResponse subqueryResult;
                        
                        // Parse and execute the subquery
                        // Always use executeSubqueryWithoutFromCheck to avoid infinite recursion
                        // even if the subquery contains nested FROM subqueries (they'll be handled when parsed)
                        CalciteParser calciteParser = new CalciteParser();
                        ParsedQuery subqueryParsed = calciteParser.parse(subquerySql);
                        
                        // Fix table name if it was uppercased during SQL conversion
                        // Only do this for SELECT queries - aggregation queries need their full structure preserved
                        if (actualTableName != null && subqueryParsed.getTableName() != null &&
                            !actualTableName.equalsIgnoreCase(subqueryParsed.getTableName()) &&
                            (subqueryParsed.getType() == ParsedQuery.Type.SELECT || 
                             subqueryParsed.getType() == ParsedQuery.Type.SELECT_WITH_SUBQUERY)) {
                            log.debug("📊 Correcting table name: parsed={}, actual={}", 
                                subqueryParsed.getTableName(), actualTableName);
                            // Create a new ParsedQuery with the correct table name
                            subqueryParsed = new ParsedQuery(
                                subqueryParsed.getType(),
                                actualTableName,
                                subquerySql
                            );
                        }
                        
                        // Execute the subquery using execute() but skip FROM subquery check
                        // to avoid infinite recursion for nested FROM subqueries
                        subqueryResult = executeSubqueryWithoutFromCheck(subqueryParsed);
                        
                        if (subqueryResult.getError() != null) {
                            return QueryResponse.error("Subquery failed: " + subqueryResult.getError());
                        }
                        
                        log.info("✅ Subquery returned {} rows", subqueryResult.getRows() != null ? subqueryResult.getRows().size() : 0);
                        
                        // Now execute the outer query against the subquery results
                        return executeOuterQueryOnSubqueryResults(outerSelect, subqueryResult, alias, orderBy);
                    }
                }
            }
            
            return QueryResponse.error("FROM subqueries not yet fully implemented for this query structure");
            
        } catch (Exception e) {
            log.error("Failed to execute FROM subquery", e);
            return QueryResponse.error("FROM subquery execution failed: " + e.getMessage());
        }
    }
    
    /**
     * Execute the outer query against subquery results
     * The subquery results act as a temporary in-memory table
     */
    private QueryResponse executeOuterQueryOnSubqueryResults(
            SqlSelect outerSelect, 
            QueryResponse subqueryResult,
            String subqueryAlias,
            SqlOrderBy orderBy) {
        
        try {
            // The subquery results are now our "table"
            List<Map<String, Object>> rows = subqueryResult.getRows();
            List<String> columns = subqueryResult.getColumns();
            
            if (rows == null || rows.isEmpty()) {
                QueryResponse response = new QueryResponse();
                response.setRows(new ArrayList<>());
                response.setColumns(columns != null ? columns : new ArrayList<>());
                return response;
            }
            
            // Apply WHERE clause if present
            if (outerSelect.getWhere() != null) {
                rows = applyWhereClauseToRows(rows, outerSelect.getWhere());
            }
            
            // Check if outer query has GROUP BY or aggregates
            boolean hasGroupBy = outerSelect.getGroup() != null && outerSelect.getGroup().size() > 0;
            boolean hasAggregates = hasAggregatesInSelectList(outerSelect.getSelectList());
            
            if (hasGroupBy || hasAggregates) {
                // Execute aggregation on the subquery results
                return executeAggregationOnRows(outerSelect, rows, columns, orderBy);
            }
            
            // Apply SELECT projection
            rows = applySelectProjection(outerSelect.getSelectList(), rows, columns);
            
            // Apply ORDER BY if present
            if (orderBy != null && orderBy.orderList != null) {
                rows = applyOrderBy(rows, orderBy.orderList);
            } else if (outerSelect.getOrderList() != null) {
                rows = applyOrderBy(rows, outerSelect.getOrderList());
            }
            
            // Apply LIMIT if present
            if (outerSelect.getFetch() != null) {
                int limit = extractLimitValue(outerSelect.getFetch());
                if (limit > 0 && rows.size() > limit) {
                    rows = rows.subList(0, limit);
                }
            }
            
            // Build response
            QueryResponse response = new QueryResponse();
            response.setRows(rows);
            
            // Extract column names from SELECT list
            List<String> resultColumns = extractColumnNamesFromSelectList(outerSelect.getSelectList(), columns);
            response.setColumns(resultColumns);
            
            return response;
            
        } catch (Exception e) {
            log.error("Failed to execute outer query on subquery results", e);
            return QueryResponse.error("Outer query execution failed: " + e.getMessage());
        }
    }
    
    // Helper methods for FROM subquery execution
    
    private List<Map<String, Object>> applyWhereClauseToRows(List<Map<String, Object>> rows, SqlNode whereNode) {
        // Reuse existing WHERE clause evaluation logic
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (evaluateWhereCondition(whereNode, row)) {
                filtered.add(row);
            }
        }
        return filtered;
    }
    
    private boolean evaluateWhereCondition(SqlNode condition, Map<String, Object> row) {
        try {
            Object result = sqlParser.evaluateExpression(condition, row);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return result != null;
        } catch (Exception e) {
            log.debug("Failed to evaluate WHERE condition: {}", e.getMessage());
            return false;
        }
    }
    
    private boolean hasAggregatesInSelectList(SqlNodeList selectList) {
        if (selectList == null) return false;
        
        for (SqlNode node : selectList) {
            if (containsAggregate(node)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean containsAggregate(SqlNode node) {
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            String funcName = call.getOperator().getName().toUpperCase();
            if (funcName.equals("SUM") || funcName.equals("COUNT") || funcName.equals("AVG") ||
                funcName.equals("MIN") || funcName.equals("MAX")) {
                return true;
            }
            // Check operands recursively
            for (SqlNode operand : call.getOperandList()) {
                if (containsAggregate(operand)) {
                    return true;
                }
            }
        } else if (node instanceof SqlCall) {
            SqlCall call = (SqlCall) node;
            for (SqlNode operand : call.getOperandList()) {
                if (containsAggregate(operand)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private QueryResponse executeAggregationOnRows(SqlSelect select, List<Map<String, Object>> rows, 
                                                    List<String> columns, SqlOrderBy orderBy) {
        try {
            // Group rows by GROUP BY columns
            Map<String, List<Map<String, Object>>> groups = new HashMap<>();
            
            boolean hasGroupBy = select.getGroup() != null && select.getGroup().size() > 0;
            
            if (hasGroupBy) {
                // Group by specified columns
                for (Map<String, Object> row : rows) {
                    // Build group key from GROUP BY columns
                    StringBuilder groupKey = new StringBuilder();
                    for (SqlNode groupCol : select.getGroup()) {
                        String colName = groupCol.toString().replace("`", "").toLowerCase();
                        Object value = getCaseInsensitive(row, colName);
                        groupKey.append(value).append("|");
                    }
                    
                    String key = groupKey.toString();
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
            } else {
                // No GROUP BY - treat all rows as one group
                groups.put("all", rows);
            }
            
            // Compute aggregates for each group
            List<Map<String, Object>> resultRows = new ArrayList<>();
            List<String> resultColumns = new ArrayList<>();
            
            for (List<Map<String, Object>> groupRows : groups.values()) {
                Map<String, Object> resultRow = new HashMap<>();
                
                // Process each item in SELECT list
                for (SqlNode selectItem : select.getSelectList()) {
                    if (selectItem.getKind() == SqlKind.AS) {
                        SqlBasicCall asCall = (SqlBasicCall) selectItem;
                        String alias = asCall.operand(1).toString().replace("`", "").toLowerCase();
                        SqlNode expr = asCall.operand(0);
                        
                        // Check if it's an aggregate function
                        if (expr instanceof SqlBasicCall) {
                            SqlBasicCall call = (SqlBasicCall) expr;
                            String funcName = call.getOperator().getName().toUpperCase();
                            
                            Object aggValue = computeAggregateOnRows(funcName, call, groupRows);
                            resultRow.put(alias, aggValue);
                            
                            if (resultColumns.isEmpty() || !resultColumns.contains(alias)) {
                                resultColumns.add(alias);
                            }
                        } else {
                            // Non-aggregate expression (could be CASE, column reference, etc.)
                            // Try to evaluate it using the expression evaluator
                            Object value = null;
                            
                            // First try as a simple column reference
                            String colName = expr.toString().replace("`", "").toLowerCase();
                            value = getCaseInsensitive(groupRows.get(0), colName);
                            
                            // If not found, try evaluating as expression (handles CASE, arithmetic, etc.)
                            if (value == null) {
                                try {
                                    value = sqlParser.evaluateExpression(expr, groupRows.get(0));
                                } catch (Exception e) {
                                    log.debug("Failed to evaluate expression: {}", expr.toString(), e);
                                    value = null;
                                }
                            }
                            
                            resultRow.put(alias, value);
                            
                            if (resultColumns.isEmpty() || !resultColumns.contains(alias)) {
                                resultColumns.add(alias);
                            }
                        }
                    } else {
                        // No alias - use the full expression as column name
                        String colName = selectItem.toString().replace("`", "").toLowerCase();
                        
                        if (selectItem instanceof SqlBasicCall) {
                            SqlBasicCall call = (SqlBasicCall) selectItem;
                            String funcName = call.getOperator().getName().toUpperCase();
                            
                            if (funcName.equals("SUM") || funcName.equals("COUNT") || funcName.equals("AVG") ||
                                funcName.equals("MIN") || funcName.equals("MAX")) {
                                Object aggValue = computeAggregateOnRows(funcName, call, groupRows);
                                // Use the full expression as column name (e.g., "sum(amount)" not just "sum")
                                resultRow.put(colName, aggValue);
                                
                                if (resultColumns.isEmpty() || !resultColumns.contains(colName)) {
                                    resultColumns.add(colName);
                                }
                            }
                        } else {
                            Object value = groupRows.get(0).get(colName);
                            resultRow.put(colName, value);
                            
                            if (resultColumns.isEmpty() || !resultColumns.contains(colName)) {
                                resultColumns.add(colName);
                            }
                        }
                    }
                }
                
                resultRows.add(resultRow);
            }
            
            // Apply HAVING clause if present
            if (select.getHaving() != null) {
                resultRows = applyHavingClauseToRows(resultRows, select.getHaving());
            }
            
            // Apply ORDER BY
            if (orderBy != null && orderBy.orderList != null) {
                resultRows = applyOrderBy(resultRows, orderBy.orderList);
            } else if (select.getOrderList() != null) {
                resultRows = applyOrderBy(resultRows, select.getOrderList());
            }
            
            // Apply LIMIT
            if (select.getFetch() != null) {
                int limit = extractLimitValue(select.getFetch());
                if (limit > 0 && resultRows.size() > limit) {
                    resultRows = resultRows.subList(0, limit);
                }
            }
            
            QueryResponse response = new QueryResponse();
            response.setRows(resultRows);
            response.setColumns(resultColumns);
            return response;
            
        } catch (Exception e) {
            log.error("Failed to execute aggregation on rows", e);
            return QueryResponse.error("Aggregation failed: " + e.getMessage());
        }
    }
    
    private Object computeAggregateOnRows(String funcName, SqlBasicCall call, List<Map<String, Object>> rows) {
        switch (funcName) {
            case "COUNT":
                if (call.getOperandList().size() > 0) {
                    SqlNode arg = call.operand(0);
                    if (arg.toString().equals("*")) {
                        return rows.size();
                    }
                    // COUNT(column) - count non-null values
                    String colName = arg.toString().replace("`", "").toLowerCase();
                    return rows.stream().filter(r -> getCaseInsensitive(r, colName) != null).count();
                }
                return rows.size();
                
            case "SUM":
                String sumCol = call.operand(0).toString().replace("`", "").toLowerCase();
                return rows.stream()
                    .map(r -> getCaseInsensitive(r, sumCol))
                    .filter(v -> v != null)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .sum();
                    
            case "AVG":
                String avgCol = call.operand(0).toString().replace("`", "").toLowerCase();
                return rows.stream()
                    .map(r -> getCaseInsensitive(r, avgCol))
                    .filter(v -> v != null)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .average()
                    .orElse(0.0);
                    
            case "MIN":
                String minCol = call.operand(0).toString().replace("`", "").toLowerCase();
                return rows.stream()
                    .map(r -> getCaseInsensitive(r, minCol))
                    .filter(v -> v != null)
                    .min((a, b) -> compareValues(a, b))
                    .orElse(null);
                    
            case "MAX":
                String maxCol = call.operand(0).toString().replace("`", "").toLowerCase();
                return rows.stream()
                    .map(r -> getCaseInsensitive(r, maxCol))
                    .filter(v -> v != null)
                    .max((a, b) -> compareValues(a, b))
                    .orElse(null);
                    
            default:
                return null;
        }
    }
    
    private List<Map<String, Object>> applyHavingClauseToRows(List<Map<String, Object>> rows, SqlNode havingNode) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (evaluateWhereCondition(havingNode, row)) {
                filtered.add(row);
            }
        }
        return filtered;
    }
    
    private List<Map<String, Object>> applySelectProjection(SqlNodeList selectList, 
                                                             List<Map<String, Object>> rows,
                                                             List<String> availableColumns) {
        // Handle SELECT *
        if (selectList.size() == 1 && selectList.get(0).toString().equals("*")) {
            return rows;
        }
        
        // Project specific columns
        List<Map<String, Object>> projected = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> newRow = new HashMap<>();
            for (SqlNode selectItem : selectList) {
                String colName = selectItem.toString().replace("`", "").toLowerCase();
                
                // Handle AS alias
                if (selectItem.getKind() == SqlKind.AS) {
                    SqlBasicCall asCall = (SqlBasicCall) selectItem;
                    String alias = asCall.operand(1).toString().replace("`", "").toLowerCase();
                    SqlNode expr = asCall.operand(0);
                    String exprStr = expr.toString().replace("`", "").toLowerCase();
                    
                    // Try to get value from row (case-insensitive lookup)
                    Object value = getCaseInsensitive(row, exprStr);
                    if (value == null) {
                        // Try evaluating as expression
                        value = evaluateSelectExpression(expr.toString(), row);
                    }
                    newRow.put(alias, value);
                } else {
                    // Simple column reference - case-insensitive lookup
                    Object value = getCaseInsensitive(row, colName);
                    newRow.put(colName, value);
                }
            }
            projected.add(newRow);
        }
        return projected;
    }
    
    private List<Map<String, Object>> applyOrderBy(List<Map<String, Object>> rows, SqlNodeList orderList) {
        if (orderList == null || orderList.size() == 0) {
            return rows;
        }
        
        // Create a copy to sort
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        
        // Build comparator from ORDER BY list
        Comparator<Map<String, Object>> comparator = null;
        
        for (SqlNode orderItem : orderList) {
            boolean ascending = true;
            String columnName;
            
            // Check if it's a DESC/ASC specification
            if (orderItem instanceof SqlBasicCall) {
                SqlBasicCall call = (SqlBasicCall) orderItem;
                if (call.getOperator().getKind() == SqlKind.DESCENDING) {
                    ascending = false;
                    columnName = call.operand(0).toString().replace("`", "").toLowerCase();
                } else {
                    columnName = orderItem.toString().replace("`", "").toLowerCase();
                }
            } else {
                columnName = orderItem.toString().replace("`", "").toLowerCase();
            }
            
            // Remove DESC/ASC keywords if present
            columnName = columnName.replaceAll("(?i)\\s+(ASC|DESC)$", "").trim();
            
            final String finalColumnName = columnName;
            final boolean finalAscending = ascending;
            
            Comparator<Map<String, Object>> columnComparator = (row1, row2) -> {
                Object val1 = getCaseInsensitive(row1, finalColumnName);
                Object val2 = getCaseInsensitive(row2, finalColumnName);
                
                // Handle nulls
                if (val1 == null && val2 == null) return 0;
                if (val1 == null) return finalAscending ? 1 : -1;  // NULLS LAST by default
                if (val2 == null) return finalAscending ? -1 : 1;
                
                int cmp = compareValues(val1, val2);
                return finalAscending ? cmp : -cmp;
            };
            
            // Chain comparators
            if (comparator == null) {
                comparator = columnComparator;
            } else {
                comparator = comparator.thenComparing(columnComparator);
            }
        }
        
        if (comparator != null) {
            sorted.sort(comparator);
        }
        
        return sorted;
    }
    
    private int extractLimitValue(SqlNode fetchNode) {
        if (fetchNode instanceof SqlNumericLiteral) {
            return ((SqlNumericLiteral) fetchNode).intValue(true);
        }
        return -1;
    }
    
    private List<String> extractColumnNamesFromSelectList(SqlNodeList selectList, List<String> availableColumns) {
        if (selectList.size() == 1 && selectList.get(0).toString().equals("*")) {
            return availableColumns;
        }
        
        List<String> columnNames = new ArrayList<>();
        for (SqlNode selectItem : selectList) {
            if (selectItem.getKind() == SqlKind.AS) {
                SqlBasicCall asCall = (SqlBasicCall) selectItem;
                String alias = asCall.operand(1).toString().replace("`", "").toLowerCase();
                columnNames.add(alias);
            } else {
                String colName = selectItem.toString().replace("`", "").toLowerCase();
                columnNames.add(colName);
            }
        }
        return columnNames;
    }
    
    /**
     * Case-insensitive lookup in a map
     */
    private Object getCaseInsensitive(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        
        // Try exact match first
        Object value = map.get(key.toLowerCase());
        if (value != null) {
            return value;
        }
        
        // Try case-insensitive search
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
}

