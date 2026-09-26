# Wow Review Rubric

Use this rubric after resolving the actual diff and reading the current definitions and consumers.

Pin the downstream version and target source before applying query rules. The fixed-pipeline rules below apply to targets exposing `QueryFilter.prepare`, `QueryPolicy.evaluate`, and Backend `(query, schema)`. Historical targets must be reviewed against their own contracts, not against every later V9 implementation.

## Correctness

- Command handlers enforce invariants and emit events rather than directly mutating sourced state.
- Sourcing is deterministic, ordered, and side-effect free.
- Events contain enough durable meaning to rebuild state and preserve compatibility.
- Saga branches, Projection/EventProcessor side effects, duplicate delivery, retry, and idempotency are verified at the correct boundary.
- Mandatory tenant/owner/lifecycle/business constraints use `QueryPolicy`, AND-composed after all replaceable `QueryFilter.prepare` steps. Both models retain captured identity/scope across subscriptions; empty/error policies stop later policies and Backend invocation. A policy owns its applicability check; ABAC reads tags only for Snapshot.
- Schema holds immutable structure, native binding/capability and protection facts; it neither admits requests nor selects policies. Gateway calls public validation; Backend owns native compilation and execution. Preserve declaration merge and refresh locality. OpenAPI `x-wow-query-fields` is not backend capability proof; refresh does not update other replicas, mappings or stored data.
- Cursor effective sort has independent `CURSOR_SORT` capability, single-valued ordered data and a stable unique tie-breaker; ordinary `SORT` alone is insufficient. Reject Mask and protected logical/projection/physical aliases. Use historical EXACT/mode status checks only when the inspected target exposes them.
- Cursor tokens are opaque: application code does not decode, log, rewrite, or cross Backend boundaries with them; every page uses the managed Gateway and tokens carry no authorization.
- Empty-string behavior distinguishes `""`, whitespace, null, missing fields, and collections; do not infer an HTTP expensive-operator guard from in-process capability.
- EventStream aggregation uses the managed Gateway, `EVENT_STREAM` schema and event-relative `body` paths. Elements start absolute and then remain relative; final sort uses output aliases. Aggregation reuses scope/policy but does not mask results, so protected groups/metrics/expressions are rejected before execution. Verify available HTTP/OpenAPI/Schema routes from the target.
- Request-facing queries use the managed aggregate `QueryGateway`. Raw `factory.create(namedAggregate).backend` access requires explicit Schema, admission and predicates; it bypasses scope, policies, defaults, Mask and observation. An identical Backend does not prove policy equivalence. WebFlux extracts scope into Reactor Context and applies its own guard; Gateway appends scope after prepare. JVM calls do not automatically inherit HTTP scope.
- Gateway/wait changes preserve identity, propagation, cancellation cleanup, timeout semantics, and ambiguous outcomes.
- Runtime changes preserve one owner, admission/drain ordering, fatal cause, readiness, deadlines, and repeated-signal safety.

## Compatibility and integration

- Public APIs, event revisions, serialization, schema, OpenAPI (including runtime Schema GET/refresh routes and generic query request bodies), generated metadata, and downstream consumers remain compatible unless breaking change is authorized.
- Judge source, binary and wire compatibility separately against the requested scope. When implementation SPI changes are explicitly allowed, do not demand bridges for concrete Gateway constructors or intermediate overrides. Preserve the agreed QueryGateway API and existing Condition compatibility through 10.0.0; this exception does not authorize unrelated application breaks.
- Configuration examples match current property classes and conditional auto-configuration.
- Dependencies, module boundaries, and Gradle feature variants select the intended implementation.
- Reactive paths do not gain blocking calls, manual subscriptions, accidental scheduler changes, or broken cancellation/backpressure.

## Tests and evidence

- Behavior changes have focused regression evidence, including negative and lifecycle branches.
- A policy Bean existing in the container or a direct Gateway test does not prove transport wiring. For each claimed HTTP path, exercise real registration/Handler selection, inspect the final Backend filter and verify rejection with zero Backend calls. Keep manual-route WebTestClient evidence separate from complete startup/route discovery, native storage behavior and performance.
- Direct unit tests do not overclaim registration, delivery, retry, persistence, transport behavior, or runtime support from a generated aggregation route alone.
- Generated artifacts are changed through their source/generator.
- Verification commands are actual and scoped; “should pass” is not evidence.

## Finding threshold

Report a finding only when the diff introduces a concrete defect, regression, unsafe boundary, or missing evidence required to support its claim. Include severity, location, impact, trigger, evidence, and a direction that fixes the underlying boundary rather than its symptom.
