# Node log digest: r1-main

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 15:35:00 | 15:35:00 |
| M2 | WARN | `m.a.w.r.cluster.PartitionOwnership` | node-<n> lost the lease of partition <n> of transfer-service/transfer. |  | 5 | 15:38:29 | 15:38:29 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| outside the measurements | 6 | M1 × 1, M2 × 5 |

## First line of each

- M1 (node-1): `2026-11-11T15:35:00.094-08:00  WARN 62750 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-11T15:38:29.136-08:00  WARN 62750 --- [transfer-service] [atcher-worker-2] m.a.w.r.cluster.PartitionOwnership       : node-1 lost the lease of partition 8 of transfer-service/transfer.`
