package com.geico.poc.cassandrasql.kv;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata for a PostgreSQL database.
 * 
 * Each database maps to a Cassandra keyspace with its own replication strategy.
 */
public class DatabaseMetadata {
    
    private final int databaseId;
    private final String databaseName;
    private final String cassandraKeyspace;
    private final ReplicationConfig replicationConfig;
    private final Instant createdAt;
    private final String owner;
    
    public DatabaseMetadata(
            int databaseId,
            String databaseName,
            String cassandraKeyspace,
            ReplicationConfig replicationConfig,
            Instant createdAt,
            String owner) {
        this.databaseId = databaseId;
        this.databaseName = databaseName;
        this.cassandraKeyspace = cassandraKeyspace;
        this.replicationConfig = replicationConfig;
        this.createdAt = createdAt;
        this.owner = owner;
    }
    
    public int getDatabaseId() {
        return databaseId;
    }
    
    public String getDatabaseName() {
        return databaseName;
    }
    
    public String getCassandraKeyspace() {
        return cassandraKeyspace;
    }
    
    public ReplicationConfig getReplicationConfig() {
        return replicationConfig;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public String getOwner() {
        return owner;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseMetadata that = (DatabaseMetadata) o;
        return databaseId == that.databaseId;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(databaseId);
    }
    
    @Override
    public String toString() {
        return "DatabaseMetadata{" +
                "databaseId=" + databaseId +
                ", databaseName='" + databaseName + '\'' +
                ", cassandraKeyspace='" + cassandraKeyspace + '\'' +
                ", replicationConfig=" + replicationConfig +
                ", createdAt=" + createdAt +
                ", owner='" + owner + '\'' +
                '}';
    }
    
    /**
     * Builder for DatabaseMetadata
     */
    public static class Builder {
        private int databaseId;
        private String databaseName;
        private String cassandraKeyspace;
        private ReplicationConfig replicationConfig;
        private Instant createdAt;
        private String owner;
        
        public Builder databaseId(int databaseId) {
            this.databaseId = databaseId;
            return this;
        }
        
        public Builder databaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }
        
        public Builder cassandraKeyspace(String cassandraKeyspace) {
            this.cassandraKeyspace = cassandraKeyspace;
            return this;
        }
        
        public Builder replicationConfig(ReplicationConfig replicationConfig) {
            this.replicationConfig = replicationConfig;
            return this;
        }
        
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }
        
        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }
        
        public DatabaseMetadata build() {
            return new DatabaseMetadata(
                databaseId,
                databaseName,
                cassandraKeyspace,
                replicationConfig,
                createdAt,
                owner
            );
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}



