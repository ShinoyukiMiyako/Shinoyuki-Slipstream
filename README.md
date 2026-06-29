# Slipstream

Minecraft 1.20.1 Forge 的网络层流量遥测与优化。Shinoyuki 优化系列的网络分支——BetterAutoSave 之于存盘/加载 IO，Slipstream 之于网络出站带宽。

## 定位

Shinoyuki 系列遵循同一条优先级：**数据安全 > 稳定性 > 兼容性 > 性能**。性能优化只在不损害前三者的前提下进行——任何需要牺牲数据保真、服务器稳定或客户端兼容来换取带宽的手段，一律不做。

具体到 Slipstream：

- **数据安全**：所有协议改写无损。实体字段 delta 的编解码有逐字节往返（byte-exact round-trip）单元测试门禁；入站 zstd 帧有解压炸弹大小防护。
- **稳定性**：全部处理在 netty event-loop 上完成，从不触碰服务器主线程（tick 线程），不增加 MSPT、不影响 TPS。
- **兼容性**：声明为 server-optional（`IGNORESERVERONLY`），原版及未安装本 mod 的客户端照常连接。压缩等改写采用单端可回退——对端没有 Slipstream 时自动退回原版 zlib，线上字节与原版完全一致。
- **性能**：在以上前提下削减出站带宽（压缩、小包聚合、实体字段 delta、广播去重）。

### 明确不做

为守住上述优先级，以下常见带宽手段本 mod 主动放弃：

- **有损压缩**（坐标/速度量化）：带宽虽可再降，但牺牲数据保真，违背数据安全优先。
- **强制双端的包头索引替换**：需双端共享索引表，会牺牲单端可回退的兼容性。

## 功能

### 遥测

服务端出站（clientbound）流量分析，纯测量、不改协议：

- 按包类型：数量、压缩前字节、线上（压缩后）字节、压缩比、出站占比、字节/秒
- 按玩家（热力）：谁接收最多，及其主要包类型
- 区块广播冗余：同一区块为多个玩家重复序列化/压缩的频率，用于度量 serialize-once 的收益

### 优化

- **zstd 线上压缩**：单端可回退，按连接协商，仅当双端均启用时生效。压缩级别 1-19（默认 3）。区块包线上字节约削减 15-35%。
- **大包支持**：抬高原版包体积上限，使 MTR 地图数据、载具/结构 NBT、蓝图粘贴等大包不再崩溃或被拒——内置替代 XLPackets / PacketFixer。
- **serialize-once 广播去重**：同一次同步广播中，每个区块包只压缩一次，其余接收者复用同一压缩帧，跳过重复的 Deflater 开销；零失效。
- **小包聚合**（战斗场景）：把成片的微型实体状态包（motion / move / head-rotate）在一个窗口内合并成一个批，消除逐包封帧开销，并把可压缩的批整体交给 zstd。窗口默认 20ms。
- **实体字段 delta**（战斗场景）：把高频实体状态包按“每实体每字段”重编码为 zigzag-varint 增量，平滑运动塌缩到亚字节字段。无损。

聚合与实体 delta 在内部生产服密集战斗场景实测可观降低实体包出站（聚合约三分之一，实体 delta 在其上再叠加约 18% 无损）。具体数字依场景与负载而定，非普适保证。

## 命令

需 OP（权限等级 2）：

- `/slipstream report` — 打印当前遥测表，并把快照写入 `logs/slipstream/`（同时输出 .json）
- `/slipstream reset` — 清零所有计数器，重启测量窗口
- `/slipstream capture start [秒数]` — 抓取原始出站包到磁盘供离线分析，默认 180 秒，范围 5-600，到时自动停
- `/slipstream capture stop` — 手动停止抓包

## 配置

`config/Shinoyuki-Optimize/slipstream/common.toml`

### telemetry

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关；关闭后所有 netty 侧钩子立即返回（零开销） |
| `perPlayer` | `true` | 累积每连接（每玩家）分解，用于热力 |
| `chunkDedup` | `true` | 跟踪区块广播冗余 |
| `autoReportSeconds` | `0` | 每 N 秒自动写快照到 `logs/slipstream`；0 关闭（仅手动 report）。范围 0-3600 |

### optimize

| 键 | 默认 | 说明 |
|---|---|---|
| `chunkSerializeOnce` | `false` | serialize-once 广播去重；未命中缓存的包回退原版逐连接压缩 |
| `zstdEnabled` | `true` | zstd 线上压缩；按连接协商，仅双端均开启才生效，否则字节级回退原版 zlib。改动需重启 |
| `zstdLevel` | `3` | zstd 压缩级别，范围 1-19。级别越高区块包削减越多，netty event-loop 每包 CPU 越高 |
| `zstdMaxUncompressedMiB` | `256` | 入站（client->server）zstd 帧解压大小上限（MiB），防解压炸弹。范围 8-1024 |
| `largePacketEnabled` | `true` | 大包支持（替代 XLPackets / PacketFixer）。普通小包与原版字节一致；大包需双端均安装 |
| `aggregateEnabled` | `false` | 小包聚合；双端协商，仅双端均开启才生效 |
| `aggregateWindowMs` | `20` | 聚合刷新窗口（毫秒）。一个 server tick = 50ms；关键包（keep-alive）绕过窗口立即刷新。范围 5-100 |
| `l2Enabled` | `false` | 实体字段 delta；无损；双端协商，仅双端均开启才生效 |

协商类功能（zstd / 聚合 / 实体 delta / 大包）的能力通道在启动时注册，因此开关这些选项需要重启服务端（及客户端）。

## 兼容性

- **原版/未装客户端**：可正常连接；所有优化对其回退为原版行为。
- **另一端也装 Slipstream**：协商类功能需双端均开启同一选项方才生效。
- **XLPackets / PacketFixer**：开启 `largePacketEnabled` 后可移除二者。
- **应用层压缩 mod（NotEnoughBandwidth / BandwidthOptimizer 等）**：不要与 Slipstream 的 zstd 同台叠加——会造成双重压缩（白烧 CPU）与 native 内存叠加，应二选一。

## 许可证

AGPL-3.0-or-later。
