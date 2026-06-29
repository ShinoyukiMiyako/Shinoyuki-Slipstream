package com.shinoyuki.slipstream.aggregate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 EmbeddedChannel 验证 AggregateOutboundHandler (攒批) -> DeaggregateInboundHandler (拆批) 的双端往返与
 * PLAY 状态门控。PLAY 期 (gate=true): 出站包攒批压扁后, 客户端拆回的子包必须与原始逐字节一致、顺序不变, 跨多批
 * 分片亦无损。登录期 (gate=false): 两端均透传, 反聚合器绝不把未聚合的原始帧当批拆 —— 这是早期握手窗口竞态崩溃
 * (DEAGG 撞登录期原始帧 -> IndexOutOfBounds) 的回归防线。codec 格式本身另有 AggregateFrameCodecTest。
 */
class AggregateHandlerTest {

    private static final BooleanSupplier PLAY = () -> true;
    private static final BooleanSupplier LOGIN = () -> false;

    private static List<byte[]> roundTrip(List<byte[]> packets, int maxBatchBytes,
                                          BooleanSupplier serverGate, BooleanSupplier clientGate) {
        EmbeddedChannel server = new EmbeddedChannel(new AggregateOutboundHandler(100_000, maxBatchBytes, serverGate));
        for (byte[] p : packets) {
            server.write(Unpooled.wrappedBuffer(p.clone()));      // PLAY: 缓冲攒批; LOGIN: 透传到 ctx.write
        }
        server.flush();                                           // 模拟 Connection.send 的 flush: PLAY 期被吞, LOGIN 期透传放行
        server.pipeline().remove(AggregateOutboundHandler.class); // handlerRemoved -> PLAY 期排空成批

        EmbeddedChannel client = new EmbeddedChannel(new DeaggregateInboundHandler(clientGate));
        ByteBuf frame;
        while ((frame = server.readOutbound()) != null) {
            client.writeInbound(frame);                           // deagg 拆批 / 透传 -> 子包入站
        }

        List<byte[]> got = new ArrayList<>();
        ByteBuf sub;
        while ((sub = client.readInbound()) != null) {
            byte[] b = new byte[sub.readableBytes()];
            sub.readBytes(b);
            got.add(b);
            sub.release();
        }
        server.finish();
        client.finish();
        return got;
    }

    private static void assertRoundTrip(List<byte[]> packets, int maxBatchBytes,
                                        BooleanSupplier serverGate, BooleanSupplier clientGate) {
        List<byte[]> got = roundTrip(packets, maxBatchBytes, serverGate, clientGate);
        assertEquals(packets.size(), got.size(), "子包数必须一致");
        for (int i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i), got.get(i), "子包 " + i + " 必须逐字节无损且不乱序");
        }
    }

    @Test
    void playManyTinyEntityPacketsRoundTripInOneBatch() {
        Random r = new Random(7);
        List<byte[]> pkts = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            byte[] p = new byte[4 + r.nextInt(9)];
            r.nextBytes(p);
            pkts.add(p);
        }
        assertRoundTrip(pkts, 1 << 20, PLAY, PLAY);   // 大上限 -> 一个 BATCH 帧 (handlerRemoved 排空)
    }

    @Test
    void playSinglePacketUsesRawTagAndRoundTrips() {
        byte[] one = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        // 窗内单包 -> RAW 标记帧 (首字节 TAG_RAW), 不带 count/len。
        EmbeddedChannel server = new EmbeddedChannel(new AggregateOutboundHandler(100_000, 1 << 20, PLAY));
        server.write(Unpooled.wrappedBuffer(one.clone()));
        server.pipeline().remove(AggregateOutboundHandler.class);
        ByteBuf frame = server.readOutbound();
        assertEquals(AggregateFrameCodec.TAG_RAW, frame.getByte(0) & 0xFF, "单包帧首字节应为 TAG_RAW");
        EmbeddedChannel client = new EmbeddedChannel(new DeaggregateInboundHandler(PLAY));
        client.writeInbound(frame);
        ByteBuf sub = client.readInbound();
        byte[] got = new byte[sub.readableBytes()];
        sub.readBytes(got);
        sub.release();
        assertArrayEquals(one, got, "RAW 帧拆回必须等于原包");
        server.finish();
        client.finish();
    }

    @Test
    void playMultiPacketUsesBatchTag() {
        EmbeddedChannel server = new EmbeddedChannel(new AggregateOutboundHandler(100_000, 1 << 20, PLAY));
        server.write(Unpooled.wrappedBuffer(new byte[]{10, 11}));
        server.write(Unpooled.wrappedBuffer(new byte[]{20, 21, 22}));
        server.pipeline().remove(AggregateOutboundHandler.class);
        ByteBuf frame = server.readOutbound();
        assertEquals(AggregateFrameCodec.TAG_BATCH, frame.getByte(0) & 0xFF, "多包帧首字节应为 TAG_BATCH");
        frame.release();
        server.finish();
    }

    @Test
    void playSizeCapSplitsAcrossBatchesStillLossless() {
        Random r = new Random(3);
        List<byte[]> pkts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            byte[] p = new byte[20];
            r.nextBytes(p);
            pkts.add(p);
        }
        assertRoundTrip(pkts, 64, PLAY, PLAY);         // 小上限 -> 每攒满 64 字节就提前 flush, 多批; 仍须无损保序
    }

    @Test
    void loginWindowFramesPassThroughUntouched() {
        // 回归: 登录期 (gate=false) 两端均透传, 原始帧逐字节不变, 反聚合器绝不当批拆。
        Random r = new Random(11);
        List<byte[]> pkts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            byte[] p = new byte[1 + r.nextInt(40)];
            r.nextBytes(p);
            pkts.add(p);
        }
        assertRoundTrip(pkts, 1 << 20, LOGIN, LOGIN);
    }

    @Test
    void deaggregatorInLoginStateNeverParsesRawFrameAsBatch() {
        // 锋利回归: 构造一个"未聚合的原始包"帧, 其首字节若被当 count/len 解析会读越界 (旧握手窗口竞态崩点)。
        // 登录态 (gate=false) DEAGG 必须原样透传该帧, 不读标记不拆批, 不抛异常。
        byte[] rawPacket = {(byte) 0x7F, 1, 2, 3};   // 0x7F=127: 若当作 count 解析会声称 127 个子包 -> 越界
        EmbeddedChannel client = new EmbeddedChannel(new DeaggregateInboundHandler(LOGIN));
        client.writeInbound(Unpooled.wrappedBuffer(rawPacket.clone()));
        client.checkException();                       // 不应有异常被记录
        ByteBuf out = client.readInbound();
        byte[] got = new byte[out.readableBytes()];
        out.readBytes(got);
        out.release();
        assertArrayEquals(rawPacket, got, "登录态原始帧必须原样透传");
        assertTrue(client.inboundMessages().isEmpty(), "只应有一个透传帧");
        client.finish();
    }

    @Test
    void playUnknownTagThrowsCleanDecoderException() {
        // PLAY 态收到未知标记 (既非 RAW 也非 BATCH): 干净抛 DecoderException, 不静默读越界/不崩坏。
        byte[] badFrame = {(byte) 99, 1, 2, 3};
        EmbeddedChannel client = new EmbeddedChannel(new DeaggregateInboundHandler(PLAY));
        assertThrows(DecoderException.class, () -> {
            client.writeInbound(Unpooled.wrappedBuffer(badFrame));
            client.checkException();
        });
    }
}
