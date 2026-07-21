package com.almachat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.almachat.ui.WindowManager;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Main JavaFX Application manager.
 * 
 * Initializes the JavaFX runtime environment and delegates primary stage
 * configuration to the {@link WindowManager}.
 */
public class App extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) throws Exception {
        LOG.info("Starting JavaFX runtime...");
        new WindowManager().initializePrimaryStage(primaryStage);
    }

    public static void main(String[] args) {
        Application.launch(args);
    }
}
