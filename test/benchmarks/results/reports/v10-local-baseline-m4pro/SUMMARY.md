# Rerun Benchmark Summary (Apple M4 Pro 14 Cores)

## Environment
| Property | Value |
| :--- | :--- |
| Machine | MacBook Pro, Apple M4 Pro (14 cores: 10P + 4E), 24 GB RAM |
| OS | macOS 27.0.1 (Build 26A434) |
| Docker Desktop | 4.96.3 (244472), Engine: 29.9.2 |
| Docker Resources | 12 CPUs, 15.6 GiB (~16 GB) RAM |
| Disk Image & Virtualization | `Docker.raw` (ext4 with discard on /dev/vda1), VirtioFS file sharing |
| JDK | OpenJDK 21.0.13 (Temurin-21.0.13+9-LTS) |
| Accounts | 100,000 primed accounts, no JFR |

## Stage 0: A/A Noise (PostgreSQL E1)
| Session, Run | Throughput | OK | Failed | p50 (ms) | p99 (ms) | Max (ms) | Server CPU (ms/op) | Alloc (KB/op) | WAL Max Flush Time (ms) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1, 1 | 6346.0/s | 380760 | 0 | 22.27 | 284.67 | 798.2 | 0.448 | 100.3 | **898** |
| 1, 2 | 6340.6/s | 380435 | 325 (wow.command.busy) | 43.77 | 663.55 | 1550.0 | 0.400 | 104.1 | **987** |
| 2, 1 | 6346.0/s | 380760 | 0 | 4.00 | 76.80 | 372.2 | 0.511 | 81.3 | **961** |
| 2, 2 | 6346.0/s | 380760 | 0 | 7.58 | 319.49 | 743.4 | 0.460 | 84.2 | **898** |
| 3, 1 | 6335.4/s | 380123 | 637 (wow.command.busy) | 4.09 | 247.81 | 928.0 | 0.469 | 81.6 | **1190** |
| 3, 2 | 6346.0/s | 380760 | 0 | 2.88 | 41.22 | 294.4 | 0.515 | 83.6 | **1032** |

## Stage 1: Cost and Saturation
### Closed-Loop Sweeps Peak
| Scenario | Sweep Max Concurrency | Peak Throughput | Concurrency at Peak | Mark |
| :--- | :--- | :--- | :--- | :--- |
| postgres E1 uniform | 1024 | 11232.6/s | 1024 | ≥ |
| postgres E1 zipf | 512 | 4512.2/s | 32 |  |
| postgres E3 uniform | 512 | 1707.1/s | 512 | ≥ |
| mongo E1 uniform | 1024 | 8856.2/s | 256 |  |
| mongo E1 zipf | 512 | 3469.1/s | 256 |  |
| mongo E3 uniform | 512 | 1823.4/s | 256 |  |

### Open-Loop Measurements
| Scenario | Share | Target Rate | Throughput | OK | Failed | p50 (ms) | p99 (ms) | Max (ms) | Server CPU (ms/op) | Store Max Latency (ms) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| postgres E1 uniform | 0.25 | 2808/s | 2804.3/s | 168257 | 223 (wow.command.busy) | 2.22 | 1097.73 | 1787.3 | 0.578 | **WAL: 3223 (exceed 500ms)** |
| postgres E1 uniform | 0.5 | 5616/s | 5589.0/s | 335342 | 1618 (wow.command.busy) | 28.80 | 598.01 | 1256.1 | 0.418 | **WAL: 1032 (exceed 500ms)** |
| postgres E1 uniform | 0.75 | 8424/s | 8383.0/s | 502983 | 2457 (wow.command.busy) | 111.10 | 626.69 | 1522.5 | 0.312 | **WAL: 744 (exceed 500ms)** |
| postgres E1 zipf | 0.25 | 1128/s | 1128.0/s | 67680 | 0 | 0.80 | 518.14 | 845.3 | 0.582 | **WAL: 1198 (exceed 500ms)** |
| postgres E1 zipf | 0.5 | 2256/s | 2174.6/s | 130475 | 4885 (wow.command.busy) | 1.30 | 3686.40 | 4505.5 | 0.529 | **WAL: 4146 (exceed 500ms)** |
| postgres E1 zipf | 0.75 | 3384/s | 3224.4/s | 193464 | 9576 (wow.command.busy) | 1.70 | 4096.00 | 7418.9 | 0.530 | **WAL: 3532 (exceed 500ms)** |
| postgres E3 uniform | 0.25 | 427/s | 427.0/s | 25620 | 0 | 2.80 | 1343.49 | 1982.8 | 1.722 | **WAL: 1955 (exceed 500ms)** |
| postgres E3 uniform | 0.5 | 854/s | 854.0/s | 51240 | 0 | 5.50 | 1097.73 | 1855.8 | 1.636 | **WAL: 1395 (exceed 500ms)** |
| postgres E3 uniform | 0.75 | 1280/s | 1254.7/s | 75282 | 1518 (wow.command.busy) | 129.53 | 3850.24 | 5654.5 | 1.191 | **WAL: 1888 (exceed 500ms)** |
| mongo E1 uniform | 0.25 | 2214/s | 2214.0/s | 132840 | 0 | 2.24 | 95.74 | 299.3 | 0.679 | Chk: 0 |
| mongo E1 uniform | 0.5 | 4428/s | 4428.0/s | 265680 | 0 | 7.49 | 327.68 | 1000.1 | 0.527 | Chk: 0 |
| mongo E1 uniform | 0.75 | 6642/s | 6642.0/s | 398520 | 0 | 40.45 | 292.86 | 669.6 | 0.334 | Chk: 0 |
| mongo E1 zipf | 0.25 | 867/s | 867.0/s | 52020 | 0 | 2.01 | 2703.36 | 3338.3 | 0.913 | Chk: 0 |
| mongo E1 zipf | 0.5 | 1735/s | 1735.0/s | 104100 | 0 | 1.28 | 57.09 | 490.4 | 0.642 | Chk: 0 |
| mongo E1 zipf | 0.75 | 2602/s | 2500.2/s | 150009 | 6111 (wow.command.busy) | 4.45 | 5144.57 | 5641.1 | 0.517 | Chk: 0 |
| mongo E3 uniform | 0.25 | 456/s | 456.0/s | 27360 | 0 | 7.84 | 2916.35 | 3586.6 | 2.539 | Chk: 0 |
| mongo E3 uniform | 0.5 | 912/s | 912.0/s | 54720 | 0 | 10.11 | 197.63 | 368.0 | 1.759 | Chk: 0 |
| mongo E3 uniform | 0.75 | 1368/s | 1327.6/s | 79655 | 2425 (wow.command.busy) | 75.78 | 4521.98 | 6221.7 | 1.202 | Chk: 0 |

## Stage 2: ABAB 1 GB vs 2 GB Heap Comparison
### ABAB Sweeps and Constant Rate Runs
| Backend | Session | E1 Peak | E3 Peak | E1 Run (p50 / p99) | E3 Run (p50 / p99) | E1 Fails | E3 Fails | Store Max (WAL/Chk ms) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| postgres | 1g-1 | 12184.0/s | 2855.1/s | 125.44 / 954.37 ms | 17.54 / 5046.27 ms | 3423 (wow.command.busy) | 3523 (wow.command.busy) | WAL: 2523 |
| postgres | 2g-1 | 18590.8/s | 4390.5/s | 31.74 / 1572.86 ms | 16.77 / 2523.14 ms | 10210 (wow.command.busy) | 272 (wow.command.busy) | WAL: 2131 |
| postgres | 1g-2 | 12455.6/s | 2697.8/s | 292.86 / 1122.30 ms | 13.12 / 444.42 ms | 8806 (wow.command.busy) | 0 | WAL: 899 |
| postgres | 2g-2 | 19112.7/s | 4730.5/s | 14.14 / 3964.93 ms | 16.00 / 3915.78 ms | 26066 (wow.command.busy) | 2008 (wow.command.busy) | WAL: 4909 |
| mongo | 1g-1 | 9457.9/s | 2526.1/s | 368.64 / 1875.97 ms | 11.58 / 240.64 ms | 9527 (wow.command.busy) | 0 | Chk: 5288 |
| mongo | 2g-1 | 13922.9/s | 4269.9/s | 5.15 / 268.29 ms | 6.72 / 1736.70 ms | 0 | 0 | Chk: 1978 |
| mongo | 1g-2 | 10459.8/s | 2429.4/s | 146.43 / 790.53 ms | 11.52 / 172.03 ms | 0 | 0 | Chk: 1968 |
| mongo | 2g-2 | 14170.8/s | 4343.8/s | 7.04 / 2359.30 ms | 4.99 / 243.71 ms | 8805 (wow.command.busy) | 0 | Chk: 3743 |

### ABAB 2g / 1g Ratios
| Backend | Round | Metric | 1g Value | 2g Value | Ratio (2g / 1g) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| postgres | Round 1 | E1 Sweep Peak | 12184.0/s | 18590.8/s | 1.526 |
| postgres | Round 1 | E3 Sweep Peak | 2855.1/s | 4390.5/s | 1.538 |
| postgres | Round 1 | E1 Run Throughput | 6034.9/s | 5921.8/s | 0.981 |
| postgres | Round 1 | E1 Run p50 | 125.44 ms | 31.74 ms | 0.253 |
| postgres | Round 1 | E1 Run p99 | 954.37 ms | 1572.86 ms | 1.648 |
| postgres | Round 1 | E3 Run Throughput | 1369.3/s | 1423.5/s | 1.040 |
| postgres | Round 1 | E3 Run p50 | 17.54 ms | 16.77 ms | 0.956 |
| postgres | Round 1 | E3 Run p99 | 5046.27 ms | 2523.14 ms | 0.500 |
| postgres | Round 2 | E1 Sweep Peak | 12455.6/s | 19112.7/s | 1.534 |
| postgres | Round 2 | E3 Sweep Peak | 2697.8/s | 4730.5/s | 1.753 |
| postgres | Round 2 | E1 Run Throughput | 5945.2/s | 5657.6/s | 0.952 |
| postgres | Round 2 | E1 Run p50 | 292.86 ms | 14.14 ms | 0.048 |
| postgres | Round 2 | E1 Run p99 | 1122.30 ms | 3964.93 ms | 3.533 |
| postgres | Round 2 | E3 Run Throughput | 1428.0/s | 1394.5/s | 0.977 |
| postgres | Round 2 | E3 Run p50 | 13.12 ms | 16.00 ms | 1.220 |
| postgres | Round 2 | E3 Run p99 | 444.42 ms | 3915.78 ms | 8.811 |
| mongo | Round 1 | E1 Sweep Peak | 9457.9/s | 13922.9/s | 1.472 |
| mongo | Round 1 | E3 Sweep Peak | 2526.1/s | 4269.9/s | 1.690 |
| mongo | Round 1 | E1 Run Throughput | 4570.2/s | 4729.0/s | 1.035 |
| mongo | Round 1 | E1 Run p50 | 368.64 ms | 5.15 ms | 0.014 |
| mongo | Round 1 | E1 Run p99 | 1875.97 ms | 268.29 ms | 0.143 |
| mongo | Round 1 | E3 Run Throughput | 1263.0/s | 1263.0/s | 1.000 |
| mongo | Round 1 | E3 Run p50 | 11.58 ms | 6.72 ms | 0.580 |
| mongo | Round 1 | E3 Run p99 | 240.64 ms | 1736.70 ms | 7.217 |
| mongo | Round 2 | E1 Sweep Peak | 10459.8/s | 14170.8/s | 1.355 |
| mongo | Round 2 | E3 Sweep Peak | 2429.4/s | 4343.8/s | 1.788 |
| mongo | Round 2 | E1 Run Throughput | 4729.0/s | 4582.2/s | 0.969 |
| mongo | Round 2 | E1 Run p50 | 146.43 ms | 7.04 ms | 0.048 |
| mongo | Round 2 | E1 Run p99 | 790.53 ms | 2359.30 ms | 2.984 |
| mongo | Round 2 | E3 Run Throughput | 1263.0/s | 1263.0/s | 1.000 |
| mongo | Round 2 | E3 Run p50 | 11.52 ms | 4.99 ms | 0.433 |
| mongo | Round 2 | E3 Run p99 | 172.03 ms | 243.71 ms | 1.417 |

## Stage 3: L1 Component Scaling
| Backend | Scenario | Concurrency | Throughput | Round Trips / op | Alloc (KB/op) | p50 (ms) | p99 (ms) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| postgres | E1 | 16 | 5647.5/s | 2.62 | 78.1 | 2.82 | 4.59 |
| postgres | E1 | 64 | 6742.2/s | 2.06 | 76.8 | 9.10 | 18.41 |
| postgres | E1 | 256 | 8710.1/s | 1.37 | 76.3 | 29.49 | 52.22 |
| postgres | E3 | 16 | 2571.8/s | 5.29 | 195.2 | 6.00 | 10.86 |
| postgres | E3 | 64 | 2944.0/s | 3.99 | 198.8 | 21.19 | 36.39 |
| postgres | E3 | 256 | 4757.5/s | 1.97 | 200.0 | 53.11 | 81.24 |
| mongo | E1 | 16 | 4647.9/s | 3.05 | 173.7 | 3.29 | 7.70 |
| mongo | E1 | 64 | 7039.2/s | 2.76 | 165.5 | 8.54 | 17.93 |
| mongo | E1 | 256 | 10715.9/s | 2.02 | 143.6 | 22.79 | 49.02 |
| mongo | E3 | 16 | 2309.1/s | 5.32 | 359.5 | 6.39 | 13.29 |
| mongo | E3 | 64 | 3629.7/s | 4.48 | 334.8 | 17.09 | 33.31 |
| mongo | E3 | 256 | 6307.0/s | 2.55 | 268.4 | 39.31 | 66.87 |
