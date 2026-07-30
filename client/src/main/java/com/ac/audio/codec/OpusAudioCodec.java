package com.ac.audio.codec;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

/**
 * Wrapper class encapsulating the native Java Concentus Opus implementation for
 * audio encoding and decoding operations.
 */
public class OpusAudioCodec {
    private final OpusEncoder encoder;
    private final OpusDecoder decoder;

    /**
     * Instantiates Opus encoder and decoder instances optimized for VoIP voice
     * transmission.
     * 
     * @param sampleRate Operating sampling rate (e.g., 48kHz)
     * @param channels   Number of channels (1 for Mono, 2 for Stereo)
     * @param bitrate    Target bit rate in bits per second (e.g., 384,000)
     * @throws OpusException If codec initialization fails
     */
    public OpusAudioCodec(int sampleRate, int channels, int bitrate) throws OpusException {
        this.encoder = new OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP);
        this.encoder.setBitrate(bitrate);
        this.decoder = new OpusDecoder(sampleRate, channels);
    }

    /**
     * Encodes uncompressed PCM shorts into an Opus byte payload.
     * 
     * @param pcmInput   Buffer containing raw 16-bit PCM samples
     * @param frameSize  Number of samples per channel (e.g., 960 for 20ms at 48kHz)
     * @param opusOutput Destination buffer for compressed packet
     * @return Transcoded byte payload length
     * @throws OpusException If encoding fails
     */
    public int encode(short[] pcmInput, int frameSize, byte[] opusOutput) throws OpusException {
        return encoder.encode(pcmInput, 0, frameSize, opusOutput, 0, opusOutput.length);
    }

    /**
     * Decodes a compressed Opus byte payload back into raw PCM short samples.
     * 
     * @param opusInput Buffer containing incoming compressed Opus packet
     * @param offset    Read start offset in byte array
     * @param length    Compressed packet length
     * @param pcmOutput Destination buffer for decoded 16-bit PCM shorts
     * @param frameSize Target frame length per channel
     * @return Total decoded sample count per channel
     * @throws OpusException If packet decoding fails
     */
    public int decode(byte[] opusInput, int offset, int length, short[] pcmOutput, int frameSize) throws OpusException {
        return decoder.decode(opusInput, offset, length, pcmOutput, 0, frameSize, false);
    }
}
