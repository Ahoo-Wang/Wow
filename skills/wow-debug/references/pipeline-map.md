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

| Symptom | First boundary to prove |
|---|---|
| Command not handled | route, aggregate metadata, generated registration, bus selection |
| State is wrong | emitted event body/order, sourcing selection, replay/snapshot path |
| Saga/processor not invoked | event type/scope, bounded-context resolution, Spring annotated-bean discovery or explicit registration, runtime parser, subscription, function selection |
| Handler invoked but retries incorrectly | original exception, retry filter, idempotency, compensation state |
| Projection is stale | event publication, processor invocation, repository result, duplicate/retry behavior |
| Wait hangs or times out | command identity, wait plan, propagation, stage completion, `notifyAndForget` failure logs, resource cleanup |
| Query is wrong | enforced filters, filter normalization/conversion and scoped paths, schema source/merge/provider/cache, validation mode, backend adapter, pagination/sort |
| Cursor first page works but continuation fails | exact original/next cursor, filter, effective sort, request rewrite, Query Schema resolved sort, unique tie-breaker, Backend token decode/value arity/codec, route/client transport |
| Aggregation route exists but fails | generated path/body, aggregate `QueryGateway` and its `QueryType.AGGREGATION` filter chain, routed `SnapshotQueryBackend.aggregate`, backend compiler/mapping |
| EventStream aggregation fails | managed/raw path, aggregate `QueryGateway` filter chain, routed `EventStreamQueryBackend.aggregate`, `EVENT_STREAM` schema, `body` scope, backend mapping, and generated HTTP/OpenAPI route |
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

Use current source, tests, logs, generated metadata, broker/store state, and traces as available. Label every inaccessible boundary as unknown.
