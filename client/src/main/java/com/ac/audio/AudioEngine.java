package com.ac.audio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles local microphone audio capture, central VoiceServer relay
 * transmission and speaker playback.
 */
public class AudioEngine {
    private static final Logger LOG = LoggerFactory.getLogger(AudioEngine.class);

    // Standard VoIP Format: 48kHz, 16-bit, Stereo, Signed, Little-Endian
    private static final AudioFormat FORMAT = new AudioFormat(48000.0f, 16, 2, true, false);
    private static final int BUFFER_SIZE = 3840; // 20ms per packet
    private static final String LEAVE_SIGNAL = "LEAVE"; // Control signal to notify the server on client exit.

    private TargetDataLine micLine;
    private SourceDataLine speakerLine;
    private DatagramSocket socket;

    private InetAddress serverAddr;
    private int serverPort;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private volatile boolean deafened = false;

    private Thread senderThread;
    private Thread receiverThread;

    /**
     * Binds a local ephemeral port and connects to the central VoiceServer.
     * 
     * @param serverHost Target IP or hostname of the VoiceServer
     * @param serverPort Target UDP port of the VoiceServer (default: 50005)
     */
    public synchronized void startStreaming(String serverHost, int serverPort) {
        if (running) {
            return;
        }

        try {
            this.serverPort = serverPort;
            this.serverAddr = InetAddress.getByName(serverHost);

            LOG.info("Connecting AudioEngine to central VoiceServer at {}:{}...", serverHost, serverPort);

            // Bind to any available local OS port
            socket = new DatagramSocket();

            // Initialize hardware mic input
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
            micLine = (TargetDataLine) AudioSystem.getLine(micInfo);
            micLine.open(FORMAT, BUFFER_SIZE * 4);
            micLine.start();

            // Initialize hardware speaker output
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
            speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speakerLine.open(FORMAT, BUFFER_SIZE * 4);
            speakerLine.start();

            running = true;

            // Start sender thread (mic -> VoiceServer)
            senderThread = new Thread(() -> sendLoop(serverAddr, serverPort), "AudioSender-Thread");
            senderThread.setDaemon(true);
            senderThread.start();

            // Start receiver thread (VoiceServer -> speaker)
            receiverThread = new Thread(this::receiveLoop, "AudioReceiver-Thread");
            receiverThread.setDaemon(true);
            receiverThread.start();

            LOG.info("AudioEngine connected and streaming successfully.");
        } catch (SocketException e) {
            LOG.error("Failed to initialize UDP socket: {}", e.getMessage(), e);
            stop();
        } catch (LineUnavailableException e) {
            LOG.error("Audio hardware resource unavailable: {}", e.getMessage(), e);
            stop();
        } catch (UnknownHostException e) {
            LOG.error("Invalid server host address: {}", e.getMessage(), e);
            stop();
        }
    }

    /**
     * Disconnects from the remote VoiceServer by transmitting the LEAVE signal and
     * calls {@link #stop()}.
     */
    public synchronized void disconnect() {
        if (!running) {
            return;
        }

        LOG.info("Disconnecting from voice server...");

        // Send LEAVE control packet if socket is active
        if (!socket.isClosed() && serverAddr != null) {
            try {
                byte[] leaveData = LEAVE_SIGNAL.getBytes(StandardCharsets.UTF_8);
                DatagramPacket leavePacket = new DatagramPacket(leaveData, leaveData.length, serverAddr, serverPort);
                socket.send(leavePacket);
                LOG.info("Sent LEAVE signal to VoiceServer.");
            } catch (IOException e) {
                LOG.warn("Failed to send LEAVE packet to server: {}", e.getMessage());
            }
        }

        stop();
    }

    /**
     * Reads mic data continuously and transmits it via UDP packets.
     * 
     * @param targetAddr The destination address of the VoiceServer
     * @param targetPort The destination UDP port of the VoiceServer
     */
    private void sendLoop(InetAddress targetAddr, int targetPort) {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            int bytesRead = micLine.read(buffer, 0, buffer.length);

            if (bytesRead <= 0) {
                continue;
            }

            // If muted, transmit silence (zeros) so timing stays synced
            if (muted) {
                Arrays.fill(buffer, (byte) 0);
            }

            try {
                DatagramPacket packet = new DatagramPacket(buffer, bytesRead, targetAddr, targetPort);
                socket.send(packet);
            } catch (IOException e) {
                if (running) {
                    LOG.warn("Failed to send audio packet: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Listens for incoming UDP audio packets and writes them to the speaker.
     */
    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                if (!deafened) {
                    speakerLine.write(packet.getData(), 0, packet.getLength());
                }
            } catch (IOException e) {
                if (running) {
                    LOG.warn("Failed to receive audio packet: {}", e.getMessage());
                }
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

    public boolean isConnected() {
        return running;
    }

    /**
     * Stops current background execution, shuts down audio hardware lines and
     * releases network socket resources.
     */
    public synchronized void stop() {
        if (!running && socket.isClosed()) {
            return;
        }

        LOG.info("Stopping AudioEngine...");
        running = false;

        if (!socket.isClosed()) {
            socket.close();
        }
        if (micLine.isOpen()) {
            micLine.stop();
            micLine.close();
        }
        if (speakerLine.isOpen()) {
            speakerLine.stop();
            speakerLine.close();
        }
        LOG.info("AudioEngine stopped.");
    }
}
