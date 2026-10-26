## large-mongo@100k

E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.

E1-large, uniform keys over 100000 accounts; warm-up 30 s, measured 40 s, 80% of saturation, 2 round(s) + 1 (two runs of one arm differed by more than 5%). Runner: AMD EPYC 7763 64-Core Processor, 4 cores.

Primed: every run touched each of the 100000 accounts once before its warm-up, untimed (56–165 s), then waited until the nodes were quiet again (4–4 s).

| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |
| --- | --- | --- | --- | --- |
| base | 1037.9/s | 2048 | 622.7/s | 60% |
| head | 1419.6/s | 512 | 1135.7/s | 80% |

Open-loop calibration probes (not part of the results):

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| probe1-base | base | 830.3/s | 345.0/s | 6900 | 9706 | 8257.5 ms | 11337.7 ms | 11927.6 ms | n/a | 12845.1 ms | 1.22 cores | kafka 48% · mongo 111% | 0.87 of 1 | server 1.22 | 8.15 | 237.9 KB | http-429-wow.command.busy 7685 · http-504-wow.wait.timeout 1959 · http-503-wow.command.unknown 62 |
| probe2-base | base | 622.7/s | 622.0/s | 12440 | 13 | 4489.2 ms | 7405.6 ms | 8781.8 ms | n/a | 9371.6 ms | 1.21 cores | kafka 47% · mongo 122% | 0.69 of 1 | mongo 1.22 | 4.66 | 338.8 KB | http-504-wow.wait.timeout 13 |
| probe1-head | head | 1135.7/s | 1135.7/s | 22714 | 0 | 7569.4 ms | 8650.8 ms | 9764.9 ms | n/a | 10223.6 ms | 0.86 cores | kafka 53% · mongo 125% | 0.93 of 1 | mongo 1.25 | 2.33 | 221.7 KB | - |

| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-base | base | 622.7/s | 566.8/s | 22673 | 2234 | 5505.0 ms | 9044.0 ms | 9961.5 ms | n/a | 10747.9 ms | 1.30 cores | kafka 41% · mongo 123% | 0.40 of 1 | server 1.30 | 5.18 | 353.5 KB | http-504-wow.wait.timeout 1269 · http-429-wow.command.busy 883 · http-503-wow.command.unknown 80 · http-503-wow.storage.unavailable 2 |
| r1-head | head | 1135.7/s | 1135.7/s | 45428 | 0 | 272.4 ms | 3735.6 ms | 4259.8 ms | n/a | 4685.8 ms | 1.05 cores | kafka 51% · mongo 130% | 0.72 of 1 | mongo 1.30 | 2.52 | 240.9 KB | - |
| r2-head | head | 1135.7/s | 1029.0/s | 41162 | 4266 | 2801.7 ms | 7209.0 ms | 8781.8 ms | n/a | 9699.3 ms | 1.24 cores | kafka 50% · mongo 115% | 0.85 of 1 | server 1.24 | 2.81 | 239.3 KB | http-429-wow.command.busy 4266 |
| r2-base | base | 622.7/s | 528.6/s | 21144 | 3763 | 5963.8 ms | 9371.6 ms | 10027.0 ms | n/a | 10551.3 ms | 1.38 cores | kafka 40% · mongo 117% | 0.41 of 1 | server 1.38 | 5.59 | 364.9 KB | http-429-wow.command.busy 1844 · http-504-wow.wait.timeout 1823 · http-503-wow.command.unknown 96 |
| r3-base | base | 622.7/s | 515.4/s | 20615 | 4292 | 5668.9 ms | 9306.1 ms | 9961.5 ms | n/a | 10289.2 ms | 1.35 cores | kafka 40% · mongo 122% | 0.41 of 1 | server 1.35 | 5.75 | 364.4 KB | http-429-wow.command.busy 2071 · http-504-wow.wait.timeout 2026 · http-503-wow.command.unknown 193 · http-503-wow.storage.unavailable 2 |
| r3-head | head | 1135.7/s | 1031.8/s | 41272 | 4156 | 2752.5 ms | 8093.7 ms | 9306.1 ms | n/a | 10354.7 ms | 1.20 cores | kafka 53% · mongo 117% | 0.85 of 1 | server 1.20 | 2.81 | 241.9 KB | http-429-wow.command.busy 4156 |
| e1-base | base | 622.7/s | 470.3/s | 18812 | 6095 | 6291.5 ms | 9502.7 ms | 10092.5 ms | n/a | 10485.8 ms | 1.32 cores | kafka 40% · mongo 120% | 0.46 of 1 | server 1.32 | 6.22 | 352.2 KB | http-429-wow.command.busy 3561 · http-504-wow.wait.timeout 2490 · http-503-wow.command.unknown 42 · http-503-wow.storage.unavailable 2 |
| e1-head | head | 622.7/s | 622.7/s | 24907 | 0 | 8.3 ms | 81.4 ms | 176.1 ms | n/a | 360.4 ms | 0.76 cores | kafka 51% · mongo 134% | 0.34 of 1 | mongo 1.34 | 4.19 | 256.7 KB | - |
| e2-head | head | 622.7/s | 622.7/s | 24907 | 0 | 7.1 ms | 55.3 ms | 122.9 ms | n/a | 237.6 ms | 0.78 cores | kafka 53% · mongo 129% | 0.34 of 1 | mongo 1.29 | 4.19 | 258.3 KB | - |
| e2-base | base | 622.7/s | 416.5/s | 16660 | 8247 | 6815.7 ms | 9764.9 ms | 10289.2 ms | n/a | 10682.4 ms | 1.31 cores | kafka 42% · mongo 121% | 0.41 of 1 | server 1.31 | 7.06 | 347.9 KB | http-429-wow.command.busy 4656 · http-504-wow.wait.timeout 3507 · http-503-wow.command.unknown 82 · http-503-wow.storage.unavailable 2 |

Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.004 | 0.049 | 0.413 | 0.428 | - | 0.436 | 0.487 | 0.681 |
| 2 | 1.947 | 0.470 | 0.769 | 0.876 | - | 0.919 | 0.504 | 0.656 |
| 3 | 2.002 | 0.486 | 0.870 | 0.934 | - | 1.006 | 0.488 | 0.664 |

Pairs at one rate for both arms, 622.7/s (latencies compare; lower is better):

| Round | Throughput head/base | p50 head/base | p95 head/base | p99 head/base | p999 head/base | Worst-second p99 head/base | CPU ms/op head/base | Alloc/op head/base |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1.324 | 0.001 | 0.009 | 0.017 | - | 0.034 | 0.674 | 0.729 |
| 2 | 1.495 | 0.001 | 0.006 | 0.012 | - | 0.022 | 0.593 | 0.742 |

Anomalies:

| Suite | Run | Context | Anomaly | Detail |
| --- | --- | --- | --- | --- |
| large-mongo@100k | r1-base | steady | **unstable** | second half against first, beyond the default limits: throughput -12.6% (±5.0%) |
| large-mongo@100k | r1-base | steady | **error spike** | 2234 of 24907 failed: http-504-wow.wait.timeout 1269 · http-429-wow.command.busy 883 · http-503-wow.command.unknown 80 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | r1-base | steady | **throughput dip** | 1 s below half the rate (lowest 282/s) |
| large-mongo@100k | r1-head | steady | **unstable** | second half against first, beyond the default limits: p50 -94.9% (±25.0%) |
| large-mongo@100k | r1-head | steady | **p99 > 10× p50** | p50 272.4 ms, p99 4259.8 ms |
| large-mongo@100k | r2-head | steady | **unstable** | second half against first, beyond the default limits: throughput +23.1% (±5.0%), p50 -55.5% (±25.0%) |
| large-mongo@100k | r2-head | steady | **error spike** | 4266 of 45428 failed: http-429-wow.command.busy 4266 |
| large-mongo@100k | r2-head | steady | **throughput dip** | 3 s below half the rate (lowest 21/s) |
| large-mongo@100k | r2-base | steady | **error spike** | 3763 of 24907 failed: http-429-wow.command.busy 1844 · http-504-wow.wait.timeout 1823 · http-503-wow.command.unknown 96 |
| large-mongo@100k | r2-base | steady | **throughput dip** | 2 s below half the rate (lowest 176/s) |
| large-mongo@100k | r3-base | steady | **unstable** | second half against first, beyond the default limits: throughput +5.1% (±5.0%) |
| large-mongo@100k | r3-base | steady | **error spike** | 4292 of 24907 failed: http-429-wow.command.busy 2071 · http-504-wow.wait.timeout 2026 · http-503-wow.command.unknown 193 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | r3-base | steady | **throughput dip** | 1 s below half the rate (lowest 262/s) |
| large-mongo@100k | r3-head | steady | **unstable** | second half against first, beyond the default limits: throughput +22.4% (±5.0%), p50 -64.3% (±25.0%) |
| large-mongo@100k | r3-head | steady | **error spike** | 4156 of 45428 failed: http-429-wow.command.busy 4156 |
| large-mongo@100k | r3-head | steady | **throughput dip** | 3 s below half the rate (lowest 63/s) |
| large-mongo@100k | e1-base | steady | **unstable** | second half against first, beyond the default limits: throughput +48.0% (±5.0%) |
| large-mongo@100k | e1-base | steady | **error spike** | 6095 of 24907 failed: http-429-wow.command.busy 3561 · http-504-wow.wait.timeout 2490 · http-503-wow.command.unknown 42 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | e1-base | steady | **throughput dip** | 6 s below half the rate (lowest 213/s) |
| large-mongo@100k | e1-head | steady | **p99 > 10× p50** | p50 8.3 ms, p99 176.1 ms |
| large-mongo@100k | e2-head | steady | **unstable** | second half against first, beyond the default limits: p50 -25.4% (±25.0%) |
| large-mongo@100k | e2-head | steady | **p99 > 10× p50** | p50 7.1 ms, p99 122.9 ms |
| large-mongo@100k | e2-base | steady | **unstable** | second half against first, beyond the default limits: throughput +10.5% (±5.0%) |
| large-mongo@100k | e2-base | steady | **error spike** | 8247 of 24907 failed: http-429-wow.command.busy 4656 · http-504-wow.wait.timeout 3507 · http-503-wow.command.unknown 82 · http-503-wow.storage.unavailable 2 |
| large-mongo@100k | e2-base | steady | **throughput dip** | 7 s below half the rate (lowest 164/s) |

