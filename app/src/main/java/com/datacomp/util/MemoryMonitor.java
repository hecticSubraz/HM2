package com.datacomp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory Monitor utility for tracking JVM heap and GPU memory usage.
 * 
 * Provides real-time memory statistics and logging for debugging memory issues.
 * Monitors both JVM heap and GPU buffer allocations.
 */
public class MemoryMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryMonitor.class);
    
    // Singleton instance
    private static final MemoryMonitor INSTANCE = new MemoryMonitor();
    
    // Memory tracking
    private final MemoryMXBean memoryBean;
    private final AtomicLong peakHeapUsage;
    private final AtomicLong totalOperations;
    
    // Memory thresholds for warnings (80% of max)
    private static final double WARNING_THRESHOLD = 0.8;
    
    private MemoryMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.peakHeapUsage = new AtomicLong(0);
        this.totalOperations = new AtomicLong(0);
        logger.info("Memory Monitor initialized");
    }
    
    public static MemoryMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * Log current memory status with operation context.
     * Call this after each major operation (compress/decompress).
     * 
     * @param operationName Name of the operation that just completed
     */
    public void logMemoryStatus(String operationName) {
        totalOperations.incrementAndGet();
        
        MemorySnapshot snapshot = captureSnapshot();
        
        // Update peak usage
        long currentUsed = snapshot.heapUsed;
        peakHeapUsage.updateAndGet(peak -> Math.max(peak, currentUsed));
        
        // Log memory status
        logger.info("Memory Status after [{}]:", operationName);
        logger.info("  Heap: {} MB used / {} MB max ({}%)",
            snapshot.heapUsedMB, snapshot.heapMaxMB, snapshot.heapUsagePercent);
        logger.info("  Non-Heap: {} MB used / {} MB max",
            snapshot.nonHeapUsedMB, snapshot.nonHeapMaxMB);
        logger.info("  GPU Buffers: {} MB allocated",
            snapshot.gpuBuffersMB);
        logger.info("  Total Operations: {}", totalOperations.get());
        
        // Warn if memory usage is high
        if (snapshot.heapUsagePercent > WARNING_THRESHOLD * 100) {
            logger.warn("⚠️  High memory usage detected! Consider running garbage collection.");
            suggestGarbageCollection();
        }
    }
    
    /**
     * Get detailed memory snapshot.
     * 
     * @return Memory snapshot with all statistics
     */
    public MemorySnapshot captureSnapshot() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        long heapUsed = heapUsage.getUsed();
        long heapMax = heapUsage.getMax();
        long nonHeapUsed = nonHeapUsage.getUsed();
        long nonHeapMax = nonHeapUsage.getMax();
        
        // Get GPU buffer allocation from buffer manager
        double gpuBuffers = GpuBufferManager.getInstance().getTotalAllocatedMB();
        
        return new MemorySnapshot(
            heapUsed, heapMax, nonHeapUsed, nonHeapMax, gpuBuffers
        );
    }
    
    /**
     * Format memory size in human-readable format.
     * 
     * @param bytes Memory size in bytes
     * @return Formatted string (e.g., "15.5 MB", "2.3 GB")
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * Suggest garbage collection if memory usage is high.
     * Only suggests, doesn't force (System.gc() is just a hint).
     */
    public void suggestGarbageCollection() {
        logger.info("Suggesting garbage collection to JVM...");
        System.gc();
        
        // Wait a bit and log result
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        MemorySnapshot afterGC = captureSnapshot();
        logger.info("After GC: {} MB heap used ({}%)", 
            afterGC.heapUsedMB, afterGC.heapUsagePercent);
    }
    
    /**
     * Clear all tracked memory (GPU buffers and JVM).
     * Use between operations or when memory pressure is detected.
     */
    public void clearMemory() {
        logger.info("Clearing all memory (GPU + JVM)");
        
        // Clear GPU buffers
        GpuBufferManager.getInstance().clearAllPools();
        
        // Suggest JVM GC
        suggestGarbageCollection();
    }
    
    /**
     * Get peak heap usage since application start.
     * 
     * @return Peak heap usage in MB
     */
    public double getPeakHeapUsageMB() {
        return peakHeapUsage.get() / (1024.0 * 1024.0);
    }
    
    /**
     * Get total number of operations monitored.
     * 
     * @return Total operations count
     */
    public long getTotalOperations() {
        return totalOperations.get();
    }
    
    /**
     * Get comprehensive memory statistics as formatted string.
     * 
     * @return Statistics string
     */
    public String getStatistics() {
        MemorySnapshot snapshot = captureSnapshot();
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== Memory Monitor Statistics ===\n");
        sb.append(String.format("JVM Heap Memory:\n"));
        sb.append(String.format("  Current: %s / %s (%.1f%%)\n",
            formatBytes(snapshot.heapUsed), formatBytes(snapshot.heapMax), snapshot.heapUsagePercent));
        sb.append(String.format("  Peak: %s\n", formatBytes(peakHeapUsage.get())));
        sb.append(String.format("Non-Heap Memory:\n"));
        sb.append(String.format("  Current: %s / %s\n",
            formatBytes(snapshot.nonHeapUsed), formatBytes(snapshot.nonHeapMax)));
        sb.append(String.format("GPU Buffers: %.2f MB\n", snapshot.gpuBuffersMB));
        sb.append(String.format("Total Operations Monitored: %d\n", totalOperations.get()));
        sb.append("\n");
        sb.append(GpuBufferManager.getInstance().getStatistics());
        
        return sb.toString();
    }
    
    /**
     * Memory snapshot containing all memory statistics.
     */
    public static class MemorySnapshot {
        public final long heapUsed;
        public final long heapMax;
        public final long nonHeapUsed;
        public final long nonHeapMax;
        public final double gpuBuffersMB;
        
        // Derived values
        public final double heapUsedMB;
        public final double heapMaxMB;
        public final double nonHeapUsedMB;
        public final double nonHeapMaxMB;
        public final double heapUsagePercent;
        
        MemorySnapshot(long heapUsed, long heapMax, long nonHeapUsed, 
                      long nonHeapMax, double gpuBuffersMB) {
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.nonHeapUsed = nonHeapUsed;
            this.nonHeapMax = nonHeapMax;
            this.gpuBuffersMB = gpuBuffersMB;
            
            // Calculate derived values
            this.heapUsedMB = heapUsed / (1024.0 * 1024.0);
            this.heapMaxMB = heapMax / (1024.0 * 1024.0);
            this.nonHeapUsedMB = nonHeapUsed / (1024.0 * 1024.0);
            this.nonHeapMaxMB = nonHeapMax / (1024.0 * 1024.0);
            this.heapUsagePercent = (heapUsed * 100.0) / heapMax;
        }
        
        @Override
        public String toString() {
            return String.format("MemorySnapshot[heap=%.1f/%.1f MB (%.1f%%), nonHeap=%.1f/%.1f MB, gpu=%.1f MB]",
                heapUsedMB, heapMaxMB, heapUsagePercent, nonHeapUsedMB, nonHeapMaxMB, gpuBuffersMB);
        }
    }
}

