package com.almachat.ui;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Manages the JavaFX Stage configuration and window lifecycle.
 * 
 * Responsible for assembling Scenes, setting title, applying window
 * icon, and displaying the primary desktop stage.
 */
public class WindowManager {
    private static final Logger LOG = LoggerFactory.getLogger(WindowManager.class);
    private static final String APP_TITLE = "AlmaChat";
    private static final String ICON_PATH = "/icon.png";

    /**
     * Initializes, builds and displays the primary stage.
     * 
     * @param primaryStage The root stage provided by the JavaFX runtime.
     */
    public void initializePrimaryStage(Stage primaryStage) {
        LOG.info("Initializing primary app stage...");

        // Assemble scene graph from the layout provided by MainView
        primaryStage.setScene(new Scene(new MainView().getRoot(), 1920, 1080));

        // Load window icon from classpath resources
        InputStream iconInputStream = getClass().getResourceAsStream(ICON_PATH);
        if (iconInputStream != null) {
            primaryStage.getIcons().add(new Image(iconInputStream));
        } else {
            LOG.warn("Icon file not found at resource path: {}", ICON_PATH);
        }

        primaryStage.setTitle(APP_TITLE);
        primaryStage.show();
        LOG.info("Primary stage initialized and displayed successfully.");
    }
}
