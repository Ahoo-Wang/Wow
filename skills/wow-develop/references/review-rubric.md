# Review rubric

Judge behavior before style. Each finding: file and line, what goes wrong, when it triggers, and the smallest correct direction. Verify each item against the target's source; the rubric says what to look for, not what is true.

## Model

- A decide function that mutates the state, reads a clock or a random source directly, or does blocking I/O without `@Blocking`.
- An evolve function with services, I/O, a clock, or a missing `onSourcing` for an event the aggregate produces.
- A command function returning `Decision` where its return type could name its events (one event kind: return it, or `E?`), or without `@OnCommand(produces)`.
- A business rule failed by an exception other than `reject(code, …)` / `RejectedException`, `require` or `check`; an error code without `@ErrorCode`, with the `wow.` prefix, or reused.
- A check of the command's own shape in decide (a quantity not positive, a blank name, an empty list) with an error code, instead of a Jakarta constraint on the command class; a constraint on a nested value without `@field:Valid` on its property; a service with constraints but no Bean Validation provider (they are then never checked).
- Identity in a command body (aggregate id, tenant, owner); the caller's identity read from a raw header instead of the `Caller`.
- A unique key that does not include the tenant without meaning to be global; uniqueness checked by a query instead of `DecideScope.reserve`.
- An event changed after release without `@Revision` and an `@Upgrade` chain, an event deleted instead of retired, or the Manifest baseline updated to hide a break.
- A state property without an initial value; personal data in events.

## Processes

- A reactor that sends commands; a projection that derives commands; a saga that calls the outside world.
- A saga whose expected rejections have no `@OnRejected` (the aggregate parks); a command id that is not deterministic.
- A projection without a version guard; a reactor without the invocation key on its outside call.
- A handler that filters `event.events` by type instead of a typed handler.
- A new reactor or saga over existing history whose start position would act on the past; a cross-context subscription that must not miss history but starts at the end.
- Retries added to hide a failure the code should classify (`@Retry(unrecoverable)`).

## Across contexts and hosting

- A cross-context saga on a node without Kafka, a target without `command-intake`, a command topic with a partition count other than `wow.log.partitions`, or a peer's api module missing from the class path.
- A synchronous call where a command and receipt would do, or one without `@OnEvent(calls)`.
- `wow.log.partitions` changed after the log holds entries; a context name with a dot or upper case.
- The internal port reachable from outside; no cluster token outside a development profile.
- A V10 module depending on a 9.x module, or a 9.x annotation in a V10 module.

## Tests

- A behavior change without a spec, or a spec that asserts nothing a regression would break (`expectNoError` alone).
- A JUnit function that returns a value (skipped silently).
- A saga test without `expectDeterministic()` where determinism matters; a rejection path untested.
- A command constraint tested in a spec (`aggregateSpec` runs no admission) instead of at the server, by the 400 `wow.command.invalid` and its violation's path and constraint.
