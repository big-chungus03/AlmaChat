package com.ac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the application.
 * 
 * Serves as a plain Java bootstrap class without extending
 * {@link javafx.application.Application}.
 * This is required to prevent runtime module resolution errors when executing
 * packaged JAR or EXE distributions.
 */
public class Launcher {
    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    /**
     * Launches the underlying JavaFX application.
     */
    public static void main(String[] args) {
        LOG.info("Launching App...");
        App.main(args);
    }
}
