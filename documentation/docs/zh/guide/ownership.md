---
title: 所有者、空间与标签
description: 决定聚合除了租户之外还属于谁，让每个所有者拥有一个以所有者 ID 为 ID 的聚合，在命令中核对调用方，转移所有者和空间，并为基于属性的访问给聚合打标签。
outline: deep
---

# 所有者、空间与标签

每个聚合都属于它的租户。大多数聚合还属于某个人：订单属于它的买家，保存的视图属于保存它的人。Wow 把这一点记为聚合的**作用域**，即它的所有者和空间，并把每个调用方限制在它作用域内的聚合上。本页选择聚合的归属、让每个所有者拥有某类聚合中的一个、在作用域不够用的地方核对调用方，以及改变所有者、空间和标签。模型见[身份、租户与访问](../concepts/identity)。

## 选择聚合归谁所有 {#choose-who-an-aggregate-belongs-to}

由 `@AggregateRoot(ownership = ...)` 声明：

| `Ownership` | 聚合属于 | 例如 |
| --- | --- | --- |
| `OWNER`（默认） | 它的租户，以及创建它的命令的所有者和空间 | 订单、购物车、保存的视图 |
| `TENANT` | 它的租户，以及它所在的空间（如果有）；不记录所有者 | 商品、品牌，以及租户的每个调用方都要读的库存 |

商品由每位顾客读取、由员工修改，所以它属于它的租户：

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

编译器把归属写进 Manifest，即 `"ownership": "tenant"`（`OWNER` 聚合作为默认值省略它），查询准入和状态路由从那里读取它。之后再改它会改变谁能读取这个聚合，所以契约的兼容性检查把它当作破坏性变更拒绝。

## 内核检查什么 {#what-the-kernel-checks}

第一条命令的调用方为 `OWNER` 聚合确定作用域。此后，调用方的所有者或空间与聚合不同时，内核以 `wow.aggregate.forbidden` 拒绝命令。调用方没有值的那一项不检查：

- **所有者级**调用方的作用域有所有者，只能接触它自己的聚合。
- **租户级**调用方没有所有者，例如员工，可以接触它租户的每个聚合；它在某个空间里行事时，限于这个空间。它能做什么由动作级授权决定，例如 CoSec 的授权。

`TENANT` 聚合不记录所有者，所以它的写入不对照所有者检查；调用方的空间仍然检查。谁能修改商品只由动作级授权决定：没有授权器时，路由放行的任何调用方都能发送它的命令（[授权命令](../operate/security#authorize-commands)）。

## 谁能读取它 {#who-reads-it}

读取遵循同样的作用域。查询准入把它加进这个聚合类型的每个查询，包括它的事件流；状态路由在状态读取和 trace 时检查它：

- `OWNER` 聚合在调用方的租户、所有者和空间之内读取，每一项都以调用方有值为前提：所有者级调用方读取自己的，租户级调用方读取整个租户。
- `TENANT` 聚合在调用方的租户和空间之内读取，从不加上所有者。租户里的每个调用方都能读取商品，购物者与员工一样。

所以 `TENANT` 不按角色限制读取。属于租户、但只有部分调用方可以读取的聚合，例如运维控制台列出的失败、分析目录的数据源，需要服务自己的读取策略：一个 `QueryPolicy` bean，拒绝没有该角色的调用方（[授权读取](../operate/security#authorize-reads)）。受信代码和订阅的处理函数不带调用方地读取整个租户，这样的策略会为它们让开。

## 每个所有者一个聚合 {#one-aggregate-per-owner}

有些聚合每个所有者只有一个：一个买家有一个购物车，一个用户有一套偏好设置。这样的聚合以所有者的 ID 作为自己的 ID，由 `@AggregateRoot(idSource = AggregateIdSource.OWNER)` 声明：

<!-- sample: fragment -->

```kotlin
@AggregateRoot(idSource = AggregateIdSource.OWNER)
class Basket(private val state: BasketState) {
    fun onCommand(command: AddBasketItem): BasketItemAdded =
        BasketItemAdded(command.productId, command.unitPrice, command.quantity)
}
```

于是每个所有者在它的租户中恰好有一个购物车，它的 ID 就是买家的用户 ID。这个声明要求 `ownership = OWNER`，即默认值：属于租户的聚合没有所有者可以取 ID。它的所有者永不改变，所以默认的生命周期不含 `TransferOwner`，编译器拒绝在 `lifecycle` 中列出它，也拒绝产生 `OwnerTransferred` 的命令。默认的 `AggregateIdSource.ASSIGNED` 取请求指明的 ID，或者为创建命令生成一个。

### 它的路由 {#its-routes}

这种聚合的每条命令，包括生命周期命令，除了按 ID 的路由之外，还有一条不带 ID 的路由。不带 ID 的路由指调用方自己的聚合：

| 路由 | 提供 |
| --- | --- |
| `POST /<context>/<aggregate>/<command>` | 发往调用方自己的聚合的命令：`POST /eshop-basket/basket/add-basket-item` |
| `POST /<context>/<aggregate>/wow.lifecycle.<command>`、`DELETE /<context>/<aggregate>` | 发往调用方自己的聚合的生命周期命令 |
| `GET /<context>/<aggregate>/state`、`…/state/trace` | 调用方自己的聚合的状态和历史 |
| 按 ID 的路由：`POST /<context>/<aggregate>/{id}/<command>`、`GET …/{id}/state` | 那个 ID 的聚合，供没有所有者的调用方使用 |

没有路由会生成 ID：创建命令创建的是它的所有者的聚合。

### 谁指明 ID {#who-names-the-id}

准入从调用方取得 ID：

- **所有者级**调用方的聚合就是它自己的：ID 是它的所有者，无论走不带 ID 的路由还是用它自己的 ID；通过 `Wow-Scope` 在另一个作用域中行事的调用方，接触的是那个作用域的所有者的聚合。指明另一个所有者的 ID 会被以 `403 wow.aggregate.forbidden` 拒绝。
- **没有所有者**的调用方，例如租户级的员工、服务或 Saga，没有自己的聚合：它在按 ID 的路由上指明 ID。它走不带 ID 的路由时得到 `400 wow.command.malformed`（"This caller has no owner; address the aggregate by its id."），读取状态时得到 `400 wow.query.malformed`。未认证的调用方得到 `401 wow.auth.unauthenticated`。
- Saga 派生的命令或定时命令没有指明 ID 时，取它所带作用域的所有者：跟随买家订单的 Saga 接触的是那个买家的购物车。

内核把聚合的 ID 记为它的所有者，无论是谁创建它，并拒绝所有者不同的命令，创建命令也一样。因此命令函数不必核对调用方：上面的购物车不需要自己的所有权检查。

## 在命令中核对调用方 {#check-the-caller-in-a-command}

作用域仍不够用的地方，命令函数接收 `Command<X>`，读取 `command.scope`（调用方的所有者和空间），并用自己的错误码拒绝命令（[读取准入后的命令](./aggregates#read-the-admitted-command)）。

## Saga 发出的命令 {#commands-a-saga-sends}

Saga 派生的命令、补偿和定时命令，带着它们所跟随的那次提交的作用域和操作人。所以 Saga 创建的聚合属于同一个所有者：购物车结账创建的订单属于购物车的买家。ID 即所有者 ID 的聚合则以这个 ID 作为所有者（[每个所有者一个聚合](#one-aggregate-per-owner)）。见[编写 Saga](./sagas#build-the-commands)。

## 转移所有者或空间 {#transfer-an-owner-or-a-space}

两条生命周期命令改变聚合的作用域；除非 `@AggregateRoot(lifecycle = [...])` 把它们排除在外，每个聚合都处理它们：

| 命令 | 路由 | 请求体 |
| --- | --- | --- |
| `TransferOwner` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `{"ownerId": "…"}` |
| `TransferSpace` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `{"spaceId": "…"}` |

`TENANT` 聚合不处理 `TransferOwner`，ID 即所有者 ID 的聚合也不处理。每次提交都记录提交之后的作用域，所以投影或查询不用重放历史就能读到当前的所有者。

## 给聚合打标签 {#tag-aggregates}

标签是聚合上的键值对。基于属性的访问在读取时用调用方的属性匹配它们：查询只返回调用方属性所允许其标签的那些聚合。设置标签有两种方式：

- **应用的：** 生命周期命令 `ApplyTags`，即 `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags`，请求体为 `{"tags": {"region": ["eu"]}}`，替换已应用的标签。
- **推出的：** 状态中标注 `@Tags` 的成员（一个属性，或一个无参数、返回 `Map<String, Collection<String>>` 的函数）从状态推出标签。它在每次提交之后求值，它返回的每个键会替换该键已应用的值。每次调用都返回它负责的所有键，没有值的用空列表。

基于属性的访问默认关闭。`wow.query.abac.enabled: true` 打开它，调用方以前缀 `abac.` 命名的属性（`abac.region`）与同名键的标签匹配（[配置项](../reference/configuration#wow-query-abac)）。

## 固定的租户 {#a-fixed-tenant}

所有聚合都放在同一个租户里（无论调用方是哪个租户）的聚合类型，从宿主获取租户：`wow.aggregates.[<context>.<aggregate>].tenant`。

## 在另一个作用域中行事 {#act-in-another-scope}

被允许在另一个租户或作用域中行事的调用方，例如客服工具，在 `Wow-Scope` 请求头中指明它，或者在命令客户端的 `CommandRequest`（`targetTenant`、`targetScope`）中指明。提交会记录操作人是在另一个作用域中行事的。
