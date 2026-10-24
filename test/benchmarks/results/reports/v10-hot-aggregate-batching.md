# V10 hot-aggregate batching: an aggregate's queued commands, one append (D273)

Roadmap §6 performance item 10. One build of `claude/hot-aggregate-batching` (the executor's lanes batch, `Kernel.executeBatch`, `appendAll` of one aggregate's consecutive streams as a prefix), measured on the local machine of the [local baseline](v10-local-baseline.md) (Apple M4, Docker Desktop) on 2026-10-24. The two arms differ only in `wow.executor.max-batch`: 1 (every command alone, as before) against the default 32, set through `SPRING_APPLICATION_JSON` on fresh primed sessions (100,000 accounts, 1 GB heap, one node).

## Saturation where one aggregate is hot

Each session: a closed-loop sweep of E1 (deposit, answered once stored) with one hot account taking half the commands (`--distribution=hot --hot-keys=1`), then one with Zipf keys, concurrency 1 to 256, 10 s a step ([`batch-ab.sh`](v10-hot-aggregate-batching/sweeps/batch-ab.sh), results beside it). Rounds 1 and 2 ran the arms in the order 1, 32; round 3 in the order 32, 1.

| Peak, per second | Round 1 | Round 2 | Round 3 (reversed) |
| --- | --- | --- | --- |
| PostgreSQL, one hot account | 3,180 → 8,172 (2.57×) | 2,593 → 5,977 (2.31×) | 2,708 → 7,100 (2.62×) |
| PostgreSQL, Zipf | 4,214 → 6,409 (1.52×) | 3,993 → 6,021 (1.51×) | 4,174 → 7,168 (1.72×) |
| MongoDB, one hot account | 1,843 → 3,958 (2.15×) | 1,566 → 5,837 (3.73×) | 1,970 → 6,456 (3.28×) |
| MongoDB, Zipf | 2,695 → 4,860 (1.80×) | 2,674 → 5,908 (2.21×) | 2,853 → 6,553 (2.30×) |

- Alone, the throughput stops growing from concurrency 8 (PostgreSQL 2,600–3,200/s, MongoDB 1,570–1,970/s with the hot account): the hot account's lane commits one command per append, and more clients only queue behind it. Batched, it keeps growing to concurrency 128–256; most of the batched peaks are at the sweep's top step, so they are lower bounds.
- 1.5–2.6 times on PostgreSQL, 1.8–3.7 times on MongoDB, whichever arm ran first.

## The cost per command where nothing queues

Uniform keys over 100,000 accounts almost never put two commands of one account in a lane at once, so both arms run every command alone, through `Kernel.execute`. Fresh sessions in the order 1, 32, 32, 1, each E1 at a fixed rate first, then a sweep of one hot account from concurrency 1 to 8, 20 s a step ([`batch-clean.sh`](v10-hot-aggregate-batching/clean/batch-clean.sh), results beside it):

| Per E1 command, uniform | 1 (sessions 1, 4) | 32 (sessions 2, 3) |
| --- | --- | --- |
| PostgreSQL, node CPU | 0.641, 0.531 ms | 0.554, 0.524 ms |
| PostgreSQL, allocated | 87.5, 86.9 KB | 86.4, 86.6 KB |
| PostgreSQL, rows fetched | 2.862, 2.868 | 2.868, 2.871 |
| PostgreSQL, p50 / p99 | 0.74 / 4.0, 0.57 / 2.4 ms | 0.60 / 3.0, 0.56 / 2.4 ms |
| MongoDB, node CPU | 0.634, 0.589 ms | 0.595, 0.597 ms |
| MongoDB, allocated | 134.4, 136.2 KB | 135.5, 136.2 KB |
| MongoDB, queries | 0.046, 0.046 | 0.046, 0.046 |
| MongoDB, p50 / p99 | 2.11 / 11.6, 1.78 / 8.8 ms | 1.77 / 11.8, 1.75 / 9.4 ms |

| One hot account, per second | 1 (sessions 1, 4) | 32 (sessions 2, 3) |
| --- | --- | --- |
| PostgreSQL, concurrency 1 | 1,762, 1,832 | 1,844, 1,833 |
| PostgreSQL, concurrency 8 | 2,349, 2,730 | 4,029, 4,047 |
| MongoDB, concurrency 1 | 883, 871 | 862, 885 |
| MongoDB, concurrency 8 | 1,912, 1,889 | 3,025, 3,039 |

- The lane costs nothing where it does not batch: the CPU, bytes and reads per command and the throughput of one client are the same within the sessions' spread (the first session of each backend, an arm of 1, is the slowest; the last, also of 1, matches the arm of 32).
- Batching starts paying from a few clients of one aggregate: at concurrency 8, 48–72% more on PostgreSQL and about 60% more on MongoDB, at a lower p50.

## A confound, and what it shows about the state cache

The sweeps' sessions also ran E1 at a fixed rate with uniform keys after the two sweeps. There the batched arm cost more per command, whichever arm ran first: on PostgreSQL 3.2–3.3% more bytes in each round, on MongoDB 3.6% in two of three, MongoDB's queries 0.089–0.122 against 0.068–0.071, PostgreSQL's rows 3.50–3.56 against 3.31–3.39, PostgreSQL's p99 about three times. Both arms ran the same code there (batches of one), and on fresh sessions (above) the same E1 costs the same: 0.046 queries, 2.87 rows.

The difference is what the sweeps left in the state cache. Each cached state's recent-request rings grow with the requests its aggregate committed (decision D261, B.O-4), and the cache is bounded by their estimated bytes. The batched sweeps committed about twice the commands, so more accounts' rings grew and the same bytes hold fewer states: the E1 after them loads more. A node that serves hot aggregates holds fewer of the others for it; the item stays with the state cache (roadmap §6 item 13), not with batching.

## Correctness

Kernel tests (`KernelBatchTest`): commands committed with one append, in order; a batch ending before a command that appends nothing, decides with I/O or repeats a request; a stale state whose first stream conflicts, decided again alone. Executor tests: batches of 2–4 behind a held command, `max-batch=1` with no `appendAll`, a cancelled caller leaving the lane to the others. The event-log TCK runs `appendAll` with one aggregate's consecutive streams on every backend: all committed, none after a conflict, none after a duplicate.
