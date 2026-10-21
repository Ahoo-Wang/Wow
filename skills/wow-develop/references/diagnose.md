# Diagnose: from a symptom to the first wrong stage

The job: say where and why the pipeline broke, with evidence, before anyone changes code. A command travels one path; find the first stage that did not do what it should, compare with a path in the same checkout that works, and test one hypothesis at a time.

## The path of a command

1. **Edge:** HTTP route from the Manifest, the caller (`CallerResolver`), admission (body, `If-Match`, `Wow-Wait`, authorization by the `CommandAuthorizer`).
2. **Routing:** the node that owns the aggregate's partition (a forward over the internal port when it is another node).
3. **Decide:** the aggregate is loaded (snapshot plus the log's tail, events upgraded, retired ones skipped), the command function decides.
4. **Append:** the events and reservations commit at the next version, or the version conflicts.
5. **Evolve and snapshot:** the state is evolved; the snapshot subscription writes the read model.
6. **Subscriptions:** each processor's handler runs; a saga's derived commands go back to step 1, locally or to another context's command topic.
7. **Wait:** the request's `Wow-Wait` plan (`committed`, `snapshot`, `handled(...)`) answers once its last stage is reached, or times out.

The problem's `code` in the response (`application/problem+json`) or in the command's result names the stage almost always. Read it before reading code.

## Symptoms

| Symptom | First suspects | Evidence to collect |
| --- | --- | --- |
| 504 `wow.wait.timeout` | The command may have committed: the wait ran out at a later stage. The problem carries the last stage reached. A slow or parked subscription on a `handled(...)` plan; a snapshot subscription behind | The last stage in the problem; the aggregate's state read (`GET …/{id}/state`); subscription lag and parked entries (`/wow/ops/parked`, health `wow` group) |
| 500 `wow.subscription.parked` | A subscription on the wait chain parked the aggregate | The parked entry: subscription, version, reason; the handler's log at that time |
| 409 `wow.aggregate.version-mismatch` (412 with `If-Match`) | The caller's expected version is stale | The request's `If-Match` and the current version |
| 409 `wow.command.contended` | Writers keep winning the version | Retrying with the same `Wow-Request-Id` is safe; look for a hot aggregate |
| 409 `wow.command.request-id-reused` | A request id reused for another command | The client's id generation |
| 422 (or the code's status) with a domain code | A rejection: decide refused by the rule | The rule in the aggregate and the state it read; this is behavior, not a failure |
| 400 `wow.command.unhandled` | The aggregate does not handle this command | The command class's aggregate in the Manifest; a missing `@OnCommand` or wrong body type |
| 404 `wow.aggregate.not-found` / 409 `wow.aggregate.exists` | Creation rules: `@CreateAggregate`, `@AllowCreate`, neither | The command's annotations; the id the route carried |
| 429 `wow.command.busy` | The node's executor or waits are full | `wow.executor.max-in-flight`, `wow.wait.max-registered`; load |
| 429 `wow.query.busy` | The store's query bulkhead is full | `wow.query.max-concurrent`, the adaptive bound, the append latency |
| 503 `wow.command.unknown` | A forward or append whose outcome is unknown | Retry with the same request id; the cluster's health |
| 503 `wow.storage.unavailable` | A store unreachable | Health `wow` group, the store |
| 503 `wow.node.standby` | The node runs with `wow.standby=true` (a migration's dual-run) | `GET /wow/node/standby` on the internal port |
| A derived command is rejected and the aggregate parks | No `@OnRejected` for an expected rejection | The saga's handlers; the parked entry's reason |
| A handler never runs | Subscription start (`end` for a reactor or saga of the own log), wrong `aggregates`, a typed handler skipped for a `PublishedEvent`, no Kafka for another context | The subscription's start, checkpoint and the Manifest's `subscriptions` |
| Startup fails: unknown `wow.*` key | A typo or a key of another version | The configuration metadata of the pinned version |
| Startup fails: `Node … has no command transport …` | A saga sends to another context without Kafka | `wow-kafka` and its keys, or `wow.topics.in-memory` locally |
| A state read shows an old state | Read before the snapshot caught up | `?minVersion=<the command result's version>`, or a `snapshot` wait |

## Read-only evidence

- The command's result and problem, the request id and the aggregate id.
- `GET …/{id}/state`, `?version=`, `…/state/trace` (the commits that built it).
- The ops routes (`wow.ops.enabled`): parked entries, their reasons and retry state. Resuming or skipping one is a production write.
- Actuator health groups `wow`, `readiness`, `liveness`.

## Rules

- One falsifiable hypothesis at a time ("stage X fails because Y"), and the smallest check that tells it apart: a spec with the same events and command reproduces decide and evolve without a server (`testing.md`).
- Do not add retries, relax assertions, change start positions or annotations before the stage is proven.
- A fix to a contract (an event, a code, the state's shape) passes the Manifest compatibility check or is a reviewed break.
