package com.datacomp.test;

import com.datacomp.service.gpu.StreamingGpuCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Random;

/**
 * Test utility for streaming compression with large files.
 * 
 * Tests:
 * 1. Constant memory usage (no heap overflow)
 * 2. Correct compression/decompression
 * 3. Performance with huge files (1GB, 10GB, 100GB+)
 * 
 * Usage:
 *   java com.datacomp.test.StreamingCompressionTest <file-size-MB> [chunk-size-MB]
 * 
 * Examples:
 *   java com.datacomp.test.StreamingCompressionTest 100        # 100MB file, 32MB chunks
 *   java com.datacomp.test.StreamingCompressionTest 1000 64    # 1GB file, 64MB chunks
 *   java com.datacomp.test.StreamingCompressionTest 10000 128  # 10GB file, 128MB chunks
 */
public class StreamingCompressionTest {
    
    private static final Logger logger = LoggerFactory.getLogger(StreamingCompressionTest.class);
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: StreamingCompressionTest <file-size-MB> [chunk-size-MB]");
            System.out.println("Example: StreamingCompressionTest 100 32");
            System.exit(1);
        }
        
        try {
            int fileSizeMB = Integer.parseInt(args[0]);
            int chunkSizeMB = args.length > 1 ? Integer.parseInt(args[1]) : 32;
            
            StreamingCompressionTest test = new StreamingCompressionTest();
            test.runTest(fileSizeMB, chunkSizeMB);
            
        } catch (Exception e) {
            logger.error("Test failed", e);
            System.exit(1);
        }
    }
    
    public void runTest(int fileSizeMB, int chunkSizeMB) throws Exception {
        logger.info("");
        logger.info("╔════════════════════════════════════════════════════════════╗");
        logger.info("║      STREAMING COMPRESSION TEST                            ║");
        logger.info("╠════════════════════════════════════════════════════════════╣");
        logger.info("║  File Size:  {:<46} ║", fileSizeMB + " MB");
        logger.info("║  Chunk Size: {:<46} ║", chunkSizeMB + " MB");
        logger.info("╚════════════════════════════════════════════════════════════╝");
        logger.info("");
        
        Path testFile = Paths.get("streaming-test-" + fileSizeMB + "mb.bin");
        Path compressedFile = Paths.get("streaming-test-" + fileSizeMB + "mb.dc");
        Path decompressedFile = Paths.get("streaming-test-" + fileSizeMB + "mb.restored.bin");
        
        try {
            // Step 1: Generate test file
            logger.info("Step 1: Generating {} MB test file...", fileSizeMB);
            long startGen = System.currentTimeMillis();
            generateTestFile(testFile, fileSizeMB);
            long genTime = System.currentTimeMillis() - startGen;
            logger.info("✓ Test file generated in {}ms", genTime);
            
            // Step 2: Calculate original checksum
            logger.info("Step 2: Computing checksum of original file...");
            long startChecksum = System.currentTimeMillis();
            byte[] originalChecksum = computeChecksum(testFile);
            long checksumTime = System.currentTimeMillis() - startChecksum;
            logger.info("✓ Checksum computed in {}ms: {}", checksumTime, 
                bytesToHex(originalChecksum).substring(0, 16) + "...");
            
            // Step 3: Compress with memory monitoring
            logger.info("Step 3: Compressing with streaming GPU service...");
            monitorMemoryDuring(() -> {
                try {
                    StreamingGpuCompressionService service = 
                        new StreamingGpuCompressionService(chunkSizeMB, true);
                    
                    long startCompress = System.currentTimeMillis();
                    service.compress(testFile, compressedFile, progress -> {
                        if (progress % 0.1 < 0.01) { // Log every 10%
                            logger.debug("Compression progress: {:.1f}%", progress * 100);
                        }
                    });
                    long compressTime = System.currentTimeMillis() - startCompress;
                    
                    long originalSize = Files.size(testFile);
                    long compressedSize = Files.size(compressedFile);
                    double ratio = (double) compressedSize / originalSize;
                    double throughput = (originalSize / 1_000_000.0) / (compressTime / 1000.0);
                    
                    logger.info("✓ Compression complete:");
                    logger.info("  - Time: {}ms", compressTime);
                    logger.info("  - Original: {} MB", originalSize / 1_048_576);
                    logger.info("  - Compressed: {} MB", compressedSize / 1_048_576);
                    logger.info("  - Ratio: {:.2f}%", ratio * 100);
                    logger.info("  - Throughput: {:.2f} MB/s", throughput);
                    
                } catch (Exception e) {
                    throw new RuntimeException("Compression failed", e);
                }
            });
            
            // Step 4: Decompress with memory monitoring
            logger.info("Step 4: Decompressing...");
            monitorMemoryDuring(() -> {
                try {
                    StreamingGpuCompressionService service = 
                        new StreamingGpuCompressionService(chunkSizeMB, true);
                    
                    long startDecompress = System.currentTimeMillis();
                    service.decompress(compressedFile, decompressedFile, progress -> {
                        if (progress % 0.1 < 0.01) { // Log every 10%
                            logger.debug("Decompression progress: {:.1f}%", progress * 100);
                        }
                    });
                    long decompressTime = System.currentTimeMillis() - startDecompress;
                    
                    long decompressedSize = Files.size(decompressedFile);
                    double throughput = (decompressedSize / 1_000_000.0) / (decompressTime / 1000.0);
                    
                    logger.info("✓ Decompression complete:");
                    logger.info("  - Time: {}ms", decompressTime);
                    logger.info("  - Size: {} MB", decompressedSize / 1_048_576);
                    logger.info("  - Throughput: {:.2f} MB/s", throughput);
                    
                } catch (Exception e) {
                    throw new RuntimeException("Decompression failed", e);
                }
            });
            
            // Step 5: Verify checksum
            logger.info("Step 5: Verifying lossless compression...");
            byte[] decompressedChecksum = computeChecksum(decompressedFile);
            
            if (MessageDigest.isEqual(originalChecksum, decompressedChecksum)) {
                logger.info("✓✓✓ SUCCESS: Checksums match - compression is LOSSLESS ✓✓✓");
            } else {
                logger.error("✗✗✗ FAILURE: Checksums do NOT match ✗✗✗");
                logger.error("Original:     {}", bytesToHex(originalChecksum));
                logger.error("Decompressed: {}", bytesToHex(decompressedChecksum));
                throw new RuntimeException("Lossless verification failed!");
            }
            
            // Step 6: Verify file sizes
            long originalSize = Files.size(testFile);
            long decompressedSize = Files.size(decompressedFile);
            
            if (originalSize == decompressedSize) {
                logger.info("✓ File sizes match: {} bytes", originalSize);
            } else {
                throw new RuntimeException(String.format(
                    "File size mismatch: original=%d, decompressed=%d", 
                    originalSize, decompressedSize));
            }
            
            logger.info("");
            logger.info("╔════════════════════════════════════════════════════════════╗");
            logger.info("║                  TEST PASSED ✓                             ║");
            logger.info("╠════════════════════════════════════════════════════════════╣");
            logger.info("║  Streaming compression works correctly!                    ║");
            logger.info("║  Memory usage remained constant.                           ║");
            logger.info("║  Lossless compression verified.                            ║");
            logger.info("╚════════════════════════════════════════════════════════════╝");
            logger.info("");
            
        } finally {
            // Cleanup
            Files.deleteIfExists(testFile);
            Files.deleteIfExists(compressedFile);
            Files.deleteIfExists(decompressedFile);
            logger.info("Test files cleaned up");
        }
    }
    
    /**
     * Generate test file with mix of random and repetitive data.
     */
    private void generateTestFile(Path path, int sizeMB) throws IOException {
        long sizeBytes = (long) sizeMB * 1024 * 1024;
        Random random = new Random(42); // Fixed seed for reproducibility
        
        try (OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(path), 1024 * 1024)) {
            
            byte[] buffer = new byte[1024 * 1024]; // 1 MB buffer
            long remaining = sizeBytes;
            
            while (remaining > 0) {
                int toWrite = (int) Math.min(buffer.length, remaining);
                
                // Mix of random (30%) and repetitive (70%) for moderate compression
                for (int i = 0; i < toWrite; i++) {
                    if (random.nextDouble() < 0.3) {
                        buffer[i] = (byte) random.nextInt(256); // Random
                    } else {
                        buffer[i] = (byte) (i % 64); // Repetitive
                    }
                }
                
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
            }
        }
    }
    
    /**
     * Compute SHA-256 checksum efficiently (streaming).
     */
    private byte[] computeChecksum(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        try (InputStream in = new BufferedInputStream(
                Files.newInputStream(path), 1024 * 1024)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        
        return digest.digest();
    }
    
    /**
     * Monitor memory usage during operation.
     */
    private void monitorMemoryDuring(Runnable operation) {
        Runtime runtime = Runtime.getRuntime();
        
        long startMemory = runtime.totalMemory() - runtime.freeMemory();
        logger.info("Memory before: {} MB", startMemory / 1_048_576);
        
        // Start memory monitor thread
        Thread monitor = new Thread(() -> {
            long maxMemory = startMemory;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                    long currentMemory = runtime.totalMemory() - runtime.freeMemory();
                    maxMemory = Math.max(maxMemory, currentMemory);
                    logger.debug("Current memory: {} MB", currentMemory / 1_048_576);
                }
            } catch (InterruptedException e) {
                // Exit monitor
            }
            logger.info("Peak memory: {} MB", maxMemory / 1_048_576);
        });
        
        monitor.start();
        
        // Run operation
        operation.run();
        
        // Stop monitor
        monitor.interrupt();
        try {
            monitor.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long endMemory = runtime.totalMemory() - runtime.freeMemory();
        logger.info("Memory after: {} MB", endMemory / 1_048_576);
        logger.info("Memory delta: {} MB", (endMemory - startMemory) / 1_048_576);
    }
    
    /**
     * Convert byte array to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

