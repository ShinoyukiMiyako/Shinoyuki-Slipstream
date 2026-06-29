package com.shinoyuki.slipstream.l2;

import com.shinoyuki.slipstream.l2.L2FieldCodec.FieldKind;
import com.shinoyuki.slipstream.l2.L2FieldCodec.L2Type;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正确性红线: L2 字段 delta 必须无损 —— vanilla 包字节 -> encode(delta) -> decode -> 逐字节还原原 vanilla 字节。
 * 编码端与解码端各自维护 (entity,type) baseline (同一序列同步演进), 验证首包全量 + 后续 delta + 角度环绕 +
 * short 边界 + 多字节 entityId + 多实体独立基线 全部字节级一致。任何字段错位/delta 不可逆都会让此测试挂掉。
 */
class L2FieldCodecTest {

    /** 构造一个 vanilla 实体包字节: [VarInt packetId][VarInt entityId][字段 (SHORT 2B / ANGLE,BOOL 1B)]。 */
    private static byte[] buildVanilla(L2Type type, int packetId, int entityId, int[] vals) {
        ByteBuf b = Unpooled.buffer();
        L2FieldCodec.writeVarInt(b, packetId);
        L2FieldCodec.writeVarInt(b, entityId);
        for (int i = 0; i < type.fields.length; i++) {
            if (type.fields[i] == FieldKind.SHORT) {
                b.writeShort(vals[i]);
            } else {
                b.writeByte(vals[i]);
            }
        }
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    /** 一次 round-trip: vanilla --encode(encBase)--> delta --decode(decBase)--> 还原, 断言逐字节等于 vanilla。返回新基线。 */
    private static int[] roundTrip(L2Type type, byte[] vanilla, int[] encBase, int[] decBase) {
        ByteBuf vin = Unpooled.wrappedBuffer(vanilla);
        ByteBuf delta = Unpooled.buffer();
        int[] newEncBase = L2FieldCodec.encode(type, vin, delta, encBase);
        vin.release();

        byte[] deltaBytes = new byte[delta.readableBytes()];
        delta.readBytes(deltaBytes);
        delta.release();

        ByteBuf din = Unpooled.wrappedBuffer(deltaBytes);
        ByteBuf rebuilt = Unpooled.buffer();
        int[] newDecBase = L2FieldCodec.decode(type, din, rebuilt, decBase);
        din.release();
        byte[] rebuiltBytes = new byte[rebuilt.readableBytes()];
        rebuilt.readBytes(rebuiltBytes);
        rebuilt.release();

        assertArrayEquals(vanilla, rebuiltBytes, type + ": 还原字节必须与 vanilla 逐字节一致");
        assertArrayEquals(newEncBase, newDecBase, type + ": 两端基线演进必须一致");
        return newEncBase;
    }

    private static int randField(Random r, FieldKind k) {
        switch (k) {
            case SHORT:
                return (short) r.nextInt(65536);   // 覆盖 -32768..32767 全程
            case ANGLE:
                return r.nextInt(256);             // 0..255
            case BOOL:
                return r.nextInt(2);               // 0/1
            default:
                throw new IllegalStateException();
        }
    }

    @Test
    void eachTypeRoundTripsRandomSequence() {
        Random r = new Random(20260630L);
        for (L2Type type : L2Type.values()) {
            int packetId = 40 + type.ordinal();
            int entityId = 1 + r.nextInt(200000);   // 可能多字节 VarInt
            int[] encBase = null, decBase = null;
            for (int n = 0; n < 200; n++) {
                int[] vals = new int[type.fields.length];
                for (int i = 0; i < vals.length; i++) {
                    vals[i] = randField(r, type.fields[i]);
                }
                byte[] vanilla = buildVanilla(type, packetId, entityId, vals);
                int[] next = roundTrip(type, vanilla, encBase, decBase);
                encBase = next;
                decBase = next;   // 两端同步演进 (实测同一 TCP 有序流的等价)
            }
        }
    }

    @Test
    void firstPacketIsFullValueThenDelta() {
        L2Type type = L2Type.MOVE_POS;
        byte[] p1 = buildVanilla(type, 45, 12345, new int[]{1000, -2000, 30000, 1});
        int[] base = roundTrip(type, p1, null, null);                      // 首包全量
        byte[] p2 = buildVanilla(type, 45, 12345, new int[]{1005, -1990, 30000, 0});
        roundTrip(type, p2, base, base);                                   // 第二包 delta
    }

    @Test
    void angleWrapsAroundLosslessly() {
        L2Type type = L2Type.ROT_HEAD;
        int[] seq = {250, 5, 200, 0, 255, 128, 127};   // 跨 255->0 环绕
        int[] base = null;
        for (int a : seq) {
            byte[] v = buildVanilla(type, 52, 777, new int[]{a});
            base = roundTrip(type, v, base, base);
        }
    }

    @Test
    void shortBoundariesLossless() {
        L2Type type = L2Type.MOTION;
        int[][] seq = {{-32768, 32767, 0}, {32767, -32768, 1}, {-1, 1, -32768}, {0, 0, 0}};
        int[] base = null;
        for (int[] vals : seq) {
            byte[] v = buildVanilla(type, 40, 99999, vals);   // 大 entityId -> 3 字节 VarInt
            base = roundTrip(type, v, base, base);
        }
    }

    @Test
    void multipleEntitiesIndependentBaselines() {
        // 模拟 handler 按 (eid,type) 持有独立基线: 两个实体交错发包, 各自基线独立, 互不串。
        L2Type type = L2Type.MOVE_POSROT;
        Random r = new Random(7);
        Map<Integer, int[]> encBases = new HashMap<>();
        Map<Integer, int[]> decBases = new HashMap<>();
        int[] eids = {100, 200, 300};
        for (int n = 0; n < 150; n++) {
            int eid = eids[r.nextInt(eids.length)];
            int[] vals = new int[type.fields.length];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = randField(r, type.fields[i]);
            }
            byte[] vanilla = buildVanilla(type, 46, eid, vals);

            ByteBuf vin = Unpooled.wrappedBuffer(vanilla);
            ByteBuf delta = Unpooled.buffer();
            int[] nb = L2FieldCodec.encode(type, vin, delta, encBases.get(eid));
            vin.release();
            encBases.put(eid, nb);
            byte[] db = new byte[delta.readableBytes()];
            delta.readBytes(db);
            delta.release();

            ByteBuf din = Unpooled.wrappedBuffer(db);
            ByteBuf rebuilt = Unpooled.buffer();
            int[] ndb = L2FieldCodec.decode(type, din, rebuilt, decBases.get(eid));
            din.release();
            decBases.put(eid, ndb);
            byte[] rb = new byte[rebuilt.readableBytes()];
            rebuilt.readBytes(rb);
            rebuilt.release();

            assertArrayEquals(vanilla, rb, "实体 " + eid + " 第 " + n + " 包必须无损还原");
        }
        assertTrue(encBases.size() == 3, "三个实体各有独立基线");
    }
}
