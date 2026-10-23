# D271 spike: owner-assigned sequences on MongoDB

Whether appending on MongoDB without a transaction pays (decision D271, roadmap §6 performance item 3), measured on 2026-10-22 against `main`. The spike covers the happy path only, as an upper bound of the gain: it is not the design that ships.

## Setup

| | |
| --- | --- |
| Baseline | `main` at `099e932efe`: an append is one multi-document transaction (read the version, `findAndModify` the partition counter, insert, commit), behind the partition append queue |
| Spike | the same commit with [`spike.patch`](v10-d271-mongo-sequences-spike/spike.patch): each partition takes blocks of 256 sequences from its counter, an append is one `insertOne` (`w: majority`) at the next one, the unique keys refuse a taken version or request; the append queue is off on MongoDB. A definite failure (a conflict, a refusal) writes a placeholder at its sequence, which the tail steps over; the first batch ran before placeholders were added (see below) |
| Not in the spike | the block list, closing a block, crash recovery, interleaved blocks of two nodes, an unknown outcome's hole |
| Machine | Apple M4, 10 cores, 16 GB; MongoDB, Kafka and the server in Docker or on the host, all on this machine, JDK 21 |
| Data | 100,000 accounts, uniform keys; MongoDB single-node replica set |

Commands, from the repository root, with `--install` the baseline's or the spike's `installDist` output:

```bash
test/benchmarks/harness/bench.sh component --backend=mongo --scenario=E1 --accounts=100000 --install=<dir>
test/benchmarks/harness/bench.sh saturate --version=v10 --backend=mongo --scenario=E1 --accounts=100000 --seed-concurrency=128 --max-concurrency=256 --install=<dir>
```

The same with `--scenario=E3`. Raw results are in [`v10-d271-mongo-sequences-spike/`](v10-d271-mongo-sequences-spike/): each component run's `component.json`, and each saturation's per-step lines.

## Store round trips and allocation per operation (L1)

`bench.sh component`: the domain on the kernel and the store in one JVM, closed loop. Counts are the result; timings only indicate.

| Scenario | | Baseline | Spike | Change |
| --- | --- | --- | --- | --- |
| E1 | round trips per op | 5.82–5.83 | 3.08–3.09 | **−47%** |
| E1 | allocation per op | 305 KB | 218–222 KB | **−28%** |
| E3 | round trips per op | 12.50–12.52 | 5.18–5.20 | **−58%** |
| E3 | allocation per op | 658–662 KB | 417–422 KB | **−37%** |

The baseline's E1 issues per operation `find` 1.86, `aggregate` 1.06, `findAndModify` 0.93, `insert` 0.93 and `commitTransaction` 0.93; the spike's `find` 1.00, `aggregate` 1.00 and `insert` 1.00. The version read, the counter and the commit are gone; the snapshot read and the tail's read remain. An E3 transfer appends three times, so it gains more.

The first spike batch had no placeholders: a conflicting append in E3 left its sequence empty, the tail waited at it for good and a saga wait timed out (`component-spike-E3-2`, not kept). The second batch, with placeholders, ran without errors, and its counts equal the first's.

## End to end, closed loop (one node)

`bench.sh saturate`: throughput and p50 at each concurrency. On one machine the server, MongoDB, Kafka and the load generator share the cores, and two baseline runs of E1 differ by up to 24% at saturation: read the curves for direction.

| Concurrency | E1 baseline | E1 spike | E3 baseline | E3 spike |
| --- | --- | --- | --- | --- |
| 1 | 343–407/s, p50 2 ms | 505/s, p50 1 ms | 161/s, p50 5 ms | 241/s, p50 3 ms |
| 8 | 1,036–1,285/s, p50 5–7 ms | 1,622/s, p50 4 ms | 419/s, p50 17 ms | 723/s, p50 9 ms |
| 32 | 1,457/s, p50 19 ms | 1,810/s, p50 15 ms | 565/s, p50 53 ms | 1,057/s, p50 27 ms |
| Saturation | 2,030–2,511/s at 256 | 2,219/s at 256 | 977/s at 256, p50 254 ms | 1,087/s at 128, p50 100 ms |

Below saturation, where latency sets the throughput, E3 carries about 1.5–1.9× the rate at a lower p50, and E1 1.25–1.6×. At saturation the gain is about 10%: on this machine something else bounds both. The spike E1 saturation of the first batch was disturbed by a build on the same machine and is not kept.

## Conclusion

The gain is real and largest where Wow 10 on MongoDB fell short in the L4 run (v10-vs-9x): at moderate load, where E1's p50 was 2–4× 9.x's and the sustainable rates 16–22% of saturation. The spike also showed that the placeholders are not optional. D271 goes ahead with the full design (decision D271, A′).
