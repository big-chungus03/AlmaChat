package com.ac.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ac.audio.AudioEngine;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Builds and manages the primary application layout for the application.
 * 
 * Implements a fixed left sidebar with channel navigation and a pinned user
 * control panel alongside a dynamic, responsive center workspace.
 */
public class MainView {
    private static final Logger LOG = LoggerFactory.getLogger(MainView.class);

    private boolean isMuted = false;
    private boolean isDeafened = false;

    private final AudioEngine audioEngine = new AudioEngine();

    /**
     * Constructs the root container for the UI.
     * 
     * @return The configured {@link Parent} node for scene embedding.
     */
    public Parent getRoot() {
        LOG.info("Constructing main scene...");
        BorderPane root = new BorderPane();

        // Left region: pinned sidebar containing navigation + bottom user control panel
        LOG.info("Building sidebar...");
        HBox sidebar = new HBox(buildSidebar(), new Separator(Orientation.VERTICAL));
        root.setLeft(sidebar);

        // Center region: Flexible workspace that adapts to screen size
        LOG.info("Building main workspace...");
        root.setCenter(buildMainScreen());

        LOG.info("Main scene constructed successfully.");
        audioEngine.startStreaming(12000, "localhost", 12000);
        return root;
    }

    /**
     * Cleans up background threads and releases audio resources.
     */
    public void cleanup() {
        LOG.info("Cleaning up MainWiew resources...");
        audioEngine.stop();
    }

    /**
     * Builds the fixed-width left sidebar.
     * 
     * Combines channel lists at the top with the user control panel at the bottom.
     */
    private VBox buildSidebar() {
        VBox sidebar = new VBox();
        sidebar.setPrefWidth(240.0); // fixed width: wont stretch horizontally on window resize

        // ---- 1. CHANNEL NAVIGATION (Top section) ----
        VBox channelSection = new VBox(10.0);
        channelSection.setPadding(new Insets(12.0));

        // Voice channels Header & List
        ListView<String> voiceList = new ListView<>();
        voiceList.getItems().add("📢 Main");
        voiceList.getSelectionModel().select(0);
        voiceList.setPrefHeight(100.0);

        Label voiceHeader = new Label("Voice Channels");

        channelSection.getChildren().addAll(voiceHeader, voiceList);
        VBox.setVgrow(channelSection, Priority.ALWAYS); // ensures navigation fills available vertical space

        // ---- 2. CONTROL PANEL (Bottom section) ----
        VBox controlPanel = buildControlPanel();

        sidebar.getChildren().addAll(channelSection, controlPanel);
        return sidebar;
    }

    /**
     * Builds the user control panel pinned to the bottom of the sidebar.
     */
    private VBox buildControlPanel() {
        VBox panel = new VBox(8.0);
        panel.setPadding(new Insets(10.0));

        // Top row: ursername
        HBox userRow = new HBox(8.0);
        userRow.setAlignment(Pos.CENTER);

        Label usernameLabel = new Label("Big Chungus");
        userRow.getChildren().add(usernameLabel);

        // Bottom row: audio controls
        HBox buttonRow = new HBox(6.0);
        buttonRow.setAlignment(Pos.CENTER);

        Button muteButton = new Button("Mute");
        muteButton.setOnAction(e -> {
            isMuted = !isMuted;
            audioEngine.setMuted(isMuted);
            muteButton.setText(isMuted ? "Unmute" : "Mute");
        });

        Button deafenButton = new Button("Deaf");
        deafenButton.setOnAction(e -> {
            isDeafened = !isDeafened;
            audioEngine.setDeafened(isDeafened);
            deafenButton.setText(isDeafened ? "Undeaf" : "Deaf");
        });

        buttonRow.getChildren().addAll(muteButton, deafenButton);
        panel.getChildren().addAll(userRow, buttonRow);

        // Add a top separator before returning
        VBox controlSection = new VBox(8.0, new Separator(), panel);
        return controlSection;
    }

    /**
     * Builds the main center workspace displaying participants in the current
     * channel.
     * 
     * This section automatically expands and reflows on window resize / fullscreen.
     */
    private VBox buildMainScreen() {
        VBox mainScreen = new VBox(20.0);
        mainScreen.setPadding(new Insets(20.0));
        mainScreen.setAlignment(Pos.TOP_LEFT);

        // Header
        Label roomTitle = new Label("📢 Main");

        // Participant display
        ListView<String> activeUsersView = new ListView<>();
        activeUsersView.getItems().add("Big Chungus");

        VBox.setVgrow(activeUsersView, Priority.ALWAYS); // Dynamic resizing with screen size

        mainScreen.getChildren().addAll(roomTitle, activeUsersView);
        return mainScreen;
    }
}
