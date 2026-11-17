# Node log digest: 3-N-mongo

The warnings and errors of node-1.log, node-2.log, node-3.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | node-2 | node-3 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 1 | 1 | 03:40:36 | 03:40:40 |
| M2 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings su… |  | 8934 | 1721 | 4849 | 03:50:21 | 03:51:00 |
| M3 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most <n> handlings at once until handlings succeed… |  | 9649 | 2086 | 4680 | 03:50:21 | 03:51:00 |
| M4 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onPrepared throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings succee… |  | 5 | 4 | 2 | 03:50:21 | 03:50:57 |
| M5 | WARN | `m.a.w.r.subscription.PartitionThrottle` | Subscription TransferSaga.onAmountEntered throttles partition <n> of group transfer-service/transfer: the downstream of transfer.account/(<n>)/acc-<n> pushed back; it runs at most null handlings at once until handlings s… |  | 2 | 3 | 1 | 03:50:24 | 03:50:58 |
| M6 | WARN | `m.a.wow.http.internal.HttpSignalSender` | Dropped the signal of wait <id> to <n>.<n>.<n>.<n>:<n> (its wait was over before it was sent); <n> dropped so far. |  | 1 | 0 | 0 | 03:50:31 | 03:50:31 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e3-sweep 4096 | 31937 | M2 × 15504, M3 × 16415, M4 × 11, M5 × 6, M6 × 1 |
| outside the measurements | 3 | M1 × 3 |

## First line of each

- M1 (node-1): `2026-11-17T03:40:36.656-08:00  WARN 69037 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-17T03:50:21.152-08:00  WARN 69037 --- [transfer-service] [atcher-worker-1] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 63 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0061967 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M3 (node-1): `2026-11-17T03:50:21.153-08:00  WARN 69037 --- [transfer-service] [atcher-worker-1] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 19 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0016411 pushed back; it runs at most 512 handlings at once until handlings succeed again.`
- M4 (node-1): `2026-11-17T03:50:21.245-08:00  WARN 69037 --- [transfer-service] [atcher-worker-5] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onPrepared throttles partition 12 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0067326 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M5 (node-1): `2026-11-17T03:50:43.316-08:00  WARN 69037 --- [transfer-service] [tcher-worker-14] m.a.w.r.subscription.PartitionThrottle   : Subscription TransferSaga.onAmountEntered throttles partition 10 of group transfer-service/transfer: the downstream of transfer.account/(0)/acc-0059318 pushed back; it runs at most null handlings at once until handlings succeed again.`
- M6 (node-1): `2026-11-17T03:50:31.971-08:00  WARN 69037 --- [transfer-service] [tcher-worker-18] m.a.wow.http.internal.HttpSignalSender   : Dropped the signal of wait 01a1e958-040c-7d9f-a60a-2690f5a07e19 to 127.0.0.1:19092 (its wait was over before it was sent); 1 dropped so far.`
