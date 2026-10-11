package org.tielang.tink;

import java.io.ByteArrayOutputStream;

/**
 * tink v3 frame skeleton + message fragmentation.
 *
 * <p>Mirrors the C/Python ports and the tie reference implementation
 * ({@code tlib/std/tink_v3.tie}); KAT vectors are byte-exact across languages.
 *
 * <p>Frame (16-byte fixed header, big-endian; design doc §3):
 * <pre>
 * [magic 0x74 0x6B][ver=3][type][flags u16][stream_id u32][seq u32][len u16]
 * [ext region: ext_len u16 + TLV bytes]?   (flags.EXT)
 * [payload: len bytes]                     (flags.OFFSET: first 4 bytes = u32 BE offset)
 * [tail: CRC32C 4B | AEAD tag 16B | none]
 * </pre>
 *
 * <p>Implemented subset: CRC32C tail, EXT region, single frames up to 65535
 * bytes, message fragmentation (&gt; 65531 bytes split into OFFSET+FIN
 * fragments, seq = fragment index) with transparent reassembly on decode.
 * PROTECTED frames are rejected (crypto layer out of scope).
 */
public final class TinkV3 {

    private TinkV3() {
    }

    public static final int T_MESSAGE = 0x04;
    public static final int F_EXT = 1;
    public static final int F_PROTECTED = 2;
    public static final int F_CRC = 4;
    public static final int F_FIN = 8;
    public static final int F_OFFSET = 16;
    public static final int F_URGENT = 32;

    /** Max fragment data (65535 - 4-byte offset prefix). */
    public static final int CHUNK_MAX = 65531;

    private static final int[] CRC_TAB = buildTable();

    private static int[] buildTable() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ 0x82F63B78 : (c >>> 1);
            }
            t[i] = c;
        }
        return t;
    }

    /**
     * CRC32C (Castagnoli, reflected poly 0x82F63B78; init/xorout 0xFFFFFFFF).
     *
     * <p>Check vector: {@code crc32c("123456789".getBytes()) == 0xE3069283L}.
     */
    public static long crc32c(byte[] data) {
        return crc32c(data, 0, data.length);
    }

    public static long crc32c(byte[] data, int off, int len) {
        int c = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            c = (c >>> 8) ^ CRC_TAB[(c ^ data[i]) & 0xFF];
        }
        return (c ^ 0xFFFFFFFF) & 0xFFFFFFFFL;
    }

    private static void wrU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void wrU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    private static int rdU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long rdU32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] frameOne(byte[] payload, int flags, long seq, byte[] ext) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + payload.length + 4);
        out.write(0x74);
        out.write(0x6B);
        out.write(3);
        out.write(T_MESSAGE);
        wrU16(out, flags);
        wrU32(out, 0); // stream_id
        wrU32(out, seq);
        wrU16(out, payload.length);
        if ((flags & F_EXT) != 0) {
            wrU16(out, ext.length);
            out.writeBytes(ext);
        }
        out.writeBytes(payload);
        byte[] head = out.toByteArray();
        long c = crc32c(head);
        wrU32(out, c);
        return out.toByteArray();
    }

    /** Single frame without extension region (CRC|FIN; payload up to 65535). */
    public static byte[] frameEncode(byte[] payload) {
        if (payload.length > 65535) {
            throw new IllegalArgumentException("payload too large for a single frame");
        }
        return frameOne(payload, F_CRC | F_FIN, 0, new byte[0]);
    }

    /** Single frame carrying an extension region (raw TLV bytes). */
    public static byte[] frameEncodeExt(byte[] payload, byte[] ext) {
        if (payload.length > 65535 || ext.length > 65535) {
            throw new IllegalArgumentException("payload or ext too large");
        }
        int flags = F_CRC | F_FIN | (ext.length > 0 ? F_EXT : 0);
        return frameOne(payload, flags, 0, ext);
    }

    /** Message layer: single frame when {@code payload.length <= 65531}, else OFFSET fragments. */
    public static byte[] msgEncode(byte[] payload) {
        if (payload.length <= CHUNK_MAX) {
            return frameOne(payload, F_CRC | F_FIN, 0, new byte[0]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int off = 0;
        long idx = 0;
        while (off < payload.length) {
            int end = Math.min(off + CHUNK_MAX, payload.length);
            int flags = F_CRC | F_OFFSET | (end == payload.length ? F_FIN : 0);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            wrU32(body, off);
            body.write(payload, off, end - off);
            out.writeBytes(frameOne(body.toByteArray(), flags, idx, new byte[0]));
            off = end;
            idx++;
        }
        return out.toByteArray();
    }

    /** One parsed frame: {flags, payloadOffset, payloadLen, next} or null. */
    private static long[] frameRead(byte[] b, int pos) {
        if (pos + 16 > b.length) {
            return null;
        }
        if (b[pos] != 0x74 || b[pos + 1] != 0x6B || b[pos + 2] != 3 || b[pos + 3] != T_MESSAGE) {
            return null;
        }
        int flags = rdU16(b, pos + 4);
        if ((flags & 0xFFC0) != 0 || (flags & F_PROTECTED) != 0) {
            return null;
        }
        int eoff = pos + 16;
        if ((flags & F_EXT) != 0) {
            if (pos + 18 > b.length) {
                return null;
            }
            int elen = rdU16(b, pos + 16);
            eoff = pos + 18 + elen;
            if (eoff > b.length) {
                return null;
            }
        }
        int ln = rdU16(b, pos + 14);
        int tlen = (flags & F_CRC) != 0 ? 4 : 0;
        if (eoff + ln + tlen > b.length) {
            return null;
        }
        if (tlen == 4) {
            long want = rdU32(b, eoff + ln);
            long got = crc32c(b, pos, (eoff - pos) + ln);
            if (want != got) {
                return null;
            }
        }
        return new long[] {flags, eoff, ln, eoff + ln + tlen};
    }

    /** Decoded logical message. */
    public static final class Decoded {
        public final byte[] payload;
        public final int next;

        Decoded(byte[] payload, int next) {
            this.payload = payload;
            this.next = next;
        }
    }

    /**
     * Decode one logical message at {@code pos}, reassembling fragments.
     * Returns null on malformed frame / CRC mismatch.
     */
    public static Decoded msgDecode(byte[] b, int pos) {
        long[] r = frameRead(b, pos);
        if (r == null) {
            return null;
        }
        int flags = (int) r[0];
        int po = (int) r[1];
        int pl = (int) r[2];
        int nx = (int) r[3];
        if ((flags & F_OFFSET) == 0) {
            return new Decoded(java.util.Arrays.copyOfRange(b, po, po + pl), nx);
        }
        if (pl < 4 || rdU32(b, po) != 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b, po + 4, pl - 4);
        int cur = nx;
        boolean fin = (flags & F_FIN) != 0;
        while (!fin) {
            long[] r2 = frameRead(b, cur);
            if (r2 == null) {
                return null;
            }
            int fl2 = (int) r2[0];
            int po2 = (int) r2[1];
            int pl2 = (int) r2[2];
            int nx2 = (int) r2[3];
            if ((fl2 & F_OFFSET) == 0 || pl2 < 4) {
                return null;
            }
            long off2 = rdU32(b, po2);
            if (off2 != out.size()) {
                return null;
            }
            out.write(b, po2 + 4, pl2 - 4);
            fin = (fl2 & F_FIN) != 0;
            cur = nx2;
        }
        return new Decoded(out.toByteArray(), cur);
    }
}
