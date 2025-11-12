package com.datacomp.service;

import com.datacomp.model.StageProgressCallback;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Service interface for compression operations with stage-wise benchmarking support.
 */
public interface CompressionService {
    
    /**
     * Compress a file with basic progress tracking.
     * 
     * @param inputPath Input file path
     * @param outputPath Output file path
     * @param progressCallback Callback for progress updates (0.0 to 1.0)
     * @throws IOException If I/O error occurs
     */
    void compress(Path inputPath, Path outputPath, 
                 Consumer<Double> progressCallback) throws IOException;
    
    /**
     * Compress a file with detailed stage-wise progress tracking.
     * 
     * @param inputPath Input file path
     * @param outputPath Output file path
     * @param stageCallback Callback for stage completion with detailed metrics
     * @throws IOException If I/O error occurs
     */
    default void compressWithStages(Path inputPath, Path outputPath,
                                    StageProgressCallback stageCallback) throws IOException {
        // Default implementation falls back to basic compression
        compress(inputPath, outputPath, progress -> 
            stageCallback.onStageComplete(null, progress));
    }
    
    /**
     * Decompress a file with basic progress tracking.
     * 
     * @param inputPath Compressed file path
     * @param outputPath Output file path
     * @param progressCallback Callback for progress updates (0.0 to 1.0)
     * @throws IOException If I/O error occurs
     */
    void decompress(Path inputPath, Path outputPath,
                   Consumer<Double> progressCallback) throws IOException;
    
    /**
     * Decompress a file with detailed stage-wise progress tracking.
     * 
     * @param inputPath Compressed file path
     * @param outputPath Output file path
     * @param stageCallback Callback for stage completion with detailed metrics
     * @throws IOException If I/O error occurs
     */
    default void decompressWithStages(Path inputPath, Path outputPath,
                                      StageProgressCallback stageCallback) throws IOException {
        // Default implementation falls back to basic decompression
        decompress(inputPath, outputPath, progress -> 
            stageCallback.onStageComplete(null, progress));
    }
    
    /**
     * Resume compression from a checkpoint.
     * 
     * @param inputPath Input file path
     * @param outputPath Partial output file path
     * @param lastCompletedChunk Last successfully compressed chunk index
     * @param progressCallback Callback for progress updates
     * @throws IOException If I/O error occurs
     */
    void resumeCompression(Path inputPath, Path outputPath,
                          int lastCompletedChunk,
                          Consumer<Double> progressCallback) throws IOException;
    
    /**
     * Verify compressed file integrity without full decompression.
     * 
     * @param compressedPath Compressed file path
     * @return true if all checksums match
     * @throws IOException If I/O error occurs
     */
    boolean verifyIntegrity(Path compressedPath) throws IOException;
    
    /**
     * Get service name.
     */
    String getServiceName();
    
    /**
     * Check if service is available.
     */
    boolean isAvailable();
}

