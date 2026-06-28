package com.shinoyuki.slipstream.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envelope round-trip + bound checks for {@link ZstdFrameCodec}. Exercises the real zstd-jni native (via the
 * jar on the test classpath), so a packaging or API regression fails here, not only on a live server.
 */
class ZstdFrameCodecTest {

    private static final int THRESHOLD = 256;
    private static final int MAX_UNCOMPRESSED = 256 * 1024 * 1024;   // the default zstdMaxUncompressedMiB

    private static byte[] roundTrip(byte[] payload, boolean validate) {
        try (ZstdCompressCtx cctx = new ZstdCompressCtx(); ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            return ZstdFrameCodec.decompress(frame, THRESHOLD, validate, MAX_UNCOMPRESSED, dctx);
        }
    }

    @Test
    void compressedRoundTripIsIdentity() {
        Random r = new Random(42);
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i % 37) ^ r.nextInt(4));   // chunk-like: repetitive but not trivial
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
    void compressedFrameIsSmallerForCompressibleData() {
        byte[] payload = new byte[16_384];   // all zeros -> highly compressible
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertTrue(frame.length < payload.length / 4,
                    "zstd frame should be far smaller than the raw payload, was " + frame.length);
        }
    }

    @Test
    void belowThresholdIsStoredVerbatim() {
        byte[] payload = {1, 2, 3, 4, 5};
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            // STORED frame = VarInt(0) + payload, i.e. one length byte (0) then the verbatim bytes.
            assertEquals(payload.length + 1, frame.length);
            assertEquals(0, frame[0]);
        }
        assertArrayEquals(payload, roundTrip(payload, false));
    }

    @Test
    void emptyPayloadRoundTrips() {
        assertEquals(0, roundTrip(new byte[0], false).length);
    }

    // A large, incompressible packet compresses to > 2 MiB -- the old hard compressed cap would have rejected
    // it (and kicked the client). With XLPackets / PacketFixer raising the framing limit such packets are real,
    // so the codec must pass them through. Both server-inbound (validate) and server-outbound (no validate).
    @Test
    void largeIncompressiblePacketRoundTrips() {
        Random r = new Random(99);
        byte[] payload = new byte[4 * 1024 * 1024];   // 4 MiB random -> zstd cannot shrink it
        r.nextBytes(payload);
        try (ZstdCompressCtx cctx = new ZstdCompressCtx(); ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertTrue(frame.length > 2 * 1024 * 1024, "expected a compressed frame past the old 2 MiB cap");
            assertArrayEquals(payload, ZstdFrameCodec.decompress(frame, THRESHOLD, true, MAX_UNCOMPRESSED, dctx));
            assertArrayEquals(payload, ZstdFrameCodec.decompress(frame, THRESHOLD, false, MAX_UNCOMPRESSED, dctx));
        }
    }

    @Test
    void validateRejectsDeclaredSizeBelowThreshold() {
        // VarInt(10) header with threshold 256, validate on -> rejected before any decompress.
        byte[] frame = {10, 0, 0, 0};
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            assertThrows(DecoderException.class,
                    () -> ZstdFrameCodec.decompress(frame, THRESHOLD, true, MAX_UNCOMPRESSED, dctx));
        }
    }

    @Test
    void validateRejectsDeclarationAboveConfiguredMax() {
        int max = 1 * 1024 * 1024;   // 1 MiB cap for this test
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
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            assertThrows(DecoderException.class,
                    () -> ZstdFrameCodec.decompress(frame, THRESHOLD, true, max, dctx));
        }
    }

    @Test
    void unvalidatedFrameIgnoresTheMax() {
        // Server-to-client (validate=false): a declared size above the cap must NOT be rejected on size alone.
        // Use a genuine round-trip so the declared size is real; a tiny cap that validate would have rejected.
        byte[] payload = new byte[64_000];
        new Random(5).nextBytes(payload);
        try (ZstdCompressCtx cctx = new ZstdCompressCtx(); ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            cctx.setLevel(3);
            byte[] frame = ZstdFrameCodec.compress(payload, THRESHOLD, cctx);
            assertArrayEquals(payload, ZstdFrameCodec.decompress(frame, THRESHOLD, false, 1024, dctx));
        }
    }

    @Test
    void truncatedHeaderIsRejected() {
        // continuation bit set on the final byte -> VarInt never terminates within the frame.
        byte[] frame = {(byte) 0x80, (byte) 0x80};
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            assertThrows(DecoderException.class,
                    () -> ZstdFrameCodec.decompress(frame, THRESHOLD, false, MAX_UNCOMPRESSED, dctx));
        }
    }
}
