package com.shinoyuki.slipstream.l2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * L2 字段 delta 编解码 (战斗 regime 无损杠杆 E1)。把高频实体包的字段按 per-(entity,type) 上次值做差分, 用
 * zigzag-varint 编码: 平滑运动下"变化量的变化量"接近 0 -&gt; 1 字节, 取代原版定长 short, 聚合+zstd 之上实测再省
 * +15~21pp (见 scratchpad/entity_sim2.py)。<b>无损</b>: 还原出的字段整数与原值逐位相等 -&gt; 重建的包字节与原版
 * 逐字节一致 (这是正确性红线, 由 round-trip 单测把关)。
 *
 * <p>只碰这五种 clientbound 实体包 (字段布局 forgesrc 核实, 见 {@link L2Type})。包结构 = VarInt(packetId) +
 * VarInt(entityId) + 字段; packetId/entityId 原样透传 (不 delta), 仅字段做 delta。baseline 由调用方 (handler)
 * 按 (entityId,type) 持有: 首包 (baseline==null) 发全量, 之后发 delta; 两端处理同一 TCP 有序流 -&gt; baseline 恒等。
 *
 * <p>角度字节 (1/256 圈) 按模 256 环绕 delta; short/bool 普通 delta。所有读写大端序, 与 vanilla 序列化一致。
 */
public final class L2FieldCodec {

    public enum FieldKind {
        SHORT,   // 2 字节有符号 (move 的 Δpos×4096 / motion 的 vel×8000)
        ANGLE,   // 1 字节无符号 0-255 (1/256 圈), 环绕 delta
        BOOL     // 1 字节 0/1 (onGround)
    }

    /** 五种包的字段布局 (按 packet id 路由; entityId 之后的字段顺序, forgesrc write 方法核实)。 */
    public enum L2Type {
        MOTION(FieldKind.SHORT, FieldKind.SHORT, FieldKind.SHORT),
        MOVE_POS(FieldKind.SHORT, FieldKind.SHORT, FieldKind.SHORT, FieldKind.BOOL),
        MOVE_POSROT(FieldKind.SHORT, FieldKind.SHORT, FieldKind.SHORT, FieldKind.ANGLE, FieldKind.ANGLE, FieldKind.BOOL),
        MOVE_ROT(FieldKind.ANGLE, FieldKind.ANGLE, FieldKind.BOOL),
        ROT_HEAD(FieldKind.ANGLE);

        final FieldKind[] fields;

        L2Type(FieldKind... fields) {
            this.fields = fields;
        }

        public int fieldCount() {
            return fields.length;
        }
    }

    /**
     * 编码: in 定位在 vanilla 包起始 [VarInt packetId][VarInt entityId][字段...]; 写 [VarInt packetId][VarInt
     * entityId][zigzag(字段 delta)...] 到 out。返回本包字段值数组 (调用方存为该 (eid,type) 的新 baseline)。
     * baseline==null 时发全量 (delta=值本身, 无环绕)。
     */
    public static int[] encode(L2Type type, ByteBuf in, ByteBuf out, int[] baseline) {
        writeVarInt(out, readVarInt(in));   // packetId 透传
        writeVarInt(out, readVarInt(in));   // entityId 透传
        FieldKind[] ks = type.fields;
        int[] vals = new int[ks.length];
        for (int i = 0; i < ks.length; i++) {
            int val = readField(in, ks[i]);
            vals[i] = val;
            int d = (baseline == null) ? val : delta(ks[i], val, baseline[i]);
            writeVarInt(out, zigzag(d));
        }
        return vals;
    }

    /**
     * 解码 (encode 的逆): in 定位在 [VarInt packetId][VarInt entityId][zigzag delta...]; 重建出 vanilla 包字节
     * [VarInt packetId][VarInt entityId][字段...] 写到 out。返回重建的字段值数组 (调用方存为新 baseline)。
     */
    public static int[] decode(L2Type type, ByteBuf in, ByteBuf out, int[] baseline) {
        writeVarInt(out, readVarInt(in));   // packetId 透传
        writeVarInt(out, readVarInt(in));   // entityId 透传
        FieldKind[] ks = type.fields;
        int[] vals = new int[ks.length];
        for (int i = 0; i < ks.length; i++) {
            int d = unzigzag(readVarInt(in));
            int val = (baseline == null) ? d : reconstruct(ks[i], baseline[i], d);
            vals[i] = val;
            writeField(out, ks[i], val);
        }
        return vals;
    }

    private static int delta(FieldKind k, int val, int base) {
        if (k == FieldKind.ANGLE) {
            return ((val - base + 128) & 0xFF) - 128;   // 模 256 环绕到 [-128,127]
        }
        return val - base;                              // SHORT / BOOL 普通差
    }

    private static int reconstruct(FieldKind k, int base, int d) {
        if (k == FieldKind.ANGLE) {
            return (base + d) & 0xFF;                    // 模 256 还原回 0-255
        }
        return base + d;
    }

    private static int readField(ByteBuf in, FieldKind k) {
        switch (k) {
            case SHORT:
                return in.readShort();              // 有符号 -32768..32767
            case ANGLE:
                return in.readUnsignedByte();        // 0..255
            case BOOL:
                return in.readByte();                // 0/1
            default:
                throw new DecoderException("unknown field kind " + k);
        }
    }

    private static void writeField(ByteBuf out, FieldKind k, int val) {
        switch (k) {
            case SHORT:
                out.writeShort(val);                 // 写回低 16 位 (val 即原 short 值, 逐位一致)
                break;
            case ANGLE:
            case BOOL:
                out.writeByte(val);                  // 写回低 8 位
                break;
            default:
                throw new DecoderException("unknown field kind " + k);
        }
    }

    static int zigzag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    static int unzigzag(int z) {
        return (z >>> 1) ^ -(z & 1);
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (!buf.isReadable()) {
                throw new DecoderException("truncated VarInt in L2 frame");
            }
            byte b = buf.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 35) {
                throw new DecoderException("VarInt too long in L2 frame");
            }
        }
    }

    private L2FieldCodec() {
    }
}
