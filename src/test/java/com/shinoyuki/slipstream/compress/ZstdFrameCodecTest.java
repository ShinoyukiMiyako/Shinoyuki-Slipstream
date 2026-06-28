package com.shinoyuki.slipstream.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envelope round-trip + bound checks + the adaptive (zstd-or-zlib) decode for {@link ZstdFrameCodec}. Exercises
 * the real zstd-jni native (via the jar on the test classpath), so a packaging or API regression fails here.
 */
class ZstdFrameCodecTest {

    private static final int THRESHOLD = 256;
    private static final int MAX_UNCOMPRESSED = 256 * 1024 * 1024;   // the default zstdMaxUncompressedMiB

    private static byte[] dec(byte[] frame, boolean validate, int max) {
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            return ZstdFrameCodec.decompress(frame, THRESHOLD, validate, max, dctx, new Inflater());
        }
    }

    private static byte[] roundTrip(byte[] payload, boolean validate) {
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            return dec(frame, validate, MAX_UNCOMPRESSED);
        }
    }

    // Build a vanilla-shaped zlib frame: VarInt(uncompressedLen) + zlib-deflated payload (mirrors CompressionEncoder).
    private static byte[] zlibFrame(byte[] payload) {
        Deflater d = new Deflater();
        d.setInput(payload);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!d.finished()) {
            bos.write(tmp, 0, d.deflate(tmp));
        }
        d.end();
        byte[] compressed = bos.toByteArray();
        byte[] varint = new byte[5];
        int n = 0;
        int v = payload.length;
        while ((v & ~0x7F) != 0) {
            varint[n++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        varint[n++] = (byte) v;
        byte[] frame = new byte[n + compressed.length];
        System.arraycopy(varint, 0, frame, 0, n);
        System.arraycopy(compressed, 0, frame, n, compressed.length);
        return frame;
    }

    @Test
    void compressedRoundTripIsIdentity() {
        Random r = new Random(42);
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i % 37) ^ r.nextInt(4));
        }
        assertArrayEquals(payload, roundTrip(payload, true));
    }

    @Test
    void chunkScalePayloadRoundTrips() {
        Random r = new Random(7);
        byte[] payload = new byte[60_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i / 16) + r.nextInt(3));
        }
        assertArrayEquals(payload, roundTrip(payload, true));
    }

    @Test
    void belowThresholdIsStoredVerbatim() {
        byte[] payload = {1, 2, 3, 4, 5};
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertEquals(payload.length + 1, frame.length);
            assertEquals(0, frame[0]);
        }
        assertArrayEquals(payload, roundTrip(payload, false));
    }

    @Test
    void emptyPayloadRoundTrips() {
        assertEquals(0, roundTrip(new byte[0], false).length);
    }

    @Test
    void largeIncompressiblePacketRoundTrips() {
        Random r = new Random(99);
        byte[] payload = new byte[4 * 1024 * 1024];
        r.nextBytes(payload);
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertTrue(frame.length > 2 * 1024 * 1024, "expected a compressed frame past the old 2 MiB cap");
            assertArrayEquals(payload, dec(frame, true, MAX_UNCOMPRESSED));
            assertArrayEquals(payload, dec(frame, false, MAX_UNCOMPRESSED));
        }
    }

    // The adaptive decoder must accept a vanilla zlib frame (an asymmetric peer that did not swap to zstd) and
    // inflate it, instead of throwing "Unknown frame descriptor". This is the resilience fix's core guarantee.
    @Test
    void zlibFrameDecodesViaFallback() {
        Random r = new Random(123);
        byte[] payload = new byte[30_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i % 29) ^ r.nextInt(3));
        }
        byte[] frame = zlibFrame(payload);
        assertTrue(ZstdFrameCodec.isZlibFallback(frame), "a zlib frame must be detected as a fallback");
        assertArrayEquals(payload, dec(frame, true, MAX_UNCOMPRESSED));
        assertArrayEquals(payload, dec(frame, false, MAX_UNCOMPRESSED));
    }

    @Test
    void zstdFrameIsNotFlaggedAsZlibFallback() {
        Random r = new Random(7);
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i % 37) ^ r.nextInt(4));
        }
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertFalse(ZstdFrameCodec.isZlibFallback(frame), "a real zstd frame must not be flagged as zlib");
        }
    }

    @Test
    void validateRejectsDeclaredSizeBelowThreshold() {
        byte[] frame = {10, 0, 0, 0};
        assertThrows(DecoderException.class, () -> dec(frame, true, MAX_UNCOMPRESSED));
    }

    @Test
    void validateRejectsDeclarationAboveConfiguredMax() {
        int max = 1024 * 1024;
        int oversize = max + 1;
        byte[] varint = new byte[5];
        int n = 0;
        int v = oversize;
        while ((v & ~0x7F) != 0) {
            varint[n++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        varint[n++] = (byte) v;
        byte[] frame = new byte[n + 4];
        System.arraycopy(varint, 0, frame, 0, n);
        assertThrows(DecoderException.class, () -> dec(frame, true, max));
    }

    @Test
    void unvalidatedFrameIgnoresTheMax() {
        byte[] payload = new byte[64_000];
        new Random(5).nextBytes(payload);
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertArrayEquals(payload, dec(frame, false, 1024));
        }
    }

    @Test
    void truncatedHeaderIsRejected() {
        byte[] frame = {(byte) 0x80, (byte) 0x80};
        assertThrows(DecoderException.class, () -> dec(frame, false, MAX_UNCOMPRESSED));
    }
}
