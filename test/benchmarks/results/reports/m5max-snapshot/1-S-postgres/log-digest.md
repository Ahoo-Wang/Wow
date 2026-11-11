# Node log digest: 1-S-postgres

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 22:33:58 | 22:33:58 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 650 | 22:40:10 | 22:59:45 |
| M3 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription wow.transport-sink.account throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succ… |  | 5409 | 22:44:56 | 23:00:42 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription wow.transport-sink.account throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings suc… |  | 6 | 22:48:24 | 22:56:52 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 7476 | 22:59:45 | 23:00:41 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 8358 | 22:59:45 | 23:00:43 |
| M7 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 3 | 22:59:47 | 23:00:40 |
| M8 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 2 | 22:59:52 | 23:00:25 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-top 4096 | 281 | M2 × 281 |
| e1-zipf-sweep 1024 | 138 | M2 × 138 |
| e1-zipf-sweep 4096 | 2897 | M3 × 2897 |
| e1-zipf-top 4096 | 925 | M3 × 924, M4 × 1 |
| e1-hot-sweep 1 | 1 | M3 × 1 |
| e1-hot-sweep 4 | 2 | M3 × 2 |
| e1-hot-sweep 256 | 1 | M3 × 1 |
| e1-hot-sweep 1024 | 271 | M2 × 79, M3 × 191, M4 × 1 |
| e1-hot-top 4096 | 1 | M3 × 1 |
| e3-sweep 4 | 697 | M3 × 693, M4 × 4 |
| e3-sweep 1024 | 1042 | M2 × 152, M5 × 472, M6 × 418 |
| e3-sweep 4096 | 15140 | M3 × 688, M5 × 6847, M6 × 7601, M7 × 2, M8 × 2 |
| outside the measurements | 509 | M1 × 1, M3 × 11, M5 × 157, M6 × 339, M7 × 1 |

## First line of each

- M1 (node-1): `2026-11-10T22:33:58.864-08:00  WARN 91026 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-10T22:40:10.293-08:00  WARN 91026 --- [transfer-service] [tor-http-nio-18] reactor.netty.channel.FluxReceive        : [eed6ee4d-1, L:/127.0.0.1:18080 - R:/127.0.0.1:55060] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M3 (node-1): `2026-11-10T22:44:56.052-08:00  WARN 91026 --- [transfer-service] [atcher-worker-6] m.a.w.r.subscription.PartitionThrottle   : Subscription wow.transport-sink.account throttles partition 3 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0007527 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M4 (node-1): `2026-11-10T22:48:24.695-08:00  WARN 91026 --- [transfer-service] [tcher-worker-18] m.a.w.r.subscription.PartitionThrottle   : Subscription wow.transport-sink.account throttles partition 53 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0000241 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-10T22:59:45.256-08:00  WARN 91026 --- [transfer-service] [atcher-worker-2] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 46 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0034467 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-10T22:59:45.256-08:00  WARN 91026 --- [transfer-service] [tcher-worker-19] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 46 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0021098 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M7 (node-1): `2026-11-10T22:59:47.663-08:00  WARN 91026 --- [transfer-service] [tcher-worker-16] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 40 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0027612 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M8 (node-1): `2026-11-10T22:59:52.201-08:00  WARN 91026 --- [transfer-service] [tcher-worker-11] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 51 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0086375 pushed back; it runs at most null handlings at once until handlings succeed again.`
