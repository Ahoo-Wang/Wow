# Node log digest: 1-S-mongo

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 23:01:10 | 23:01:10 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 106 | 23:05:47 | 23:25:30 |
| M3 | ERROR | `me.ahoo.wow.http.WowRoutes` | A request failed with an unexpected error. | java.net.SocketException: Connection reset | 1 | 23:05:47 | 23:05:47 |
| M4 | ERROR | `reactor.core.publisher.Operators` | Operator called default onErrorDropped | java.net.SocketException: Connection reset | 1 | 23:05:47 | 23:05:47 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription wow.transport-sink.account throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succ… |  | 251 | 23:18:56 | 23:26:22 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription wow.transport-sink.account throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings suc… |  | 69 | 23:18:56 | 23:24:37 |
| M7 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 4285 | 23:25:30 | 23:26:22 |
| M8 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 5614 | 23:25:31 | 23:26:23 |
| M9 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 25 | 23:25:31 | 23:26:20 |
| M10 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 12 | 23:25:31 | 23:26:12 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 76 | M2 × 74, M3 × 1, M4 × 1 |
| e1-hot-sweep 256 | 14 | M5 × 13, M6 × 1 |
| e1-hot-sweep 1024 | 1 | M2 × 1 |
| e3-sweep 1 | 41 | M5 × 29, M6 × 12 |
| e3-sweep 256 | 205 | M5 × 149, M6 × 56 |
| e3-sweep 1024 | 43 | M2 × 31, M7 × 12 |
| e3-sweep 4096 | 9728 | M10 × 12, M5 × 58, M7 × 4272, M8 × 5361, M9 × 25 |
| outside the measurements | 257 | M1 × 1, M5 × 2, M7 × 1, M8 × 253 |

## First line of each

- M1 (node-1): `2026-11-10T23:01:10.369-08:00  WARN 31645 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-10T23:05:47.287-08:00  WARN 31645 --- [transfer-service] [ctor-http-nio-3] reactor.netty.channel.FluxReceive        : [4e9ddba1-1, L:/127.0.0.1:18080 - R:/127.0.0.1:53651] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M3 (node-1): `2026-11-10T23:05:47.293-08:00 ERROR 31645 --- [transfer-service] [tor-http-nio-18] me.ahoo.wow.http.WowRoutes               : A request failed with an unexpected error.`; cause `java.net.SocketException: Connection reset`
- M4 (node-1): `2026-11-10T23:05:47.463-08:00 ERROR 31645 --- [transfer-service] [tor-http-nio-18] reactor.core.publisher.Operators         : Operator called default onErrorDropped`; cause `java.net.SocketException: Connection reset`
- M5 (node-1): `2026-11-10T23:18:56.466-08:00  WARN 31645 --- [transfer-service] [atcher-worker-3] m.a.w.r.subscription.PartitionThrottle   : Subscription wow.transport-sink.account throttles partition 41 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0059900 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-10T23:18:56.488-08:00  WARN 31645 --- [transfer-service] [tcher-worker-12] m.a.w.r.subscription.PartitionThrottle   : Subscription wow.transport-sink.account throttles partition 41 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0032280 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M7 (node-1): `2026-11-10T23:25:30.864-08:00  WARN 31645 --- [transfer-service] [tcher-worker-18] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 11 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0048207 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M8 (node-1): `2026-11-10T23:25:31.048-08:00  WARN 31645 --- [transfer-service] [atcher-worker-2] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 5 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0018114 pushed back; it runs at most 256 handlings at once until handlings succeed again.`
- M9 (node-1): `2026-11-10T23:25:31.198-08:00  WARN 31645 --- [transfer-service] [atcher-worker-5] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 62 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0018911 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M10 (node-1): `2026-11-10T23:25:31.361-08:00  WARN 31645 --- [transfer-service] [tcher-worker-16] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 11 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0027657 pushed back; it runs at most null handlings at once until handlings succeed again.`
