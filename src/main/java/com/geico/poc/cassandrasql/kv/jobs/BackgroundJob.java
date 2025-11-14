package com.geico.poc.cassandrasql.kv.jobs;

/**
 * Interface for background maintenance jobs in KV mode.
 * 
 * Background jobs run periodically to maintain the KV store:
 * - Vacuum: Clean up old MVCC versions and deleted rows
 * - Statistics: Collect table statistics for cost-based optimizer
 * - Compaction: Trigger Cassandra compaction
 */
public interface BackgroundJob {
    
    /**
     * Execute the background job
     */
    void execute();
    
    /**
     * Get job name for logging
     */
    String getName();
    
    /**
     * Get initial delay before first execution (milliseconds)
     */
    long getInitialDelayMs();
    
    /**
     * Get period between executions (milliseconds)
     */
    long getPeriodMs();
    
    /**
     * Check if job is enabled
     */
    boolean isEnabled();
}



