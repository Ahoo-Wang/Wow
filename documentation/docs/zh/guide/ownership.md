---
title: 所有者、空间与标签
description: 决定聚合除了租户之外还属于谁，在命令中核对调用方，转移所有者和空间，并为基于属性的访问给聚合打标签。
outline: deep
---

# 所有者、空间与标签

每个聚合都属于它的租户。大多数聚合还属于某个人：订单属于它的买家，保存的视图属于保存它的人。Wow 把这一点记为聚合的**作用域**，即它的所有者和空间，并把每个调用方限制在它作用域内的聚合上。本页选择聚合的归属、在作用域不够用的地方核对调用方，以及改变所有者、空间和标签。模型见[身份、租户与访问](../concepts/identity)。

## 选择聚合归谁所有 {#choose-who-an-aggregate-belongs-to}

由 `@AggregateRoot(ownership = ...)` 声明：

| `Ownership` | 聚合属于 | 例如 |
| --- | --- | --- |
| `OWNER`（默认） | 它的租户，以及创建它的命令的所有者和空间 | 订单、购物车、保存的视图 |
| `TENANT` | 它的租户，以及它所在的空间（如果有）；不记录所有者 | 商品、品牌，以及租户的每个调用方都要读的库存 |

商品由每位顾客读取、由员工修改，所以它属于它的租户：

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

编译器把归属写进 Manifest，查询准入从那里读取它。

## 内核检查什么 {#what-the-kernel-checks}

第一条命令的调用方为 `OWNER` 聚合确定作用域。此后，调用方的所有者或空间与聚合不同时，内核以 `wow.aggregate.forbidden` 拒绝命令，查询准入也会把调用方的所有者和空间加进每个查询。调用方没有值的那一项不检查：

- **所有者级**调用方的作用域有所有者，只能接触它自己的聚合。
- **租户级**调用方没有所有者，例如员工，可以接触它租户的每个聚合。它能做什么由动作级授权决定，例如 CoSec 的授权。

`TENANT` 聚合的写入根本不对照所有者检查：谁能修改商品由动作级授权决定。

## 在命令中核对调用方 {#check-the-caller-in-a-command}

有时作用域还不够。购物车的 ID 就是买家的用户 ID，所以创建它的第一条命令要核对调用方就是这个买家。命令函数接收 `Command<X>`，把 `command.scope.ownerId` 与 ID 比较：

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

条目的数量和价格是命令的形状：它的约束在函数运行之前就已检查（[编写聚合](./aggregates#check-its-shape)），所以函数只检查调用方。购物车存在之后，内核把它限定给创建它的所有者。

## Saga 发出的命令 {#commands-a-saga-sends}

Saga 派生的命令、补偿和定时命令，带着它们所跟随的那次提交的作用域和操作人。所以 Saga 创建的聚合属于同一个所有者：购物车结账创建的订单属于购物车的买家。见[编写 Saga](./sagas#build-the-commands)。

## 转移所有者或空间 {#transfer-an-owner-or-a-space}

两条生命周期命令改变聚合的作用域；除非 `@AggregateRoot(lifecycle = [...])` 把它们排除在外，每个聚合都处理它们：

| 命令 | 路由 | 请求体 |
| --- | --- | --- |
| `TransferOwner` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `{"ownerId": "…"}` |
| `TransferSpace` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `{"spaceId": "…"}` |

`TENANT` 聚合不处理 `TransferOwner`。每次提交都记录提交之后的作用域，所以投影或查询不用重放历史就能读到当前的所有者。

## 给聚合打标签 {#tag-aggregates}

标签是聚合上的键值对。基于属性的访问在读取时用调用方的属性匹配它们：查询只返回调用方属性所允许其标签的那些聚合。设置标签有两种方式：

- **应用的：** 生命周期命令 `ApplyTags`，即 `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags`，请求体为 `{"tags": {"region": ["eu"]}}`，替换已应用的标签。
- **推出的：** 状态中标注 `@Tags` 的成员（一个属性，或一个无参数、返回 `Map<String, Collection<String>>` 的函数）从状态推出标签。它在每次提交之后求值，它返回的每个键会替换该键已应用的值。每次调用都返回它负责的所有键，没有值的用空列表。

基于属性的访问默认关闭。`wow.query.abac.enabled: true` 打开它，调用方以前缀 `abac.` 命名的属性（`abac.region`）与同名键的标签匹配（[配置项](../reference/configuration#wow-query-abac)）。

## 固定的租户 {#a-fixed-tenant}

所有聚合都放在同一个租户里（无论调用方是哪个租户）的聚合类型，从宿主获取租户：`wow.aggregates.[<context>.<aggregate>].tenant`。

## 在另一个作用域中行事 {#act-in-another-scope}

被允许在另一个租户或作用域中行事的调用方，例如客服工具，在 `Wow-Scope` 请求头中指明它，或者在命令客户端的 `CommandRequest`（`targetTenant`、`targetScope`）中指明。提交会记录操作人是在另一个作用域中行事的。
