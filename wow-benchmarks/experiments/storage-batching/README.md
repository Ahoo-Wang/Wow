# Storage batching comparison

Self-contained, bounded experiments for the batching refactor. This directory contains scripts and fixture source only; write JARs, CSVs, JFRs and generated classes outside the repository. See the [current review](../../../documentation/designs/storage-batching-review.md) for results and remaining acceptance gaps.

## Prerequisites and commands

Use the same Java/Javac 17 installation for both artifacts. Supply complete baseline and candidate JMH JARs; both run the same newly compiled fixture classes. MongoDB must be available under the JAR's benchmark infrastructure configuration. Its fixture creates an isolated database and deletes that database at teardown. The full runner also needs the JMH annotation processor bundled in those JARs.

From the repository root, inspect the plan and run self-checks without collecting measurements:

```sh
python3 wow-benchmarks/experiments/storage-batching/analyze.py --self-test
python3 wow-benchmarks/experiments/storage-batching/test_reporting.py
python3 wow-benchmarks/experiments/storage-batching/run.py \
  --baseline /path/to/baseline-jmh.jar \
  --candidate /path/to/candidate-jmh.jar \
  --output /path/to/new-external-output
```

Append `--run` to execute the fixed full experiment. The output directory must not exist; the runner preserves commands, source/class/JAR hashes and raw samples, and has a 900-second budget. Do not run Gradle, other benchmarks or profilers concurrently. Record host/container load separately; the runner itself does not establish an isolated machine.

To repeat only the corrected Mongo protocol while referencing existing core evidence:

```sh
python3 wow-benchmarks/experiments/storage-batching/run_mongo_v2.py \
  --baseline /path/to/baseline-jmh.jar \
  --candidate /path/to/candidate-jmh.jar \
  --core-evidence /path/to/existing-core-evidence \
  --output /path/to/new-external-mongo-output
```

This command executes immediately, with a 180-second budget. It references the supplied core evidence without recomputing or certifying it. After compilation, `java -cp '<output>/classes:<candidate-jar>' batchharness.OpenLoop --self-test` exercises deterministic pacing and real clock accounting without Mongo writes.

## Frozen comparison protocol

- Core uses four lanes and four representative cells: prepared keys/metrics off/one producer; prepared/metrics on/one; prepared/metrics on/four; generated keys/metrics off/one. Each cell has three A/B pairs in AB/BA/AB order, three 3-second warmups and three 3-second measurements per fork.
- Prepared keys remove input generation and the old per-item global confirmation counter. Both boundaries still include publisher composition, subscription, scheduling and terminal aggregation. Neither measures a completely isolated coordinator. Metrics use SimpleMeterRegistry, excluding exporter/network costs. Each invocation verifies 128 successful terminals, matching OperationsPerInvocation.
- Mongo tests absolute baseline rates 5000, 10000 and 20000/s independently of closed-loop throughput. Each process has three 2-second warmup and three measurement intervals. Inputs are prepared before timing; persisted counts must match successes.
- The v2 sender keeps absolute planned slots and a minimum actual gap of 0.9 periods, allowing bounded phase recovery (at most about 11.1% short-term rate increase), never zero-gap catch-up bursts. Slots over 1ms late remain visible as not_sent. The final 1ms uses busy waiting, which consumes sender CPU.
- Calibration requires every measured interval to have at most 1% missing slots, zero errors and bounded backlog growth: last-three-decile peak ≤ first-three-decile peak + max(8, one millisecond of arrivals). The formal rate is 70% of the highest eligible rung, an observed bound under these conditions, not an exact capacity. No eligible rung means no formal Mongo comparison.
- Formal Mongo validity requires zero misses/errors and the backlog criterion in every interval. Process p95/p99 pool the three measured request populations; per-interval trends and sender lag remain available. Missing requests cannot be hidden by successful-only latency.
- Three process-level paired log ratios use a two-sided 95% t interval, df=2. Noninferiority requires throughput lower bound ≥0.95, allocation upper bound ≤1.05, latency upper bound ≤1.10. Equivalence additionally requires both bounds within the corresponding symmetric band; improvement excludes 1 in the favorable direction. These are marginal comparisons, not family-wide claims.
- Core screening requires final warmup throughput within 10% of measured mean and measured throughput CV ≤10%. Mongo requires final warmup call-p99 within 20% of pooled measurement call-p99 plus backlog validity. Every paired process must pass screening and delivery checks before claiming overall validity. Allocation shares the throughput stability screen. These short checks do not prove long-term steady state.

No selective retries or historical sample pooling. Preserve failed and timed-out commands. This is representative coverage, not Mongo snapshot/Elasticsearch or long-term performance acceptance.

## Historical evidence

This packaged protocol uses the final v2 sender. To reproduce earlier measurements exactly, use their archived frozen sources and JARs, not this directory as a replacement. `BatchLatency.java` retains the legacy ABI/backend fixture unchanged; runners use its setup, input, write and verification helpers. Its legacy standalone sender is not the supported experiment entrypoint. `analyze.py --correct-warmup <directory>` exists only for archived logs with the historical multiline parsing issue and writes separate corrected files.

Missing or empty successful populations produce null confidence bounds and false inference flags, never a fabricated zero-latency result. The runner reporting regression check uses synthetic JVM outputs, without starting benchmarks or databases.
