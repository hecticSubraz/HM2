package com.datacomp.model;

/**
 * Represents a single stage in the compression/decompression pipeline with timing and throughput metrics.
 */
public class BenchmarkStage {
    
    public enum Stage {
        FREQUENCY_COUNTING("Frequency Counting", "#3b82f6"),  // Blue
        TREE_BUILDING("Tree Building", "#10b981"),            // Green
        ENCODING("Encoding", "#f59e0b"),                      // Amber
        DECODING("Decoding", "#8b5cf6"),                      // Purple
        CHECKSUM_VALIDATION("Checksum Validation", "#ef4444"); // Red
        
        private final String displayName;
        private final String color;
        
        Stage(String displayName, String color) {
            this.displayName = displayName;
            this.color = color;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getColor() {
            return color;
        }
    }
    
    private final Stage stage;
    private final long durationNanos;
    private final long bytesProcessed;
    private final double throughputMBps;
    private final int chunkIndex;
    private final int totalChunks;
    
    public BenchmarkStage(Stage stage, long durationNanos, long bytesProcessed, 
                         int chunkIndex, int totalChunks) {
        this.stage = stage;
        this.durationNanos = durationNanos;
        this.bytesProcessed = bytesProcessed;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        
        // Calculate throughput (MB/s)
        double durationSeconds = durationNanos / 1_000_000_000.0;
        this.throughputMBps = durationSeconds > 0 ? 
            (bytesProcessed / 1_000_000.0) / durationSeconds : 0.0;
    }
    
    public Stage getStage() {
        return stage;
    }
    
    public long getDurationNanos() {
        return durationNanos;
    }
    
    public double getDurationMillis() {
        return durationNanos / 1_000_000.0;
    }
    
    public double getDurationSeconds() {
        return durationNanos / 1_000_000_000.0;
    }
    
    public long getBytesProcessed() {
        return bytesProcessed;
    }
    
    public double getThroughputMBps() {
        return throughputMBps;
    }
    
    public int getChunkIndex() {
        return chunkIndex;
    }
    
    public int getTotalChunks() {
        return totalChunks;
    }
    
    public String getFormattedDuration() {
        double millis = getDurationMillis();
        if (millis < 1000) {
            return String.format("%.2f ms", millis);
        } else {
            return String.format("%.2f s", getDurationSeconds());
        }
    }
    
    public String getFormattedThroughput() {
        if (throughputMBps < 1.0) {
            return String.format("%.2f KB/s", throughputMBps * 1024);
        } else if (throughputMBps < 1024) {
            return String.format("%.2f MB/s", throughputMBps);
        } else {
            return String.format("%.2f GB/s", throughputMBps / 1024);
        }
    }
    
    @Override
    public String toString() {
        return String.format("%s [Chunk %d/%d]: %s, %s", 
            stage.getDisplayName(), 
            chunkIndex + 1, 
            totalChunks,
            getFormattedDuration(),
            getFormattedThroughput());
    }
}



