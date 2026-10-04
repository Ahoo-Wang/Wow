# Wow Review Rubric

Use this rubric after resolving the actual diff and reading the current definitions and consumers.

Pin the downstream version and target source before applying query rules. `query-read-model.md` holds the query contract (fixed pipeline, Schema, `QueryAdmission`, policies, cursors, aggregation, raw Factory access) and which targets it applies to; review query changes against it and against the target's own source. Historical targets must be reviewed against their own contracts, not against every later V9 implementation.

## Correctness

- Command handlers enforce invariants and emit events rather than directly mutating sourced state.
- Sourcing is deterministic, ordered, and side-effect free.
- Events contain enough durable meaning to rebuild state and preserve compatibility.
- Saga branches, Projection/EventProcessor side effects, duplicate delivery, retry, and idempotency are verified at the correct boundary.
- Gateway/wait changes preserve identity, propagation, cancellation cleanup, timeout semantics, and ambiguous outcomes.
- Runtime changes preserve one owner, admission/drain ordering, fatal cause, readiness, deadlines, and repeated-signal safety.

### Query changes

Check each against `query-read-model.md`; these are the review-specific traps:

- Mandatory tenant/owner/lifecycle/business constraints live in `QueryPolicy`, AND-composed after every replaceable `QueryFilter.prepare`; captured identity/scope is retained per subscription; an empty or error policy stops later policies and the Backend. ABAC reads tags only for Snapshot.
- Schema stays immutable fact data; it neither admits requests nor selects policies. Preserve declaration merge and per-instance revalidation. OpenAPI `x-wow-query-fields` is not backend capability proof, and revalidating one instance updates no other replica, mapping, or stored data.
- Cursor sorts need `CURSOR_SORT` capability, single-valued ordered data and a stable unique tie-breaker (`SORT` alone is insufficient), and reject Mask and protected aliases. Apply historical EXACT/mode status checks only when the inspected target exposes them. Application code never decodes, logs, rewrites, or carries a token across Backends; every page goes through the managed Gateway, and tokens carry no authorization.
- Empty-string filters distinguish `""`, whitespace, null, missing fields, and collections; in-process capability does not imply the HTTP expensive-operator guard.
- EventStream aggregation uses the managed Gateway, the `EVENT_STREAM` schema and event-relative `body` paths; elements start absolute and then stay relative, and the final sort uses output aliases. Aggregation reuses scope and policy but does not mask, so protected groups, metrics, and expressions must be rejected before execution. Verify the HTTP/OpenAPI/Schema routes from the target.
- Request-facing queries use the managed aggregate `QueryGateway`. Raw `factory.create(namedAggregate).backend` access needs an `AdmittedQuery` from `QueryAdmission` plus explicit predicates, and bypasses scope, policies, defaults, Mask and observation; an identical Backend does not prove policy equivalence. JVM calls do not inherit HTTP scope.

## Compatibility and integration

- Public APIs, event revisions, serialization, schema, OpenAPI (including the runtime Schema GET capability descriptor and generic query request bodies), generated metadata, and downstream consumers remain compatible unless breaking change is authorized.
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
