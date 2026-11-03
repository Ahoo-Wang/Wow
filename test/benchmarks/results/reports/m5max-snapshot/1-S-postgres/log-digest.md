# Node log digest: 1-S-postgres

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `me.ahoo.wow.runtime.node.JdkCheck` | Wow runs on JDK <n>, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness… |  | 1 | 23:28:00 | 23:28:00 |
| M2 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 23:28:01 | 23:28:01 |
| M3 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 318 | 23:32:08 | 00:00:47 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 6401 | 00:00:47 | 00:01:27 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 7518 | 00:00:47 | 00:01:27 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 61 | 00:00:47 | 00:01:26 |
| M7 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 35 | 00:00:47 | 00:01:27 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep.void-1 256 | 21 | M3 × 21 |
| e1-sweep 1024 | 31 | M3 × 31 |
| e1-hot-sweep 1024 | 33 | M3 × 33 |
| e3-sweep 1024 | 1258 | M3 × 233, M4 × 501, M5 × 494, M6 × 19, M7 × 11 |
| e3-sweep 4096 | 12990 | M4 × 5900, M5 × 7024, M6 × 42, M7 × 24 |
| outside the measurements | 2 | M1 × 1, M2 × 1 |

## First line of each

- M1 (node-1): `2026-11-02T23:28:00.134-08:00  WARN 35793 --- [transfer-service] [           main] me.ahoo.wow.runtime.node.JdkCheck        : Wow runs on JDK 21, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness then fails and restarts it. Run Wow on JDK 25, which d`
- M2 (node-1): `2026-11-02T23:28:01.523-08:00  WARN 35793 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M3 (node-1): `2026-11-02T23:32:08.663-08:00  WARN 35793 --- [transfer-service] [tor-http-nio-17] reactor.netty.channel.FluxReceive        : [fc1c4b7d-1, L:/127.0.0.1:18080 - R:/127.0.0.1:52257] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M4 (node-1): `2026-11-03T00:00:47.170-08:00  WARN 35793 --- [transfer-service] [atcher-worker-8] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 7 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0026799 pushed back; it runs at most 256 handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-03T00:00:47.170-08:00  WARN 35793 --- [transfer-service] [atcher-worker-6] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 39 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0034183 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-03T00:00:47.229-08:00  WARN 35793 --- [transfer-service] [tcher-worker-16] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 12 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0000350 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M7 (node-1): `2026-11-03T00:00:47.307-08:00  WARN 35793 --- [transfer-service] [atcher-worker-9] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 2 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0072933 pushed back; it runs at most null handlings at once until handlings succeed again.`
