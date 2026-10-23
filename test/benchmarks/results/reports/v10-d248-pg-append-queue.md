# D248 ④: the append queue on PostgreSQL

Whether the partition append queue should be on by default on PostgreSQL (decision D248), measured on 2026-10-23. With the queue on, a partition's appends queued together commit as one statement (`appendAll`); off, each append is its own statement.

## Setup

| | |
| --- | --- |
| Commit | `main` at `fa3e85b28` |
| Arms | the queue off (the default then) and on (`SPRING_APPLICATION_JSON={"wow.log.append-queue.enabled":true}`), alternating, two rounds |
| Machine | Apple M4, 10 cores, 16 GB; PostgreSQL, Kafka and the server on this machine, JDK 21 |
| Data | 100,000 accounts, uniform keys |

```bash
SPRING_APPLICATION_JSON='{"wow.log.append-queue.enabled":true}' test/benchmarks/harness/bench.sh saturate --version=v10 --backend=postgres --scenario=E1 --accounts=100000 --seed-concurrency=128 --max-concurrency=512
```

The same without the property for the queue off, and with `--scenario=E3`. Raw results are in [`v10-d248-pg-append-queue/`](v10-d248-pg-append-queue/), one file per run with its per-step lines. The closed loop stops once throughput grows less than 5% twice, so an arm that levels off stops below 512.

## Closed-loop saturation

Throughput and p50 at each concurrency, rounds 1 and 2.

| Concurrency | E1 off | E1 on | E3 off | E3 on |
| --- | --- | --- | --- | --- |
| 1 | 1,640 / 1,633/s | 1,456 / 1,598/s | 591 / 655/s | 578 / 661/s |
| 8 | 3,374 / 3,495/s | 3,206 / 3,281/s | 1,168 / 1,423/s | 1,143 / 1,343/s |
| 32 | 4,129 / 4,366/s, 7 ms | 4,012 / 4,238/s, 7 ms | 1,402 / 1,644/s, 22 / 19 ms | 1,125 / 1,606/s, 24 / 19 ms |
| 64 | 4,267 / 4,586/s, 15 / 14 ms | 4,080 / 4,347/s, 14 ms | 1,426 / 1,709/s, 45 / 38 ms | 1,876 / 1,724/s, 33 / 36 ms |
| 128 | 4,470 / 4,648/s, 29 ms | 4,399 / 4,835/s, 28 / 25 ms | – / 1,791/s, 74 ms | 2,230 / 2,010/s, 56 / 62 ms |
| 256 | – / 4,808/s, 56 ms | 4,828 / 5,508/s, 52 / 46 ms | | 2,381 / 2,130/s |
| 512 | | 4,951 / 5,941/s, 103 / 87 ms | | 2,515 / 2,469/s |
| Saturation | 4,470 / 4,808/s | **4,951 / 5,941/s** | 1,426 / 1,791/s | **2,515 / 2,469/s** |

- **Off**, E1 levels off at concurrency 128–256 and E3 at 64–128: each append's statement and commit bound the rate.
- **On**, both keep growing to 512: E1 +11% and +24% at saturation, E3 +76% and +38%, with a lower p50 at the same high concurrency.
- **At low concurrency** the queue costs 1–5% of throughput at the same concurrency, the p50 unchanged; single points differ more, within the noise of one machine.

## On GitHub Actions after the merge

[39956730984](https://github.com/Ahoo-Wang/Wow/actions/runs/39956730984) (`sustainable-pg`, 100k, `main` at `896081659`, the queue on), on an AMD EPYC 7763 runner (4 cores):

| Scenario | Sustainable (≤ 1% errors, p99 ≤ 200 ms) | Saturation | Share | CPU ms/op |
| --- | --- | --- | --- | --- |
| E1 | 909/s | 1,939/s | 47% | 2.59 |
| E3 | 426/s | 758/s | 56% | 6.06 |

The earlier `sustainable-pg` runs were on other processors (Xeon 8370C, EPYC 9V45, Xeon 8573C), so their rates do not divide by these; the shares of saturation are in their range (E1 47–53%, E3 50–56%). This run neither confirms nor refutes the local gain: that takes the queue off and on paired on one runner, which the `pg-append-queue` suite does. Near the limit the E1 probe at 970/s still began its measured phase with a queue (p99 1.6 s in its first second, decaying to under 100 ms from the sixth), the case roadmap §6 item 6 keeps as an anomaly.

### Off and on paired on one runner

[39968017660](https://github.com/Ahoo-Wang/Wow/actions/runs/39968017660) (the `pg-append-queue` suite, E1, 100k, Xeon 8573C): each arm's closed-loop saturation once, then two rounds of both arms at one offered load, 1,415/s (60% of the queue off's saturation).

| | Queue off | Queue on | On/off |
| --- | --- | --- | --- |
| Closed-loop saturation | 2,359/s | 2,224/s | 0.94 |
| Failed at 1,415/s | 1,051–1,557 (`429 wow.command.busy`) | 0 | |
| p50 at 1,415/s | 122–131 ms | 100–108 ms | 0.82 |
| p99 at 1,415/s | 4.36–5.34 s | 1.46–1.81 s | 0.27–0.42 |
| CPU ms/op | 1.91–1.94 | 1.90–1.94 | 1.0 |

The suite's verdict reads "off", from the saturation alone: one closed-loop run per arm, 6% lower with the queue on, at the edge of a single run's noise, where the two local rounds found it 11–24% higher. At the same offered load, which is what a service sees, the queue on refused nothing where off refused 1,000–1,500 commands (the node's in-flight bound), and its p99 was a third. All four rounds are marked unstable: 1,415/s is near this runner's limit, and each began its measured phase with a queue.

## Conclusion

The queue goes on by default for PostgreSQL, as it is for MongoDB (decision D271 ⑧): at the same load it refuses nothing and its tail is shorter, and its closed-loop ceiling is higher locally and within a single run's noise on the 4-core runner. With both on, the per-store preset (`Store.queuesAppends`) has nothing left to choose and is removed; `wow.log.append-queue.enabled=false` turns the queue off.
