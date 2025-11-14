package com.geico.poc.cassandrasql.postgres;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import com.geico.poc.cassandrasql.QueryService;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.transaction.TransactionSessionManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Handles PostgreSQL protocol connections
 */
public class PostgresConnectionHandler extends SimpleChannelInboundHandler<PostgresMessage> {
    
    private static final Logger log = LoggerFactory.getLogger(PostgresConnectionHandler.class);
    
    private final QueryService queryService;
    private final CqlSession session;
    private final TransactionSessionManager transactionManager;
    private final PreparedStatementManager preparedStatementManager;
    private final ConnectionLimiter connectionLimiter;
    private final CassandraSqlConfig config;
    private String connectionId;  // Unique ID for this connection
    
    // Connection-level settings
    private boolean timingEnabled = false;
    private boolean expandedOutput = false;
    
    public PostgresConnectionHandler(QueryService queryService, CqlSession session, 
                                    TransactionSessionManager transactionManager,
                                    PreparedStatementManager preparedStatementManager,
                                    ConnectionLimiter connectionLimiter,
                                    CassandraSqlConfig config) {
        this.queryService = queryService;
        this.session = session;
        this.transactionManager = transactionManager;
        this.preparedStatementManager = preparedStatementManager;
        this.connectionLimiter = connectionLimiter;
        this.config = config;
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.connectionId = ctx.channel().id().asLongText();
        log.debug("PostgreSQL client connected: " + ctx.channel().remoteAddress() + " (id: " + connectionId + ")");
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("PostgreSQL client disconnected: " + ctx.channel().remoteAddress() + " (id: " + connectionId + ")");
        
        // Clean up resources in try-finally to ensure connection limit is released
        try {
            // Clean up prepared statements
            try {
                preparedStatementManager.closeConnection(connectionId);
            } catch (Exception e) {
                log.error("⚠️  Failed to cleanup prepared statements for connection " + connectionId + ": " + e.getMessage());
            }
            
            // Rollback any active transaction
            try {
                if (transactionManager.hasActiveTransaction(connectionId)) {
                    log.debug("⚠️  Rolling back active transaction for disconnected connection: " + connectionId);
                    transactionManager.rollback(connectionId);
                }
            } catch (Exception e) {
                log.error("⚠️  Failed to rollback transaction for connection " + connectionId + ": " + e.getMessage());
            }
        } finally {
            // ALWAYS release connection limit, even if cleanup fails
            connectionLimiter.release();
        }
        
        super.channelInactive(ctx);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("❌ Exception in PostgreSQL connection handler: " + cause.getMessage());
        cause.printStackTrace();
        ctx.close();  // This will trigger channelInactive() which does cleanup
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, PostgresMessage msg) {
        if (msg instanceof PostgresMessage.StartupMessage) {
            handleStartup(ctx, (PostgresMessage.StartupMessage) msg);
        } else if (msg instanceof PostgresMessage.QueryMessage) {
            handleQuery(ctx, (PostgresMessage.QueryMessage) msg);
        } else if (msg instanceof PostgresMessage.ParseMessage) {
            handleParse(ctx, (PostgresMessage.ParseMessage) msg);
        } else if (msg instanceof PostgresMessage.BindMessage) {
            handleBind(ctx, (PostgresMessage.BindMessage) msg);
        } else if (msg instanceof PostgresMessage.DescribeMessage) {
            handleDescribe(ctx, (PostgresMessage.DescribeMessage) msg);
        } else if (msg instanceof PostgresMessage.ExecuteMessage) {
            handleExecute(ctx, (PostgresMessage.ExecuteMessage) msg);
        } else if (msg instanceof PostgresMessage.SyncMessage) {
            handleSync(ctx);
        } else if (msg instanceof PostgresMessage.CloseMessage) {
            handleClose(ctx, (PostgresMessage.CloseMessage) msg);
        } else if (msg instanceof PostgresMessage.CopyDataMessage) {
            handleCopyData(ctx, (PostgresMessage.CopyDataMessage) msg);
        } else if (msg instanceof PostgresMessage.CopyDoneMessage) {
            handleCopyDone(ctx);
        } else if (msg instanceof PostgresMessage.CopyFailMessage) {
            handleCopyFail(ctx, (PostgresMessage.CopyFailMessage) msg);
        } else if (msg instanceof PostgresMessage.TerminateMessage) {
            log.debug("PostgreSQL client disconnected: " + ctx.channel().remoteAddress());
            preparedStatementManager.closeConnection(connectionId);
            ctx.close();
        }
    }
    
    private void handleStartup(ChannelHandlerContext ctx, PostgresMessage.StartupMessage msg) {
        log.debug("PostgreSQL startup: user=" + msg.getUser() + ", database=" + msg.getDatabase());
        
        // Send AuthenticationOk (no password required for MVP)
        ctx.write(new PostgresMessage.AuthenticationOkMessage());
        
        // Send ParameterStatus messages
        ctx.write(new PostgresMessage.ParameterStatusMessage("server_version", "14.0 (Cassandra SQL Layer)"));
        ctx.write(new PostgresMessage.ParameterStatusMessage("server_encoding", "UTF8"));
        ctx.write(new PostgresMessage.ParameterStatusMessage("client_encoding", "UTF8"));
        ctx.write(new PostgresMessage.ParameterStatusMessage("DateStyle", "ISO, MDY"));
        
        // Send ReadyForQuery
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage('I'));
    }
    
    private void handleQuery(ChannelHandlerContext ctx, PostgresMessage.QueryMessage msg) {
        String sql = msg.getSql();
        log.debug("PostgreSQL query: " + sql);
        
        try {
            // Strip trailing semicolon and check for transaction commands
            String sqlTrimmed = sql.trim();
            if (sqlTrimmed.endsWith(";")) {
                sqlTrimmed = sqlTrimmed.substring(0, sqlTrimmed.length() - 1).trim();
            }
            String sqlUpper = sqlTrimmed.toUpperCase();
            
            log.debug("DEBUG: Checking transaction command: '" + sqlUpper + "'");
            
            // Handle transaction commands
            // In KV mode, transactions are handled by KvTransactionCoordinator, not TransactionSessionManager
            boolean isKvMode = (config != null && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV);
            
            if (sqlUpper.equals("BEGIN") || sqlUpper.equals("BEGIN TRANSACTION") || sqlUpper.equals("START TRANSACTION")) {
                log.debug("DEBUG: Detected BEGIN command");
                if (!isKvMode) {
                    transactionManager.begin(connectionId);
                }
                // In KV mode, BEGIN is a no-op (transactions are per-statement)
                ctx.write(new PostgresMessage.CommandCompleteMessage("BEGIN"));
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage('T'));  // 'T' = in transaction
                return;
            } else if (sqlUpper.equals("COMMIT")) {
                log.debug("DEBUG: Detected COMMIT command");
                if (!isKvMode) {
                    transactionManager.commit(connectionId);
                }
                // In KV mode, COMMIT is a no-op (transactions are already committed per-statement)
                ctx.write(new PostgresMessage.CommandCompleteMessage("COMMIT"));
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage('I'));  // 'I' = idle
                return;
            } else if (sqlUpper.equals("ROLLBACK") || sqlUpper.equals("ABORT")) {
                log.debug("DEBUG: Detected ROLLBACK command");
                if (!isKvMode) {
                    transactionManager.rollback(connectionId);
                }
                // In KV mode, ROLLBACK is a no-op (can't rollback already-committed statements)
                ctx.write(new PostgresMessage.CommandCompleteMessage("ROLLBACK"));
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage('I'));  // 'I' = idle
                return;
            }
            
            // Handle PostgreSQL backslash commands (psql meta-commands)
            if (sqlTrimmed.startsWith("\\")) {
                handleMetaCommand(ctx, sqlTrimmed);
                return;
            }
            
            // Handle COPY FROM STDIN command
            if (sqlUpper.startsWith("COPY ") && sqlUpper.contains(" FROM STDIN")) {
                handleCopyFromStdin(ctx, sqlTrimmed);
                return;
            }
            
            // Handle PostgreSQL system functions
            if (sqlUpper.equals("SELECT CURRENT_TIMESTAMP") || sqlUpper.equals("SELECT NOW()")) {
                handleCurrentTimestamp(ctx);
                return;
            } else if (sqlUpper.equals("SELECT VERSION()")) {
                handleVersion(ctx);
                return;
            } else if (sqlUpper.equals("SELECT CURRENT_DATABASE()")) {
                handleCurrentDatabase(ctx);
                return;
            } else if (sqlUpper.equals("SELECT CURRENT_USER")) {
                handleCurrentUser(ctx);
                return;
            }
            
            // Handle queries to PostgreSQL system catalogs (pg_catalog, information_schema)
            // Translate pg_catalog.* references to our internal keyspace
            if (sqlUpper.contains("PG_CATALOG.") || sqlUpper.contains("PG_CLASS") || 
                sqlUpper.contains("PG_NAMESPACE") || sqlUpper.contains("PG_TABLES")) {
                handleSystemCatalogQuery(ctx, sqlTrimmed);
                return;
            }
            
            // Execute query through existing SQL layer (with connection ID for transaction awareness)
            QueryResponse response = queryService.execute(sql, connectionId);
            
            // Check if response contains an error
            if (response.getError() != null && !response.getError().isEmpty()) {
                ctx.write(new PostgresMessage.ErrorResponseMessage(response.getError()));
                char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
                return;
            }
            
            // Send RowDescription (always send if we have rows, even if columns list is empty)
            if (response.getRows() != null && !response.getRows().isEmpty()) {
                List<String> columns = response.getColumns();
                if (columns == null || columns.isEmpty()) {
                    // If columns list is empty but we have rows, infer columns from first row
                    if (!response.getRows().isEmpty()) {
                        columns = new ArrayList<>(response.getRows().get(0).keySet());
                    } else {
                        columns = Collections.emptyList();
                    }
                }
                ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
                
                // Send DataRow messages
                for (Map<String, Object> row : response.getRows()) {
                    ctx.write(new PostgresMessage.DataRowMessage(row, columns));
                }
            } else if (response.getColumns() != null && !response.getColumns().isEmpty()) {
                // No rows but we have column definitions (e.g., SELECT that returns 0 rows)
                ctx.write(new PostgresMessage.RowDescriptionMessage(response.getColumns()));
            }
            
            // Send CommandComplete
            String tag = determineCommandTag(sql, response.getRowCount());
            ctx.write(new PostgresMessage.CommandCompleteMessage(tag));
            
            // Send ReadyForQuery (check if in transaction)
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
            
        } catch (Exception e) {
            log.error("PostgreSQL query error: " + e.getMessage());
            e.printStackTrace();
            
            // Send ErrorResponse
            ctx.write(new PostgresMessage.ErrorResponseMessage(e.getMessage()));
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage('I'));
        }
    }
    
    private String determineCommandTag(String sql, int rowCount) {
        String sqlUpper = sql.trim().toUpperCase();
        if (sqlUpper.startsWith("SELECT")) {
            return "SELECT " + rowCount;
        } else if (sqlUpper.startsWith("INSERT")) {
            return "INSERT 0 " + rowCount;
        } else if (sqlUpper.startsWith("UPDATE")) {
            return "UPDATE " + rowCount;
        } else if (sqlUpper.startsWith("DELETE")) {
            return "DELETE " + rowCount;
        } else if (sqlUpper.startsWith("CREATE TABLE")) {
            return "CREATE TABLE";
        } else if (sqlUpper.startsWith("CREATE INDEX")) {
            return "CREATE INDEX";
        }
        return "OK";
    }
    
    /**
     * Handle SELECT CURRENT_TIMESTAMP / SELECT NOW()
     */
    private void handleCurrentTimestamp(ChannelHandlerContext ctx) {
        java.time.Instant now = java.time.Instant.now();
        String timestamp = now.toString();
        
        // Send RowDescription
        java.util.List<String> columns = java.util.Arrays.asList("current_timestamp");
        ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
        
        // Send DataRow
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("current_timestamp", timestamp);
        ctx.write(new PostgresMessage.DataRowMessage(row, columns));
        
        // Send CommandComplete and ReadyForQuery
        ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT 1"));
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle SELECT VERSION()
     */
    private void handleVersion(ChannelHandlerContext ctx) {
        String version = "PostgreSQL 14.0 (Cassandra SQL Layer 1.0)";
        
        java.util.List<String> columns = java.util.Arrays.asList("version");
        ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
        
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("version", version);
        ctx.write(new PostgresMessage.DataRowMessage(row, columns));
        
        ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT 1"));
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle SELECT CURRENT_DATABASE()
     */
    private void handleCurrentDatabase(ChannelHandlerContext ctx) {
        String database = "cassandra_sql";
        
        java.util.List<String> columns = java.util.Arrays.asList("current_database");
        ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
        
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("current_database", database);
        ctx.write(new PostgresMessage.DataRowMessage(row, columns));
        
        ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT 1"));
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle SELECT CURRENT_USER
     */
    private void handleCurrentUser(ChannelHandlerContext ctx) {
        String user = "cassandra";
        
        java.util.List<String> columns = java.util.Arrays.asList("current_user");
        ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
        
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("current_user", user);
        ctx.write(new PostgresMessage.DataRowMessage(row, columns));
        
        ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT 1"));
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle PostgreSQL meta-commands (\dt, \d, etc.)
     */
    private void handleMetaCommand(ChannelHandlerContext ctx, String command) {
        String cmd = command.trim().toLowerCase();
        
        try {
            if (cmd.equals("\\dt") || cmd.startsWith("\\dt ")) {
                // List tables with optional pattern
                String pattern = cmd.length() > 3 ? cmd.substring(3).trim() : null;
                handleListTables(ctx, pattern);
            } else if (cmd.equals("\\dt+") || cmd.startsWith("\\dt+ ")) {
                // List tables with sizes
                String pattern = cmd.length() > 4 ? cmd.substring(4).trim() : null;
                handleListTablesWithSizes(ctx, pattern);
            } else if (cmd.equals("\\d") || cmd.startsWith("\\d ")) {
                // Describe table or list all relations
                if (cmd.equals("\\d")) {
                    handleListAllRelations(ctx);
                } else if (cmd.startsWith("\\d+ ")) {
                    // Describe table with extra info
                    String tableName = cmd.substring(3).trim();
                    handleDescribeTableExtended(ctx, tableName);
                } else {
                    String tableName = cmd.substring(2).trim();
                    handleDescribeTable(ctx, tableName);
                }
            } else if (cmd.equals("\\di") || cmd.startsWith("\\di ")) {
                // List indexes
                String pattern = cmd.length() > 3 ? cmd.substring(3).trim() : null;
                handleListIndexes(ctx, pattern);
            } else if (cmd.equals("\\dn")) {
                // List schemas (keyspaces in Cassandra)
                handleListSchemas(ctx);
            } else if (cmd.equals("\\du")) {
                // List users/roles
                handleListUsers(ctx);
            } else if (cmd.equals("\\l")) {
                // List databases
                handleListDatabases(ctx);
            } else if (cmd.equals("\\q")) {
                // Quit
                handleQuit(ctx);
            } else if (cmd.equals("\\?")) {
                // Help
                handleHelp(ctx);
            } else if (cmd.equals("\\timing")) {
                // Toggle timing
                handleTiming(ctx);
            } else if (cmd.equals("\\x")) {
                // Toggle expanded output
                handleExpandedOutput(ctx);
            } else {
                // Unknown command
                ctx.write(new PostgresMessage.ErrorResponseMessage(
                    "Unknown meta-command: " + command + ". Try \\? for help."));
                char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
            }
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage(
                "Error executing meta-command: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Handle \dt - List tables
     * Works in both Cassandra schema mode and KV mode
     * 
     * Filters out internal tables (kv_*, tx_*, pg_*, information_schema_*)
     */
    private void handleListTables(ChannelHandlerContext ctx) {
        handleListTables(ctx, null);
    }
    
    /**
     * Handle \dt [pattern] - List tables with optional pattern
     * 
     * Patterns:
     * - \dt              : User tables only (default)
     * - \dt *.*          : All tables including system
     * - \dt schema.*     : Tables in specific schema
     * - \dt pattern      : Tables matching pattern
     */
    private void handleListTables(ChannelHandlerContext ctx, String pattern) {
        try {
            // Define internal tables to filter out
            java.util.Set<String> internalTablePrefixes = java.util.Set.of(
                "kv_", "tx_", "pg_", "information_schema_", "system_"
            );
            
            // Query pg_tables from internal keyspace
            String sql = "SELECT schemaname, tablename FROM cassandra_sql_internal.pg_tables WHERE schemaname = 'public'";
            QueryResponse response = queryService.execute(sql, connectionId);
            
            java.util.List<String> columns = java.util.Arrays.asList("Schema", "Name", "Type", "Owner");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            int count = 0;
            if (response.getRows() != null) {
                for (Map<String, Object> row : response.getRows()) {
                    String tableName = (String) row.get("tablename");
                    
                    // Filter out internal tables unless pattern is *.*
                    if (pattern == null || !pattern.equals("*.*")) {
                        boolean isInternal = false;
                        for (String prefix : internalTablePrefixes) {
                            if (tableName.startsWith(prefix)) {
                                isInternal = true;
                                break;
                            }
                        }
                        if (isInternal) {
                            continue;  // Skip internal tables
                        }
                    }
                    
                    // Apply pattern matching if specified
                    if (pattern != null && !pattern.equals("*.*")) {
                        if (!matchesPattern(tableName, pattern)) {
                            continue;
                        }
                    }
                    
                    java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                    resultRow.put("Schema", row.get("schemaname"));
                    resultRow.put("Name", tableName);
                    resultRow.put("Type", "table");
                    resultRow.put("Owner", "cassandra");
                    ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                    count++;
                }
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error listing tables: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Simple pattern matching for table names
     * Supports wildcards: * (any characters), ? (single character)
     */
    private boolean matchesPattern(String tableName, String pattern) {
        // Convert SQL pattern to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return tableName.matches(regex);
    }
    
    /**
     * Handle \d - List all relations (tables, views, etc.)
     */
    private void handleListAllRelations(ChannelHandlerContext ctx) {
        // For now, just list tables (same as \dt)
        handleListTables(ctx);
    }
    
    /**
     * Handle \d tablename - Describe table structure
     */
    private void handleDescribeTable(ChannelHandlerContext ctx, String tableName) {
        try {
            // Query Cassandra system schema for column information
            com.datastax.oss.driver.api.core.cql.ResultSet rs = 
                session.execute(
                    String.format(
                        "SELECT column_name, type, kind FROM system_schema.columns " +
                        "WHERE keyspace_name = 'cassandra_sql' AND table_name = '%s'",
                        tableName));
            
            java.util.List<String> columns = java.util.Arrays.asList("Column", "Type", "Nullable", "Default");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            int count = 0;
            for (com.datastax.oss.driver.api.core.cql.Row row : rs) {
                java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                resultRow.put("Column", row.getString("column_name"));
                resultRow.put("Type", row.getString("type"));
                String kind = row.getString("kind");
                resultRow.put("Nullable", "partition_key".equals(kind) || "clustering".equals(kind) ? "not null" : "");
                resultRow.put("Default", "");
                ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                count++;
            }
            
            if (count == 0) {
                ctx.write(new PostgresMessage.ErrorResponseMessage(
                    "Did not find any relation named \"" + tableName + "\"."));
            } else {
                ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            }
            
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error describing table: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Handle \dn - List schemas (keyspaces)
     */
    private void handleListSchemas(ChannelHandlerContext ctx) {
        try {
            com.datastax.oss.driver.api.core.cql.ResultSet rs = 
                session.execute("SELECT keyspace_name FROM system_schema.keyspaces");
            
            java.util.List<String> columns = java.util.Arrays.asList("Name", "Owner");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            int count = 0;
            for (com.datastax.oss.driver.api.core.cql.Row row : rs) {
                java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                resultRow.put("Name", row.getString("keyspace_name"));
                resultRow.put("Owner", "cassandra");
                ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                count++;
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error listing schemas: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Handle \du - List users/roles
     */
    private void handleListUsers(ChannelHandlerContext ctx) {
        java.util.List<String> columns = java.util.Arrays.asList("Role name", "Attributes");
        ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
        
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("Role name", "cassandra");
        row.put("Attributes", "Superuser");
        ctx.write(new PostgresMessage.DataRowMessage(row, columns));
        
        ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT 1"));
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle \l - List databases
     */
    private void handleListDatabases(ChannelHandlerContext ctx) {
        try {
            com.datastax.oss.driver.api.core.cql.ResultSet rs = 
                session.execute("SELECT keyspace_name FROM system_schema.keyspaces");
            
            java.util.List<String> columns = java.util.Arrays.asList("Name", "Owner", "Encoding");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            int count = 0;
            for (com.datastax.oss.driver.api.core.cql.Row row : rs) {
                java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                resultRow.put("Name", row.getString("keyspace_name"));
                resultRow.put("Owner", "cassandra");
                resultRow.put("Encoding", "UTF8");
                ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                count++;
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error listing databases: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Handle queries to PostgreSQL system catalogs
     */
    private void handleSystemCatalogQuery(ChannelHandlerContext ctx, String sql) {
        // In KV mode, catalog tables are stored in pg_catalog keyspace in the KV store
        // Just strip the pg_catalog. prefix and let the query executor handle routing
        String sqlUpper = sql.toUpperCase();
        
        try {
            log.debug("Processing pg_catalog query: {}", sql);
            
            // Strip pg_catalog. prefix - the KV store will route to the correct keyspace
            // based on table ID (negative IDs go to pg_catalog keyspace)
            String translatedSql = sql
                .replaceAll("(?i)pg_catalog\\.", "");
            
            // Check for unsupported catalog features (regex operators, functions, etc.)
            if (sqlUpper.contains("PG_PARTITIONED_TABLE") || 
                sqlUpper.contains("PG_INHERITS") ||
                sqlUpper.contains("LATERAL") ||
                sqlUpper.contains("CURRENT_SCHEMAS") ||
                sqlUpper.contains("ARRAY_POSITION") ||
                sqlUpper.contains("PG_GET_USERBYID") ||
                sqlUpper.contains("PG_TABLE_IS_VISIBLE") ||
                sql.contains("!~") ||  // Regex not-match operator
                sql.contains("~")) {   // Regex match operator
                
                log.debug("Query contains unsupported catalog features - simulating \\dt response");
                
                // Extract table name from WHERE clause if present
                String tableNameFilter = null;
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("c\\.relname\\s*=\\s*'([^']+)'", java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher matcher = pattern.matcher(sql);
                if (matcher.find()) {
                    tableNameFilter = matcher.group(1);
                    log.debug("Filtering for table: {}", tableNameFilter);
                }
                
                // This is likely a \\dt command from psql - simulate it
                // Query pg_class to get the list of tables (relkind='r')
                // Note: KV executor doesn't support column projection yet, so we get all columns
                try {
                    String simpleSql = "SELECT * FROM pg_class WHERE relkind = 'r'";
                    if (tableNameFilter != null) {
                        simpleSql += " AND relname = '" + tableNameFilter + "'";
                    }
                    QueryResponse response = queryService.execute(simpleSql, connectionId);
                    
                    java.util.List<String> columns = java.util.Arrays.asList("Schema", "Name", "Type", "Owner");
                    ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
                    
                    int count = 0;
                    if (response.getRows() != null) {
                        for (java.util.Map<String, Object> row : response.getRows()) {
                            // Debug: log what keys are actually in the row
                            if (count == 0) {
                                log.debug("First row keys: {}", row.keySet());
                                log.debug("First row values: {}", row);
                            }
                            
                            java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                            // Extract table name from relname column
                            Object name = row.get("relname");
                            if (name == null) name = row.get("RELNAME");
                            
                            // Hardcode schema to 'public' (KV mode doesn't support column projection yet)
                            resultRow.put("Schema", "public");
                            resultRow.put("Name", name);
                            resultRow.put("Type", "table");
                            resultRow.put("Owner", "cassandra");
                            ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                            count++;
                        }
                    }
                    
                    ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
                    char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                    ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
                    return;
                } catch (Exception e) {
                    log.error("Error simulating \\dt command", e);
                    // Fall through to return empty result
                }
                
                // Fallback: Return empty result
                java.util.List<String> columns = java.util.Arrays.asList("Schema", "Name", "Type", "Owner");
                ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
                ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT 0"));
                char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
                return;
            }
            
            log.debug("Executing translated query: {}", translatedSql);
            
            // Execute the translated query through the normal query path
            QueryResponse response = queryService.execute(translatedSql, connectionId);
            
            if (response.getError() != null && !response.getError().isEmpty()) {
                ctx.write(new PostgresMessage.ErrorResponseMessage(response.getError()));
                char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
                return;
            }
            
            // Send results
            if (response.getColumns() != null && !response.getColumns().isEmpty()) {
                ctx.write(new PostgresMessage.RowDescriptionMessage(response.getColumns()));
                
                if (response.getRows() != null) {
                    for (java.util.Map<String, Object> row : response.getRows()) {
                        ctx.write(new PostgresMessage.DataRowMessage(row, response.getColumns()));
                    }
                }
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + response.getRowCount()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
            
        } catch (Exception e) {
            log.error("Error handling system catalog query", e);
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error querying system catalog: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Execute a translated system query
     */
    private void executeTranslatedQuery(ChannelHandlerContext ctx, String cql) {
        try {
            com.datastax.oss.driver.api.core.cql.ResultSet rs = session.execute(cql);
            
            // Get column names from result set metadata
            java.util.List<String> columns = new java.util.ArrayList<>();
            for (com.datastax.oss.driver.api.core.cql.ColumnDefinition colDef : rs.getColumnDefinitions()) {
                columns.add(colDef.getName().asInternal());
            }
            
            if (!columns.isEmpty()) {
                ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            }
            
            // Send rows
            int count = 0;
            for (com.datastax.oss.driver.api.core.cql.Row row : rs) {
                java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                for (String col : columns) {
                    resultRow.put(col, row.getObject(col));
                }
                ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                count++;
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error executing translated query: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    // ========== Prepared Statement Handlers ==========
    
    private void handleParse(ChannelHandlerContext ctx, PostgresMessage.ParseMessage msg) {
        try {
            log.debug("📝 Parse: " + msg.getStatementName() + " -> " + msg.getQuery());
            
            preparedStatementManager.createStatement(
                connectionId,
                msg.getStatementName(),
                msg.getQuery(),
                msg.getParamTypes()
            );
            
            ctx.write(new PostgresMessage.ParseCompleteMessage());
            
        } catch (Exception e) {
            log.error("Parse error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("Parse error: " + e.getMessage()));
        }
    }
    
    private void handleBind(ChannelHandlerContext ctx, PostgresMessage.BindMessage msg) {
        try {
            log.debug("🔗 Bind: portal=" + msg.getPortalName() + ", statement=" + msg.getStatementName());
            
            preparedStatementManager.createPortal(
                connectionId,
                msg.getPortalName(),
                msg.getStatementName(),
                msg.getParamValues()
            );
            
            ctx.write(new PostgresMessage.BindCompleteMessage());
            
        } catch (Exception e) {
            log.error("Bind error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("Bind error: " + e.getMessage()));
        }
    }
    
    private void handleDescribe(ChannelHandlerContext ctx, PostgresMessage.DescribeMessage msg) {
        try {
            log.debug("🔍 Describe: type=" + msg.getType() + ", name=" + msg.getName());
            
            if (msg.getType() == 'S') {
                // Describe statement
                PreparedStatementManager.PreparedStatement stmt = 
                    preparedStatementManager.getStatement(connectionId, msg.getName());
                
                if (stmt == null) {
                    ctx.write(new PostgresMessage.ErrorResponseMessage("Statement not found: " + msg.getName()));
                    return;
                }
                
                // Send parameter description
                ctx.write(new PostgresMessage.ParameterDescriptionMessage(stmt.getParamTypes()));
                
                // For now, we don't know the result columns until execution
                // Send NoData to indicate we can't describe result set yet
                ctx.write(new PostgresMessage.NoDataMessage());
                
            } else if (msg.getType() == 'P') {
                // Describe portal
                PreparedStatementManager.Portal portal = 
                    preparedStatementManager.getPortal(connectionId, msg.getName());
                
                if (portal == null) {
                    ctx.write(new PostgresMessage.ErrorResponseMessage("Portal not found: " + msg.getName()));
                    return;
                }
                
                // If portal has cached result, describe it
                QueryResponse cached = portal.getCachedResult();
                if (cached != null && cached.getColumns() != null && !cached.getColumns().isEmpty()) {
                    ctx.write(new PostgresMessage.RowDescriptionMessage(cached.getColumns()));
                } else {
                    ctx.write(new PostgresMessage.NoDataMessage());
                }
            }
            
        } catch (Exception e) {
            log.error("Describe error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("Describe error: " + e.getMessage()));
        }
    }
    
    private void handleExecute(ChannelHandlerContext ctx, PostgresMessage.ExecuteMessage msg) {
        try {
            log.debug("▶️  Execute: portal=" + msg.getPortalName() + ", maxRows=" + msg.getMaxRows());
            
            PreparedStatementManager.Portal portal = 
                preparedStatementManager.getPortal(connectionId, msg.getPortalName());
            
            if (portal == null) {
                ctx.write(new PostgresMessage.ErrorResponseMessage("Portal not found: " + msg.getPortalName()));
                return;
            }
            
            // Get the bound query
            String boundQuery = portal.getBoundQuery();
            log.debug("   Bound query: " + boundQuery);
            
            // Execute the query
            QueryResponse response = queryService.execute(boundQuery, connectionId);
            
            // Cache result for potential re-execution
            portal.setCachedResult(response);
            
            // Check for errors
            if (response.getError() != null && !response.getError().isEmpty()) {
                ctx.write(new PostgresMessage.ErrorResponseMessage(response.getError()));
                return;
            }
            
            // Send RowDescription if not already sent
            if (response.getColumns() != null && !response.getColumns().isEmpty()) {
                ctx.write(new PostgresMessage.RowDescriptionMessage(response.getColumns()));
            }
            
            // Send DataRow messages
            if (response.getRows() != null) {
                int rowsSent = 0;
                int maxRows = msg.getMaxRows();
                int startRow = portal.getCurrentRow();
                
                for (int i = startRow; i < response.getRows().size(); i++) {
                    if (maxRows > 0 && rowsSent >= maxRows) {
                        portal.setCurrentRow(i);
                        break;
                    }
                    
                    Map<String, Object> row = response.getRows().get(i);
                    ctx.write(new PostgresMessage.DataRowMessage(row, response.getColumns()));
                    rowsSent++;
                }
                
                // If all rows sent, update portal position
                if (maxRows == 0 || startRow + rowsSent >= response.getRows().size()) {
                    portal.setCurrentRow(response.getRows().size());
                }
            }
            
            // Send CommandComplete
            String tag = determineCommandTag(boundQuery, response.getRowCount());
            ctx.write(new PostgresMessage.CommandCompleteMessage(tag));
            
        } catch (Exception e) {
            log.error("Execute error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("Execute error: " + e.getMessage()));
        }
    }
    
    private void handleSync(ChannelHandlerContext ctx) {
        log.debug("🔄 Sync");
        
        // Sync point - flush all pending messages and send ReadyForQuery
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    private void handleClose(ChannelHandlerContext ctx, PostgresMessage.CloseMessage msg) {
        try {
            log.debug("🗑️  Close: type=" + msg.getType() + ", name=" + msg.getName());
            
            if (msg.getType() == 'S') {
                // Close statement
                preparedStatementManager.closeStatement(connectionId, msg.getName());
            } else if (msg.getType() == 'P') {
                // Close portal
                preparedStatementManager.closePortal(connectionId, msg.getName());
            }
            
            ctx.write(new PostgresMessage.CloseCompleteMessage());
            
        } catch (Exception e) {
            log.error("Close error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("Close error: " + e.getMessage()));
        }
    }
    
    /**
     * Handle \q - Quit
     */
    private void handleQuit(ChannelHandlerContext ctx) {
        log.debug("Client requested quit: " + connectionId);
        preparedStatementManager.closeConnection(connectionId);
        ctx.close();
    }
    
    /**
     * Handle \? - Help on backslash commands
     */
    private void handleHelp(ChannelHandlerContext ctx) {
        String helpText = 
            "General\n" +
            "  \\q                    quit psql\n" +
            "  \\?                    help on backslash commands\n" +
            "\n" +
            "Informational\n" +
            "  \\d [NAME]             describe table, or list tables\n" +
            "  \\d+ [NAME]            describe table with additional details\n" +
            "  \\dt [PATTERN]         list tables\n" +
            "  \\dt+ [PATTERN]        list tables with sizes\n" +
            "  \\dt *.*               list all tables including system tables\n" +
            "  \\di [PATTERN]         list indexes\n" +
            "  \\dn [PATTERN]         list schemas\n" +
            "  \\du [PATTERN]         list roles\n" +
            "  \\l                    list databases\n" +
            "\n" +
            "Formatting\n" +
            "  \\x                    toggle expanded output\n" +
            "\n" +
            "Query Execution\n" +
            "  \\timing               toggle timing of commands\n" +
            "\n" +
            "For more information about SQL commands, type \\h.\n";
        
        sendNoticeMessage(ctx, helpText);
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle \\timing - Toggle timing of commands
     */
    private void handleTiming(ChannelHandlerContext ctx) {
        timingEnabled = !timingEnabled;
        String message = timingEnabled ? "Timing is on." : "Timing is off.";
        sendNoticeMessage(ctx, message);
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle \\x - Toggle expanded output
     */
    private void handleExpandedOutput(ChannelHandlerContext ctx) {
        expandedOutput = !expandedOutput;
        String message = expandedOutput ? "Expanded display is on." : "Expanded display is off.";
        sendNoticeMessage(ctx, message);
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
    
    /**
     * Handle \\di - List indexes
     * Queries pg_class for entries with relkind='i' (indexes)
     */
    private void handleListIndexes(ChannelHandlerContext ctx, String pattern) {
        try {
            // Query pg_class for indexes (relkind = 'i')
            // Join with pg_index to get the table name
            String sql = 
                "SELECT " +
                "    i.relname as indexname, " +
                "    t.relname as tablename, " +
                "    n.nspname as schemaname " +
                "FROM pg_class i " +
                "JOIN pg_index idx ON idx.indexrelid = i.oid " +
                "JOIN pg_class t ON t.oid = idx.indrelid " +
                "JOIN pg_namespace n ON n.oid = i.relnamespace " +
                "WHERE i.relkind = 'i' AND n.nspname = 'public'";
            
            if (pattern != null && !pattern.trim().isEmpty()) {
                sql += " AND i.relname LIKE '" + pattern.replace("*", "%") + "'";
            }
            
            // Execute via QueryService to use KV mode properly
            com.geico.poc.cassandrasql.dto.QueryResponse response = queryService.execute(sql, connectionId);
            
            if (response.getError() != null) {
                ctx.write(new PostgresMessage.ErrorResponseMessage("Error listing indexes: " + response.getError()));
                char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
                return;
            }
            
            java.util.List<String> columns = java.util.Arrays.asList("Schema", "Name", "Type", "Owner", "Table");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            int count = 0;
            if (response.getRows() != null) {
                for (java.util.Map<String, Object> row : response.getRows()) {
                    String indexName = (String) row.get("indexname");
                    String tableName = (String) row.get("tablename");
                    String schemaName = (String) row.get("schemaname");
                    
                    if (indexName == null || tableName == null) {
                        continue;
                    }
                    
                    java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                    resultRow.put("Schema", schemaName != null ? schemaName : "public");
                    resultRow.put("Name", indexName);
                    resultRow.put("Type", "index");
                    resultRow.put("Owner", "cassandra");
                    resultRow.put("Table", tableName);
                    ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                    count++;
                }
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error listing indexes: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Handle \\dt+ - List tables with sizes
     */
    private void handleListTablesWithSizes(ChannelHandlerContext ctx, String pattern) {
        try {
            // Query pg_tables from internal keyspace
            String sql = "SELECT schemaname, tablename FROM cassandra_sql_internal.pg_tables WHERE schemaname = 'public'";
            QueryResponse response = queryService.execute(sql, connectionId);
            
            java.util.List<String> columns = java.util.Arrays.asList("Schema", "Name", "Type", "Owner", "Size", "Description");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            // Define internal tables to filter out
            java.util.Set<String> internalTablePrefixes = java.util.Set.of(
                "kv_", "tx_", "pg_", "information_schema_", "system_"
            );
            
            int count = 0;
            if (response.getRows() != null) {
                for (Map<String, Object> row : response.getRows()) {
                    String tableName = (String) row.get("tablename");
                    
                    // Filter out internal tables unless pattern is *.*
                    if (pattern == null || !pattern.equals("*.*")) {
                        boolean isInternal = false;
                        for (String prefix : internalTablePrefixes) {
                            if (tableName.startsWith(prefix)) {
                                isInternal = true;
                                break;
                            }
                        }
                        if (isInternal) {
                            continue;
                        }
                    }
                    
                    // Apply pattern matching
                    if (pattern != null && !pattern.equals("*.*") && !matchesPattern(tableName, pattern)) {
                        continue;
                    }
                    
                    // Get table size from Cassandra (approximate)
                    String sizeStr = getTableSize(tableName);
                    
                    java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                    resultRow.put("Schema", row.get("schemaname"));
                    resultRow.put("Name", tableName);
                    resultRow.put("Type", "table");
                    resultRow.put("Owner", "cassandra");
                    resultRow.put("Size", sizeStr);
                    resultRow.put("Description", "");
                    ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                    count++;
                }
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error listing tables: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Handle \\d+ tablename - Describe table with extended information
     */
    private void handleDescribeTableExtended(ChannelHandlerContext ctx, String tableName) {
        try {
            // Query Cassandra system schema for column information
            com.datastax.oss.driver.api.core.cql.ResultSet rs = 
                session.execute(
                    String.format(
                        "SELECT column_name, type, kind FROM system_schema.columns " +
                        "WHERE keyspace_name = 'cassandra_sql' AND table_name = '%s'",
                        tableName));
            
            java.util.List<String> columns = java.util.Arrays.asList("Column", "Type", "Nullable", "Default", "Storage", "Description");
            ctx.write(new PostgresMessage.RowDescriptionMessage(columns));
            
            int count = 0;
            for (com.datastax.oss.driver.api.core.cql.Row row : rs) {
                String columnName = row.getString("column_name");
                String columnType = row.getString("type");
                String kind = row.getString("kind");
                
                java.util.Map<String, Object> resultRow = new java.util.LinkedHashMap<>();
                resultRow.put("Column", columnName);
                resultRow.put("Type", columnType);
                resultRow.put("Nullable", kind.equals("partition_key") || kind.equals("clustering") ? "not null" : "");
                resultRow.put("Default", "");
                resultRow.put("Storage", "plain");
                resultRow.put("Description", "");
                ctx.write(new PostgresMessage.DataRowMessage(resultRow, columns));
                count++;
            }
            
            // Add indexes information
            String indexQuery = String.format(
                "SELECT index_name FROM system_schema.indexes " +
                "WHERE keyspace_name = 'cassandra_sql' AND table_name = '%s'",
                tableName);
            com.datastax.oss.driver.api.core.cql.ResultSet indexRs = session.execute(indexQuery);
            
            java.util.List<String> indexes = new java.util.ArrayList<>();
            for (com.datastax.oss.driver.api.core.cql.Row row : indexRs) {
                indexes.add(row.getString("index_name"));
            }
            
            ctx.write(new PostgresMessage.CommandCompleteMessage("SELECT " + count));
            
            // Send indexes as notice
            if (!indexes.isEmpty()) {
                String indexInfo = "Indexes:\n";
                for (String idx : indexes) {
                    indexInfo += "    \"" + idx + "\"\n";
                }
                sendNoticeMessage(ctx, indexInfo);
            }
            
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        } catch (Exception e) {
            ctx.write(new PostgresMessage.ErrorResponseMessage("Error describing table: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    /**
     * Get approximate table size
     */
    private String getTableSize(String tableName) {
        try {
            // Query Cassandra system views for table size
            // Note: This is approximate and may not be available in all Cassandra versions
            return "N/A";  // Placeholder - would need nodetool or JMX access for accurate size
        } catch (Exception e) {
            return "N/A";
        }
    }
    
    /**
     * Send a notice message to the client
     */
    private void sendNoticeMessage(ChannelHandlerContext ctx, String message) {
        ctx.write(new PostgresMessage.NoticeResponseMessage(message));
    }
    
    // ========== COPY Protocol Handlers ==========
    
    /**
     * State for COPY FROM STDIN operation
     */
    private static class CopyState {
        String tableName;
        java.util.List<String> columns;
        java.util.List<String> dataLines = new java.util.ArrayList<>();
        
        CopyState(String tableName, java.util.List<String> columns) {
            this.tableName = tableName;
            this.columns = columns;
        }
    }
    
    private CopyState copyState = null;
    
    private void handleCopyFromStdin(ChannelHandlerContext ctx, String sql) {
        try {
            log.debug("📥 COPY FROM STDIN: " + sql);
            
            // Parse COPY command: COPY table_name [(col1, col2, ...)] FROM STDIN
            String sqlUpper = sql.toUpperCase();
            int copyPos = sqlUpper.indexOf("COPY");
            int fromPos = sqlUpper.indexOf(" FROM ");
            
            if (copyPos == -1 || fromPos == -1) {
                ctx.write(new PostgresMessage.ErrorResponseMessage("Invalid COPY syntax"));
                char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
                ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
                return;
            }
            
            String tableSpec = sql.substring(copyPos + 4, fromPos).trim();
            
            // Extract table name and optional column list
            String tableName;
            java.util.List<String> columns = null;
            
            int parenPos = tableSpec.indexOf('(');
            if (parenPos > 0) {
                tableName = tableSpec.substring(0, parenPos).trim();
                int endParen = tableSpec.indexOf(')');
                if (endParen > parenPos) {
                    String colList = tableSpec.substring(parenPos + 1, endParen);
                    columns = java.util.Arrays.asList(colList.split(","));
                    // Trim whitespace from column names
                    columns = columns.stream()
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toList());
                }
            } else {
                tableName = tableSpec;
            }
            
            log.debug("   Table: " + tableName + ", Columns: " + columns);
            
            // Initialize COPY state
            copyState = new CopyState(tableName, columns);
            
            // Send CopyInResponse to client
            int numColumns = (columns != null) ? columns.size() : 0;
            ctx.writeAndFlush(new PostgresMessage.CopyInResponseMessage(numColumns));
            
        } catch (Exception e) {
            log.error("COPY FROM STDIN error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("COPY error: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
        }
    }
    
    private void handleCopyData(ChannelHandlerContext ctx, PostgresMessage.CopyDataMessage msg) {
        if (copyState == null) {
            log.error("Received CopyData but no COPY operation in progress");
            return;
        }
        
        try {
            // Parse the data (tab-separated values, one row per line)
            String data = new String(msg.getData(), java.nio.charset.StandardCharsets.UTF_8);
            
            // Split into lines and add to buffer
            String[] lines = data.split("\n");
            for (String line : lines) {
                if (!line.trim().isEmpty() && !line.equals("\\.")) {
                    copyState.dataLines.add(line);
                }
            }
            
        } catch (Exception e) {
            log.error("Error processing COPY data: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleCopyDone(ChannelHandlerContext ctx) {
        if (copyState == null) {
            log.error("Received CopyDone but no COPY operation in progress");
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
            return;
        }
        
        try {
            log.debug("📥 COPY complete: " + copyState.dataLines.size() + " rows received");
            
            // Execute INSERT statements for each row
            int rowsInserted = 0;
            for (String line : copyState.dataLines) {
                // Parse tab-separated values
                String[] values = line.split("\t");
                
                // Build INSERT statement
                StringBuilder insertSql = new StringBuilder();
                insertSql.append("INSERT INTO ").append(copyState.tableName);
                
                if (copyState.columns != null && !copyState.columns.isEmpty()) {
                    insertSql.append(" (");
                    insertSql.append(String.join(", ", copyState.columns));
                    insertSql.append(")");
                }
                
                insertSql.append(" VALUES (");
                for (int i = 0; i < values.length; i++) {
                    if (i > 0) insertSql.append(", ");
                    
                    String value = values[i];
                    if (value.equals("\\N")) {
                        // NULL value
                        insertSql.append("NULL");
                    } else {
                        // Escape single quotes and wrap in quotes
                        value = value.replace("'", "''");
                        insertSql.append("'").append(value).append("'");
                    }
                }
                insertSql.append(")");
                
                // Execute INSERT
                try {
                    queryService.execute(insertSql.toString(), connectionId);
                    rowsInserted++;
                } catch (Exception e) {
                    log.error("Failed to insert row: " + e.getMessage());
                    // Continue with other rows
                }
            }
            
            // Send CommandComplete
            ctx.write(new PostgresMessage.CommandCompleteMessage("COPY " + rowsInserted));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
            
            // Clear COPY state
            copyState = null;
            
        } catch (Exception e) {
            log.error("COPY completion error: " + e.getMessage());
            e.printStackTrace();
            ctx.write(new PostgresMessage.ErrorResponseMessage("COPY error: " + e.getMessage()));
            char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
            ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
            copyState = null;
        }
    }
    
    private void handleCopyFail(ChannelHandlerContext ctx, PostgresMessage.CopyFailMessage msg) {
        log.error("COPY failed: " + msg.getErrorMessage());
        copyState = null;
        char status = transactionManager.hasActiveTransaction(connectionId) ? 'T' : 'I';
        ctx.writeAndFlush(new PostgresMessage.ReadyForQueryMessage(status));
    }
}

