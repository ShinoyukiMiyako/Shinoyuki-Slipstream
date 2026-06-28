package com.shinoyuki.slipstream.aggregate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用 EmbeddedChannel 验证 AggregateOutboundHandler (攒批) -> DeaggregateInboundHandler (拆批) 的双端往返:
 * 出站包攒批压扁后, 客户端拆回的子包必须与原始逐字节一致、且顺序不变, 跨多批分片也无损。覆盖 handler 的
 * buffer/flush/promise/refcount 路径 (codec 格式本身另有 AggregateFrameCodecTest)。
 */
class AggregateHandlerTest {

    private static List<byte[]> roundTrip(List<byte[]> packets, int maxBatchBytes) {
        EmbeddedChannel server = new EmbeddedChannel(new AggregateOutboundHandler(100_000, maxBatchBytes));
        for (byte[] p : packets) {
            server.write(Unpooled.wrappedBuffer(p.clone()));      // 仅缓冲, 不 flush
        }
        server.pipeline().remove(AggregateOutboundHandler.class); // handlerRemoved -> 排空成批

        EmbeddedChannel client = new EmbeddedChannel(new DeaggregateInboundHandler());
        ByteBuf batch;
        while ((batch = server.readOutbound()) != null) {
            client.writeInbound(batch);                           // deagg 拆批 -> 子包入站
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

    private static void assertRoundTrip(List<byte[]> packets, int maxBatchBytes) {
        List<byte[]> got = roundTrip(packets, maxBatchBytes);
        assertEquals(packets.size(), got.size(), "子包数必须一致");
        for (int i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i), got.get(i), "子包 " + i + " 必须逐字节无损且不乱序");
        }
    }

    @Test
    void manyTinyEntityPacketsRoundTripInOneBatch() {
        Random r = new Random(7);
        List<byte[]> pkts = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            byte[] p = new byte[4 + r.nextInt(9)];
            r.nextBytes(p);
            pkts.add(p);
        }
        assertRoundTrip(pkts, 1 << 20);   // 大上限 -> 一个批 (handlerRemoved 排空)
    }

    @Test
    void singlePacketRoundTrips() {
        assertRoundTrip(List.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}), 1 << 20);
    }

    @Test
    void sizeCapSplitsAcrossBatchesStillLossless() {
        Random r = new Random(3);
        List<byte[]> pkts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            byte[] p = new byte[20];
            r.nextBytes(p);
            pkts.add(p);
        }
        assertRoundTrip(pkts, 64);         // 小上限 -> 每攒满 64 字节就提前 flush, 多批; 仍须无损保序
    }
}
