# Pure-Create `main` vs PR Paired A/B Evidence

## Result

This evidence set compares the code-equivalent `main` runtime with PR candidate
`b38afec22ae7462b581434fd7bb6c7c605b757f5` for the following closed-loop path:

```text
fresh aggregate command -> sendAndWaitForProcessed -> acknowledged version-1 append
```

The paired geometric throughput results are:

| Backend | Baseline mean | Candidate mean | Geometric ratio | Gain | Two-sided 95% CI | One-sided 95% lower bound | `> 1.20` |
|---|---:|---:|---:|---:|---:|---:|---|
| MongoDB | 21,340.38 ops/s | 21,429.87 ops/s | 1.004182 | +0.42% | -0.19% to +1.03% | -0.07% | No |
| Redis | 26,672.55 ops/s | 27,452.90 ops/s | 1.029241 | +2.92% | +2.46% to +3.39% | +2.55% | No |

The PR therefore does not meet the relative-throughput target of at least 20% on either backend.
The MongoDB result is indistinguishable from no change at the two-sided 95% level; the Redis gain is
small but consistent in this environment.

## Protocol

- A is the baseline and B is the candidate.
- One unscored warm-up pair was completed before the formal run.
- Formal order was frozen before scores were inspected:
  `AB, BA, BA, AB, BA, AB, AB, BA`.
- Every position used a separate Gradle/JMH process and ran serially.
- Each invocation ran both real backends with 14 JMH callers, `PARALLEL`, scheduler pool `14`,
  `896` stripes, `2 x 5s` warmup, `3 x 10s` measurement, one fork, G1, a 4 GiB fixed heap,
  and no profiler.
- All 16 manifests report `SUCCESS`, `dirty=false`, the exact run specification, and two result
  rows. Artifact sizes and SHA-256 values were revalidated after packaging.
- MongoDB and Redis remained healthy with `restartCount=0`; the 16 measured invocations did not
  overlap.
- No score was inspected before all eight pairs completed. No pair was excluded or repeated based
  on its result.

For backend \(b\) and pair \(i\), the primary statistic is:

```text
x[i,b] = ln(candidate throughput / baseline throughput)
ratio[b] = exp(mean(x[,b]))
LCB95[b] = exp(mean(x[,b]) - t(0.95, 7) * sd(x[,b]) / sqrt(8))
```

The predeclared acceptance gate requires `LCB95 > 1.20` for MongoDB and Redis independently, with
both conditions passing. This is an intersection-union test: success requires both component
claims, so the component one-sided alpha of 0.05 does not need a multiplicity correction.

Run the dependency-free verifier and analysis with:

```bash
node analyze.mjs
```

It validates the manifests, commits, JAR hashes, run specification, artifact hashes, pair ordering,
and non-overlap before reading scores and reproducing the statistics.

## Layout

Each `pair-NN-ab` or `pair-NN-ba` directory contains a `baseline` and a `candidate` directory.
Within each variant directory, the JSON, human-readable JMH output, and manifest retain their
original filenames so the paths and hashes recorded in the manifest remain directly verifiable.

- `summary.csv` contains the scores and ratios used in the paired analysis.
- `provenance.json` records source, tree, JAR, harness-bundle, runtime, and container identity.
- `run-paired.sh` is the frozen runner used for this experiment. It retains the original temporary
  worktree paths as an execution record, not as a portable benchmark launcher.

## Interpretation limits

This is controlled local real-backend E2E evidence, not a production-capacity claim:

- Docker Desktop was limited to 4 vCPUs and about 5.8 GiB while the host exposed 14 processors.
- MongoDB and Redis data used tmpfs; Redis persistence was disabled.
- Every operation created a fresh aggregate against a short-lived empty fixture.
- There was no snapshot/history replay, projection, saga, or downstream consumer.
- Fourteen closed-loop callers measure throughput at fixed in-flight concurrency, not open-loop
  maximum sustainable load or a latency SLA.
- The result is the combined effect of all candidate runtime changes that reach this path. It does
  not isolate an individual dispatcher, wait-handle, or allocation optimization, and it does not
  measure Spring Boot per-role configuration because the harness constructs the dispatcher
  directly.
