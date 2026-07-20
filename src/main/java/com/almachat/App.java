package com.almachat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.almachat.ui.MainView;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) throws Exception {
        LOG.info("Starting UI...");

        primaryStage.setScene(new Scene(new MainView().getRoot(), 1920, 1080));
        primaryStage.setTitle("AlmaChat");
        primaryStage.show();

        LOG.info("UI displayed successfully.");
    }

    public static void main(String[] args) {
        Application.launch(args);
    }
}
