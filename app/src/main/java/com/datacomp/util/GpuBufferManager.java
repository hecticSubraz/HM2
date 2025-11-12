package com.datacomp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU Buffer Manager for efficient memory management with TornadoVM.
 * 
 * Manages reusable GPU buffers to minimize allocation/deallocation overhead.
 * Automatically frees unused buffers and expands capacity as needed.
 * Thread-safe for concurrent GPU operations.
 */
public class GpuBufferManager {
    
    private static final Logger logger = LoggerFactory.getLogger(GpuBufferManager.class);
    
    // Singleton instance for global buffer management
    private static final GpuBufferManager INSTANCE = new GpuBufferManager();
    
    // Buffer pools by size (in bytes) - uses concurrent map for thread safety
    private final ConcurrentHashMap<Integer, BufferPool> bufferPools;
    
    // Track total allocated GPU memory
    private final AtomicLong totalAllocatedBytes;
    
    // Maximum memory per buffer type (64MB default)
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024;
    
    // Maximum number of buffers to keep in pool
    private static final int MAX_POOLED_BUFFERS = 4;
    
    private GpuBufferManager() {
        this.bufferPools = new ConcurrentHashMap<>();
        this.totalAllocatedBytes = new AtomicLong(0);
        logger.info("GPU Buffer Manager initialized");
    }
    
    public static GpuBufferManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Get or create a byte buffer of specified size.
     * Reuses existing buffers when available.
     * 
     * @param size Buffer size in bytes
     * @return Byte array buffer (managed by buffer manager)
     */
    public byte[] getByteBuffer(int size) {
        BufferPool pool = bufferPools.computeIfAbsent(size, k -> new BufferPool(size, "byte[]"));
        return pool.acquire();
    }
    
    /**
     * Get or create an int buffer of specified size.
     * 
     * @param size Buffer size (number of ints)
     * @return Int array buffer
     */
    public int[] getIntBuffer(int size) {
        BufferPool pool = bufferPools.computeIfAbsent(size * 4, k -> new BufferPool(size, "int[]"));
        return pool.acquireIntBuffer();
    }
    
    /**
     * Release a buffer back to the pool for reuse.
     * 
     * @param buffer Buffer to release
     */
    public void releaseBuffer(byte[] buffer) {
        if (buffer == null) return;
        
        int size = buffer.length;
        BufferPool pool = bufferPools.get(size);
        if (pool != null) {
            pool.release(buffer);
        }
    }
    
    /**
     * Release an int buffer back to the pool.
     * 
     * @param buffer Int buffer to release
     */
    public void releaseIntBuffer(int[] buffer) {
        if (buffer == null) return;
        
        int size = buffer.length * 4;
        BufferPool pool = bufferPools.get(size);
        if (pool != null) {
            pool.releaseIntBuffer(buffer);
        }
    }
    
    /**
     * Clear all GPU device memory and flush TornadoVM runtime.
     * Call this after completing GPU operations to free memory.
     * Note: TornadoExecutionPlan try-with-resources already handles GPU cleanup.
     */
    public void clearGpuMemory() {
        try {
            // Suggest garbage collection to free host memory
            // GPU memory is automatically freed by TornadoExecutionPlan.close()
            System.gc();
            logger.debug("GPU memory cleanup triggered (GC suggested)");
        } catch (Exception e) {
            logger.warn("Failed to clear GPU memory: {}", e.getMessage());
        }
    }
    
    /**
     * Clear all buffer pools and release memory.
     * Use when shutting down or when memory pressure is high.
     */
    public void clearAllPools() {
        logger.info("Clearing all buffer pools");
        bufferPools.values().forEach(BufferPool::clear);
        bufferPools.clear();
        totalAllocatedBytes.set(0);
        clearGpuMemory();
    }
    
    /**
     * Get current total allocated memory in bytes.
     * 
     * @return Total allocated bytes across all pools
     */
    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }
    
    /**
     * Get current total allocated memory in MB.
     * 
     * @return Total allocated memory in MB
     */
    public double getTotalAllocatedMB() {
        return totalAllocatedBytes.get() / (1024.0 * 1024.0);
    }
    
    /**
     * Get statistics about buffer usage.
     * 
     * @return Statistics string
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("GPU Buffer Manager Stats:\n"));
        sb.append(String.format("  Total Pools: %d\n", bufferPools.size()));
        sb.append(String.format("  Total Allocated: %.2f MB\n", getTotalAllocatedMB()));
        
        bufferPools.forEach((size, pool) -> {
            sb.append(String.format("  Pool[%d bytes]: %d buffers (%.2f MB)\n",
                size, pool.getPooledCount(), (size * pool.getPooledCount()) / (1024.0 * 1024.0)));
        });
        
        return sb.toString();
    }
    
    /**
     * Buffer pool for a specific buffer size.
     * Thread-safe implementation using concurrent data structures.
     */
    private class BufferPool {
        private final int bufferSize;
        private final String bufferType;
        private final ConcurrentHashMap<Integer, byte[]> byteBufferPool;
        private final ConcurrentHashMap<Integer, int[]> intBufferPool;
        private final AtomicLong pooledCount;
        
        BufferPool(int size, String type) {
            this.bufferSize = size;
            this.bufferType = type;
            this.byteBufferPool = new ConcurrentHashMap<>();
            this.intBufferPool = new ConcurrentHashMap<>();
            this.pooledCount = new AtomicLong(0);
        }
        
        byte[] acquire() {
            // Try to reuse existing buffer
            for (Integer key : byteBufferPool.keySet()) {
                byte[] buffer = byteBufferPool.remove(key);
                if (buffer != null) {
                    pooledCount.decrementAndGet();
                    logger.trace("Reused byte buffer of size {}", bufferSize);
                    return buffer;
                }
            }
            
            // Allocate new buffer
            byte[] newBuffer = new byte[bufferSize];
            totalAllocatedBytes.addAndGet(bufferSize);
            logger.trace("Allocated new byte buffer of size {}", bufferSize);
            return newBuffer;
        }
        
        int[] acquireIntBuffer() {
            // Try to reuse existing buffer
            for (Integer key : intBufferPool.keySet()) {
                int[] buffer = intBufferPool.remove(key);
                if (buffer != null) {
                    pooledCount.decrementAndGet();
                    logger.trace("Reused int buffer of size {}", bufferSize / 4);
                    return buffer;
                }
            }
            
            // Allocate new buffer
            int[] newBuffer = new int[bufferSize / 4];
            totalAllocatedBytes.addAndGet(bufferSize);
            logger.trace("Allocated new int buffer of size {}", bufferSize / 4);
            return newBuffer;
        }
        
        void release(byte[] buffer) {
            if (pooledCount.get() < MAX_POOLED_BUFFERS) {
                byteBufferPool.put(System.identityHashCode(buffer), buffer);
                pooledCount.incrementAndGet();
                logger.trace("Returned byte buffer to pool (size: {})", bufferSize);
            } else {
                // Pool is full, let GC handle it
                totalAllocatedBytes.addAndGet(-bufferSize);
                logger.trace("Discarded byte buffer (pool full, size: {})", bufferSize);
            }
        }
        
        void releaseIntBuffer(int[] buffer) {
            if (pooledCount.get() < MAX_POOLED_BUFFERS) {
                intBufferPool.put(System.identityHashCode(buffer), buffer);
                pooledCount.incrementAndGet();
                logger.trace("Returned int buffer to pool (size: {})", bufferSize / 4);
            } else {
                // Pool is full, let GC handle it
                totalAllocatedBytes.addAndGet(-bufferSize);
                logger.trace("Discarded int buffer (pool full, size: {})", bufferSize / 4);
            }
        }
        
        void clear() {
            int byteCount = byteBufferPool.size();
            int intCount = intBufferPool.size();
            byteBufferPool.clear();
            intBufferPool.clear();
            pooledCount.set(0);
            logger.debug("Cleared pool for {} buffers: {} byte[], {} int[]", 
                bufferType, byteCount, intCount);
        }
        
        long getPooledCount() {
            return pooledCount.get();
        }
    }
}

