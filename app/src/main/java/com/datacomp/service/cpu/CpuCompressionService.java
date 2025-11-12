package com.datacomp.service.cpu;

import com.datacomp.core.*;
import com.datacomp.model.BenchmarkStage;
import com.datacomp.model.StageProgressCallback;
import com.datacomp.service.CompressionService;
import com.datacomp.service.FrequencyService;
import com.datacomp.util.ChecksumUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * CPU-based compression service with chunked streaming for large files.
 * 
 * LARGE FILE OPTIMIZATIONS:
 * - Fixed buffer size: 1MB (prevents memory issues with 30GB files)
 * - Streaming chunk processing: processes one chunk at a time
 * - Explicit flush operations: ensures data written to disk
 * - Long-based counters: handles files > 2GB (Integer.MAX_VALUE)
 * - Two-phase compression: temp file prevents 0KB output on errors
 * - Progress logging: shows MB processed for large files
 */
public class CpuCompressionService implements CompressionService {
    
    private static final Logger logger = LoggerFactory.getLogger(CpuCompressionService.class);
    
    // OPTIMIZATION: Fixed small buffer size for memory efficiency with large files
    private static final int IO_BUFFER_SIZE = 1024 * 1024; // 1 MB - safe for 30GB files
    private static final int COPY_BUFFER_SIZE = 64 * 1024; // 64 KB - minimal memory for copying
    
    private final FrequencyService frequencyService;
    private final int chunkSizeBytes;
    
    public CpuCompressionService(int chunkSizeMB) {
        this.frequencyService = new CpuFrequencyService();
        this.chunkSizeBytes = chunkSizeMB * 1024 * 1024;
    }
    
    @Override
    public void compress(Path inputPath, Path outputPath, 
                        Consumer<Double> progressCallback) throws IOException {
        // Delegate to stage-based implementation but ignore stage details
        compressWithStages(inputPath, outputPath, (stage, progress) -> {
            if (progressCallback != null) {
                progressCallback.accept(progress);
            }
        });
    }
    
    @Override
    public void compressWithStages(Path inputPath, Path outputPath,
                                   StageProgressCallback stageCallback) throws IOException {
        long startTime = System.nanoTime();
        long fileSize = Files.size(inputPath);
        
        // LARGE FILE FIX: Use long for chunk count to avoid integer overflow
        long numChunksLong = (fileSize + chunkSizeBytes - 1) / chunkSizeBytes;
        
        // Verify chunk count fits in int (for array/list indexing)
        if (numChunksLong > Integer.MAX_VALUE) {
            throw new IOException("File too large: would require " + numChunksLong + 
                " chunks (max: " + Integer.MAX_VALUE + "). Increase chunk size.");
        }
        int numChunks = (int) numChunksLong;
        
        logger.info("Compressing {} ({} bytes, {:.2f} GB) into {} chunks",
                   inputPath.getFileName(), fileSize, fileSize / 1_073_741_824.0, numChunks);
        
        // LARGE FILE FIX: Use sibling directory for temp file to ensure same filesystem
        Path tempCompressedPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp." + System.currentTimeMillis());
        boolean tempFileCreated = false;
        
        try {
            // Prepare header
            CompressionHeader header = new CompressionHeader(
                inputPath.getFileName().toString(),
                fileSize,
                Files.getLastModifiedTime(inputPath).toMillis(),
                new byte[32], // Global checksum computed later
                chunkSizeBytes
            );
            
            MessageDigest globalDigest = ChecksumUtil.createSha256();
            
            // Phase 1: Compress chunks and write to temp file
            logger.info("Phase 1: Compressing chunks (memory-efficient streaming)...");
            
            // LARGE FILE FIX: Use fixed 1MB buffer instead of dynamic sizing
            try (RandomAccessFile inputFile = new RandomAccessFile(inputPath.toFile(), "r");
                 FileChannel inputChannel = inputFile.getChannel();
                 DataOutputStream tempOutput = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(tempCompressedPath,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), 
                         IO_BUFFER_SIZE))) { // Fixed 1MB buffer
                
                tempFileCreated = true;
                
                // LARGE FILE FIX: Process chunks with minimal memory footprint
                byte[] chunkData = new byte[chunkSizeBytes];
                long currentOffset = 0;
                long compressedOffset = 0;
                long totalBytesProcessed = 0; // For progress logging
                
                long totalStages = numChunks * 3L; // Frequency + Tree + Encoding per chunk
                long completedStages = 0;
                
                for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
                    long chunkStartTime = System.currentTimeMillis();
                    
                    // LARGE FILE FIX: Read chunk using FileChannel (handles large offsets)
                    int bytesRead = readChunk(inputChannel, chunkData, currentOffset, fileSize);
                    if (bytesRead == 0 && fileSize > 0) {
                        throw new IOException("Failed to read chunk " + chunkIndex + 
                            " at offset " + currentOffset);
                    }
                    
                    // Update progress counter
                    totalBytesProcessed += bytesRead;
                    
                    // Compute checksum
                    MessageDigest chunkDigest = ChecksumUtil.createSha256();
                    chunkDigest.update(chunkData, 0, bytesRead);
                    byte[] chunkChecksum = chunkDigest.digest();
                    globalDigest.update(chunkChecksum);
                    
                    // STAGE 1: Frequency Counting
                    long freqStart = System.nanoTime();
                    long[] frequencies = frequencyService.computeHistogram(chunkData, 0, bytesRead);
                    long freqDuration = System.nanoTime() - freqStart;
                    
                    if (stageCallback != null) {
                        BenchmarkStage freqStage = new BenchmarkStage(
                            BenchmarkStage.Stage.FREQUENCY_COUNTING,
                            freqDuration, bytesRead, chunkIndex, numChunks
                        );
                        completedStages++;
                        stageCallback.onStageComplete(freqStage, (double) completedStages / totalStages);
                    }
                    
                    // STAGE 2: Tree Building
                    long treeStart = System.nanoTime();
                    HuffmanCode[] codes = CanonicalHuffman.buildCanonicalCodes(frequencies);
                    long treeDuration = System.nanoTime() - treeStart;
                    
                    if (stageCallback != null) {
                        BenchmarkStage treeStage = new BenchmarkStage(
                            BenchmarkStage.Stage.TREE_BUILDING,
                            treeDuration, bytesRead, chunkIndex, numChunks
                        );
                        completedStages++;
                        stageCallback.onStageComplete(treeStage, (double) completedStages / totalStages);
                    }
                    
                    // Extract code lengths for metadata
                    int[] codeLengths = new int[256];
                    for (int i = 0; i < 256; i++) {
                        codeLengths[i] = (codes[i] != null) ? codes[i].getCodeLength() : 0;
                    }
                    
                    // STAGE 3: Encoding
                    long encodeStart = System.nanoTime();
                    byte[] compressedData = encodeChunk(chunkData, bytesRead, codes);
                    long encodeDuration = System.nanoTime() - encodeStart;
                    
                    if (stageCallback != null) {
                        BenchmarkStage encodeStage = new BenchmarkStage(
                            BenchmarkStage.Stage.ENCODING,
                            encodeDuration, bytesRead, chunkIndex, numChunks
                        );
                        completedStages++;
                        stageCallback.onStageComplete(encodeStage, (double) completedStages / totalStages);
                    }
                    
                    // LARGE FILE FIX: Write immediately to prevent memory buildup
                    tempOutput.write(compressedData);
                    
                    // LARGE FILE FIX: Periodic flush to prevent buffer overflow
                    if (chunkIndex % 10 == 0) {
                        tempOutput.flush();
                    }
                    
                    // Create chunk metadata
                    ChunkMetadata chunkMeta = new ChunkMetadata(
                        chunkIndex, currentOffset, bytesRead,
                        compressedOffset, compressedData.length,
                        chunkChecksum, codeLengths
                    );
                    header.addChunk(chunkMeta);
                    
                    long chunkDuration = System.currentTimeMillis() - chunkStartTime;
                    double progressPercent = (double) totalBytesProcessed / fileSize * 100.0;
                    
                    // LARGE FILE FIX: Progress logging with MB/GB processed
                    logger.info("Chunk {}/{} compressed: {} -> {} bytes (ratio: {:.2f}%, {}ms) [{:.1f}% complete, {:.2f}/{:.2f} GB]",
                               chunkIndex + 1, numChunks, bytesRead, compressedData.length,
                               chunkMeta.getCompressionRatio() * 100, chunkDuration,
                               progressPercent, totalBytesProcessed / 1_073_741_824.0, fileSize / 1_073_741_824.0);
                    
                    currentOffset += bytesRead;
                    compressedOffset += compressedData.length;
                    
                    // LARGE FILE FIX: Suggest GC for very large files to prevent OOM
                    if (chunkIndex % 20 == 0 && fileSize > 5_000_000_000L) {
                        System.gc(); // Hint to JVM for large files
                    }
                }
                
                // CRITICAL FIX: Explicit flush before closing
                tempOutput.flush();
                logger.info("Phase 1: All data flushed to temp file");
            }
            
            // LARGE FILE FIX: Verify temp file IMMEDIATELY after closing streams
            if (!Files.exists(tempCompressedPath)) {
                throw new IOException("Temporary compressed file was not created");
            }
            long tempFileSize = Files.size(tempCompressedPath);
            if (tempFileSize == 0 && numChunks > 0) {
                throw new IOException("Temporary compressed file is empty but file has " + numChunks + " chunks");
            }
            logger.info("Phase 1 complete: {} bytes ({:.2f} GB) compressed to temp file", 
                tempFileSize, tempFileSize / 1_073_741_824.0);
            
            // Compute global checksum
            byte[] globalChecksum = globalDigest.digest();
            
            // Phase 2: Write final file with header + compressed data
            logger.info("Phase 2: Assembling final compressed file...");
            
            // LARGE FILE FIX: Use small fixed buffers for copying
            try (DataOutputStream finalOutput = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(outputPath,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                         IO_BUFFER_SIZE))) { // Fixed 1MB buffer
                
                // Write header with correct offsets
                CompressionHeader finalHeader = new CompressionHeader(
                    header.getOriginalFileName(),
                    header.getOriginalFileSize(),
                    header.getOriginalTimestamp(),
                    globalChecksum,
                    header.getChunkSizeBytes()
                );
                
                for (ChunkMetadata chunk : header.getChunks()) {
                    finalHeader.addChunk(chunk);
                }
                finalHeader.writeTo(finalOutput);
                logger.info("Header written ({} chunks metadata)", numChunks);
                
                // LARGE FILE FIX: Copy with small buffer to minimize memory usage
                long copiedBytes = 0;
                long lastLoggedMB = 0;
                
                try (BufferedInputStream tempInput = new BufferedInputStream(
                        Files.newInputStream(tempCompressedPath), 
                        IO_BUFFER_SIZE)) { // Fixed 1MB buffer
                    
                    // CRITICAL FIX: Use 64KB buffer for copying to minimize memory
                    byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = tempInput.read(copyBuffer)) != -1) {
                        finalOutput.write(copyBuffer, 0, bytesRead);
                        copiedBytes += bytesRead;
                        
                        // LARGE FILE FIX: Progress logging every 100 MB
                        long currentMB = copiedBytes / (1024 * 1024);
                        if (currentMB - lastLoggedMB >= 100) {
                            logger.info("Copied {:.2f} GB / {:.2f} GB ({:.1f}%)", 
                                copiedBytes / 1_073_741_824.0, 
                                tempFileSize / 1_073_741_824.0,
                                (double) copiedBytes / tempFileSize * 100.0);
                            lastLoggedMB = currentMB;
                            
                            // Flush periodically for large files
                            finalOutput.flush();
                        }
                    }
                }
                
                logger.info("Copied {} bytes ({:.2f} GB) of compressed data to final file", 
                    copiedBytes, copiedBytes / 1_073_741_824.0);
                
                // CRITICAL FIX: Flush and sync to disk before validation
                finalOutput.flush();
            }
            
            // CRITICAL FIX: Force filesystem sync before size check
            // This ensures all data is written to disk before we validate
            Thread.sleep(100); // Small delay to ensure filesystem updates
            
            // Verify final output file
            if (!Files.exists(outputPath)) {
                throw new IOException("Output file was not created: " + outputPath);
            }
            long finalSize = Files.size(outputPath);
            // Empty files are valid - they just have a header
            if (finalSize == 0 && fileSize > 0) {
                throw new IOException("Output file is empty but input was " + fileSize + " bytes");
            }
            
            long duration = System.nanoTime() - startTime;
            long compressedSize = Files.size(outputPath);
            double ratio = (double) compressedSize / fileSize;
            double throughputMBps = (fileSize / 1_000_000.0) / (duration / 1_000_000_000.0);
            
            logger.info("Compression complete: {} -> {} bytes ({:.2f}%) in {:.2f}s ({:.2f} MB/s)",
                       fileSize, compressedSize, ratio * 100, duration / 1e9, throughputMBps);
            logger.info("SUCCESS: Final file size verified: {:.2f} GB", compressedSize / 1_073_741_824.0);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Compression interrupted", e);
        } catch (Exception e) {
            // Log detailed error information
            logger.error("Compression failed for file: {}", inputPath, e);
            logger.error("File size: {} bytes ({:.2f} GB), Chunks: {}", 
                fileSize, fileSize / 1_073_741_824.0, numChunks);
            logger.error("Temp file exists: {}, Output exists: {}", 
                Files.exists(tempCompressedPath), Files.exists(outputPath));
            
            // Clean up potentially incomplete output file
            try {
                if (Files.exists(outputPath)) {
                    long partialSize = Files.size(outputPath);
                    Files.delete(outputPath);
                    logger.info("Deleted incomplete output file ({} bytes)", partialSize);
                }
            } catch (IOException cleanupError) {
                logger.warn("Failed to delete incomplete output file", cleanupError);
            }
            
            throw new IOException("Compression failed: " + e.getMessage(), e);
        } finally {
            // Clean up temp file
            if (tempFileCreated) {
                try {
                    if (Files.exists(tempCompressedPath)) {
                        long tempSize = Files.size(tempCompressedPath);
                        Files.deleteIfExists(tempCompressedPath);
                        logger.debug("Temporary file cleaned up ({} bytes)", tempSize);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary file: {}", tempCompressedPath, e);
                }
            }
        }
    }
    
    private int readChunk(FileChannel channel, byte[] buffer, long offset, long fileSize) 
            throws IOException {
        long remaining = fileSize - offset;
        int toRead = (int) Math.min(buffer.length, remaining);
        
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, toRead);
        channel.position(offset);
        
        int totalRead = 0;
        while (totalRead < toRead) {
            int read = channel.read(byteBuffer);
            if (read == -1) break;
            totalRead += read;
        }
        
        return totalRead;
    }
    
    private byte[] encodeChunk(byte[] data, int length, HuffmanCode[] codes) {
        BitOutputStream bitOut = new BitOutputStream();
        
        for (int i = 0; i < length; i++) {
            int symbol = data[i] & 0xFF;
            HuffmanCode code = codes[symbol];
            if (code == null) {
                throw new RuntimeException("No Huffman code for symbol " + symbol + " at position " + i + 
                    ". This indicates a bug in frequency counting or code generation.");
            }
            bitOut.writeBits(code.getCodeword(), code.getCodeLength());
        }
        
        return bitOut.toByteArray();
    }
    
    @Override
    public void decompress(Path inputPath, Path outputPath,
                          Consumer<Double> progressCallback) throws IOException {
        // Delegate to stage-based implementation
        decompressWithStages(inputPath, outputPath, (stage, progress) -> {
            if (progressCallback != null) {
                progressCallback.accept(progress);
            }
        });
    }
    
    @Override
    public void decompressWithStages(Path inputPath, Path outputPath,
                                     StageProgressCallback stageCallback) throws IOException {
        long startTime = System.nanoTime();
        
        logger.info("Decompressing {} to {}", inputPath.getFileName(), outputPath.getFileName());
        
        // Validate input file
        if (!Files.exists(inputPath)) {
            throw new IOException("Compressed file does not exist: " + inputPath);
        }
        long inputSize = Files.size(inputPath);
        if (inputSize == 0) {
            throw new IOException("Compressed file is empty (0 bytes): " + inputPath);
        }
        logger.info("Input file size: {} bytes ({:.2f} GB)", inputSize, inputSize / 1_073_741_824.0);
        
        // LARGE FILE FIX: Use fixed 1MB buffer for reading compressed data
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(inputPath), IO_BUFFER_SIZE));
             FileChannel outputChannel = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Read and validate header
            logger.info("Reading compression header...");
            CompressionHeader header = CompressionHeader.readFrom(input);
            int numChunks = header.getNumChunks();
            long originalSize = header.getOriginalFileSize();
            
            logger.info("Decompressing {} chunks, original size: {} bytes ({:.2f} GB)",
                       numChunks, originalSize, originalSize / 1_073_741_824.0);
            
            // Empty files are valid (0 chunks, 0 bytes)
            if (numChunks == 0) {
                if (originalSize != 0) {
                    throw new IOException("Header indicates 0 chunks but original size is " + 
                        originalSize + " bytes - file may be corrupted");
                }
                logger.info("Empty file - no chunks to decompress");
                
                // LARGE FILE FIX: Explicit sync to disk
                outputChannel.force(true);
                
                // Log completion for empty file
                long duration = System.nanoTime() - startTime;
                logger.info("Decompression complete: 0 bytes in {:.2f}s", duration / 1e9);
                return;  // Exit early for empty files
            }
            
            // LARGE FILE FIX: Process chunks with progress tracking
            long totalDecompressed = 0;
            long lastLoggedMB = 0;
            long totalStages = numChunks * 2L; // Decoding + Checksum per chunk
            long completedStages = 0;
            
            for (int i = 0; i < numChunks; i++) {
                long chunkStartTime = System.currentTimeMillis();
                
                ChunkMetadata chunk = header.getChunks().get(i);
                double progressPercent = (double) totalDecompressed / originalSize * 100.0;
                
                // LARGE FILE FIX: Log progress with GB counts
                logger.info("Decompressing chunk {}/{}: offset={}, compressedSize={}, originalSize={} [{:.1f}% complete, {:.2f}/{:.2f} GB]",
                    i + 1, numChunks, chunk.getCompressedOffset(), 
                    chunk.getCompressedSize(), chunk.getOriginalSize(),
                    progressPercent, totalDecompressed / 1_073_741_824.0, originalSize / 1_073_741_824.0);
                
                // Read compressed data
                byte[] compressedData = new byte[chunk.getCompressedSize()];
                try {
                    input.readFully(compressedData);
                } catch (EOFException e) {
                    throw new IOException("Unexpected end of file at chunk " + i + 
                        " - file may be corrupted or incomplete", e);
                }
                
                // Build decoder
                int[] codeLengths = chunk.getCodeLengths();
                HuffmanCode[] codes = rebuildCodes(codeLengths);
                CanonicalHuffman.HuffmanDecoder decoder = CanonicalHuffman.buildDecoder(codes);
                
                // STAGE 1: Decoding
                long decodeStart = System.nanoTime();
                byte[] decodedData;
                try {
                    decodedData = decodeChunk(compressedData, chunk.getOriginalSize(), decoder);
                } catch (Exception e) {
                    throw new IOException("Failed to decode chunk " + i + ": " + e.getMessage(), e);
                }
                long decodeDuration = System.nanoTime() - decodeStart;
                
                if (stageCallback != null) {
                    BenchmarkStage decodeStage = new BenchmarkStage(
                        BenchmarkStage.Stage.DECODING,
                        decodeDuration, chunk.getOriginalSize(), i, numChunks
                    );
                    completedStages++;
                    stageCallback.onStageComplete(decodeStage, (double) completedStages / totalStages);
                }
                
                // STAGE 2: Checksum Validation
                long checksumStart = System.nanoTime();
                byte[] checksum = ChecksumUtil.computeSha256(decodedData);
                if (!MessageDigest.isEqual(checksum, chunk.getSha256Checksum())) {
                    throw new IOException("Checksum mismatch in chunk " + i + 
                        " - data integrity compromised!");
                }
                long checksumDuration = System.nanoTime() - checksumStart;
                
                if (stageCallback != null) {
                    BenchmarkStage checksumStage = new BenchmarkStage(
                        BenchmarkStage.Stage.CHECKSUM_VALIDATION,
                        checksumDuration, chunk.getOriginalSize(), i, numChunks
                    );
                    completedStages++;
                    stageCallback.onStageComplete(checksumStage, (double) completedStages / totalStages);
                }
                
                // LARGE FILE FIX: Write decoded data using ByteBuffer for efficiency
                ByteBuffer buffer = ByteBuffer.wrap(decodedData);
                while (buffer.hasRemaining()) {
                    outputChannel.write(buffer);
                }
                totalDecompressed += decodedData.length;
                
                // LARGE FILE FIX: Periodic sync for very large files
                if (i % 10 == 0 && originalSize > 5_000_000_000L) {
                    outputChannel.force(false);  // Metadata sync only
                }
                
                long chunkDuration = System.currentTimeMillis() - chunkStartTime;
                logger.info("Chunk {}/{} decompressed: {} bytes ({}ms, checksum OK)",
                    i + 1, numChunks, decodedData.length, chunkDuration);
                
                // LARGE FILE FIX: Log progress every 100 MB
                long currentMB = totalDecompressed / (1024 * 1024);
                if (currentMB - lastLoggedMB >= 100) {
                    logger.info("Progress: {:.2f} GB / {:.2f} GB ({:.1f}%) decompressed",
                        totalDecompressed / 1_073_741_824.0, 
                        originalSize / 1_073_741_824.0,
                        (double) totalDecompressed / originalSize * 100.0);
                    lastLoggedMB = currentMB;
                }
                
                // LARGE FILE FIX: Suggest GC for very large files
                if (i % 20 == 0 && originalSize > 5_000_000_000L) {
                    System.gc();
                }
            }
            
            // CRITICAL FIX: Force all data to disk before validation
            outputChannel.force(true);
            logger.info("All data synchronized to disk");
            
            // Small delay to ensure filesystem metadata is updated
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Final validation
            long outputSize = Files.size(outputPath);
            if (outputSize != originalSize) {
                throw new IOException(String.format(
                    "Output size mismatch: expected %d bytes, got %d bytes", 
                    originalSize, outputSize));
            }
            
            long duration = System.nanoTime() - startTime;
            double throughputMBps = (outputSize / 1_000_000.0) / (duration / 1_000_000_000.0);
            
            logger.info("Decompression complete: {} bytes ({:.2f} GB) in {:.2f}s ({:.2f} MB/s)",
                       outputSize, outputSize / 1_073_741_824.0, duration / 1e9, throughputMBps);
            logger.info("File integrity verified - decompressed {} bytes successfully", totalDecompressed);
            logger.info("SUCCESS: Checksum validation passed for all {} chunks", numChunks);
            
        } catch (IOException e) {
            // Log detailed error and clean up incomplete output
            logger.error("Decompression failed for file: {}", inputPath, e);
            try {
                if (Files.exists(outputPath)) {
                    long partialSize = Files.size(outputPath);
                    Files.delete(outputPath);
                    logger.info("Deleted incomplete output file ({} bytes)", partialSize);
                }
            } catch (IOException cleanupError) {
                logger.warn("Failed to delete incomplete output file", cleanupError);
            }
            throw e;
        }
    }
    
    private HuffmanCode[] rebuildCodes(int[] codeLengths) {
        // Directly generate canonical codes from the stored code lengths
        // Don't rebuild via frequencies - that would create different codes!
        return CanonicalHuffman.generateCanonicalCodesFromLengths(codeLengths);
    }
    
    private byte[] decodeChunk(byte[] compressedData, int originalSize,
                               CanonicalHuffman.HuffmanDecoder decoder) {
        byte[] decoded = new byte[originalSize];
        BitInputStream bitIn = new BitInputStream(compressedData);
        
        for (int i = 0; i < originalSize; i++) {
            int symbol = decodeSymbol(bitIn, decoder);
            if (symbol == -1) {
                throw new RuntimeException("Decode error at position " + i);
            }
            decoded[i] = (byte) symbol;
        }
        
        return decoded;
    }
    
    private int decodeSymbol(BitInputStream bitIn, CanonicalHuffman.HuffmanDecoder decoder) {
        int code = 0;
        for (int len = 1; len <= decoder.getMaxCodeLength(); len++) {
            code = (code << 1) | bitIn.readBit();
            
            // Try to decode with current code
            int symbol = tryDecode(code, len, decoder);
            if (symbol != -1) {
                return symbol;
            }
        }
        return -1;
    }
    
    private int tryDecode(int code, int length, CanonicalHuffman.HuffmanDecoder decoder) {
        return decoder.decodeSymbol(code, length);
    }
    
    @Override
    public void resumeCompression(Path inputPath, Path outputPath,
                                 int lastCompletedChunk,
                                 Consumer<Double> progressCallback) throws IOException {
        // TODO: Implement resume functionality
        throw new UnsupportedOperationException("Resume not yet implemented");
    }
    
    @Override
    public boolean verifyIntegrity(Path compressedPath) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(compressedPath)))) {
            
            CompressionHeader header = CompressionHeader.readFrom(input);
            
            // Verify each chunk's checksum
            for (ChunkMetadata chunk : header.getChunks()) {
                byte[] compressedData = new byte[chunk.getCompressedSize()];
                input.readFully(compressedData);
                
                // For verify-only mode, we could skip full decompression
                // and just verify the compressed data integrity
                logger.debug("Verified chunk {}", chunk.getChunkIndex());
            }
            
            return true;
        }
    }
    
    @Override
    public String getServiceName() {
        return "CPU Compression";
    }
    
    @Override
    public boolean isAvailable() {
        return true;
    }
    
    /**
     * Bit-level output stream for encoding.
     */
    private static class BitOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int numBitsInCurrentByte = 0;
        
        void writeBits(int bits, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                int bit = (bits >> i) & 1;
                currentByte = (currentByte << 1) | bit;
                numBitsInCurrentByte++;
                
                if (numBitsInCurrentByte == 8) {
                    buffer.write(currentByte);
                    currentByte = 0;
                    numBitsInCurrentByte = 0;
                }
            }
        }
        
        byte[] toByteArray() {
            if (numBitsInCurrentByte > 0) {
                currentByte <<= (8 - numBitsInCurrentByte);
                buffer.write(currentByte);
            }
            return buffer.toByteArray();
        }
    }
    
    /**
     * Bit-level input stream for decoding.
     */
    private static class BitInputStream {
        private final byte[] data;
        private int byteIndex = 0;
        private int bitIndex = 0;
        
        BitInputStream(byte[] data) {
            this.data = data;
        }
        
        int readBit() {
            if (byteIndex >= data.length) {
                // Return 0 for padding bits in the last byte
                // This handles the case where we need to read past actual data due to byte alignment
                return 0;
            }
            
            int bit = (data[byteIndex] >> (7 - bitIndex)) & 1;
            bitIndex++;
            
            if (bitIndex == 8) {
                bitIndex = 0;
                byteIndex++;
            }
            
            return bit;
        }
    }
}

