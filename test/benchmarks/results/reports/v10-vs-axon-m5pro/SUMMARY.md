# Wow V10 对比 Axon Framework 性能基准（Apple M5 Pro）

## 环境

| 项目 | 配置 |
| :--- | :--- |
| 分支 | `claude/m5pro-bench-1026` |
| 提交号 (HEAD) | `147ce51d53` |
| 机器硬件 | Apple M5 Pro (MacBook Pro Mac17,9，15 核: 5 超级核 + 10 性能核，48 GB 统一内存) |
| 操作系统 | macOS (Darwin 24.x) |
| JDK 版本 | Eclipse Adoptium Temurin 21.0.13+9 (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`) |
| Docker Desktop | 4.96.3 |
| Docker Engine | 29.9.2 |
| Docker 资源分配 | 12 CPUs，16 GB 内存 (15.6 GiB 可用) |
| 虚拟机类型 | Apple Virtualization Framework (`useVirtualizationFramework: true`, `virtioFS: true`) |
| 存储介质 | 磁盘 (`Docker.raw`，保持默认，未启用 `--tmpfs`，包含真实磁盘 I/O 成本) |
| 数据集 | 100,000 账户 (`--accounts=100000`)，`--prime --seed-concurrency=128` |
| 节点堆内存 | 1 GB (`-Xms1g -Xmx1g -XX:+UseG1GC`) |

## 标准对比会话 (W A A W K K)

### E1 at 2,500/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 2,500/s | 0 | 0.60 ms | 3.2 ms | 10.8 ms | 0.498 ms | 0.232 ms | 1.98 |
| sessions/2-A-postgres | Axon | 2,500/s | 0 | 1.81 ms | 20.1 ms | 33.3 ms | 0.554 ms | 0.332 ms | 1.69 |
| sessions/3-A-postgres | Axon | 2,500/s | 0 | 2.05 ms | 108.5 ms | 128.0 ms | 0.563 ms | 0.350 ms | 1.60 |
| sessions/4-W-postgres | Wow, Kafka off | 2,500/s | 0 | 0.58 ms | 2.0 ms | 7.4 ms | 0.498 ms | 0.229 ms | 2.00 |
| sessions/5-K-postgres | Wow, Kafka on | 2,500/s | 0 | 0.70 ms | 3.0 ms | 10.8 ms | 0.660 ms | 0.251 ms | 1.96 |
| sessions/6-K-postgres | Wow, Kafka on | 2,500/s | 0 | 0.71 ms | 2.3 ms | 9.2 ms | 0.645 ms | 0.251 ms | 1.98 |
| pool-32/1-A-postgres | Axon | (none) | | | | | | | |

### E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 700/s | 0 | 0.94 ms | 7.6 ms | 60.2 ms | 1.212 ms | 0.662 ms | 6.01 |
| sessions/2-A-postgres | Axon | 700/s | 0 | 3.34 ms | 18.7 ms | 36.1 ms | 1.314 ms | 0.994 ms | 5.86 |
| sessions/3-A-postgres | Axon | 700/s | 0 | 3.84 ms | 20.1 ms | 37.6 ms | 1.418 ms | 1.045 ms | 5.71 |
| sessions/4-W-postgres | Wow, Kafka off | 700/s | 0 | 0.90 ms | 3.1 ms | 10.6 ms | 1.184 ms | 0.613 ms | 6.09 |
| sessions/5-K-postgres | Wow, Kafka on | 700/s | 0 | 1.01 ms | 22.4 ms | 36.4 ms | 1.604 ms | 0.644 ms | 5.81 |
| sessions/6-K-postgres | Wow, Kafka on | 700/s | 0 | 0.97 ms | 3.1 ms | 12.8 ms | 1.550 ms | 0.663 ms | 6.07 |
| pool-32/1-A-postgres | Axon | (none) | | | | | | | |

### E1 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 1,920/s, p99 1 ms; 4: 3,377/s, p99 2 ms; 16: 7,206/s, p99 5 ms; 64: 14,210/s, p99 10 ms; 256: 15,856/s, p99 29 ms; 1024: 23,629/s, p99 76 ms
- sessions/2-A-postgres (Axon): 1: 885/s, p99 2 ms; 4: 2,172/s, p99 3 ms; 16: 3,412/s, p99 7 ms; 64: 3,520/s, p99 43 ms; 256: 3,574/s, p99 140 ms; 1024: 3,593/s, p99 569 ms
- sessions/3-A-postgres (Axon): 1: 891/s, p99 2 ms; 4: 2,080/s, p99 3 ms; 16: 3,203/s, p99 8 ms; 64: 3,306/s, p99 45 ms; 256: 3,480/s, p99 144 ms; 1024: 3,458/s, p99 586 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 1,907/s, p99 1 ms; 4: 3,295/s, p99 2 ms; 16: 6,360/s, p99 5 ms; 64: 11,740/s, p99 12 ms; 256: 14,108/s, p99 32 ms; 1024: 20,579/s, p99 87 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 1,819/s, p99 1 ms; 4: 2,776/s, p99 3 ms; 16: 5,322/s, p99 6 ms; 64: 10,292/s, p99 14 ms; 256: 12,890/s, p99 36 ms; 1024: 17,615/s, p99 98 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 1,844/s, p99 1 ms; 4: 2,956/s, p99 2 ms; 16: 5,318/s, p99 6 ms; 64: 10,239/s, p99 14 ms; 256: 12,874/s, p99 36 ms; 1024: 18,934/s, p99 97 ms
- pool-32/1-A-postgres (Axon): 1: 902/s, p99 2 ms; 4: 2,228/s, p99 3 ms; 16: 3,956/s, p99 7 ms; 64: 5,254/s, p99 39 ms; 256: 5,372/s, p99 91 ms; 1024: 5,440/s, p99 379 ms

### E3 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 863/s, p99 2 ms; 4: 1,444/s, p99 5 ms; 16: 3,028/s, p99 13 ms; 64: 4,816/s, p99 27 ms; 256: 4,829/s, p99 75 ms; 1024: 9,627/s, p99 240 ms
- sessions/2-A-postgres (Axon): 1: 338/s, p99 5 ms; 4: 822/s, p99 8 ms; 16: 1,158/s, p99 36 ms; 64: 1,061/s, p99 197 ms; 256: 1,099/s, p99 1622 ms; 1024: 923/s, p99 4358 ms
- sessions/3-A-postgres (Axon): 1: 336/s, p99 5 ms; 4: 804/s, p99 8 ms; 16: 1,155/s, p99 28 ms; 64: 1,162/s, p99 197 ms; 256: 1,108/s, p99 1655 ms; 1024: 950/s, p99 4145 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 852/s, p99 2 ms; 4: 1,199/s, p99 15 ms; 16: 2,454/s, p99 19 ms; 64: 3,619/s, p99 105 ms; 256: 4,260/s, p99 170 ms; 1024: 8,123/s, p99 239 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 754/s, p99 6 ms; 4: 1,021/s, p99 9 ms; 16: 2,152/s, p99 17 ms; 64: 3,473/s, p99 36 ms; 256: 4,024/s, p99 144 ms; 1024: 7,287/s, p99 261 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 794/s, p99 2 ms; 4: 1,212/s, p99 5 ms; 16: 2,455/s, p99 12 ms; 64: 3,732/s, p99 32 ms; 256: 4,179/s, p99 97 ms; 1024: 7,055/s, p99 549 ms
- pool-32/1-A-postgres (Axon): 1: 336/s, p99 5 ms; 4: 806/s, p99 8 ms; 16: 1,289/s, p99 26 ms; 64: 1,516/s, p99 175 ms; 256: 1,451/s, p99 1352 ms; 1024: 1,265/s, p99 3965 ms
