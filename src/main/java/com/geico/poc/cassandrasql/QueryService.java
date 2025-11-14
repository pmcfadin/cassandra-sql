package com.geico.poc.cassandrasql;

import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.storage.StorageBackend;
import com.geico.poc.cassandrasql.transaction.TransactionalQueryExecutor;
import com.geico.poc.cassandrasql.validation.EnhancedSchemaValidator;
import com.geico.poc.cassandrasql.validation.EnhancedValidationResult;
import com.geico.poc.cassandrasql.validation.SqlValidator;
import com.geico.poc.cassandrasql.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class QueryService {
    
    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    @Autowired
    private CalciteParser parser;

    @Autowired
    private CassandraExecutor executor;
    
    @Autowired
    private HashJoinExecutor joinExecutor;
    
    @Autowired
    private MultiWayJoinExecutor multiWayJoinExecutor;
    
    @Autowired
    private QueryAnalyzer queryAnalyzer;
    
    @Autowired
    private SchemaValidator schemaValidator;
    
    @Autowired
    private ResultProcessor resultProcessor;
    
    @Autowired
    private TransactionalQueryExecutor transactionalExecutor;
    
    @Autowired
    private SqlValidator sqlValidator;
    
    @Autowired
    private EnhancedSchemaValidator enhancedValidator;
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired
    private StorageBackend storageBackend;
    
    @Autowired(required = false)
    private com.geico.poc.cassandrasql.kv.KvTransactionSessionManager kvTransactionSessionManager;

    /**
     * Execute SQL (HTTP endpoint - no connection ID, auto-commit mode)
     */
    public QueryResponse execute(String sql) throws Exception {
        return execute(sql, null);
    }
    
    /**
     * List all tables (delegates to storage backend)
     */
    public List<String> listTables() {
        return storageBackend.listTables();
    }
    
    /**
     * Execute SQL with connection ID (for transaction awareness)
     */
    public QueryResponse execute(String sql, String connectionId) throws Exception {
        // Strip trailing semicolon (psql sends queries with semicolons)
        sql = sql.trim();
        
        // Check if this is a DO block or multiple statements
        if (StatementSplitter.isDoBlock(sql)) {
            return executeDoBlock(sql, connectionId);
        }
        
        List<String> statements = StatementSplitter.split(sql);
        if (statements.size() > 1) {
            return executeMultipleStatements(statements, connectionId);
        }
        
        // Single statement - execute normally
        return executeSingleStatement(sql, connectionId);
    }
    
    /**
     * Execute a DO block (PostgreSQL procedural block)
     */
    private QueryResponse executeDoBlock(String sql, String connectionId) throws Exception {
        // For now, treat DO blocks as no-ops (return success)
        // In the future, we can implement a simple PL/pgSQL interpreter
        log.debug("📦 Executing DO block (treating as no-op for now)");
        return new QueryResponse(Collections.emptyList(), Collections.emptyList());
    }
    
    /**
     * Execute multiple statements in sequence
     */
    private QueryResponse executeMultipleStatements(List<String> statements, String connectionId) throws Exception {
        log.debug("📚 Executing {} statements in sequence", statements.size());
        
        QueryResponse lastResponse = null;
        int successCount = 0;
        
        for (int i = 0; i < statements.size(); i++) {
            String stmt = statements.get(i);
            log.debug("  Statement {}/{}: {}", i + 1, statements.size(), 
                     stmt.length() > 50 ? stmt.substring(0, 50) + "..." : stmt);
            
            try {
                lastResponse = executeSingleStatement(stmt, connectionId);
                successCount++;
                
                // If any statement fails, stop execution
                if (lastResponse.getError() != null && !lastResponse.getError().isEmpty()) {
                    log.error("  ❌ Statement {} failed: {}", i + 1, lastResponse.getError());
                    return lastResponse;
                }
            } catch (Exception e) {
                log.error("  ❌ Statement {} threw exception: {}", i + 1, e.getMessage());
                throw e;
            }
        }
        
        log.debug("  ✅ All {} statements executed successfully", successCount);
        
        // Return the last response (or empty if no statements)
        return lastResponse != null ? lastResponse : new QueryResponse(Collections.emptyList(), Collections.emptyList());
    }
    
    /**
     * Execute a single SQL statement
     */
    private QueryResponse executeSingleStatement(String sql, String connectionId) throws Exception {
        // Strip trailing semicolon
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        
        // 0. Validate SQL for safety (before parsing)
        ValidationResult sqlValidation = sqlValidator.validateRawSql(sql);
        if (sqlValidation.hasErrors()) {
            log.error("❌ SQL validation failed:");
            System.err.println(sqlValidation.getErrorMessage());
            throw new IllegalArgumentException(sqlValidation.getErrorMessage());
        }
        
        // Log SQL validation warnings
        if (sqlValidation.hasWarnings()) {
            log.debug("⚠️  SQL validation warnings:");
            System.out.println(sqlValidation.getWarningMessage());
        }
        
        // 1. Enhanced validation (type checking + schema validation)
        log.debug("Running enhanced validator...");
        EnhancedValidationResult enhancedValidation = enhancedValidator.validate(sql);
        
        if (!enhancedValidation.isValid()) {
            System.err.println(enhancedValidation.getErrorMessage());
            throw new IllegalArgumentException("SQL validation failed:\n" + enhancedValidation.getErrorMessage());
        }
        
        // Log warnings
        if (enhancedValidation.hasWarnings()) {
            System.out.println(enhancedValidation.getWarningMessage());
        }
        
        // Log info
        if (!enhancedValidation.getInfo().isEmpty()) {
            log.debug(enhancedValidation.getInfoMessage());
        }
        
        // 2. Check if this is a UNION query - if so, let storage backend handle it directly
        String sqlUpper = sql.toUpperCase();
        // Use Pattern.DOTALL to match newlines, or simpler: just check if it contains " UNION "
        if (sqlUpper.startsWith("SELECT") && (sqlUpper.contains(" UNION ALL ") || sqlUpper.contains(" UNION "))) {
            log.debug("🔗 Detected UNION query, routing directly to storage backend");
            // Create a minimal ParsedQuery with the raw SQL
            ParsedQuery unionQuery = new ParsedQuery(ParsedQuery.Type.SELECT, (String) null, sql);
            return storageBackend.execute(connectionId, unionQuery, sql);
        }
        
        // 3. Parse SQL (for execution)
        ParsedQuery parsedQuery = parser.parse(sql);
        
        // 4. Check for catalog queries that should bypass storage backend
        if (storageBackend.shouldBypassForCatalogQuery(sql)) {
            log.debug("📋 Routing catalog query to Cassandra (bypassing storage backend)");
            return executor.execute(parsedQuery);
        }

        // 4. Handle transaction commands
        boolean isKvMode = (config != null && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV);
        
        if (parsedQuery.getType() == ParsedQuery.Type.BEGIN) {
            // For HTTP/test requests, we need a connection ID
            // Generate one if not provided
            if (connectionId == null) {
                connectionId = "http-" + System.currentTimeMillis();
            }
            
            if (isKvMode && kvTransactionSessionManager != null) {
                // Use KV transaction session manager
                kvTransactionSessionManager.begin(connectionId);
            } else {
                // Use schema-based transaction session manager
                transactionalExecutor.getSessionManager().begin(connectionId);
            }
            return new QueryResponse(Collections.emptyList(), Collections.emptyList());
            
        } else if (parsedQuery.getType() == ParsedQuery.Type.COMMIT) {
            if (connectionId != null) {
                if (isKvMode && kvTransactionSessionManager != null) {
                    String error = kvTransactionSessionManager.commit(connectionId);
                    if (error != null) {
                        return QueryResponse.error(error);
                    }
                } else {
                    transactionalExecutor.getSessionManager().commit(connectionId);
                }
            }
            return new QueryResponse(Collections.emptyList(), Collections.emptyList());
            
        } else if (parsedQuery.getType() == ParsedQuery.Type.ROLLBACK) {
            if (connectionId != null) {
                if (isKvMode && kvTransactionSessionManager != null) {
                    kvTransactionSessionManager.rollback(connectionId);
                } else {
                    transactionalExecutor.getSessionManager().rollback(connectionId);
                }
            }
            return new QueryResponse(Collections.emptyList(), Collections.emptyList());
        }

        // 5. Route to storage backend
        return storageBackend.execute(connectionId, parsedQuery, sql);
    }
}
