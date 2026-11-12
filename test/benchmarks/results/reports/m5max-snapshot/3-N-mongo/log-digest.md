# Node log digest: 3-N-mongo

The warnings and errors of node-1.log, node-2.log, node-3.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | node-2 | node-3 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 1 | 1 | 00:44:30 | 00:44:34 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 8 | 0 | 2 | 00:49:30 | 00:49:30 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-sweep 1024 | 10 | M2 × 10 |
| outside the measurements | 3 | M1 × 3 |

## First line of each

- M1 (node-1): `2026-11-12T00:44:30.812-08:00  WARN 41426 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-12T00:49:30.466-08:00  WARN 41426 --- [transfer-service] [tor-http-nio-12] reactor.netty.channel.FluxReceive        : [a5c4cdf5-1, L:/127.0.0.1:18080 - R:/127.0.0.1:63504] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
