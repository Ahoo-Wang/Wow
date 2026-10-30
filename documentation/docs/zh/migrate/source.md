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
| 手工添加的 `ksp("me.ahoo.wow:wow-compiler")` | 按角色的 Gradle 插件：领域模块用 `id("me.ahoo.wow")`（KSP），其 api 模块用 `id("me.ahoo.wow.api")`，服务器用 `id("me.ahoo.wow.service")`（Manifest 任务） |
| 领域代码中的 `wow-query` | `wow-query-api`：查询类型、Kotlin DSL 和字段注解 |
| `ISingleQuery`、`IListQuery`、`IPagedQuery`、`ICursorQuery`、`ICountQuery`、`IPagedList` | 类 `SingleQuery`、`ListQuery`、`PagedQuery`、`CursorQuery`、`CountQuery`、`PagedList`，用它们的 `of` 工厂或 DSL 构建；对查询泛型的代码接收 `Queryable<Q>` 或 `FilterCapable<Q>` |
| `ListQuery(limit = 0)`，在查询网关上返回全部匹配 | 不写 `limit` 取入口的默认列表大小，`0` 会被拒绝；要读全部匹配，用 `queryApi.trusted().unbounded()` 或 `stream` |
| 在 `limit` 处按分组键截断的聚合 | 加上 `sort` 得到前 N 名；不带排序时，分组多于 `limit` 会以 `TOO_MANY_GROUPS` 失败 |
| 返回 `Int` 的 `Pagination.offset()` | 返回 `Long` |
| `wow-test`（`AggregateSpec`） | `wow-test`，带函数式的 `aggregateSpec` 和 `sagaSpec` |
| `wow-redis`、`wow-bi`、`wow-apiclient`、`wow-compensation-*` | 已移除：没有 Redis 后端，改用 ClickHouse Sink、`CommandClient` 和 `QueryApi` 客户端、每个节点的失败台账 |
| `wow-cocache` | V10 的 `wow-cocache`：由拥有聚合的上下文为每个聚合类型写一份共享缓存，读者注入 `StateViewCache<V>`；读者自建的索引缓存与关联缓存没有对应物 |
| 返回 `Mono` 或 `Flux` 的处理函数 | `suspend` 函数，或通过 `wow-reactor` 使用 Reactor |

这些插件需要 Gradle 9 或更高版本、Kotlin JVM 插件 2.3 或更高版本：先升级 Gradle 8 的 wrapper。KSP 读取整个源码目录，忽略源码集的 `exclude`，所以每次只编译领域模块一部分的迁移，要把这些文件同步到单独的目录。

[V10 中的 9.x 能力](./capabilities)说明每项 9.x 能力的去向。

## 注解 {#annotations}

包仍然是 `me.ahoo.wow.api.annotation`，所以保留下来的名字不需要新的导入。V10 编译器把被移除的注解报告为错误，并指明它的替代。

| 9.x | V10 |
| --- | --- |
| `@BoundedContext(name, alias, …, aggregates)` | `@BoundedContext(name, description)`；上下文名是每个命令、事件和路由名的一部分，所以要有意地选择 |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle, published, publishState, topic, ownership, idSource, retiredEvents)`；挂载的命令是带 `CommandTransformer` 的 `@MountedCommand`，它的类放在其聚合所在的领域模块里，编译器在那里找到它 |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])`，只有返回 `Decision` 的函数才需要 |
| `@Event(revision = "0.0.1")` | `@Revision(n)`；迁移来的 9.x 形状是修订号 1 |
| `EventUpgrader` | 在从 `ObjectNode` 到 `ObjectNode` 的函数上标注 `@Upgrade(event, from)` |
| `DroppedEvent` | `@AggregateRoot(retiredEvents = ["<stored name>"])` |
| `@StatelessSaga`、`@ProjectionProcessor`、`@EventProcessor` | `@Saga`、`@Projection`、`@Reactor` |
| `@OnEvent(vararg aggregates)`、`@OnStateEvent`、`@OnMessage` | `@OnEvent`，一种接收 `StateEvent` 的处理函数 |
| `@Retry(maxRetries, minBackoff, executionTimeout)` | `@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)` 用于进程内的预算；9.x 的值变成停放条目的重试，`wow.subscriptions.[<context>.<processor.function>].parked.*`（`executionTimeout` 对应 `claim-ttl`） |
| `@OnError` | 已移除：返回拒绝，带上要记录的事件（`reject(code, message, events)`），在 `@Saga` 的 `@OnRejected` 处理函数中补偿 |
| `@PreparableKey` | `DecideScope.reserve(namespace, key, tenancy)` |
| 命令字段上的 `@AggregateId`、`@TenantId`、`@OwnerId`、`@AggregateVersion` | 已移除：身份是命令的 `ref`，作用域是调用方的 |
| `@StaticTenantId`、`@StaticAggregateId` | 宿主配置：`wow.aggregates.[<context>.<aggregate>].tenant` |
| `@AggregateRoute`、`@CommandRoute` | 已移除：路由来自 Manifest |
| `@AggregateOwner(OwnerPolicy.*)`、`@Spaced` | `@AggregateRoot(ownership = Ownership.OWNER \| TENANT)`；所有者和空间来自调用方。`OwnerPolicy.AGGREGATE_ID` 是 `@AggregateRoot(idSource = AggregateIdSource.OWNER)` |
| `@VoidCommand`、`@Order`、`@EntityObject`、`@ValueObject` | 已移除 |
| 无 | 拒绝码上的 `@ErrorCode`、用于之后重命名的 `@Alias`、`@QueryIndex`、`@Tags` |

## 命令不携带身份 {#commands-carry-no-identity}

命令体只保存业务数据。聚合是 `command.ref`，取自路由；所有者和空间是 `command.scope`，取自已认证的调用方。删除身份字段和路由注解，剩下的就是业务数据。eShop 的订单由一个只携带订单项的命令创建，ID 来自路由（没有字段剩下的命令变成 `object`）：

<!-- sample: examples/eshop/ordering/ordering-api/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/api/order/OrderCommands.kt#submit-order -->

```kotlin
/**
 * Submits an order of [items]. The basket context sends it when its buyer checks out, with the order's id fixed by the
 * basket. The order belongs to the buyer: a saga's command carries the scope of the basket it is derived from, whose
 * owner is its buyer, and the order takes its owner from the command that creates it. An order has
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

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

```kotlin
/**
 * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
 * `<context>.order.<code>`, as its peers read them from the api's generated contract.
 */
object Rejections {
    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "wrong-status"

    @ErrorCode(description = "A payment pays the order's total amount.")
    const val WRONG_AMOUNT = "wrong-amount"
}
```

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
```

拒绝携带一个错误码和一条消息：9.x 的 `bindingErrors` 放进消息里，读取它们的客户端要随之改变。9.x 的 `CommandValidator`，或者对命令自身字段的 `require`，变成命令类上的 Jakarta 约束，就像上面 `SubmitOrder` 带 `@field:NotEmpty @field:Valid` 的条目：网关在准入时检查它，并回答 `wow.command.invalid`（400）及其违反项。需要状态的规则仍是拒绝。

## 事件与状态 {#events-and-state}

- **名字。** V10 的事件名是 `<context>.<aggregate>.<Name>`；9.x 用的是蛇形命名（`order_created`）。不要用 `@Name` 保留 9.x 的拼写：在迁移映射中映射它。
- **修订号。** V10 形状与 9.x 所存储的不同的事件（去掉了身份字段、删掉了计算属性），要用更高的 `@Revision`，以及一个从修订号 1 升级的 `@Upgrade` 函数，并由一个使用真实 9.x 载荷的测试覆盖。
- **状态。** `onSourcing` 函数不是私有的，命令产生的每个事件都有一个，哪怕是空的；每个属性在创建之前都有值（不用 `lateinit`）。9.x 的 `StateAggregateTagsExtractor` 是状态的一个 `@Tags` 成员。
- **被删除的事件**要退役：删除类和它的溯源函数，在 `retiredEvents` 中列出存储时的名字，并用 `"retire": true` 映射它。
- **事件与状态中的 Wow 类型。** 存储下来的 `Condition`、`Sort` 或 `Projection`（例如保存的过滤条件）在 V10 中没有对应形式：`Condition` 协议已经没有了，内核的 JSON 没有查询协议的编解码，查询协议的枚举现在也是小写。改存应用自己的类型，用一个 `@Upgrade` 函数把 9.x 的 JSON 升级过去，并写测试覆盖。
- **事件的信封。** 读取 `DomainEvent.createTime` 或操作人的 9.x 溯源函数，目前在 V10 中没有对应形式：溯源函数只接收事件本身。把这个值放进事件；从 9.x 历史重建的状态缺少它，`compare` 会报告出来。V10 如何向溯源暴露信封，尚待决定。

## 处理器 {#processors}

Saga、投影或反应器恰好带有 `@Saga`、`@Projection`、`@Reactor` 之一；每个处理函数都是一个独立的订阅，名为 `processor.function`。处理函数接收提交的 `StateEvent`（或其中的一个事件，`StateTypedEvent<S, E>`），Saga 以 `CommandRequest` 的形式返回它推导出的命令，运行时把它们转成 ID 确定的命令：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>
): CommandRequest<AwaitStockValidation> = CommandRequest.of(AwaitStockValidation, event.ref.id)
```

处理过 9.x `onError` 的 Saga，要为被拒绝的推导命令加上 `@OnRejected` 处理函数；没有它时，拒绝会让聚合停放。处理其他上下文事件的处理器，通过那个上下文的 `*-api` jar 读取它发布的事件流（[发布与消费事件流](../guide/published-streams)）。

9.x 服务在 Simba 或 `@Scheduled` 里运行的周期作业，如果是从到期的内容推导命令，就是 Saga 的一个 `@OnSchedule` 处理函数，按 cron 规则运行；要等每条命令的结果再发下一条的作业，留在 Wow 之外，通过 `CommandClient` 发送（[定时命令](../guide/scheduling)）。9.x 把已存储事件重新投递给某个处理函数的回填，在 V10 中是一个新名字的处理函数，带 `@OnEvent(start = OnEvent.Start.BEGINNING)`，从事件流的开头读一遍（[重建与重放](../operate/replay)）。

## 预留键 {#reserved-keys}

9.x 的 `PrepareKey` 变成在 decide 函数中获取的预留；事件追加之后，内核会确认它。9.x 的键是全局的，V10 的键在每个聚合类型和租户内唯一，除非用 `ReservationTenancy.SYSTEM` 预留，所以要决定每个键的租户范围，并把同样的命名空间和租户范围写进映射的 `reservations`。多种类型的聚合共用的 9.x 键，变成一个持有这些键的独立聚合（[唯一键](../guide/unique-keys#a-key-unique-across-aggregate-types)）。品牌名，在其租户内唯一：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#take -->

```kotlin
/** Takes [name] for this brand, or rejects the command when another brand holds it. */
private suspend fun take(name: String, scope: DecideScope) {
    if (!scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
        reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
    }
}
```

[跨聚合的唯一键](../guide/unique-keys)解释预留。

## 路由、租户与所有权 {#routes-tenancy-and-ownership}

路由由 Manifest 推导：`POST /<context>/<aggregate>/<id>/<command>`，没有租户或所有者前缀，没有自定义动作或方法。固定的租户是宿主配置，9.x 中整个租户共用一个所有者的资源是 `Ownership.TENANT`，ID 即所有者 ID 的资源（`OwnerPolicy.AGGREGATE_ID`，例如用户的购物车）是 `idSource = AggregateIdSource.OWNER`：它不带 ID 地作为调用方自己的聚合提供，命令函数中不再留所有权检查（[每个所有者一个聚合](../guide/ownership#one-aggregate-per-owner)）。只有 `wow.lifecycle.TransferOwner` 能移动所有者：实现了 `OwnerTransferred` 的 9.x 事件，变成一个领域事件和一个推导出转移命令的 Saga。9.x starter 自带的路由按它们所做的事改写，用转换器、快照查询和 `Decision.NoOp`（[HTTP 与 TypeScript 客户端](./http-and-clients)列出了每个路由和请求头）。

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

::: warning 连续的 `whenCommand` 是链接的
在 9.x 中，同一个 `on { … }` 或 `givenEvent(…) { … }` 块里并列的 `whenCommand` 各自从那个 given 开始：它们是互相独立的场景。在 V10 中，一个 spec 是一个序列，每个 `whenCommand` 都作用于上一个留下的聚合。逐个调用照搬改写的 9.x spec 能编译，但后面的场景会运行在错误的状态上。把每组并列的调用改写为 given 之后的 `fork(…) { whenCommand(…) { … } }` 分支，每个并列调用一个（[分叉开头相同的场景](../guide/testing#fork-scenarios-that-share-a-beginning)）。
:::

### 聚合 spec {#aggregate-specs}

| 9.x | V10 |
| --- | --- |
| `class XSpec : AggregateSpec<A, S>({ on { … } })`，一个 `@TestFactory` | `@Test fun x() = aggregateSpec(model) { … }`，一个测试；`@TestFactory fun x() = aggregateSpecTests(model) { … }`，每个步骤和分叉一个测试 |
| `aggregateVerifier<A, S>()` 及其链式阶段（Kotlin 或 Java 调用） | 一个 `aggregateSpec`：`.whenCommand(c).expect…().verify()` 是 `whenCommand(c) { expect… }`；`.then()` 与 `.then(false)` 是下一个 `whenCommand`，因为 spec 是链接的，拒绝不改变任何东西；`.fork { … }` 是检查中的 `fork(name) { … }`。测试 DSL 只支持 Kotlin |
| `inject { register(service) }` | 生成的模型工厂接收的服务（`orderModel(name, inventory)`，第一个参数是带默认值的上下文名，只传服务时按参数名传）；某个分支需要服务给出不同回答时，用 `fork(name, model = …)` |
| `givenOwnerId(…)`、`givenSpaceId(…)` | `given`、`givenState` 或 `whenCommand` 上的 `scope = Scope(owner, space)` |
| `givenEvent(event) { … }`、`givenEvent(arrayOf(…)) { … }` | `given(vararg events)`，一个独立的步骤；`givenState(state, version)` 不变 |
| `whenCommand(command, header, ownerId, spaceId)` | `whenCommand(command, requestId, expectedVersion, scope, headers, operator) { … }` |
| 同一 given 上并列的 `whenCommand` | `fork(…)` 分支（见上文） |
| 已验证阶段上的 `ref("name")`、`fork("name") { … }` | spec 中的 `ref("name")`、`fork(name, from = "name") { … }`；或者在检查中使用 `fork { … }` |
| 经由 `TestValidator` 的命令校验 | 已去掉：校验是准入层的 `CommandValidator`，内核和 spec 都不经过它；在 HTTP 层测试它 |
| `TestGlobalIdGeneratorFactory` | 不需要设置：在 spec 中，`DecideScope.ids` 给出顺序的 ID |

### 聚合的期望 {#aggregate-expectations}

| 9.x | V10 |
| --- | --- |
| `expectNoError()` | `expectNoError()` |
| `expectError()`、`expectErrorType(X::class)` | `expectError<X>()`，用于 decide 函数抛出的异常；`expectRejected(code)` 用于它返回的拒绝 |
| `expectEventType(A::class, B::class)` | `expectEventTypes(A::class, B::class)` |
| `expectEventCount(n)` | `expectEventCount(n)` |
| `expectEvent<E> { … }`、`expectEventBody<E> { … }`：该类型的第一个事件 | `expectEvent<E> { … }`：恰好一个事件，且是 `E`；多个事件按值用 `expectEvents(…)` |
| `expectEventStream { … }`、`expectEventIterator { … }` | 整个流用 `expectEvents(…)` 或 `expectEventTypes(…)`；单个事件连同信封用 `expectEventEnvelope<E> { … }` |
| 事件的 `aggregateId`、`version`、`commandId`、`requestId`、`header` | `expectEventEnvelope<E> { aggregateId; version; commandId; requestId; headers }` |
| 事件的操作者和创建时间 | `expectEventEnvelope<E> { operator; commitTime }`，用于提交了一个事件的命令 |
| `expectState { … }`、`expectState(Consumer)` | `expectState { … }` 或 `expectState(expected)`；`Consumer` 重载已去掉 |
| `expectStateAggregate { version }` | `expectVersion(n)`：提交后的版本，或命令什么都没追加时聚合停留的版本 |
| `expectStateAggregate { deleted }` | `expectDeleted()` |
| `expectStateAggregate { ownerId; spaceId }`、标签 | `expectScope(scope)`、`expectTags(tags)` |

较小的差异：`expectEvent` 和 saga 的 `expectCommand` 现在要求恰好一个，而 9.x 取第一个匹配项；什么都没提交的命令用 `expectUnchanged()`。

### Saga spec {#saga-specs}

| 9.x | V10 |
| --- | --- |
| `class XSpec : SagaSpec<T>({ on { … } })` | `sagaSpec(<saga>Handlers(…)) { … }`，测试树用 `sagaSpecTests` |
| `inject { … }` | 处理器的依赖，交给生成的处理器工厂；读取用 `givenQueries(queries)` |
| 命令网关和消息工厂 | 已去掉：`answerCalls { … }` 回答同步调用 |
| `functionFilter { … }`、`functionName("onX")` | `whenEvent(event, handler = "onX")` |
| `whenEvent(event, state, ownerId, spaceId)` | `whenEvent(stateEvent(ref, state, events…, scope = …))`；对端上下文的事件：`publishedEvent(name, body)` |
| `expectCommandType(X::class)` | `expectCommandTypes(X::class)` |
| `expectCommandCount(n)` | `expectCommandCount(n)` |
| `expectCommand<C> { … }`、`expectCommandBody<C> { … }`：第一条命令 | `expectCommand<C> { … }` 或 `expectCommand(body, to = ref)`：恰好一条命令；多条用 `expectCommands(…)` |
| `expectCommandStream`、`expectCommandIterator` | `commands`、`expectCommands(…)`、`expectCommandTypes(…)` |
| `expectNoCommand()`、`expectNoError()`、`expectError…` | 名称不变；`expectError<X>()` 与聚合相同 |

V10 新增、9.x 没有对应的：用于 saga 处理回执的 `whenRejected` 和 `whenCommitted`、`expectDeterministic`、`expectHandledBy`、`expectCalls`、定时相关的期望，以及 `projectionSpec`、`reactorSpec` 和 `scheduleSpec`。

## Skill 无法完成的事 {#what-the-skill-cannot-do}

有些 9.x 构造没有 V10 的形式。Skill 会报告服务用到的每一个，以及所做的决定；它从不模拟其中任何一个：

- 自定义路由、方法以及路径或请求头变量：客户端和网关规则要随之改变；
- `bindingErrors`、`@VoidCommand`、`@Order`、swagger 注解；
- 9.x 的 Redis 事件存储：数据工具只读取 MongoDB；
- 基于 `AggregateVerifier` 的 Java 测试，以及对 `suspend` API（`DecideScope.reserve`、`SagaContext.call`）的 Java 调用：只支持 Kotlin；
- 在类路径上发现的 Jackson 模块，以及没有公共 getter 的字段：`WowJson` 两者都不应用；
- 状态事件重发和按版本重新分发：10.x 之前都没有；改为重试停放的条目；
- 在运行时注册的 `MessageFunction`：把它改写为带注解的处理器。

下一步：[配置](./configuration)、[HTTP 与 TypeScript 客户端](./http-and-clients)，然后是[数据](./data)和[切换](./cutover)。
