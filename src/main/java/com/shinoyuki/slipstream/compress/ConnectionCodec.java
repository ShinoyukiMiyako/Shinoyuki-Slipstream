package com.shinoyuki.slipstream.compress;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;

/**
 * Per-connection negotiated wire codec, stored on the netty channel (mirrors {@code PacketTelemetry.STATS_KEY}).
 * Set once during login by the negotiation mixins; read by the setupCompression swap and the broadcast share.
 * A missing attribute is treated as {@link WireCodec#ZLIB} so every un-negotiated connection stays vanilla.
 */
public final class ConnectionCodec {

    public static final AttributeKey<WireCodec> KEY = AttributeKey.valueOf("slipstream:wire_codec");

    public static void set(Channel channel, WireCodec codec) {
        if (channel != null) {
            channel.attr(KEY).set(codec);
        }
    }

    public static WireCodec of(Channel channel) {
        if (channel == null) {
            return WireCodec.ZLIB;
        }
        WireCodec codec = channel.attr(KEY).get();
        return codec != null ? codec : WireCodec.ZLIB;
    }

    public static WireCodec of(Connection connection) {
        return of(connection.channel());
    }

    private ConnectionCodec() {
    }
}
