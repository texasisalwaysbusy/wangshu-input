package org.wangshu.ime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Identity and smoke checks for the upstream WAVs. No accuracy reference is implied. */
final class PublicVoiceFixture {
    private static final String WAV_0 = "7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b";
    private static final String WAV_1 = "8bfb42c963e623ebab31b81ff4404867d07d3102507c87ac14577c4c61663b8c";

    final String name;
    final String sha256;
    final int sampleRate;
    final float[] samples;

    private PublicVoiceFixture(String name, String sha256, int sampleRate, float[] samples) {
        this.name = name;
        this.sha256 = sha256;
        this.sampleRate = sampleRate;
        this.samples = samples;
    }

    static PublicVoiceFixture parse(byte[] bytes) throws IOException {
        String hash = digest(bytes);
        String name;
        if (WAV_0.equals(hash)) name = "0.wav";
        else if (WAV_1.equals(hash)) name = "1.wav";
        else throw new IOException("Unrecognized public WAV SHA-256: " + hash);

        ByteBuffer wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || wav.getInt(0) != 0x46464952 || wav.getInt(8) != 0x45564157)
            throw new IOException("Expected RIFF WAV");
        int channels = 0, rate = 0, bits = 0, start = -1, length = 0;
        for (int pos = 12; pos + 8 <= bytes.length;) {
            int chunk = wav.getInt(pos);
            int size = wav.getInt(pos + 4);
            int data = pos + 8;
            if (size < 0 || data + (long) size > bytes.length) throw new IOException("Invalid WAV chunk");
            if (chunk == 0x20746d66) {
                if (size < 16 || wav.getShort(data) != 1) throw new IOException("Expected PCM WAV");
                channels = wav.getShort(data + 2);
                rate = wav.getInt(data + 4);
                bits = wav.getShort(data + 14);
            } else if (chunk == 0x61746164) {
                start = data;
                length = size;
                break;
            }
            pos = data + size + (size & 1);
        }
        if (channels != 1 || rate != 16000 || bits != 16 || start < 0 || length == 0 || (length & 1) != 0)
            throw new IOException("Expected nonempty mono PCM16 16 kHz WAV");
        float[] samples = new float[length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = wav.getShort(start + i * 2) / 32768f;
        return new PublicVoiceFixture(name, hash, rate, samples);
    }

    boolean matchesSmoke(String text) {
        if (WAV_0.equals(sha256))
            return text.contains("MONDAY") && text.contains("TOMORROW") && text.contains("星期三");
        return text.contains("这是第一种") && text.contains("什么意思");
    }

    private static String digest(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }
}
