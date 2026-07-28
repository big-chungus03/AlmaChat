package com.ac;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles receiving UDP audio packets from active clients and relaying them to
 * all other connected endpoints in the voice channel.
 */
public class VoiceServer {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceServer.class);

    private static final int BUFFER_SIZE = 1920; // 2KB audio buffer capacity
    private static final String LEAVE_SIGNAL = "LEAVE"; // Control signal indicating a client disconnect.

    private final int port;
    private DatagramSocket socket;
    private volatile boolean running = false;

    // Track active client endpoints (thread-safe set)
    private final Set<SocketAddress> clients = ConcurrentHashMap.newKeySet();

    public VoiceServer(int port) {
        this.port = port;
    }

    /**
     * Binds the server socket to the target UDP port and starts the background
     * listener thread.
     */
    public void start() {
        if (running) {
            return;
        }

        try {
            socket = new DatagramSocket(port);
            running = true;
            LOG.info("VoiceServer listening on UDP port {}", port);

            // Listen loop thread
            Thread listenThread = new Thread(this::listenLoop, "VoiceServer-Listener");
            listenThread.start();
        } catch (SocketException e) {
            LOG.error("Failed to bind VoiceServer to port {}", port, e);
        }
    }

    /**
     * Main background loop that receives UDP packets, handles LEAVE control signal,
     * registers new socket endpoints and broadcasts audio data.
     */
    private void listenLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                SocketAddress senderAddr = packet.getSocketAddress();

                // Check for LEAVE Signal
                if (packet.getLength() < 10) {
                    String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                            StandardCharsets.UTF_8).trim();
                    if (LEAVE_SIGNAL.equals(payload)) {
                        removeClient(senderAddr);
                        continue;
                    }
                }

                // Register client if new
                if (clients.add(senderAddr)) {
                    LOG.info("New client joined voice channel: {}", senderAddr);
                }

                // Relay the exact packet payload to all other peers
                relayPacket(packet, senderAddr);
            } catch (IOException e) {
                if (!running) {
                    LOG.info("VoiceServer socket closed.");
                    break;
                }
                LOG.error("Error receiving packet in VoiceServer", e);
            }

        }
    }

    /**
     * Relay s a received audio packet to all registered endpoints except the
     * original sender.
     * 
     * @param packet     The incoming UDP packet containing PCM audio payload
     * @param senderAddr The socket address of the origin client
     */
    private void relayPacket(DatagramPacket packet, SocketAddress senderAddr) {
        byte[] data = packet.getData();
        int length = packet.getLength();

        for (SocketAddress recipientAddr : clients) {
            // Do NOT echo the audio back to the sender (temporarily disables for debugging
            // reasons)
            // if (recipientAddr.equals(senderAddr)) {
            // continue;
            // }

            try {
                DatagramPacket outgoingPacket = new DatagramPacket(data, length, recipientAddr);
                socket.send(outgoingPacket);
            } catch (IOException e) {
                LOG.warn("Failed to relay packet to {}", recipientAddr, e);
            }
        }
    }

    /**
     * Removes an active client endpoint from the relay registry.
     * 
     * @param addr The {@link SocketAddress} of the client disconnecting.
     */
    public void removeClient(SocketAddress addr) {
        if (clients.remove(addr)) {
            LOG.info("Client left voice channel: {}", addr);
        }
    }

    /**
     * Stops the listener loop and closes the underlying server UDP socket.
     */
    public void stop() {
        running = false;
        socket.close();
        LOG.info("VoiceServer stopped.");
    }
}
