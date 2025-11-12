package com.datacomp.service;

import com.datacomp.config.AppConfig;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.service.cpu.CpuFrequencyService;
import com.datacomp.service.gpu.GpuCompressionService;
import com.datacomp.service.gpu.OptimizedGpuCompressionService;
import com.datacomp.service.gpu.StreamingGpuCompressionService;
import com.datacomp.service.gpu.GpuFrequencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating compression services based on configuration.
 * 
 * UPDATED: Now supports three GPU service levels:
 * 1. StreamingGpuCompressionService - Ultra memory efficient (constant memory)
 * 2. OptimizedGpuCompressionService - Maximum performance (balanced)
 * 3. GpuCompressionService - Standard GPU acceleration
 */
public class ServiceFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceFactory.class);
    
    // Feature flags for GPU service selection
    // DEFAULT: CPU mode to prevent UI freezing and compatibility issues
    // GPU mode can be enabled via checkbox in UI when needed
    // ⚠️ IMPORTANT: Enable ONLY ONE GPU service at a time!
    private static final boolean USE_STREAMING_GPU = true;  // Memory-efficient mode (RECOMMENDED)
    private static final boolean USE_OPTIMIZED_GPU = false; // Experimental full GPU mode (DO NOT USE WITH STREAMING)
    
    /**
     * Create compression service based on configuration.
     */
    public static CompressionService createCompressionService(AppConfig config) {
        return createCompressionService(config, config.isForceCpu());
    }
    
    /**
     * Create compression service with explicit CPU/GPU selection.
     * 
     * @param config Application configuration
     * @param forceCpu If true, force CPU mode regardless of config
     * @return Compression service instance
     */
    public static CompressionService createCompressionService(AppConfig config, boolean forceCpu) {
        int chunkSizeMB = config.getChunkSizeMB();
        
        if (forceCpu) {
            logger.info("CPU mode forced by configuration");
            return new CpuCompressionService(chunkSizeMB);
        }
        
        if (config.isGpuAutoDetect()) {
            try {
                // Try streaming GPU service for huge files (ultra memory efficient)
                if (USE_STREAMING_GPU) {
                    logger.info("Attempting to initialize STREAMING GPU service...");
                    try {
                        StreamingGpuCompressionService streamingService = 
                            new StreamingGpuCompressionService(chunkSizeMB, config.isGpuFallbackOnError());
                        
                        if (streamingService.isAvailable()) {
                            logger.info("✓ Using STREAMING GPU compression service (Ultra Memory Efficient Mode)");
                            return streamingService;
                        }
                    } catch (Exception e) {
                        logger.info("Streaming GPU service unavailable, trying optimized service: {}", 
                                   e.getMessage());
                    }
                }
                
                // Try optimized GPU service for maximum performance
                if (USE_OPTIMIZED_GPU) {
                    logger.info("Attempting to initialize OPTIMIZED GPU service...");
                    try {
                        OptimizedGpuCompressionService optimizedService = 
                            new OptimizedGpuCompressionService(chunkSizeMB, config.isGpuFallbackOnError());
                        
                        if (optimizedService.isAvailable()) {
                            logger.info("✓ Using OPTIMIZED GPU compression service (Maximum Performance Mode)");
                            return optimizedService;
                        }
                    } catch (Exception e) {
                        logger.info("Optimized GPU service unavailable, trying standard GPU service: {}", 
                                   e.getMessage());
                    }
                }
                
                // Fallback to standard GPU service
                logger.info("Attempting to initialize standard GPU service...");
                GpuCompressionService gpuService = new GpuCompressionService(
                    chunkSizeMB, config.isGpuFallbackOnError());
                
                if (gpuService.isAvailable()) {
                    logger.info("✓ Using standard GPU compression service");
                    return gpuService;
                } else {
                    logger.info("GPU not available, using CPU service");
                    return new CpuCompressionService(chunkSizeMB);
                }
            } catch (Exception e) {
                logger.warn("Failed to create GPU service, using CPU fallback", e);
                return new CpuCompressionService(chunkSizeMB);
            }
        }
        
        logger.info("GPU auto-detect disabled, using CPU service");
        return new CpuCompressionService(chunkSizeMB);
    }
    
    /**
     * Create frequency service based on configuration.
     */
    public static FrequencyService createFrequencyService(AppConfig config) {
        if (config.isForceCpu()) {
            return new CpuFrequencyService();
        }
        
        if (config.isGpuAutoDetect()) {
            try {
                GpuFrequencyService gpuService = new GpuFrequencyService();
                if (gpuService.isAvailable()) {
                    return gpuService;
                }
            } catch (Exception e) {
                logger.warn("GPU frequency service unavailable", e);
            }
        }
        
        return new CpuFrequencyService();
    }
}

