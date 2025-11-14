package com.geico.poc.cassandrasql.postgres;

import com.geico.poc.cassandrasql.dto.QueryResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages prepared statements and portals for PostgreSQL protocol
 */
public class PreparedStatementManager {
    
    // Per-connection storage
    private final Map<String, Map<String, PreparedStatement>> connectionStatements = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Portal>> connectionPortals = new ConcurrentHashMap<>();
    
    /**
     * Represents a prepared statement
     */
    public static class PreparedStatement {
        private final String name;
        private final String query;
        private final int[] paramTypes;
        
        public PreparedStatement(String name, String query, int[] paramTypes) {
            this.name = name;
            this.query = query;
            this.paramTypes = paramTypes;
        }
        
        public String getName() {
            return name;
        }
        
        public String getQuery() {
            return query;
        }
        
        public int[] getParamTypes() {
            return paramTypes;
        }
    }
    
    /**
     * Represents a portal (bound statement ready for execution)
     */
    public static class Portal {
        private final String name;
        private final PreparedStatement statement;
        private final List<String> paramValues;
        private QueryResponse cachedResult;
        private int currentRow = 0;
        
        public Portal(String name, PreparedStatement statement, List<String> paramValues) {
            this.name = name;
            this.statement = statement;
            this.paramValues = paramValues;
        }
        
        public String getName() {
            return name;
        }
        
        public PreparedStatement getStatement() {
            return statement;
        }
        
        public List<String> getParamValues() {
            return paramValues;
        }
        
        public String getBoundQuery() {
            String query = statement.getQuery();
            
            // Replace $1, $2, etc. with actual values
            for (int i = 0; i < paramValues.size(); i++) {
                String value = paramValues.get(i);
                String placeholder = "$" + (i + 1);
                
                // Quote string values
                if (value != null && !value.equals("NULL")) {
                    // Check if it's a number
                    try {
                        Double.parseDouble(value);
                        // It's a number, don't quote
                    } catch (NumberFormatException e) {
                        // It's a string, quote it
                        value = "'" + value.replace("'", "''") + "'";
                    }
                } else if (value == null) {
                    value = "NULL";
                }
                
                query = query.replace(placeholder, value);
            }
            
            return query;
        }
        
        public void setCachedResult(QueryResponse result) {
            this.cachedResult = result;
            this.currentRow = 0;
        }
        
        public QueryResponse getCachedResult() {
            return cachedResult;
        }
        
        public int getCurrentRow() {
            return currentRow;
        }
        
        public void setCurrentRow(int row) {
            this.currentRow = row;
        }
    }
    
    /**
     * Create a prepared statement
     */
    public void createStatement(String connectionId, String statementName, String query, int[] paramTypes) {
        Map<String, PreparedStatement> statements = connectionStatements.computeIfAbsent(
            connectionId, k -> new ConcurrentHashMap<>());
        
        PreparedStatement stmt = new PreparedStatement(statementName, query, paramTypes);
        statements.put(statementName, stmt);
        
        System.out.println("📝 Created prepared statement: " + statementName + " for connection " + connectionId);
    }
    
    /**
     * Get a prepared statement
     */
    public PreparedStatement getStatement(String connectionId, String statementName) {
        Map<String, PreparedStatement> statements = connectionStatements.get(connectionId);
        if (statements == null) {
            return null;
        }
        return statements.get(statementName);
    }
    
    /**
     * Create a portal (bind statement with parameters)
     */
    public void createPortal(String connectionId, String portalName, String statementName, List<String> paramValues) {
        PreparedStatement stmt = getStatement(connectionId, statementName);
        if (stmt == null) {
            throw new IllegalArgumentException("Prepared statement not found: " + statementName);
        }
        
        Map<String, Portal> portals = connectionPortals.computeIfAbsent(
            connectionId, k -> new ConcurrentHashMap<>());
        
        Portal portal = new Portal(portalName, stmt, paramValues);
        portals.put(portalName, portal);
        
        System.out.println("🔗 Created portal: " + portalName + " for connection " + connectionId);
    }
    
    /**
     * Get a portal
     */
    public Portal getPortal(String connectionId, String portalName) {
        Map<String, Portal> portals = connectionPortals.get(connectionId);
        if (portals == null) {
            return null;
        }
        return portals.get(portalName);
    }
    
    /**
     * Close a statement
     */
    public void closeStatement(String connectionId, String statementName) {
        Map<String, PreparedStatement> statements = connectionStatements.get(connectionId);
        if (statements != null) {
            statements.remove(statementName);
            System.out.println("🗑️  Closed prepared statement: " + statementName);
        }
    }
    
    /**
     * Close a portal
     */
    public void closePortal(String connectionId, String portalName) {
        Map<String, Portal> portals = connectionPortals.get(connectionId);
        if (portals != null) {
            portals.remove(portalName);
            System.out.println("🗑️  Closed portal: " + portalName);
        }
    }
    
    /**
     * Close all statements and portals for a connection
     */
    public void closeConnection(String connectionId) {
        connectionStatements.remove(connectionId);
        connectionPortals.remove(connectionId);
        System.out.println("🗑️  Closed all prepared statements for connection: " + connectionId);
    }
}



