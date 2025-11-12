package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.service.gpu.GpuFrequencyService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.common.TornadoDevice;

import java.io.IOException;
import java.util.List;

/**
 * Main view controller with navigation and content management.
 */
public class MainViewController {
    
    private static final Logger logger = LoggerFactory.getLogger(MainViewController.class);
    
    @FXML private BorderPane rootPane;
    @FXML private StackPane contentPane;
    @FXML private Label statusLabel;
    @FXML private Label gpuStatusLabel;
    @FXML private ToggleButton dashboardButton;
    @FXML private ToggleButton compressButton;
    @FXML private ToggleButton benchmarkButton;
    @FXML private ToggleButton settingsButton;
    
    private AppConfig config;
    private ToggleGroup navigationGroup;
    
    @FXML
    public void initialize() {
        logger.info("Initializing main view controller");
        
        // Setup navigation
        navigationGroup = new ToggleGroup();
        dashboardButton.setToggleGroup(navigationGroup);
        compressButton.setToggleGroup(navigationGroup);
        benchmarkButton.setToggleGroup(navigationGroup);
        settingsButton.setToggleGroup(navigationGroup);
        
        // Set default selection
        dashboardButton.setSelected(true);
    }
    
    public void setConfig(AppConfig config) {
        this.config = config;
        // Don't create compression service here - defer until needed to avoid blocking UI thread
        
        // Initialize views
        Platform.runLater(() -> {
            showDashboard();
            // Update GPU status in background thread to avoid blocking UI with GPU initialization
            new Thread(() -> updateGpuStatus()).start();
        });
    }
    
    @FXML
    private void showDashboard() {
        loadView("/fxml/DashboardView.fxml", "Dashboard");
    }
    
    @FXML
    private void showCompress() {
        loadView("/fxml/CompressView.fxml", "Compress");
    }
    
    @FXML
    private void showBenchmark() {
        loadView("/fxml/BenchmarkView.fxml", "Benchmark");
    }
    
    @FXML
    private void showSettings() {
        loadView("/fxml/SettingsView.fxml", "Settings");
    }
    
    private void loadView(String fxmlPath, String viewName) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent view = loader.load();
            
            // Inject config into controller if it has setConfig method
            Object controller = loader.getController();
            if (controller instanceof ConfigurableController) {
                ((ConfigurableController) controller).setConfig(config);
            }
            
            contentPane.getChildren().clear();
            contentPane.getChildren().add(view);
            
            setStatus("Viewing: " + viewName);
            
        } catch (IOException e) {
            logger.error("Failed to load view: {}", fxmlPath, e);
            showError("Failed to load view: " + viewName);
        }
    }
    
    private void updateGpuStatus() {
        // This method can be called from a background thread, so wrap UI updates in Platform.runLater
        try {
            List<TornadoDevice> devices = GpuFrequencyService.getAvailableDevices();
            
            if (devices.isEmpty()) {
                Platform.runLater(() -> {
                    if (gpuStatusLabel != null) {
                        gpuStatusLabel.setText("GPU: Not Available");
                        gpuStatusLabel.setStyle("-fx-text-fill: #ff6b6b;");
                    }
                });
            } else {
                TornadoDevice device = devices.get(0);
                Platform.runLater(() -> {
                    if (gpuStatusLabel != null) {
                        gpuStatusLabel.setText("GPU: " + device.getDeviceName());
                        gpuStatusLabel.setStyle("-fx-text-fill: #51cf66;");
                    }
                });
            }
        } catch (Exception e) {
            Platform.runLater(() -> {
                if (gpuStatusLabel != null) {
                    gpuStatusLabel.setText("GPU: Error");
                    gpuStatusLabel.setStyle("-fx-text-fill: #ffa500;");
                }
            });
            logger.warn("Failed to query GPU status", e);
        }
    }
    
    private void setStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }
    
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Interface for controllers that need configuration.
     */
    public interface ConfigurableController {
        void setConfig(AppConfig config);
    }
}

