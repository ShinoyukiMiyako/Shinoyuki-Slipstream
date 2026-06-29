package com.shinoyuki.slipstream.l2;

import com.shinoyuki.slipstream.l2.L2FieldCodec.FieldKind;
import com.shinoyuki.slipstream.l2.L2FieldCodec.L2Type;
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
 * 用 EmbeddedChannel 验证 L2EncodeHandler (服务端出站 delta 编码) -&gt; L2DecodeHandler (客户端入站还原) 双端往返:
 * PLAY 期实体包经 delta 编/解必须逐字节还原 vanilla; 非实体包 (RAW) 与登录期 (gate=false) 必须整体透传不变。
 * 覆盖多实体混合序列 (per-(eid,type) 独立 baseline) + RAW 透传 + 登录期透传。codec 本身另有 L2FieldCodecTest。
 */
class L2HandlerTest {

    private static byte[] vanilla(L2Type type, int packetId, int entityId, int[] vals) {
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

    private static byte[] drain(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    private static int randField(Random r, FieldKind k) {
        switch (k) {
            case SHORT: return (short) r.nextInt(65536);
            case ANGLE: return r.nextInt(256);
            default: return r.nextInt(2);
        }
    }

    @Test
    void mixedEntitySequenceRoundTripsThroughHandlers() {
        EmbeddedChannel server = new EmbeddedChannel(new L2EncodeHandler(() -> true));
        EmbeddedChannel client = new EmbeddedChannel(new L2DecodeHandler(() -> true));
        Random r = new Random(20260630L);
        L2Type[] types = L2Type.values();
        int[] eids = {7, 4242, 1000000};   // 含多字节 VarInt 的 entityId
        List<byte[]> sent = new ArrayList<>();

        for (int n = 0; n < 400; n++) {
            L2Type type = types[r.nextInt(types.length)];
            int eid = eids[r.nextInt(eids.length)];
            int packetId = 44 + type.ordinal();
            int[] vals = new int[type.fields.length];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = randField(r, type.fields[i]);
            }
            byte[] van = vanilla(type, packetId, eid, vals);
            sent.add(van);
            server.attr(L2EncodeHandler.L2_TYPE).set(type);     // 模拟 encoder 钩子标类型
            server.writeOutbound(Unpooled.wrappedBuffer(van.clone()));
        }

        int i = 0;
        ByteBuf frame;
        while ((frame = server.readOutbound()) != null) {
            client.writeInbound(frame);
            ByteBuf rebuilt = client.readInbound();
            assertArrayEquals(sent.get(i), drain(rebuilt), "第 " + i + " 包必须逐字节还原 vanilla");
            i++;
        }
        assertEquals(sent.size(), i, "收发包数必须一致");
        server.finish();
        client.finish();
    }

    @Test
    void nonEntityPacketPassesThroughAsRaw() {
        // attr 未标类型 -> RAW: 打 TAG=0, 客户端剥 TAG 还原原字节。
        EmbeddedChannel server = new EmbeddedChannel(new L2EncodeHandler(() -> true));
        EmbeddedChannel client = new EmbeddedChannel(new L2DecodeHandler(() -> true));
        byte[] sound = {(byte) 0x62, 1, 2, 3, 4, 5, 6, 7};   // 任意非实体包字节 (含 0x62 packetId)
        server.writeOutbound(Unpooled.wrappedBuffer(sound.clone()));
        ByteBuf frame = server.readOutbound();
        assertEquals(0, frame.getByte(0) & 0xFF, "RAW 帧首字节应为 TAG=0");
        client.writeInbound(frame);
        assertArrayEquals(sound, drain(client.readInbound()), "RAW 包必须原样还原");
        server.finish();
        client.finish();
    }

    @Test
    void loginStatePassesThroughUntouched() {
        // gate=false (登录期): 两端都不打/不读 TAG, 整帧透传。
        EmbeddedChannel server = new EmbeddedChannel(new L2EncodeHandler(() -> false));
        EmbeddedChannel client = new EmbeddedChannel(new L2DecodeHandler(() -> false));
        byte[] loginPkt = {2, 9, 9, 9, 0, 1};
        server.attr(L2EncodeHandler.L2_TYPE).set(L2Type.MOVE_POS);   // 即便标了类型, gate=false 也必须透传
        server.writeOutbound(Unpooled.wrappedBuffer(loginPkt.clone()));
        ByteBuf frame = server.readOutbound();
        client.writeInbound(frame);
        assertArrayEquals(loginPkt, drain(client.readInbound()), "登录期帧必须原样透传不打 TAG");
        server.finish();
        client.finish();
    }
}
