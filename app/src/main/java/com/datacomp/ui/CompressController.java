package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.model.BenchmarkStage;
import com.datacomp.model.CompressionMetrics;
import com.datacomp.service.CompressionService;
import com.datacomp.service.MetricsService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compression/decompression view controller with detailed stage-wise benchmarking.
 */
public class CompressController implements MainViewController.ConfigurableController {
    
    private static final Logger logger = LoggerFactory.getLogger(CompressController.class);
    
    @FXML private VBox dragDropArea;
    @FXML private Label fileLabel;
    @FXML private TextField inputFileField;
    @FXML private TextField outputFileField;
    @FXML private Button compressButton;
    @FXML private Button decompressButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label statusLabel;
    @FXML private Label throughputLabel;
    @FXML private Label etaLabel;
    @FXML private CheckBox useCpuCheckBox;
    
    // Benchmark UI components
    @FXML private VBox benchmarkSection;
    @FXML private TableView<BenchmarkStageRow> benchmarkTable;
    @FXML private TableColumn<BenchmarkStageRow, String> stageColumn;
    @FXML private TableColumn<BenchmarkStageRow, String> chunkColumn;
    @FXML private TableColumn<BenchmarkStageRow, String> durationColumn;
    @FXML private TableColumn<BenchmarkStageRow, String> throughputColumn;
    @FXML private TableColumn<BenchmarkStageRow, String> statusColumn;
    
    @FXML private Label totalFreqTimeLabel;
    @FXML private Label totalTreeTimeLabel;
    @FXML private Label totalEncodeTimeLabel;
    @FXML private Label totalDecodeTimeLabel;
    @FXML private Label totalChecksumTimeLabel;
    
    private AppConfig config;
    private CompressionService compressionService;
    private Path selectedFile;
    private ExecutorService executor;
    
    private ObservableList<BenchmarkStageRow> benchmarkData;
    private Map<BenchmarkStage.Stage, Double> stageTotalTimes;
    private Map<BenchmarkStage.Stage, Long> stageTotalBytes;
    
    @FXML
    public void initialize() {
        logger.debug("Initializing compress controller");
        executor = Executors.newSingleThreadExecutor();
        benchmarkData = FXCollections.observableArrayList();
        stageTotalTimes = new HashMap<>();
        stageTotalBytes = new HashMap<>();
        
        // Initialize stage totals
        for (BenchmarkStage.Stage stage : BenchmarkStage.Stage.values()) {
            stageTotalTimes.put(stage, 0.0);
            stageTotalBytes.put(stage, 0L);
        }
        
        // Setup drag and drop
        if (dragDropArea != null) {
            setupDragAndDrop();
        }
        
        // Setup input file field listener
        if (inputFileField != null) {
            inputFileField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && !newVal.trim().isEmpty()) {
                    selectedFile = Path.of(newVal);
                    updateButtonStates();
                    autoGenerateOutputPath();
                }
            });
        }
        
        // Setup benchmark table
        if (benchmarkTable != null) {
            setupBenchmarkTable();
        }
        
        // Set CPU mode as default for stability
        if (useCpuCheckBox != null) {
            useCpuCheckBox.setSelected(true);
            logger.info("CPU mode enabled by default for UI stability");
        }
    }
    
    private void setupBenchmarkTable() {
        benchmarkTable.setItems(benchmarkData);
        
        stageColumn.setCellValueFactory(new PropertyValueFactory<>("stageName"));
        chunkColumn.setCellValueFactory(new PropertyValueFactory<>("chunk"));
        durationColumn.setCellValueFactory(new PropertyValueFactory<>("duration"));
        throughputColumn.setCellValueFactory(new PropertyValueFactory<>("throughput"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        
        // Color-coded stage names
        stageColumn.setCellFactory(column -> new TableCell<BenchmarkStageRow, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    BenchmarkStageRow row = getTableView().getItems().get(getIndex());
                    setStyle("-fx-text-fill: " + row.getColor() + "; -fx-font-weight: bold;");
                }
            }
        });
        
        // Limit table height
        benchmarkTable.setFixedCellSize(30);
        benchmarkTable.setPrefHeight(250);
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
        // Don't create compression service here - defer until needed to avoid blocking UI thread
    }
    
    private void setupDragAndDrop() {
        dragDropArea.setOnDragOver(this::handleDragOver);
        dragDropArea.setOnDragDropped(this::handleDragDropped);
    }
    
    private void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }
    
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        
        if (db.hasFiles() && !db.getFiles().isEmpty()) {
            File file = db.getFiles().get(0);
            selectedFile = file.toPath();
            fileLabel.setText(file.getName());
            updateButtonStates();
            success = true;
        }
        
        event.setDropCompleted(success);
        event.consume();
    }
    
    @FXML
    private void handleSelectFile() {
        handleSelectInputFile();
    }
    
    @FXML
    private void handleSelectInputFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Input File");
        
        File file = fileChooser.showOpenDialog(inputFileField != null ? 
            inputFileField.getScene().getWindow() : 
            dragDropArea.getScene().getWindow());
        
        if (file != null) {
            selectedFile = file.toPath();
            if (inputFileField != null) {
                inputFileField.setText(file.getAbsolutePath());
            }
            if (fileLabel != null) {
                fileLabel.setText(file.getName());
            }
            updateButtonStates();
            autoGenerateOutputPath();
        }
    }
    
    @FXML
    private void handleSelectOutputFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Output File");
        
        if (selectedFile != null && selectedFile.getParent() != null) {
            fileChooser.setInitialDirectory(selectedFile.getParent().toFile());
        }
        
        if (selectedFile != null && outputFileField != null) {
            String defaultName = selectedFile.getFileName().toString();
            if (isCompressedFile(selectedFile)) {
                if (defaultName.endsWith(config.getCompressedExtension())) {
                    defaultName = defaultName.substring(0, defaultName.length() - config.getCompressedExtension().length());
                }
            } else {
                defaultName = defaultName + config.getCompressedExtension();
            }
            fileChooser.setInitialFileName(defaultName);
        }
        
        File file = fileChooser.showSaveDialog(outputFileField.getScene().getWindow());
        
        if (file != null && outputFileField != null) {
            outputFileField.setText(file.getAbsolutePath());
        }
    }
    
    private void autoGenerateOutputPath() {
        if (selectedFile == null || outputFileField == null) return;
        
        String outputName;
        if (isCompressedFile(selectedFile)) {
            String fileName = selectedFile.getFileName().toString();
            if (fileName.endsWith(config.getCompressedExtension())) {
                outputName = fileName.substring(0, fileName.length() - config.getCompressedExtension().length());
            } else {
                outputName = fileName + ".decompressed";
            }
        } else {
            outputName = selectedFile.getFileName().toString() + config.getCompressedExtension();
        }
        
        Path outputPath = selectedFile.getParent().resolve(outputName);
        outputFileField.setText(outputPath.toString());
    }
    
    private boolean isCompressedFile(Path file) {
        return file != null && file.toString().endsWith(config.getCompressedExtension());
    }
    
    @FXML
    private void handleCompress() {
        if (selectedFile == null) return;
        
        Path outputPath;
        if (outputFileField != null && !outputFileField.getText().trim().isEmpty()) {
            outputPath = Path.of(outputFileField.getText());
        } else {
            String outputName = selectedFile.getFileName().toString() + config.getCompressedExtension();
            outputPath = selectedFile.getParent().resolve(outputName);
        }
        
        boolean useCpu = useCpuCheckBox != null && useCpuCheckBox.isSelected();
        
        final String processorType = useCpu ? "CPU" : "GPU";
        final Path finalOutputPath = outputPath;
        
        // Clear benchmark data
        Platform.runLater(() -> {
            benchmarkData.clear();
            for (BenchmarkStage.Stage stage : BenchmarkStage.Stage.values()) {
                stageTotalTimes.put(stage, 0.0);
                stageTotalBytes.put(stage, 0L);
            }
            updateStageSummaries();
            if (benchmarkSection != null) {
                benchmarkSection.setVisible(true);
                benchmarkSection.setManaged(true);
            }
        });
        
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                long startTime = System.nanoTime();
                long fileSize = Files.size(selectedFile);
                
                try {
                    // Use ServiceCache with TIMEOUT to prevent indefinite hanging
                    updateMessage("Initializing " + processorType + " service...");
                    updateProgress(0.0, 1.0);
                    
                    compressionService = com.datacomp.service.ServiceCache.getInstance()
                        .getServiceAsync(config, useCpu, msg -> {
                            // Update UI with init progress
                            updateMessage(msg);
                            logger.debug("Init: {}", msg);
                        })
                        .get(30, java.util.concurrent.TimeUnit.SECONDS); // 30 second timeout
                    
                    logger.info("Service initialized successfully: {}", compressionService.getServiceName());
                    
                } catch (java.util.concurrent.TimeoutException e) {
                    logger.warn("Service initialization timed out, falling back to CPU");
                    updateMessage("GPU initialization timeout, using CPU...");
                    compressionService = new com.datacomp.service.cpu.CpuCompressionService(config.getChunkSizeMB());
                } catch (Exception e) {
                    logger.error("Service initialization failed, falling back to CPU", e);
                    updateMessage("Service init failed, using CPU...");
                    compressionService = new com.datacomp.service.cpu.CpuCompressionService(config.getChunkSizeMB());
                }
                
                updateMessage("Compressing...");
                updateProgress(0.0, 1.0);
                
                // Use THROTTLED progress handler to prevent UI freezing
                ThrottledProgressHandler progressHandler = new ThrottledProgressHandler(
                    stage -> {
                        // Add benchmark row (runs on JavaFX thread, already throttled)
                        addBenchmarkRowThrottled(stage);
                        updateStageTotals(stage);
                        updateStageSummaries();
                    },
                    overallProgress -> {
                        // Update progress bar
                        updateProgress(overallProgress, 1.0);
                        
                        // Calculate throughput and ETA
                        long elapsed = System.nanoTime() - startTime;
                        if (elapsed > 0) {
                            double throughputMBps = (fileSize * overallProgress / 1_000_000.0) / 
                                (elapsed / 1_000_000_000.0);
                            double remainingTime = overallProgress > 0 ? 
                                (elapsed / overallProgress - elapsed) / 1_000_000_000.0 : 0;
                            
                            throughputLabel.setText(String.format("%.2f MB/s", throughputMBps));
                            etaLabel.setText(String.format("ETA: %.1fs", remainingTime));
                        }
                    },
                    info -> {
                        // Update additional info (already on JavaFX thread)
                        if (statusLabel != null) {
                            statusLabel.setText(info);
                        }
                    }
                );
                
                // Use stage-wise compression for detailed benchmarking
                compressionService.compressWithStages(selectedFile, finalOutputPath, progressHandler);
                
                // Force final update
                progressHandler.flush();
                
                // Record metrics
                long endTime = System.nanoTime();
                double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
                long inputSize = Files.size(selectedFile);
                long outputSize = Files.size(finalOutputPath);
                double avgThroughput = (inputSize / 1_000_000.0) / durationSeconds;
                
                CompressionMetrics metrics = new CompressionMetrics(
                    selectedFile.getFileName().toString(),
                    CompressionMetrics.OperationType.COMPRESS,
                    inputSize,
                    outputSize,
                    avgThroughput,
                    durationSeconds,
                    processorType
                );
                
                MetricsService.getInstance().addMetrics(metrics);
                
                return null;
            }
        };
        
        task.setOnSucceeded(event -> {
            statusLabel.setText("Compression complete!");
            statusLabel.setStyle("-fx-text-fill: #51cf66;");
            resetProgress();
        });
        
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            logger.error("Compression failed", ex);
            statusLabel.setText("Compression failed: " + ex.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            resetProgress();
        });
        
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        
        compressButton.setDisable(true);
        decompressButton.setDisable(true);
        
        executor.submit(task);
    }
    
    @FXML
    private void handleDecompress() {
        if (selectedFile == null) return;
        
        Path outputPath;
        if (outputFileField != null && !outputFileField.getText().trim().isEmpty()) {
            outputPath = Path.of(outputFileField.getText());
        } else {
            String fileName = selectedFile.getFileName().toString();
            if (fileName.endsWith(config.getCompressedExtension())) {
                fileName = fileName.substring(0, fileName.length() - config.getCompressedExtension().length());
            } else {
                fileName = fileName + ".decompressed";
            }
            outputPath = selectedFile.getParent().resolve(fileName);
        }
        
        boolean useCpu = useCpuCheckBox != null && useCpuCheckBox.isSelected();
        
        final String processorType = useCpu ? "CPU" : "GPU";
        final Path finalOutputPath = outputPath;
        
        // Clear benchmark data
        Platform.runLater(() -> {
            benchmarkData.clear();
            for (BenchmarkStage.Stage stage : BenchmarkStage.Stage.values()) {
                stageTotalTimes.put(stage, 0.0);
                stageTotalBytes.put(stage, 0L);
            }
            updateStageSummaries();
            if (benchmarkSection != null) {
                benchmarkSection.setVisible(true);
                benchmarkSection.setManaged(true);
            }
        });
        
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                long startTime = System.nanoTime();
                long fileSize = Files.size(selectedFile);
                
                try {
                    // Use ServiceCache with TIMEOUT to prevent indefinite hanging
                    updateMessage("Initializing " + processorType + " service...");
                    updateProgress(0.0, 1.0);
                    
                    compressionService = com.datacomp.service.ServiceCache.getInstance()
                        .getServiceAsync(config, useCpu, msg -> {
                            // Update UI with init progress
                            updateMessage(msg);
                            logger.debug("Init: {}", msg);
                        })
                        .get(30, java.util.concurrent.TimeUnit.SECONDS); // 30 second timeout
                    
                    logger.info("Service initialized successfully: {}", compressionService.getServiceName());
                    
                } catch (java.util.concurrent.TimeoutException e) {
                    logger.warn("Service initialization timed out, falling back to CPU");
                    updateMessage("GPU initialization timeout, using CPU...");
                    compressionService = new com.datacomp.service.cpu.CpuCompressionService(config.getChunkSizeMB());
                } catch (Exception e) {
                    logger.error("Service initialization failed, falling back to CPU", e);
                    updateMessage("Service init failed, using CPU...");
                    compressionService = new com.datacomp.service.cpu.CpuCompressionService(config.getChunkSizeMB());
                }
                
                updateMessage("Decompressing...");
                updateProgress(0.0, 1.0);
                
                // Use THROTTLED progress handler to prevent UI freezing
                ThrottledProgressHandler progressHandler = new ThrottledProgressHandler(
                    stage -> {
                        // Add benchmark row (runs on JavaFX thread, already throttled)
                        addBenchmarkRowThrottled(stage);
                        updateStageTotals(stage);
                        updateStageSummaries();
                    },
                    overallProgress -> {
                        // Update progress bar
                        updateProgress(overallProgress, 1.0);
                        
                        // Calculate throughput and ETA
                        long elapsed = System.nanoTime() - startTime;
                        if (elapsed > 0) {
                            double throughputMBps = (fileSize * overallProgress / 1_000_000.0) / 
                                (elapsed / 1_000_000_000.0);
                            double remainingTime = overallProgress > 0 ? 
                                (elapsed / overallProgress - elapsed) / 1_000_000_000.0 : 0;
                            
                            throughputLabel.setText(String.format("%.2f MB/s", throughputMBps));
                            etaLabel.setText(String.format("ETA: %.1fs", remainingTime));
                        }
                    },
                    info -> {
                        // Update additional info (already on JavaFX thread)
                        if (statusLabel != null) {
                            statusLabel.setText(info);
                        }
                    }
                );
                
                compressionService.decompressWithStages(selectedFile, finalOutputPath, progressHandler);
                
                // Force final update
                progressHandler.flush();
                
                long endTime = System.nanoTime();
                double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
                long inputSize = Files.size(selectedFile);
                long outputSize = Files.size(finalOutputPath);
                double avgThroughput = (inputSize / 1_000_000.0) / durationSeconds;
                
                CompressionMetrics metrics = new CompressionMetrics(
                    selectedFile.getFileName().toString(),
                    CompressionMetrics.OperationType.DECOMPRESS,
                    inputSize,
                    outputSize,
                    avgThroughput,
                    durationSeconds,
                    processorType
                );
                
                MetricsService.getInstance().addMetrics(metrics);
                
                return null;
            }
        };
        
        task.setOnSucceeded(event -> {
            statusLabel.setText("Decompression complete!");
            statusLabel.setStyle("-fx-text-fill: #51cf66;");
            resetProgress();
        });
        
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            logger.error("Decompression failed", ex);
            statusLabel.setText("Decompression failed: " + ex.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            resetProgress();
        });
        
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        
        compressButton.setDisable(true);
        decompressButton.setDisable(true);
        
        executor.submit(task);
    }
    
    @SuppressWarnings("unused")
    private void addBenchmarkRow(BenchmarkStage stage) {
        BenchmarkStageRow row = new BenchmarkStageRow(
            stage.getStage().getDisplayName(),
            String.format("%d/%d", stage.getChunkIndex() + 1, stage.getTotalChunks()),
            stage.getFormattedDuration(),
            stage.getFormattedThroughput(),
            "✓",
            stage.getStage().getColor()
        );
        benchmarkData.add(row);
        
        // Keep table scrolled to bottom
        if (benchmarkTable != null) {
            benchmarkTable.scrollTo(benchmarkData.size() - 1);
        }
    }
    
    /**
     * Throttled version that limits table size to prevent performance issues.
     * This method is called from the throttled progress handler (already on JavaFX thread).
     */
    private void addBenchmarkRowThrottled(BenchmarkStage stage) {
        // Maximum number of rows to keep in table for performance
        final int MAX_TABLE_ROWS = 100;
        
        BenchmarkStageRow row = new BenchmarkStageRow(
            stage.getStage().getDisplayName(),
            String.format("%d/%d", stage.getChunkIndex() + 1, stage.getTotalChunks()),
            stage.getFormattedDuration(),
            stage.getFormattedThroughput(),
            "✓",
            stage.getStage().getColor()
        );
        
        benchmarkData.add(row);
        
        // Remove old rows if table is getting too large
        if (benchmarkData.size() > MAX_TABLE_ROWS) {
            // Remove the oldest rows (keep most recent ones)
            int toRemove = benchmarkData.size() - MAX_TABLE_ROWS;
            benchmarkData.remove(0, toRemove);
        }
        
        // Keep table scrolled to bottom (only if close to bottom already)
        if (benchmarkTable != null && benchmarkData.size() > 1) {
            // Only auto-scroll if we're near the bottom to avoid annoying users
            int lastVisibleIndex = benchmarkTable.getItems().size() - 1;
            benchmarkTable.scrollTo(lastVisibleIndex);
        }
    }
    
    private void updateStageTotals(BenchmarkStage stage) {
        BenchmarkStage.Stage stageType = stage.getStage();
        double currentTotal = stageTotalTimes.getOrDefault(stageType, 0.0);
        long currentBytes = stageTotalBytes.getOrDefault(stageType, 0L);
        
        stageTotalTimes.put(stageType, currentTotal + stage.getDurationSeconds());
        stageTotalBytes.put(stageType, currentBytes + stage.getBytesProcessed());
    }
    
    private void updateStageSummaries() {
        if (totalFreqTimeLabel != null) {
            double time = stageTotalTimes.getOrDefault(BenchmarkStage.Stage.FREQUENCY_COUNTING, 0.0);
            long bytes = stageTotalBytes.getOrDefault(BenchmarkStage.Stage.FREQUENCY_COUNTING, 0L);
            double throughput = time > 0 ? (bytes / 1_000_000.0) / time : 0.0;
            totalFreqTimeLabel.setText(String.format("Freq: %.2fs (%.1f MB/s)", time, throughput));
        }
        
        if (totalTreeTimeLabel != null) {
            double time = stageTotalTimes.getOrDefault(BenchmarkStage.Stage.TREE_BUILDING, 0.0);
            long bytes = stageTotalBytes.getOrDefault(BenchmarkStage.Stage.TREE_BUILDING, 0L);
            double throughput = time > 0 ? (bytes / 1_000_000.0) / time : 0.0;
            totalTreeTimeLabel.setText(String.format("Tree: %.2fs (%.1f MB/s)", time, throughput));
        }
        
        if (totalEncodeTimeLabel != null) {
            double time = stageTotalTimes.getOrDefault(BenchmarkStage.Stage.ENCODING, 0.0);
            long bytes = stageTotalBytes.getOrDefault(BenchmarkStage.Stage.ENCODING, 0L);
            double throughput = time > 0 ? (bytes / 1_000_000.0) / time : 0.0;
            totalEncodeTimeLabel.setText(String.format("Encode: %.2fs (%.1f MB/s)", time, throughput));
        }
        
        if (totalDecodeTimeLabel != null) {
            double time = stageTotalTimes.getOrDefault(BenchmarkStage.Stage.DECODING, 0.0);
            long bytes = stageTotalBytes.getOrDefault(BenchmarkStage.Stage.DECODING, 0L);
            double throughput = time > 0 ? (bytes / 1_000_000.0) / time : 0.0;
            totalDecodeTimeLabel.setText(String.format("Decode: %.2fs (%.1f MB/s)", time, throughput));
        }
        
        if (totalChecksumTimeLabel != null) {
            double time = stageTotalTimes.getOrDefault(BenchmarkStage.Stage.CHECKSUM_VALIDATION, 0.0);
            long bytes = stageTotalBytes.getOrDefault(BenchmarkStage.Stage.CHECKSUM_VALIDATION, 0L);
            double throughput = time > 0 ? (bytes / 1_000_000.0) / time : 0.0;
            totalChecksumTimeLabel.setText(String.format("Checksum: %.2fs (%.1f MB/s)", time, throughput));
        }
    }
    
    private void updateButtonStates() {
        boolean hasFile = selectedFile != null && Files.exists(selectedFile);
        compressButton.setDisable(!hasFile);
        
        boolean isCompressed = isCompressedFile(selectedFile);
        decompressButton.setDisable(!isCompressed);
    }
    
    private void resetProgress() {
        compressButton.setDisable(false);
        decompressButton.setDisable(false);
        updateButtonStates();
        
        Platform.runLater(() -> {
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
            progressBar.setProgress(0);
            progressLabel.setText("");
            throughputLabel.setText("");
            etaLabel.setText("");
        });
    }
    
    /**
     * Data model for benchmark table rows.
     */
    public static class BenchmarkStageRow {
        private final SimpleStringProperty stageName;
        private final SimpleStringProperty chunk;
        private final SimpleStringProperty duration;
        private final SimpleStringProperty throughput;
        private final SimpleStringProperty status;
        private final String color;
        
        public BenchmarkStageRow(String stageName, String chunk, String duration,
                                String throughput, String status, String color) {
            this.stageName = new SimpleStringProperty(stageName);
            this.chunk = new SimpleStringProperty(chunk);
            this.duration = new SimpleStringProperty(duration);
            this.throughput = new SimpleStringProperty(throughput);
            this.status = new SimpleStringProperty(status);
            this.color = color;
        }
        
        public String getStageName() { return stageName.get(); }
        public String getChunk() { return chunk.get(); }
        public String getDuration() { return duration.get(); }
        public String getThroughput() { return throughput.get(); }
        public String getStatus() { return status.get(); }
        public String getColor() { return color; }
    }
}
