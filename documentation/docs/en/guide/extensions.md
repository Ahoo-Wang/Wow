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

With `wow-cosid` and [CosId](https://github.com/Ahoo-Wang/CosId) 5's starter on the class path, the Spring Boot host takes its ids from CosId's runtime instead:

- the global ids are the text ids of CosId's generator named `wow.cosid.global-generator`, `default` unless set. CosId has no generator of its own: configure it. A node whose CosId has no generator of that name does not start, and the error names the key;
- an aggregate type's ids: Wow builds no generator. CosId's configuration decides which exist, and Wow finds a type's by name, in this order:
  1. the CosId generator `wow.aggregates.[<context>.<aggregate>].id-generator` names, which must exist, or the node does not start, the error naming the key;
  2. else the generator named after the aggregate's qualified name, `<context>.<aggregate>`;
  3. else the global ids.

  Ids from the global generator are as unique and time-ordered as a type's own; only a per-type sequence is gone, which nothing stored depends on. Each type's generator is logged at INFO when it takes its first id.

A `cosid80` generator with a radix-62 codec gives ids of 15 characters that sort like the time they were issued, the shape of CosId's 4.x `CosIdGenerator`. An aggregate type gets a sequence space or a layout of its own by one more generator, named after it, with nothing in Wow (a dotted name goes in brackets):

```yaml
cosid:
  machine:
    leased:
      store: db # or static: { slot: 0 } on a single machine
  stores:
    db:
      type: jdbc
  generators:
    default:
      strategy: cosid80
      codec: [{ radix: { radix: 62 } }]
    '[eshop-ordering.order]':
      strategy: cosid80
      codec: [{ radix: { radix: 62 } }]
```

A time-based generator needs a machine slot, from a store (`cosid.machine.leased`) or fixed (`cosid.machine.static.slot`); without one, CosId does not start. For a local or development run, `cosid.machine.static.slot: 0` needs no store. Every instance of an application uses the same `cosid.namespace` (by default `spring.application.name`), `bits` and `tolerance`, and an instance of another configuration is refused. CosId 5 is a pre-release: see its [migration guide](https://github.com/Ahoo-Wang/CosId/blob/main/documentation/docs/en/guide/migration/from-4x.md) to move ids that 4.x issued.

An application that defines its own `GlobalIdGenerator`, or a `wowAggregateIdGenerator` bean, keeps it: the module gives way, and an aggregate type without a CosId generator of its own then takes the application's global ids. An id taken in a decide function is taken again when the decision is decided again after a version conflict, which is safe: the conflicting attempt appended nothing, and a retried request is answered from its first commit.

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
