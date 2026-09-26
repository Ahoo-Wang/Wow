# Wow Failure Pipeline Map

Locate the first incorrect transition rather than debugging every downstream symptom.

```text
request / command
  -> route and aggregate metadata
  -> command delivery and idempotency
  -> command handler
  -> emitted event stream
  -> event persistence and publication
  -> sourcing / Saga / Projection / EventProcessor
  -> repository or downstream side effect
  -> wait notification / query / client observation
```

## Symptom routing

Query rows describe targets with `QueryFilter.prepare`, `QueryPolicy.evaluate` and explicit Backend `(query, schema)` arguments. For older targets, locate the corresponding stages in that version's source instead of assuming these SPI types or removed-setting behavior exist.

| Symptom | First boundary to prove |
|---|---|
| Command not handled | route, aggregate metadata, generated registration, bus selection |
| State is wrong | emitted event body/order, sourcing selection, replay/snapshot path |
| Saga/processor not invoked | event type/scope, bounded-context resolution, Spring annotated-bean discovery or explicit registration, runtime parser, subscription, function selection |
| Handler invoked but retries incorrectly | original exception, retry filter, idempotency, compensation state |
| Projection is stale | event publication, processor invocation, repository result, duplicate/retry behavior |
| Wait hangs or times out | command identity, wait plan, propagation, stage completion, `notifyAndForget` failure logs, resource cleanup |
| Query is wrong | exact target, managed/raw entry, prepare replacement, captured scope/identity, configured QueryPolicy, Schema source/provider facts, Gateway public validation, native compiler and pagination/sort |
| Cursor first page works but continuation fails | exact original/next cursor, filter, effective sort, request rewrite, Query Schema resolved sort, unique tie-breaker, Backend token decode/value arity/codec, route/client transport |
| Aggregation route exists but fails | generated path/body, fixed Gateway preparation/scope/policy, public input protection, routed Backend `(query, schema)`, native compiler/mapping |
| EventStream policy has no effect | actual Gateway Bean or manual constructor, EventStream Registrar's QueryPolicy injection, policy applicability and emitted filter, trusted Reactor Context, final Backend predicate |
| EventStream aggregation fails | managed/raw path, same Gateway policy/admission stages, `EVENT_STREAM` Schema, relative `body` scope, routed Backend and actual HTTP/OpenAPI route |
| Query startup fails after an upgrade | exact version and first exception, removed validation-mode property or stale constructor/SPI, sources and declaration conflicts; do not infer a fix from a property name alone |
| Configuration is ignored | property prefix/binding, capability/variant, condition, active profile, bean selection |
| Runtime startup/shutdown fails | lifecycle owner, state transition, component slot, fatal cause, deadline |
| Test fails unexpectedly | fixture, owner/tenant, event order, fork/ref checkpoint, assertion boundary |

Continuation evidence contract:

- Documented continuation invariants: exact token, unchanged filter, unchanged request/effective sort.
- Backend token bindings: only the format, field count, value, and type that the exact target codec/source proves it encodes, decodes, or validates.
- Comparison inputs: record projection and page size changes, but do not attribute `Invalid cursor` to them unless the exact target source proves token binding.
- Business consistency constraint: preserve a deterministic unique tie-breaker in the effective sort.

Treat a cursor as opaque Backend input. Reproduce with the exact token and unchanged filter/sort before inspecting its codec; do not decode or rewrite it in application code, retry by restarting at page one, or switch to offset pagination to hide the first failing stage. A successful first page proves neither continuation nor cross-Backend compatibility.

At each boundary collect one positive or negative fact before moving downstream. If an earlier stage failed, do not patch a later stage to hide it.

For a target with `QueryFilter.prepare`, `QueryPolicy.evaluate` and Backend `(query, schema)`, Schema is immutable fact data; Gateway owns orchestration and public validation, and Backend owns native compilation/execution. The removed `wow.query.schema.validation-mode` property fails startup even when set to `strict`; inspect older target behavior separately because negotiated modes are valid on historical versions. Keep a source/declaration conflict distinct from a removed-setting error.

For a suspected policy bypass, compare the same caller identity and input on the managed JVM and HTTP paths. Trace scope extraction, the actual Gateway instance and Policy Beans, each policy's applicability/output, and the final Backend filter. Ordinary prepare can replace earlier request conditions; policy constraints belong after it. Empty/error policies must stop Backend invocation. A manual Gateway or raw Backend may omit governance supplied by Spring, but establish that from application source or a reproduction before calling it the root cause. Use a recording Backend to prove assembly and a real query against isolated data to prove returned-row isolation; one does not establish the other.

Use current source, tests, logs, generated metadata, broker/store state, and traces as available. Label every inaccessible boundary as unknown.
