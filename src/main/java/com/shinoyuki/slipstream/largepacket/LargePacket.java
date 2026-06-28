package com.shinoyuki.slipstream.largepacket;

/**
 * Shared limits for the large-packet support mixins -- a clean built-in replacement for XLPackets / PacketFixer.
 * Each mixin raises one hardcoded vanilla ceiling to these values when {@code largePacketEnabled}, or returns the
 * original vanilla constant when disabled (so the feature is a no-op when off). All changes are always-on (not
 * gated on the zstd negotiation): normal small packets encode byte-identically to vanilla, large packets need
 * Slipstream on both ends -- the same requirement XLPackets / PacketFixer imposed, except here they also compress.
 */
public final class LargePacket {

    // ~2 GiB, matching XLPackets / PacketFixer; kept just under Integer.MAX_VALUE so a "size + header" sum in
    // vanilla bounds-check arithmetic cannot overflow.
    public static final int MAX_BYTES = 2_000_000_000;
    public static final long MAX_BYTES_LONG = 2_000_000_000L;

    // A 5-byte length varint covers a full 32-bit int length; vanilla caps the length prefix at 3 bytes (21 bits
    // = 2 MiB), which is the single ceiling that actually blocks large packets on the wire.
    public static final int FRAME_VARINT_BYTES = 5;

    // Timeouts raised so a slow large transfer is not mistaken for a dead connection.
    public static final int CLIENT_READ_TIMEOUT_SECONDS = 120;
    public static final long KEEPALIVE_WINDOW_MS = 45_000L;
    public static final int LOGIN_TIMEOUT_TICKS = 1_800;

    private LargePacket() {
    }
}
