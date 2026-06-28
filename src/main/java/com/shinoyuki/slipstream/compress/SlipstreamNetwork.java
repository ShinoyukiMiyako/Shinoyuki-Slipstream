package com.shinoyuki.slipstream.compress;

import com.shinoyuki.slipstream.Slipstream;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * P2 capability channel. Registered only when zstd is enabled, and only so the Forge login handshake exchanges
 * its presence both ways: a connection where both ends carry it is a Slipstream-to-Slipstream pair that has
 * opted into zstd. No messages are ever sent on it -- presence is the whole signal. Because each end registers
 * the channel only when its own config enables zstd, {@link SimpleChannel#isRemotePresent} is true on a given
 * end iff BOTH ends enabled it, which keeps the swap decision symmetric (no end can flip to zstd alone).
 * {@code acceptMissingOr} keeps vanilla / non-Slipstream peers connectable.
 */
public final class SlipstreamNetwork {

    public static final String PROTOCOL_VERSION = "1";

    private static volatile SimpleChannel channel;

    /** Idempotent. Call on the main thread during setup, only when zstd is enabled. */
    public static void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Slipstream.MOD_ID, "codec"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
    }

    /** True only when this end registered the channel (zstd enabled here) AND the remote end also carries it. */
    public static boolean remoteSupportsZstd(Connection connection) {
        SimpleChannel ch = channel;
        return ch != null && ch.isRemotePresent(connection);
    }

    private SlipstreamNetwork() {
    }
}
