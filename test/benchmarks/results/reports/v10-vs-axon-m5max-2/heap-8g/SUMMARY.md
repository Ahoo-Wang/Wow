
### E1 at 2,500/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.45 ms | 4.6 ms | 14.0 ms | 0.433 ms | 0.228 ms | 1.67 |
| sessions/2-A-postgres | Axon | 2,500/s | 0 | 1.69 ms | 5.8 ms | 21.9 ms | 0.418 ms | 0.317 ms | 1.91 |
| sessions/3-A-postgres | Axon | 2,500/s | 0 | 1.68 ms | 7.6 ms | 25.6 ms | 0.412 ms | 0.307 ms | 1.93 |
| sessions/4-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.46 ms | 5.0 ms | 16.3 ms | 0.428 ms | 0.226 ms | 1.70 |
| sessions/5-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.51 ms | 4.9 ms | 16.0 ms | 0.536 ms | 0.238 ms | 1.63 |
| sessions/6-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.50 ms | 4.4 ms | 13.5 ms | 0.540 ms | 0.234 ms | 1.65 |
| pool-32/1-A-postgres | Axon (pool 32) | 2,500/s | 0 | 1.61 ms | 4.3 ms | 16.2 ms | 0.403 ms | 0.307 ms | 1.96 |
| pool-32/2-W-postgres | Wow, Kafka off (pool 32) | 2,500/s | 0 | 1.45 ms | 5.0 ms | 14.1 ms | 0.436 ms | 0.232 ms | 1.67 |

### E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 700/s | 0 | 2.65 ms | 172.0 ms | 200.7 ms | 1.165 ms | 0.639 ms | 4.61 |
| sessions/2-A-postgres | Axon | 700/s | 0 | 3.68 ms | 9.1 ms | 26.2 ms | 1.026 ms | 1.020 ms | 6.71 |
| sessions/3-A-postgres | Axon | 700/s | 0 | 3.71 ms | 9.1 ms | 22.7 ms | 1.015 ms | 1.010 ms | 6.72 |
| sessions/4-W-postgres | Wow, Kafka off | 700/s | 0 | 2.25 ms | 6.9 ms | 17.0 ms | 1.245 ms | 0.682 ms | 5.75 |
| sessions/5-K-postgres | Wow, Kafka on | 700/s | 0 | 2.64 ms | 9.5 ms | 22.4 ms | 1.626 ms | 0.805 ms | 5.45 |
| sessions/6-K-postgres | Wow, Kafka on | 700/s | 0 | 2.38 ms | 7.0 ms | 19.1 ms | 1.558 ms | 0.703 ms | 5.75 |
| pool-32/1-A-postgres | Axon (pool 32) | 700/s | 0 | 3.62 ms | 8.4 ms | 17.4 ms | 1.014 ms | 1.018 ms | 6.84 |
| pool-32/2-W-postgres | Wow, Kafka off (pool 32) | 700/s | 0 | 2.48 ms | 9.0 ms | 17.4 ms | 1.335 ms | 0.782 ms | 5.54 |

### E1 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 1,119/s, p99 2 ms; 4: 2,511/s, p99 3 ms; 16: 6,252/s, p99 5 ms; 64: 12,321/s, p99 11 ms; 256: 15,487/s, p99 31 ms; 1024: 26,402/s, p99 78 ms; 4096: 38,877/s, p99 172 ms
- sessions/2-A-postgres (Axon): 1: 684/s, p99 3 ms; 4: 2,222/s, p99 4 ms; 16: 4,450/s, p99 6 ms; 64: 4,722/s, p99 23 ms; 256: 4,002/s, p99 251 ms; 1024: 4,664/s, p99 334 ms; 4096: 4,471/s, p99 1114 ms
- sessions/3-A-postgres (Axon): 1: 683/s, p99 3 ms; 4: 2,455/s, p99 3 ms; 16: 4,540/s, p99 6 ms; 64: 4,734/s, p99 23 ms; 256: 4,714/s, p99 103 ms; 1024: 4,592/s, p99 346 ms; 4096: 4,472/s, p99 1147 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 1,119/s, p99 2 ms; 4: 2,495/s, p99 3 ms; 16: 5,285/s, p99 9 ms; 64: 12,351/s, p99 10 ms; 256: 17,369/s, p99 25 ms; 1024: 27,619/s, p99 65 ms; 4096: 38,583/s, p99 195 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 1,097/s, p99 2 ms; 4: 2,480/s, p99 3 ms; 16: 5,994/s, p99 5 ms; 64: 11,548/s, p99 11 ms; 256: 14,562/s, p99 34 ms; 1024: 25,535/s, p99 69 ms; 4096: 35,594/s, p99 226 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 1,085/s, p99 2 ms; 4: 2,490/s, p99 3 ms; 16: 5,976/s, p99 6 ms; 64: 10,870/s, p99 13 ms; 256: 16,414/s, p99 28 ms; 1024: 25,220/s, p99 78 ms; 4096: 35,182/s, p99 285 ms
- pool-32/1-A-postgres (Axon (pool 32)): 1: 902/s, p99 2 ms; 4: 2,501/s, p99 3 ms; 16: 5,450/s, p99 5 ms; 64: 7,852/s, p99 14 ms; 256: 7,742/s, p99 72 ms; 1024: 7,180/s, p99 334 ms; 4096: 7,381/s, p99 1106 ms
- pool-32/2-W-postgres (Wow, Kafka off (pool 32)): 1: 1,018/s, p99 2 ms; 4: 2,331/s, p99 4 ms; 16: 5,963/s, p99 6 ms; 64: 11,982/s, p99 11 ms; 256: 14,485/s, p99 36 ms; 1024: 26,782/s, p99 76 ms; 4096: 37,747/s, p99 184 ms

### E3 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 517/s, p99 3 ms; 4: 1,071/s, p99 7 ms; 16: 2,681/s, p99 11 ms; 64: 4,698/s, p99 26 ms; 256: 5,519/s, p99 71 ms; 1024: 10,501/s, p99 251 ms; 4096: 8,349/s (408574 errors)
- sessions/2-A-postgres (Axon): 1: 214/s, p99 10 ms; 4: 913/s, p99 8 ms; 16: 1,479/s, p99 21 ms; 64: 1,509/s, p99 178 ms; 256: 1,478/s, p99 1253 ms; 1024: 1,398/s, p99 6914 ms; 4096: 1,138/s, p99 5407 ms
- sessions/3-A-postgres (Axon): 1: 317/s, p99 6 ms; 4: 894/s, p99 8 ms; 16: 1,484/s, p99 22 ms; 64: 1,519/s, p99 208 ms; 256: 1,496/s, p99 958 ms; 1024: 1,412/s, p99 5997 ms; 4096: 1,145/s, p99 5472 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 509/s, p99 4 ms; 4: 1,103/s, p99 6 ms; 16: 2,691/s, p99 11 ms; 64: 4,915/s, p99 24 ms; 256: 5,063/s, p99 223 ms; 1024: 10,518/s, p99 276 ms; 4096: 6,970/s (403201 errors)
- sessions/5-K-postgres (Wow, Kafka on): 1: 490/s, p99 4 ms; 4: 1,020/s, p99 7 ms; 16: 2,440/s, p99 13 ms; 64: 4,369/s, p99 29 ms; 256: 5,114/s, p99 225 ms; 1024: 9,944/s, p99 253 ms; 4096: 6,942/s (170874 errors)
- sessions/6-K-postgres (Wow, Kafka on): 1: 502/s, p99 4 ms; 4: 1,077/s, p99 6 ms; 16: 2,441/s, p99 15 ms; 64: 4,417/s, p99 30 ms; 256: 4,814/s, p99 237 ms; 1024: 9,900/s, p99 295 ms; 4096: 7,316/s (340397 errors)
- pool-32/1-A-postgres (Axon (pool 32)): 1: 309/s, p99 6 ms; 4: 880/s, p99 8 ms; 16: 1,731/s, p99 19 ms; 64: 1,974/s, p99 202 ms; 256: 1,994/s, p99 991 ms; 1024: 1,859/s, p99 4194 ms; 4096: 1,587/s, p99 4358 ms
- pool-32/2-W-postgres (Wow, Kafka off (pool 32)): 1: 486/s, p99 4 ms; 4: 1,007/s, p99 7 ms; 16: 2,439/s, p99 14 ms; 64: 4,084/s, p99 35 ms; 256: 5,427/s, p99 75 ms; 1024: 10,536/s, p99 160 ms; 4096: 7,440/s (231866 errors)
