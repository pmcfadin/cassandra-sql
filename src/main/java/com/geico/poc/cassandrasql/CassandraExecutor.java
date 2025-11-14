package com.geico.poc.cassandrasql;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.geico.poc.cassandrasql.dto.QueryResponse;
// Removed: import com.geico.poc.cassandrasql.storage.schema.SchemaPreparedStatements;
import com.geico.poc.cassandrasql.validation.EnhancedSchemaValidator;
import com.geico.poc.cassandrasql.validation.EnhancedValidationResult;
import com.geico.poc.cassandrasql.validation.EnhancedValidationResult.ValidationIssue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.*;

@Component
public class CassandraExecutor {

    private CqlSession session;
    private static final String KEYSPACE = "cassandra_sql";
    
    /**
     * Get the Cassandra session for schema validation
     */
    public CqlSession getSession() {
        return session;
    }

    @PostConstruct
    public void init() {
        try {
            System.out.println("Connecting to Cassandra at localhost:9042...");
            
            // Connect to Cassandra
            session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress("localhost", 9042))
                .withLocalDatacenter("datacenter1")
                .build();

            System.out.println("Connected to Cassandra successfully!");

            // Create keyspace if not exists
            System.out.println("Creating keyspace: " + KEYSPACE);
            session.execute(
                "CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE + 
                " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
            );

            // Use keyspace
            session.execute("USE " + KEYSPACE);
            System.out.println("Using keyspace: " + KEYSPACE);
            
        } catch (Exception e) {
            System.err.println("Failed to connect to Cassandra: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Cannot connect to Cassandra. Make sure it's running on localhost:9042", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (session != null) {
            System.out.println("Closing Cassandra session...");
            session.close();
        }
    }

    public QueryResponse execute(ParsedQuery query) {
        if (query.isJoin()) {
            System.out.println("Executing JOIN query");
            System.out.println("SQL: " + query.getRawSql());
            // JOIN queries are handled by HashJoinExecutor
            throw new IllegalArgumentException("JOIN queries should be routed to HashJoinExecutor");
        }
        
        // Validation is now handled by CalciteParser during parsing
        
        System.out.println("Executing query type: " + query.getType() + " on table: " + query.getTableName());
        System.out.println("SQL: " + query.getRawSql());
        
        switch (query.getType()) {
            case CREATE_TABLE:
                return executeCreateTable(query);
            case CREATE_INDEX:
                return executeCreateIndex(query);
            case DROP_TABLE:
                return executeDropTable(query);
            case INSERT:
                return executeInsert(query);
            case SELECT:
                return executeSelect(query);
            case UPDATE:
                return executeUpdate(query);
            case DELETE:
                return executeDelete(query);
            default:
                throw new IllegalArgumentException("Unsupported query type: " + query.getType());
        }
    }
    
    /**
     * Validate query before execution
     * Note: Validation is now handled by CalciteParser during parsing
     */
    private boolean validateQuery(ParsedQuery query) {
        // Validation is done during parsing by CalciteParser
        // This method is kept for backwards compatibility but always returns true
        return true;
    }

    private QueryResponse executeCreateTable(ParsedQuery query) {
        try {
            // Translate PostgreSQL CREATE TABLE to Cassandra CQL
            String cql = SqlToCqlTranslator.translateCreateTable(query.getRawSql());
            System.out.println("Executing CQL: " + cql);
            session.execute(cql);
            System.out.println("Table created successfully");
            return new QueryResponse(Collections.emptyList(), Collections.emptyList());
        } catch (Exception e) {
            System.err.println("Error creating table: " + e.getMessage());
            throw new RuntimeException("Failed to create table: " + e.getMessage(), e);
        }
    }
    
    private QueryResponse executeCreateIndex(ParsedQuery query) {
        try {
            // Translate PostgreSQL CREATE INDEX to Cassandra CQL
            String cql = SqlToCqlTranslator.translateCreateIndex(query.getRawSql());
            System.out.println("Executing CQL: " + cql);
            session.execute(cql);
            System.out.println("Index created successfully");
            return new QueryResponse(Collections.emptyList(), Collections.emptyList());
        } catch (Exception e) {
            System.err.println("Error creating index: " + e.getMessage());
            throw new RuntimeException("Failed to create index: " + e.getMessage(), e);
        }
    }
    
    private QueryResponse executeDropTable(ParsedQuery query) {
        try {
            // Translate PostgreSQL DROP TABLE to Cassandra CQL
            String cql = SqlToCqlTranslator.translateDropTable(query.getRawSql());
            System.out.println("Executing CQL: " + cql);
            session.execute(cql);
            System.out.println("Table dropped successfully");
            return new QueryResponse(Collections.emptyList(), Collections.emptyList());
        } catch (Exception e) {
            System.err.println("Error dropping table: " + e.getMessage());
            throw new RuntimeException("Failed to drop table: " + e.getMessage(), e);
        }
    }

    private QueryResponse executeInsert(ParsedQuery query) {
        throw new UnsupportedOperationException(
            "Schema mode is no longer supported. Use KV mode (queryService.execute) instead.");
    }

    private QueryResponse executeUpdate(ParsedQuery query) {
        throw new UnsupportedOperationException(
            "Schema mode is no longer supported. Use KV mode (queryService.execute) instead.");
    }

    private QueryResponse executeDelete(ParsedQuery query) {
        throw new UnsupportedOperationException(
            "Schema mode is no longer supported. Use KV mode (queryService.execute) instead.");
    }

    private QueryResponse executeSelect(ParsedQuery query) {
        throw new UnsupportedOperationException(
            "Schema mode is no longer supported. Use KV mode (queryService.execute) instead.");
    }

    private String convertToCQL(String sql) {
        // Use SQL-to-CQL translator for proper conversion
        return SqlToCqlTranslator.translate(sql);
    }
    
    /**
     * Execute raw CQL (for transaction commits)
     */
    public void executeCQL(String cql) {
        try {
            session.execute(cql);
        } catch (Exception e) {
            System.err.println("Error executing CQL: " + e.getMessage());
            throw new RuntimeException("Failed to execute CQL: " + e.getMessage(), e);
        }
    }
}

