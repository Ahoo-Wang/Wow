# Node log digest: 1-S-mongo

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 03:00:46 | 03:00:46 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 615 | 03:05:20 | 03:26:14 |
| M3 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 1769 | 03:26:14 | 03:26:52 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 1923 | 03:26:14 | 03:26:52 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 105 | M2 × 105 |
| e1-top 4096 | 19 | M2 × 19 |
| e1-top 8192 | 23 | M2 × 23 |
| e1-zipf-sweep 1024 | 127 | M2 × 127 |
| e1-hot-sweep 1024 | 132 | M2 × 132 |
| e3-sweep 1024 | 435 | M2 × 209, M3 × 154, M4 × 72 |
| e3-sweep 4096 | 3466 | M3 × 1615, M4 × 1851 |
| outside the measurements | 1 | M1 × 1 |

## First line of each

- M1 (node-1): `2026-11-17T03:00:46.530-08:00  WARN 54325 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-17T03:05:20.560-08:00  WARN 54325 --- [transfer-service] [ctor-http-nio-7] reactor.netty.channel.FluxReceive        : [dd5ed1e2-1, L:/127.0.0.1:18080 - R:/127.0.0.1:60834] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M3 (node-1): `2026-11-17T03:26:14.430-08:00  WARN 54325 --- [transfer-service] [atcher-worker-6] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 29 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0087110 pushed back; it runs at most 128 handlings at once until handlings succeed again.`
- M4 (node-1): `2026-11-17T03:26:14.431-08:00  WARN 54325 --- [transfer-service] [tcher-worker-14] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 3 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0078572 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
