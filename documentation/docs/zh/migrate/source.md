---
title: 迁移源码
description: 用 wow-migrate Skill 把 9.x 服务的 Kotlin 源码改写为 V10：每种 9.x 构造变成什么、迁移映射记录什么，以及 Skill 无法替你完成的事。
---

# 迁移源码

9.x 服务的源码要为 V10 改写，而不是升级：注解失去它们的 9.x 属性，命令体失去身份，异常变成拒绝，处理器变成订阅。`wow-migrate` 这个 Agent Skill 与你一起完成改写，并把每一处名字和形状的变化记录在 `migration-map.json` 中，接下来由数据工具读取它（[迁移数据](./data)）。本页说明改写做了什么；Skill 的参考文档 `skills/wow-migrate/references/v9-to-v10.md` 是它遵循的完整映射。

## 开始之前 {#before-you-start}

- **9.x 的最后一个次版本。** V10 只从它迁移。先在 9.x 之内升级。
- **Gradle、JDK 21、带 KSP 的 Kotlin。** V10 在构建时、在 Gradle 中绑定模型和 Manifest。用 Maven 构建的 9.x 服务，要么在迁移中改用 Gradle，要么留在 9.x。
- **固定目标版本。** 指明确切的 V10 版本，并对照它核实每个注解、签名、路由和配置项。
- **源码要改写，数据要转换。** 永远不要为了适应新源码而修改已存储的事件：这由映射文件和数据工具完成。

## 运行 Skill {#run-the-skill}

从 Ahoo Skills Hub 安装 `ahoo-wow-skills` 插件，或者把固定的 Wow tag 中的 `skills/wow-migrate` 复制到你的 Agent 的 Skills 目录，然后让 Agent 迁移这个服务，并指明 9.x 和 V10 的版本。它分阶段工作，每个阶段有自己的关卡：构建、源码、映射，最后是对着真实 HTTP 请求的运行时。遇到无法决定的改写，例如某个键的唯一性或某个资源的所有者，它会询问，或者在无法编译的代码中留下 `TODO(wow-migrate)`；它从不把猜测的改写写成能编译的代码。

服务在 V10 编译器下能编译、领域测试通过，改写才算被接受。这证明的是源码，而不是数据：每个重建的聚合都与它的 9.x 快照一致，数据才算得到证明。

## 构建 {#the-build}

| 9.x | V10 |
| --- | --- |
| 带能力变体的 `wow-spring-boot-starter` | `wow-spring-boot`，加上每种技术一个模块：`wow-postgres`、`wow-mongo`、`wow-kafka`、`wow-http`、`wow-cosec` |
| `wow-core`、`wow-spring`、`wow-webflux`、`wow-metadata`、`wow-schema` | `wow-kernel` 和 `wow-runtime`、`wow-http`、`wow-manifest` |
| 手工添加的 `ksp("me.ahoo.wow:wow-compiler")` | Gradle 插件 `id("me.ahoo.wow")`，它应用 KSP 和 Manifest 任务 |
| 领域代码中的 `wow-query` | `wow-query-api`：查询类型、Kotlin DSL 和字段注解 |
| `wow-test`（`AggregateSpec`） | `wow-test`，带函数式的 `aggregateSpec` 和 `sagaSpec` |
| `wow-redis`、`wow-bi`、`wow-apiclient`、`wow-cocache`、`wow-compensation-*` | 已移除：没有 Redis 后端，改用 ClickHouse Sink、`CommandClient` 和 `QueryApi` 客户端、每个节点的失败台账 |
| 返回 `Mono` 或 `Flux` 的处理函数 | `suspend` 函数，或通过 `wow-reactor` 使用 Reactor |

[V10 中的 9.x 能力](./capabilities)说明每项 9.x 能力的去向。

## 注解 {#annotations}

包仍然是 `me.ahoo.wow.api.annotation`，所以保留下来的名字不需要新的导入。V10 编译器把被移除的注解报告为错误，并指明它的替代。

| 9.x | V10 |
| --- | --- |
| `@BoundedContext(name, alias, …, aggregates)` | `@BoundedContext(name, description)`；上下文名是每个命令、事件和路由名的一部分，所以要有意地选择 |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle, published, publishState, topic, ownership, retiredEvents)`；挂载的命令是带 `CommandTransformer` 的 `@MountedCommand` |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])`，只有返回 `Decision` 的函数才需要 |
| `@Event(revision = "0.0.1")` | `@Revision(n)`；迁移来的 9.x 形状是修订号 1 |
| `EventUpgrader` | 在从 `ObjectNode` 到 `ObjectNode` 的函数上标注 `@Upgrade(event, from)` |
| `DroppedEvent` | `@AggregateRoot(retiredEvents = ["<stored name>"])` |
| `@StatelessSaga`、`@ProjectionProcessor`、`@EventProcessor` | `@Saga`、`@Projection`、`@Reactor` |
| `@OnEvent(vararg aggregates)`、`@OnStateEvent`、`@OnMessage` | `@OnEvent`，一种接收 `StateEvent` 的处理函数 |
| `@Retry(maxRetries, minBackoff, executionTimeout)` | `@Retry(maxAttempts, initialBackoff, maxBackoff, timeout)` 用于进程内的预算；9.x 的值变成停放条目的重试，`wow.subscriptions.[<processor.function>].parked.*` |
| `@OnError` | 已移除：返回拒绝，在 `@Saga` 中补偿 |
| `@PreparableKey` | `DecideScope.reserve(namespace, key, tenancy)` |
| 命令字段上的 `@AggregateId`、`@TenantId`、`@OwnerId`、`@AggregateVersion` | 已移除：身份是命令的 `ref`，作用域是调用方的 |
| `@StaticTenantId`、`@StaticAggregateId` | 宿主配置：`wow.aggregates.[<context>.<aggregate>].tenant` |
| `@AggregateRoute`、`@CommandRoute` | 已移除：路由来自 Manifest |
| `@AggregateOwner(OwnerPolicy.*)`、`@Spaced` | `@AggregateRoot(ownership = Ownership.OWNER \| TENANT)`；所有者和空间来自调用方 |
| `@VoidCommand`、`@Order`、`@EntityObject`、`@ValueObject` | 已移除 |
| 无 | 拒绝码上的 `@ErrorCode`、用于之后重命名的 `@Alias`、`@QueryIndex`、`@Tags` |

## 命令不携带身份 {#commands-carry-no-identity}

命令体只保存业务数据。聚合是 `command.ref`，取自路由；所有者和空间是 `command.scope`，取自已认证的调用方。删除身份字段和路由注解，剩下的就是业务数据。eShop 的订单由一个只携带订单项的命令创建，ID 来自路由（没有字段剩下的命令变成 `object`）：

<!-- sample: example/eshop/ordering/ordering-api/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/api/order/OrderCommands.kt#submit-order -->

```kotlin
/**
 * Submits an order of [items]. The basket context sends it when its buyer checks out, with the order's id fixed by the
 * basket. The order belongs to the buyer: a saga's command carries the scope of the basket it is derived from, whose
 * owner is its buyer (decision D189), and the order takes its owner from the command that creates it. An order has
 * items, each valid ([OrderItem]'s constraints): the gateway checks that before the order decides.
 */
@CreateAggregate
@Summary("Submit an order")
data class SubmitOrder(
    @field:NotEmpty @field:Valid val items: List<OrderItem>,
)
```

在 decide 中，`CommandMessage<X>` 是 `Command<X>`，`command.aggregateId.id` 是 `command.ref.id`，`command.ownerId` 是 `command.scope.ownerId`，9.x 的 `CommandResultAccessor` 是 `DecideScope.putResult`，在 decide 中生成的 ID 来自 `scope.ids.next()`。

## 异常变成拒绝 {#exceptions-become-rejections}

`require`、`check` 或抛出的领域异常变成 `Decision.reject(code, message)`，错误码在聚合旁边用 `@ErrorCode` 声明一次。它的 HTTP 状态默认为 422；客户端依赖其状态的 9.x 错误码，用 `status` 保留它：

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

```kotlin
/**
 * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
 * `<context>.order.<code>` (decision D259), as its peers read them from the api's generated contract.
 */
object Rejections {
    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "wrong-status"

    @ErrorCode(description = "A payment pays the order's total amount.")
    const val WRONG_AMOUNT = "wrong-amount"
}
```

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
@OnCommand(produces = [OrderSubmitted::class])
fun onCommand(command: SubmitOrder): Decision = Decision.events(OrderSubmitted(command.items))
```

拒绝携带一个错误码和一条消息：9.x 的 `bindingErrors` 放进消息里，读取它们的客户端要随之改变。9.x 的 `CommandValidator`，或者对命令自身字段的 `require`，变成命令类上的 Jakarta 约束，就像上面 `SubmitOrder` 带 `@field:NotEmpty @field:Valid` 的条目：网关在准入时检查它，并回答 `wow.command.invalid`（400）及其违反项。需要状态的规则仍是拒绝。

## 事件与状态 {#events-and-state}

- **名字。** V10 的事件名是 `<context>.<aggregate>.<Name>`；9.x 用的是蛇形命名（`order_created`）。不要用 `@Name` 保留 9.x 的拼写：在迁移映射中映射它。
- **修订号。** V10 形状与 9.x 所存储的不同的事件（去掉了身份字段、删掉了计算属性），要用更高的 `@Revision`，以及一个从修订号 1 升级的 `@Upgrade` 函数，并由一个使用真实 9.x 载荷的测试覆盖。
- **状态。** `onSourcing` 函数不是私有的，命令产生的每个事件都有一个，哪怕是空的；每个属性在创建之前都有值（不用 `lateinit`）。9.x 的 `StateAggregateTagsExtractor` 是状态的一个 `@Tags` 成员。
- **被删除的事件**要退役：删除类和它的溯源函数，在 `retiredEvents` 中列出存储时的名字，并用 `"retire": true` 映射它。

## 处理器 {#processors}

Saga、投影或 Reactor 恰好带有 `@Saga`、`@Projection`、`@Reactor` 之一；每个处理函数都是一个独立的订阅，名为 `processor.function`。处理函数接收提交的 `StateEvent`（或其中的一个事件，`StateTypedEvent<S, E>`），Saga 返回它推导出的命令，用 `context.command(…)` 构建，让它们的 ID 是确定的：

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>,
    context: SagaContext,
): Command<AwaitStockValidation> = context.command(event.ref.id, AwaitStockValidation)
```

处理过 9.x `onError` 的 Saga，要为被拒绝的推导命令加上 `@OnRejected` 处理函数；没有它时，拒绝会让聚合停放。处理其他上下文事件的处理器，通过那个上下文的 `*-api` jar 读取它发布的事件流（[发布与消费事件流](../guide/published-streams)）。

## 预留键 {#reserved-keys}

9.x 的 `PrepareKey` 变成在 decide 函数中获取的预留；事件追加之后，内核会确认它。9.x 的键是全局的，V10 的键在每个租户内唯一，除非用 `ReservationTenancy.SYSTEM` 预留，所以要决定每个键的租户范围，并把同样的命名空间和租户范围写进映射的 `reservations`。品牌名，在其租户内唯一：

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#take -->

```kotlin
/** Rejects a name another brand holds; null when this brand may take [name]. */
private suspend fun take(name: String, scope: DecideScope): Decision? =
    if (scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
        null
    } else {
        Decision.reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
    }
```

[跨聚合的唯一键](../guide/unique-keys)解释预留。

## 路由、租户与所有权 {#routes-tenancy-and-ownership}

路由由 Manifest 推导：`POST /<context>/<aggregate>/<id>/<command>`，没有租户或所有者前缀，没有自定义动作或方法。固定的租户是宿主配置，9.x 中整个租户共用一个所有者的资源是 `Ownership.TENANT`。只有 `wow.lifecycle.TransferOwner` 能移动所有者：实现了 `OwnerTransferred` 的 9.x 事件，变成一个领域事件和一个推导出转移命令的 Saga。9.x starter 自带的路由按它们所做的事改写，用转换器、快照查询和 `Decision.NoOp`（[HTTP 与 TypeScript 客户端](./http-and-clients)列出了每个路由和请求头）。

## 迁移映射 {#the-migration-map}

命令或事件的名字、上下文、修订号或形状的每一处变化，都是 `migration-map.json`（格式 1）中的一个条目：

<!-- sample: fragment -->

```json
{
  "format": 1,
  "headers": { "app_id": "app_id" },
  "aggregates": [
    {
      "from": "example-service.order",
      "to": "example.order",
      "commands": [{ "from": "create_order", "to": "CreateOrder" }],
      "events": [
        { "from": "order_created", "fromRevision": "0.0.1", "to": "OrderCreated", "toRevision": 1, "upgrade": "upgradeOrderCreatedFrom1" },
        { "from": "order_archived", "to": "OrderArchived", "retire": true }
      ],
      "reservations": [{ "from": "order_no", "namespace": "order.number", "tenancy": "TENANT" }]
    }
  ]
}
```

映射 9.x 代码可能产生的每个事件和命令，而不仅是它的存储今天保存的那些：9.x 会一直写入到切换为止，它首次存储的事件会让导入停下来。重命名的状态属性放在聚合的 `"state": {"fields": …}` 之下，这样比对才会读取它。数据工具在写入任何东西之前，都会对照 9.x 存储和 V10 的 Manifest 检查映射（[迁移数据](./data)）。

## 测试 {#tests}

`AggregateSpec<A, S>` 变成基于生成模型的函数式 `aggregateSpec`：`given` 事件、`whenCommand`，然后 `expectEvents`、`expectRejected(code)` 和 `expectState`。保留每个 9.x 场景，并为每个新的拒绝码、每个升级函数，以及移除了所有权策略之处的作用域守卫各加一个场景（[测试领域代码](../guide/testing)）。

## Skill 无法完成的事 {#what-the-skill-cannot-do}

有些 9.x 构造没有 V10 的形式。Skill 会报告服务用到的每一个，以及所做的决定；它从不模拟其中任何一个：

- 自定义路由、方法以及路径或请求头变量：客户端和网关规则要随之改变；
- `bindingErrors`、`@VoidCommand`、`@Order`、swagger 注解；
- 9.x 的 Redis 事件存储：数据工具只读取 MongoDB；
- 基于 `AggregateVerifier` 的 Java 测试，以及对 `suspend` API（`DecideScope.reserve`、`SagaContext.call`）的 Java 调用：只支持 Kotlin；
- 在类路径上发现的 Jackson 模块，以及没有公共 getter 的字段：`WowJson` 两者都不应用；
- 状态事件重发和按版本重新分发：10.x 之前都没有；改为恢复停放的条目；
- 在运行时注册的 `MessageFunction`：把它改写为带注解的处理器。

下一步：[配置](./configuration)、[HTTP 与 TypeScript 客户端](./http-and-clients)，然后是[数据](./data)和[切换](./cutover)。
