# Framework E2E Baseline Comparison

- **Accepted Baseline**: `wow-benchmarks/results/baselines/framework-e2e.json`
- **Thresholds**: throughput=10.0%, latency=10.0%, allocation=10.0%
- **Classification**: `REGRESSION_CANDIDATE`/`IMPROVEMENT_CANDIDATE` requires both a threshold crossing and non-overlapping JMH error intervals; `INCONCLUSIVE` crosses the threshold but has overlapping or unavailable intervals.
- **Interpretation**: JMH error describes measurement uncertainty inside one run, not cross-run machine variance. Candidates are investigation signals and do not fail comparison; confirm them with a controlled targeted rerun before treating them as regressions.

**Summary:** 2 regression candidate(s), 7 improvement candidate(s), 3 inconclusive comparison(s), 20 stable metric comparison(s), 0 coverage change(s).

## Actionable Signals

| Status | Metric | Benchmark | Threads | Baseline | Current | Delta |
|--------|--------|-----------|---------|----------|---------|-------|
| THROUGHPUT_INCONCLUSIVE | throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | 1.48 M ops/s | 1.18 M ops/s | -20.0% |
| THROUGHPUT_REGRESSION_CANDIDATE | throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | 1.21 M ops/s | 0.88 M ops/s | -27.2% |
| THROUGHPUT_INCONCLUSIVE | throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | 1.32 M ops/s | 1.02 M ops/s | -22.5% |
| THROUGHPUT_REGRESSION_CANDIDATE | throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | 1.28 M ops/s | 0.85 M ops/s | -33.8% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | 63.21 k ops/s | 89.15 k ops/s | +41.0% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | 160.28 k ops/s | 188.43 k ops/s | +17.6% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | 125.39 k ops/s | 192.55 k ops/s | +53.6% |
| THROUGHPUT_INCONCLUSIVE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | 89.37 k ops/s | 79.89 k ops/s | -10.6% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | 116.09 k ops/s | 171.82 k ops/s | +48.0% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | 136.07 k ops/s | 227.24 k ops/s | +67.0% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | 60.67 k ops/s | 86.3 k ops/s | +42.2% |
| THROUGHPUT_IMPROVEMENT_CANDIDATE | throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | 132.12 k ops/s | 190.45 k ops/s | +44.1% |

## Full Comparison

| Metric | Benchmark | Threads | Mode | Baseline | Baseline Error | Current | Current Error | Delta | Threshold | Status |
|--------|-----------|---------|------|----------|----------------|---------|---------------|-------|-----------|--------|
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 1.48 M ops/s | ±0.14 M ops/s | 1.18 M ops/s | ±0.42 M ops/s | -20.0% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 1 | thrpt | 2.21 KiB/op | ±<0.01 KiB/op | 2.18 KiB/op | ±0.04 KiB/op | -1.1% | 10.0% | STABLE |
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 1.21 M ops/s | ±0.06 M ops/s | 0.88 M ops/s | ±0.08 M ops/s | -27.2% | 10.0% | THROUGHPUT_REGRESSION_CANDIDATE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=ceiling) | 4 | thrpt | 2.21 KiB/op | ±<0.01 KiB/op | 2.19 KiB/op | ±0.03 KiB/op | -0.8% | 10.0% | STABLE |
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 1.32 M ops/s | ±0.04 M ops/s | 1.02 M ops/s | ±0.34 M ops/s | -22.5% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 1 | thrpt | 2.61 KiB/op | ±<0.01 KiB/op | 2.59 KiB/op | ±0.03 KiB/op | -0.6% | 10.0% | STABLE |
| throughput | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 1.28 M ops/s | ±0.16 M ops/s | 0.85 M ops/s | ±0.11 M ops/s | -33.8% | 10.0% | THROUGHPUT_REGRESSION_CANDIDATE |
| allocation | CommandSendE2EBenchmark.sendAndWaitSent (gatewayScenario=validated) | 4 | thrpt | 2.61 KiB/op | ±<0.01 KiB/op | 2.59 KiB/op | ±<0.01 KiB/op | -0.5% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 294.72 k ops/s | ±12.68 k ops/s | 304.92 k ops/s | ±12.49 k ops/s | +3.5% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 12.06 KiB/op | ±0.05 KiB/op | 11.85 KiB/op | ±<0.01 KiB/op | -1.8% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 222.97 k ops/s | ±20.21 k ops/s | 239.58 k ops/s | ±21.25 k ops/s | +7.4% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 12.08 KiB/op | ±<0.01 KiB/op | 11.95 KiB/op | ±0.04 KiB/op | -1.1% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 63.21 k ops/s | ±7.43 k ops/s | 89.15 k ops/s | ±4.94 k ops/s | +41.0% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 1 | thrpt | 3.86 KiB/op | ±<0.01 KiB/op | 3.61 KiB/op | ±<0.01 KiB/op | -6.4% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 160.28 k ops/s | ±10.9 k ops/s | 188.43 k ops/s | ±4.28 k ops/s | +17.6% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=ceiling, schedulerStrategy=PARALLEL) | 4 | thrpt | 3.86 KiB/op | ±<0.01 KiB/op | 3.6 KiB/op | ±<0.01 KiB/op | -6.7% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 213.38 k ops/s | ±31.66 k ops/s | 201.64 k ops/s | ±15.12 k ops/s | -5.5% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 13.13 KiB/op | ±<0.01 KiB/op | 12.93 KiB/op | ±0.07 KiB/op | -1.6% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 125.39 k ops/s | ±8.54 k ops/s | 192.55 k ops/s | ±15.88 k ops/s | +53.6% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 13.18 KiB/op | ±0.07 KiB/op | 13 KiB/op | ±0.02 KiB/op | -1.4% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 89.37 k ops/s | ±2.92 k ops/s | 79.89 k ops/s | ±7.87 k ops/s | -10.6% | 10.0% | THROUGHPUT_INCONCLUSIVE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 1 | thrpt | 4.49 KiB/op | ±<0.01 KiB/op | 4.25 KiB/op | ±<0.01 KiB/op | -5.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 116.09 k ops/s | ±6.35 k ops/s | 171.82 k ops/s | ±8.46 k ops/s | +48.0% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=in-memory-new-aggregate, schedulerStrategy=PARALLEL) | 4 | thrpt | 4.48 KiB/op | ±<0.01 KiB/op | 4.21 KiB/op | ±<0.01 KiB/op | -6.1% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 226.04 k ops/s | ±35.5 k ops/s | 234.73 k ops/s | ±8.08 k ops/s | +3.8% | 10.0% | STABLE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 1 | thrpt | 12.97 KiB/op | ±<0.01 KiB/op | 12.79 KiB/op | ±0.12 KiB/op | -1.4% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 136.07 k ops/s | ±5.15 k ops/s | 227.24 k ops/s | ±19.59 k ops/s | +67.0% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=IMMEDIATE) | 4 | thrpt | 13.01 KiB/op | ±0.02 KiB/op | 12.85 KiB/op | ±0.04 KiB/op | -1.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 60.67 k ops/s | ±12.98 k ops/s | 86.3 k ops/s | ±2.02 k ops/s | +42.2% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 1 | thrpt | 4.78 KiB/op | ±0.08 KiB/op | 4.48 KiB/op | ±<0.01 KiB/op | -6.2% | 10.0% | STABLE |
| throughput | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 132.12 k ops/s | ±37.1 k ops/s | 190.45 k ops/s | ±4.34 k ops/s | +44.1% | 10.0% | THROUGHPUT_IMPROVEMENT_CANDIDATE |
| allocation | CommandWriteE2EBenchmark.sendAndWaitProcessed (scenario=noop-store, schedulerStrategy=PARALLEL) | 4 | thrpt | 4.73 KiB/op | ±0.08 KiB/op | 4.44 KiB/op | ±<0.01 KiB/op | -6.2% | 10.0% | STABLE |
