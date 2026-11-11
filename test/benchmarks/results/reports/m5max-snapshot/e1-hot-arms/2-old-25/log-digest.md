# Node log digest: 2-old-25

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 01:15:14 | 01:15:14 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 23 | 01:19:59 | 01:19:59 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-hot-sweep 1024 | 23 | M2 × 23 |
| outside the measurements | 1 | M1 × 1 |

## First line of each

- M1 (node-1): `2026-11-11T01:15:14.534-08:00  WARN 96732 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-11T01:19:59.946-08:00  WARN 96732 --- [transfer-service] [tor-http-nio-12] reactor.netty.channel.FluxReceive        : [7581ba23-1, L:/127.0.0.1:18080 - R:/127.0.0.1:50311] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
