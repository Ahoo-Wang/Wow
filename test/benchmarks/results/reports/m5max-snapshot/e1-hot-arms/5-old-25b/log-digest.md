# Node log digest: 5-old-25b

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 01:32:42 | 01:32:42 |
| M2 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 161 | 01:37:34 | 01:37:34 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-hot-sweep 4096 | 161 | M2 × 161 |
| outside the measurements | 1 | M1 × 1 |

## First line of each

- M1 (node-1): `2026-11-11T01:32:42.663-08:00  WARN 10340 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M2 (node-1): `2026-11-11T01:37:34.072-08:00  WARN 10340 --- [transfer-service] [ctor-http-nio-7] reactor.netty.channel.FluxReceive        : [db00daaa-1, L:/127.0.0.1:18080 - R:/127.0.0.1:49736] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
