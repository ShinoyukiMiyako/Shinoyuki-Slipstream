package com.shinoyuki.slipstream.aggregate;

import com.shinoyuki.slipstream.Slipstream;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 聚合能力 channel (P1)。只用于让 Forge 登录握手双向交换其 presence: 两端都带它的连接 = 双方都开了聚合的
 * Slipstream-to-Slipstream 对。本 channel 不发任何消息, presence 即全部信号 —— 与 {@code SlipstreamNetwork}
 * (zstd) 完全同构。因为每端只在自己 aggregateEnabled 时注册, {@link SimpleChannel#isRemotePresent} 在某端为真
 * 当且仅当两端都开了聚合, 故安装决策天然对称 (任一端单独开都不会启用)。{@code acceptMissingOr} 保证 vanilla /
 * 未开聚合的对端照常连入。
 *
 * <p>安装时机不走"玩家进 PLAY 后 offer/ack 握手" —— 那个跨端往返存在窗口: 客户端先装反聚合、服务端后开始聚合,
 * 中间服务端发的未聚合原始帧会撞上已就位的反聚合器拆崩 (实测 IndexOutOfBounds)。改为两端在登录协商期 (zstd 同
 * 一钩子) 各自按 presence 独立安装 AGG/DEAGG, 由 {@link AggregateOutboundHandler} / {@link DeaggregateInboundHandler}
 * 的 PLAY 协议状态门控对齐生效边界, 无往返、无窗口。
 */
public final class AggregateNetwork {

    public static final String PROTOCOL_VERSION = "1";
    /** 单批字节上限, 攒满即提前 flush (不无界增长)。 */
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    /** pipeline handler 名 (server 出站聚合 / client 入站反聚合)。 */
    public static final String AGG_HANDLER = "slipstream_aggregate";
    public static final String DEAGG_HANDLER = "slipstream_deaggregate";

    private static volatile SimpleChannel channel;

    /** Idempotent. 仅在 aggregateEnabled 时于 common setup 注册 (每端只在自己开了聚合时注册 -> 双端 presence 即双端同意)。 */
    public static void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Slipstream.MOD_ID, "agg"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
    }

    /** True 当且仅当本端注册了 channel (本端开了聚合) 且对端也带它 (对端也开了聚合)。 */
    public static boolean remoteSupportsAggregate(Connection connection) {
        SimpleChannel ch = channel;
        return ch != null && ch.isRemotePresent(connection);
    }

    private AggregateNetwork() {
    }
}
