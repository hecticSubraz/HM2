# Quick Reference Card - GPU Memory Management & Async Execution

## 🔧 Buffer Manager API

### Get Buffers
```java
// Get byte buffer from pool
byte[] buffer = GpuBufferManager.getInstance().getByteBuffer(sizeInBytes);

// Get int buffer from pool
int[] intBuffer = GpuBufferManager.getInstance().getIntBuffer(numberOfInts);
```

### Release Buffers (Always in finally!)
```java
try {
    byte[] buffer = GpuBufferManager.getInstance().getByteBuffer(1024);
    // ... use buffer ...
} finally {
    GpuBufferManager.getInstance().releaseBuffer(buffer);
}
```

### Memory Cleanup
```java
// Clear all GPU memory
GpuBufferManager.getInstance().clearGpuMemory();

// Clear all buffer pools
GpuBufferManager.getInstance().clearAllPools();
```

### Statistics
```java
// Get allocated memory
double allocatedMB = GpuBufferManager.getInstance().getTotalAllocatedMB();

// Get full statistics
String stats = GpuBufferManager.getInstance().getStatistics();
System.out.println(stats);
```

---

## 📊 Memory Monitor API

### Log Memory Status
```java
// Log after each operation
MemoryMonitor.getInstance().logMemoryStatus("Compression Complete");
```

### Get Memory Snapshot
```java
MemorySnapshot snap = MemoryMonitor.getInstance().captureSnapshot();
System.out.println("Heap: " + snap.heapUsedMB + " MB (" + snap.heapUsagePercent + "%)");
System.out.println("GPU Buffers: " + snap.gpuBuffersMB + " MB");
```

### Memory Cleanup
```java
// Suggest garbage collection
MemoryMonitor.getInstance().suggestGarbageCollection();

// Clear everything (GPU + JVM)
MemoryMonitor.getInstance().clearMemory();
```

### Statistics
```java
// Get peak heap usage
double peakMB = MemoryMonitor.getInstance().getPeakHeapUsageMB();

// Get total operations
long ops = MemoryMonitor.getInstance().getTotalOperations();

// Get full statistics
String stats = MemoryMonitor.getInstance().getStatistics();
System.out.println(stats);
```

---

## 🚀 Async Execution Pattern

### Background GPU Operation
```java
Task<Void> task = new Task<Void>() {
    @Override
    protected Void call() throws Exception {
        // 1. Initialize service in background
        updateMessage("Initializing GPU service...");
        CompressionService service = ServiceFactory.createCompressionService(config, useCpu);
        
        // 2. Perform GPU operation
        updateMessage("Processing...");
        service.compress(inputFile, outputFile, progress -> {
            updateProgress(progress, 1.0);
        });
        
        // Memory logged automatically inside service
        return null;
    }
};

// Success callback (UI thread)
task.setOnSucceeded(event -> {
    statusLabel.setText("Complete!");
});

// Error callback (UI thread)
task.setOnFailed(event -> {
    Throwable ex = task.getException();
    statusLabel.setText("Failed: " + ex.getMessage());
});

// Submit to executor
executor.submit(task);
// UI stays responsive!
```

---

## 🔒 Exception Handling Pattern

### GPU Operation with Cleanup
```java
int[] histogram = null;
int[] localHistograms = null;

try {
    // Get buffers
    histogram = GpuBufferManager.getInstance().getIntBuffer(256);
    localHistograms = GpuBufferManager.getInstance().getIntBuffer(WORK_GROUP_SIZE * 256);
    
    // Initialize to zero
    java.util.Arrays.fill(histogram, 0);
    java.util.Arrays.fill(localHistograms, 0);
    
    // Create task graph
    TaskGraph taskGraph = new TaskGraph("histogram-" + System.nanoTime())
        .transferToDevice(DataTransferMode.FIRST_EXECUTION, data, localHistograms)
        .task("compute", TornadoKernels::histogramWorkGroupKernel, 
              data, 0, length, localHistograms, histogram, WORK_GROUP_SIZE)
        .transferToHost(DataTransferMode.EVERY_EXECUTION, histogram);
    
    // Execute with auto-cleanup
    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
        executionPlan.execute();
    }
    
    // Clean up GPU memory
    cleanupGpuMemory();
    
    // Process results
    return processHistogram(histogram);
    
} catch (Exception e) {
    logger.warn("GPU operation failed: {}", e.getMessage());
    throw e;
} finally {
    // ALWAYS return buffers to pool
    if (histogram != null) {
        GpuBufferManager.getInstance().releaseIntBuffer(histogram);
    }
    if (localHistograms != null) {
        GpuBufferManager.getInstance().releaseIntBuffer(localHistograms);
    }
}
```

---

## 📝 Logging Patterns

### Operation Logging
```java
logger.info("Starting compression: {} ({} bytes)", fileName, fileSize);

// ... perform operation ...

logger.info("Compression complete: {} -> {} bytes ({:.2f}%) in {:.2f}s ({:.2f} MB/s)",
           inputSize, outputSize, ratio * 100, durationSec, throughputMBps);

// Log memory after operation
MemoryMonitor.getInstance().logMemoryStatus("Compression: " + fileName);
```

### Memory Warning Detection
```java
MemorySnapshot snap = MemoryMonitor.getInstance().captureSnapshot();
if (snap.heapUsagePercent > 80) {
    logger.warn("⚠️  High memory usage: {:.1f}%", snap.heapUsagePercent);
    MemoryMonitor.getInstance().suggestGarbageCollection();
}
```

---

## 🔄 Buffer Pool Best Practices

### ✅ DO
```java
// Always use try-finally
int[] buffer = null;
try {
    buffer = GpuBufferManager.getInstance().getIntBuffer(256);
    // ... use buffer ...
} finally {
    if (buffer != null) {
        GpuBufferManager.getInstance().releaseIntBuffer(buffer);
    }
}

// Use unique task graph names
TaskGraph taskGraph = new TaskGraph("operation-" + System.nanoTime());

// Initialize buffers to zero
java.util.Arrays.fill(buffer, 0);
```

### ❌ DON'T
```java
// Don't forget to release
int[] buffer = GpuBufferManager.getInstance().getIntBuffer(256);
// ... use buffer ...
// MISSING: releaseIntBuffer(buffer) - MEMORY LEAK!

// Don't reuse task graph names
TaskGraph taskGraph = new TaskGraph("histogram"); // Same name = conflicts!

// Don't assume buffers are zeroed
int[] buffer = GpuBufferManager.getInstance().getIntBuffer(256);
// buffer may contain old data!
```

---

## 🏃 Running the Application

### Build
```bash
gradlew.bat build -x test
```

### Run (Standard)
```bash
gradlew.bat run
```

### Run (With TornadoVM)
```bash
gradlew.bat runTornado
```

---

## 📈 Memory Monitoring in Production

### Batch Processing Example
```java
for (int i = 0; i < files.size(); i++) {
    Path file = files.get(i);
    
    // Compress file
    compressionService.compress(file, outputFile, progressCallback);
    
    // Log memory every 10 files
    if (i % 10 == 0) {
        MemoryMonitor.getInstance().logMemoryStatus("Batch " + (i/10));
        
        // Check memory pressure
        MemorySnapshot snap = MemoryMonitor.getInstance().captureSnapshot();
        if (snap.heapUsagePercent > 70) {
            logger.warn("High memory, cleaning up...");
            GpuBufferManager.getInstance().clearAllPools();
            MemoryMonitor.getInstance().suggestGarbageCollection();
        }
    }
}
```

---

## 🐛 Debugging Tips

### Check Buffer Pool Stats
```java
String stats = GpuBufferManager.getInstance().getStatistics();
logger.info("Buffer Pool:\n{}", stats);
```

### Check Memory Stats
```java
String stats = MemoryMonitor.getInstance().getStatistics();
logger.info("Memory:\n{}", stats);
```

### Enable Trace Logging
In `logback.xml`:
```xml
<logger name="com.datacomp.service.gpu" level="TRACE"/>
<logger name="com.datacomp.util" level="TRACE"/>
```

---

## 🎯 Common Use Cases

### 1. Single File Compression
```java
CompressionService service = ServiceFactory.createCompressionService(config, false);
service.compress(inputFile, outputFile, progress -> {
    System.out.println("Progress: " + (progress * 100) + "%");
});
MemoryMonitor.getInstance().logMemoryStatus("Single File Compression");
```

### 2. Multiple Files with Memory Monitoring
```java
for (Path file : files) {
    service.compress(file, getOutputPath(file), null);
    
    MemorySnapshot snap = MemoryMonitor.getInstance().captureSnapshot();
    if (snap.heapUsagePercent > 75) {
        GpuBufferManager.getInstance().clearAllPools();
        System.gc();
        Thread.sleep(100);
    }
}
```

### 3. Manual Buffer Management
```java
byte[] buffer = GpuBufferManager.getInstance().getByteBuffer(64 * 1024 * 1024);
try {
    // Read file into buffer
    Files.readAllBytes(file); // or custom read
    
    // Process with GPU
    // ...
    
} finally {
    GpuBufferManager.getInstance().releaseBuffer(buffer);
}
```

---

## 📞 Support

### Documentation
- Full Details: `GPU_ASYNC_MEMORY_MANAGEMENT_SUMMARY.md`
- Changes: `CHANGES_SUMMARY.md`
- UI Fix: `UI_FREEZE_FIX.md`
- Verification: `QUICK_FIX_VERIFICATION.md`

### Key Files
- Buffer Manager: `app/src/main/java/com/datacomp/util/GpuBufferManager.java`
- Memory Monitor: `app/src/main/java/com/datacomp/util/MemoryMonitor.java`
- GPU Service: `app/src/main/java/com/datacomp/service/gpu/GpuCompressionService.java`

---

**Quick Reference v2.0** | November 2025

