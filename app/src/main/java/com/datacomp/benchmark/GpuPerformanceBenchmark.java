package com.datacomp.benchmark;

import com.datacomp.config.AppConfig;
import com.datacomp.service.CompressionService;
import com.datacomp.service.ServiceFactory;
import com.datacomp.service.cpu.CpuCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Comprehensive GPU Performance Benchmark Tool
 * 
 * This tool provides:
 * 1. Lossless compression verification
 * 2. CPU vs GPU performance comparison
 * 3. Throughput measurements (GB/s)
 * 4. Speedup calculations
 * 5. Multiple test scenarios
 * 
 * Usage:
 *   java com.datacomp.benchmark.GpuPerformanceBenchmark [test-file-size-MB]
 * 
 * Example:
 *   java com.datacomp.benchmark.GpuPerformanceBenchmark 100
 */
public class GpuPerformanceBenchmark {
    
    private static final Logger logger = LoggerFactory.getLogger(GpuPerformanceBenchmark.class);
    
    private static final int[] TEST_SIZES_MB = {10, 50, 100, 500, 1000}; // Test file sizes
    private static final int WARMUP_ITERATIONS = 2;
    private static final int MEASUREMENT_ITERATIONS = 5;
    
    public static void main(String[] args) throws IOException {
        logger.info("");
        logger.info("╔════════════════════════════════════════════════════════════╗");
        logger.info("║     GPU HUFFMAN COMPRESSION PERFORMANCE BENCHMARK          ║");
        logger.info("║                  Comprehensive Testing Suite               ║");
        logger.info("╚════════════════════════════════════════════════════════════╝");
        logger.info("");
        
        // Determine test sizes
        int[] testSizes;
        if (args.length > 0) {
            try {
                int customSize = Integer.parseInt(args[0]);
                testSizes = new int[]{customSize};
                logger.info("Running custom test with {} MB file", customSize);
            } catch (NumberFormatException e) {
                logger.warn("Invalid size argument, using default test suite");
                testSizes = TEST_SIZES_MB;
            }
        } else {
            testSizes = TEST_SIZES_MB;
        }
        
        // Run comprehensive benchmark
        GpuPerformanceBenchmark benchmark = new GpuPerformanceBenchmark();
        benchmark.runComprehensiveBenchmark(testSizes);
    }
    
    public void runComprehensiveBenchmark(int[] testSizesMB) throws IOException {
        List<BenchmarkResult> allResults = new ArrayList<>();
        
        // Test each file size
        for (int sizeMB : testSizesMB) {
            logger.info("");
            logger.info("┌────────────────────────────────────────────────────────────┐");
            logger.info("│  TESTING: {} MB FILE                                        │", String.format("%-44s", sizeMB));
            logger.info("└────────────────────────────────────────────────────────────┘");
            
            BenchmarkResult result = runBenchmarkForSize(sizeMB);
            allResults.add(result);
            
            // Print individual result
            printResult(result);
        }
        
        // Print summary
        printSummary(allResults);
    }
    
    private BenchmarkResult runBenchmarkForSize(int sizeMB) throws IOException {
        long sizeBytes = (long) sizeMB * 1024 * 1024;
        
        // Create test data with varying compressibility
        Path testFile = createTestFile(sizeMB);
        Path cpuCompressed = Paths.get("test-cpu-compressed.dc");
        Path gpuCompressed = Paths.get("test-gpu-compressed.dc");
        Path cpuDecompressed = Paths.get("test-cpu-decompressed.bin");
        Path gpuDecompressed = Paths.get("test-gpu-decompressed.bin");
        
        try {
            // Calculate original checksum
            byte[] originalChecksum = computeFileChecksum(testFile);
            
            // Create services
            AppConfig config = new AppConfig();
            CpuCompressionService cpuService = new CpuCompressionService(config.getChunkSizeMB());
            CompressionService gpuService = ServiceFactory.createCompressionService(config);
            
            // Warmup phase
            logger.info("Warming up GPU ({}  iterations)...", WARMUP_ITERATIONS);
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                if (gpuService.isAvailable()) {
                    gpuService.compress(testFile, gpuCompressed, null);
                    Files.deleteIfExists(gpuCompressed);
                }
            }
            
            // CPU Compression Benchmark
            logger.info("Running CPU compression benchmark ({} iterations)...", MEASUREMENT_ITERATIONS);
            List<Long> cpuCompressionTimes = new ArrayList<>();
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                cpuService.compress(testFile, cpuCompressed, null);
                long duration = System.nanoTime() - start;
                cpuCompressionTimes.add(duration);
                
                if (i < MEASUREMENT_ITERATIONS - 1) {
                    Files.deleteIfExists(cpuCompressed);
                }
            }
            long cpuCompressTime = computeMedian(cpuCompressionTimes);
            long cpuCompressedSize = Files.size(cpuCompressed);
            
            // CPU Decompression Benchmark
            logger.info("Running CPU decompression benchmark ({} iterations)...", MEASUREMENT_ITERATIONS);
            List<Long> cpuDecompressionTimes = new ArrayList<>();
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                long start = System.nanoTime();
                cpuService.decompress(cpuCompressed, cpuDecompressed, null);
                long duration = System.nanoTime() - start;
                cpuDecompressionTimes.add(duration);
                
                if (i < MEASUREMENT_ITERATIONS - 1) {
                    Files.deleteIfExists(cpuDecompressed);
                }
            }
            long cpuDecompressTime = computeMedian(cpuDecompressionTimes);
            
            // Verify CPU lossless compression
            byte[] cpuDecompressedChecksum = computeFileChecksum(cpuDecompressed);
            boolean cpuLossless = MessageDigest.isEqual(originalChecksum, cpuDecompressedChecksum);
            
            if (!cpuLossless) {
                throw new RuntimeException("CPU compression is NOT lossless!");
            }
            logger.info("✓ CPU compression verified as LOSSLESS");
            
            // GPU Compression Benchmark
            long gpuCompressTime = 0;
            long gpuCompressedSize = 0;
            long gpuDecompressTime = 0;
            boolean gpuLossless = false;
            boolean gpuAvailable = gpuService.isAvailable();
            
            if (gpuAvailable) {
                logger.info("Running GPU compression benchmark ({} iterations)...", MEASUREMENT_ITERATIONS);
                List<Long> gpuCompressionTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    gpuService.compress(testFile, gpuCompressed, null);
                    long duration = System.nanoTime() - start;
                    gpuCompressionTimes.add(duration);
                    
                    if (i < MEASUREMENT_ITERATIONS - 1) {
                        Files.deleteIfExists(gpuCompressed);
                    }
                }
                gpuCompressTime = computeMedian(gpuCompressionTimes);
                gpuCompressedSize = Files.size(gpuCompressed);
                
                // Verify GPU and CPU produce same compressed size
                if (gpuCompressedSize != cpuCompressedSize) {
                    logger.warn("GPU compressed size ({}) differs from CPU ({})", 
                        gpuCompressedSize, cpuCompressedSize);
                }
                
                // GPU Decompression Benchmark
                logger.info("Running GPU decompression benchmark ({} iterations)...", MEASUREMENT_ITERATIONS);
                List<Long> gpuDecompressionTimes = new ArrayList<>();
                for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                    long start = System.nanoTime();
                    gpuService.decompress(gpuCompressed, gpuDecompressed, null);
                    long duration = System.nanoTime() - start;
                    gpuDecompressionTimes.add(duration);
                    
                    if (i < MEASUREMENT_ITERATIONS - 1) {
                        Files.deleteIfExists(gpuDecompressed);
                    }
                }
                gpuDecompressTime = computeMedian(gpuDecompressionTimes);
                
                // Verify GPU lossless compression
                byte[] gpuDecompressedChecksum = computeFileChecksum(gpuDecompressed);
                gpuLossless = MessageDigest.isEqual(originalChecksum, gpuDecompressedChecksum);
                
                if (!gpuLossless) {
                    throw new RuntimeException("GPU compression is NOT lossless!");
                }
                logger.info("✓ GPU compression verified as LOSSLESS");
            } else {
                logger.warn("GPU not available, skipping GPU benchmarks");
            }
            
            // Calculate metrics
            double cpuCompressThroughput = (sizeBytes / 1_000_000.0) / (cpuCompressTime / 1_000_000_000.0);
            double cpuDecompressThroughput = (sizeBytes / 1_000_000.0) / (cpuDecompressTime / 1_000_000_000.0);
            double compressionRatio = (double) cpuCompressedSize / sizeBytes;
            
            double gpuCompressThroughput = 0;
            double gpuDecompressThroughput = 0;
            double compressSpeedup = 0;
            double decompressSpeedup = 0;
            
            if (gpuAvailable) {
                gpuCompressThroughput = (sizeBytes / 1_000_000.0) / (gpuCompressTime / 1_000_000_000.0);
                gpuDecompressThroughput = (sizeBytes / 1_000_000.0) / (gpuDecompressTime / 1_000_000_000.0);
                compressSpeedup = (double) cpuCompressTime / gpuCompressTime;
                decompressSpeedup = (double) cpuDecompressTime / gpuDecompressTime;
            }
            
            return new BenchmarkResult(
                sizeMB, sizeBytes, compressionRatio,
                cpuCompressTime, cpuDecompressTime,
                cpuCompressThroughput, cpuDecompressThroughput,
                gpuCompressTime, gpuDecompressTime,
                gpuCompressThroughput, gpuDecompressThroughput,
                compressSpeedup, decompressSpeedup,
                cpuLossless, gpuLossless, gpuAvailable
            );
            
        } finally {
            // Cleanup
            Files.deleteIfExists(testFile);
            Files.deleteIfExists(cpuCompressed);
            Files.deleteIfExists(gpuCompressed);
            Files.deleteIfExists(cpuDecompressed);
            Files.deleteIfExists(gpuDecompressed);
        }
    }
    
    private Path createTestFile(int sizeMB) throws IOException {
        Path testFile = Paths.get("test-data-" + sizeMB + "mb.bin");
        long sizeBytes = (long) sizeMB * 1024 * 1024;
        
        logger.info("Generating {} MB test file...", sizeMB);
        
        Random random = new Random(42); // Fixed seed for reproducibility
        byte[] buffer = new byte[1024 * 1024]; // 1 MB buffer
        
        try (var output = Files.newOutputStream(testFile)) {
            long remaining = sizeBytes;
            while (remaining > 0) {
                int toWrite = (int) Math.min(buffer.length, remaining);
                
                // Mix of random and repetitive data (moderate compressibility)
                for (int i = 0; i < toWrite; i++) {
                    if (i % 4 == 0) {
                        buffer[i] = (byte) random.nextInt(256); // Random
                    } else {
                        buffer[i] = (byte) (i % 128); // Repetitive
                    }
                }
                
                output.write(buffer, 0, toWrite);
                remaining -= toWrite;
            }
        }
        
        logger.info("✓ Test file created: {} MB", sizeMB);
        return testFile;
    }
    
    private byte[] computeFileChecksum(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            
            try (var input = Files.newInputStream(file)) {
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            return digest.digest();
        } catch (Exception e) {
            throw new IOException("Failed to compute checksum", e);
        }
    }
    
    private long computeMedian(List<Long> values) {
        values.sort(Long::compareTo);
        int size = values.size();
        if (size % 2 == 0) {
            return (values.get(size / 2 - 1) + values.get(size / 2)) / 2;
        } else {
            return values.get(size / 2);
        }
    }
    
    private void printResult(BenchmarkResult result) {
        logger.info("");
        logger.info("╔════════════════════════════════════════════════════════════╗");
        logger.info("║  BENCHMARK RESULTS: {} MB                                   ║", String.format("%-44s", result.sizeMB));
        logger.info("╠════════════════════════════════════════════════════════════╣");
        logger.info("║  Compression Ratio: {:<39} ║", String.format("%.2f%% (%.2f:1)", result.compressionRatio * 100, 1.0 / result.compressionRatio));
        logger.info("║  Lossless Verified: {:<39} ║", String.format("CPU=%s, GPU=%s", result.cpuLossless ? "✓" : "✗", result.gpuLossless ? "✓" : "✗"));
        logger.info("╠════════════════════════════════════════════════════════════╣");
        logger.info("║  CPU PERFORMANCE:                                          ║");
        logger.info("║    Compression:   {:<41} ║", String.format("%.2f MB/s (%.2fs)", result.cpuCompressThroughput, result.cpuCompressTime / 1e9));
        logger.info("║    Decompression: {:<41} ║", String.format("%.2f MB/s (%.2fs)", result.cpuDecompressThroughput, result.cpuDecompressTime / 1e9));
        logger.info("╠════════════════════════════════════════════════════════════╣");
        
        if (result.gpuAvailable) {
            logger.info("║  GPU PERFORMANCE:                                          ║");
            logger.info("║    Compression:   {:<41} ║", String.format("%.2f MB/s (%.2fs)", result.gpuCompressThroughput, result.gpuCompressTime / 1e9));
            logger.info("║    Decompression: {:<41} ║", String.format("%.2f MB/s (%.2fs)", result.gpuDecompressThroughput, result.gpuDecompressTime / 1e9));
            logger.info("╠════════════════════════════════════════════════════════════╣");
            logger.info("║  SPEEDUP (GPU vs CPU):                                     ║");
            logger.info("║    Compression:   {:<41} ║", String.format("%.2fx faster", result.compressSpeedup));
            logger.info("║    Decompression: {:<41} ║", String.format("%.2fx faster", result.decompressSpeedup));
        } else {
            logger.info("║  GPU: Not Available                                        ║");
        }
        
        logger.info("╚════════════════════════════════════════════════════════════╝");
        logger.info("");
    }
    
    private void printSummary(List<BenchmarkResult> results) {
        logger.info("");
        logger.info("╔════════════════════════════════════════════════════════════╗");
        logger.info("║                    BENCHMARK SUMMARY                       ║");
        logger.info("╚════════════════════════════════════════════════════════════╝");
        logger.info("");
        
        if (results.isEmpty()) {
            logger.info("No results to summarize");
            return;
        }
        
        boolean anyGpuAvailable = results.stream().anyMatch(r -> r.gpuAvailable);
        
        // Calculate averages
        double avgCompressionRatio = results.stream()
            .mapToDouble(r -> r.compressionRatio).average().orElse(0);
        double avgCpuCompressThroughput = results.stream()
            .mapToDouble(r -> r.cpuCompressThroughput).average().orElse(0);
        double avgCpuDecompressThroughput = results.stream()
            .mapToDouble(r -> r.cpuDecompressThroughput).average().orElse(0);
        
        logger.info("Average Compression Ratio: {:.2f}%", avgCompressionRatio * 100);
        logger.info("Average CPU Compression:   {:.2f} MB/s", avgCpuCompressThroughput);
        logger.info("Average CPU Decompression: {:.2f} MB/s", avgCpuDecompressThroughput);
        
        if (anyGpuAvailable) {
            double avgGpuCompressThroughput = results.stream()
                .filter(r -> r.gpuAvailable)
                .mapToDouble(r -> r.gpuCompressThroughput).average().orElse(0);
            double avgGpuDecompressThroughput = results.stream()
                .filter(r -> r.gpuAvailable)
                .mapToDouble(r -> r.gpuDecompressThroughput).average().orElse(0);
            double avgCompressSpeedup = results.stream()
                .filter(r -> r.gpuAvailable)
                .mapToDouble(r -> r.compressSpeedup).average().orElse(0);
            double avgDecompressSpeedup = results.stream()
                .filter(r -> r.gpuAvailable)
                .mapToDouble(r -> r.decompressSpeedup).average().orElse(0);
            
            logger.info("");
            logger.info("Average GPU Compression:   {:.2f} MB/s ({:.3f} GB/s)", 
                avgGpuCompressThroughput, avgGpuCompressThroughput / 1000.0);
            logger.info("Average GPU Decompression: {:.2f} MB/s ({:.3f} GB/s)", 
                avgGpuDecompressThroughput, avgGpuDecompressThroughput / 1000.0);
            logger.info("");
            logger.info("Average Compression Speedup:   {:.2fx", avgCompressSpeedup);
            logger.info("Average Decompression Speedup: {:.2fx", avgDecompressSpeedup);
            logger.info("");
            
            // Performance assessment
            if (avgCompressSpeedup >= 5.0) {
                logger.info("✓ EXCELLENT: GPU compression achieved 5x+ speedup target!");
            } else if (avgCompressSpeedup >= 3.0) {
                logger.info("✓ GOOD: GPU compression achieved 3x+ speedup");
            } else if (avgCompressSpeedup >= 2.0) {
                logger.info("✓ ACCEPTABLE: GPU compression achieved 2x+ speedup");
            } else {
                logger.info("⚠ WARNING: GPU speedup below 2x - consider optimization");
            }
        }
        
        // Lossless verification
        boolean allLossless = results.stream().allMatch(r -> r.cpuLossless && (r.gpuLossless || !r.gpuAvailable));
        if (allLossless) {
            logger.info("");
            logger.info("✓✓✓ ALL TESTS VERIFIED AS LOSSLESS ✓✓✓");
        } else {
            logger.error("");
            logger.error("✗✗✗ LOSSLESS VERIFICATION FAILED ✗✗✗");
        }
        
        logger.info("");
    }
    
    /**
     * Benchmark result data container.
     */
    @SuppressWarnings("unused")  // Fields used for potential JSON serialization
    private static class BenchmarkResult {
        final int sizeMB;
        final long sizeBytes;
        final double compressionRatio;
        
        final long cpuCompressTime;
        final long cpuDecompressTime;
        final double cpuCompressThroughput;
        final double cpuDecompressThroughput;
        
        final long gpuCompressTime;
        final long gpuDecompressTime;
        final double gpuCompressThroughput;
        final double gpuDecompressThroughput;
        
        final double compressSpeedup;
        final double decompressSpeedup;
        
        final boolean cpuLossless;
        final boolean gpuLossless;
        final boolean gpuAvailable;
        
        BenchmarkResult(int sizeMB, long sizeBytes, double compressionRatio,
                       long cpuCompressTime, long cpuDecompressTime,
                       double cpuCompressThroughput, double cpuDecompressThroughput,
                       long gpuCompressTime, long gpuDecompressTime,
                       double gpuCompressThroughput, double gpuDecompressThroughput,
                       double compressSpeedup, double decompressSpeedup,
                       boolean cpuLossless, boolean gpuLossless, boolean gpuAvailable) {
            this.sizeMB = sizeMB;
            this.sizeBytes = sizeBytes;
            this.compressionRatio = compressionRatio;
            this.cpuCompressTime = cpuCompressTime;
            this.cpuDecompressTime = cpuDecompressTime;
            this.cpuCompressThroughput = cpuCompressThroughput;
            this.cpuDecompressThroughput = cpuDecompressThroughput;
            this.gpuCompressTime = gpuCompressTime;
            this.gpuDecompressTime = gpuDecompressTime;
            this.gpuCompressThroughput = gpuCompressThroughput;
            this.gpuDecompressThroughput = gpuDecompressThroughput;
            this.compressSpeedup = compressSpeedup;
            this.decompressSpeedup = decompressSpeedup;
            this.cpuLossless = cpuLossless;
            this.gpuLossless = gpuLossless;
            this.gpuAvailable = gpuAvailable;
        }
    }
}

