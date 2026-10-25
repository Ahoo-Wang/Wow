# V10 E3: where a transfer's cost goes

Roadmap §6 item 16 asked why an E3 operation (a transfer, answered once the saga's entry is committed) costs the node about three times what an E1 operation (a deposit) does, before choosing what to optimize. Measured on the local machine of the [local baseline](v10-local-baseline.md) (Apple M4, Docker Desktop) on 2026-10-25 at `5adb2ece2`: one session per backend, the stores in memory (`--tmpfs`), 1 GB heap, 100,000 accounts, a flight recording of the node, E1 at 1,720/s then E3 at 700/s, 30 s warm-up and 60 s measured ([`v10-e3-cost/`](v10-e3-cost/), `run.sh`; `breakdown.txt` holds the tables below in full).

## A transfer is three commands

| Per operation | PostgreSQL E1 | PostgreSQL E3 | MongoDB E1 | MongoDB E3 |
| --- | --- | --- | --- | --- |
| Node CPU | 0.401 ms | 1.186 ms (0.395 a command) | 0.541 ms | 1.629 ms (0.543 a command) |
| Streams inserted | 1.01 | 3.03 | 1.01 | 3.03 |
| Commits / updates | 2.02 commits | 6.07 commits | 1.30 updates | 3.77 updates |
| Rows fetched / queries | 2.71 rows | 8.50 rows | 0.003 queries | 0.008 queries |

A transfer is the source's `Prepare`, the target's `Entry` and the source's `Confirm`, the saga sending the second and third: three commands, each committing a stream, publishing its state and writing a snapshot. Per command, E3 costs what E1 does, within 2%, and its store work is three times E1's. The saga's own handling is small beside them (the binding and its context, about 0.03 ms per transfer in the samples).

So there is no E3-specific cost to remove: what lowers a command's cost lowers a transfer's three times. The work of roadmap §6 on the per-command path (D268–D280: the reads, the cache, the batching and group commits) already applies to it; D280's cross-partition group commit, for instance, raised E3's closed-loop peak on PostgreSQL by 57%, more than E1's, since a transfer's three appends land in different partitions at once ([report](v10-pg-cross-partition.md)).

## A caution on the samples

Bucketing the CPU samples (`jdk.ExecutionSample`) by component put 46% of E3's node CPU on PostgreSQL into the Kafka producer, and 23% on MongoDB, against 12% and 8% in E1. The threads' own CPU (`jdk.ThreadCPULoad`) says otherwise: the producer's network thread used 0.05–0.07 ms per transfer, about 5% of the node. The samples under-represent the work on virtual threads (the store calls, the saga's), so a platform thread that runs all the time (Kafka's sender, Netty's event loops) takes a share far beyond its cost when most of the work is virtual, and the per-component shares of E3 above are not costs. The E1 runs' thread loads also include the session's seeding and priming (the recordings start with the node), so only E3's are clean:

| E3, cores by thread | PostgreSQL | MongoDB |
| --- | --- | --- |
| Coroutine workers (`DefaultDispatcher-worker`) | 0.39 (0.558 ms/op) | 0.48 (0.689 ms/op) |
| Virtual threads' carriers (`ForkJoinPool-worker`) | 0.23 (0.324 ms/op) | 0.36 (0.508 ms/op) |
| Netty (`reactor-http-nio`) | 0.04 (0.063 ms/op) | 0.08 (0.117 ms/op) |
| Kafka producer | 0.04 (0.054 ms/op) | 0.05 (0.072 ms/op) |

A later breakdown by component should read the samples within each kind of thread and scale them by that thread kind's measured CPU, from a recording started after the priming.
