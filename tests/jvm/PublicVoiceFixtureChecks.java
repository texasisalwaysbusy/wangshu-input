package org.wangshu.ime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PublicVoiceFixtureChecks {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Expected the pinned 0.wav and 1.wav");
        byte[] first = Files.readAllBytes(Path.of(args[0]));
        PublicVoiceFixture zero = PublicVoiceFixture.parse(first);
        PublicVoiceFixture one = PublicVoiceFixture.parse(Files.readAllBytes(Path.of(args[1])));
        String zeroSmoke = "MONDAY TOMORROW 星期三";
        String oneSmoke = "这是第一种 什么意思";
        require(zero.name.equals("0.wav") && zero.sampleRate == 16000 && zero.samples.length == 160850);
        require(one.name.equals("1.wav") && one.sampleRate == 16000 && one.samples.length == 81600);
        require(zero.matchesSmoke(zeroSmoke) && !zero.matchesSmoke(oneSmoke));
        require(one.matchesSmoke(oneSmoke) && !one.matchesSmoke(zeroSmoke));
        first[first.length - 1] ^= 1;
        try {
            PublicVoiceFixture.parse(first);
            throw new AssertionError("Altered public WAV accepted");
        } catch (IOException expected) {
            require(expected.getMessage().startsWith("Unrecognized public WAV SHA-256:"));
        }
        System.out.println("PASS pinned WAV identities, PCM shape, matched and swapped assertions, altered input rejection");
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("Public WAV fixture check failed");
    }
}
