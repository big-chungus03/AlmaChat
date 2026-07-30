package com.ac.audio.hardware;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages low-level hardware interactions with physical audio lines
 * (microphones and speakers) using the Java Sound API
 * ({@link javax.sound.sampled}).
 */
public class AudioHardwareManager {
    private static final Logger LOG = LoggerFactory.getLogger(AudioHardwareManager.class);

    private TargetDataLine micLine;
    private SourceDataLine speakerLine;

    /**
     * Initializes and open target (microphone) and source (speaker) hardware lines.
     * 
     * @param format          The target {@link AudioFormat} (PCM specifications)
     * @param bufferSizeBytes The expected size in bytes for a single processing
     *                        frame
     * @throws LineUnavailableException If the requested jardware line is already in
     *                                  use or unavailable
     */
    public synchronized void open(AudioFormat format, int bufferSizeBytes) throws LineUnavailableException {
        // Configure microphone input
        DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, format);
        micLine = (TargetDataLine) AudioSystem.getLine(micInfo);
        // Quadruple buffer capacity to prevent hardware overrun / dropped frames under
        // CPU spikes
        micLine.open(format, bufferSizeBytes * 4);
        micLine.start();

        // Configure speaker output
        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, format);
        speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
        speakerLine.open(format, bufferSizeBytes * 4);
        speakerLine.start();

        LOG.info("Audio hardware lines opened successfully [Format: {}].", format);
    }

    /**
     * BLocking read call to capture audio samples from the microphone hardware
     * line.
     * 
     * @param buffer Output byte array buffer to store raw PCM data
     * @param offset Byte offset destination start position
     * @param length Number of bytes requested to read
     * @return Number of bytes actually read from the microphone line
     */
    public int readMic(byte[] buffer, int offset, int length) {
        if (micLine.isOpen()) {
            return micLine.read(buffer, offset, length);
        }
        return 0;
    }

    /**
     * Blocking write call to stream decoded PCM samples into the speaker hardware
     * line.
     * 
     * @param buffer Source byte array buffer containing raw PCM data
     * @param offset Byte offset source start position
     * @param length Number of bytes to transfer to the sound card
     */
    public void writeSpeaker(byte[] buffer, int offset, int length) {
        if (speakerLine.isOpen()) {
            speakerLine.write(buffer, offset, length);
        }
    }

    /**
     * Safely drains remaining buffers, halts playback/capture threads, and releases
     * system hardware resources.
     */
    public synchronized void close() {
        if (micLine != null) {
            if (micLine.isOpen()) {
                micLine.stop();
                micLine.close();
            }
        }

        if (speakerLine != null) {
            if (speakerLine.isOpen()) {
                speakerLine.drain();
                speakerLine.stop();
                speakerLine.close();
            }
        }

        LOG.info("Audio hardware lines released.");
    }
}
