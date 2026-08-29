# Query Gateway / ObjectNode benchmark gate manifest

## Identity and status

- Manifest assembled: `2026-08-30T04:43:32+0800` (`Asia/Shanghai`).
- Baseline commit: `2e43a9f0d3fee099ee249bc75f55f8678bb27635`.
- V9 production commit: `6b67462c8645fe135f34c882f90dc442e2dd84c9`.
- Benchmark/result commit under test: `6d6a4f575500dae6ccca0cd6843013b0a862eba0`.
- Artifact packaging commit: the commit containing this manifest; resolve with `git log -1 --format=%H -- docs/superpowers/benchmarks/2026-08-29-query-gateway-object-node-artifacts/gate-manifest.md`.
- Overall Task 12 and merge status: `BLOCKED`. All 16 Mongo combinations are `MISSING EVIDENCE` under `SERVER-121912`; the Elasticsearch adverse signals also have no product budget or production-risk acceptance and require a longer stable rerun. Every non-performance gate below passed.

## Environment

| Item | Value |
| --- | --- |
| Host | Apple M4 Pro, 14 logical CPUs |
| OS | macOS 26.5.2 (25F84), Darwin 25.5.0 arm64 |
| Docker | client/server 29.7.2; VM kernel `7.0.12-linuxkit` |
| JDK | Azul Zulu OpenJDK 17.0.7+7-LTS |
| JVM | OpenJDK 64-Bit Server VM; G1; `-Xms1g -Xmx1g -XX:+UseG1GC` |
| JMH | 1.37; 1 thread; 3 forks; 5 × 200 ms warmup; 10 × 200 ms measurement; `thrpt,sample`; `-prof gc` |
| Dataset | 1,000 snapshots; seed `20260829` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.2.6`; image ID `sha256:8fb2a046f8adf4e8d64066206ebcb798bef8ff4420379feedd427c30500c5d3c`; digest `sha256:e5673d86bb6a41ed543329ec094fc93d5ef749d32cc87a4150a15d520ad9c670` |

## Durable artifacts

All six files were copied byte-for-byte from the measured local results or preserved detached-main harness. `cmp` returned exit 0 for every pair.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `main-elasticsearch.json` | 3,364,790 | `19f4c438e8563a171c3b7d95db5ce95bf54413399df1743f93ef1d466011ccc5` |
| `main-elasticsearch.txt` | 524,418 | `55115865cadb2446af95964ca6758c5b9f10934573305d7102cf96157adb7f3a` |
| `v9-elasticsearch.json` | 3,270,452 | `f7d8389575dde7c05f3e64f7bfa4b927339880efa73403b94d87ce9fce311199` |
| `v9-elasticsearch.txt` | 525,493 | `bbf245afe7177a805e3ce6cc66963ed14eae7aa37d0a44e958f89e4d9c6b13f5` |
| `QueryGatewayBackendBenchmark-main.kt` | 13,992 | `fe71b30975917bc6d0660378606d8d2628e394fcc8be153d1f3f223a0506fd86` |
| `META-INF/wow-metadata.json` | 484 | `5753d0aa17512785454f6558e7a19ffddda6ce19925c5d3db59d1a7c90461a30` |

The JSON files retain throughput fork `rawData`, GC secondary metrics and sample p95. Each human output contains 16 sample histograms and 16 percentile tables.

The exact JMH text contains histogram-line trailing spaces, and the exact JSON contains its generated EOF layout. Root `.gitattributes` marks only this directory's `*-elasticsearch.json` and `*-elasticsearch.txt` as `binary`, so Git preserves and hashes the original bytes while `git diff --check` continues to inspect editable source and Markdown.

## Benchmark commands and results

The repository-provided Gradle command was run first:

```bash
./gradlew :wow-benchmarks:jmh \
  -Pjmh.include='.*QueryGatewayBackendBenchmark.*' \
  -Pjmh.profilers=gc
```

- Exit: `0`, but Gradle printed `:wow-benchmarks:jmh SKIPPED`; it is not counted as measurement evidence.
- Exact wall-clock timestamp/source log: not persisted. The final JMH artifacts below are the measurement evidence.

Both detached main and V9 used the following JMH CLI with their own byte-identified jar and result path:

```bash
java -jar wow-benchmarks/build/libs/wow-benchmarks-8.16.1-jmh.jar \
  '.*QueryGatewayBackendBenchmark.query' \
  -p storage=elasticsearch \
  -wi 5 -w 200ms -i 10 -r 200ms -f 3 -t 1 \
  -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \
  -rf json -rff <result.json> -o <human.txt>
```

| Run | Completion timestamp from original output mtime | Exit | Records / combinations | Output SHA-256 |
| --- | --- | ---: | --- | --- |
| main | `2026-08-30T03:51:17+0800` | 0 | 32 mode rows / 16 combinations | JSON `19f4c4...cca5`; text `551158...f3a` |
| V9 | `2026-08-30T03:59:41+0800` | 0 | 32 mode rows / 16 combinations | JSON `f7d838...1199`; text `bbf245...13f5` |

Artifact validation command:

```bash
cmp <each original source> <each durable copy>
shasum -a 256 <six durable artifacts>
jq -e '<32 rows; 16 combinations; 16 thrpt; 16 sample; forks=3; gc present; p95 present; throughput rawData has 3 forks>' {main,v9}-elasticsearch.json
test "$(rg -c '^  Histogram, s/op:$' <human-output>)" -eq 16
test "$(rg -c '^  Percentiles, s/op:$' <human-output>)" -eq 16
```

- Timestamp: `2026-08-30T04:43:04+0800`.
- Exit: `0`.
- Result: both JSON files passed every structural assertion; both text files contained 16 histograms and 16 percentile tables; all `cmp` checks passed.
- Local source log: `.superpowers/sdd/2026-08-29-query-gateway-backend-refactor/task-12-logs/artifact-validation-fix-round-1.log`, 1,312 bytes, SHA-256 `9d81e25ca0b347bde294828094a3b128079b007c9d4a09457cc6117c74b5bf36`.

## Final non-performance gates

The Gradle gates ran after the benchmark source was commit-ready. `--rerun-tasks` forced fresh task execution.

### Focused modules

```bash
./gradlew \
  :wow-api:check \
  :wow-core:check \
  :wow-query:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check \
  :wow-webflux:check \
  :wow-cocache:check \
  :wow-apiclient:check \
  --stacktrace --rerun-tasks
```

- Completion timestamp: `2026-08-30T04:07:38+0800`.
- Exit: `0`.
- Tail: `BUILD SUCCESSFUL in 1m 7s`; `133 actionable tasks: 133 executed`.
- XML count: 429 suites, 2,214 tests, 0 skipped, 0 failures, 0 errors.
- Local source log: `focused-check.log`, 126,696 bytes, SHA-256 `63a65152ae96ccd20c5b4abbd3affe55b6b0144bb5587f759b532d432346a9aa`.

### Storage, integration and wow-it

```bash
./gradlew \
  :wow-mongo:check :wow-mongo:integrationTest \
  :wow-elasticsearch:check :wow-elasticsearch:integrationTest \
  :wow-it:integrationTest \
  --stacktrace --rerun-tasks
```

- Completion timestamp: `2026-08-30T04:10:15+0800`.
- Exit: `0`.
- Tail: `BUILD SUCCESSFUL in 2m 8s`; `54 actionable tasks: 54 executed`.
- XML count: 65 suites, 677 tests, 0 skipped, 0 failures, 0 errors.
- Local source log: `storage-integration.log`, 62,340 bytes, SHA-256 `8177066a7e5c4a1c4ad3b2548ca06e14c9443575b2a910b9860bb470d76023dd`.

### Full build

```bash
./gradlew build --rerun-tasks
```

- Completion timestamp: `2026-08-30T04:11:50+0800`.
- Exit: `0`.
- Tail: `BUILD SUCCESSFUL in 1m 30s`; `335 actionable tasks: 335 executed`.
- XML count: 650 `test`/`contractTest` suites, 3,737 tests, 0 skipped, 0 failures, 0 errors.
- Local source log: `full-build.log`, 165,717 bytes, SHA-256 `29f19e6928cf57ed5413581c9b6ad84e4ef0f29d8f11671504a313513fddce15`.

The focused/storage counts are final XML snapshots on the same commit; later full-build unit tasks can overwrite matching unit XML. The three totals must not be added together.

### Documentation build (durability fix round 1)

```bash
cd documentation && pnpm docs:build
```

- Completion timestamp: `2026-08-30T04:48:06+0800`.
- Exit: `0`.
- Tail: client/server bundles, page render and sitemap completed; `build complete in 7.15s.`
- Warning: existing chunk-size warning only.
- Local source log: `docs-build-fix-round-1.log`, 6,454 bytes, SHA-256 `df23b525393d1c257f3d79949757b640212aa656290dc043a68301b7463e3849`.

### Required static scans

```bash
! rg -n "QueryService|DynamicDocument|SimpleDynamicDocument|TailSnapshotQueryFilter|TailEventStreamQueryFilter|DataMasking|DYNAMIC_SINGLE|DYNAMIC_LIST|DYNAMIC_PAGED" \
  wow-*/src test example compensation --glob '!**/build/**'

! rg -n "class QueryRouter|interface QueryRouter|QueryGatewayFactory|AggregatedQueryGateway" \
  wow-*/src test example compensation --glob '!**/build/**'
```

- Completion timestamp: `2026-08-30T04:12:21+0800`.
- Each wrapped command exit: `0`; each underlying `rg` exit: `1`; matches: `0`.
- Local source logs: `legacy-static-scan.log` and `router-static-scan.log`, both 0 bytes, both SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

Additional architecture scans:

```bash
! rg -n "Query.*Proxy|Proxy.*Query" wow-*/src test example compensation --glob '!**/build/**'
! rg -n "BeanFactory" wow-webflux/src/main --glob '!**/build/**'
```

- Completion timestamps: `2026-08-30T04:20:50+0800` and `2026-08-30T04:21:18+0800`.
- Each wrapped command exit: `0`; matches: `0`.
- Local source logs: `query-proxy-static-scan.log` and `webflux-handler-beanfactory-scan.log`, both 0 bytes, both SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

### Diff checks

```bash
git diff --check
git diff --cached --check
git diff --check 2e43a9f0d..HEAD
```

- Timestamp: `2026-08-30T04:46:58+0800`.
- Each exit: `0`; diagnostic output: none.
- Local source log: `diff-check-fix-round-1.log`, 0 bytes, SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

## Local log retention boundary

The four large Gradle logs are not committed. Their exact local path, byte length and SHA-256 are recorded above; the committed artifact payload contains the raw benchmark fork/histogram data, both harness inputs and this durable manifest. Losing the local Gradle logs does not lose the recorded command, commit, timestamp, exit, BUILD tail, task count or test count.
