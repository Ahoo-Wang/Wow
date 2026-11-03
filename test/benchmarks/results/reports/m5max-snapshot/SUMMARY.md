# m5max-snapshot: measurements

```text
## 01-single-node.sh 2026-11-02T23:27:51-0800
commit: b435768e4979f146e5c52142410f87a06b941861
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 67826
  67826   0.0 2037680 Mon Nov  2 18:38:20 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
## 02-three-nodes.sh 2026-11-03T00:25:16-0800
commit: b435768e4979f146e5c52142410f87a06b941861
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 67826
```

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-S-mongo/e1-hot-sweep | 1,634.7/s at 1,024 | 1,456.7/s at 64 | 4,096: 49.7% (543,008 × 429 wow.command.busy) | 18,301.3/s at 4,096, errors 543,008 × http-429-wow.command.busy | none | none | none | 3.70 6.91 8.65 | 16.44 8.06 8.35 |
| 1-S-mongo/e1-hot-top | – | none | 4,096: 57.7% (656,829 × 429 wow.command.busy, 1,890 × 504 wow.wait.timeout) | 16,087.4/s at 4,096, errors 656,829 × http-429-wow.command.busy, 1,890 × http-504-wow.wait.timeout | none | none | none | 16.44 8.06 8.35 | 19.35 10.11 9.09 |
| 1-S-mongo/e1-sweep | ≥ 22,886.7/s at 4,096 | 19,580.4/s at 1,024 | none | 22,886.7/s at 4,096, errors 0 | none | none | none | 11.52 10.88 9.94 | 19.50 12.22 10.44 |
| 1-S-mongo/e1-top | 21,949.7/s at 4,096 | none | 8,192: 8.3% (56,003 × 429 wow.command.busy) | 20,601.2/s at 8,192, errors 56,003 × http-429-wow.command.busy | none | none | none | 19.50 12.22 10.44 | 17.26 13.34 11.03 |
| 1-S-mongo/e1-zipf-sweep | 3,898.2/s at 256 | 2,819.9/s at 64 | 4,096: 13.3% (30,749 × 429 wow.command.busy) | 6,667.6/s at 4,096, errors 30,749 × http-429-wow.command.busy | none | none | none | 17.26 13.34 11.03 | 3.90 7.46 8.94 |
| 1-S-mongo/e1-zipf-top | – | none | 4,096: 13.3% (32,028 × 429 wow.command.busy) | 6,982.9/s at 4,096, errors 32,028 × http-429-wow.command.busy | none | none | none | 3.90 7.46 8.94 | 3.70 6.91 8.65 |
| 1-S-mongo/e3-sweep | 7,629.5/s at 1,024 | 5,100.5/s at 256 | 4,096: 74.6% (311,424 × 429 wow.command.busy, 1,493 × 504 wow.wait.timeout) | 3,547.0/s at 4,096, errors 311,424 × http-429-wow.command.busy, 1,493 × http-504-wow.wait.timeout | none | none | none | 19.35 10.11 9.09 | 18.79 11.96 9.85 |
| 1-S-postgres/e1-hot-sweep | 12,351.4/s at 1,024 | 8,000.2/s at 256 | 4,096: 48.9% (681,803 × 429 wow.command.busy) | 23,768.6/s at 4,096, errors 681,803 × http-429-wow.command.busy | none | none | none | 7.54 11.42 10.17 | 13.76 9.25 9.09 |
| 1-S-postgres/e1-hot-top | – | none | 4,096: 49.0% (696,339 × 429 wow.command.busy) | 24,142.2/s at 4,096, errors 696,339 × http-429-wow.command.busy | none | none | none | 13.76 9.25 9.09 | 19.29 11.35 9.88 |
| 1-S-postgres/e1-sweep | ≥ 31,484.9/s at 4,096 | 24,541.9/s at 1,024 | none | 31,484.9/s at 4,096, errors 0 | none | none | none | 7.96 9.57 5.67 | 22.14 13.61 8.24 |
| 1-S-postgres/e1-sweep.void-1 | ≥ 34,878.5/s at 4,096 | 26,854.6/s at 1,024 | none | 34,878.5/s at 4,096, errors 0 | none | none (an attempt set aside: void by the rule in force when it was measured, the script's log) | none | 12.10 4.77 2.58 | 21.53 11.57 5.99 |
| 1-S-postgres/e1-top | 32,303.0/s at 4,096 | none | 8,192: 2.1% (13,373 × 429 wow.command.busy) | 20,823.6/s at 8,192, errors 13,373 × http-429-wow.command.busy | none | none | none | 22.14 13.61 8.24 | 18.17 14.95 9.30 |
| 1-S-postgres/e1-zipf-sweep | 15,112.4/s at 1,024 | 15,063.1/s at 256 | 4,096: 13.8% (95,019 × 429 wow.command.busy) | 19,754.8/s at 4,096, errors 95,019 × http-429-wow.command.busy | none | none | none | 18.17 14.95 9.30 | 26.29 17.35 11.61 |
| 1-S-postgres/e1-zipf-top | – | none | 4,096: 13.5% (82,969 × 429 wow.command.busy) | 17,714.5/s at 4,096, errors 82,969 × http-429-wow.command.busy | none | none | none | 26.29 17.35 11.61 | 7.54 11.42 10.17 |
| 1-S-postgres/e3-sweep | 7,176.3/s at 1,024 | 3,799.1/s at 64 | 4,096: 75.6% (399,748 × 429 wow.command.busy, 1,719 × 504 wow.wait.timeout) | 4,325.8/s at 4,096, errors 399,748 × http-429-wow.command.busy, 1,719 × http-504-wow.wait.timeout | none | none | none | 19.29 11.35 9.88 | 13.87 11.24 10.00 |
| 3-N-mongo/e1-c1024 | 14,395.6/s at 1,024 (one step) | none | none | 14,395.6/s at 1,024, errors 0 | none | none | none | 34.75 24.65 20.94 | 38.90 27.80 22.44 |
| 3-N-mongo/e1-sweep | 15,341.3/s at 1,024 | 15,341.3/s at 1,024 | none | 6,580.8/s at 4,096, errors 0 | none | none | none | 29.50 26.13 19.89 | 32.79 24.69 20.35 |
| 3-N-mongo/e3-c1024 | 4,892.3/s at 1,024 (one step) | none | none | 4,892.3/s at 1,024, errors 0 | none | none | none | 38.90 27.80 22.44 | 36.65 29.53 23.57 |
| 3-N-mongo/e3-sweep | 5,154.1/s at 1,024 | 4,608.0/s at 256 | none | 2,087.7/s at 4,096, errors 163 × http-504-wow.wait.timeout | none | none | none | 32.79 24.69 20.35 | 34.75 24.65 20.94 |
| 3-N-postgres/e1-c1024 | 18,319.9/s at 1,024 (one step) | 18,319.9/s at 1,024 | none | 18,319.9/s at 1,024, errors 0 | none | none | none | 29.71 24.74 17.63 | 27.51 24.95 18.33 |
| 3-N-postgres/e1-sweep | 19,274.8/s at 1,024 | 19,274.8/s at 1,024 | 4,096: 10.2% (24,075 × 429 wow.command.busy, 9,366 × 504 wow.wait.timeout, 135 × 503 wow.command.unknown) | 9,837.1/s at 4,096, errors 24,075 × http-429-wow.command.busy, 9,366 × http-504-wow.wait.timeout, 135 × http-503-wow.command.unknown | none | none | none | 25.56 14.30 10.84 | 31.61 20.80 14.26 |
| 3-N-postgres/e3-c1024 | 5,893.5/s at 1,024 (one step) | none | none | 5,893.5/s at 1,024, errors 0 | none | none | none | 27.51 24.95 18.33 | 31.30 26.57 19.53 |
| 3-N-postgres/e3-sweep | 6,470.6/s at 1,024 | 5,770.0/s at 256 | 4,096: 1.4% (1,123 × 504 wow.wait.timeout, 2 × 409 wow.command.contended) | 2,665.9/s at 4,096, errors 1,123 × http-504-wow.wait.timeout, 2 × http-409-wow.command.contended | none | none | none | 31.61 20.80 14.26 | 29.71 24.74 17.63 |

The allowed JVMs (environment.txt) over 23 measurements, 211 samples: mean 0.1%, max 24.9% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.
