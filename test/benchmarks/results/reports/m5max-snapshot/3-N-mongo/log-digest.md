# Node log digest: 3-N-mongo

The warnings and errors of node-1.log, node-2.log, node-3.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | node-2 | node-3 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `me.ahoo.wow.runtime.node.JdkCheck` | Wow runs on JDK <n>, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness… |  | 1 | 1 | 1 | 00:39:07 | 00:39:13 |
| M2 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 1 | 1 | 00:39:09 | 00:39:13 |
| M3 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 0 | 0 | 11 | 00:49:03 | 00:49:03 |
| M4 | WARN | `m.a.wow.http.internal.HttpSignalSender` | Dropped the signal of wait <id> to <n>.<n>.<n>.<n>:<n> (its wait was over before it was sent); <n> dropped so far. |  | 1 | 1 | 1 | 00:49:14 | 00:49:21 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 9 | 2 | 36 | 00:49:14 | 00:49:48 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e3-sweep 1024 | 11 | M3 × 11 |
| e3-sweep 4096 | 50 | M4 × 3, M5 × 47 |
| outside the measurements | 6 | M1 × 3, M2 × 3 |

## First line of each

- M1 (node-1): `2026-11-03T00:39:07.384-08:00  WARN 59518 --- [transfer-service] [           main] me.ahoo.wow.runtime.node.JdkCheck        : Wow runs on JDK 21, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness then fails and restarts it. Run Wow on JDK 25, which d`
- M2 (node-1): `2026-11-03T00:39:09.660-08:00  WARN 59518 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M3 (node-3): `2026-11-03T00:49:03.288-08:00  WARN 59577 --- [transfer-service] [tor-http-nio-17] reactor.netty.channel.FluxReceive        : [6d274d7a-1, L:/127.0.0.1:18082 - R:/127.0.0.1:50577] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M4 (node-1): `2026-11-03T00:49:21.552-08:00  WARN 59518 --- [transfer-service] [tcher-worker-12] m.a.wow.http.internal.HttpSignalSender   : Dropped the signal of wait 01a1a099-1d7d-753f-b251-d487ceb7b784 to 127.0.0.1:19092 (its wait was over before it was sent); 1 dropped so far.`
- M5 (node-1): `2026-11-03T00:49:21.558-08:00  WARN 59518 --- [transfer-service] [atcher-worker-5] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 54 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0019453 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
