---
title: Owners, Spaces and Tags
description: Decide who an aggregate belongs to besides its tenant, check the caller in a command, transfer owners and spaces, and tag aggregates for attribute-based access.
outline: deep
---

# Owners, Spaces and Tags

Every aggregate belongs to its tenant. Most also belong to someone: an order to its buyer, a saved view to the person who saved it. Wow records that as the aggregate's **scope**, its owner and its space, and keeps every caller to the aggregates in its scope. This page chooses an aggregate's ownership, checks the caller where the scope is not enough, and changes owners, spaces and tags. [Identity, Tenancy and Access](../concepts/identity) explains the model.

## Choose who an aggregate belongs to

`@AggregateRoot(ownership = ...)` declares it:

| `Ownership` | The aggregate belongs to | Such as |
| --- | --- | --- |
| `OWNER`, the default | Its tenant, and the owner and space of the command that creates it | An order, a basket, a saved view |
| `TENANT` | Its tenant, and a space if it has one; no owner is recorded | A product, a brand, a stock every caller of the tenant reads |

A product is read by every shopper and changed by staff, so it belongs to its tenant:

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/Product.kt#root -->

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

## Check the caller in a command

Sometimes the scope is not enough. A basket's id is its buyer's user id, so its first command, which creates it, checks that the caller is that buyer. The command function takes `Command<X>` and compares `command.scope.ownerId` with the id:

<!-- sample: example/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/Basket.kt#add-item -->

```kotlin
fun onCommand(command: Command<AddBasketItem>): BasketItemAdded {
    val owner = command.scope.ownerId
    if (owner != null && owner != command.ref.id) {
        reject(Rejections.NOT_OWNER, "The basket ${command.ref.id} is not $owner's.")
    }
    val adding = command.body
    return BasketItemAdded(adding.productId, adding.unitPrice, adding.quantity)
}
```

The item's quantity and price are the command's shape: its constraints are checked before the function runs ([Write an Aggregate](./aggregates#check-its-shape)), so the function checks only the caller. After the basket exists, the kernel keeps it to the owner that created it.

## Commands a saga sends

A command a saga derives, a compensation and a scheduled command carry the scope and the operator of the commit they follow. An aggregate a saga creates therefore belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. [Write a Saga](./sagas#build-the-commands) covers it.

## Transfer an owner or a space

Two lifecycle commands change an aggregate's scope; every aggregate handles them unless `@AggregateRoot(lifecycle = [...])` leaves them out:

| Command | Route | Body |
| --- | --- | --- |
| `TransferOwner` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `{"ownerId": "…"}` |
| `TransferSpace` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `{"spaceId": "…"}` |

A `TENANT` aggregate handles no `TransferOwner`. Every commit records the scope after it, so a projection or a query reads the current owner without replaying the history.

## Tag aggregates

Tags are key-value pairs on an aggregate. Attribute-based access matches them against the caller's attributes when it reads: a query returns only the aggregates whose tags the caller's attributes allow. There are two ways to set them:

- **Applied:** the lifecycle command `ApplyTags`, `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags` with `{"tags": {"region": ["eu"]}}`, replaces the applied tags.
- **Derived:** a member of the state annotated `@Tags`, a property or a function without parameters returning `Map<String, Collection<String>>`, derives tags from the state. It is evaluated after every commit, and each key it returns replaces the applied value of that key. Return every key it owns on every call, with an empty list for none.

Attribute-based access is off by default. `wow.query.abac.enabled: true` turns it on, and the caller's attributes named with the prefix `abac.` (`abac.region`) are matched against the tags of the same key ([Configuration Keys](../reference/configuration#wow-query-abac)).

## A fixed tenant

An aggregate type whose aggregates all live in one tenant, whatever the caller's, takes it from the host: `wow.aggregates.[<context>.<aggregate>].tenant`.

## Act in another scope

A caller allowed to act in another tenant or scope, such as a support tool, names it in the `Wow-Scope` header, or in the command client's `CommandRequest` (`targetTenant`, `targetScope`). The commit records that the operator acted in another scope.
