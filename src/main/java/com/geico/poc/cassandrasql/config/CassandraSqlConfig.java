package com.geico.poc.cassandrasql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Cassandra SQL application
 */
@Configuration
@ConfigurationProperties(prefix = "cassandra-sql")
public class CassandraSqlConfig {
    
    private StorageMode storageMode = StorageMode.SCHEMA;
    private KvStorageConfig kvStorage = new KvStorageConfig();
    private BackgroundJobsConfig backgroundJobs = new BackgroundJobsConfig();
    private QueryOptimizerConfig queryOptimizer = new QueryOptimizerConfig();
    
    public enum StorageMode {
        /**
         * Schema-mapped mode: Use Cassandra's native schema (tables, columns, types).
         * Direct mapping: SQL table → Cassandra table.
         * This is the default mode.
         */
        SCHEMA,
        
        /**
         * Key-value mode: Use Cassandra as a pure key-value store.
         * Encode SQL schema, data, and metadata into Cassandra keys and values.
         * Similar to TiDB/TiKV and CockroachDB/RocksDB architecture.
         */
        KV
    }
    
    public StorageMode getStorageMode() {
        return storageMode;
    }
    
    public void setStorageMode(StorageMode storageMode) {
        this.storageMode = storageMode;
    }
    
    public KvStorageConfig getKvStorage() {
        return kvStorage;
    }
    
    public void setKvStorage(KvStorageConfig kvStorage) {
        this.kvStorage = kvStorage;
    }
    
    public BackgroundJobsConfig getBackgroundJobs() {
        return backgroundJobs;
    }
    
    public void setBackgroundJobs(BackgroundJobsConfig backgroundJobs) {
        this.backgroundJobs = backgroundJobs;
    }
    
    public QueryOptimizerConfig getQueryOptimizer() {
        return queryOptimizer;
    }
    
    public void setQueryOptimizer(QueryOptimizerConfig queryOptimizer) {
        this.queryOptimizer = queryOptimizer;
    }
    
    /**
     * Configuration for key-value storage mode
     */
    public static class KvStorageConfig {
        private MvccConfig mvcc = new MvccConfig();
        private TransactionConfig transaction = new TransactionConfig();
        private EncodingConfig encoding = new EncodingConfig();
        private PerformanceConfig performance = new PerformanceConfig();
        
        public MvccConfig getMvcc() {
            return mvcc;
        }
        
        public void setMvcc(MvccConfig mvcc) {
            this.mvcc = mvcc;
        }
        
        public TransactionConfig getTransaction() {
            return transaction;
        }
        
        public void setTransaction(TransactionConfig transaction) {
            this.transaction = transaction;
        }
        
        public EncodingConfig getEncoding() {
            return encoding;
        }
        
        public void setEncoding(EncodingConfig encoding) {
            this.encoding = encoding;
        }
        
        public PerformanceConfig getPerformance() {
            return performance;
        }
        
        public void setPerformance(PerformanceConfig performance) {
            this.performance = performance;
        }
    }
    
    /**
     * MVCC (Multi-Version Concurrency Control) configuration
     */
    public static class MvccConfig {
        private boolean enabled = true;
        private long gcRetentionSeconds = 86400;  // 24 hours
        private long gcIntervalSeconds = 3600;    // 1 hour
        private int maxVersionsPerKey = 10;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public long getGcRetentionSeconds() {
            return gcRetentionSeconds;
        }
        
        public void setGcRetentionSeconds(long gcRetentionSeconds) {
            this.gcRetentionSeconds = gcRetentionSeconds;
        }
        
        public long getGcIntervalSeconds() {
            return gcIntervalSeconds;
        }
        
        public void setGcIntervalSeconds(long gcIntervalSeconds) {
            this.gcIntervalSeconds = gcIntervalSeconds;
        }
        
        public int getMaxVersionsPerKey() {
            return maxVersionsPerKey;
        }
        
        public void setMaxVersionsPerKey(int maxVersionsPerKey) {
            this.maxVersionsPerKey = maxVersionsPerKey;
        }
    }
    
    /**
     * Transaction configuration for KV mode
     */
    public static class TransactionConfig {
        private long timeoutSeconds = 30;
        private long lockTtlSeconds = 60;
        
        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }
        
        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
        
        public long getLockTtlSeconds() {
            return lockTtlSeconds;
        }
        
        public void setLockTtlSeconds(long lockTtlSeconds) {
            this.lockTtlSeconds = lockTtlSeconds;
        }
    }
    
    /**
     * Encoding configuration for KV mode
     */
    public static class EncodingConfig {
        private boolean compression = false;
        private boolean encryption = false;
        
        public boolean isCompression() {
            return compression;
        }
        
        public void setCompression(boolean compression) {
            this.compression = compression;
        }
        
        public boolean isEncryption() {
            return encryption;
        }
        
        public void setEncryption(boolean encryption) {
            this.encryption = encryption;
        }
    }
    
    /**
     * Performance configuration for KV mode
     */
    public static class PerformanceConfig {
        private int batchSize = 1000;
        private int scanPageSize = 100;
        
        public int getBatchSize() {
            return batchSize;
        }
        
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
        
        public int getScanPageSize() {
            return scanPageSize;
        }
        
        public void setScanPageSize(int scanPageSize) {
            this.scanPageSize = scanPageSize;
        }
    }
    
    /**
     * Configuration for background jobs (vacuum, statistics, etc.)
     */
    public static class BackgroundJobsConfig {
        /**
         * Rate limit for background jobs that scan the database.
         * Default: 1000 keys per hour to minimize impact on read latency.
         */
        private long keysPerHour = 1000000;
        
        /**
         * Whether rate limiting is enabled for background jobs.
         * Default: true (enabled)
         */
        private boolean rateLimitEnabled = true;
        
        public long getKeysPerHour() {
            return keysPerHour;
        }
        
        public void setKeysPerHour(long keysPerHour) {
            if (keysPerHour <= 0) {
                throw new IllegalArgumentException("keysPerHour must be positive, got: " + keysPerHour);
            }
            this.keysPerHour = keysPerHour;
        }
        
        public boolean isRateLimitEnabled() {
            return rateLimitEnabled;
        }
        
        public void setRateLimitEnabled(boolean rateLimitEnabled) {
            this.rateLimitEnabled = rateLimitEnabled;
        }
    }
    
    /**
     * Configuration for query optimizer (Calcite CBO)
     */
    public static class QueryOptimizerConfig {
        /**
         * Enable Calcite cost-based optimizer.
         * When enabled, queries are parsed and optimized using Apache Calcite.
         * Default: false (use legacy parser for stability)
         */
        private boolean enableCalciteCbo = false;
        
        /**
         * Enable plan caching.
         * Caches optimized query plans to avoid re-optimization.
         * Default: true
         */
        private boolean enablePlanCache = true;
        
        /**
         * Maximum number of cached plans.
         * Default: 1000
         */
        private int planCacheSize = 1000;
        
        /**
         * Enable query plan logging.
         * Logs the optimized query plan for debugging.
         * Default: false
         */
        private boolean logQueryPlans = false;
        
        public boolean isEnableCalciteCbo() {
            return enableCalciteCbo;
        }
        
        public void setEnableCalciteCbo(boolean enableCalciteCbo) {
            this.enableCalciteCbo = enableCalciteCbo;
        }
        
        public boolean isEnablePlanCache() {
            return enablePlanCache;
        }
        
        public void setEnablePlanCache(boolean enablePlanCache) {
            this.enablePlanCache = enablePlanCache;
        }
        
        public int getPlanCacheSize() {
            return planCacheSize;
        }
        
        public void setPlanCacheSize(int planCacheSize) {
            this.planCacheSize = planCacheSize;
        }
        
        public boolean isLogQueryPlans() {
            return logQueryPlans;
        }
        
        public void setLogQueryPlans(boolean logQueryPlans) {
            this.logQueryPlans = logQueryPlans;
        }
    }
}


