package com.ac.audio.dsp;

/**
 * Strategy interface representing a real-time Digital Signal Processing (DSP)
 * filter operating on interleaved 16-bit PCM audio frame buffers.
 */
public interface AudioFilter {
    /**
     * Mutates raw 16-bit PCM audio samples in-place.
     * 
     * @param pcmBuffer      Interleaved sample array (e.g., [L0, R0, L1, R1, ...])
     * @param samplesChannel Total frame count per individual audio channel
     */
    void process(short[] pcmBuffer, int samplesChannel);
}