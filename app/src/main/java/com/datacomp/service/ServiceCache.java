package com.datacomp.service;

import com.datacomp.config.AppConfig;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.service.gpu.StreamingGpuCompressionService;
import com.datacomp.service.gpu.GpuCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Caches compression services to avoid repeated slow GPU initialization.
 * 
 * GPU initialization can take 5-10+ seconds:
 * - TornadoVM runtime loading
 * - GPU driver initialization
 * - Kernel compilation
 * - Device detection
 * 
 * By caching the service, we only pay this cost once per session.
 */
public class ServiceCache {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceCache.class);
    
    private static final ServiceCache INSTANCE = new ServiceCache();
    
    // Cached services
    private volatile CompressionService cachedGpuService = null;
    private volatile CompressionService cachedCpuService = null;
    
    // Initialization state
    private volatile CompletableFuture<CompressionService> gpuInitFuture = null;
    private volatile boolean gpuInitFailed = false;
    
    // Background executor for async init
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServiceCache-Init");
        t.setDaemon(true);
        return t;
    });
    
    private ServiceCache() {
        // Private constructor for singleton
    }
    
    public static ServiceCache getInstance() {
        return INSTANCE;
    }
    
    /**
     * Get or create compression service asynchronously.
     * 
     * @param config Application configuration
     * @param forceCpu If true, force CPU mode
     * @param progressCallback Optional callback for initialization progress
     * @return CompletableFuture that completes with the service
     */
    public CompletableFuture<CompressionService> getServiceAsync(
            AppConfig config, 
            boolean forceCpu,
            Consumer<String> progressCallback) {
        
        // CPU service requested
        if (forceCpu) {
            if (cachedCpuService == null) {
                synchronized (this) {
                    if (cachedCpuService == null) {
                        if (progressCallback != null) {
                            progressCallback.accept("Initializing CPU service...");
                        }
                        cachedCpuService = new CpuCompressionService(config.getChunkSizeMB());
                        logger.info("CPU service cached");
                    }
                }
            }
            return CompletableFuture.completedFuture(cachedCpuService);
        }
        
        // GPU service requested
        if (!config.isGpuAutoDetect()) {
            // GPU disabled, use CPU
            if (cachedCpuService == null) {
                synchronized (this) {
                    if (cachedCpuService == null) {
                        if (progressCallback != null) {
                            progressCallback.accept("Initializing CPU service...");
                        }
                        cachedCpuService = new CpuCompressionService(config.getChunkSizeMB());
                        logger.info("CPU service cached (GPU disabled)");
                    }
                }
            }
            return CompletableFuture.completedFuture(cachedCpuService);
        }
        
        // Check if GPU service is already cached
        if (cachedGpuService != null) {
            logger.debug("Using cached GPU service");
            return CompletableFuture.completedFuture(cachedGpuService);
        }
        
        // Check if GPU init already failed
        if (gpuInitFailed) {
            logger.debug("GPU init previously failed, using CPU service");
            if (cachedCpuService == null) {
                cachedCpuService = new CpuCompressionService(config.getChunkSizeMB());
            }
            return CompletableFuture.completedFuture(cachedCpuService);
        }
        
        // Check if GPU init is already in progress
        synchronized (this) {
            if (gpuInitFuture != null) {
                logger.debug("GPU initialization already in progress, waiting...");
                if (progressCallback != null) {
                    progressCallback.accept("Waiting for GPU initialization...");
                }
                return gpuInitFuture;
            }
            
            // Start GPU initialization asynchronously
            gpuInitFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    if (progressCallback != null) {
                        progressCallback.accept("Initializing TornadoVM runtime...");
                    }
                    logger.info("Starting GPU service initialization (this may take 5-10 seconds)...");
                    
                    long startTime = System.currentTimeMillis();
                    
                    // Try streaming GPU service first
                    if (progressCallback != null) {
                        progressCallback.accept("Loading GPU driver and kernels...");
                    }
                    
                    try {
                        StreamingGpuCompressionService streamingService = 
                            new StreamingGpuCompressionService(
                                config.getChunkSizeMB(), 
                                config.isGpuFallbackOnError());
                        
                        if (streamingService.isAvailable()) {
                            long duration = System.currentTimeMillis() - startTime;
                            logger.info("✓ GPU service initialized successfully in {}ms", duration);
                            
                            cachedGpuService = streamingService;
                            
                            if (progressCallback != null) {
                                progressCallback.accept(String.format(
                                    "GPU initialized (%.1fs)", duration / 1000.0));
                            }
                            
                            return streamingService;
                        }
                    } catch (Exception e) {
                        logger.warn("Streaming GPU service failed: {}", e.getMessage());
                    }
                    
                    // Try standard GPU service as fallback
                    if (progressCallback != null) {
                        progressCallback.accept("Trying standard GPU service...");
                    }
                    
                    try {
                        GpuCompressionService gpuService = new GpuCompressionService(
                            config.getChunkSizeMB(), 
                            config.isGpuFallbackOnError());
                        
                        if (gpuService.isAvailable()) {
                            long duration = System.currentTimeMillis() - startTime;
                            logger.info("✓ Standard GPU service initialized in {}ms", duration);
                            
                            cachedGpuService = gpuService;
                            
                            if (progressCallback != null) {
                                progressCallback.accept(String.format(
                                    "GPU initialized (%.1fs)", duration / 1000.0));
                            }
                            
                            return gpuService;
                        }
                    } catch (Exception e) {
                        logger.warn("Standard GPU service failed: {}", e.getMessage());
                    }
                    
                    // GPU init failed, fall back to CPU
                    logger.info("GPU initialization failed, falling back to CPU");
                    gpuInitFailed = true;
                    
                    if (progressCallback != null) {
                        progressCallback.accept("GPU unavailable, using CPU");
                    }
                    
                    if (cachedCpuService == null) {
                        cachedCpuService = new CpuCompressionService(config.getChunkSizeMB());
                    }
                    
                    return cachedCpuService;
                    
                } catch (Exception e) {
                    logger.error("Service initialization failed", e);
                    gpuInitFailed = true;
                    
                    if (progressCallback != null) {
                        progressCallback.accept("Initialization failed, using CPU");
                    }
                    
                    if (cachedCpuService == null) {
                        cachedCpuService = new CpuCompressionService(config.getChunkSizeMB());
                    }
                    
                    return cachedCpuService;
                }
            }, initExecutor);
            
            return gpuInitFuture;
        }
    }
    
    /**
     * Get or create compression service (blocking).
     * Prefer getServiceAsync() for non-blocking initialization.
     */
    public CompressionService getService(AppConfig config, boolean forceCpu) {
        try {
            return getServiceAsync(config, forceCpu, null).get();
        } catch (Exception e) {
            logger.error("Failed to get service", e);
            if (cachedCpuService == null) {
                cachedCpuService = new CpuCompressionService(config.getChunkSizeMB());
            }
            return cachedCpuService;
        }
    }
    
    /**
     * Clear cached services (for testing or configuration changes).
     */
    public void clearCache() {
        synchronized (this) {
            logger.info("Clearing service cache");
            cachedGpuService = null;
            cachedCpuService = null;
            gpuInitFuture = null;
            gpuInitFailed = false;
        }
    }
    
    /**
     * Shutdown the service cache.
     */
    public void shutdown() {
        initExecutor.shutdown();
    }
}

