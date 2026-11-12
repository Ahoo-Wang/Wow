---
title: Import from Another System
description: Bring aggregates from a system that is not Wow into a service with a creation command that declares when each was created; keep when it was imported, make a re-run safe, and see what the old times do downstream.
outline: deep
---

# Import from Another System

An order placed three years ago in another shop system should be, once imported, an order placed three years ago: its `firstEventTime` is when it was placed, not when the import ran, and no domain field beside it gives a second answer. An import does that with an ordinary creation command, sent through the gateway like any other, that declares when the aggregate was created. This page writes one, with an order as the example.

A 9.x service is not imported this way: its event history is converted by `migrate-data` ([Migrating from 9.x](../migrate/)).

## Write the import command

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/OccurredAtSpecTest.kt#command -->

```kotlin
@CreateAggregate
@InternalCommand
data class ImportOrder(
    @AggregateId val legacyId: String,
    @OccurredAt val placedAt: Long,
    val importedAt: Long,
)

data class OrderImported(val importedAt: Long)
```

| On the command | Why |
| --- | --- |
| `@CreateAggregate` | Only a command that creates its aggregate declares when it occurred. A second import of the same order is `wow.aggregate.exists`, before its time is read |
| `@InternalCommand` | Only the platform sends it ([Keep a command internal](./aggregates#keep-a-command-internal)). A creation that declares its time on a public route lets whoever may send it choose the time its aggregate was created |
| `@AggregateId` on the source's key | The order's id is its key in the source system, so an import names the same aggregate every time it runs ([Name the aggregate in the body](./aggregates#name-the-aggregate-in-the-body)) |
| `@OccurredAt` | The property that holds when the order was created in the source, epoch milliseconds, a `Long`, or a `Long?` whose null declares nothing |
| `importedAt` | When the order entered Wow: [Keep when it was imported](#keep-when-it-was-imported) |
| An event of its own, `OrderImported` | A saga or a reactor that acts on a new order, by `OrderSubmitted` say, does not take an imported one for a new one: it sends no confirmation mail for an order of three years ago |

The compiler checks the declaration: one `@OccurredAt` property per command, a `Long` or a `Long?` (not an `Instant`), public or internal, written by Wow's JSON, on a command that creates its aggregate. The Manifest names it as the command's `occurredAtProperty`. Adding one to a command is additive; removing it, or moving it to another property, is breaking ([The Manifest and Compatibility](./manifest#the-compatibility-check)).

## What the declared time changes

A commit has two times: when what it records occurred, its commit time, and when it was written. On almost every commit they are one. On an import's creation, the kernel takes the declared time as the commit time, and the clock's reading as the time it was written:

| Reads the time it occurred, the declared time | Reads the time it was written |
| --- | --- |
| The decide function's `scope.now` | An `after` schedule, and a cron rule's first slot: counted from the write ([Scheduled Commands](./scheduling)) |
| The commit time, so the snapshot's `firstEventTime`, and its `lastEventTime` until the next commit | A subscription that starts at an instant: an import written after it is read |
| Queries over snapshots and event streams, and the datasets | The subscription lag metric, `wow.subscription.lag` |

An `at` schedule names its instant and stays at it. The commit after the import is at the clock's time, like any other.

The kernel refuses a declared time later than its clock's reading, or before the epoch, with `wow.command.invalid` (400), and writes nothing. A null declares nothing: the commit is at the clock's time.

## Keep when it was imported

Once the commit time is the time the order was placed, nothing in the system records when it was imported. So the import event keeps it, `importedAt` here: it is a fact of the import, as the source's id or its status are.

- The import program gives it, in the command's body. One reading of its clock for a whole batch is fine.
- The decide function copies it into the event, and the evolve function reads it from the event.
- It is the import program's clock. The exact instant each commit was written is in that commit's `wow.commit.written-at` header, for an audit.

The decide function reads no clock of its own: `scope.now` is the declared time, and a clock read there would change on each retry and differ between nodes.

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.api.annotation.AggregateRoot

@AggregateRoot
class Order(private val state: OrderState) {
    fun onCommand(command: ImportOrder): OrderImported = OrderImported(command.importedAt)
}

class OrderState {
    var importedAt: Long? = null
        private set

    fun onSourcing(event: OrderImported) {
        importedAt = event.importedAt
    }
}
```

## Send the import

The import program reads the source and sends one command per aggregate through the command client ([Call Other Services](./calling-services)), as its own service caller. In this service it sends in process; from another service, it calls the internal port, which `wow.callers.<service>.commands` permits ([Keep a command internal](./aggregates#keep-a-command-internal)).

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest

val importer = commandClient.of(Caller.ofService("order-import"))
val importedAt = System.currentTimeMillis()
for (row in batch) {
    val command = ImportOrder(row.key, row.placedAt, importedAt)
    importer.wait(CommandRequest.of(command, requestId = "import:legacy-shop:${row.key}"))
}
```

- **The request id comes from the source's key.** A run that stopped halfway is run again from the start: a command whose request id already committed is answered as done, `duplicate: true`, and decided no second time. Its `importedAt` stays the first run's.
- **Nothing writes to a store.** Every import goes through the gateway and the kernel: its constraints, unique keys, access attributes, the stream's operator and the subscriptions all see it as they see any creation. A row written directly to the log or a snapshot store skips them all.
- **The owner is the creating command's.** An aggregate that belongs to its owner takes the importer's scope unless the importer acts in the source owner's scope ([Act in another scope](./ownership#act-in-another-scope)).

## Test it

A specification decides the import at the time it declares:

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/OccurredAtSpecTest.kt#spec -->

```kotlin
aggregateSpec(model, id = "legacy-1", start = START) {
    whenCommand(ImportOrder("legacy-1", PLACED, IMPORTED)) {
        expectEventEnvelope<OrderImported> {
            body.assert().isEqualTo(OrderImported(IMPORTED))
            // The commit is at the time the order was placed; the header holds when it was written.
            commitTime.assert().isEqualTo(PLACED)
            headers[EventStreamRecord.WRITTEN_AT].assert().isEqualTo("${START.toEpochMilli()}")
        }
        expectState { importedAt.assert().isEqualTo(IMPORTED) }
    }
}
```

## Old times downstream

- **Projections and datasets** that should show imported orders handle `OrderImported` as well as the event of a new order.
- **A schedule the import sets** with `after` is due that long after the import was written, not after the order was placed. One with `at` an instant already past is due at once.
- **A warehouse history with a TTL** (`wow.aggregates.<context>.<aggregate>.clickhouse.history.ttl`) counts from the commit time. An imported commit older than the TTL leaves the warehouse's history soon after it is written. The log keeps it: it never expires.

## Not in 10.0

- **Only a creation declares a time.** A later command of an imported aggregate commits at the clock's time. The compiler, and the model at build, refuse `@OccurredAt` on a command that may or must find its aggregate existing.
- **The creator is not declared.** The import's commit is recorded under the importer's caller, as any commit is under its sender, so the snapshot's `firstOperator` is the importer. Who created the order in the source, if the domain needs it, is a field of the import event.
