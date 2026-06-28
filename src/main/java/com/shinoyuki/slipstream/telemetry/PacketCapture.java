package com.shinoyuki.slipstream.telemetry;

import com.shinoyuki.slipstream.Slipstream;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按需的出站包流抓取, 给离线算法仿真喂真实数据。激活时每个出站包记一行 JSON
 * {@code {t(微秒), d(方向), p(type), r(rawLen), w(wireLen), b(base64 原始字节)}} 到
 * logs/slipstream/capture-*.jsonl。原始字节只对 {@code <= bytesCap} 的包内嵌 (实体/Mod 小包 = 仿真目标);
 * 更大的包 (区块) 只留元数据 —— 区块字节走 region 文件, 不靠抓包。
 *
 * <p>未激活时零成本 (每包一个 volatile 读)。激活时 netty 线程只入队; 单条 <b>daemon</b> 写线程落盘
 * (daemon: 绝不能钉死关服 —— 见 BetterAutoSave 非 daemon worker 拖死关服的教训)。受时长/文件大小/
 * 满队列丢弃三重上限约束 (丢弃计数上报)。
 */
public final class PacketCapture {

    private static final Logger LOGGER = Slipstream.LOGGER;
    private static volatile PacketCapture ACTIVE;

    private static final int QUEUE_CAPACITY = 1 << 16;
    private static final int DEFAULT_BYTES_CAP = 8192;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private final Path file;
    private final int bytesCap;
    private final long startNanos = System.nanoTime();
    private final long deadlineNanos;
    private final ArrayBlockingQueue<Rec> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writer;
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicLong fileBytes = new AtomicLong();
    private volatile boolean running = true;

    private record Rec(long tMicros, char dir, String type, int rawLen, int wireLen, byte[] raw) {
    }

    private PacketCapture(Path file, int seconds, int bytesCap) {
        this.file = file;
        this.bytesCap = bytesCap;
        this.deadlineNanos = startNanos + seconds * 1_000_000_000L;
        this.writer = new Thread(this::drain, "Slipstream-Capture-Writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public static boolean active() {
        return ACTIVE != null;
    }

    /** netty 线程入口。O(1) 非阻塞; 写线程跟不上则丢弃 (计数)。raw 可能被持有到写出。 */
    public static void record(char dir, String type, int rawLen, int wireLen, byte[] rawOrNull) {
        PacketCapture c = ACTIVE;
        if (c == null) {
            return;
        }
        byte[] keep = (rawOrNull != null && rawLen <= c.bytesCap) ? rawOrNull : null;
        if (!c.queue.offer(new Rec((System.nanoTime() - c.startNanos) / 1000L, dir, type, rawLen, wireLen, keep))) {
            c.dropped.incrementAndGet();
        }
    }

    public static Path start(int seconds) {
        return start(seconds, DEFAULT_BYTES_CAP);
    }

    public static synchronized Path start(int seconds, int bytesCap) {
        if (ACTIVE != null) {
            return null;
        }
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("slipstream");
            Files.createDirectories(dir);
            Path f = dir.resolve("capture-" + System.currentTimeMillis() + ".jsonl");
            ACTIVE = new PacketCapture(f, seconds, bytesCap);
            return f;
        } catch (IOException e) {
            LOGGER.error("[Slipstream] capture start failed", e);
            return null;
        }
    }

    public static synchronized String stop() {
        PacketCapture c = ACTIVE;
        if (c == null) {
            return null;
        }
        ACTIVE = null;
        c.running = false;
        try {
            c.writer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return String.format("recorded=%d dropped=%d bytes=%d file=%s",
                c.recorded.get(), c.dropped.get(), c.fileBytes.get(), c.file);
    }

    private void drain() {
        Base64.Encoder b64 = Base64.getEncoder();
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("{\"_meta\":\"slipstream-capture\",\"start_ms\":" + System.currentTimeMillis()
                    + ",\"bytes_cap\":" + bytesCap + "}");
            w.newLine();
            StringBuilder sb = new StringBuilder(256);
            while (running || !queue.isEmpty()) {
                Rec r;
                try {
                    r = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    selfStop();
                    break;
                }
                if (r == null) {
                    if (System.nanoTime() > deadlineNanos) {
                        selfStop();
                    }
                    continue;
                }
                sb.setLength(0);
                sb.append("{\"t\":").append(r.tMicros)
                        .append(",\"d\":\"").append(r.dir)
                        .append("\",\"p\":\"").append(r.type == null ? "?" : r.type)
                        .append("\",\"r\":").append(r.rawLen)
                        .append(",\"w\":").append(r.wireLen);
                if (r.raw != null) {
                    sb.append(",\"b\":\"").append(b64.encodeToString(r.raw)).append('"');
                }
                sb.append('}');
                w.write(sb.toString());
                w.newLine();
                long fb = fileBytes.addAndGet(sb.length() + 1L);
                recorded.incrementAndGet();
                if (System.nanoTime() > deadlineNanos || fb > MAX_FILE_BYTES) {
                    selfStop();
                }
            }
            w.write("{\"_summary\":true,\"recorded\":" + recorded.get() + ",\"dropped\":" + dropped.get()
                    + ",\"file_bytes\":" + fileBytes.get() + "}");
            w.newLine();
            LOGGER.info("[Slipstream] capture written: {} (recorded={} dropped={})", file, recorded.get(), dropped.get());
        } catch (IOException e) {
            LOGGER.error("[Slipstream] capture writer failed", e);
        }
    }

    private void selfStop() {
        running = false;
        // ACTIVE 可能已被显式 stop() 清掉; 仅当仍指向自己时清, 避免误清掉随后开启的新会话。
        if (ACTIVE == this) {
            ACTIVE = null;
        }
    }
}
