# mongo-store-cost: measurements

```text
## store-cost.sh 2026-11-11T15:31:01-0800
commit: 4665c3f0f6cab2cd4cf1e6c8eb51ed7b4ea5f069
changed paths: 0
machine: Mac17,14, Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB
macOS: 27.2 (26B123)
engine: OrbStack 2.2.5 (2020500)
docker: 29.8.2, 16 CPUs, 33670660096 bytes
jdk: OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)
allowed JVMs (pid, %cpu, rss KB, started, command): 38528 48490 57345 57438 57683
  38528   0.1 5136080 Tue Nov 10 18:29:56 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  48490   0.0 5618768 Wed Nov 11 15:03:33 2026     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
  57345   0.0 4452176 Wed Nov 11 15:27:00 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
  57438   0.0 2649568 Wed Nov 11 15:27:20 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java -cp /Users/ahoowang/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kot
  57683   0.0 5480480 Wed Nov 11 15:27:56 2026     /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.inv
```

| Measurement | Peak (failed 1% or less) or result | Sustainable | Failed more than 1% | Last step or provenance | Disturbed steps | Void steps | Generator-bound steps | Load before | Load after |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| r1-main/run | 7,992.9/s at 8,000/s (**not reached**), p50 7.74 ms, p99 206.8 ms, failed 857 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:35:55.189563Z | none | none | – | 7.28 8.36 11.33 | 8.57 9.25 11.24 |
| r1-main-noidx/run | 8,000.0/s at 8,000/s (reached), p50 7.36 ms, p99 113.7 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:39:41.828696Z | none | none | – | 5.75 8.40 10.77 | 11.30 10.18 11.12 |
| r1-tag/run | 8,000.0/s at 8,000/s (reached), p50 8.77 ms, p99 190.5 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:32:09.186973Z | none | none | – | 5.92 9.13 12.47 | 7.36 8.50 11.63 |
| r1-tag-idx/run | 8,000.0/s at 8,000/s (reached), p50 7.65 ms, p99 184.3 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:43:26.925128Z | none | none | – | 8.19 9.69 10.87 | 11.07 10.26 10.90 |
| r2-main/run | 8,000.0/s at 8,000/s (reached), p50 6.82 ms, p99 90.6 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:54:46.180847Z | none | none | – | 6.90 9.25 10.12 | 9.22 9.10 9.90 |
| r2-main-noidx/run | 8,000.0/s at 8,000/s (reached), p50 7.78 ms, p99 178.2 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:51:00.185129Z | none | none | – | 8.85 9.61 10.40 | 9.80 10.02 10.43 |
| r2-tag/run | 7,994.4/s at 8,000/s (**not reached**), p50 8.45 ms, p99 193.5 ms, failed 666 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:58:31.801650Z | none | none | – | 9.98 9.55 10.01 | 12.30 10.70 10.39 |
| r2-tag-idx/run | 8,000.0/s at 8,000/s (reached), p50 7.74 ms, p99 120.8 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-11T23:47:13.922909Z | none | none | – | 7.71 9.62 10.62 | 8.00 9.40 10.38 |
| r3-main/run | 7,999.8/s at 8,000/s (**not reached**), p50 6.59 ms, p99 108.0 ms, failed 22 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-12T00:03:45.343603Z | none | none | – | 8.38 8.95 9.66 | 9.50 9.33 9.70 |
| r3-main-noidx/run | 8,000.0/s at 8,000/s (reached), p50 8.00 ms, p99 123.9 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-12T00:07:31.836676Z | none | none | – | 7.39 8.91 9.52 | 8.44 9.31 9.63 |
| r4-main/run | 8,000.0/s at 8,000/s (**not reached**), p50 8.45 ms, p99 124.4 ms, failed 5 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-12T00:15:01.839843Z | none | none | – | 4.76 7.87 8.97 | 7.24 8.18 8.94 |
| r4-main-noidx/run | 8,000.0/s at 8,000/s (reached), p50 6.94 ms, p99 113.2 ms, failed 0 | – | – | commit 4665c3f0f6, dirty False, started 2026-11-12T00:11:17.420185Z | none | none | – | 7.22 8.89 9.45 | 8.54 9.11 9.47 |

The allowed JVMs (environment.txt) over 12 measurements, 156 samples: mean 0.4%, max 37.2% of one core; a step is void with two samples in a row at 5% or more, or a build request in a daemon's log (allowed-jvms.txt).

Other JVMs seen during the measurements: none.
