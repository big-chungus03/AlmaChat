package com.ac.audio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ac.audio.codec.OpusAudioCodec;
import com.ac.audio.dsp.AudioFilter;
import com.ac.audio.dsp.HighPassFilter;
import com.ac.audio.dsp.SoftLimiter;
import com.ac.audio.hardware.AudioHardwareManager;

import io.github.jaredmdobson.concentus.OpusException;

/**
 * High-level orchestration engine for VoIP streaming and reception.
 */
public class AudioEngine {
    private static final Logger LOG = LoggerFactory.getLogger(AudioEngine.class);

    // Standard VoIP Format: 48kHz, 16-bit, Stereo, Signed, Little-Endian
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);

    // Opus 20ms Frame Size at 48kHz Stereo:
    // 48000 samples/sec * 0.020 sec = 960 samples per channel per frame.
    private static final int FRAME_SIZE = 960;
    private static final int PCM_BYTE_SIZE = FRAME_SIZE * CHANNELS * 2; // 3840 bytes per 20ms frame
    private static final String LEAVE_SIGNAL = "LEAVE"; // Control signal to notify the server on client exit.

    private final AudioHardwareManager hardwareManager = new AudioHardwareManager();
    private final List<AudioFilter> dspPipeline = new ArrayList<>();

    private OpusAudioCodec codec;
    private DatagramSocket socket;

    private InetAddress serverAddr;
    private int serverPort;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private volatile boolean deafened = false;

    private Thread senderThread;
    private Thread receiverThread;

    public AudioEngine() {
        // Cut low-end rumble
        dspPipeline.add(new HighPassFilter(SAMPLE_RATE, 80.0));
        // Catch sudden loud spikes
        dspPipeline.add(new SoftLimiter());
    }

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

            codec = new OpusAudioCodec(SAMPLE_RATE, CHANNELS, 384000); // Initialize Opus codecs
            socket = new DatagramSocket(); // Bind to any available local OS port
            hardwareManager.open(FORMAT, PCM_BYTE_SIZE);

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
        } catch (OpusException e) {
            LOG.error("Failed to initialize Opus codecs: {}", e.getMessage(), e);
            stop();
        }
    }

    /**
     * Reads mic data continuously and transmits it via UDP packets.
     * 
     * @param targetAddr The destination address of the VoiceServer
     * @param targetPort The destination UDP port of the VoiceServer
     */
    private void sendLoop(InetAddress targetAddr, int targetPort) {

        byte[] pcmByteBuffer = new byte[PCM_BYTE_SIZE];
        short[] pcmShortBuffer = new short[FRAME_SIZE * CHANNELS];
        byte[] opusEncodedBuffer = new byte[1275]; // Opus max payload packet size

        while (running) {
            int bytesRead = 0;
            // Ensure full 20ms frame is read before encoding
            while (running && bytesRead < PCM_BYTE_SIZE) {
                int read = hardwareManager.readMic(pcmByteBuffer, bytesRead, PCM_BYTE_SIZE - bytesRead);
                if (read > 0) {
                    bytesRead += read;
                }
            }

            if (bytesRead < PCM_BYTE_SIZE) {
                continue;
            }

            // If muted, transmit silence (zeros) so timing stays synced
            if (muted) {
                Arrays.fill(pcmByteBuffer, (byte) 0);
            }

            // Convert Little-Endian byte array to 16-bit short PCM samples
            ByteBuffer.wrap(pcmByteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShortBuffer);

            // Execute DSP chain
            for (AudioFilter filter : dspPipeline) {
                filter.process(pcmShortBuffer, FRAME_SIZE);
            }

            try {
                // Encode PCM shorts into Opus payload
                int encodedBytes = codec.encode(pcmShortBuffer, FRAME_SIZE, opusEncodedBuffer);

                if (encodedBytes > 0) {
                    DatagramPacket packet = new DatagramPacket(opusEncodedBuffer, encodedBytes, targetAddr, targetPort);
                    socket.send(packet);

                }
            } catch (IOException e) {
                if (running) {
                    LOG.warn("Failed to send audio packet: {}", e.getMessage());
                }
            } catch (OpusException e) {
                if (running) {
                    LOG.warn("Failed to encode audio packet: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Listens for incoming UDP audio packets and writes them to the speaker.
     */
    private void receiveLoop() {
        byte[] udpReceiveBuffer = new byte[1500]; // Standard MTU size
        short[] decodedPcmShorts = new short[FRAME_SIZE * CHANNELS];
        byte[] decodedPcmBytes = new byte[PCM_BYTE_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(udpReceiveBuffer, udpReceiveBuffer.length);
                socket.receive(packet);

                // Ignore LEAVE or non-audio packets if routed back
                if (packet.getLength() <= 10) {
                    continue;
                }

                if (!deafened) {
                    // Decode Opus payload back into short PCM samples
                    int samplesDecoded = codec.decode(packet.getData(), packet.getOffset(), packet.getLength(),
                            decodedPcmShorts, FRAME_SIZE);
                    if (samplesDecoded > 0) {
                        // Convert Short samples back into Little-Endian PCM byte array
                        ByteBuffer.wrap(decodedPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                .put(decodedPcmShorts);
                        hardwareManager.writeSpeaker(decodedPcmBytes, 0, decodedPcmBytes.length);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    LOG.warn("Failed to receive audio packet: {}", e.getMessage());
                }
            } catch (OpusException e) {
                if (running) {
                    LOG.warn("Failed to decode audio packet: {}", e.getMessage());
                }
            }
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
        hardwareManager.close();

        LOG.info("AudioEngine stopped.");
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
}
