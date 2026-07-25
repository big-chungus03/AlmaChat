package com.almachat.audio;

import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles local microphone audio capture and speaker output playback.
 */
public class AudioEngine {
    private static final Logger LOG = LoggerFactory.getLogger(AudioEngine.class);

    // Standard VoIP Format: 16kHz, 16-bit, Mono, Signed, Little-Endian
    private static final AudioFormat FORMAT = new AudioFormat(16000.0f, 16, 1, true, false);
    private static final int BUFFER_SIZE = 1024; // ~32ms per packet

    private TargetDataLine micLine;
    private SourceDataLine speakerLine;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private volatile boolean deafened = false;

    private Thread captureThread;

    /**
     * Starts capturing audio from the mic and looping it back to speakers.
     */
    public synchronized void startLoopback() {
        if (running)
            return;

        try {
            LOG.info("Starting audio engine loopback...");
            // Initialize microphone input
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
            micLine = (TargetDataLine) AudioSystem.getLine(micInfo);
            micLine.open(FORMAT, BUFFER_SIZE * 4);
            micLine.start();

            // Initialize speaker output
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
            speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speakerLine.open(FORMAT, BUFFER_SIZE * 4);
            speakerLine.start();

            running = true;

            // Background thread for continuous audio transfer
            captureThread = new Thread(this::audioLoop, "AudioEngine-Thread");
            captureThread.setDaemon(true);
            captureThread.start();

            LOG.info("Audio engine loopback started successfully.");
        } catch (Exception e) {
            LOG.error("Failed to initialize audio devices", e);
            stop();
        }
    }

    private void audioLoop() {
        // Read raw PCM audio from microphone
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            int bytesRead = micLine.read(buffer, 0, buffer.length);

            if (bytesRead <= 0) {
                continue;
            }

            // If muted, replace audio with silence
            if (muted) {
                Arrays.fill(buffer, (byte) 0);
            }

            // If deafened, skip sending output to speaker
            if (!deafened) {
                speakerLine.write(buffer, 0, bytesRead);
            }
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        LOG.info("Audio input state: Muted={}", muted);
    }

    public void setDeafened(boolean deafened) {
        this.deafened = deafened;
        LOG.info("Audio output state: Deafened={}", deafened);
    }

    public synchronized void stop() {
        running = false;
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        if (speakerLine != null) {
            speakerLine.stop();
            speakerLine.close();
        }
        LOG.info("Audio engine stopped.");
    }
}
