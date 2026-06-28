package com.shinoyuki.slipstream.compress;

/** The wire compression codec negotiated for a connection. Absence of a stored value means {@link #ZLIB}. */
public enum WireCodec {
    ZLIB,
    ZSTD
}
