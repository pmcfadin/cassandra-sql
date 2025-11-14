package com.geico.poc.cassandrasql.postgres;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits the number of concurrent PostgreSQL connections to prevent resource exhaustion.
 * 
 * Thread-safe implementation using Semaphore for connection limiting.
 */
@Component
public class ConnectionLimiter {
    
    private final Semaphore connectionSemaphore;
    private final AtomicInteger activeConnections;
    private final AtomicInteger totalConnections;
    private final AtomicInteger rejectedConnections;
    private final int maxConnections;
    private final long connectionTimeoutMs;
    
    public ConnectionLimiter(
            @Value("${cassandra-sql.protocol.max-connections:1000}") int maxConnections,
            @Value("${cassandra-sql.protocol.connection-timeout-seconds:30}") long connectionTimeoutSeconds) {
        this.maxConnections = maxConnections;
        this.connectionTimeoutMs = connectionTimeoutSeconds * 1000;
        this.connectionSemaphore = new Semaphore(maxConnections, true); // Fair semaphore
        this.activeConnections = new AtomicInteger(0);
        this.totalConnections = new AtomicInteger(0);
        this.rejectedConnections = new AtomicInteger(0);
        
        System.out.println("📊 ConnectionLimiter initialized: max=" + maxConnections + 
                          ", timeout=" + connectionTimeoutSeconds + "s");
    }
    
    /**
     * Try to acquire a connection slot.
     * Returns true if acquired, false if limit reached.
     */
    public boolean tryAcquire() {
        try {
            boolean acquired = connectionSemaphore.tryAcquire(connectionTimeoutMs, TimeUnit.MILLISECONDS);
            if (acquired) {
                activeConnections.incrementAndGet();
                totalConnections.incrementAndGet();
                return true;
            } else {
                rejectedConnections.incrementAndGet();
                System.err.println("⚠️  Connection rejected: limit reached (" + maxConnections + ")");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectedConnections.incrementAndGet();
            return false;
        }
    }
    
    /**
     * Release a connection slot.
     */
    public void release() {
        connectionSemaphore.release();
        activeConnections.decrementAndGet();
    }
    
    /**
     * Get current number of active connections.
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }
    
    /**
     * Get total connections created since startup.
     */
    public long getTotalConnections() {
        return totalConnections.get();
    }
    
    /**
     * Get number of rejected connections.
     */
    public long getRejectedConnections() {
        return rejectedConnections.get();
    }
    
    /**
     * Get maximum allowed connections.
     */
    public int getMaxConnections() {
        return maxConnections;
    }
    
    /**
     * Get connection utilization (0.0 to 1.0).
     */
    public double getUtilization() {
        return (double) activeConnections.get() / maxConnections;
    }
    
    /**
     * Check if connection pool is near capacity (> 80%).
     */
    public boolean isNearCapacity() {
        return getUtilization() > 0.8;
    }
    
    /**
     * Get statistics as a formatted string.
     */
    public String getStats() {
        return String.format(
            "Connections: active=%d/%d (%.1f%%), total=%d, rejected=%d",
            activeConnections.get(),
            maxConnections,
            getUtilization() * 100,
            totalConnections.get(),
            rejectedConnections.get()
        );
    }
}



