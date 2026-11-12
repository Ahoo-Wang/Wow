# Node log digest: 1-S-postgres

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 23:39:42 | 23:39:42 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 204 | 23:44:28 | 00:05:04 |
| M3 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription wow.transport-sink.account throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succ… |  | 22 | 23:53:36 | 00:05:37 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 6330 | 00:05:04 | 00:05:43 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 7604 | 00:05:04 | 00:05:44 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 40 | 00:05:04 | 00:05:31 |
| M7 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 30 | 00:05:04 | 00:05:42 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 11 | M2 × 11 |
| e1-top 4096 | 82 | M2 × 82 |
| e1-zipf-sweep 1024 | 78 | M2 × 78 |
| e1-zipf-top 4096 | 3 | M3 × 3 |
| e3-sweep 4096 | 14056 | M2 × 33, M3 × 19, M4 × 6330, M5 × 7604, M6 × 40, M7 × 30 |
| outside the measurements | 1 | M1 × 1 |

## First line of each

- M1 (node-1): `2026-11-11T23:39:42.479-08:00  WARN 20322 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-11T23:44:28.206-08:00  WARN 20322 --- [transfer-service] [tor-http-nio-16] reactor.netty.channel.FluxReceive        : [1edf9929-1, L:/127.0.0.1:18080 - R:/127.0.0.1:65281] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M3 (node-1): `2026-11-11T23:53:36.877-08:00  WARN 20322 --- [transfer-service] [atcher-worker-5] m.a.w.r.subscription.PartitionThrottle   : Subscription wow.transport-sink.account throttles partition 25 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0000010 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M4 (node-1): `2026-11-12T00:05:04.098-08:00  WARN 20322 --- [transfer-service] [tcher-worker-13] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 14 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0061946 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-12T00:05:04.118-08:00  WARN 20322 --- [transfer-service] [tcher-worker-15] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 63 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0092059 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-12T00:05:04.160-08:00  WARN 20322 --- [transfer-service] [atcher-worker-4] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 11 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0090710 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M7 (node-1): `2026-11-12T00:05:04.184-08:00  WARN 20322 --- [transfer-service] [atcher-worker-8] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 7 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0013359 pushed back; it runs at most null handlings at once until handlings succeed again.`
