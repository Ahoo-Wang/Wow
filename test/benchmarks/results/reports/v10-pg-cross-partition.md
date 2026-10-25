# V10 PostgreSQL: several partitions' groups in one statement (D280)

Roadmap §6 item 15, after D275 on MongoDB: the append queue's mergers hand several partitions' groups to one `appendAll`, and PostgreSQL commits them with one statement. Measured on the local machine of the [local baseline](v10-local-baseline.md) (Apple M4, Docker Desktop, the store on disk) on 2026-10-25; the scripts and the runs are in [`v10-pg-cross-partition/`](v10-pg-cross-partition/).

## The statement

[`PostgresEventLogTables.appendGroupAcross`](../../../../backends/postgres/src/main/kotlin/me/ahoo/wow/postgres/PostgresEventLogTables.kt) is the single-partition group commit with each stream's partition beside it:

- each partition takes its own run of sequences, bumping its own counter by its streams that fit, ranked in the group's order: each partition's sequences stay contiguous and in order (D10, D250);
- the counter rows are locked first, in partition order (`SELECT … ORDER BY partition FOR UPDATE`), before anything is bumped or inserted, and the request keys after them in the existing order: two groups that share partitions take them in one order, and a group waiting on a counter holds no request key;
- a definite abort (a unique key, a refused value) is retried a partition at a time, each as a single-partition group: one partition's conflict or duplicate decides that partition only. An unknown outcome is unknown for every stream, as before;
- the partitions are bound parameters, so the statement's text depends on the group's shape alone and PostgreSQL plans it once per shape. The first version wrote them as literals (below).

## L1: merging off against on

The kernel and the store in one JVM through the append queue, one build, `wow.log.append-queue.merge` off (A) and on (B), A B B A, 100,000 accounts ([`l1-ab.sh`](v10-pg-cross-partition/l1-ab.sh); the statement's first version, with the partitions as literals). PostgreSQL's CPU, commits and WAL syncs are sampled beside each run, per operation including the accounts' seeding:

| | E1, 256 | E1, 64 | E3, 256 | E3, 64 |
| --- | --- | --- | --- | --- |
| Throughput | 7,031–8,871 → 10,870–12,038/s | 5,298–5,658 → 7,207–7,354/s | 2,493–2,588 → 3,268–3,351/s | 2,080–2,089 → 2,390–2,410/s |
| p50 | 25.8–34.5 → 18.9–19.8 ms | 10.3–10.9 → 7.9–8.0 ms | 94.6–99.4 → 74.7–77.5 ms | 29.5–29.9 → 25.7–25.8 ms |
| Store round trips per op | 0.42 → 0.16 | 1.05 → 0.50 | 1.00 → 0.45 | 2.84 → 1.37 |
| Commits per op | 1.43 → 0.82 | 1.84 → 1.08 | 1.59 → 0.88 | 2.35 → 1.24 |
| WAL syncs per op | 0.34 → 0.10 | 0.52 → 0.28 | 0.42 → 0.14 | 0.70 → 0.40 |
| PostgreSQL CPU per op | 1.24–1.42 → 1.34–1.35 ms | 1.34–1.35 → 1.40–1.41 ms | 1.72–1.74 → 1.46–1.48 ms | about the same |

PostgreSQL already merges concurrent commits' WAL flushes (D267 ⑲), yet the syncs fell by half or more: one statement's commit covers what several partitions' commits did.

## End to end

`main` (A) against the branch (B) on one node, PostgreSQL on disk (no `--tmpfs`: commits and syncs are what changes), 1 GB heap, 100,000 accounts, fresh sessions A B B A.

**The first version regressed at moderate rates** ([`e2e-1-literal-partitions`](v10-pg-cross-partition/e2e-1-literal-partitions/COMPARE.md)): E1 at 2,500/s had a p50 of 3.8–4.4 ms against 0.7–1.1, PostgreSQL's CPU per command 0.70 ms against 0.31–0.36. Its text named each stream's partition, so nearly every call was a new statement, parsed and planned anew (pgjdbc prepares a statement server-side after five uses of one text); a single-partition group's text depends on its size alone and repeats. With the partitions bound as parameters ([`e2e2`](v10-pg-cross-partition/e2e2/COMPARE.md)):

| Fixed rates | A | B | B/A |
| --- | --- | --- | --- |
| E1 at 2,500/s: p50 | 1.22, 0.93 ms | 1.10, 1.10 ms | 1.02 |
| E1: PostgreSQL CPU per op | 0.385, 0.325 ms | 0.355, 0.361 ms | 1.01 |
| E1: commits per op | 1.89, 1.91 | 1.83, 1.74 | 0.94 |
| E3 at 700/s: p50 | 1.78, 1.21 ms | 1.54, 1.19 ms | 0.92 |
| E3: PostgreSQL CPU per op | 1.08, 0.87 ms | 1.04, 0.89 ms | 0.98 |

At these rates the partitions' groups seldom meet in a merger, and the costs are the same. Under load they do ([`e2e3`](v10-pg-cross-partition/e2e3/), closed-loop sweeps, 30 s a step):

| Closed loop | A (sessions 1, 4) | B (sessions 2, 3) |
| --- | --- | --- |
| E1 at 64 | 6,031, 5,344/s | 7,561, 8,548/s (+42%) |
| E1 at 256 | 7,585, 6,065/s | 8,634, 9,701/s (+34%) |
| E1 at 1,024 (the top step) | 11,384, 9,285/s | 9,864, 11,119/s |
| E3 at 64 | 2,208, 1,950/s | 2,304, 2,534/s (+15%) |
| E3 at 1,024 (the top step) | 3,064, 2,542/s | 4,578, 4,200/s (+57%) |
| E3 at 1,024: p99 | 782, 856 ms | 479, 614 ms |

- E1 gains 34–42% where the node's partitions append at once and the store is not yet the limit; at the top step both reach about the same.
- E3, three appends of a transfer's commands a saga waits on, gains 57% at the top step with a lower tail.
- At one to four clients, the steps are within the sessions' spread.

## Correctness

Kernel and store tests: the TCK's cross-partition case (a conflict and a duplicate in one partition, the others committed) now runs on PostgreSQL; a group of four partitions is one statement, and a duplicate in one of them retries each partition alone; 24 groups across overlapping partitions in shuffled orders commit at once with no deadlock and each partition's sequences contiguous.
