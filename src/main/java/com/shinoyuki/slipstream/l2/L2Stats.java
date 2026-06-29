package com.shinoyuki.slipstream.l2;

import java.util.concurrent.atomic.AtomicLong;

/**
 * L2 字段 delta 自度量。{@link L2EncodeHandler} 每重编码一个实体包就记 (vanilla 包字节, L2 帧字节)。
 * 抓包 tap 在压缩层之下、且 L2 又在聚合之上 —— 压后/批后都看不出 L2 的字段级省流, 故由编码器自报; /slipstream
 * report 直接给出 L2 在"压缩/聚合之前"把实体包字段压了多少 (与 {@code AggregateStats} 同理由)。进程级累加。
 */
public final class L2Stats {

    private static final AtomicLong packetsEncoded = new AtomicLong();
    private static final AtomicLong rawBytes = new AtomicLong();
    private static final AtomicLong encodedBytes = new AtomicLong();

    public static void record(int rawLen, int encodedLen) {
        packetsEncoded.incrementAndGet();
        rawBytes.addAndGet(rawLen);
        encodedBytes.addAndGet(encodedLen);
    }

    public static long packetsEncoded() {
        return packetsEncoded.get();
    }

    public static long rawBytes() {
        return rawBytes.get();
    }

    public static long encodedBytes() {
        return encodedBytes.get();
    }

    private L2Stats() {
    }
}
