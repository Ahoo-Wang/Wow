---
title: Owners, Spaces and Tags
description: Decide who an aggregate belongs to besides its tenant, give each owner one aggregate whose id is the owner's, check the caller in a command, transfer owners and spaces, and tag aggregates for attribute-based access.
outline: deep
---

# Owners, Spaces and Tags

Every aggregate belongs to its tenant. Most also belong to someone: an order to its buyer, a saved view to the person who saved it. Wow records that as the aggregate's **scope**, its owner and its space, and keeps every caller to the aggregates in its scope. This page chooses an aggregate's ownership, gives each owner one aggregate of a type, checks the caller where the scope is not enough, and changes owners, spaces and tags. [Identity, Tenancy and Access](../concepts/identity) explains the model.

## Choose who an aggregate belongs to

`@AggregateRoot(ownership = ...)` declares it:

| `Ownership` | The aggregate belongs to | Such as |
| --- | --- | --- |
| `OWNER`, the default | Its tenant, and the owner and space of the command that creates it | An order, a basket, a saved view |
| `TENANT` | Its tenant, and a space if it has one; no owner is recorded | A product, a brand, a stock every caller of the tenant reads |

A product is read by every shopper and changed by staff, so it belongs to its tenant:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Product.kt#root -->

```kotlin
/**
 * A product of the catalog: a draft until it is published on the storefront, which takes a brand and a category; it
 * can be delisted and published again. Its name, description, brand, category and price change at any time.
 *
 * Every shopper of the tenant reads the products, and an employee changes them: a product belongs to its tenant
 * (`Ownership.TENANT`), so neither its reads nor its writes are restricted by an owner. The catalog
 * server's action-level authorization lets only an employee send its commands.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A product of the catalog")
class Product(private val state: ProductState) {
```

The compiler writes the ownership into the Manifest, where query admission reads it.

## What the kernel checks

The first command's caller gives an `OWNER` aggregate its scope. After that, the kernel refuses a command whose caller's owner or space differs from the aggregate's, with `wow.aggregate.forbidden`, and query admission adds the caller's owner and space to every query. A part the caller has no value for is not checked:

- An **owner-level** caller, whose scope has an owner, reaches only its own aggregates.
- A **tenant-level** caller, without an owner, such as a member of staff, reaches every aggregate of its tenant. What it may do is decided by action-level authorization, such as CoSec's.

The writes of a `TENANT` aggregate are not checked against an owner at all: action-level authorization decides who may change a product.

## One aggregate per owner

Some aggregates exist once per owner: a buyer has one basket, a user one set of preferences. Such an aggregate takes its owner's id as its own, which `@AggregateRoot(idSource = AggregateIdSource.OWNER)` declares:

<!-- sample: fragment -->

```kotlin
@AggregateRoot(idSource = AggregateIdSource.OWNER)
class Basket(private val state: BasketState) {
    fun onCommand(command: AddBasketItem): BasketItemAdded =
        BasketItemAdded(command.productId, command.unitPrice, command.quantity)
}
```

Each owner then has exactly one basket in its tenant, and its id is the buyer's user id. The declaration requires `ownership = OWNER`, the default: an aggregate that belongs to its tenant has no owner to take its id from. Its owner never changes, so the default lifecycle leaves `TransferOwner` out, and the compiler refuses one listed in `lifecycle`, or a command that produces `OwnerTransferred`. The default, `AggregateIdSource.ASSIGNED`, takes the id the request names, or generates one for a creating command.

### Its routes

Every command of such an aggregate, its lifecycle commands included, has a route that names no id beside the route by id. The route without an id means the caller's own aggregate:

| Route | Serves |
| --- | --- |
| `POST /<context>/<aggregate>/<command>` | A command to the caller's own aggregate: `POST /eshop-basket/basket/add-basket-item` |
| `POST /<context>/<aggregate>/wow.lifecycle.<command>`, `DELETE /<context>/<aggregate>` | The lifecycle commands to the caller's own aggregate |
| `GET /<context>/<aggregate>/state`, `…/state/trace` | The caller's own aggregate's state and history |
| The routes by id: `POST /<context>/<aggregate>/{id}/<command>`, `GET …/{id}/state` | The aggregate of that id, for a caller with no owner |

No route generates an id: a creating command creates the aggregate of its owner.

### Who names the id

Admission takes the id from the caller:

- An **owner-level** caller's aggregate is its own: the id is its owner, on the routes without an id or by its own id, and a caller acting in another scope through `Wow-Scope` reaches that scope's owner's. Naming another owner's id is refused with `403 wow.aggregate.forbidden`.
- A caller **with no owner**, such as a tenant-level member of staff, a service or a saga, has no aggregate of its own: it names the id, on the routes by id. On a route without an id it is answered `400 wow.command.malformed` ("This caller has no owner; address the aggregate by its id."), and `400 wow.query.malformed` on the state read. An unauthenticated caller is answered `401 wow.auth.unauthenticated`.
- A command a saga derives, or a scheduled command, that names no id takes the owner of the scope it carries: a saga that follows a buyer's order reaches that buyer's basket.

The kernel records the aggregate's id as its owner, whoever creates it, and refuses a command whose owner is another, a creating one too. The command functions therefore check no caller: the basket above needs no ownership check of its own.

## Check the caller in a command

Where the scope is still not enough, a command function takes `Command<X>` and reads `command.scope`, the caller's owner and space, and rejects the command with its own code ([Read the admitted command](./aggregates#read-the-admitted-command)).

## Commands a saga sends

A command a saga derives, a compensation and a scheduled command carry the scope and the operator of the commit they follow. An aggregate a saga creates therefore belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. An aggregate whose id is its owner's takes that id for its owner instead ([One aggregate per owner](#one-aggregate-per-owner)). [Write a Saga](./sagas#build-the-commands) covers it.

## Transfer an owner or a space

Two lifecycle commands change an aggregate's scope; every aggregate handles them unless `@AggregateRoot(lifecycle = [...])` leaves them out:

| Command | Route | Body |
| --- | --- | --- |
| `TransferOwner` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `{"ownerId": "…"}` |
| `TransferSpace` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `{"spaceId": "…"}` |

A `TENANT` aggregate handles no `TransferOwner`, and neither does one whose id is its owner's. Every commit records the scope after it, so a projection or a query reads the current owner without replaying the history.

## Tag aggregates

Tags are key-value pairs on an aggregate. Attribute-based access matches them against the caller's attributes when it reads: a query returns only the aggregates whose tags the caller's attributes allow. There are two ways to set them:

- **Applied:** the lifecycle command `ApplyTags`, `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags` with `{"tags": {"region": ["eu"]}}`, replaces the applied tags.
- **Derived:** a member of the state annotated `@Tags`, a property or a function without parameters returning `Map<String, Collection<String>>`, derives tags from the state. It is evaluated after every commit, and each key it returns replaces the applied value of that key. Return every key it owns on every call, with an empty list for none.

Attribute-based access is off by default. `wow.query.abac.enabled: true` turns it on, and the caller's attributes named with the prefix `abac.` (`abac.region`) are matched against the tags of the same key ([Configuration Keys](../reference/configuration#wow-query-abac)).

## A fixed tenant

An aggregate type whose aggregates all live in one tenant, whatever the caller's, takes it from the host: `wow.aggregates.[<context>.<aggregate>].tenant`.

## Act in another scope

A caller allowed to act in another tenant or scope, such as a support tool, names it in the `Wow-Scope` header, or in the command client's `CommandRequest` (`targetTenant`, `targetScope`). The commit records that the operator acted in another scope.
