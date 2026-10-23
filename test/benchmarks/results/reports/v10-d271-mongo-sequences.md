# D271: owner-assigned sequences on MongoDB

The implementation of decision D271 against `main`, measured on 2026-10-23 on one machine. It follows the [spike](v10-d271-mongo-sequences-spike.md), which covered the happy path only.

## Setup

| | |
| --- | --- |
| Baseline | `main` at `099e932efe`: an append is one multi-document transaction, and the partition append queue commits a group in one transaction |
| D271 | the branch of decision D271: an append is one `insertOne` at a sequence of the node's block; with the append queue on, a group is one unordered `insertMany` per collection. L1 at `861afa44d`, the saturation runs at `d196e79bb` (the group commit) |
| Arms | `main`; D271 with the queue off; D271 with the queue on (`SPRING_APPLICATION_JSON={"wow.log.append-queue.enabled":true}`), which becomes MongoDB's default |
| Machine | Apple M4, 10 cores, 16 GB; MongoDB (single-node replica set), Kafka and the server on this machine, JDK 21 |
| Data | 100,000 accounts, uniform keys |

```bash
test/benchmarks/harness/bench.sh component --backend=mongo --scenario=E1 --accounts=100000 --install=<dir>
test/benchmarks/harness/bench.sh saturate --version=v10 --backend=mongo --scenario=E1 --accounts=100000 --seed-concurrency=128 --max-concurrency=512 --install=<dir>
```

The same with `--scenario=E3`; the high-concurrency E3 runs add `--start-concurrency=128`. Raw results are in [`v10-d271-mongo-sequences/`](v10-d271-mongo-sequences/).

## Store round trips and allocation per operation (L1)

Two rounds per arm, alternating. Counts are the result; timings only indicate.

| Scenario | | Baseline | D271 | Change |
| --- | --- | --- | --- | --- |
| E1 | round trips per op | 5.83–5.84 | 3.06 | −48% |
| E1 | allocation per op | 304–308 KB | 218–219 KB | −29% |
| E1 | throughput, p50 | 2,234–2,464/s, 24–26 ms | 3,296–3,785/s, 15–18 ms | about +50% |
| E3 | round trips per op | 12.51–12.52 | 5.12 | −59% |
| E3 | allocation per op | 651–655 KB | 404 KB | −38% |
| E3 | throughput, p50 | 980–1,055/s, 58–61 ms | 1,704–1,820/s, 32–36 ms | about +73% |

## End to end, closed loop (one node)

Throughput and p50 at each concurrency. On one machine the server, MongoDB, Kafka and the load generator share the cores: read the curves for direction.

| Concurrency | E1 baseline | E1 queue off | E1 queue on |
| --- | --- | --- | --- |
| 1 | 366/s, 2 ms | 506/s, 1 ms | 532/s, 1 ms |
| 8 | 1,115/s, 6 ms | 1,604/s, 4 ms | 1,674/s, 4 ms |
| 32 | 1,389/s, 20 ms | 2,040/s, 14 ms | 2,058/s, 14 ms |
| 128 | 1,755/s, 69 ms | 2,396/s, 51 ms | 2,696/s, 44 ms |
| 256 | 2,148/s, 113 ms | 2,439/s, 108 ms | 2,870/s, 85 ms |
| 512 | 2,772/s, 178 ms | | 3,169/s, 156 ms |

| Concurrency | E3 baseline | E3 queue off | E3 queue on |
| --- | --- | --- | --- |
| 1 | 167/s, 5 ms | 234/s, 3 ms | 245/s, 3 ms |
| 8 | 465/s, 16 ms | 748/s, 9 ms | 860/s, 8 ms |
| 64 | 702/s, 87 ms | 931/s, 65 ms | 948/s, 63 ms |
| 128 | 656–856/s, 143–191 ms | 922/s, 137 ms | 1,162/s, 102 ms |
| 256 | 976–1,090/s, 227–255 ms | 893/s, 282 ms | 1,436/s, 172 ms |
| 512 | 1,169–1,238/s, 409–430 ms | | 1,526/s, 327 ms |

The E3 rows from 128 up for the baseline and the queue on are the high-concurrency runs (`high-concurrency-E3.txt`); where the baseline ran twice, both are given.

- **Below saturation** both D271 arms carry 1.3–1.9× the baseline's rate at a lower p50.
- **With single inserts** (queue off) E3 levels off at about 900–930/s from concurrency 64, below the baseline's group commit at 512 (1,169–1,238/s): each insert pays MongoDB's per-write cost alone.
- **With the queue on**, a partition's appends under load become one `insertMany` per collection: E1 peaks at 3,169/s against 2,772/s (+14%), E3 at 1,526/s against 1,238/s (+23%), with a lower p50 at every concurrency. At low concurrency a group holds one stream, so the queue costs nothing measurable.

## On GitHub Actions after the merge

[39929517589](https://github.com/Ahoo-Wang/Wow/actions/runs/39929517589) (`suites=sustainable-mongo,e5-mongo`, 100k, `main` at `7b9c1ff30`). The sustainable rates ran on the same processor as the run before D271, [39870041854](https://github.com/Ahoo-Wang/Wow/actions/runs/39870041854) (AMD EPYC 9V45, 4 cores), so the two compare:

| Scenario | Sustainable (≤ 1% errors, p99 ≤ 200 ms) before | After | CPU ms/op before | After | Saturation before | After |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | 423/s | **771/s** (+82%) | 5.52 | 3.24 | 1,934/s | 1,646/s |
| E2 | 297/s | **569/s** (+92%) | 6.68 | 4.11 | 1,054/s | 1,401/s |
| E3 | 186/s | **259/s** (+39%) | 12.18 | 9.08 | 849/s | 1,036/s |

On that processor PostgreSQL sustained E1 1,318/s and E3 580/s: MongoDB now carries 58% and 45% of them, against about a third before. E1's closed-loop saturation is lower than before while its sustainable rate is higher; the closed loop finds the deepest queue's throughput, the sustainable rate the latency, which is what improved.

Three nodes (`e5-mongo`, AMD EPYC 7763): every steady run without a failure (E1 150/s p99 37 ms, E3 50/s p99 132 ms); no wait timed out, no signal dropped, no partition paused. Through a hand-over no second fell below half the rate; the worst second's p99 was 3.0 s (E1) and 5.9 s (E3), against 4.3 s and 7.4 s before; the failed requests are the load generator's to the stopped node (roadmap §6 item 6).

## Conclusion

D271 halves MongoDB's round trips per operation and cuts allocation by about a third. With the append queue on, which stays MongoDB's default (its reason changed: grouping inserts, no longer avoiding aborts), it is faster than `main` at every load measured.
