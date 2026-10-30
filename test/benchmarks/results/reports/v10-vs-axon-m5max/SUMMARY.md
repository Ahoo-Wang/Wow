# Wow V10 对比 Axon Framework 性能基准（Apple M5 Max）

## 环境

| 项目 | 配置 |
| :--- | :--- |
| 分支 | `bench/vs-axon-m5max` |
| 提交号 (HEAD) | `3c138c0237` |
| 机器硬件 | Apple M5 Max (`Mac17,14`，18 核：6 超级核 + 12 性能核，64 GB 统一内存) |
| 操作系统 | macOS 27.2 (26B123) |
| JDK 版本 | Eclipse Adoptium Temurin 21.0.13+9 (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`) |
| Docker Engine | 29.8.2 (OrbStack 2.2.5) |
| Docker 资源分配 | 16 CPUs，32 GB 内存 (`33670660096` 字节) |
| 存储介质 | 磁盘 (`virtioFS`，未启用 `--tmpfs`，包含真实磁盘 I/O 成本) |
| 数据集 | 100,000 账户 (`--accounts=100000`)，`--prime --seed-concurrency=128` |
| 节点堆内存 | 1 GB (`-Xms1g -Xmx1g -XX:+UseG1GC`) |

## 标准对比会话 (W A A W K K)

### E1 at 2,500/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.48 ms | 4.5 ms | 15.0 ms | 0.480 ms | 0.233 ms | 1.66 |
| sessions/2-A-postgres | Axon | 2,500/s | 0 | 1.66 ms | 18.4 ms | 29.3 ms | 0.490 ms | 0.355 ms | 1.96 |
| sessions/3-A-postgres | Axon | 2,500/s | 0 | 1.70 ms | 23.7 ms | 39.9 ms | 0.505 ms | 0.348 ms | 1.95 |
| sessions/4-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.47 ms | 4.5 ms | 12.5 ms | 0.488 ms | 0.232 ms | 1.69 |
| sessions/5-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.92 ms | 92.2 ms | 116.7 ms | 0.546 ms | 0.201 ms | 1.23 |
| sessions/6-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.55 ms | 4.6 ms | 19.5 ms | 0.554 ms | 0.236 ms | 1.64 |

### E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 700/s | 0 | 2.21 ms | 6.8 ms | 17.0 ms | 1.480 ms | 0.726 ms | 5.71 |
| sessions/2-A-postgres | Axon | 700/s | 0 | 3.47 ms | 12.7 ms | 28.8 ms | 1.191 ms | 1.122 ms | 6.99 |
| sessions/3-A-postgres | Axon | 700/s | 0 | 3.52 ms | 17.7 ms | 33.5 ms | 1.252 ms | 1.112 ms | 6.97 |
| sessions/4-W-postgres | Wow, Kafka off | 700/s | 0 | 2.14 ms | 7.4 ms | 15.7 ms | 1.537 ms | 0.712 ms | 5.75 |
| sessions/5-K-postgres | Wow, Kafka on | 700/s | 0 | 2.32 ms | 7.1 ms | 23.0 ms | 1.737 ms | 0.719 ms | 5.72 |
| sessions/6-K-postgres | Wow, Kafka on | 700/s | 0 | 2.25 ms | 7.6 ms | 20.2 ms | 1.613 ms | 0.717 ms | 5.76 |

### E1 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 1,076/s, p99 2 ms; 4: 2,393/s, p99 3 ms; 16: 994/s, p99 87 ms; 64: 29/s, p99 590 ms; 256: 0/s (887328 errors)
- sessions/2-A-postgres (Axon): 1: 942/s, p99 2 ms; 4: 2,380/s, p99 3 ms; 16: 4,406/s, p99 7 ms; 64: 4,566/s, p99 37 ms; 256: 4,604/s, p99 89 ms; 1024: 4,559/s, p99 410 ms; 4096: 4,425/s, p99 1761 ms
- sessions/3-A-postgres (Axon): 1: 857/s, p99 3 ms; 4: 2,408/s, p99 3 ms; 16: 4,474/s, p99 7 ms; 64: 4,380/s, p99 37 ms; 256: 4,712/s, p99 86 ms; 1024: 4,599/s, p99 420 ms; 4096: 4,500/s, p99 1090 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 1,113/s, p99 2 ms; 4: 2,398/s, p99 3 ms; 16: 951/s, p99 87 ms; 64: 26/s, p99 1368 ms; 256: 0/s (1385181 errors)
- sessions/5-K-postgres (Wow, Kafka on): 1: 1,082/s, p99 2 ms; 4: 2,453/s, p99 3 ms; 16: 5,804/s, p99 6 ms; 64: 11,546/s, p99 11 ms; 256: 15,729/s, p99 29 ms; 1024: 25,564/s, p99 70 ms; 4096: 33,655/s, p99 255 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 1,102/s, p99 1 ms; 4: 2,464/s, p99 3 ms; 16: 5,786/s, p99 6 ms; 64: 11,690/s, p99 11 ms; 256: 15,453/s, p99 34 ms; 1024: 26,136/s, p99 71 ms; 4096: 34,256/s, p99 242 ms

### E3 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 0/s (240596 errors)
- sessions/2-A-postgres (Axon): 1: 328/s, p99 6 ms; 4: 900/s, p99 8 ms; 16: 1,542/s, p99 21 ms; 64: 1,563/s, p99 138 ms; 256: 1,462/s, p99 1548 ms; 1024: 1,403/s, p99 8847 ms; 4096: 1,175/s, p99 5308 ms
- sessions/3-A-postgres (Axon): 1: 308/s, p99 7 ms; 4: 904/s, p99 8 ms; 16: 1,554/s, p99 21 ms; 64: 1,577/s, p99 143 ms; 256: 1,505/s, p99 1409 ms; 1024: 1,381/s, p99 5964 ms; 4096: 1,176/s, p99 5243 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 0/s (231673 errors)
- sessions/5-K-postgres (Wow, Kafka on): 1: 527/s, p99 3 ms; 4: 1,052/s, p99 6 ms; 16: 2,450/s, p99 12 ms; 64: 4,367/s, p99 28 ms; 256: 5,432/s, p99 95 ms; 1024: 10,009/s, p99 178 ms; 4096: 8,406/s (216629 errors)
- sessions/6-K-postgres (Wow, Kafka on): 1: 513/s, p99 4 ms; 4: 1,057/s, p99 7 ms; 16: 2,495/s, p99 12 ms; 64: 4,558/s, p99 24 ms; 256: 5,550/s, p99 79 ms; 1024: 9,839/s, p99 303 ms; 4096: 8,102/s (290211 errors)

## 连接池 32 会话 (pool-32)

### E1 at 2,500/s & E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pool-32/1-A-postgres | Axon (pool 32) E1 | 2,500/s | 0 | 1.66 ms | 16.3 ms | 28.7 ms | 0.496 ms | 0.356 ms | 1.97 |
| pool-32/1-A-postgres | Axon (pool 32) E3 | 700/s | 0 | 3.58 ms | 11.6 ms | 28.8 ms | 1.204 ms | 1.145 ms | 7.11 |
| pool-32/2-W-postgres | Wow (pool 32) E1 | 2,500/s | 0 | 1.52 ms | 15.7 ms | 102.4 ms | 0.467 ms | 0.222 ms | 1.59 |
| pool-32/2-W-postgres | Wow (pool 32) E3 | 700/s | 0 | 2.21 ms | 6.7 ms | 22.7 ms | 1.492 ms | 0.708 ms | 5.74 |

### E1 closed loop

- pool-32/1-A-postgres (Axon, pool 32): 1: 942/s, p99 2 ms; 4: 2,407/s, p99 3 ms; 16: 5,601/s, p99 5 ms; 64: 7,904/s, p99 29 ms; 256: 7,425/s, p99 74 ms; 1024: 7,175/s, p99 326 ms; 4096: 7,473/s, p99 1065 ms
- pool-32/2-W-postgres (Wow, Kafka off, pool 32): 1: 1,118/s, p99 2 ms; 4: 2,408/s, p99 3 ms; 16: 881/s, p99 87 ms; 64: 18/s, p99 668 ms; 256: 0/s (1396584 errors)

### E3 closed loop

- pool-32/1-A-postgres (Axon, pool 32): 1: 0/s (3 errors, OOM after 1.16M E1 operations in the same 1 GB JVM)
- pool-32/2-W-postgres (Wow, Kafka off, pool 32): 1: 0/s (235023 errors, server died of OOM during E1 sweep)

## 异常与发现

1. **Wow 在 Kafka 开启 (K) 下性能提升巨大**：
   - E1 闭环最高吞吐达 **34,256/s**（并发 4,096，p99 242 ms），相比 M4 Pro 峰值（18,926/s）提升 **81%**；为 Axon 10 连接峰值（4,712/s）的 **7.3 倍**，为 Axon 32 连接峰值（7,904/s）的 **4.3 倍**。
   - E3 闭环最高吞吐达 **10,009/s**（并发 1,024，p99 178 ms），相比 M4 Pro 峰值（6,879/s）提升 **45%**；为 Axon 10 连接峰值（1,577/s）的 **6.3 倍**。
2. **Wow 在 Kafka 关闭 (W) 下高并发 OOM 机制**：
   - 自 commit `bae2d4024`（R5 batch 4a-2）起，`wow.kafka.bootstrap-servers=`（空地址）触发配置自动推导，回退至 `DevelopmentMode` 下的 `InMemoryTopicsAutoConfiguration`（`wow.transport=memory`）。
   - `InMemoryTopics` 使用无界的 JVM 内存 `ArrayList` 保存所有发布的消息，且无淘汰机制。在 M5 Max 极高并发扫描下，产生数十万条消息迅速打满 1 GB 堆内存，抛出 `OutOfMemoryError: Java heap space` 导致节点崩溃，随后的 E3 扫描因而连接中断。
3. **Axon 32 连接与长周期会话堆内存限制**：
   - 32 连接将 Axon 的 E1 峰值提升了 68%（由 4,712/s 提升至 7,904/s），但并发在 64 即达到瓶颈，4096 并发下 p99 劣化至 1,065 ms。
   - 在完成包含 116 万次数据库写入的 E1 扫描后，Axon 的 16 个跟踪处理器线程与连接缓存已耗尽 1 GB 堆剩余空间，在紧接启动的 E3 扫描第一步即发生 `EventProcessor[transfer]-10` 堆内存溢出崩溃。
4. **定速开环资源开销对比**：
   - 在 2,500/s 定速下，Wow 与 Axon 的节点 CPU 消耗相近（0.48-0.55 ms/op），但 Axon 的 PostgreSQL 数据库端 CPU 开销高出 **50%**（0.35 ms vs 0.23 ms），提交数多 18%（1.96 vs 1.66）。
   - 在 700/s 定速下，Axon 的 p50（3.5 ms）为 Wow（2.2 ms）的 **1.6 倍**，PostgreSQL 数据库 CPU 开销高出 **55%**（1.12 ms vs 0.72 ms）。
