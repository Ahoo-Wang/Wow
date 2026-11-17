# Node log digest: 3-N-postgres

The warnings and errors of node-1.log, node-2.log, node-3.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | node-2 | node-3 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 1 | 1 | 03:27:16 | 03:27:20 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 16 | 0 | 0 | 03:32:10 | 03:32:10 |
| M3 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 9608 | 1802 | 8243 | 03:36:55 | 03:37:35 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 9487 | 1554 | 8325 | 03:36:55 | 03:37:35 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 1 | 3 | 1 | 03:36:56 | 03:37:23 |
| M6 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 1 | 0 | 2 | 03:36:58 | 03:37:02 |
| M7 | WARN | `m.a.wow.http.internal.HttpSignalSender` | Dropped the signal of wait <id> to <n>.<n>.<n>.<n>:<n> (its wait was over before it was sent); <n> dropped so far. |  | 1 | 0 | 0 | 03:37:06 | 03:37:06 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 16 | M2 × 16 |
| e3-sweep 1024 | 115 | M3 × 60, M4 × 55 |
| e3-sweep 4096 | 38913 | M3 × 19593, M4 × 19311, M5 × 5, M6 × 3, M7 × 1 |
| outside the measurements | 3 | M1 × 3 |

## First line of each

- M1 (node-1): `2026-11-17T03:27:16.782-08:00  WARN 64101 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-17T03:32:10.338-08:00  WARN 64101 --- [transfer-service] [ctor-http-nio-8] reactor.netty.channel.FluxReceive        : [cad0b223-1, L:/127.0.0.1:18080 - R:/127.0.0.1:49513] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
- M3 (node-1): `2026-11-17T03:36:56.190-08:00  WARN 64101 --- [transfer-service] [atcher-worker-4] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 59 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0000801 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M4 (node-1): `2026-11-17T03:36:56.190-08:00  WARN 64101 --- [transfer-service] [atcher-worker-3] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 54 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0025551 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-17T03:37:23.674-08:00  WARN 64101 --- [transfer-service] [tcher-worker-13] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 12 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0088695 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-17T03:37:02.766-08:00  WARN 64101 --- [transfer-service] [tcher-worker-14] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 37 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0088546 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M7 (node-1): `2026-11-17T03:37:06.578-08:00  WARN 64101 --- [transfer-service] [tcher-worker-11] m.a.wow.http.internal.HttpSignalSender   : Dropped the signal of wait 01a1e94b-b9fb-7312-a652-22b40764bb1b to 127.0.0.1:19091 (its wait was over before it was sent); 1 dropped so far.`
