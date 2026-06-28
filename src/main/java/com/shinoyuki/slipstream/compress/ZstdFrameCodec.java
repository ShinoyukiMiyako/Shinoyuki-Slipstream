package com.shinoyuki.slipstream.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.handler.codec.DecoderException;

import java.util.Arrays;

/**
 * zstd framing that reproduces vanilla's compression envelope byte-for-byte (see {@code CompressionEncoder} /
 * {@code CompressionDecoder}): a leading Minecraft VarInt carries the uncompressed length, 0 meaning "stored,
 * not compressed". Only the payload codec is swapped from zlib to zstd; the discriminator and the DoS bounds
 * are identical, so the surrounding pipeline (length splitter, cipher) is untouched. Pure byte[] logic with no
 * netty/Minecraft coupling beyond {@link DecoderException}, so it unit-tests against zstd-jni alone.
 */
public final class ZstdFrameCodec {

    // Mirror CompressionDecoder.MAXIMUM_*; a hostile peer must not be able to declare an unbounded size.
    public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8388608;   // 8 MiB
    public static final int MAXIMUM_COMPRESSED_LENGTH = 2097152;     // 2 MiB

    public static byte[] compress(byte[] payload, int threshold, ZstdCompressCtx ctx) {
        if (payload.length < threshold) {
            byte[] out = new byte[varIntSize(0) + payload.length];
            int pos = writeVarInt(out, 0, 0);
            System.arraycopy(payload, 0, out, pos, payload.length);
            return out;
        }
        byte[] compressed = ctx.compress(payload);
        byte[] out = new byte[varIntSize(payload.length) + compressed.length];
        int pos = writeVarInt(out, 0, payload.length);
        System.arraycopy(compressed, 0, out, pos, compressed.length);
        return out;
    }

    public static byte[] decompress(byte[] frame, int threshold, boolean validate, ZstdDecompressCtx ctx) {
        int pos = 0;
        int uncompressed = 0;
        int shift = 0;
        byte b;
        do {
            if (pos >= frame.length || shift >= 35) {
                throw new DecoderException("Badly compressed packet - malformed length header");
            }
            b = frame[pos++];
            uncompressed |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        if (uncompressed == 0) {
            return Arrays.copyOfRange(frame, pos, frame.length);
        }
        if (validate) {
            if (uncompressed < threshold) {
                throw new DecoderException("Badly compressed packet - size of " + uncompressed
                        + " is below server threshold of " + threshold);
            }
            if (uncompressed > MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw new DecoderException("Badly compressed packet - size of " + uncompressed
                        + " is larger than protocol maximum of " + MAXIMUM_UNCOMPRESSED_LENGTH);
            }
        }
        int compressedLen = frame.length - pos;
        if (compressedLen > MAXIMUM_COMPRESSED_LENGTH) {
            throw new DecoderException("Badly compressed packet - compressed size of " + compressedLen
                    + " is larger than protocol maximum of " + MAXIMUM_COMPRESSED_LENGTH);
        }
        byte[] compressed = Arrays.copyOfRange(frame, pos, frame.length);
        return ctx.decompress(compressed, uncompressed);
    }

    // Minecraft VarInt (LEB128: 7 data bits + continuation bit), max 5 bytes for an int.
    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int writeVarInt(byte[] dst, int offset, int value) {
        while ((value & ~0x7F) != 0) {
            dst[offset++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dst[offset++] = (byte) value;
        return offset;
    }

    private ZstdFrameCodec() {
    }
}
