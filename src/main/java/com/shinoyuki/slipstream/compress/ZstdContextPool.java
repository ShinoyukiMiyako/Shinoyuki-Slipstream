package com.shinoyuki.slipstream.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import java.util.zip.Inflater;

/**
 * One zstd context per thread. {@code ZSTD_CCtx}/{@code ZSTD_DCtx} are not thread-safe; netty pins each channel
 * to a fixed event-loop thread, so a {@link ThreadLocal} yields one reused context per event loop, used serially.
 * The contexts are intentionally never closed: event-loop threads live for the JVM lifetime, so the native
 * handles are bounded by the (small, fixed) event-loop pool rather than leaked per connection. A reused zlib
 * {@link Inflater} is kept alongside for the adaptive decoder's zlib fallback (a peer that did not swap to zstd).
 */
public final class ZstdContextPool {

    private static final ThreadLocal<ZstdCompressCtx> COMPRESSOR = ThreadLocal.withInitial(ZstdCompressCtx::new);
    private static final ThreadLocal<ZstdDecompressCtx> DECOMPRESSOR = ThreadLocal.withInitial(ZstdDecompressCtx::new);
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);

    public static ZstdCompressCtx compressor(int level) {
        ZstdCompressCtx ctx = COMPRESSOR.get();
        ctx.setLevel(level);
        return ctx;
    }

    public static ZstdDecompressCtx decompressor() {
        return DECOMPRESSOR.get();
    }

    public static Inflater inflater() {
        return INFLATER.get();
    }

    private ZstdContextPool() {
    }
}
