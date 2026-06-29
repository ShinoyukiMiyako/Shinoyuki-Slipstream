package com.shinoyuki.slipstream.aggregate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 聚合器自度量 (P1 效果测量)。{@link AggregateOutboundHandler} flush 每个批时上报: 攒了几个包、入站负载字节
 * (各包原文之和, 即不聚合时逐包要发的负载)、出站成帧字节 (加了 tag/count/len 前缀, 压缩前)。
 *
 * <p>抓包 tap 在压缩层 (ZstdEncoder) 之下, 看到的是压扁后的批、数不出批里的包数; 故聚合的咬合效果只有聚合器
 * 自己最清楚 —— 这些计数器让 {@code /slipstream report} 直接给出"包->批咬合比 + 成帧开销", 配合 report 已有的
 * 总 wire(压缩后批字节) 即可离线算真实省流, 无需 aggregate on/off 的 A/B。全局累加 (自服务端启动起), 进程级。
 */
public final class AggregateStats {

    private static final AtomicLong packetsIn = new AtomicLong();
    private static final AtomicLong batchesOut = new AtomicLong();
    private static final AtomicLong payloadBytesIn = new AtomicLong();
    private static final AtomicLong framedBytesOut = new AtomicLong();

    public static void recordBatch(int nPackets, long payloadBytes, long framedBytes) {
        packetsIn.addAndGet(nPackets);
        batchesOut.incrementAndGet();
        payloadBytesIn.addAndGet(payloadBytes);
        framedBytesOut.addAndGet(framedBytes);
    }

    public static long packetsIn() {
        return packetsIn.get();
    }

    public static long batchesOut() {
        return batchesOut.get();
    }

    public static long payloadBytesIn() {
        return payloadBytesIn.get();
    }

    public static long framedBytesOut() {
        return framedBytesOut.get();
    }

    private AggregateStats() {
    }
}
