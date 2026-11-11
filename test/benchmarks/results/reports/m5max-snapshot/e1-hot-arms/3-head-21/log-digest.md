# Node log digest: 3-head-21

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `me.ahoo.wow.runtime.node.JdkCheck` | Wow runs on JDK <n>, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness… |  | 1 | 01:21:02 | 01:21:02 |
| M2 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 01:21:03 | 01:21:03 |
| M3 | WARN | `reactor.netty.channel.FluxReceive` | [<channel>, L:/<n>.<n>.<n>.<n>:<n> - R:/<n>.<n>.<n>.<n>:<n>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset |  | 127 | 01:25:50 | 01:25:50 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| e1-hot-sweep 1024 | 127 | M3 × 127 |
| outside the measurements | 2 | M1 × 1, M2 × 1 |

## First line of each

- M1 (node-1): `2026-11-11T01:21:02.290-08:00  WARN 2109 --- [transfer-service] [           main] me.ahoo.wow.runtime.node.JdkCheck        : Wow runs on JDK 21, which pins a virtual thread that blocks inside synchronized code, as database drivers, Kafka clients and Java agents do, to its carrier: enough of them stop every store call of a node, whose liveness then fails and restarts it. Run Wow on JDK 25, which do`
- M2 (node-1): `2026-11-11T01:21:03.276-08:00  WARN 2109 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
- M3 (node-1): `2026-11-11T01:25:50.387-08:00  WARN 2109 --- [transfer-service] [tor-http-nio-11] reactor.netty.channel.FluxReceive        : [109240ca-1, L:/127.0.0.1:18080 - R:/127.0.0.1:55077] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`
