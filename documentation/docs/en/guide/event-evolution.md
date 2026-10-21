---
title: Evolve Events
description: Change an event without rewriting the log, with revisions and upgrade functions; rename commands and events, retire events, and change the state's structure.
outline: deep
---

# Evolve Events

The log is never rewritten, so an event written today will be read for as long as the aggregate lives. When an event's shape must change, its class says which revision it is now, and a small function upgrades each older record when it is read. This page shows the changes that need nothing, the ones that need a revision, and how to rename or retire an event. [Deciding and Evolving](../concepts/programming-model#events-change-over-time) explains the model.

## Changes that need nothing

Adding an optional field with a default is not a new revision: an older record without the field is read with the default. Neither is a change that only adds to what a reader may ignore.

Everything else (removing, renaming or retyping a field, a field whose meaning changes, a required field) needs a new revision.

## Raise the revision

An event's class carries its current revision with `@Revision(n)`; an event without it is at revision 1. The view store's `ViewCreated` gained the view's `kind` in revision 2:

<!-- sample: platform/view-store/view-store-api/src/main/kotlin/me/ahoo/wow/viewstore/api/view/ViewEvents.kt#view-created -->

```kotlin
/** Revision 2 records the [kind]; a 9.x record (revision 1) has none, and is read with its config's `kind`. */
@Summary("A view was saved")
@Revision(2)
data class ViewCreated(
    val definitionId: String,
    val title: String,
    val kind: ViewKind,
    val audience: ViewAudience,
    val appId: String,
    val config: Map<String, Any?>,
)
```

## Upgrade the older records

For each older revision there is one upgrade function, annotated `@Upgrade(event = X::class, from = n)`, that turns the stored JSON of revision `n` into revision `n + 1`. A record of revision 1 of an event now at revision 3 goes through the upgrade from 1, then the one from 2:

<!-- sample: platform/view-store/view-store-domain/src/main/kotlin/me/ahoo/wow/viewstore/domain/view/ViewUpgrades.kt#upgrades -->

```kotlin
/**
 * The 9.x records of a view, read as revision 2 (decision D267): 9.x recorded no `kind`, which it defined as the
 * config's own `kind` (its `ViewKind`), so the upgrade takes it from there. A 9.x config without a kind, or with one
 * that is no [ViewKind], fails the read: the view is not guessed into a kind, and the read names the view.
 */
object ViewUpgrades {
    @Upgrade(event = ViewCreated::class, from = 1)
    @JvmStatic
    fun viewCreatedFrom1(node: ObjectNode): ObjectNode = withKind(node)

    @Upgrade(event = ViewSaved::class, from = 1)
    @JvmStatic
    fun viewSavedFrom1(node: ObjectNode): ObjectNode = withKind(node)

    private fun withKind(node: ObjectNode): ObjectNode {
        val named = node.get(CONFIG)?.get(ViewConfigs.KIND)?.takeIf { it.isString }?.stringValue()
        val kind = requireNotNull(ViewKind.of(named)) {
            "A 9.x view's config has no kind among ${ViewKind.entries.map { it.value }}: ${node.get(
                CONFIG
            )?.get(ViewConfigs.KIND)}."
        }
        return node.put(KIND, kind.value)
    }

    private const val CONFIG = "config"
    private const val KIND = "kind"
}
```

- An upgrade function is top-level, or a member of an `object` (with `@JvmStatic`).
- It takes and returns a Jackson 3 `JsonNode` or `ObjectNode` (`tools.jackson.databind`): it works on the record, not on the class, which no longer matches it.
- It is pure: no I/O, no services, and it does not suspend. It may fail, as the view's does for a record it cannot read; the read then fails and names the record.
- An event at revision `n` needs exactly one upgrade from each revision 1 to `n - 1`. A gap or a duplicate fails the compilation.

Records are upgraded wherever the log is read: when an aggregate is loaded, and when a subscription reads its events, so a handler only ever sees the current class. A published stream carries events at their current revision too.

## Rename a command or an event

A command or an event is stored and routed by its name, the class's simple name unless `@Name` gives another. To rename the class and keep reading what was written under the old name, list the old name with `@Alias("OldName")`. A value without a dot is a former name in the same aggregate; a value with dots is a full type name, `context.aggregate.Name`, for a class that moved from another aggregate.

## Retire an event

An event the aggregate no longer has is retired: delete its class and its sourcing function, and list its stored name in `@AggregateRoot(retiredEvents = [...])`. Its records are skipped when the aggregate is loaded, though their streams still count as versions, and subscriptions pass over them. A record under a name that is neither an event's nor retired still fails to read.

## Change the state's structure

Snapshots hold the state as it was serialized. When the stored snapshots no longer read as the state, or no longer hold what the state now derives (a sourcing function whose meaning changed, a field renamed or retyped, a new field the history must compute), raise `@AggregateRoot(stateRevision = n)`. The service then rebuilds the stored snapshots from the log, in place and in the background. An optional field added to the state needs no new revision.

## Peers see it too

The api module's events and the aggregate's state are part of the context's contract: peers read them from the published stream. The Manifest's compatibility check compares each build with the committed baseline and fails a change that breaks a reader within the same major version, such as a removed or retyped field, whatever the revisions say. [The Manifest](../concepts/contract#compatibility) explains the check.

## Test it

`givenRecords` in `aggregateSpec` commits records as the log holds them, so a test can load an aggregate from a record of an older revision, through its upgrades, or from a retired event's record, and check the state it ends in: [Test Domain Code](./testing).
