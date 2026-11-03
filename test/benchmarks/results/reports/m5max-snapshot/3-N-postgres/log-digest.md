# Node log digest: 3-N-postgres

The warnings and errors of node-1.log, node-2.log, node-3.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | node-2 | node-3 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `me.ahoo.wow.runtime.node.JdkCheck` | Wow runs on JDK <n>, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness… |  | 1 | 1 | 1 | 00:25:25 | 00:25:30 |
| M2 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 1 | 1 | 00:25:26 | 00:25:30 |
| M3 | WARN | `i.netty.channel.DefaultChannelPipeline` | An exceptionCaught() event was fired, and it reached at the tail of the pipeline. It usually means the last handler in the pipeline did not handle the exception. | io.netty.handler.codec.http2.Http2Exception$StacklessHttp2Exception: Maximum number of RST frames reached | 0 | 0 | 4 | 00:30:43 | 00:30:56 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 67 | 56 | 5 | 00:35:40 | 00:36:11 |
| M5 | WARN | `m.a.wow.http.internal.HttpSignalSender` | Dropped the signal of wait <id> to <n>.<n>.<n>.<n>:<n> (its wait was over before it was sent); <n> dropped so far. |  | 2 | 2 | 2 | 00:35:40 | 00:35:48 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 0 | 1 | 0 | 00:35:48 | 00:35:48 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 4096 | 4 | M3 × 4 |
| e3-sweep 4096 | 135 | M4 × 128, M5 × 6, M6 × 1 |
| outside the measurements | 6 | M1 × 3, M2 × 3 |

## First line of each

- M1 (node-1): `2026-11-03T00:25:25.179-08:00  WARN 55307 --- [transfer-service] [           main] me.ahoo.wow.runtime.node.JdkCheck        : Wow runs on JDK 21, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness then fails and restarts it. Run Wow on JDK 25, which d`
- M2 (node-1): `2026-11-03T00:25:26.674-08:00  WARN 55307 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M3 (node-3): `2026-11-03T00:30:43.612-08:00  WARN 55353 --- [transfer-service] [tor-http-nio-10] i.netty.channel.DefaultChannelPipeline   : An exceptionCaught() event was fired, and it reached at the tail of the pipeline. It usually means the last handler in the pipeline did not handle the exception.`; cause `io.netty.handler.codec.http2.Http2Exception$StacklessHttp2Exception: Maximum number of RST frames reached`
- M4 (node-1): `2026-11-03T00:35:41.124-08:00  WARN 55307 --- [transfer-service] [tcher-worker-19] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 12 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0038483 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-03T00:35:40.438-08:00  WARN 55307 --- [transfer-service] [atcher-worker-1] m.a.wow.http.internal.HttpSignalSender   : Dropped the signal of wait 01a1a08c-95fe-78d9-8ee0-4c6fefd1a775 to 127.0.0.1:19091 (its wait was over before it was sent); 1 dropped so far.`
- M6 (node-2): `2026-11-03T00:35:48.725-08:00  WARN 55331 --- [transfer-service] [tcher-worker-19] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 55 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0017269 pushed back; it runs at most null handlings at once until handlings succeed again.`
