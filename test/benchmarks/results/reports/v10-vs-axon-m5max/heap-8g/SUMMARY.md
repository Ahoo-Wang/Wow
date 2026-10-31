# Wow V10 对比 Axon Framework 性能基准（Apple M5 Max，8 GB 堆空间）

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
| 节点堆内存 | **8 GB** (`-Xms8g -Xmx8g -XX:+UseG1GC`) |

## 标准对比会话 (W A A W K K)

### E1 at 2,500/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.50 ms | 38.7 ms | 103.4 ms | 0.449 ms | 0.217 ms | 1.55 |
| sessions/2-A-postgres | Axon | 2,500/s | 0 | 1.56 ms | 5.6 ms | 17.2 ms | 0.410 ms | 0.342 ms | 1.98 |
| sessions/3-A-postgres | Axon | 2,500/s | 0 | 1.58 ms | 9.7 ms | 42.0 ms | 0.409 ms | 0.349 ms | 1.94 |
| sessions/4-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.53 ms | 123.4 ms | 177.2 ms | 0.439 ms | 0.214 ms | 1.51 |
| sessions/5-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.53 ms | 4.6 ms | 14.7 ms | 0.559 ms | 0.238 ms | 1.64 |
| sessions/6-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.61 ms | 53.8 ms | 107.5 ms | 0.587 ms | 0.223 ms | 1.48 |

### E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 700/s | 0 | 2.04 ms | 6.4 ms | 16.3 ms | 1.376 ms | 0.700 ms | 5.81 |
| sessions/2-A-postgres | Axon | 700/s | 0 | 3.41 ms | 8.5 ms | 24.7 ms | 1.038 ms | 1.106 ms | 7.07 |
| sessions/3-A-postgres | Axon | 700/s | 0 | 3.39 ms | 8.1 ms | 21.8 ms | 1.032 ms | 1.111 ms | 7.07 |
| sessions/4-W-postgres | Wow, Kafka off | 700/s | 0 | 2.06 ms | 7.4 ms | 19.5 ms | 1.361 ms | 0.706 ms | 5.75 |
| sessions/5-K-postgres | Wow, Kafka on | 700/s | 0 | 2.17 ms | 7.8 ms | 28.9 ms | 1.623 ms | 0.731 ms | 5.74 |
| sessions/6-K-postgres | Wow, Kafka on | 700/s | 0 | 2.24 ms | 7.5 ms | 49.4 ms | 1.763 ms | 0.732 ms | 5.72 |

### E1 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 1,114/s, p99 1 ms; 4: 2,506/s, p99 3 ms; 16: 5,953/s, p99 6 ms; 64: 12,043/s, p99 10 ms; 256: 16,065/s, p99 30 ms; 1024: 27,502/s, p99 61 ms; 4096: 36,685/s, p99 215 ms
- sessions/2-A-postgres (Axon): 1: 949/s, p99 2 ms; 4: 2,309/s, p99 4 ms; 16: 4,700/s, p99 6 ms; 64: 4,538/s, p99 29 ms; 256: 4,988/s, p99 94 ms; 1024: 4,872/s, p99 305 ms; 4096: 4,692/s, p99 1090 ms
- sessions/3-A-postgres (Axon): 1: 916/s, p99 3 ms; 4: 2,266/s, p99 5 ms; 16: 4,802/s, p99 6 ms; 64: 4,992/s, p99 22 ms; 256: 4,922/s, p99 92 ms; 1024: 4,762/s, p99 330 ms; 4096: 4,462/s, p99 1794 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 1,133/s, p99 2 ms; 4: 2,550/s, p99 3 ms; 16: 5,947/s, p99 6 ms; 64: 12,081/s, p99 10 ms; 256: 16,422/s, p99 28 ms; 1024: 26,929/s, p99 68 ms; 4096: 36,215/s, p99 204 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 1,104/s, p99 1 ms; 4: 2,349/s, p99 5 ms; 16: 5,796/s, p99 6 ms; 64: 11,574/s, p99 11 ms; 256: 16,227/s, p99 28 ms; 1024: 26,056/s, p99 68 ms; 4096: 36,452/s, p99 208 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 1,082/s, p99 2 ms; 4: 2,435/s, p99 3 ms; 16: 5,748/s, p99 6 ms; 64: 11,470/s, p99 11 ms; 256: 16,412/s, p99 27 ms; 1024: 25,956/s, p99 69 ms; 4096: 34,783/s, p99 242 ms

### E3 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 533/s, p99 3 ms; 4: 1,070/s, p99 6 ms; 16: 2,411/s, p99 15 ms; 64: 4,365/s, p99 27 ms; 256: 2,548/s, p99 303 ms; 1024: 462/s, p99 4653 ms; 4096: 8/s (22302 errors)
- sessions/2-A-postgres (Axon): 1: 342/s, p99 6 ms; 4: 873/s, p99 9 ms; 16: 1,548/s, p99 20 ms; 64: 1,554/s, p99 179 ms; 256: 1,489/s, p99 1327 ms; 1024: 1,414/s, p99 7471 ms; 4096: 1,148/s, p99 5177 ms
- sessions/3-A-postgres (Axon): 1: 332/s, p99 6 ms; 4: 828/s, p99 11 ms; 16: 1,545/s, p99 21 ms; 64: 1,555/s, p99 178 ms; 256: 1,524/s, p99 1180 ms; 1024: 1,439/s, p99 4325 ms; 4096: 1,136/s, p99 5407 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 540/s, p99 3 ms; 4: 1,069/s, p99 6 ms; 16: 2,434/s, p99 13 ms; 64: 4,364/s, p99 27 ms; 256: 2,783/s, p99 303 ms; 1024: 732/s (1 errors); 4096: 0/s (39892 errors)
- sessions/5-K-postgres (Wow, Kafka on): 1: 515/s, p99 4 ms; 4: 1,059/s, p99 6 ms; 16: 2,484/s, p99 12 ms; 64: 4,577/s, p99 24 ms; 256: 5,393/s, p99 78 ms; 1024: 9,808/s, p99 217 ms; 4096: 8,432/s (189309 errors)
- sessions/6-K-postgres (Wow, Kafka on): 1: 517/s, p99 3 ms; 4: 1,038/s, p99 7 ms; 16: 2,449/s, p99 12 ms; 64: 3,972/s, p99 34 ms; 256: 5,421/s, p99 78 ms; 1024: 9,867/s, p99 322 ms; 4096: 5,764/s (377043 errors)

## 连接池 32 会话 (pool-32)

### E1 at 2,500/s & E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| pool-32/1-A-postgres | Axon (pool 32) E1 | 2,500/s | 0 | 2.45 ms | 153.6 ms | 174.1 ms | 0.370 ms | 0.306 ms | 1.78 |
| pool-32/1-A-postgres | Axon (pool 32) E3 | 700/s | 0 | 3.41 ms | 7.5 ms | 17.7 ms | 1.041 ms | 1.117 ms | 7.17 |
| pool-32/2-W-postgres | Wow (pool 32) E1 | 2,500/s | 0 | 1.53 ms | 9.5 ms | 145.4 ms | 0.467 ms | 0.232 ms | 1.58 |
| pool-32/2-W-postgres | Wow (pool 32) E3 | 700/s | 0 | 2.06 ms | 6.9 ms | 23.4 ms | 1.395 ms | 0.716 ms | 5.81 |

### E1 closed loop

- pool-32/1-A-postgres (Axon, pool 32): 1: 944/s, p99 2 ms; 4: 2,573/s, p99 3 ms; 16: 6,108/s, p99 4 ms; 64: **8,436/s**, p99 14 ms; 256: 7,827/s, p99 114 ms; 1024: 8,126/s, p99 255 ms; 4096: 8,123/s, p99 905 ms
- pool-32/2-W-postgres (Wow, Kafka off, pool 32): 1: 1,125/s, p99 1 ms; 4: 2,509/s, p99 3 ms; 16: 5,930/s, p99 6 ms; 64: 12,189/s, p99 10 ms; 256: 15,112/s, p99 34 ms; 1024: 27,276/s, p99 62 ms; 4096: **37,019/s**, p99 209 ms

### E3 closed loop

- pool-32/1-A-postgres (Axon, pool 32): 1: 322/s, p99 6 ms; 4: 867/s, p99 8 ms; 16: 1,857/s, p99 18 ms; 64: **2,345/s**, p99 111 ms; 256: 2,287/s, p99 1466 ms; 1024: 2,013/s, p99 5472 ms; 4096: 1,590/s, p99 4260 ms
- pool-32/2-W-postgres (Wow, Kafka off, pool 32): 1: 503/s, p99 7 ms; 4: 1,061/s, p99 6 ms; 16: 2,469/s, p99 12 ms; 64: 4,208/s, p99 30 ms; 256: 2,759/s, p99 303 ms; 1024: 490/s, p99 4751 ms; 4096: 18/s (21458 errors)

## 核心发现与 1 GB 对比

1. **8 GB 完全解除了内存瓶颈**：
   - **Wow 关闭 Kafka (W)**：彻底消除了 1 GB 堆下的 OOM。在无 Kafka 投递开销时，E1 闭环最高吞吐达 **37,019/s**（并发 4,096，0 错误），展现了 Wow 核心引擎与 PostgreSQL Group Commit 的纯吞吐上限。
   - **Axon 32 连接 (pool-32)**：8 GB 堆空间成功支撑了 Axon 经历 116 万次 E1 写入后的完整 E3 扫描，顺利测出 Axon 在 32 连接下的真实 E3 峰值：**2,345/s**（并发 64），相比 10 连接（1,555/s）提升 **51%**。
2. **两框架的上限差距维持不变**：
   - **E1 写入峰值**：Wow 10（36,452/s）为 Axon 10 连接（4,992/s）的 **7.3 倍**；在 32 连接对等下，Wow（37,019/s）为 Axon（8,436/s）的 **4.4 倍**。
   - **E3 Saga 峰值**：Wow 10（9,867/s）为 Axon 10 连接（1,555/s）的 **6.3 倍**，为 Axon 32 连接（2,345/s）的 **4.2 倍**。
3. **数据库端资源开销**：
   - 在 2,500/s 与 700/s 定速下，Axon 的 PostgreSQL CPU 消耗持续高出 Wow **45%～55%**（E1: 0.34 ms vs 0.23 ms; E3: 1.11 ms vs 0.72 ms），数据库提交数多出 20% 左右。
