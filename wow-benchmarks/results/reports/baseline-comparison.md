# Framework E2E Baseline Comparison

- **Accepted Baseline**: `wow-benchmarks/results/baselines/framework-e2e.json`
- **Thresholds**: throughput=10.0%, latency=10.0%, allocation=10.0%
- **Classification**: `REGRESSION`/`IMPROVED` requires both a threshold crossing and non-overlapping JMH error intervals; `INCONCLUSIVE` crosses the threshold but has overlapping or unavailable intervals.
- **Interpretation**: JMH error describes measurement uncertainty inside each run. Treat regressions as investigation signals until a controlled rerun or profile identifies the cause.

**Summary:** 3 regression(s), 0 improvement(s), 5 inconclusive comparison(s), 24 stable metric comparison(s), 0 coverage change(s).

## Actionable Signals

| Status | Metric | Benchmark | Threads | Baseline | Current | Delta |
|--------|--------|-----------|---------|----------|---------|-------|
| THROUGHPUT_INCONCLUSIVE | throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | 1.21 M ops/s | 1 M ops/s | -17.3% |
| THROUGHPUT_REGRESSION | throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | 1.28 M ops/s | 0.96 M ops/s | -25.0% |
| THROUGHPUT_REGRESSION | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | 222.97 k ops/s | 167.62 k ops/s | -24.8% |
| THROUGHPUT_REGRESSION | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | 160.28 k ops/s | 124.28 k ops/s | -22.5% |
| THROUGHPUT_INCONCLUSIVE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | 89.37 k ops/s | 54.79 k ops/s | -38.7% |
| THROUGHPUT_INCONCLUSIVE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | 226.04 k ops/s | 192.93 k ops/s | -14.6% |
| THROUGHPUT_INCONCLUSIVE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | 136.07 k ops/s | 152.67 k ops/s | +12.2% |
| THROUGHPUT_INCONCLUSIVE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | 60.67 k ops/s | 74.16 k ops/s | +22.2% |

## Full Comparison

| Metric | Benchmark | Threads | Mode | Baseline | Baseline Error | Current | Current Error | Delta | Threshold | Status |
|--------|-----------|---------|------|----------|----------------|---------|---------------|-------|-----------|--------|
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 1.48 M ops/s | ±0.14 M ops/s | 1.53 M ops/s | ±0.2 M ops/s | +3.4% | 10.0% | STABLE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 2.21 KiB/op | ±<0.01 KiB/op | 2.21 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 1.21 M ops/s | ±0.06 M ops/s | 1 M ops/s | ±0.22 M ops/s | -17.3% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 2.21 KiB/op | ±<0.01 KiB/op | 2.21 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 1.32 M ops/s | ±0.04 M ops/s | 1.3 M ops/s | ±0.44 M ops/s | -1.2% | 10.0% | STABLE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 2.61 KiB/op | ±<0.01 KiB/op | 2.61 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 1.28 M ops/s | ±0.16 M ops/s | 0.96 M ops/s | ±0.11 M ops/s | -25.0% | 10.0% | THROUGHPUT_REGRESSION |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 2.61 KiB/op | ±<0.01 KiB/op | 2.65 KiB/op | ±<0.01 KiB/op | +1.8% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 294.72 k ops/s | ±12.68 k ops/s | 301.06 k ops/s | ±11.97 k ops/s | +2.2% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 12.06 KiB/op | ±0.05 KiB/op | 12.12 KiB/op | ±0.12 KiB/op | +0.5% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 222.97 k ops/s | ±20.21 k ops/s | 167.62 k ops/s | ±6.62 k ops/s | -24.8% | 10.0% | THROUGHPUT_REGRESSION |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 12.08 KiB/op | ±<0.01 KiB/op | 12.11 KiB/op | ±0.02 KiB/op | +0.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 63.21 k ops/s | ±7.43 k ops/s | 66.59 k ops/s | ±30.76 k ops/s | +5.3% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 3.86 KiB/op | ±<0.01 KiB/op | 3.86 KiB/op | ±0.01 KiB/op | -0.0% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 160.28 k ops/s | ±10.9 k ops/s | 124.28 k ops/s | ±8 k ops/s | -22.5% | 10.0% | THROUGHPUT_REGRESSION |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 3.86 KiB/op | ±<0.01 KiB/op | 3.86 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 213.38 k ops/s | ±31.66 k ops/s | 202.85 k ops/s | ±14.06 k ops/s | -4.9% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 13.13 KiB/op | ±<0.01 KiB/op | 13.13 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 125.39 k ops/s | ±8.54 k ops/s | 135.54 k ops/s | ±26.29 k ops/s | +8.1% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 13.18 KiB/op | ±0.07 KiB/op | 13.16 KiB/op | ±<0.01 KiB/op | -0.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 89.37 k ops/s | ±2.92 k ops/s | 54.79 k ops/s | ±32.98 k ops/s | -38.7% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 4.49 KiB/op | ±<0.01 KiB/op | 4.54 KiB/op | ±0.09 KiB/op | +1.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 116.09 k ops/s | ±6.35 k ops/s | 114.64 k ops/s | ±5.66 k ops/s | -1.3% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 4.48 KiB/op | ±<0.01 KiB/op | 4.48 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 226.04 k ops/s | ±35.5 k ops/s | 192.93 k ops/s | ±60.36 k ops/s | -14.6% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 12.97 KiB/op | ±<0.01 KiB/op | 12.99 KiB/op | ±0.09 KiB/op | +0.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 136.07 k ops/s | ±5.15 k ops/s | 152.67 k ops/s | ±41.09 k ops/s | +12.2% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 13.01 KiB/op | ±0.02 KiB/op | 13.02 KiB/op | ±<0.01 KiB/op | +0.0% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 60.67 k ops/s | ±12.98 k ops/s | 74.16 k ops/s | ±25.24 k ops/s | +22.2% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 4.78 KiB/op | ±0.08 KiB/op | 4.74 KiB/op | ±0.04 KiB/op | -0.8% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 132.12 k ops/s | ±37.1 k ops/s | 126.65 k ops/s | ±16.91 k ops/s | -4.1% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 4.73 KiB/op | ±0.08 KiB/op | 4.72 KiB/op | ±0.03 KiB/op | -0.3% | 10.0% | STABLE |
