package com.ac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);
    private static final int DEFAULT_PORT = 50005;

    public static void main(String[] args) {
        LOG.info("Starting AlmaChat Central Voice Server...");

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid port argument '{}', defaulting to {}", args[0], DEFAULT_PORT);
            }
        }

        VoiceServer voiceServer = new VoiceServer(port);
        voiceServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down server...");
            voiceServer.stop();
        }));
    }
}
