# Node log digest: 1-S-mongo

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 00:06:10 | 00:06:10 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 132 | 00:10:50 | 00:29:52 |
| M3 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 5391 | 00:29:52 | 00:30:32 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 6603 | 00:29:52 | 00:30:32 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 24 | 00:29:52 | 00:30:26 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 48 | 00:29:53 | 00:30:25 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 7 | M2 × 7 |
| e1-zipf-sweep 1024 | 72 | M2 × 72 |
| e1-hot-sweep 1024 | 32 | M2 × 32 |
| e3-sweep 1024 | 102 | M2 × 21, M3 × 48, M4 × 30, M5 × 3 |
| e3-sweep 4096 | 11985 | M3 × 5343, M4 × 6573, M5 × 21, M6 × 48 |
| outside the measurements | 1 | M1 × 1 |

## First line of each

- M1 (node-1): `2026-11-12T00:06:10.820-08:00  WARN 28780 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-12T00:10:50.145-08:00  WARN 28780 --- [transfer-service] [tor-http-nio-14] reactor.netty.channel.FluxReceive        : [0dbdfde8-1, L:/127.0.0.1:18080 - R:/127.0.0.1:50736] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M3 (node-1): `2026-11-12T00:29:52.589-08:00  WARN 28780 --- [transfer-service] [atcher-worker-9] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 55 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0081680 pushed back; it runs at most 8 handlings at once until handlings succeed again.`
- M4 (node-1): `2026-11-12T00:29:52.722-08:00  WARN 28780 --- [transfer-service] [atcher-worker-5] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 32 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0062857 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-12T00:29:52.802-08:00  WARN 28780 --- [transfer-service] [atcher-worker-2] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 19 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0049060 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-12T00:29:53.313-08:00  WARN 28780 --- [transfer-service] [atcher-worker-5] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 16 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0009735 pushed back; it runs at most null handlings at once until handlings succeed again.`
