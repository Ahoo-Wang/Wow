# Wow Core Unit Test Rewrite Design

Date: 2026-06-05

## Context

`:wow-core` has the largest local test surface in the repository. Its
`src/test` source set currently contains many small focused tests, several large
tests that cover multiple responsibilities, and shared mock files used across
command, event, messaging, modeling, serialization, and infrastructure tests.

Recent repository history already split the test runtime into local, contract,
and integration layers. The current design does not revisit that runtime split.
It focuses only on rewriting `:wow-core` local unit tests so each test file has
one clear responsibility and each assertion is derived from current production
code.

Production code is the only source of truth. Existing tests may be used as
discovery hints for historical coverage, helpers, and risky edge cases, but they
must not define expected behavior when they conflict with production code.

## Scope

In scope:

- Rewrite `wow-core/src/test` for `:wow-core`.
- Delete obsolete `wow-core/src/test` files as their owning capability domain is
  rewritten.
- Recreate test files from current `wow-core/src/main/kotlin` behavior.
- Keep tests aligned with the current production package structure.
- Keep test fixtures small, explicit, and scoped to test data construction.
- Verify the rewrite with `./gradlew :wow-core:test`, and use
  `./gradlew :wow-core:check` when shared fixtures or core reactive paths are
  touched.

Out of scope:

- Do not rewrite `wow-core/src/contractTest`.
- Do not rewrite `test/wow-test` or `test/wow-tck`.
- Do not change the Gradle local, contract, or integration test layer model.
- Do not change production behavior as part of this test rewrite.
- Do not add dependencies or move responsibilities across modules.

## Design Principles

Tests must be behavior-focused and production-code-driven.

For every capability domain, the rewrite starts by reading the matching
production package under `wow-core/src/main/kotlin`. Public types, extension
functions, error paths, reactive completion behavior, null handling, boundary
values, and observable side effects define the test cases.

Old tests are not preserved for historical reasons. A legacy test with no
assertion, only incidental execution, or expectations that are not supported by
production code should be replaced with a clear behavior test or removed.

Each test file should answer one question: what production concept or tightly
related behavior group does this file verify? If the answer contains several
independent concepts, split the file.

## Test File Boundaries

The rewritten `wow-core/src/test` tree should continue to mirror production
packages, but file boundaries should be based on observable responsibility, not
mechanically one file per class.

Examples:

- `command/wait` should separate header parsing, wait strategy lifecycle,
  signal matching, command-stage comparison, notifier behavior, and waiting
  chain propagation.
- `modeling` should separate aggregate identity, metadata derivation, command
  aggregate behavior, state aggregate behavior, and delete behavior.
- `messaging` should separate header mutation, message propagation, function
  metadata parsing, dispatcher lifecycle, retry filters, and compensation
  matching.
- `serialization` should separate object mapper configuration, command/event
  serialization, state JSON records, polymorphic behavior, and error handling.
- `infra` should separate accessors, prepare-key metadata, reflection scanning,
  idempotency, lifecycle helpers, and sink behavior.

Shared fixture files are allowed, but they should express test data construction
only. Fixtures must not hide assertions or encode behavior expectations. If a
fixture becomes a catch-all for many domains, split it by package or capability.

## Rewrite Workflow

Each capability domain uses the same replacement workflow:

1. Inspect the matching production package and list observable responsibilities.
2. Identify old tests and fixtures in the matching `wow-core/src/test` package.
3. Delete the old tests for that domain.
4. Recreate focused test files from production behavior.
5. Run the narrowest relevant Gradle test selection.
6. Run `./gradlew :wow-core:test` after the domain is stable.

Old and new tests for the same domain should not coexist for long. The preferred
unit of replacement is a whole capability domain or subdomain, so duplicated or
contradictory behavior assertions do not accumulate.

## Rewrite Order

The rewrite should proceed from low-level utilities to core orchestration:

1. Foundation: `annotation`, `naming`, `exception`, `ioc`, and `infra`.
2. Messaging and handler flow: `messaging`, `filter`, and `reactor`.
3. Command behavior: `command`, `command/factory`, and `command/wait`.
4. Event behavior: `event` and `eventsourcing`.
5. Modeling behavior: `modeling`.
6. Orchestration and projection: `saga` and `projection`.
7. Serialization and remaining shared services: `serialization`,
   `configuration`, `metrics`, `scheduler`, `id`, and `sharding`.

This order keeps early phases close to pure value/object/helper behavior before
rewriting command wait, event sourcing, and aggregate modeling tests that depend
on those lower-level concepts.

## Assertion And Reactive Style

Use the repository's existing test stack:

- Prefer `me.ahoo.test.asserts.assert` FluentAssert extensions for value and
  collection assertions.
- Use Reactor `StepVerifier` for `Mono` and `Flux` behavior.
- Use MockK only when the production collaborator boundary is the behavior under
  test.
- Avoid fixed sleeps. Reactive lifecycle tests should use deterministic signals,
  virtual time where appropriate, or explicit completion/error verification.
- Avoid tests that only execute code without checking observable behavior.

Test names should describe behavior rather than implementation steps. File names
should be readable as responsibility labels, for example
`WaitingForStageHeaderTest`, `SimpleStateAggregateLifecycleTest`, or
`DefaultHeaderMutationTest`.

## Error Handling

The rewrite should explicitly cover error behavior exposed by production code:

- Validation and precondition failures.
- Missing metadata or unsupported functions.
- Empty, null, malformed, or default header values.
- Reactive error propagation and completion paths.
- Serialization failures and type mismatch behavior.
- Registrar replacement, duplicate registration, and absence cases.

If a production behavior appears suspicious while writing tests, the test rewrite
should not silently change production code. The issue should be reported as a
separate production-code question unless the user explicitly approves a fix.

## Verification

Run narrow verification while each domain is being rewritten, for example:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.command.wait.*"
./gradlew :wow-core:test --tests "me.ahoo.wow.modeling.*"
```

Run the full local test task after each stable domain replacement:

```bash
./gradlew :wow-core:test
```

Run the full module check when the rewrite touches shared fixtures, command wait,
modeling, event sourcing, messaging dispatch, or Reactor lifecycle behavior:

```bash
./gradlew :wow-core:check
```

Success criteria:

- `./gradlew :wow-core:test` passes.
- `./gradlew :wow-core:check` passes for stages that affect shared or contract
  adjacent behavior.
- New test files have clear single-responsibility names.
- Removed legacy files are replaced by production-code-derived coverage.
- No production code, Gradle test topology, or external test layers are changed.

## Risks

The main risk is losing historical edge-case coverage while removing old tests.
The mitigation is to use old tests only as discovery input after production
responsibilities are mapped, then decide whether each historical case is still
observable in production code.

The second risk is rewriting too much before verification. The mitigation is
domain-level replacement with narrow Gradle test runs, followed by
`:wow-core:test`.

The third risk is accidentally testing implementation internals. The mitigation
is to test public or package-visible observable behavior, reactive signals,
headers, emitted messages, return values, and thrown errors instead of private
steps.

## Deliverable

The implementation deliverable is a rewritten `wow-core/src/test` source set
whose files are organized by production capability and clear test
responsibility. The rewrite should leave production code unchanged unless a
separate approved production fix is created.
