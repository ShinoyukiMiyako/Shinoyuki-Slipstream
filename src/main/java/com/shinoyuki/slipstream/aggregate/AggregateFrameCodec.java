package com.shinoyuki.slipstream.aggregate;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合批的线格式: VarInt(count) 后接 count 个 (VarInt(len) + len 字节)。每个子包是一个完整序列化的出站包
 * (等同它单独发送时 splitter/decoder 会看到的字节); 反聚合端拆出来后逐个重新注入解码管线, 如同各自独立到达。
 *
 * <p>纯函数 + 全程边界检查, 可脱离 netty 管线单测; handler 在其上包一层 ByteBuf。解析任何越界/截断即抛
 * (恶意对端的批不可信), 由 handler 最外层捕获降级, 绝不静默读越界。
 */
public final class AggregateFrameCodec {

    /** 把子包负载打成一个批 blob。 */
    public static byte[] pack(List<byte[]> packets) {
        int size = varIntLen(packets.size());
        for (byte[] p : packets) {
            size += varIntLen(p.length) + p.length;
        }
        byte[] out = new byte[size];
        int i = writeVarInt(out, 0, packets.size());
        for (byte[] p : packets) {
            i = writeVarInt(out, i, p.length);
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    /** 把批 blob 拆回子包负载。越界/截断抛 IllegalArgumentException。 */
    public static List<byte[]> unpack(byte[] batch) {
        int[] cursor = {0};
        int count = readVarInt(batch, cursor);
        // 每个子包至少 1 字节 (VarInt(0) 的长度前缀), 故 count 不可能超过剩余字节数。
        if (count < 0 || count > batch.length) {
            throw new IllegalArgumentException("aggregate batch declares " + count + " sub-packets in " + batch.length + " bytes");
        }
        List<byte[]> out = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            int len = readVarInt(batch, cursor);
            if (len < 0 || cursor[0] + len > batch.length) {
                throw new IllegalArgumentException("aggregate sub-packet " + k + " length " + len + " overruns batch of " + batch.length);
            }
            byte[] p = new byte[len];
            System.arraycopy(batch, cursor[0], p, 0, len);
            cursor[0] += len;
            out.add(p);
        }
        return out;
    }

    static int varIntLen(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            n++;
        }
        return n;
    }

    static int writeVarInt(byte[] out, int pos, int value) {
        while ((value & ~0x7F) != 0) {
            out[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out[pos++] = (byte) value;
        return pos;
    }

    static int readVarInt(byte[] in, int[] cursor) {
        int value = 0;
        int shift = 0;
        int pos = cursor[0];
        while (true) {
            if (pos >= in.length) {
                throw new IllegalArgumentException("truncated VarInt in aggregate batch");
            }
            byte b = in[pos++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IllegalArgumentException("VarInt too long in aggregate batch");
            }
        }
        cursor[0] = pos;
        return value;
    }

    private AggregateFrameCodec() {
    }
}
