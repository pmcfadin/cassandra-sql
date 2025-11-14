package com.geico.poc.cassandrasql.postgres;

/**
 * Base class for PostgreSQL protocol messages
 */
public abstract class PostgresMessage {
    
    public static class StartupMessage extends PostgresMessage {
        private final String user;
        private final String database;
        
        public StartupMessage(String user, String database) {
            this.user = user;
            this.database = database;
        }
        
        public String getUser() {
            return user;
        }
        
        public String getDatabase() {
            return database;
        }
    }
    
    public static class QueryMessage extends PostgresMessage {
        private final String sql;
        
        public QueryMessage(String sql) {
            this.sql = sql;
        }
        
        public String getSql() {
            return sql;
        }
    }
    
    public static class TerminateMessage extends PostgresMessage {
    }
    
    public static class AuthenticationOkMessage extends PostgresMessage {
    }
    
    public static class ParameterStatusMessage extends PostgresMessage {
        private final String name;
        private final String value;
        
        public ParameterStatusMessage(String name, String value) {
            this.name = name;
            this.value = value;
        }
        
        public String getName() {
            return name;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    public static class ReadyForQueryMessage extends PostgresMessage {
        private final char status; // 'I' = idle, 'T' = in transaction, 'E' = error
        
        public ReadyForQueryMessage(char status) {
            this.status = status;
        }
        
        public char getStatus() {
            return status;
        }
    }
    
    public static class RowDescriptionMessage extends PostgresMessage {
        private final java.util.List<String> columns;
        
        public RowDescriptionMessage(java.util.List<String> columns) {
            this.columns = columns;
        }
        
        public java.util.List<String> getColumns() {
            return columns;
        }
    }
    
    public static class DataRowMessage extends PostgresMessage {
        private final java.util.List<Object> values;
        
        public DataRowMessage(java.util.Map<String, Object> row, java.util.List<String> columns) {
            this.values = new java.util.ArrayList<>();
            for (String column : columns) {
                values.add(row.get(column));
            }
        }
        
        public java.util.List<Object> getValues() {
            return values;
        }
    }
    
    public static class CommandCompleteMessage extends PostgresMessage {
        private final String tag;
        
        public CommandCompleteMessage(String tag) {
            this.tag = tag;
        }
        
        public String getTag() {
            return tag;
        }
    }
    
    public static class ErrorResponseMessage extends PostgresMessage {
        private final String message;
        
        public ErrorResponseMessage(String message) {
            this.message = message;
        }
        
        public String getMessage() {
            return message;
        }
    }
    
    /**
     * Notice Response message (similar to Error but non-fatal)
     */
    public static class NoticeResponseMessage extends PostgresMessage {
        private final String message;
        
        public NoticeResponseMessage(String message) {
            this.message = message;
        }
        
        public String getMessage() {
            return message;
        }
    }
    
    // ========== Prepared Statement Messages ==========
    
    public static class ParseMessage extends PostgresMessage {
        private final String statementName;
        private final String query;
        private final int[] paramTypes;
        
        public ParseMessage(String statementName, String query, int[] paramTypes) {
            this.statementName = statementName;
            this.query = query;
            this.paramTypes = paramTypes;
        }
        
        public String getStatementName() {
            return statementName;
        }
        
        public String getQuery() {
            return query;
        }
        
        public int[] getParamTypes() {
            return paramTypes;
        }
    }
    
    public static class BindMessage extends PostgresMessage {
        private final String portalName;
        private final String statementName;
        private final java.util.List<String> paramValues;
        
        public BindMessage(String portalName, String statementName, java.util.List<String> paramValues) {
            this.portalName = portalName;
            this.statementName = statementName;
            this.paramValues = paramValues;
        }
        
        public String getPortalName() {
            return portalName;
        }
        
        public String getStatementName() {
            return statementName;
        }
        
        public java.util.List<String> getParamValues() {
            return paramValues;
        }
    }
    
    public static class DescribeMessage extends PostgresMessage {
        private final char type; // 'S' = statement, 'P' = portal
        private final String name;
        
        public DescribeMessage(char type, String name) {
            this.type = type;
            this.name = name;
        }
        
        public char getType() {
            return type;
        }
        
        public String getName() {
            return name;
        }
    }
    
    public static class ExecuteMessage extends PostgresMessage {
        private final String portalName;
        private final int maxRows; // 0 = unlimited
        
        public ExecuteMessage(String portalName, int maxRows) {
            this.portalName = portalName;
            this.maxRows = maxRows;
        }
        
        public String getPortalName() {
            return portalName;
        }
        
        public int getMaxRows() {
            return maxRows;
        }
    }
    
    public static class SyncMessage extends PostgresMessage {
    }
    
    public static class CloseMessage extends PostgresMessage {
        private final char type; // 'S' = statement, 'P' = portal
        private final String name;
        
        public CloseMessage(char type, String name) {
            this.type = type;
            this.name = name;
        }
        
        public char getType() {
            return type;
        }
        
        public String getName() {
            return name;
        }
    }
    
    public static class ParseCompleteMessage extends PostgresMessage {
    }
    
    public static class BindCompleteMessage extends PostgresMessage {
    }
    
    public static class CloseCompleteMessage extends PostgresMessage {
    }
    
    public static class NoDataMessage extends PostgresMessage {
    }
    
    public static class ParameterDescriptionMessage extends PostgresMessage {
        private final int[] paramTypes;
        
        public ParameterDescriptionMessage(int[] paramTypes) {
            this.paramTypes = paramTypes;
        }
        
        public int[] getParamTypes() {
            return paramTypes;
        }
    }
    
    // ========== COPY Protocol Messages ==========
    
    /**
     * CopyInResponse - Server tells client to start sending COPY data
     */
    public static class CopyInResponseMessage extends PostgresMessage {
        private final byte format; // 0 = text, 1 = binary
        private final int numColumns;
        
        public CopyInResponseMessage(int numColumns) {
            this.format = 0; // Text format
            this.numColumns = numColumns;
        }
        
        public byte getFormat() {
            return format;
        }
        
        public int getNumColumns() {
            return numColumns;
        }
    }
    
    /**
     * CopyData - Client sends data rows (or server sends in COPY TO)
     */
    public static class CopyDataMessage extends PostgresMessage {
        private final byte[] data;
        
        public CopyDataMessage(byte[] data) {
            this.data = data;
        }
        
        public byte[] getData() {
            return data;
        }
    }
    
    /**
     * CopyDone - Client signals end of COPY data
     */
    public static class CopyDoneMessage extends PostgresMessage {
    }
    
    /**
     * CopyFail - Client signals COPY failure
     */
    public static class CopyFailMessage extends PostgresMessage {
        private final String errorMessage;
        
        public CopyFailMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}





