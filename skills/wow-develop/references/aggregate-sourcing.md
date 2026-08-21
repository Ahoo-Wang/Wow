# Aggregate and Sourcing Decisions

Use this reference for aggregate modeling, command handling, event design, state reconstruction, lifecycle, tenant/owner routing, and aggregate behavior tests.

## Stable boundaries

- Let the command model decide whether an intent is valid and emit facts describing the accepted change.
- Treat domain events as the authoritative facts for aggregate state. When loading the latest state, Wow may start from a persisted snapshot and apply only subsequent ordered event streams. Keep every sourcing transition deterministic, synchronous in meaning, and free of external services or writes; snapshots must remain regenerable from event history.
- Put business invariants inside one aggregate when they require one consistency boundary. Coordinate multiple aggregates with messages rather than shared mutable state.
- Make emitted events sufficient to rebuild state and to preserve downstream meaning. Treat serialized event changes as compatibility changes.
- Keep creation, deletion, recovery, expected-version, tenant, owner, and routing behavior explicit and covered by tests when relevant.
- Prefer the command/state separation already used by neighboring aggregates; do not force a new structural pattern without a concrete benefit.

## Discover the current contract

Find the target aggregate and a nearby working example:

```bash
rg -n "@AggregateRoot|@OnCommand|@OnSourcing|@AggregateRoute|@BoundedContext" . -g '*.kt' -g '*.java'
rg -n "class .*Spec.*AggregateSpec|aggregateVerifier" . -g '*.kt' -g '*.java'
```

For an exact annotation or handler contract, inspect its definition and consumers in a separate checkout of the pinned Wow source, or in resolved dependency sources. In a Wow source checkout, start with:

- `wow-api/src/main/kotlin/me/ahoo/wow/api/annotation/`
- `wow-core/src/main/kotlin/me/ahoo/wow/command/`
- `wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/`
- `wow-compiler/src/main/kotlin/`
- `test/wow-test/src/main/kotlin/me/ahoo/wow/test/`

Do not copy a parameter list or convention from this reference. Resolve it from the target version.

## Model the change

Before editing, write down:

1. aggregate identity and consistency boundary;
2. command intent and authorization/routing inputs;
3. invariant and rejection behavior;
4. emitted fact and compatibility implications;
5. sourced state transition;
6. lifecycle, tenant, owner, and concurrency cases;
7. the smallest behavior test that proves the contract.

If one command can produce different event shapes, verify how the current compiler/runtime obtains return metadata. If a handler requires services, verify whether the handler path actually provides them; never make sourcing depend on IOC or side effects.

## Test boundary

Use the target version's `AggregateSpec` or `AggregateVerifier` APIs and neighboring tests as the syntax authority. Cover:

- accepted command and emitted event body;
- sourced state after the event;
- rejected command and error type/content;
- every material state branch;
- creation, deletion/recovery, owner/tenant, routing, or version behavior when changed;
- replay or serialization compatibility when the event contract changes.

Prefer a focused test in the owning module. Broaden to integration coverage only when metadata generation, routing, storage, or transport is part of the behavior under change.
