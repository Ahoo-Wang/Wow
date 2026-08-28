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
| Query is wrong | enforced filters, filter normalization/conversion and scoped paths, pagination/sort, backend semantics |
| Aggregation route exists but fails | generated path/body, `QueryGateway` and its `QueryType.AGGREGATION` filter chain, selected `SnapshotQueryService.aggregate`, backend compiler/mapping |
| Configuration is ignored | property prefix/binding, capability/variant, condition, active profile, bean selection |
| Runtime startup/shutdown fails | lifecycle owner, state transition, component slot, fatal cause, deadline |
| Test fails unexpectedly | fixture, owner/tenant, event order, fork/ref checkpoint, assertion boundary |

At each boundary collect one positive or negative fact before moving downstream. If an earlier stage failed, do not patch a later stage to hide it.

Use current source, tests, logs, generated metadata, broker/store state, and traces as available. Label every inaccessible boundary as unknown.
