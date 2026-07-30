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
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Handles local microphone audio capture, central VoiceServer relay
 * transmission and speaker playback.
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

    // DSP processing variables for audio filtering
    private double hpfPrevInputLeft = 0;
    private double hpfPrevOutputLeft = 0;
    private double hpfPrevInputRight = 0;
    private double hpfPrevOutputRight = 0;

    private TargetDataLine micLine;
    private SourceDataLine speakerLine;
    private DatagramSocket socket;

    private OpusEncoder encoder;
    private OpusDecoder decoder;

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

            // Initialize Opus codecs
            encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(384000); // 384 kbps (Good voice quality)
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);

            decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);

            // Bind to any available local OS port
            socket = new DatagramSocket();

            // Initialize hardware mic input
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
            micLine = (TargetDataLine) AudioSystem.getLine(micInfo);
            micLine.open(FORMAT, PCM_BYTE_SIZE * 4);
            micLine.start();

            // Initialize hardware speaker output
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
            speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speakerLine.open(FORMAT, PCM_BYTE_SIZE * 4);
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
        } catch (OpusException e) {
            LOG.error("Failed to initialize Opus codecs: {}", e.getMessage(), e);
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

        byte[] pcmByteBuffer = new byte[PCM_BYTE_SIZE];
        short[] pcmShortBuffer = new short[FRAME_SIZE * CHANNELS];
        byte[] opusEncodedBuffer = new byte[1275]; // Opus max payload packet size

        while (running) {
            int bytesRead = 0;
            // Ensure full 20ms frame is read before encoding
            while (running && bytesRead < PCM_BYTE_SIZE) {
                int read = micLine.read(pcmByteBuffer, bytesRead, PCM_BYTE_SIZE - bytesRead);
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

            // DSP pipeline
            applyHighPassFilter(pcmShortBuffer, FRAME_SIZE); // Filter handling rumble
            applyLimiter(pcmShortBuffer); // Clamp extreme volume spikes

            try {
                // Encode PCM shorts into Opus payload
                int encodedBytes = encoder.encode(pcmShortBuffer, 0, FRAME_SIZE, opusEncodedBuffer, 0,
                        opusEncodedBuffer.length);

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
                    int samplesDecoded = decoder.decode(packet.getData(), packet.getOffset(), packet.getLength(),
                            decodedPcmShorts, 0, FRAME_SIZE, false);
                    if (samplesDecoded > 0) {
                        // Convert Short samples back into Little-Endian PCM byte array
                        ByteBuffer.wrap(decodedPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                .put(decodedPcmShorts);
                        speakerLine.write(decodedPcmBytes, 0, decodedPcmBytes.length);
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

    /**
     * 1st-Order High-Pass FIlter (~80Hz cutoff at 48kHz).
     * 
     * Strips out low-frequency handling noise, cable rustle, and desk thumps.
     * 
     * @param pcmBuffer      The 16-bit stereo PCM samples to modify in-place
     * @param samplesChannel The number of audio frames per channel (960 for 20ms at
     *                       48kHz)
     */
    private void applyHighPassFilter(short[] pcmBuffer, int samplesChannel) {
        // Filter coefficient alpha for ~80Hz cutoff at 48kHz sample rate
        // alpha = RC / (RC + dt) where RC = 1 / (2 * pi * f_c)
        final double alpha = 0.989639;

        for (int i = 0; i < samplesChannel; i++) {
            // Process left channel
            int leftIdx = i * 2;
            double currentInputLeft = pcmBuffer[leftIdx];
            double currentOutputLeft = alpha * (hpfPrevOutputLeft + currentInputLeft - hpfPrevInputLeft);
            hpfPrevInputLeft = currentInputLeft;
            hpfPrevOutputLeft = currentOutputLeft;
            pcmBuffer[leftIdx] = (short) Math.max(-32768, Math.min(32767, currentOutputLeft));

            // Process right channel
            int rightIdx = i * 2 + 1;
            double currentInputRight = pcmBuffer[rightIdx];
            double currentOutputRight = alpha * (hpfPrevOutputRight + currentInputRight - hpfPrevInputRight);
            hpfPrevInputRight = currentInputRight;
            hpfPrevOutputRight = currentOutputRight;
            pcmBuffer[rightIdx] = (short) Math.max(-32768, Math.min(32767, currentOutputRight));
        }
    }

    /**
     * Soft Limiter (Clippper) with a hard threshold.
     * 
     * Prevents loud mic bumps from blowing out speakers and ears.
     * 
     * @param pcmBuffer The 16-bit stereo PCM samples to modify in-place
     */
    private void applyLimiter(short[] pcmBuffer) {
        final int threshold = 28000; // ~85% of max 16-bit short value (32767)

        for (int i = 0; i < pcmBuffer.length; i++) {
            int sample = pcmBuffer[i];
            if (sample > threshold) {
                // Soft-knee compression above threshold
                pcmBuffer[i] = (short) (threshold + (sample - threshold) * 0.25);
            } else if (sample < -threshold) {
                pcmBuffer[i] = (short) (-threshold + (sample + threshold) * 0.25);
            }
        }
    }
}
