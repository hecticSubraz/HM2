package com.datacomp.model;

/**
 * Callback interface for receiving stage-wise progress updates during compression/decompression.
 */
@FunctionalInterface
public interface StageProgressCallback {
    
    /**
     * Called when a processing stage completes.
     * 
     * @param stage The completed stage with timing and throughput metrics
     * @param overallProgress Overall progress (0.0 to 1.0)
     */
    void onStageComplete(BenchmarkStage stage, double overallProgress);
}



