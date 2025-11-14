package com.geico.poc.cassandrasql.kv.jobs;

import com.geico.poc.cassandrasql.ParsedQuery;
import com.geico.poc.cassandrasql.config.CassandraSqlConfig;
import com.geico.poc.cassandrasql.dto.QueryResponse;
import com.geico.poc.cassandrasql.kv.KvQueryExecutor;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import com.geico.poc.cassandrasql.kv.ViewMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background job to automatically refresh materialized views on a schedule.
 * Similar to pg_cron for PostgreSQL.
 * 
 * Supports:
 * - Scheduled refresh at fixed intervals
 * - Per-view refresh schedules
 * - Manual trigger via SQL command
 * - Rate limiting to avoid overwhelming the system
 */
@Component
@ConfigurationProperties(prefix = "cassandra-sql.kv.materialized-view-refresh")
public class MaterializedViewRefreshJob implements BackgroundJob {
    
    private static final Logger log = LoggerFactory.getLogger(MaterializedViewRefreshJob.class);
    
    @Autowired
    private SchemaManager schemaManager;
    
    @Autowired
    private KvQueryExecutor queryExecutor;
    
    @Autowired
    private CassandraSqlConfig config;
    
    // Configuration properties
    private boolean enabled = true;
    private int defaultRefreshIntervalMinutes = 60; // Default: refresh every hour
    private int maxConcurrentRefreshes = 3;
    private List<String> excludeViews = new ArrayList<>();
    
    // Per-view refresh schedules (view name -> interval in minutes)
    private Map<String, Integer> viewRefreshSchedules = new ConcurrentHashMap<>();
    
    // Track last refresh time for each view
    private Map<String, Long> lastRefreshTimes = new ConcurrentHashMap<>();
    
    @Override
    public String getName() {
        return "MaterializedViewRefreshJob";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled && config.getStorageMode() == CassandraSqlConfig.StorageMode.KV;
    }
    
    @Override
    public long getInitialDelayMs() {
        // Start after 2 minutes to allow system to stabilize
        return 2 * 60 * 1000L;
    }
    
    @Override
    public long getPeriodMs() {
        // Run every 5 minutes to check for views that need refresh
        // Individual views have their own refresh intervals
        return 5 * 60 * 1000L;
    }
    
    @Override
    public void execute() {
        if (!isEnabled()) {
            return;
        }
        
        try {
            log.debug("🔄 Running materialized view refresh job");
            
            // Get all materialized views
            List<ViewMetadata> views = schemaManager.getAllViews().stream()
                .filter(ViewMetadata::isMaterialized)
                .filter(v -> !v.isDropped())
                .filter(v -> !excludeViews.contains(v.getViewName()))
                .toList();
            
            if (views.isEmpty()) {
                log.debug("No materialized views to refresh");
                return;
            }
            
            log.info("Found {} materialized views, checking refresh schedules", views.size());
            
            int refreshed = 0;
            int skipped = 0;
            long now = System.currentTimeMillis();
            
            for (ViewMetadata view : views) {
                try {
                    // Check if this view needs refresh
                    if (shouldRefreshView(view, now)) {
                        log.info("🔄 Refreshing materialized view: {}", view.getViewName());
                        
                        // Execute REFRESH MATERIALIZED VIEW
                        String refreshSql = "REFRESH MATERIALIZED VIEW " + view.getViewName();
                        ParsedQuery query = new ParsedQuery(
                            ParsedQuery.Type.REFRESH_MATERIALIZED_VIEW,
                            view.getViewName(),
                            refreshSql
                        );
                        
                        QueryResponse response = queryExecutor.execute(query);
                        
                        if (response.getError() != null) {
                            log.error("Failed to refresh view {}: {}", view.getViewName(), response.getError());
                        } else {
                            log.info("✅ Refreshed view {} ({} rows)", view.getViewName(), response.getRowCount());
                            lastRefreshTimes.put(view.getViewName(), now);
                            refreshed++;
                        }
                    } else {
                        skipped++;
                    }
                    
                    // Respect max concurrent refreshes
                    if (refreshed >= maxConcurrentRefreshes) {
                        log.info("Reached max concurrent refreshes ({}), will continue in next run", maxConcurrentRefreshes);
                        break;
                    }
                    
                } catch (Exception e) {
                    log.error("Error refreshing view {}: {}", view.getViewName(), e.getMessage());
                }
            }
            
            log.info("✅ Materialized view refresh job completed: {} refreshed, {} skipped", refreshed, skipped);
            
        } catch (Exception e) {
            log.error("Materialized view refresh job failed: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Check if a view should be refreshed based on its schedule
     */
    private boolean shouldRefreshView(ViewMetadata view, long now) {
        // Get refresh interval for this view (or use default)
        int intervalMinutes = viewRefreshSchedules.getOrDefault(
            view.getViewName(),
            defaultRefreshIntervalMinutes
        );
        
        // If interval is 0 or negative, don't auto-refresh
        if (intervalMinutes <= 0) {
            return false;
        }
        
        // Check last refresh time
        Long lastRefresh = lastRefreshTimes.get(view.getViewName());
        if (lastRefresh == null) {
            // Use the view's last refresh timestamp if available
            lastRefresh = view.getLastRefreshTimestamp();
        }
        
        if (lastRefresh == null) {
            // Never refreshed, refresh now
            return true;
        }
        
        // Check if enough time has passed
        long intervalMillis = intervalMinutes * 60 * 1000L;
        return (now - lastRefresh) >= intervalMillis;
    }
    
    /**
     * Set refresh schedule for a specific view
     */
    public void setViewRefreshSchedule(String viewName, int intervalMinutes) {
        log.info("Setting refresh schedule for view {}: every {} minutes", viewName, intervalMinutes);
        viewRefreshSchedules.put(viewName, intervalMinutes);
    }
    
    /**
     * Remove refresh schedule for a view (will use default)
     */
    public void removeViewRefreshSchedule(String viewName) {
        log.info("Removing custom refresh schedule for view {}", viewName);
        viewRefreshSchedules.remove(viewName);
    }
    
    /**
     * Get refresh schedule for a view
     */
    public int getViewRefreshSchedule(String viewName) {
        return viewRefreshSchedules.getOrDefault(viewName, defaultRefreshIntervalMinutes);
    }
    
    /**
     * List all configured refresh schedules
     */
    public Map<String, Integer> listRefreshSchedules() {
        return new HashMap<>(viewRefreshSchedules);
    }
    
    // Configuration setters
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void setDefaultRefreshIntervalMinutes(int defaultRefreshIntervalMinutes) {
        this.defaultRefreshIntervalMinutes = defaultRefreshIntervalMinutes;
    }
    
    public void setMaxConcurrentRefreshes(int maxConcurrentRefreshes) {
        this.maxConcurrentRefreshes = maxConcurrentRefreshes;
    }
    
    public void setExcludeViews(List<String> excludeViews) {
        this.excludeViews = excludeViews;
    }
    
    public void setViewRefreshSchedules(Map<String, Integer> schedules) {
        this.viewRefreshSchedules.putAll(schedules);
    }
}

