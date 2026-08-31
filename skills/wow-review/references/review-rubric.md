# Wow Review Rubric

Use this rubric after resolving the actual diff and reading the current definitions and consumers.

## Correctness

- Command handlers enforce invariants and emit events rather than directly mutating sourced state.
- Sourcing is deterministic, ordered, and side-effect free.
- Events contain enough durable meaning to rebuild state and preserve compatibility.
- Saga branches, Projection/EventProcessor side effects, duplicate delivery, retry, and idempotency are verified at the correct boundary.
- Query filters preserve tenant/owner/deletion/authorization constraints, `FilterExpression` scope semantics, deterministic pagination, and backend null/numeric behavior. Aggregation `elements` keep the first path absolute, later paths relative, and sort by the effective output field.
- Runtime query schemas preserve declaration merge, validation mode, backend bindings, and cache/refresh locality. OpenAPI `x-wow-query-fields` is not backend capability proof; refreshing one instance does not update other replicas, mappings, or historical data.
- Cursor effective sort is Schema `EXACT`, `SINGLE`, unmasked, and stably unique, without logical, projection, or physical aliases.
- Cursor tokens are opaque: application code does not decode, log, rewrite, or cross Backend boundaries with them; every page uses the managed Gateway and tokens carry no authorization.
- Empty-string behavior distinguishes `""`, whitespace, null, missing fields, and collections; do not infer an HTTP expensive-operator guard from in-process capability.
- EventStream aggregation uses the managed `EventStreamQueryGateway`, `EVENT_STREAM` schema, event-relative `body` paths, and generated HTTP/OpenAPI/Schema routes.
- Request-facing and ordinary application queries use the managed aggregate `QueryGateway`. Direct `QueryBackendFactory` access is trusted infrastructure that bypasses the Gateway policy chain; the same Backend is not policy equivalence. WebFlux request-scope rewriting happens before the Gateway and is not inherited by in-process calls.
- Gateway/wait changes preserve identity, propagation, cancellation cleanup, timeout semantics, and ambiguous outcomes.
- Runtime changes preserve one owner, admission/drain ordering, fatal cause, readiness, deadlines, and repeated-signal safety.

## Compatibility and integration

- Public APIs, event revisions, serialization, schema, OpenAPI (including runtime Schema GET/refresh routes and generic query request bodies), generated metadata, and downstream consumers remain compatible unless breaking change is authorized.
- Configuration examples match current property classes and conditional auto-configuration.
- Dependencies, module boundaries, and Gradle feature variants select the intended implementation.
- Reactive paths do not gain blocking calls, manual subscriptions, accidental scheduler changes, or broken cancellation/backpressure.

## Tests and evidence

- Behavior changes have focused regression evidence, including negative and lifecycle branches.
- Direct unit tests do not overclaim registration, delivery, retry, persistence, transport behavior, or runtime support from a generated aggregation route alone.
- Generated artifacts are changed through their source/generator.
- Verification commands are actual and scoped; “should pass” is not evidence.

## Finding threshold

Report a finding only when the diff introduces a concrete defect, regression, unsafe boundary, or missing evidence required to support its claim. Include severity, location, impact, trigger, evidence, and a direction that fixes the underlying boundary rather than its symptom.
