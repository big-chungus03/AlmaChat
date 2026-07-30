package com.ac.audio.dsp;

/**
 * 1st-Order Infinite Impulse Response (IIR) High-Pass Filter.
 * 
 * Attenuates low-frequency noise (e.g., microphone bumps, ambient HVAC rumble,
 * desk thumps) below a specified cut-off frequency.
 */
public class HighPassFilter implements AudioFilter {
    private final double alpha;
    private double hpfPrevInputLeft = 0.0;
    private double hpfPrevOutputLeft = 0.0;
    private double hpfPrevInputRight = 0.0;
    private double hpfPrevOutputRight = 0.0;

    /**
     * Constructs a High-Pass Filter instance.
     * 
     * @param sampleRate The sampling rate of incoming PCM data (e.g., 48kHz)
     * @param cutoffHz   The cutoff frequency threshold in Hz (e.g., 80.0Hz)
     */
    public HighPassFilter(int sampleRate, double cutoffHz) {
        double dt = 1.0 / sampleRate;
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        this.alpha = rc / (rc + dt);
    }

    @Override
    public void process(short[] pcmBuffer, int samplesChannel) {
        for (int i = 0; i < samplesChannel; i++) {
            // Process left channel sample
            int leftIdx = i * 2;
            double currentInputLeft = pcmBuffer[leftIdx];
            double currentOutputLeft = alpha * (hpfPrevOutputLeft + currentInputLeft - hpfPrevInputLeft);
            hpfPrevInputLeft = currentInputLeft;
            hpfPrevOutputLeft = currentOutputLeft;
            pcmBuffer[leftIdx] = (short) Math.max(-32768, Math.min(32767, currentOutputLeft));

            // Process right channel sample
            int rightIdx = i * 2 + 1;
            double currentInputRight = pcmBuffer[rightIdx];
            double currentOutputRight = alpha * (hpfPrevOutputRight + currentInputRight - hpfPrevInputRight);
            hpfPrevInputRight = currentInputRight;
            hpfPrevOutputRight = currentOutputRight;
            pcmBuffer[rightIdx] = (short) Math.max(-32768, Math.min(32767, currentOutputRight));
        }
    }

}
