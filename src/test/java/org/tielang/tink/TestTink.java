package org.tielang.tink;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

/** Unit tests for {@link Tink} (plain main, no external dependencies). */
public final class TestTink {

    private static int failures = 0;

    private static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("[PASS] " + name);
        } else {
            failures++;
            System.out.println("[FAIL] " + name);
        }
    }

    public static void main(String[] args) {
        // crc32 check vector
        check(Tink.crc32("123456789".getBytes(StandardCharsets.UTF_8)) == 0xCBF43926L,
                "crc32 vector");

        // frame roundtrip
        byte[] p = {1, 2, 3};
        byte[] frame = Tink.frameEncode(p);
        Optional<Tink.Frame> got = Tink.frameNext(frame, 0);
        check(got.isPresent(), "frame present");
        if (got.isPresent()) {
            check(got.get().next() == frame.length, "frame next == length");
            check(Arrays.equals(got.get().payload(), p), "frame payload roundtrip");
        }

        // empty frame roundtrip
        byte[] fe = Tink.frameEncode(new byte[0]);
        Optional<Tink.Frame> ge = Tink.frameNext(fe, 0);
        check(ge.isPresent() && ge.get().next() == fe.length && ge.get().payload().length == 0,
                "empty frame roundtrip");

        // CRC tamper rejected
        byte[] ft = Tink.frameEncode(p);
        ft[4] += 1; // payload[0] tampered
        check(Tink.frameNext(ft, 0).isEmpty(), "crc tamper rejected");

        // frameSkip matches length
        byte[] fs = Tink.frameEncode(p);
        OptionalLong nxt = Tink.frameSkip(fs, 0);
        check(nxt.isPresent() && nxt.getAsLong() == fs.length, "frameSkip matches length");

        // out of bounds
        check(Tink.frameNext(frame, frame.length).isEmpty(), "frameNext out of bounds");
        check(Tink.frameSkip(frame, frame.length).isEmpty(), "frameSkip out of bounds");

        if (failures > 0) {
            System.out.println(failures + " checks FAILED");
            System.exit(1);
        }
        System.out.println("all tests passed");
    }
}