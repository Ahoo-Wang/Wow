# Wow V10 对比 Axon Framework 性能基准（Apple M4 Pro）

## 环境

| 项目 | 配置 |
| :--- | :--- |
| 分支 | `claude/m4pro-axon-1025` |
| 提交号 (HEAD) | `c48c5d5191` |
| 机器硬件 | Apple M4 Pro (14 核: 10 性能核 + 4 能效核，24 GB 统一内存) |
| 操作系统 | macOS |
| JDK 版本 | Eclipse Adoptium Temurin 21.0.13+9 (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`) |
| Docker Desktop | 4.96.3 |
| Docker Engine | 29.9.2 |
| Docker 资源分配 | 12 CPUs，16 GB 内存 (15.6 GiB 可用) |
| 存储介质 | 磁盘 (`Docker.raw`，未启用 `--tmpfs`，包含真实磁盘 I/O 成本) |
| 数据集 | 100,000 账户 (`--accounts=100000`)，`--prime --seed-concurrency=128` |
| 节点堆内存 | 1 GB (`-Xms1g -Xmx1g -XX:+UseG1GC`) |

## 标准对比会话 (W A A W K K)

### E1 at 2,500/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 2,500/s | 0 | 0.57 ms | 2.1 ms | 9.6 ms | 0.554 ms | 0.201 ms | 2.04 |
| sessions/2-A-postgres | Axon | 2,500/s | 0 | 1.38 ms | 540.7 ms | 720.9 ms | 0.549 ms | 0.285 ms | 1.89 |
| sessions/3-A-postgres | Axon | 2,500/s | 0 | 1.34 ms | 74.8 ms | 259.1 ms | 0.544 ms | 0.284 ms | 1.89 |
| sessions/4-W-postgres | Wow, Kafka off | 2,500/s | 0 | 0.57 ms | 3.0 ms | 12.0 ms | 0.557 ms | 0.208 ms | 2.01 |
| sessions/5-K-postgres | Wow, Kafka on | 2,500/s | 0 | 0.61 ms | 2.7 ms | 15.9 ms | 0.691 ms | 0.224 ms | 2.02 |
| sessions/6-K-postgres | Wow, Kafka on | 2,500/s | 0 | 0.61 ms | 3.2 ms | 22.7 ms | 0.715 ms | 0.230 ms | 2.03 |

### E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 700/s | 0 | 1.02 ms | 3.0 ms | 11.5 ms | 1.377 ms | 0.592 ms | 6.06 |
| sessions/2-A-postgres | Axon | 700/s | 0 | 3.04 ms | 21.1 ms | 61.7 ms | 1.324 ms | 1.001 ms | 6.07 |
| sessions/3-A-postgres | Axon | 700/s | 0 | 3.18 ms | 31.4 ms | 235.5 ms | 1.286 ms | 0.959 ms | 5.96 |
| sessions/4-W-postgres | Wow, Kafka off | 700/s | 0 | 1.07 ms | 313.3 ms | 536.6 ms | 1.353 ms | 0.620 ms | 5.84 |
| sessions/5-K-postgres | Wow, Kafka on | 700/s | 0 | 1.11 ms | 407.6 ms | 749.6 ms | 1.765 ms | 0.652 ms | 5.96 |
| sessions/6-K-postgres | Wow, Kafka on | 700/s | 0 | 1.11 ms | 85.5 ms | 421.9 ms | 1.842 ms | 0.662 ms | 5.91 |

### E1 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 1,902/s, p99 1 ms; 4: 4,088/s, p99 2 ms; 16: 8,509/s, p99 4 ms; 64: 16,305/s, p99 9 ms; 256: 17,671/s, p99 29 ms; 1024: 22,212/s, p99 82 ms
- sessions/2-A-postgres (Axon): 1: 941/s, p99 2 ms; 4: 2,638/s, p99 5 ms; 16: 4,382/s, p99 8 ms; 64: 4,172/s, p99 42 ms; 256: 4,544/s, p99 116 ms; 1024: 4,212/s, p99 528 ms
- sessions/3-A-postgres (Axon): 1: 984/s, p99 2 ms; 4: 2,572/s, p99 5 ms; 16: 4,114/s, p99 8 ms; 64: 4,756/s, p99 37 ms; 256: 4,847/s, p99 106 ms; 1024: 4,072/s, p99 872 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 1,635/s, p99 5 ms; 4: 2,961/s, p99 7 ms; 16: 7,566/s, p99 7 ms; 64: 9,807/s, p99 24 ms; 256: 16,714/s, p99 30 ms; 1024: 20,971/s, p99 84 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 1,776/s, p99 1 ms; 4: 3,345/s, p99 7 ms; 16: 5,964/s, p99 9 ms; 64: 10,350/s, p99 24 ms; 256: 14,312/s, p99 41 ms; 1024: 18,926/s, p99 117 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 1,603/s, p99 3 ms; 4: 2,897/s, p99 7 ms; 16: 5,747/s, p99 9 ms; 64: 11,304/s, p99 24 ms; 256: 12,222/s, p99 47 ms; 1024: 17,231/s, p99 264 ms

### E3 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 903/s, p99 2 ms; 4: 1,686/s, p99 4 ms; 16: 3,308/s, p99 13 ms; 64: 2,637/s, p99 120 ms; 256: 3,646/s, p99 182 ms; 1024: 7,427/s, p99 264 ms
- sessions/2-A-postgres (Axon): 1: 360/s, p99 6 ms; 4: 1,004/s, p99 8 ms; 16: 1,414/s, p99 24 ms; 64: 1,313/s, p99 253 ms; 256: 1,317/s, p99 1884 ms; 1024: 1,060/s, p99 8651 ms
- sessions/3-A-postgres (Axon): 1: 369/s, p99 5 ms; 4: 1,024/s, p99 7 ms; 16: 1,642/s, p99 20 ms; 64: 1,446/s, p99 190 ms; 256: 1,495/s, p99 1212 ms; 1024: 1,419/s, p99 5931 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 744/s, p99 7 ms; 4: 1,474/s, p99 8 ms; 16: 3,134/s, p99 18 ms; 64: 4,456/s, p99 32 ms; 256: 3,812/s, p99 147 ms; 1024: 7,885/s, p99 451 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 721/s, p99 7 ms; 4: 1,188/s, p99 9 ms; 16: 2,740/s, p99 23 ms; 64: 2,929/s, p99 112 ms; 256: 3,512/s, p99 250 ms; 1024: 6,879/s, p99 565 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 832/s, p99 2 ms; 4: 1,131/s, p99 9 ms; 16: 2,203/s, p99 31 ms; 64: 3,028/s, p99 97 ms; 256: 3,666/s, p99 248 ms; 1024: 6,642/s, p99 639 ms

## 连接池 32 会话 (Axon pool-32)

### E1 closed loop

- pool-32/1-A-postgres (Axon): 1: 1,037/s, p99 1 ms; 4: 2,857/s, p99 2 ms; 16: 5,190/s, p99 5 ms; 64: 6,730/s, p99 28 ms; 256: 6,715/s, p99 74 ms; 1024: 6,770/s, p99 309 ms

### E3 closed loop

- pool-32/1-A-postgres (Axon): 1: 367/s, p99 5 ms; 4: 1,009/s, p99 8 ms; 16: 1,781/s, p99 21 ms; 64: 1,920/s, p99 158 ms; 256: 1,614/s, p99 1671 ms; 1024: 1,536/s, p99 3604 ms
