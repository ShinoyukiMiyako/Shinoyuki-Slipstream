package com.shinoyuki.slipstream.l2;

import com.shinoyuki.slipstream.Slipstream;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * L2 字段 delta 能力 channel (P1 战斗 regime)。只用于让 Forge 登录握手双向交换 presence: 两端都带它 = 双方都开
 * 了 L2 的 Slipstream-to-Slipstream 对。与 {@code AggregateNetwork} / {@code SlipstreamNetwork} 完全同构, 不发消息,
 * presence 即信号。**独立于聚合 channel** —— L2 与聚合可各自单独开关 (复用聚合 presence 会把两能力绑死)。
 *
 * <p>安装走登录协商期对称安装 + PLAY 协议态门控 (见 {@link L2EncodeHandler} / {@link L2DecodeHandler}), 不走
 * PLAY 后 offer/ack 往返。每端只在自己 l2Enabled 时注册 -&gt; isRemotePresent 双端对称。
 */
public final class L2Network {

    public static final String PROTOCOL_VERSION = "1";
    /** pipeline handler 名 (server 出站编码 / client 入站解码)。 */
    public static final String ENCODE_HANDLER = "slipstream_l2_encode";
    public static final String DECODE_HANDLER = "slipstream_l2_decode";

    private static volatile SimpleChannel channel;

    /** Idempotent. 仅在 l2Enabled 时于 common setup 注册。 */
    public static void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Slipstream.MOD_ID, "l2"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
    }

    /** True 当且仅当本端注册了 channel (本端开了 L2) 且对端也带它 (对端也开了 L2)。 */
    public static boolean remoteSupportsL2(Connection connection) {
        SimpleChannel ch = channel;
        return ch != null && ch.isRemotePresent(connection);
    }

    private L2Network() {
    }
}
