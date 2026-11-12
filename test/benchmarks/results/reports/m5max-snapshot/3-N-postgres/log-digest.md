# Node log digest: 3-N-postgres

The warnings and errors of node-1.log, node-2.log, node-3.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | node-2 | node-3 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 1 | 1 | 00:30:58 | 00:31:02 |
| M2 | WARN | `i.netty.channel.DefaultChannelPipeline` | An exceptionCaught() event was fired, and it reached at the tail of the pipeline. It usually means the last handler in the pipeline did not handle the exception. | io.netty.handler.codec.http2.Http2Exception$StacklessHttp2Exception: Maximum number of RST frames reached | 0 | 0 | 6 | 00:36:11 | 00:36:33 |
| M3 | WARN | `m.a.wow.http.internal.HttpSignalSender` | Dropped the signal of wait <id> to <n>.<n>.<n>.<n>:<n> (its wait was over before it was sent); <n> dropped so far. |  | 1 | 1 | 0 | 00:41:07 | 00:41:29 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 1 | 15 | 0 | 00:41:09 | 00:41:30 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 4096 | 6 | M2 × 6 |
| e3-sweep 4096 | 18 | M3 × 2, M4 × 16 |
| outside the measurements | 3 | M1 × 3 |

## First line of each

- M1 (node-1): `2026-11-12T00:30:58.894-08:00  WARN 37111 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-3): `2026-11-12T00:36:11.164-08:00  WARN 37209 --- [transfer-service] [ctor-http-nio-5] i.netty.channel.DefaultChannelPipeline   : An exceptionCaught() event was fired, and it reached at the tail of the pipeline. It usually means the last handler in the pipeline did not handle the exception.`; cause `io.netty.handler.codec.http2.Http2Exception$StacklessHttp2Exception: Maximum number of RST frames reached`
- M3 (node-1): `2026-11-12T00:41:29.406-08:00  WARN 37111 --- [transfer-service] [tcher-worker-13] m.a.wow.http.internal.HttpSignalSender   : Dropped the signal of wait 01a1ceeb-252c-7113-ba82-5aa691f19bb1 to 127.0.0.1:19092 (its wait was over before it was sent); 1 dropped so far.`
- M4 (node-1): `2026-11-12T00:41:29.414-08:00  WARN 37111 --- [transfer-service] [atcher-worker-8] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 13 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0044601 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
