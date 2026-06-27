# Slipstream

Network packet telemetry and optimization for Minecraft 1.20.1 Forge.

Positioning mirrors BetterAutoSave: best performance under best compatibility, with performance taking slight priority over compatibility. Slipstream targets the network layer the way BetterAutoSave targets save/load IO.

## Phase 0 — telemetry (current)

A server-side, wire-compatible packet profiler. No protocol changes, no client mod required (declared server-optional via `IGNORESERVERONLY`, so vanilla and unmodded clients still connect).

It taps the existing netty outbound pipeline (`PacketEncoder` then `CompressionEncoder`, both on the netty event-loop, never the server tick thread) and accounts every clientbound packet:

- per packet type: count, pre-compression bytes, on-wire (post-compression) bytes, compression ratio, share of outbound, bytes/second
- per player (heatmap): who receives the most, and their top packet types
- chunk broadcast redundancy: how often the same chunk is serialized and compressed for multiple players, sizing the serialize-once broadcast optimization (P0)

### Commands

- `/slipstream report` — print the current table to chat and write a snapshot to `logs/slipstream/`
- `/slipstream reset` — clear all counters and restart the measurement window

### Config

`config/Shinoyuki-Optimize/slipstream/common.toml`

- `telemetry.enabled` — master switch; when off every hook returns immediately (zero overhead)
- `telemetry.perPlayer` — per-connection breakdown for the heatmap
- `telemetry.chunkDedup` — track chunk-broadcast redundancy
- `telemetry.autoReportSeconds` — periodic snapshot to disk (0 disables)

## Roadmap

- P0: serialize-once / compress-once chunk broadcast with refCnt-shared `ByteBuf` (server-side, wire-compatible)
- P2: dual-end stateless zstd + trained dictionary, capability-negotiated, graceful vanilla fallback

## License

AGPL-3.0-or-later.
