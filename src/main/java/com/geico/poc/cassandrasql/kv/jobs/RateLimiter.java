package com.geico.poc.cassandrasql.kv.jobs;

import java.util.concurrent.TimeUnit;

/**
 * Rate limiter for background jobs to prevent impacting read latency.
 * 
 * Uses a token bucket algorithm to limit the rate of operations.
 * Designed to throttle operations like scanning large tables or processing many keys.
 * 
 * Example: For 1000 keys/hour, each operation waits 3.6 seconds on average.
 */
public class RateLimiter {
    private final long keysPerHour;
    private final long nanosPerKey;
    private long lastOperationNanos;
    private final Object lock = new Object();
    
    /**
     * Create a rate limiter with the specified keys per hour limit.
     * 
     * @param keysPerHour Maximum number of keys to process per hour (must be > 0)
     */
    public RateLimiter(long keysPerHour) {
        if (keysPerHour <= 0) {
            throw new IllegalArgumentException("keysPerHour must be positive, got: " + keysPerHour);
        }
        this.keysPerHour = keysPerHour;
        this.nanosPerKey = TimeUnit.HOURS.toNanos(1) / keysPerHour;
        // Initialize to past time so first acquire is immediate
        this.lastOperationNanos = System.nanoTime() - nanosPerKey;
    }
    
    /**
     * Acquire permission to process one key.
     * This method blocks until enough time has passed since the last operation.
     * 
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        acquire(1);
    }
    
    /**
     * Acquire permission to process multiple keys.
     * This method blocks until enough time has passed since the last operation.
     * 
     * @param keys Number of keys to acquire permission for
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void acquire(int keys) throws InterruptedException {
        if (keys <= 0) {
            return;
        }
        
        synchronized (lock) {
            long now = System.nanoTime();
            // Calculate when we can proceed based on last operation time
            long earliestProceedTime = lastOperationNanos + nanosPerKey;
            long nanosToWait = earliestProceedTime - now;
            
            if (nanosToWait > 0) {
                // Convert to millis for Thread.sleep (more efficient than busy waiting)
                long millisToWait = TimeUnit.NANOSECONDS.toMillis(nanosToWait);
                if (millisToWait > 0) {
                    Thread.sleep(millisToWait);
                }
                // Sleep remaining nanos if needed (for sub-millisecond precision)
                long remainingNanos = nanosToWait - TimeUnit.MILLISECONDS.toNanos(millisToWait);
                if (remainingNanos > 0) {
                    // Busy wait for remaining nanos (typically very short)
                    long endNanos = System.nanoTime() + remainingNanos;
                    while (System.nanoTime() < endNanos) {
                        // Busy wait
                    }
                }
            }
            
            // Update last operation time, accounting for multiple keys
            lastOperationNanos = System.nanoTime() + (nanosPerKey * (keys - 1));
        }
    }
    
    /**
     * Try to acquire permission without blocking.
     * 
     * @return true if permission was acquired, false if rate limit would be exceeded
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }
    
    /**
     * Try to acquire permission for multiple keys without blocking.
     * 
     * @param keys Number of keys to acquire permission for
     * @return true if permission was acquired, false if rate limit would be exceeded
     */
    public boolean tryAcquire(int keys) {
        if (keys <= 0) {
            return true;
        }
        
        synchronized (lock) {
            long now = System.nanoTime();
            long earliestProceedTime = lastOperationNanos + nanosPerKey;
            long nanosToWait = earliestProceedTime - now;
            
            if (nanosToWait > 0) {
                return false;
            }
            
            // Update last operation time, accounting for multiple keys
            lastOperationNanos = now + (nanosPerKey * (keys - 1));
            return true;
        }
    }
    
    /**
     * Get the configured rate limit in keys per hour.
     * 
     * @return keys per hour
     */
    public long getKeysPerHour() {
        return keysPerHour;
    }
    
    /**
     * Get the average time between operations in milliseconds.
     * 
     * @return milliseconds per key
     */
    public long getMillisPerKey() {
        return TimeUnit.NANOSECONDS.toMillis(nanosPerKey);
    }
    
    /**
     * Reset the rate limiter state.
     * Useful for testing or when restarting operations.
     */
    public void reset() {
        synchronized (lock) {
            // Reset to past time so next acquire is immediate
            lastOperationNanos = System.nanoTime() - nanosPerKey;
        }
    }
}

