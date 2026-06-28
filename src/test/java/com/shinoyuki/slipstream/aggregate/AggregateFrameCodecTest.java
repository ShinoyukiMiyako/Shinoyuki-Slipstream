package com.shinoyuki.slipstream.aggregate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pack/unpack round-trip + 边界 + 恶意批拒绝, 验证聚合帧编解码无损且不读越界。 */
class AggregateFrameCodecTest {

    private static void assertRoundTrip(List<byte[]> packets) {
        byte[] batch = AggregateFrameCodec.pack(packets);
        List<byte[]> back = AggregateFrameCodec.unpack(batch);
        assertEquals(packets.size(), back.size(), "子包数必须一致");
        for (int i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i), back.get(i), "子包 " + i + " 必须逐字节无损");
        }
    }

    @Test
    void roundTripMixedSizes() {
        List<byte[]> pkts = new ArrayList<>();
        pkts.add(new byte[]{1, 2, 3, 4});                 // 典型 9 字节级实体包
        pkts.add(new byte[0]);                            // 零长子包
        pkts.add(new byte[]{(byte) 0xFF});                // 单字节
        byte[] big = new byte[5000];                      // 长度需 2 字节 VarInt
        new Random(1).nextBytes(big);
        pkts.add(big);
        assertRoundTrip(pkts);
    }

    @Test
    void roundTripEmptyBatch() {
        List<byte[]> empty = new ArrayList<>();
        byte[] batch = AggregateFrameCodec.pack(empty);
        assertEquals(1, batch.length, "空批 = 单字节 VarInt(0)");
        assertTrue(AggregateFrameCodec.unpack(batch).isEmpty());
    }

    @Test
    void roundTripManyTinyEntityPackets() {
        Random r = new Random(42);
        List<byte[]> pkts = new ArrayList<>();
        for (int i = 0; i < 800; i++) {                   // 模拟一个 20ms 窗的碎实体包批
            byte[] p = new byte[4 + r.nextInt(9)];
            r.nextBytes(p);
            pkts.add(p);
        }
        assertRoundTrip(pkts);
    }

    @Test
    void packSizeIsCountPlusFramedPayloads() {
        List<byte[]> pkts = new ArrayList<>();
        pkts.add(new byte[10]);
        pkts.add(new byte[200]);                          // 200 -> 2 字节长度前缀
        byte[] batch = AggregateFrameCodec.pack(pkts);
        // VarInt(2)=1 + [VarInt(10)=1 + 10] + [VarInt(200)=2 + 200] = 214
        assertEquals(1 + 1 + 10 + 2 + 200, batch.length);
    }

    @Test
    void truncatedBatchIsRejected() {
        byte[] full = AggregateFrameCodec.pack(List.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        byte[] cut = new byte[full.length - 3];           // 砍掉尾部, 子包长度越界
        System.arraycopy(full, 0, cut, 0, cut.length);
        assertThrows(IllegalArgumentException.class, () -> AggregateFrameCodec.unpack(cut));
    }

    @Test
    void lengthOverrunIsRejected() {
        // VarInt(1) count=1, 子包声明 len=100 但只有 2 字节实体 -> 必拒
        byte[] evil = {1, 100, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> AggregateFrameCodec.unpack(evil));
    }

    @Test
    void truncatedVarIntIsRejected() {
        byte[] evil = {(byte) 0x80, (byte) 0x80};         // VarInt 永不终止
        assertThrows(IllegalArgumentException.class, () -> AggregateFrameCodec.unpack(evil));
    }
}
