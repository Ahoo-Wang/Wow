---
title: Unique Keys Across Aggregates
description: Keep a value unique across aggregates, such as a user name or a brand name, by reserving it from a decision and releasing it when it changes.
outline: deep
---

# Unique Keys Across Aggregates

An aggregate decides alone, so it cannot see whether another aggregate already uses a value. When a value must be unique across aggregates, such as a user name, an email address or a brand's name, the decision reserves it. A reservation is held by one aggregate at a time, commits with the aggregate's events, and is given up if they are not written. This page keeps the eShop's brand names unique.

## Reserve a key

`DecideScope.reserve(namespace, key)` reserves `key` in `namespace` for the deciding aggregate, and returns `false` when another aggregate holds it. A brand reserves its name as it takes it:

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#take -->

```kotlin
/** Takes [name] for this brand, or rejects the command when another brand holds it. */
private suspend fun take(name: String, scope: DecideScope) {
    if (!scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
        scope.reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
    }
}
```

- **The namespace** names what is unique: `eshop-catalog.brand-name`.
- **The key** is the value, normalized the way uniqueness is meant: the catalog trims and lowercases a name, so "Contoso" and " contoso " are one name.
- **The scope** of uniqueness is the aggregate's type and tenant: another tenant may hold the same key, and so may an aggregate of another type, so a brand and a category may share a name. `reserve(namespace, key, ReservationTenancy.SYSTEM)` makes a key unique across the type's tenants.
- `reserve` is a `suspend` function, so a decide function that reserves keys is written in Kotlin.

## What happens to a reservation

1. `reserve` records the key as **pending** for this aggregate, with an expiry.
2. When the decision's events are appended, the reservation is **confirmed**.
3. When they are surely not appended (the decision rejects, the version conflicts and the command is decided again, or deciding fails), this attempt **gives its reservation up**. A process that dies first leaves it to expire.

A key the aggregate already holds stays held whatever an attempt does, so deciding a command again never loses it.

## Release a key

A value that changes releases its old key. `DecideScope.release(namespace, key)` releases a key the aggregate holds once the events are appended: the release commits with them, and nothing is released when they are not. A rename reserves the new key and releases the old one in one decision:

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#rename -->

```kotlin
suspend fun onCommand(command: RenameBrand, scope: DecideScope): BrandRenamed? {
    if (command.name.trim() == state.name) return null
    take(command.name, scope)
    // A new spelling of the same name keeps its key.
    if (CatalogNames.key(command.name) != CatalogNames.key(state.name)) {
        scope.release(CatalogNames.BRAND_NAMES, CatalogNames.key(state.name))
    }
    return BrandRenamed(command.name.trim())
}
```

Deleting an aggregate releases none of its keys, so a restored aggregate still holds them. A delete that should give a key up releases it in the aggregate's own command before the delete.

## A key unique across aggregate types

A key is unique among the aggregates of one type. When a value must be unique across types, such as an email address that users and organizations share, model the uniqueness as an aggregate of its own: an aggregate whose id is the key (or one that holds the keys) is claimed by the others through its commands, and its own decisions say who holds each key.

## Where reservations are kept

An aggregate type's reservations are kept with its log, in the store `wow.aggregates.<context>.<aggregate>.log.store` names (or the default log store), in a table or collection of their own per type: `wow_reservation__<context>__<aggregate>`. A confirmation is so as durable as the commit it belongs to. They replace 9.x's `PrepareKey`; migrate-data imports what 9.x confirmed ([Migrate the Data](../migrate/data)).

## Test it

`aggregateSpec` runs reservations against an in-memory store. `givenReservation` reserves a key for another aggregate first, so a test can check that the command is rejected when the key is taken, and `holds(namespace, key)` says whether the aggregate under test holds a key: [Test Domain Code](./testing).
