package com.geico.poc.cassandrasql.kv.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for RateLimiter.
 * 
 * Tests cover:
 * - Basic rate limiting functionality
 * - Multiple key acquisition
 * - Try-acquire non-blocking behavior
 * - Configuration validation
 * - Thread interruption handling
 * - Rate accuracy
 */
public class RateLimiterTest {
    
    // ========================================
    // Test 1: Basic Construction
    // ========================================
    
    @Test
    public void testConstructorWithValidRate() {
        RateLimiter limiter = new RateLimiter(1000);
        assertEquals(1000, limiter.getKeysPerHour());
    }
    
    @Test
    public void testConstructorWithZeroRate() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0));
    }
    
    @Test
    public void testConstructorWithNegativeRate() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-100));
    }
    
    // ========================================
    // Test 2: Basic Acquire
    // ========================================
    
    @Test
    @Timeout(5)
    public void testAcquireSingleKey() throws InterruptedException {
        // 3600 keys/hour = 1 key/second
        RateLimiter limiter = new RateLimiter(3600);
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // First acquire should be immediate
        long firstAcquire = System.currentTimeMillis() - start;
        
        limiter.acquire(); // Second acquire should wait ~1 second
        long secondAcquire = System.currentTimeMillis() - start;
        
        // First should be nearly instant (< 100ms)
        assertTrue(firstAcquire < 100, "First acquire should be immediate, was " + firstAcquire + "ms");
        
        // Second should take ~1 second (allow 800-1200ms range for timing variance)
        assertTrue(secondAcquire >= 800 && secondAcquire <= 1200, 
            "Second acquire should take ~1s, was " + secondAcquire + "ms");
    }
    
    @Test
    @Timeout(5)
    public void testAcquireMultipleKeys() throws InterruptedException {
        // 3600 keys/hour = 1 key/second
        RateLimiter limiter = new RateLimiter(3600);
        
        // First acquire of 3 keys should be immediate (reserves time for 3 keys)
        long start = System.currentTimeMillis();
        limiter.acquire(3);
        long firstDuration = System.currentTimeMillis() - start;
        
        // First should be nearly instant
        assertTrue(firstDuration < 100, "First acquire(3) should be immediate, was " + firstDuration + "ms");
        
        // Next single acquire should wait ~3 seconds (because we reserved 3 keys worth of time)
        limiter.acquire(1);
        long totalDuration = System.currentTimeMillis() - start;
        
        // Should take ~3 seconds total (allow 2800-3200ms range)
        assertTrue(totalDuration >= 2800 && totalDuration <= 3200, 
            "After acquire(3), next acquire should wait ~3s, total was " + totalDuration + "ms");
    }
    
    @Test
    @Timeout(2)
    public void testAcquireZeroKeys() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1000);
        
        long start = System.currentTimeMillis();
        limiter.acquire(0); // Should be immediate
        long duration = System.currentTimeMillis() - start;
        
        assertTrue(duration < 100, "Acquiring 0 keys should be immediate");
    }
    
    // ========================================
    // Test 3: Try-Acquire (Non-blocking)
    // ========================================
    
    @Test
    public void testTryAcquireImmediate() {
        RateLimiter limiter = new RateLimiter(1000);
        
        // First try should succeed immediately
        assertTrue(limiter.tryAcquire(), "First tryAcquire should succeed");
    }
    
    @Test
    public void testTryAcquireBlocked() {
        // 3600 keys/hour = 1 key/second
        RateLimiter limiter = new RateLimiter(3600);
        
        // First acquire succeeds
        assertTrue(limiter.tryAcquire());
        
        // Immediate second try should fail (would need to wait 1 second)
        assertFalse(limiter.tryAcquire(), "Second tryAcquire should fail immediately");
    }
    
    @Test
    public void testTryAcquireMultipleKeys() {
        RateLimiter limiter = new RateLimiter(3600);
        
        // Try to acquire 2 keys immediately
        assertTrue(limiter.tryAcquire(2));
        
        // Next try should fail
        assertFalse(limiter.tryAcquire());
    }
    
    @Test
    public void testTryAcquireZeroKeys() {
        RateLimiter limiter = new RateLimiter(1000);
        
        // Acquiring 0 keys should always succeed
        assertTrue(limiter.tryAcquire(0));
        assertTrue(limiter.tryAcquire(0));
    }
    
    // ========================================
    // Test 4: Reset Functionality
    // ========================================
    
    @Test
    public void testReset() {
        RateLimiter limiter = new RateLimiter(3600);
        
        // Acquire once
        assertTrue(limiter.tryAcquire());
        
        // Second try should fail
        assertFalse(limiter.tryAcquire());
        
        // Reset
        limiter.reset();
        
        // Now should succeed again
        assertTrue(limiter.tryAcquire(), "After reset, tryAcquire should succeed");
    }
    
    // ========================================
    // Test 5: High Rate (Fast Operations)
    // ========================================
    
    @Test
    @Timeout(2)
    public void testHighRate() throws InterruptedException {
        // 36000 keys/hour = 10 keys/second = 100ms per key
        RateLimiter limiter = new RateLimiter(36000);
        
        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long duration = System.currentTimeMillis() - start;
        
        // 5 keys at 100ms each = ~500ms (allow 400-600ms)
        assertTrue(duration >= 400 && duration <= 600, 
            "5 acquires at high rate should take ~500ms, was " + duration + "ms");
    }
    
    // ========================================
    // Test 6: Very Low Rate (Slow Operations)
    // ========================================
    
    @Test
    @Timeout(6)
    public void testLowRate() throws InterruptedException {
        // 720 keys/hour = 5 seconds per key (more reasonable for testing)
        RateLimiter limiter = new RateLimiter(720);
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // First is immediate
        limiter.acquire(); // Second waits ~5 seconds
        long duration = System.currentTimeMillis() - start;
        
        // Should take ~5 seconds (allow 4800-5200ms)
        assertTrue(duration >= 4800 && duration <= 5200, 
            "Low rate acquire should take ~5s, was " + duration + "ms");
    }
    
    // ========================================
    // Test 7: Default Rate (1000 keys/hour)
    // ========================================
    
    @Test
    @Timeout(5)
    public void testDefaultRate() throws InterruptedException {
        // 1000 keys/hour = 3.6 seconds per key
        RateLimiter limiter = new RateLimiter(1000);
        
        long start = System.currentTimeMillis();
        limiter.acquire(); // First is immediate
        limiter.acquire(); // Second waits ~3.6 seconds
        long duration = System.currentTimeMillis() - start;
        
        // Should take ~3.6 seconds (allow 3400-3800ms)
        assertTrue(duration >= 3400 && duration <= 3800, 
            "Default rate (1000/hr) should take ~3.6s, was " + duration + "ms");
    }
    
    // ========================================
    // Test 8: Thread Interruption
    // ========================================
    
    @Test
    @Timeout(5)
    public void testInterruptDuringAcquire() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(360); // 10 seconds per key
        
        Thread testThread = new Thread(() -> {
            try {
                limiter.acquire(); // First acquire (immediate)
                limiter.acquire(); // Second acquire (should be interrupted)
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // Expected
                assertTrue(Thread.currentThread().isInterrupted());
            }
        });
        
        testThread.start();
        Thread.sleep(100); // Let it start waiting
        testThread.interrupt(); // Interrupt it
        testThread.join(1000); // Wait for it to finish
        
        assertFalse(testThread.isAlive(), "Thread should have terminated");
    }
    
    // ========================================
    // Test 9: Concurrent Access
    // ========================================
    
    @Test
    @Timeout(10)
    public void testConcurrentAccess() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(3600); // 1 key/second
        
        // Start 3 threads trying to acquire
        Thread[] threads = new Thread[3];
        long[] durations = new long[3];
        
        for (int i = 0; i < 3; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    long start = System.nanoTime();
                    limiter.acquire();
                    durations[index] = (System.nanoTime() - start) / 1_000_000; // Convert to ms
                } catch (InterruptedException e) {
                    fail("Should not be interrupted");
                }
            });
        }
        
        // Start all threads at once
        for (Thread t : threads) {
            t.start();
        }
        
        // Wait for all to complete
        for (Thread t : threads) {
            t.join();
        }
        
        // Verify they were serialized (each waited progressively longer)
        // First thread: ~0ms, Second: ~1000ms, Third: ~2000ms
        assertTrue(durations[0] < 200, "First thread should be fast");
        assertTrue(durations[1] >= 800 && durations[1] <= 1200, "Second thread should wait ~1s");
        assertTrue(durations[2] >= 1800 && durations[2] <= 2200, "Third thread should wait ~2s");
    }
    
    // ========================================
    // Test 10: Milliseconds Per Key Calculation
    // ========================================
    
    @Test
    public void testMillisPerKeyCalculation() {
        // 3600 keys/hour = 1 key/second = 1000ms per key
        RateLimiter limiter1 = new RateLimiter(3600);
        assertEquals(1000, limiter1.getMillisPerKey());
        
        // 1000 keys/hour = 3.6 seconds per key = 3600ms per key
        RateLimiter limiter2 = new RateLimiter(1000);
        assertEquals(3600, limiter2.getMillisPerKey());
        
        // 36000 keys/hour = 0.1 seconds per key = 100ms per key
        RateLimiter limiter3 = new RateLimiter(36000);
        assertEquals(100, limiter3.getMillisPerKey());
    }
    
    // ========================================
    // Test 11: Burst Behavior
    // ========================================
    
    @Test
    @Timeout(5)
    public void testBurstBehavior() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(3600); // 1 key/second
        
        // Acquire one key
        limiter.acquire();
        
        // Wait 2 seconds (should accumulate "credit" for 2 keys)
        Thread.sleep(2000);
        
        // Now try to acquire 2 keys quickly
        long start = System.currentTimeMillis();
        limiter.acquire();
        limiter.acquire();
        long duration = System.currentTimeMillis() - start;
        
        // Both should be relatively fast since we waited
        // (Note: Our implementation doesn't accumulate credit, so this will still wait)
        // This test documents current behavior
        assertTrue(duration >= 800, "Should still enforce rate limit");
    }
}

