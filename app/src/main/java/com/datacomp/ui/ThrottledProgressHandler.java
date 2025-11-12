package com.datacomp.ui;

import com.datacomp.model.BenchmarkStage;
import com.datacomp.model.StageProgressCallback;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Throttles progress updates to prevent UI thread flooding.
 * Only updates UI at configurable intervals to maintain responsiveness.
 */
public class ThrottledProgressHandler implements StageProgressCallback {
    
    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(ThrottledProgressHandler.class);
    
    // Update UI at most every 100ms (10 FPS)
    private static final long UI_UPDATE_INTERVAL_MS = 100;
    
    // Maximum number of table rows to keep (prevent table from growing indefinitely)
    private static final int MAX_TABLE_ROWS = 100;
    
    private final Consumer<BenchmarkStage> onStageUpdate;
    private final Consumer<Double> onProgressUpdate;
    private final Consumer<String> onThroughputUpdate;
    
    private final AtomicLong lastUiUpdateTime = new AtomicLong(0);
    private final List<BenchmarkStage> pendingStages = new ArrayList<>();
    private volatile double lastProgress = 0.0;
    private volatile int totalStagesProcessed = 0;
    
    // For batch updates
    private volatile boolean updatePending = false;
    
    public ThrottledProgressHandler(
            Consumer<BenchmarkStage> onStageUpdate,
            Consumer<Double> onProgressUpdate,
            Consumer<String> onThroughputUpdate) {
        this.onStageUpdate = onStageUpdate;
        this.onProgressUpdate = onProgressUpdate;
        this.onThroughputUpdate = onThroughputUpdate;
    }
    
    @Override
    public void onStageComplete(BenchmarkStage stage, double overallProgress) {
        totalStagesProcessed++;
        
        // Store stage for batch processing
        if (stage != null) {
            synchronized (pendingStages) {
                pendingStages.add(stage);
                
                // Limit pending stages to prevent memory issues
                if (pendingStages.size() > MAX_TABLE_ROWS * 2) {
                    // Remove oldest stages
                    int toRemove = pendingStages.size() - MAX_TABLE_ROWS;
                    pendingStages.subList(0, toRemove).clear();
                }
            }
        }
        
        lastProgress = overallProgress;
        
        // Check if enough time has passed since last UI update
        long currentTime = System.currentTimeMillis();
        long lastUpdate = lastUiUpdateTime.get();
        
        boolean shouldUpdate = (currentTime - lastUpdate) >= UI_UPDATE_INTERVAL_MS;
        boolean isComplete = overallProgress >= 0.99; // Force update at completion
        
        if ((shouldUpdate || isComplete) && !updatePending) {
            updatePending = true;
            
            // Update timestamp immediately to prevent multiple threads from scheduling updates
            lastUiUpdateTime.set(currentTime);
            
            // Capture current state for UI update
            final double progressToUpdate = lastProgress;
            final List<BenchmarkStage> stagesToUpdate;
            
            synchronized (pendingStages) {
                stagesToUpdate = new ArrayList<>(pendingStages);
                pendingStages.clear();
            }
            
            // Schedule UI update on JavaFX thread
            Platform.runLater(() -> {
                try {
                    // Update progress
                    if (onProgressUpdate != null) {
                        onProgressUpdate.accept(progressToUpdate);
                    }
                    
                    // Update stages (batch)
                    if (onStageUpdate != null && !stagesToUpdate.isEmpty()) {
                        // Only add the most recent stages if we have too many
                        List<BenchmarkStage> stagesToAdd = stagesToUpdate;
                        if (stagesToUpdate.size() > 20) {
                            // Sample every Nth stage to keep UI responsive
                            int sampleRate = stagesToUpdate.size() / 20;
                            stagesToAdd = new ArrayList<>();
                            for (int i = 0; i < stagesToUpdate.size(); i += sampleRate) {
                                stagesToAdd.add(stagesToUpdate.get(i));
                            }
                            // Always include the last stage
                            stagesToAdd.add(stagesToUpdate.get(stagesToUpdate.size() - 1));
                        }
                        
                        for (BenchmarkStage s : stagesToAdd) {
                            onStageUpdate.accept(s);
                        }
                    }
                    
                    // Update throughput info
                    if (onThroughputUpdate != null) {
                        String info = String.format("Processed %d stages | %.1f%% complete",
                            totalStagesProcessed, progressToUpdate * 100);
                        onThroughputUpdate.accept(info);
                    }
                    
                } finally {
                    updatePending = false;
                }
            });
        }
    }
    
    /**
     * Force a final UI update (call this when operation completes).
     */
    public void flush() {
        if (!pendingStages.isEmpty() || lastProgress > 0) {
            // Force immediate update
            lastUiUpdateTime.set(0);
            onStageComplete(null, lastProgress);
            
            // Wait a bit for UI to update
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Get total number of stages processed.
     */
    public int getTotalStagesProcessed() {
        return totalStagesProcessed;
    }
}

