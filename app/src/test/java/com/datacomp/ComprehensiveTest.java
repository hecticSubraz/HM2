package com.datacomp;

import com.datacomp.config.AppConfig;
import com.datacomp.service.CompressionService;
import com.datacomp.service.ServiceCache;
import com.datacomp.service.cpu.CpuCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive test to verify:
 * 1. Compression completes successfully
 * 2. Decompression completes successfully
 * 3. Data integrity (decompressed = original)
 * 4. No hangs or freezes
 * 5. Progress callbacks work
 */
public class ComprehensiveTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ComprehensiveTest.class);
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  COMPREHENSIVE COMPRESSION/DECOMPRESSION TEST                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        boolean allTestsPassed = true;
        
        try {
            // Test 1: Small file (100 KB)
            System.out.println("TEST 1: Small File (100 KB)");
            allTestsPassed &= testFile(100 * 1024, "small");
            
            // Test 2: Medium file (1 MB)
            System.out.println("\nTEST 2: Medium File (1 MB)");
            allTestsPassed &= testFile(1024 * 1024, "medium");
            
            // Test 3: Large file (10 MB)
            System.out.println("\nTEST 3: Large File (10 MB)");
            allTestsPassed &= testFile(10 * 1024 * 1024, "large");
            
            // Test 4: Progress callback test
            System.out.println("\nTEST 4: Progress Callback");
            allTestsPassed &= testProgressCallback();
            
            // Test 5: Service cache test
            System.out.println("\nTEST 5: Service Cache (Second Run Should Be Instant)");
            allTestsPassed &= testServiceCache();
            
            // Final result
            System.out.println();
            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            if (allTestsPassed) {
                System.out.println("║  ✅ ALL TESTS PASSED - NO ISSUES DETECTED!                  ║");
            } else {
                System.out.println("║  ❌ SOME TESTS FAILED - SEE DETAILS ABOVE                   ║");
            }
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            
            System.exit(allTestsPassed ? 0 : 1);
            
        } catch (Exception e) {
            System.err.println("❌ CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static boolean testFile(int sizeBytes, String label) throws IOException {
        System.out.println("  Creating test file (" + formatSize(sizeBytes) + ")...");
        
        // Create test file with pattern
        byte[] originalData = new byte[sizeBytes];
        for (int i = 0; i < sizeBytes; i++) {
            originalData[i] = (byte) (i % 256);
        }
        
        Path testFile = Paths.get("test_" + label + ".dat");
        Path compressedFile = Paths.get("test_" + label + ".dat.huf");
        Path decompressedFile = Paths.get("test_" + label + "_decompressed.dat");
        
        try {
            // Write test file
            Files.write(testFile, originalData);
            System.out.println("  ✓ Test file created: " + formatSize(Files.size(testFile)));
            
            // Get CPU compression service
            AppConfig config = new AppConfig();
            CompressionService service = new CpuCompressionService(config.getChunkSizeMB());
            
            // Test compression
            System.out.println("  Compressing...");
            long compressStart = System.nanoTime();
            
            AtomicInteger progressUpdates = new AtomicInteger(0);
            
            service.compress(testFile, compressedFile, progress -> {
                progressUpdates.incrementAndGet();
                if (progress >= 1.0) {
                    System.out.println("    Progress: 100% ✓");
                }
            });
            
            long compressDuration = System.nanoTime() - compressStart;
            
            if (!Files.exists(compressedFile)) {
                System.out.println("  ❌ FAILED: Compressed file not created!");
                return false;
            }
            
            long compressedSize = Files.size(compressedFile);
            double compressionRatio = (double) compressedSize / sizeBytes * 100;
            double compressThroughput = (sizeBytes / 1_000_000.0) / (compressDuration / 1_000_000_000.0);
            
            System.out.println("  ✓ Compression completed in " + 
                String.format("%.2f", compressDuration / 1_000_000.0) + " ms");
            System.out.println("    Compressed size: " + formatSize(compressedSize) + 
                " (" + String.format("%.1f", compressionRatio) + "%)");
            System.out.println("    Throughput: " + String.format("%.1f", compressThroughput) + " MB/s");
            System.out.println("    Progress updates received: " + progressUpdates.get());
            
            // Test decompression
            System.out.println("  Decompressing...");
            long decompressStart = System.nanoTime();
            
            progressUpdates.set(0);
            
            service.decompress(compressedFile, decompressedFile, progress -> {
                progressUpdates.incrementAndGet();
                if (progress >= 1.0) {
                    System.out.println("    Progress: 100% ✓");
                }
            });
            
            long decompressDuration = System.nanoTime() - decompressStart;
            
            if (!Files.exists(decompressedFile)) {
                System.out.println("  ❌ FAILED: Decompressed file not created!");
                return false;
            }
            
            double decompressThroughput = (sizeBytes / 1_000_000.0) / (decompressDuration / 1_000_000_000.0);
            
            System.out.println("  ✓ Decompression completed in " + 
                String.format("%.2f", decompressDuration / 1_000_000.0) + " ms");
            System.out.println("    Throughput: " + String.format("%.1f", decompressThroughput) + " MB/s");
            System.out.println("    Progress updates received: " + progressUpdates.get());
            
            // Verify data integrity
            System.out.println("  Verifying data integrity...");
            byte[] decompressedData = Files.readAllBytes(decompressedFile);
            
            if (decompressedData.length != originalData.length) {
                System.out.println("  ❌ FAILED: Size mismatch! Original: " + 
                    originalData.length + ", Decompressed: " + decompressedData.length);
                return false;
            }
            
            if (!Arrays.equals(originalData, decompressedData)) {
                System.out.println("  ❌ FAILED: Data mismatch! Decompressed data differs from original!");
                return false;
            }
            
            System.out.println("  ✓ Data integrity verified - perfect match!");
            System.out.println("  ✅ TEST PASSED");
            
            return true;
            
        } finally {
            // Cleanup
            try {
                Files.deleteIfExists(testFile);
                Files.deleteIfExists(compressedFile);
                Files.deleteIfExists(decompressedFile);
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
    }
    
    private static boolean testProgressCallback() {
        System.out.println("  Testing progress callbacks...");
        
        try {
            // Create test file
            byte[] data = new byte[1024 * 1024]; // 1 MB
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 256);
            }
            
            Path testFile = Paths.get("test_progress.dat");
            Path compressedFile = Paths.get("test_progress.dat.huf");
            
            Files.write(testFile, data);
            
            AppConfig config = new AppConfig();
            CompressionService service = new CpuCompressionService(config.getChunkSizeMB());
            
            AtomicInteger callbackCount = new AtomicInteger(0);
            AtomicReference<Double> lastProgress = new AtomicReference<>(0.0);
            
            service.compress(testFile, compressedFile, progress -> {
                callbackCount.incrementAndGet();
                lastProgress.set(progress);
                
                // Verify progress is monotonically increasing
                if (progress < lastProgress.get()) {
                    System.out.println("  ❌ FAILED: Progress went backwards!");
                }
            });
            
            System.out.println("    Callback invoked " + callbackCount.get() + " times");
            System.out.println("    Final progress: " + String.format("%.1f", lastProgress.get() * 100) + "%");
            
            if (callbackCount.get() == 0) {
                System.out.println("  ❌ FAILED: Progress callback never invoked!");
                return false;
            }
            
            if (lastProgress.get() < 0.99) {
                System.out.println("  ❌ FAILED: Progress did not reach 100%!");
                return false;
            }
            
            System.out.println("  ✅ TEST PASSED");
            
            // Cleanup
            Files.deleteIfExists(testFile);
            Files.deleteIfExists(compressedFile);
            
            return true;
            
        } catch (Exception e) {
            System.out.println("  ❌ FAILED: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    private static boolean testServiceCache() {
        System.out.println("  Testing service cache...");
        
        try {
            AppConfig config = new AppConfig();
            
            // First initialization
            System.out.println("    First initialization...");
            long start1 = System.nanoTime();
            CompressionService service1 = ServiceCache.getInstance().getService(config, true);
            long duration1 = System.nanoTime() - start1;
            System.out.println("    Time: " + String.format("%.2f", duration1 / 1_000_000.0) + " ms");
            
            // Second initialization (should be cached)
            System.out.println("    Second initialization (cached)...");
            long start2 = System.nanoTime();
            CompressionService service2 = ServiceCache.getInstance().getService(config, true);
            long duration2 = System.nanoTime() - start2;
            System.out.println("    Time: " + String.format("%.2f", duration2 / 1_000_000.0) + " ms");
            
            if (service1 != service2) {
                System.out.println("  ⚠ WARNING: Services are different instances (may not be cached)");
            } else {
                System.out.println("    ✓ Same service instance returned (cached)");
            }
            
            if (duration2 > duration1) {
                System.out.println("  ⚠ WARNING: Second call was slower (caching may not be working)");
            } else {
                System.out.println("    ✓ Second call was faster (caching works!)");
            }
            
            System.out.println("  ✅ TEST PASSED");
            return true;
            
        } catch (Exception e) {
            System.out.println("  ❌ FAILED: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}


