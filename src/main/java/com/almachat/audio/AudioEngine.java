package com.almachat.audio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
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
 * Handles local microphone audio capture and speaker output playback.
 */
public class AudioEngine {
    private static final Logger LOG = LoggerFactory.getLogger(AudioEngine.class);

    // Standard VoIP Format: 48kHz, 16-bit, Stereo, Signed, Little-Endian
    private static final AudioFormat FORMAT = new AudioFormat(48000.0f, 16, 2, true, false);
    private static final int BUFFER_SIZE = 3840; // 20ms per packet

    private TargetDataLine micLine;
    private SourceDataLine speakerLine;
    private DatagramSocket socket;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private volatile boolean deafened = false;

    private Thread senderThread;
    private Thread receiverThread;

    /**
     * Starts listening for incoming audio packets on the local port and sending mic
     * audio to the target IP address and port over UDP.
     * 
     * @param localPort  The UDP port to bind for receiving incoming audio
     * @param targetHost The IP adress or hostname of the remote peer
     * @param targetPort The UDP port on the remote peer to send audio to
     */
    public synchronized void startStreaming(int localPort, String targetHost, int targetPort) {
        if (running) {
            return;
        }

        try {
            LOG.info("Initializing UDP AudioEngine on local port {} -> target {}:{}", localPort, targetHost,
                    targetPort);

            // Initialize UDP socket
            socket = new DatagramSocket(localPort);

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

            // Start sender thread (mic -> UDP output)
            InetAddress targetAddr = InetAddress.getByName(targetHost);
            senderThread = new Thread(() -> sendLoop(targetAddr, targetPort), "AudioSender-Thread");
            senderThread.setDaemon(true);
            senderThread.start();

            // Start receiver thread (UDP input -> speaker)
            receiverThread = new Thread(this::receiveLoop, "AudioReceiver-Thread");
            receiverThread.setDaemon(true);
            receiverThread.start();

            LOG.info("AudioEngine streaming started successfully.");
        } catch (SocketException e) {
            LOG.error("Failed to initialize UDP socket: {}", e.getMessage());
            LOG.error("Failed to start AudioEngine streaming", e);
            stop();
        } catch (LineUnavailableException e) {
            LOG.error("Failed to initialize input/output due to resource restrictions: {}", e.getMessage());
            LOG.error("Failed to start AudioEngine streaming", e);
            stop();
        } catch (UnknownHostException e) {
            LOG.error("Failed to start sender thread due to invalid host: {}", e.getMessage());
            LOG.error("Failed to start AudioEngine streaming", e);
            stop();
        }
    }

    /**
     * Reads mic data continuously and transmits it via UDP packets.
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

    public synchronized void stop() {
        LOG.info("Stopping AudioEngine...");
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        if (speakerLine != null) {
            speakerLine.stop();
            speakerLine.close();
        }
        LOG.info("AudioEngine stopped.");
    }
}
