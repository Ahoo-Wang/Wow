# head: measurements

```text
## 01-single-node.sh 2026-11-17T02:31:36-0800
commit: 103f9b674680e7b98fbd8b934ee1947fbbe37a12
server build: 103f9b674680e7b98fbd8b934ee1947fbbe37a12 (/private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/bench-run/test/benchmarks/server/build/install/benchmark-server)
load generator build: 103f9b674680e7b98fbd8b934ee1947fbbe37a12
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2.1 (26B131)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 50498138112 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
step JFR: off
allowed JVMs (pid, %cpu, rss KB, started, command): 2744 32611 
   2744   0.0 1268400 Mon Nov 16 22:45:00 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
## 02-three-nodes.sh 2026-11-17T03:27:07-0800
commit: 103f9b674680e7b98fbd8b934ee1947fbbe37a12
server build: 103f9b674680e7b98fbd8b934ee1947fbbe37a12 (/private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-p1-sweep-edges-b33c1b/b15b5aab-5b8a-44af-a70a-3928f466a07f/scratchpad/bench-run/test/benchmarks/server/build/install/benchmark-server)
load generator build: 103f9b674680e7b98fbd8b934ee1947fbbe37a12
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2.1 (26B131)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 50498138112 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
step JFR: off
allowed JVMs (pid, %cpu, rss KB, started, command): 2744 32611 
   2744   0.0 1247216 Mon Nov 16 22:45:00 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
```

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after | Builds |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1-S-mongo/e1-hot-sweep | 9,948.1/s at 256 | 9,948.1/s at 256 | 4,096: 47.9% (552,724 × 429 wow.command.busy) | 20,048.4/s at 4,096, errors 552,724 × http-429-wow.command.busy | none | none | none | 12.48 10.43 11.39 | 16.95 10.12 10.41 | server 103f9b6746, load 103f9b6746 |
| 1-S-mongo/e1-hot-top | – | none | 4,096: 49.2% (671,697 × 429 wow.command.busy) | 23,132.8/s at 4,096, errors 671,697 × http-429-wow.command.busy | none | none | none | 16.95 10.12 10.41 | 20.12 11.87 11.03 | server 103f9b6746, load 103f9b6746 |
| 1-S-mongo/e1-sweep | ≥ 28,609.4/s at 4,096 | 28,196.1/s at 1,024 | none | 28,609.4/s at 4,096, errors 0 | none | none | none | 13.20 11.23 12.59 | 16.69 11.81 12.16 | server 103f9b6746, load 103f9b6746 |
| 1-S-mongo/e1-top | 26,615.9/s at 4,096 | none | 8,192: 2.0% (12,468 × 429 wow.command.busy) | 20,037.3/s at 8,192, errors 12,468 × http-429-wow.command.busy | none | none | none | 16.69 11.81 12.16 | 19.50 14.33 13.12 | server 103f9b6746, load 103f9b6746 |
| 1-S-mongo/e1-zipf-sweep | 15,957.5/s at 1,024 | 14,914.4/s at 256 | 4,096: 14.4% (102,934 × 429 wow.command.busy) | 20,449.9/s at 4,096, errors 102,934 × http-429-wow.command.busy | none | none | none | 19.50 14.33 13.12 | 16.38 12.65 12.41 | server 103f9b6746, load 103f9b6746 |
| 1-S-mongo/e1-zipf-top | – | none | 4,096: 14.4% (98,291 × 429 wow.command.busy) | 19,552.6/s at 4,096, errors 98,291 × http-429-wow.command.busy | none | none | none | 16.38 12.65 12.41 | 12.48 10.43 11.39 | server 103f9b6746, load 103f9b6746 |
| 1-S-mongo/e3-sweep | 7,738.2/s at 1,024 | 5,898.6/s at 256 | 4,096: 78.8% (423,223 × 429 wow.command.busy, 2,707 × 504 wow.wait.timeout) | 3,812.2/s at 4,096, errors 423,223 × http-429-wow.command.busy, 2,707 × http-504-wow.wait.timeout | none | none | none | 20.12 11.87 11.03 | 16.56 12.73 11.45 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e1-hot-sweep | 11,853.7/s at 256 | 11,853.7/s at 256 | 4,096: 45.3% (535,534 × 429 wow.command.busy) | 21,540.2/s at 4,096, errors 535,534 × http-429-wow.command.busy | none | none | none | 12.46 11.94 17.13 | 16.88 10.43 13.40 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e1-hot-top | – | none | 4,096: 45.3% (518,661 × 429 wow.command.busy) | 20,876.1/s at 4,096, errors 518,661 × http-429-wow.command.busy | none | none | none | 16.88 10.43 13.40 | 19.69 12.06 13.84 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e1-sweep | ≥ 41,706.1/s at 4,096 | 41,706.1/s at 4,096 | none | 41,706.1/s at 4,096, errors 0 | none | none | none | 23.61 30.73 27.44 | 19.52 20.91 23.44 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e1-top | 40,594.1/s at 4,096 | 40,594.1/s at 4,096 | 8,192: 2.7% (19,145 × 429 wow.command.busy) | 22,659.7/s at 8,192, errors 19,145 × http-429-wow.command.busy | none | none | none | 19.52 20.91 23.44 | 18.27 20.04 22.85 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e1-zipf-sweep | 19,746.8/s at 256 | 19,746.8/s at 256 | 4,096: 14.4% (126,957 × 429 wow.command.busy) | 25,193.8/s at 4,096, errors 126,957 × http-429-wow.command.busy | none | none | none | 18.27 20.04 22.85 | 19.72 16.95 20.27 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e1-zipf-top | – | none | 4,096: 14.3% (128,487 × 429 wow.command.busy) | 25,574.1/s at 4,096, errors 128,487 × http-429-wow.command.busy | none | none | none | 19.72 16.95 20.27 | 12.46 11.94 17.13 | server 103f9b6746, load 103f9b6746 |
| 1-S-postgres/e3-sweep | 8,042.7/s at 1,024 | 3,637.9/s at 64 | 4,096: 71.7% (467,570 × 429 wow.command.busy, 2,338 × 504 wow.wait.timeout) | 6,179.5/s at 4,096, errors 467,570 × http-429-wow.command.busy, 2,338 × http-504-wow.wait.timeout | none | none | none | 19.69 12.06 13.84 | 13.79 11.02 12.61 | server 103f9b6746, load 103f9b6746 |
| 3-N-mongo/e1-c1024 | 17,695.9/s at 1,024 (one step) | 17,695.9/s at 1,024 | none | 17,695.9/s at 1,024, errors 0 | none | none | none | 30.90 27.25 24.50 | 43.63 33.04 27.01 | server 103f9b6746, load 103f9b6746 |
| 3-N-mongo/e1-sweep | 19,052.3/s at 1,024 | 19,052.3/s at 1,024 | 4,096: 8.8% (58,464 × 429 wow.command.busy) | 20,172.6/s at 4,096, errors 58,464 × http-429-wow.command.busy | none | none | none | 28.44 33.15 24.61 | 45.22 31.06 25.13 | server 103f9b6746, load 103f9b6746 |
| 3-N-mongo/e3-c1024 | 6,015.6/s at 1,024 (one step) | none | none | 6,015.6/s at 1,024, errors 0 | none | none | none | 43.63 33.04 27.01 | 49.80 38.29 29.59 | server 103f9b6746, load 103f9b6746 |
| 3-N-mongo/e3-sweep | 6,060.2/s at 1,024 | 5,462.9/s at 256 | 4,096: 11.9% (16,439 × 429 wow.command.busy, 3,522 × 504 wow.wait.timeout) | 4,936.9/s at 4,096, errors 16,439 × http-429-wow.command.busy, 3,522 × http-504-wow.wait.timeout | none | none | none | 45.22 31.06 25.13 | 30.90 27.25 24.50 | server 103f9b6746, load 103f9b6746 |
| 3-N-postgres/e1-c1024 | 24,255.1/s at 1,024 (one step) | 24,255.1/s at 1,024 | none | 24,255.1/s at 1,024, errors 0 | none | none | none | 40.78 29.43 20.46 | 46.75 33.84 22.95 | server 103f9b6746, load 103f9b6746 |
| 3-N-postgres/e1-sweep | 25,104.3/s at 1,024 | 25,104.3/s at 1,024 | 4,096: 8.5% (71,059 × 429 wow.command.busy) | 25,482.8/s at 4,096, errors 71,059 × http-429-wow.command.busy | none | none | none | 21.12 14.20 12.05 | 41.65 24.68 16.72 | server 103f9b6746, load 103f9b6746 |
| 3-N-postgres/e3-c1024 | 6,630.8/s at 1,024 (one step) | none | none | 6,630.8/s at 1,024, errors 0 | none | none | none | 46.75 33.84 22.95 | 49.18 37.70 25.40 | server 103f9b6746, load 103f9b6746 |
| 3-N-postgres/e3-sweep | 8,623.0/s at 1,024 | 7,279.0/s at 256 | 4,096: 8.9% (18,424 × 429 wow.command.busy, 3,014 × 504 wow.wait.timeout) | 7,339.6/s at 4,096, errors 18,424 × http-429-wow.command.busy, 3,014 × http-504-wow.wait.timeout | none | none | none | 41.65 24.68 16.72 | 40.78 29.43 20.46 | server 103f9b6746, load 103f9b6746 |

The allowed JVMs (environment.txt) over 22 measurements, 385 samples: mean 0.1%, max 28.2% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.
