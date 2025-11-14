package com.geico.poc.cassandrasql.kv.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler for background maintenance jobs in KV mode.
 * 
 * Manages periodic execution of:
 * - Vacuum job (clean up old MVCC versions)
 * - Statistics collection job (for cost-based optimizer)
 * - Compaction job (trigger Cassandra compaction)
 * 
 * Only runs in KV storage mode.
 */
@Component
public class BackgroundJobScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(BackgroundJobScheduler.class);
    
    @Autowired
    private CassandraSqlConfig config;
    
    @Autowired(required = false)
    private List<BackgroundJob> jobs;
    
    private ScheduledExecutorService scheduler;
    
    @PostConstruct
    public void start() {
        // Only run background jobs in KV mode
        if (config.getStorageMode() != CassandraSqlConfig.StorageMode.KV) {
            log.info("📋 Background jobs disabled (not in KV mode)");
            return;
        }
        
        if (jobs == null || jobs.isEmpty()) {
            log.info("📋 No background jobs configured");
            return;
        }
        
        // Create scheduler with one thread per job
        scheduler = Executors.newScheduledThreadPool(
            jobs.size(),
            r -> {
                Thread t = new Thread(r);
                t.setName("background-job-" + t.getId());
                t.setDaemon(true);
                return t;
            }
        );
        
        // Schedule all enabled jobs
        int scheduledCount = 0;
        for (BackgroundJob job : jobs) {
            if (job.isEnabled()) {
                scheduleJob(job);
                scheduledCount++;
            } else {
                log.info("📋 Background job disabled: " + job.getName());
            }
        }
        
        log.info("✅ Background job scheduler started (" + scheduledCount + " jobs)");
    }
    
    private void scheduleJob(BackgroundJob job) {
        log.info("📋 Scheduling background job: " + job.getName() + 
                          " (period: " + job.getPeriodMs() / 1000 + "s)");
        
        scheduler.scheduleAtFixedRate(
            () -> executeJob(job),
            job.getInitialDelayMs(),
            job.getPeriodMs(),
            TimeUnit.MILLISECONDS
        );
    }
    
    private void executeJob(BackgroundJob job) {
        try {
            long startTime = System.currentTimeMillis();
            log.info("🔄 Running background job: " + job.getName());
            
            job.execute();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Background job completed: " + job.getName() + 
                              " (duration: " + duration + "ms)");
            
        } catch (Exception e) {
            log.error("❌ Background job failed: " + job.getName());
            e.printStackTrace();
            // Don't rethrow - let scheduler continue
        }
    }
    
    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            log.info("🛑 Stopping background job scheduler...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("✅ Background job scheduler stopped");
        }
    }
}

