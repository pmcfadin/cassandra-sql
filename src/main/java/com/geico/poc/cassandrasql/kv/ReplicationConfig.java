package com.geico.poc.cassandrasql.kv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Replication configuration for a Cassandra keyspace.
 * 
 * Supports both SimpleStrategy and NetworkTopologyStrategy.
 */
public class ReplicationConfig {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private final String strategy;
    private final Map<String, Integer> datacenters;  // For NetworkTopologyStrategy
    private final Integer replicationFactor;  // For SimpleStrategy
    
    @JsonCreator
    public ReplicationConfig(
            @JsonProperty("strategy") String strategy,
            @JsonProperty("datacenters") Map<String, Integer> datacenters,
            @JsonProperty("replicationFactor") Integer replicationFactor) {
        this.strategy = strategy;
        this.datacenters = datacenters != null ? new HashMap<>(datacenters) : new HashMap<>();
        this.replicationFactor = replicationFactor;
        
        validate();
    }
    
    private void validate() {
        if (strategy == null) {
            throw new IllegalArgumentException("Replication strategy cannot be null");
        }
        
        if (strategy.equals("SimpleStrategy")) {
            if (replicationFactor == null || replicationFactor < 1) {
                throw new IllegalArgumentException(
                    "SimpleStrategy requires replication_factor >= 1"
                );
            }
        } else if (strategy.equals("NetworkTopologyStrategy")) {
            if (datacenters.isEmpty()) {
                throw new IllegalArgumentException(
                    "NetworkTopologyStrategy requires at least one datacenter"
                );
            }
            for (Map.Entry<String, Integer> entry : datacenters.entrySet()) {
                if (entry.getValue() < 0) {
                    throw new IllegalArgumentException(
                        "Replication factor for datacenter '" + entry.getKey() + 
                        "' must be >= 0"
                    );
                }
            }
        } else {
            throw new IllegalArgumentException(
                "Unsupported replication strategy: " + strategy + 
                ". Supported: SimpleStrategy, NetworkTopologyStrategy"
            );
        }
    }
    
    public String getStrategy() {
        return strategy;
    }
    
    public Map<String, Integer> getDatacenters() {
        return new HashMap<>(datacenters);
    }
    
    public Integer getReplicationFactor() {
        return replicationFactor;
    }
    
    /**
     * Convert to CQL replication map string.
     * 
     * Examples:
     * - SimpleStrategy: {'class': 'SimpleStrategy', 'replication_factor': 3}
     * - NetworkTopologyStrategy: {'class': 'NetworkTopologyStrategy', 'DC1': 3, 'DC2': 3}
     */
    public String toCqlString() {
        StringBuilder sb = new StringBuilder("{'class': '");
        sb.append(strategy).append("'");
        
        if (strategy.equals("SimpleStrategy")) {
            sb.append(", 'replication_factor': ").append(replicationFactor);
        } else if (strategy.equals("NetworkTopologyStrategy")) {
            for (Map.Entry<String, Integer> entry : datacenters.entrySet()) {
                sb.append(", '").append(entry.getKey()).append("': ")
                  .append(entry.getValue());
            }
        }
        
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * Serialize to JSON string for storage.
     */
    public String toJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ReplicationConfig to JSON", e);
        }
    }
    
    /**
     * Deserialize from JSON string.
     */
    public static ReplicationConfig fromJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, ReplicationConfig.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize ReplicationConfig from JSON", e);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReplicationConfig that = (ReplicationConfig) o;
        return Objects.equals(strategy, that.strategy) &&
               Objects.equals(datacenters, that.datacenters) &&
               Objects.equals(replicationFactor, that.replicationFactor);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(strategy, datacenters, replicationFactor);
    }
    
    @Override
    public String toString() {
        return "ReplicationConfig{" +
                "strategy='" + strategy + '\'' +
                ", datacenters=" + datacenters +
                ", replicationFactor=" + replicationFactor +
                '}';
    }
    
    // ========================================
    // Factory Methods
    // ========================================
    
    /**
     * Create SimpleStrategy configuration.
     */
    public static ReplicationConfig simpleStrategy(int replicationFactor) {
        return new ReplicationConfig("SimpleStrategy", null, replicationFactor);
    }
    
    /**
     * Create NetworkTopologyStrategy configuration.
     */
    public static ReplicationConfig networkTopologyStrategy(Map<String, Integer> datacenters) {
        return new ReplicationConfig("NetworkTopologyStrategy", datacenters, null);
    }
    
    /**
     * Create default configuration (NetworkTopologyStrategy with DC1=3).
     */
    public static ReplicationConfig defaultConfig() {
        Map<String, Integer> datacenters = new HashMap<>();
        datacenters.put("DC1", 3);
        return networkTopologyStrategy(datacenters);
    }
}



