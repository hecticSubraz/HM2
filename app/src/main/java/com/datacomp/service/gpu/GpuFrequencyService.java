package com.datacomp.service.gpu;

import com.datacomp.service.FrequencyService;
import com.datacomp.util.GpuBufferManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.*;
import uk.ac.manchester.tornado.api.common.TornadoDevice;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider;

/**
 * GPU-accelerated frequency service using TornadoVM.
 * Includes proper GPU memory management and cleanup after each operation.
 */
public class GpuFrequencyService implements FrequencyService {
    
    private static final Logger logger = LoggerFactory.getLogger(GpuFrequencyService.class);
    
    private final TornadoDevice device;
    private volatile Boolean availabilityCache = null; // Cache availability test result
    
    public GpuFrequencyService() {
        this.device = selectDevice();
    }
    
    public GpuFrequencyService(TornadoDevice device) {
        this.device = device;
    }
    
    private TornadoDevice selectDevice() {
        try {
            // Try to get default device
            TornadoDevice defaultDevice = TornadoRuntimeProvider.getTornadoRuntime()
                .getDefaultDevice();
            
            logger.info("Selected GPU device: {}", defaultDevice.getDescription());
            return defaultDevice;
        } catch (Throwable e) {
            // Catch all errors including ExceptionInInitializerError and NoClassDefFoundError
            logger.warn("Failed to initialize TornadoVM device: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public long[] computeHistogram(byte[] data, int offset, int length) {
        if (!isAvailable()) {
            throw new IllegalStateException("GPU not available");
        }
        
        int[] histogram = null;
        
        try {
            // Prepare input (copy relevant portion)
            byte[] input = new byte[length];
            System.arraycopy(data, offset, input, 0, length);
            
            // Get managed histogram buffer from buffer manager
            histogram = GpuBufferManager.getInstance().getIntBuffer(256);
            java.util.Arrays.fill(histogram, 0);
            
            // Create and execute task graph with unique name to avoid conflicts
            TaskGraph taskGraph = new TaskGraph("freq-histogram-" + System.nanoTime())
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, input)
                .task("compute", TornadoKernels::histogramKernel, input, 0, length, histogram)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, histogram);
            
            ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
            
            // Execute on GPU with auto-cleanup (try-with-resources)
            try (TornadoExecutionPlan executor = new TornadoExecutionPlan(immutableTaskGraph)) {
                executor.execute();
            }
            
            // CRITICAL: Clean up GPU device memory after execution
            cleanupGpuMemory();
            
            // Convert int[] to long[]
            long[] result = new long[256];
            for (int i = 0; i < 256; i++) {
                result[i] = histogram[i] & 0xFFFFFFFFL;
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("GPU histogram computation failed", e);
            throw new RuntimeException("GPU computation failed", e);
        } finally {
            // Return buffer to pool for reuse
            if (histogram != null) {
                GpuBufferManager.getInstance().releaseIntBuffer(histogram);
            }
        }
    }
    
    /**
     * Clean up GPU device memory after TaskSchedule execution.
     * GPU memory is automatically freed by TornadoExecutionPlan.close() via try-with-resources.
     */
    private void cleanupGpuMemory() {
        try {
            // TornadoExecutionPlan already freed GPU memory via close()
            // Just ensure host buffers are released back to pool
            logger.trace("GPU memory cleanup (FrequencyService)");
        } catch (Exception e) {
            logger.debug("GPU cleanup warning: {}", e.getMessage());
        }
    }
    
    @Override
    public String getServiceName() {
        if (device != null) {
            return "GPU (" + device.getDescription() + ")";
        }
        return "GPU (unavailable)";
    }
    
    @Override
    public boolean isAvailable() {
        if (device == null) return false;
        
        // Return cached result if available (avoid repeated slow tests)
        if (availabilityCache != null) {
            return availabilityCache;
        }
        
        // Perform one-time availability test
        logger.info("Performing GPU availability test (first time only)...");
        long startTime = System.nanoTime();
        
        int[] histogram = null;
        
        try {
            // Test with small array
            byte[] testData = new byte[1024];
            histogram = new int[256];
            
            TaskGraph testGraph = new TaskGraph("availability-test")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, testData)
                .task("test", TornadoKernels::histogramKernel, testData, 0, testData.length, histogram)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, histogram);
            
            ImmutableTaskGraph immutable = testGraph.snapshot();
            
            // Execute GPU test
            try (TornadoExecutionPlan executor = new TornadoExecutionPlan(immutable)) {
                executor.execute();
            }
            
            // Clean up after test
            cleanupGpuMemory();
            
            long duration = (System.nanoTime() - startTime) / 1_000_000; // ms
            logger.info("✓ GPU availability test PASSED ({} ms)", duration);
            
            availabilityCache = true;
            return true;
        } catch (Exception e) {
            logger.warn("✗ GPU availability test FAILED: {}", e.getMessage());
            availabilityCache = false;
            return false;
        }
    }
    
    /**
     * Get available TornadoVM devices.
     */
    public static java.util.List<TornadoDevice> getAvailableDevices() {
        java.util.List<TornadoDevice> devices = new java.util.ArrayList<>();
        
        try {
            TornadoRuntime runtime = TornadoRuntimeProvider.getTornadoRuntime();
            int numBackends = runtime.getNumBackends();
            
            for (int backendIdx = 0; backendIdx < numBackends; backendIdx++) {
                TornadoBackend backend = runtime.getBackend(backendIdx);
                int numDevices = backend.getNumDevices();
                
                for (int deviceIdx = 0; deviceIdx < numDevices; deviceIdx++) {
                    TornadoDevice device = backend.getDevice(deviceIdx);
                    devices.add(device);
                }
            }
        } catch (Throwable e) {
            // Catch all errors including ExceptionInInitializerError and NoClassDefFoundError
            logger.warn("TornadoVM not available: {}", e.getMessage());
        }
        
        return devices;
    }
}

