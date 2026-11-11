# m5max-snapshot: measurements

```text
## 01-single-node.sh 2026-11-10T22:33:48-0800
commit: a7c372ad3ef808f479776a6183247c39f46189fc
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 38528 77572
  38528   0.0 4149504 Tue Nov 10 18:29:56 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  77572   0.0 3160784 Tue Nov 10 20:26:18 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
## 02-three-nodes.sh 2026-11-10T23:26:34-0800
commit: a7c372ad3ef808f479776a6183247c39f46189fc
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 38528 77572
  38528   0.0 2841056 Tue Nov 10 18:29:56 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  77572   0.0 858832 Tue Nov 10 20:26:18 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invo
## 02-three-nodes.sh 2026-11-11T00:33:30-0800
commit: a7c372ad3ef808f479776a6183247c39f46189fc
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 38528 77572 59696
  38528   0.0 3663808 Tue Nov 10 18:29:56 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  59696   0.1 2399504 Wed Nov 11 00:23:26 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
```

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-S-mongo/e1-hot-sweep | 8,984.5/s at 1,024 | 8,079.4/s at 256 | 4,096: 49.4% (554,978 × 429 wow.command.busy) | 18,920.3/s at 4,096, errors 554,978 × http-429-wow.command.busy | none | none | none | 14.64 12.67 11.62 | 19.45 11.88 11.07 |
| 1-S-mongo/e1-hot-top | – | none | 4,096: 49.5% (530,249 × 429 wow.command.busy) | 18,025.7/s at 4,096, errors 530,249 × http-429-wow.command.busy | none | none | none | 19.45 11.88 11.07 | 23.80 13.89 11.84 |
| 1-S-mongo/e1-sweep | ≥ 21,458.6/s at 4,096 | 17,946.6/s at 1,024 | none | 21,458.6/s at 4,096, errors 0 | none | none | none | 9.74 10.40 9.75 | 24.67 14.93 11.52 |
| 1-S-mongo/e1-top | 20,694.2/s at 4,096 | none | 8,192: 4.3% (23,949 × 429 wow.command.busy) | 17,632.7/s at 8,192, errors 23,949 × http-429-wow.command.busy | none | none | none | 24.67 14.93 11.52 | 21.06 16.21 12.33 |
| 1-S-mongo/e1-zipf-sweep | 14,536.9/s at 1,024 | 12,643.0/s at 256 | 4,096: 13.5% (78,298 × 429 wow.command.busy) | 16,731.3/s at 4,096, errors 78,298 × http-429-wow.command.busy | none | none | none | 21.06 16.21 12.33 | 19.12 13.46 11.78 |
| 1-S-mongo/e1-zipf-top | – | none | 4,096: 13.1% (73,997 × 429 wow.command.busy) | 16,332.9/s at 4,096, errors 73,997 × http-429-wow.command.busy | none | none | none | 18.47 13.42 11.78 | 15.83 12.87 11.68 |
| 1-S-mongo/e3-sweep | 7,446.5/s at 1,024 | 4,436.5/s at 256 | 4,096: 4.5% (5,058 × 504 wow.wait.timeout, 4 × 429 wow.command.busy) | 3,541.6/s at 4,096, errors 5,058 × http-504-wow.wait.timeout, 4 × http-429-wow.command.busy | none | none | none | 23.80 13.89 11.84 | 14.51 13.39 12.05 |
| 1-S-postgres/e1-hot-sweep | 9,431.6/s at 256 | 9,431.6/s at 256 | 4,096: 47.7% (532,704 × 429 wow.command.busy) | 19,455.5/s at 4,096, errors 532,704 × http-429-wow.command.busy | none | none | none | 10.10 11.59 9.16 | 13.70 10.26 9.09 |
| 1-S-postgres/e1-hot-top | – | none | 4,096: 48.0% (527,870 × 429 wow.command.busy) | 19,078.7/s at 4,096, errors 527,870 × http-429-wow.command.busy | none | none | none | 13.70 10.26 9.09 | 19.79 12.27 9.89 |
| 1-S-postgres/e1-sweep | ≥ 33,938.5/s at 4,096 | 21,373.1/s at 1,024 | none | 33,938.5/s at 4,096, errors 0 | none | none | none | 7.52 4.17 2.47 | 23.57 11.69 6.05 |
| 1-S-postgres/e1-top | 32,879.1/s at 4,096 | none | none | 18,695.6/s at 8,192, errors 2,993 × http-429-wow.command.busy | none | none | none | 23.57 11.69 6.05 | 24.74 15.04 7.86 |
| 1-S-postgres/e1-zipf-sweep | 16,139.1/s at 1,024 | 15,417.2/s at 256 | 4,096: 12.8% (87,288 × 429 wow.command.busy) | 19,902.2/s at 4,096, errors 87,288 × http-429-wow.command.busy | none | none | none | 24.74 15.04 7.86 | 19.95 15.31 9.71 |
| 1-S-postgres/e1-zipf-top | – | none | 4,096: 13.0% (85,597 × 429 wow.command.busy) | 19,070.2/s at 4,096, errors 85,597 × http-429-wow.command.busy | none | none | none | 19.95 15.31 9.71 | 10.10 11.59 9.16 |
| 1-S-postgres/e3-sweep | 8,298.6/s at 1,024 | 4,333.5/s at 256 | 4,096: 19.4% (8,064 × 504 wow.wait.timeout, 11 × 429 wow.command.busy) | 1,117.9/s at 4,096, errors 8,064 × http-504-wow.wait.timeout, 11 × http-429-wow.command.busy | none | none | none | 19.79 12.27 9.89 | 9.91 10.79 9.84 |
| 3-N-mongo/e1-c1024 | 15,269.0/s at 1,024 (one step) | none | none | 15,269.0/s at 1,024, errors 0 | none | none | none | 39.69 25.86 19.56 | 24.05 24.39 19.58 |
| 3-N-mongo/e1-sweep | 14,778.8/s at 1,024 | 12,128.7/s at 256 | none | 6,998.7/s at 4,096, errors 0 | none | none | none | 15.10 10.69 14.07 | 34.96 19.37 16.41 |
| 3-N-mongo/e3-c1024 | 4,769.1/s at 1,024 (one step) | none | none | 4,769.1/s at 1,024, errors 0 | none | none | none | 22.36 24.03 19.48 | 37.67 29.04 21.79 |
| 3-N-mongo/e3-sweep | 5,332.2/s at 1,024 | 4,871.1/s at 256 | 4,096: 4.6% (3,447 × 504 wow.wait.timeout) | 2,358.7/s at 4,096, errors 3,447 × http-504-wow.wait.timeout | none | none | none | 34.96 19.37 16.41 | 39.69 25.86 19.56 |
| 3-N-postgres/e1-c1024 | 20,698.7/s at 1,024 (one step) | 20,698.7/s at 1,024 | none | 20,698.7/s at 1,024, errors 0 | none | none | none | 28.57 24.00 18.63 | 33.44 25.48 19.69 |
| 3-N-postgres/e1-sweep | 21,166.6/s at 1,024 | 21,166.6/s at 1,024 | none | 10,759.0/s at 4,096, errors 0 | none | none | none | 18.81 14.26 12.45 | 35.48 21.84 15.77 |
| 3-N-postgres/e3-c1024 | 6,122.7/s at 1,024 (one step) | none | none | 6,122.7/s at 1,024, errors 0 | none | none | none | 33.44 25.48 19.69 | 27.79 26.05 20.45 |
| 3-N-postgres/e3-sweep | 6,863.2/s at 1,024 | 6,197.0/s at 256 | 4,096: 28.6% (11,005 × 504 wow.wait.timeout) | 916.4/s at 4,096, errors 11,005 × http-504-wow.wait.timeout | none | none | none | 35.48 21.84 15.77 | 28.57 24.00 18.63 |
| e1-hot-arms/1-head-25/e1-hot-sweep | 10,643.1/s at 64 | 9,490.9/s at 256 | 4,096: 47.0% (521,920 × 429 wow.command.busy) | 19,640.4/s at 4,096, errors 521,920 × http-429-wow.command.busy | none | none | none | 6.64 12.77 16.33 | 17.65 14.23 15.83 |
| e1-hot-arms/2-old-25/e1-hot-sweep | 10,256.5/s at 256 | 10,256.5/s at 256 | 4,096: 47.6% (511,653 × 429 wow.command.busy) | 18,745.3/s at 4,096, errors 511,653 × http-429-wow.command.busy | none | none | none | 10.37 6.11 8.80 | 16.83 9.10 9.16 |
| e1-hot-arms/3-head-21/e1-hot-sweep | 8,928.9/s at 256 | 7,781.9/s at 64 | 4,096: 47.1% (502,066 × 429 wow.command.busy) | 18,818.7/s at 4,096, errors 502,066 × http-429-wow.command.busy | none | none | none | 16.70 10.28 9.58 | 20.57 13.21 10.84 |
| e1-hot-arms/4-head-25b/e1-hot-sweep | 10,353.7/s at 1,024 | 8,219.0/s at 64 | 4,096: 47.4% (521,704 × 429 wow.command.busy) | 19,273.3/s at 4,096, errors 521,704 × http-429-wow.command.busy | none | none | none | 12.85 12.33 10.67 | 17.48 11.88 10.59 |
| e1-hot-arms/5-old-25b/e1-hot-sweep | 11,875.7/s at 256 | 11,875.7/s at 256 | 4,096: 47.4% (511,403 × 429 wow.command.busy) | 18,942.9/s at 4,096, errors 511,403 × http-429-wow.command.busy | none | none | none | 13.70 11.74 10.63 | 18.46 12.07 10.77 |

The allowed JVMs (environment.txt) over 27 measurements, 510 samples: mean 0.1%, max 27.4% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.
