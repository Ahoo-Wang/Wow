# TypeId Design

## Background

Wow domain event serialization currently writes `bodyType` as the JVM fully qualified class name of the event body. Deserialization resolves that value through `Class.forName()` and then converts the JSON body to the resolved class. This works inside a stable JVM codebase, but it couples long-lived event records to package names, class names, and module layout.

The existing model already has more stable domain metadata:

- `contextName`
- `aggregateName`
- `name`
- `revision`

The goal of `TypeId` is to make that stable identity explicit, while preserving compatibility with existing records that only contain `bodyType`.

## Decision

Introduce `TypeId` as the stable identity of a message contract.

`TypeId` is not a Java class alias. It identifies the domain message contract that the payload conforms to. Runtime classes remain an implementation detail used after the contract identity is resolved.

For domain events, the canonical identity should be derived from:

```text
contextName + aggregateName + name
```

`revision` remains separate and identifies the schema version of that contract.

## Concepts

| Concept | Meaning | Stability |
| --- | --- | --- |
| `typeId` | Stable message contract identity, for example `event://sales/order/order_created` | Stable |
| `revision` | Schema version of the contract, for example `1.0.0` | Evolves intentionally |
| `bodyType` | Runtime JVM class name, for example `me.ahoo.example.OrderCreated` | Legacy/runtime hint |
| `name` | Existing Wow message name, usually derived from `@Name` or class name conversion | Stable if users treat it as contract name |

## Scope

The first implementation should focus on domain events and event streams because event records are long-lived and replay-sensitive.

Commands can adopt the same concept later, but command messages are usually shorter-lived. Adding command support in the first pass is optional and should not block the event-sourcing fix.

## Serialization Shape

New event records should include `typeId` alongside the existing fields:

```json
{
  "id": "event-id",
  "name": "order_created",
  "revision": "1.0.0",
  "typeId": "event://sales/order/order_created",
  "bodyType": "me.ahoo.example.OrderCreated",
  "body": {}
}
```

`bodyType` should remain during the compatibility window. It can still help old consumers and can serve as a fallback when the new registry cannot resolve a `typeId`.

## Resolution Order

Domain event deserialization should resolve the body class in this order:

1. If `typeId` exists, resolve `typeId + revision` from a registry.
2. If no exact revision match exists, resolve by `typeId` and allow an event upgrader to migrate the body before object conversion.
3. If `typeId` is missing or unresolved, fall back to legacy `bodyType`.
4. If both paths fail, preserve the record as `JsonDomainEvent`.

This keeps old event records readable and gives new records a stable contract-first path.

## Registry

Introduce a registry that maps stable identities to runtime types.

For events:

```text
EventTypeId(contextName, aggregateName, name) -> EventTypeDescriptor
```

`EventTypeDescriptor` should contain at least the current runtime class and the current revision. It can later grow supported-revision metadata if Wow needs stricter version negotiation.

The registry can be built from existing event metadata scanning. Later, KSP-generated `WowMetadata` can include explicit type-id entries to make runtime lookup less reflective.

The registry must detect duplicate mappings for the same `typeId` and fail fast, because two runtime classes claiming the same contract identity would make replay ambiguous.

## Compatibility

Existing records without `typeId` must continue to deserialize through `bodyType`.

Existing APIs and schemas that expose `bodyType` should not remove it in the first pass. Schema and OpenAPI generation can add `typeId` as a new required field only for new typed message schemas after the compatibility strategy is explicit. For a safer first step, generated schemas can expose `typeId` as optional while serializers write it for new records.

BI scripts should keep extracting `bodyType` and may add `type_id` in a later migration.

## Event Upgrading

Event upgraders currently run before object conversion and can mutate `bodyType`, `name`, `revision`, and `body`.

With `TypeId`, upgraders should be able to mutate `typeId` as well. This supports:

- renaming an event contract
- splitting an old event contract into a dropped or replacement contract
- migrating from legacy class-name identity to stable contract identity

Upgrade lookup should continue to use the existing stable tuple:

```text
contextName + aggregateName + name
```

That avoids requiring old records to have `typeId` before an upgrader can run.

## Error Handling

Unknown `typeId` should not crash query or transport use cases. It should produce `JsonDomainEvent`, preserving:

- original `typeId`, if present
- original `bodyType`, if present
- `name`
- `revision`
- JSON `body`

Replay-sensitive paths should continue to behave according to current semantics: if the body remains `JsonNode`, normal sourcing functions keyed by runtime event class will not run. This is acceptable only when no registry or upgrader can resolve the event.

## Testing

Focused tests should cover:

- new event serialization writes `typeId` and keeps `bodyType`
- event stream serialization writes per-event `typeId`
- deserialization prefers `typeId` over mismatched `bodyType`
- legacy records without `typeId` still deserialize through `bodyType`
- unknown `typeId` and unknown `bodyType` preserve `JsonDomainEvent`
- event upgraders can change `typeId`
- duplicate registry entries fail fast
- schema generation either preserves compatibility or explicitly snapshots the new field

## Migration Path

1. Add `typeId` support without removing `bodyType`.
2. Register event classes by stable event identity.
3. Make deserialization prefer `typeId`.
4. Update schema/OpenAPI snapshots to include `typeId` according to compatibility policy.
5. Add BI extraction for `type_id` without removing `body_type`.
6. Consider deprecating `bodyType` only after external consumers have migrated.

## Non-Goals

- Do not remove `bodyType` in the first implementation.
- Do not rewrite existing persisted events.
- Do not make TypeId a direct alias for JVM FQCN.
- Do not require commands to migrate in the first pass.
