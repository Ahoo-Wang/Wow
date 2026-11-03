# Node log digest: 1-S-mongo

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `me.ahoo.wow.runtime.node.JdkCheck` | Wow runs on JDK <n>, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness… |  | 1 | 00:01:56 | 00:01:56 |
| M2 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 00:01:58 | 00:01:58 |
| M3 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 93 | 00:06:35 | 00:24:14 |
| M4 | ERROR | `me.ahoo.wow.http.WowRoutes` | A request failed with an unexpected error. | java.net.SocketException: Connection reset | 1 | 00:08:00 | 00:08:00 |
| M5 | ERROR | `reactor.core.publisher.Operators` | Operator called default onErrorDropped | java.net.SocketException: Connection reset | 1 | 00:08:00 | 00:08:00 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 5202 | 00:24:15 | 00:24:55 |
| M7 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 6947 | 00:24:15 | 00:24:55 |
| M8 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 21 | 00:24:15 | 00:24:19 |
| M9 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 40 | 00:24:15 | 00:24:55 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 21 | M3 × 21 |
| e1-top 4096 | 2 | M4 × 1, M5 × 1 |
| e1-hot-sweep 1024 | 17 | M3 × 17 |
| e3-sweep 1024 | 55 | M3 × 55 |
| e3-sweep 4096 | 12210 | M6 × 5202, M7 × 6947, M8 × 21, M9 × 40 |
| outside the measurements | 2 | M1 × 1, M2 × 1 |

## First line of each

- M1 (node-1): `2026-11-03T00:01:56.129-08:00  WARN 48105 --- [transfer-service] [           main] me.ahoo.wow.runtime.node.JdkCheck        : Wow runs on JDK 21, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness then fails and restarts it. Run Wow on JDK 25, which d`
- M2 (node-1): `2026-11-03T00:01:58.172-08:00  WARN 48105 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M3 (node-1): `2026-11-03T00:06:35.434-08:00  WARN 48105 --- [transfer-service] [tor-http-nio-13] reactor.netty.channel.FluxReceive        : [9bf761e2-1, L:/127.0.0.1:18080 - R:/127.0.0.1:64683] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M4 (node-1): `2026-11-03T00:08:00.921-08:00 ERROR 48105 --- [transfer-service] [tor-http-nio-15] me.ahoo.wow.http.WowRoutes               : A request failed with an unexpected error.`; cause `java.net.SocketException: Connection reset`
- M5 (node-1): `2026-11-03T00:08:00.934-08:00 ERROR 48105 --- [transfer-service] [tor-http-nio-15] reactor.core.publisher.Operators         : Operator called default onErrorDropped`; cause `java.net.SocketException: Connection reset`
- M6 (node-1): `2026-11-03T00:24:15.008-08:00  WARN 48105 --- [transfer-service] [tcher-worker-10] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 36 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0079875 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M7 (node-1): `2026-11-03T00:24:15.008-08:00  WARN 48105 --- [transfer-service] [tcher-worker-13] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 19 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0089103 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M8 (node-1): `2026-11-03T00:24:15.364-08:00  WARN 48105 --- [transfer-service] [tcher-worker-12] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 46 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0004778 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M9 (node-1): `2026-11-03T00:24:15.498-08:00  WARN 48105 --- [transfer-service] [tcher-worker-13] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 57 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0095096 pushed back; it runs at most null handlings at once until handlings succeed again.`
