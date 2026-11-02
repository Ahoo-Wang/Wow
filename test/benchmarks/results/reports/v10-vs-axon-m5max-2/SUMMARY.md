
### E1 at 2,500/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.47 ms | 4.4 ms | 10.4 ms | 0.447 ms | 0.230 ms | 1.66 |
| sessions/2-A-postgres | Axon | 2,500/s | 0 | 2.21 ms | 130.0 ms | 184.3 ms | 0.476 ms | 0.285 ms | 1.59 |
| sessions/3-A-postgres | Axon | 2,500/s | 0 | 1.83 ms | 19.7 ms | 31.0 ms | 0.515 ms | 0.330 ms | 1.89 |
| sessions/4-W-postgres | Wow, Kafka off | 2,500/s | 0 | 1.50 ms | 4.9 ms | 19.2 ms | 0.446 ms | 0.234 ms | 1.66 |
| sessions/5-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.53 ms | 5.5 ms | 15.8 ms | 0.559 ms | 0.241 ms | 1.62 |
| sessions/6-K-postgres | Wow, Kafka on | 2,500/s | 0 | 1.55 ms | 4.9 ms | 11.9 ms | 0.601 ms | 0.242 ms | 1.62 |
| pool-32/1-A-postgres | Axon (pool 32) | 2,500/s | 0 | 1.81 ms | 17.3 ms | 30.3 ms | 0.515 ms | 0.327 ms | 1.88 |
| pool-32/2-W-postgres | Wow, Kafka off (pool 32) | 2,500/s | 0 | 1.48 ms | 4.7 ms | 15.3 ms | 0.449 ms | 0.235 ms | 1.65 |

### E3 at 700/s

| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| sessions/1-W-postgres | Wow, Kafka off | 700/s | 0 | 2.22 ms | 7.4 ms | 18.3 ms | 1.286 ms | 0.694 ms | 5.74 |
| sessions/2-A-postgres | Axon | 700/s | 0 | 3.74 ms | 18.4 ms | 32.3 ms | 1.251 ms | 1.025 ms | 6.67 |
| sessions/3-A-postgres | Axon | 700/s | 0 | 3.77 ms | 19.7 ms | 33.3 ms | 1.242 ms | 1.049 ms | 6.60 |
| sessions/4-W-postgres | Wow, Kafka off | 700/s | 0 | 2.43 ms | 60.7 ms | 172.0 ms | 1.273 ms | 0.687 ms | 5.49 |
| sessions/5-K-postgres | Wow, Kafka on | 700/s | 0 | 2.53 ms | 8.6 ms | 84.5 ms | 1.598 ms | 0.727 ms | 5.66 |
| sessions/6-K-postgres | Wow, Kafka on | 700/s | 0 | 2.46 ms | 8.3 ms | 24.2 ms | 1.715 ms | 0.725 ms | 5.65 |
| pool-32/1-A-postgres | Axon (pool 32) | 700/s | 0 | 3.73 ms | 17.5 ms | 32.0 ms | 1.251 ms | 1.071 ms | 6.74 |
| pool-32/2-W-postgres | Wow, Kafka off (pool 32) | 700/s | 0 | 2.30 ms | 6.8 ms | 14.8 ms | 1.300 ms | 0.718 ms | 5.70 |

### E1 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 1,110/s, p99 2 ms; 4: 2,501/s, p99 3 ms; 16: 6,186/s, p99 5 ms; 64: 11,643/s, p99 12 ms; 256: 16,821/s, p99 28 ms; 1024: 26,446/s, p99 71 ms; 4096: 35,491/s, p99 229 ms
- sessions/2-A-postgres (Axon): 1: 790/s, p99 3 ms; 4: 2,278/s, p99 3 ms; 16: 4,189/s, p99 7 ms; 64: 4,345/s, p99 39 ms; 256: 4,427/s, p99 87 ms; 1024: 4,414/s, p99 303 ms; 4096: 4,221/s, p99 1851 ms
- sessions/3-A-postgres (Axon): 1: 607/s, p99 4 ms; 4: 2,193/s, p99 4 ms; 16: 4,142/s, p99 7 ms; 64: 4,350/s, p99 37 ms; 256: 4,403/s, p99 102 ms; 1024: 4,070/s, p99 569 ms; 4096: 3,229/s, p99 2228 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 1,101/s, p99 2 ms; 4: 2,511/s, p99 3 ms; 16: 6,100/s, p99 5 ms; 64: 12,125/s, p99 11 ms; 256: 15,960/s, p99 30 ms; 1024: 27,544/s, p99 63 ms; 4096: 36,366/s, p99 212 ms
- sessions/5-K-postgres (Wow, Kafka on): 1: 1,070/s, p99 2 ms; 4: 2,442/s, p99 3 ms; 16: 5,305/s, p99 8 ms; 64: 8,963/s, p99 18 ms; 256: 14,239/s, p99 37 ms; 1024: 24,349/s, p99 80 ms; 4096: 32,762/s, p99 332 ms
- sessions/6-K-postgres (Wow, Kafka on): 1: 1,072/s, p99 1 ms; 4: 2,439/s, p99 3 ms; 16: 5,786/s, p99 6 ms; 64: 11,342/s, p99 11 ms; 256: 15,145/s, p99 31 ms; 1024: 24,518/s, p99 75 ms; 4096: 32,408/s, p99 295 ms
- pool-32/1-A-postgres (Axon (pool 32)): 1: 775/s, p99 3 ms; 4: 2,308/s, p99 3 ms; 16: 4,995/s, p99 5 ms; 64: 7,175/s, p99 30 ms; 256: 6,984/s, p99 76 ms; 1024: 7,180/s, p99 289 ms; 4096: 6,384/s, p99 1237 ms
- pool-32/2-W-postgres (Wow, Kafka off (pool 32)): 1: 1,110/s, p99 1 ms; 4: 2,468/s, p99 3 ms; 16: 6,082/s, p99 5 ms; 64: 11,602/s, p99 14 ms; 256: 13,881/s, p99 176 ms; 1024: 25,793/s, p99 113 ms; 4096: 35,569/s, p99 211 ms

### E3 closed loop

- sessions/1-W-postgres (Wow, Kafka off): 1: 508/s, p99 4 ms; 4: 1,090/s, p99 6 ms; 16: 2,661/s, p99 11 ms; 64: 4,836/s, p99 24 ms; 256: 5,600/s, p99 81 ms; 1024: 10,373/s, p99 303 ms; 4096: 7,035/s (312602 errors)
- sessions/2-A-postgres (Axon): 1: 297/s, p99 6 ms; 4: 886/s, p99 8 ms; 16: 1,333/s, p99 28 ms; 64: 1,478/s, p99 175 ms; 256: 1,382/s, p99 1507 ms; 1024: 1,216/s, p99 5997 ms; 4096: 1,104/s, p99 5308 ms
- sessions/3-A-postgres (Axon): 1: 231/s, p99 9 ms; 4: 880/s, p99 8 ms; 16: 1,458/s, p99 23 ms; 64: 889/s, p99 330 ms; 256: 1,423/s, p99 1393 ms; 1024: 1,299/s, p99 4522 ms; 4096: 1,090/s, p99 5833 ms
- sessions/4-W-postgres (Wow, Kafka off): 1: 502/s, p99 4 ms; 4: 1,087/s, p99 7 ms; 16: 2,635/s, p99 12 ms; 64: 4,721/s, p99 25 ms; 256: 5,515/s, p99 78 ms; 1024: 10,707/s, p99 165 ms; 4096: 5,825/s (398593 errors)
- sessions/5-K-postgres (Wow, Kafka on): 1: 499/s, p99 3 ms; 4: 1,014/s, p99 11 ms; 16: 2,370/s, p99 16 ms; 64: 3,799/s, p99 39 ms; 256: 5,409/s, p99 82 ms; 1024: 9,936/s, p99 164 ms; 4096: 6,133/s (373705 errors)
- sessions/6-K-postgres (Wow, Kafka on): 1: 420/s, p99 8 ms; 4: 1,045/s, p99 7 ms; 16: 1,463/s, p99 29 ms; 64: 4,464/s, p99 25 ms; 256: 5,255/s, p99 105 ms; 1024: 9,761/s, p99 214 ms; 4096: 4,867/s (354422 errors)
- pool-32/1-A-postgres (Axon (pool 32)): 1: 256/s, p99 9 ms; 4: 759/s, p99 13 ms; 16: 1,667/s, p99 27 ms; 64: 2,101/s, p99 134 ms; 256: 1,594/s, p99 1753 ms; 1024: 1,736/s, p99 2802 ms; 4096: 1,511/s, p99 4915 ms
- pool-32/2-W-postgres (Wow, Kafka off (pool 32)): 1: 510/s, p99 4 ms; 4: 1,090/s, p99 6 ms; 16: 2,661/s, p99 12 ms; 64: 4,590/s, p99 28 ms; 256: 5,617/s, p99 70 ms; 1024: 10,401/s, p99 243 ms; 4096: 7,961/s (332100 errors)
