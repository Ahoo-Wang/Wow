---
title: 跨聚合的唯一键
description: 在决定中预留一个值、在它改变时释放，从而让它在聚合之间保持唯一，例如用户名或品牌名。
outline: deep
---

# 跨聚合的唯一键

聚合独自做出决定，所以它看不到另一个聚合是否已经用了某个值。当一个值必须在聚合之间唯一时，例如用户名、邮箱地址或品牌名，就由决定来预留它。一个预留同一时刻只被一个聚合持有，与聚合的事件一起提交，事件没有写入时就放弃。本页让 eShop 的品牌名保持唯一。

## 预留一个键 {#reserve-a-key}

`DecideScope.reserve(namespace, key)` 在 `namespace` 中为正在做决定的聚合预留 `key`，另一个聚合持有它时返回 `false`。品牌在取名时预留它的名字：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#take -->

```kotlin
/** Takes [name] for this brand, or rejects the command when another brand holds it. */
private suspend fun take(name: String, scope: DecideScope) {
    if (!scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
        reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
    }
}
```

- **命名空间**指明什么是唯一的：`eshop-catalog.brand-name`。
- **键**就是这个值，按唯一性的含义规范化：catalog 会去掉名字两端的空白并转成小写，所以 "Contoso" 和 " contoso " 是同一个名字。
- 唯一性的**范围**是聚合的类型和租户：另一个租户可以持有同一个键，另一种类型的聚合也可以，所以品牌和分类可以同名。`reserve(namespace, key, ReservationTenancy.SYSTEM)` 让一个键在该类型的所有租户之间唯一。
- `reserve` 是一个 `suspend` 函数，所以预留键的决定函数要用 Kotlin 写。

## 预留会经历什么 {#what-happens-to-a-reservation}

1. `reserve` 为这个聚合把键记为**待定**，并带一个过期时间。
2. 决定的事件追加之后，预留被**确认**。
3. 当确定事件没有追加时（决定被拒绝、版本冲突后命令重新决定，或者决定失败），这次尝试**放弃它的预留**。进程先崩溃时，就让预留过期。

聚合已经持有的键，无论某次尝试做了什么都保持持有，所以重新决定一条命令永远不会丢掉它。

## 释放一个键 {#release-a-key}

值改变时释放它旧的键。`DecideScope.release(namespace, key)` 在事件追加之后释放聚合持有的一个键：释放与事件一起提交，事件没有追加时什么都不释放。重命名在同一次决定中预留新键、释放旧键：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#rename -->

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

删除聚合不会释放它的任何键，所以恢复的聚合仍持有它们。应当让出某个键的删除，要在删除之前，在聚合自己的命令里释放它。

## 跨聚合类型唯一的键 {#a-key-unique-across-aggregate-types}

一个键只在同一类型的聚合之间唯一。当一个值必须跨类型唯一时，例如用户和组织共用的电子邮件地址，就把这种唯一性建模为一个独立的聚合：以键为 id 的聚合（或持有这些键的聚合）由其他聚合通过它的命令来认领，由它自己的决策说明每个键归谁。

## 预留保存在哪里 {#where-reservations-are-kept}

一个聚合类型的预留与它的日志保存在一起，位于 `wow.aggregates.<context>.<aggregate>.log.store` 指定的存储（或默认的日志存储）中，每个类型有自己的表或集合：`wow_reservation__<context>__<aggregate>`。因此确认与它所属的提交一样持久。它们取代了 9.x 的 `PrepareKey`；migrate-data 会导入 9.x 已确认的键（[迁移数据](../migrate/data)）。

## 测试 {#test-it}

`aggregateSpec` 对着一个内存存储运行预留。`givenReservation` 先为另一个聚合预留一个键，这样测试可以检查键被占用时命令会被拒绝；`holds(namespace, key)` 说明被测的聚合是否持有某个键：见[测试领域代码](./testing)。
