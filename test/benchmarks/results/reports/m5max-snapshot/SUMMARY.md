# m5max-snapshot: measurements

```text
## 01-single-node.sh 2026-11-11T23:39:31-0800
commit: d9b0781205aa5749bf7e3941dfcbb622c2eaac36
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 10510 10555 64878 94957
  10510   0.0 1661824 Wed Nov 11 23:13:33 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
  10555   0.0 2685312 Wed Nov 11 23:13:42 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  64878   0.0 4611712 Wed Nov 11 20:01:25 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  94957   0.0 2190048 Wed Nov 11 21:36:27 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
## 02-three-nodes.sh 2026-11-12T00:30:49-0800
commit: d9b0781205aa5749bf7e3941dfcbb622c2eaac36
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 10510 10555 64878 94957
  10510   0.0 666640 Wed Nov 11 23:13:33 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invo
  10555   0.0 554960 Wed Nov 11 23:13:42 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotl
  64878   0.0 3496496 Wed Nov 11 20:01:25 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  94957   0.0 1316400 Wed Nov 11 21:36:27 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
```

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-S-mongo/e1-hot-sweep | 6,911.2/s at 1,024 | 5,417.2/s at 64 | 4,096: 49.5% (548,113 × 429 wow.command.busy) | 18,645.7/s at 4,096, errors 548,113 × http-429-wow.command.busy | none | none | none | 12.18 11.67 11.16 | 23.15 11.44 10.56 |
| 1-S-mongo/e1-hot-top | – | none | 4,096: 49.5% (527,698 × 429 wow.command.busy) | 17,971.2/s at 4,096, errors 527,698 × http-429-wow.command.busy | none | none | none | 23.15 11.44 10.56 | 26.70 13.96 11.53 |
| 1-S-mongo/e1-sweep | ≥ 21,775.2/s at 4,096 | 19,759.6/s at 1,024 | none | 21,775.2/s at 4,096, errors 0 | none | none | none | 13.64 11.00 9.86 | 21.59 13.96 11.18 |
| 1-S-mongo/e1-top | 21,822.6/s at 4,096 | none | 8,192: 16.4% (104,730 × 429 wow.command.busy) | 17,754.7/s at 8,192, errors 104,730 × http-429-wow.command.busy | none | none | none | 20.10 13.78 11.13 | 22.00 16.03 12.25 |
| 1-S-mongo/e1-zipf-sweep | 13,372.5/s at 1,024 | 3,353.8/s at 64 | 4,096: 13.7% (73,748 × 429 wow.command.busy) | 15,452.7/s at 4,096, errors 73,748 × http-429-wow.command.busy | none | none | none | 22.00 16.03 12.25 | 11.46 11.77 11.18 |
| 1-S-mongo/e1-zipf-top | – | none | 4,096: 13.1% (78,858 × 429 wow.command.busy) | 17,511.2/s at 4,096, errors 78,858 × http-429-wow.command.busy | none | none | none | 11.46 11.77 11.18 | 12.18 11.67 11.16 |
| 1-S-mongo/e3-sweep | 7,341.7/s at 1,024 | 4,809.4/s at 256 | 4,096: 75.7% (397,534 × 429 wow.command.busy, 1,989 × 504 wow.wait.timeout) | 4,282.4/s at 4,096, errors 397,534 × http-429-wow.command.busy, 1,989 × http-504-wow.wait.timeout | none | none | none | 26.70 13.96 11.53 | 19.46 12.96 11.39 |
| 1-S-postgres/e1-hot-sweep | 11,521.2/s at 1,024 | 8,520.7/s at 256 | 4,096: 48.1% (605,525 × 429 wow.command.busy) | 21,779.9/s at 4,096, errors 605,525 × http-429-wow.command.busy | none | none | none | 13.93 9.99 9.81 | 12.74 8.40 8.78 |
| 1-S-postgres/e1-hot-top | – | none | 4,096: 48.1% (606,290 × 429 wow.command.busy) | 21,789.3/s at 4,096, errors 606,290 × http-429-wow.command.busy | none | none | none | 12.74 8.40 8.78 | 19.38 10.74 9.62 |
| 1-S-postgres/e1-sweep | ≥ 37,713.6/s at 4,096 | 23,030.1/s at 1,024 | none | 37,713.6/s at 4,096, errors 0 | none | none | none | 9.35 9.98 9.30 | 22.76 12.38 10.15 |
| 1-S-postgres/e1-top | 36,719.5/s at 4,096 | none | 8,192: 4.0% (26,600 × 429 wow.command.busy) | 21,273.0/s at 8,192, errors 26,600 × http-429-wow.command.busy | none | none | none | 22.76 12.38 10.15 | 22.93 14.64 11.20 |
| 1-S-postgres/e1-zipf-sweep | 17,090.6/s at 1,024 | 15,386.1/s at 256 | 4,096: 12.0% (83,231 × 429 wow.command.busy) | 20,308.3/s at 4,096, errors 83,231 × http-429-wow.command.busy | none | none | none | 22.93 14.64 11.20 | 11.32 10.73 10.17 |
| 1-S-postgres/e1-zipf-top | – | none | 4,096: 10.7% (74,031 × 429 wow.command.busy) | 20,620.8/s at 4,096, errors 74,031 × http-429-wow.command.busy | none | none | none | 11.32 10.73 10.17 | 13.93 9.99 9.81 |
| 1-S-postgres/e3-sweep | 7,956.2/s at 1,024 | 3,687.4/s at 64 | 4,096: 69.7% (359,173 × 429 wow.command.busy, 1,066 × 504 wow.wait.timeout) | 5,222.8/s at 4,096, errors 359,173 × http-429-wow.command.busy, 1,066 × http-504-wow.wait.timeout | none | none | none | 19.38 10.74 9.62 | 15.11 10.90 9.77 |
| 3-N-mongo/e1-c1024 | 15,805.8/s at 1,024 (one step) | 15,805.8/s at 1,024 | none | 15,805.8/s at 1,024, errors 0 | none | none | none | 26.05 24.81 22.10 | 38.95 29.15 23.99 |
| 3-N-mongo/e1-sweep | 16,657.2/s at 1,024 | 16,657.2/s at 1,024 | none | 7,741.7/s at 4,096, errors 0 | none | none | none | 35.34 28.70 21.55 | 35.71 27.35 22.27 |
| 3-N-mongo/e3-c1024 | 5,273.4/s at 1,024 (one step) | none | none | 5,273.4/s at 1,024, errors 0 | none | none | none | 38.95 29.15 23.99 | 37.54 31.07 25.18 |
| 3-N-mongo/e3-sweep | 5,380.1/s at 1,024 | 4,894.1/s at 256 | none | 2,495.1/s at 4,096, errors 0 | none | none | none | 35.71 27.35 22.27 | 26.05 24.81 22.10 |
| 3-N-postgres/e1-c1024 | 19,000.2/s at 1,024 (one step) | 19,000.2/s at 1,024 | none | 19,000.2/s at 1,024, errors 0 | none | none | none | 44.39 27.88 19.30 | 35.72 28.38 20.21 |
| 3-N-postgres/e1-sweep | 21,114.1/s at 1,024 | 21,114.1/s at 1,024 | 4,096: 2.4% (7,681 × 504 wow.wait.timeout, 232 × 503 wow.command.unknown, 2 × 409 wow.command.contended) | 10,824.4/s at 4,096, errors 7,681 × http-504-wow.wait.timeout, 232 × http-503-wow.command.unknown, 2 × http-409-wow.command.contended | none | none | none | 20.30 13.88 11.81 | 29.68 21.74 15.52 |
| 3-N-postgres/e3-c1024 | 5,807.8/s at 1,024 (one step) | none | none | 5,807.8/s at 1,024, errors 0 | none | none | none | 35.72 28.38 20.21 | 24.94 27.40 20.61 |
| 3-N-postgres/e3-sweep | 6,950.1/s at 1,024 | 6,119.1/s at 256 | none | 3,217.4/s at 4,096, errors 66 × http-504-wow.wait.timeout, 2 × http-409-wow.command.contended | none | none | none | 29.68 21.74 15.52 | 44.39 27.88 19.30 |

The allowed JVMs (environment.txt) over 22 measurements, 370 samples: mean 0.5%, max 89.3% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.
