---
title: Reactor, Ids and Models
description: Keep writing handlers in Reactor with wow-reactor, take ids from CosId with wow-cosid, and reuse the capability interfaces and the tree aggregate of wow-models.
---

# Reactor, Ids and Models

Three optional modules extend what a domain can use. None is needed: a service without them writes `suspend` functions, takes UUIDv7 ids and defines its own models. A fourth, `wow-cocache`, shares aggregate states between services: [Share State through CoCache](./extensions/cocache).

## Reactor handlers: `wow-reactor`

Wow's API is coroutines throughout, but a decide function, a processor's handler or a service it calls may keep returning Reactor types. A handler that returns a `Mono` yields its value, or nothing; one that returns a `Flux`, or any other `Publisher`, yields every element it emits, as a list of events or commands:

<!-- sample: fragment -->

```kotlin
@OnCommand(produces = [OrderShipped::class])
fun onCommand(command: ShipOrder, carriers: CarrierService): Mono<OrderShipped> =
    carriers.book(command.carrier).map { booking -> OrderShipped(booking.trackingNumber) }
```

The compiler generates the code that awaits the publisher, through `wow-reactor`; without the module on the class path, a handler that returns a Reactor type does not compile. A handler that returns a publisher is not marked `@Blocking`: the publisher decides where its work runs. A Reactor caller of the `suspend` clients, `CommandClient` or `QueryApi`, wraps each call in `mono { … }` of `kotlinx-coroutines-reactor`.

## Ids: `wow-cosid`

A node generates two kinds of ids: the global ones, command ids, request ids, wait ids and `scope.ids.next()` in domain code; and an aggregate type's ids, given to a creating command that names none and returned by `scope.ids.next(type)`. By default both are version 7 UUIDs: time-ordered, and unique without coordination.

With `wow-cosid` on the class path and CosId running in the application, the Spring Boot host takes its ids from [CosId](https://github.com/Ahoo-Wang/CosId) instead:

- the global ids from CosId's generator named `wow.cosid.global-generator`, `cosid` by default, or its shared generator when it has none by that name;
- an aggregate type's ids from CosId's generator named after the aggregate, else from a generator of the type's own, with the machine id CosId allocated;
- `wow.aggregates.[<context>.<aggregate>].id-generator` names any CosId generator for one aggregate type.

An application that defines its own `GlobalIdGenerator`, or a `wowAggregateIdGenerator` bean, keeps it: the module gives way. An id taken in a decide function is taken again when the decision is decided again after a version conflict, which is safe: the conflicting attempt appended nothing, and a retried request is answered from its first commit.

## Models: `wow-models`

`wow-models` holds model helpers a domain can share: capability interfaces (`NameCapable`, `TitleCapable`, `RemarkCapable`, `ReasonCapable`, `QuantityCapable`, `TypeCapable`, `StatusCapable` and others), `EnumText`, and tree codes with `Trees.toTree`. Its reusable `Tree` aggregate keeps a tenant's tree of codes, with the commands `Create`, `Update`, `Delete` and `Move`. A domain subclasses it with its own commands and events, and the compiler binds the handlers it inherits; the subclass adds its rules through the verify hooks:

<!-- sample: extensions/models/src/test/kotlin/me/ahoo/wow/models/tree/aggregate/Category.kt#tree -->

```kotlin
/**
 * A tenant's category tree: every handler is the library's [Tree]'s, bound with the concrete commands. The verify
 * hooks add a rule of the domain, and one of the tree's codes is declared to the Manifest with its status.
 */
@AggregateRoot(ownership = Ownership.TENANT)
class Category(state: CategoryState) :
    Tree<CategoryState, CreateCategory, UpdateCategory, DeleteCategory, MoveCategory>(state) {
    override fun maxLevel(): Int = MAX_LEVEL

    override suspend fun verifyCreate(command: CreateCategory, scope: DecideScope): Rejection? = reserved(command.name)

    override suspend fun verifyUpdate(command: UpdateCategory, scope: DecideScope): Rejection? = reserved(command.name)

    private fun reserved(name: String): Rejection? =
        if (name.equals(RESERVED_NAME, ignoreCase = true)) {
            Rejection.of(NAME_RESERVED, "The category name [$name] is reserved.")
        } else {
            null
        }

    companion object {
        const val MAX_LEVEL = 3
        const val RESERVED_NAME = "uncategorized"

        @ErrorCode(description = "The category name is reserved.", status = 409)
        const val NAME_RESERVED = "category.name-reserved"

        /** [TreeRejections.PARENT_NOT_FOUND], declared to the Manifest with its status. */
        @ErrorCode(description = "The parent category does not exist.", status = 404)
        const val PARENT_NOT_FOUND = "tree.parent-not-found"
    }
}
```

Annotate the concrete create command `@AllowCreate`: the compiler reads the command's own class. The packages are `me.ahoo.wow.models.common` and `me.ahoo.wow.models.tree`, as in 9.x, so a migrated domain's imports stay.
