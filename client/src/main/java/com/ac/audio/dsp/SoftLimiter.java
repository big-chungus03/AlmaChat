package com.ac.audio.dsp;

/**
 * Soft-Knee Dynamic Range Compressor & Soft Limiter.
 * 
 * Softens extreme transient amplitude spikes (shouting, accidental mic hits)
 * above a designated threshold to prevent digital clipping distortion and
 * listener ear fatigue.
 */
public class SoftLimiter implements AudioFilter {
    private final int threshold;

    /**
     * Constructs a limiter with a customized sample threshold.
     * 
     * @param threshold Max sample amplitude limit before compression activates (0 -
     *                  32767)
     */
    public SoftLimiter(int threshold) {
        this.threshold = threshold;
    }

    /**
     * Constructs a limiter with a default threshold of 28,000 (~85% full scale).
     */
    public SoftLimiter() {
        this(28000);
    }

    @Override
    public void process(short[] pcmBuffer, int samplesChannel) {
        for (int i = 0; i < pcmBuffer.length; i++) {
            int sample = pcmBuffer[i];
            if (sample > threshold) {
                // Apply a 4:1 soft compression ratio past threshold
                pcmBuffer[i] = (short) (threshold + (sample - threshold) * 0.25);
            } else if (sample < -threshold) {
                pcmBuffer[i] = (short) (-threshold + (sample + threshold) * 0.25);
            }
        }
    }

}
