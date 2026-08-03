# Saga, Projection, and Event Processor Decisions

Use this reference for cross-aggregate orchestration, read-model projection, external side effects, event delivery, retry, compensation, and duplicate handling.

## Choose the owner

| Responsibility | Owner |
|---|---|
| Enforce one aggregate's invariant and emit facts | Aggregate |
| Coordinate commands across aggregate boundaries | Saga |
| Maintain a query/read model | Projection |
| Notify or integrate with an external system | EventProcessor |

Do not hide an aggregate inside a Saga, perform external side effects in sourcing, or make a Projection responsible for command-side invariants.

## Discover the current path

```bash
rg -n "@StatelessSaga|@ProjectionProcessor|@EventProcessor|@OnEvent|@OnStateEvent|@Retry|@Blocking" . -g '*.kt' -g '*.java'
rg -n "SagaSpec<|sagaVerifier|Projection.*Test|EventProcessor.*Test" . -g '*.kt' -g '*.java'
```

For Spring-managed Saga, Projection, and EventProcessor components, trace the component annotation, `AutoRegistrar` bean discovery, the type-specific runtime metadata parser, and the function registrar. Their processor functions are built at runtime rather than loaded from generated processor metadata; also verify the generated bounded-context scopes used by `requiredNamedBoundedContext()`. For explicit registration, trace the registrar call and resulting `MessageFunction` set. Resolve exact return shapes, parameter injection, delivery filters, retry defaults, compensation, and blocking behavior from the target version.

## Design decisions

- Make the triggering event, aggregate scope, function selection, and command target explicit.
- Test both command-producing and no-command branches for conditional orchestration.
- Preserve the originating tenant/owner/context deliberately; do not assume propagation.
- Decide whether duplicate delivery is safe before enabling or changing retries.
- Separate handler invocation tests from runtime retry, idempotency, delivery, and compensation tests.
- Treat blocking work as an explicit scheduler boundary verified against current runtime behavior; do not block a Reactor non-blocking path accidentally.

## Verification boundary

| Claim | Minimum evidence |
|---|---|
| Saga chooses the correct command | Focused Saga spec/verifier including negative branches |
| Projection maps an event correctly | Focused unit test around repository interaction and mapped data |
| Duplicate delivery is safe | Test at the repository/idempotency boundary that owns duplicate suppression |
| Retry or compensation works | Runtime/integration test through the retry/compensation path |
| Blocking scheduling is correct | Test or trace that crosses the actual invocation wrapper and scheduler boundary |
| Handler is discoverable | Spring path: annotated bean, bounded-context resolution, runtime parser result, and registrar contents; explicit path: resulting `MessageFunction` set—not only direct method invocation |

Do not claim delivery, retry, idempotency, or registration from a direct handler unit test.
