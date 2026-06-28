package com.shinoyuki.slipstream.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.handler.codec.DecoderException;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * zstd framing that reproduces vanilla's compression envelope byte-for-byte (see {@code CompressionEncoder} /
 * {@code CompressionDecoder}): a leading Minecraft VarInt carries the uncompressed length, 0 meaning "stored,
 * not compressed". Only the payload codec is swapped from zlib to zstd; the discriminator is identical, so the
 * surrounding pipeline (length splitter, cipher) is untouched.
 *
 * <p><b>Adaptive decode.</b> The decoder is codec-agnostic: it sniffs the payload magic and decompresses with
 * zstd ({@code 28 B5 2F FD}) or falls back to zlib inflate otherwise. This makes the codec swap resilient to an
 * ASYMMETRIC negotiation -- if one end installed the zstd encoder and the peer did not, the peer still receives a
 * vanilla zlib frame and decodes it correctly instead of throwing "Unknown frame descriptor". Both ends install
 * this decoder whenever zstd is enabled locally, so neither direction can be left undecodable.
 *
 * <p>Size bounds, unlike vanilla's fixed 8 MiB / 2 MiB: the compressed input is already bounded by the outer
 * length framing, so no separate compressed cap is imposed here. Only a single configurable uncompressed bound is
 * checked, and only on validated (server-inbound) frames, as a decompression-bomb guard.
 */
public final class ZstdFrameCodec {

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

    /**
     * Adaptive decompress. {@code zctx} handles zstd payloads; {@code inflater} handles a zlib fallback (a peer
     * that did not swap to zstd). Reproduces vanilla's STORED / size-validate semantics for both codecs.
     */
    public static byte[] decompress(byte[] frame, int threshold, boolean validate, int maxUncompressed,
                                    ZstdDecompressCtx zctx, Inflater inflater) {
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
            if (uncompressed > maxUncompressed) {
                throw new DecoderException("Badly compressed packet - size of " + uncompressed
                        + " is larger than configured maximum of " + maxUncompressed);
            }
        }
        byte[] compressed = Arrays.copyOfRange(frame, pos, frame.length);
        if (isZstdMagic(compressed)) {
            return zctx.decompress(compressed, uncompressed);
        }
        return inflateZlib(compressed, uncompressed, inflater);
    }

    /** True iff this frame is compressed (not stored) and its payload is NOT a zstd frame -- i.e. a zlib frame
     *  from a peer that stayed on vanilla compression. Used only to log the asymmetry once per connection. */
    public static boolean isZlibFallback(byte[] frame) {
        int pos = 0;
        int uncompressed = 0;
        int shift = 0;
        byte b;
        do {
            if (pos >= frame.length || shift >= 35) {
                return false;
            }
            b = frame[pos++];
            uncompressed |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        if (uncompressed == 0) {
            return false;   // stored, codec-agnostic
        }
        return !isZstdMagic(Arrays.copyOfRange(frame, pos, frame.length));
    }

    private static boolean isZstdMagic(byte[] data) {
        return data.length >= 4
                && data[0] == (byte) 0x28 && data[1] == (byte) 0xB5
                && data[2] == (byte) 0x2F && data[3] == (byte) 0xFD;
    }

    // Mirror of vanilla CompressionDecoder's zlib path: inflate the deflated payload into exactly the declared size.
    private static byte[] inflateZlib(byte[] compressed, int uncompressed, Inflater inflater) {
        inflater.reset();
        inflater.setInput(compressed);
        byte[] out = new byte[uncompressed];
        try {
            inflater.inflate(out);
        } catch (DataFormatException e) {
            throw new DecoderException("Badly compressed packet - zlib fallback inflate failed", e);
        }
        return out;
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
