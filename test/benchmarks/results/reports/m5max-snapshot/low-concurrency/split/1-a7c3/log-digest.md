# Node log digest: 1-a7c3

The warnings and errors of node-1.log, by message (numbers and ids masked), then by the measurement step they fell in; a step's window runs from the end of the step before it, so it includes its warm-up. Times are the host's local time.

| Id | Level | Logger | Message | Cause | node-1 | First | Last |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 | WARN | `m.a.w.h.caller.AnonymousCallerResolver` | No CallerResolver is configured: every request is served as the anonymous caller (tenant (<n>), no scope). |  | 1 | 01:39:29 | 01:39:29 |

## By step

| Measurement and step | Lines | Messages |
| --- | ---: | --- |
| outside the measurements | 1 | M1 × 1 |

## First line of each

- M1 (node-1): `2026-11-12T01:39:29.097-08:00  WARN 57809 --- [transfer-service] [ctor-http-nio-3] m.a.w.h.caller.AnonymousCallerResolver   : No CallerResolver is configured: every request is served as the anonymous caller (tenant (0), no scope).`
