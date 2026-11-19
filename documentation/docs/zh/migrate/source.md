---
title: 迁移源码
description: 用 wow-migrate Skill 把 9.x 服务的 Kotlin 源码改写为 V10：每种 9.x 构造变成什么、迁移映射记录什么，以及 Skill 无法替你完成的事。
---

# 迁移源码

9.x 服务的源码要为 V10 改写，而不是升级：注解失去它们的 9.x 属性，命令体失去身份，异常变成拒绝，处理器变成订阅。`wow-migrate` 这个 Agent Skill 与你一起完成改写，并把每一处名字和形状的变化记录在 `migration-map.json` 中，接下来由数据工具读取它（[迁移数据](./data)）。本页说明改写做了什么；Skill 的参考文档 `skills/wow-migrate/references/v9-to-v10.md` 是它遵循的完整映射。

## 开始之前 {#before-you-start}

- **先看服务群。** 在写任何代码之前，列出服务群和它的 API 依赖图，包括其中的环，并按它的顺序改写：先共享库，然后 api 模块，然后 domain，然后 server，被别人依赖的服务先于它的消费者（[切换](./cutover#find-the-estate)）。
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
| 用 DSL 类的构造器构建查询，`ListQueryDsl().apply { … }.build()` | 用入口函数 `listQuery { … }`（以及 `pagedQuery`、`singleQuery`、`cursorQuery`、`countQuery`、`aggregation`、`filter`、`projection`、`sort`、`pagination`）：DSL 类的构造器和 `build()` 是 internal 的。写在 DSL 类上的扩展函数 `fun ListQueryDsl.mine()` 仍然可用 |
| `ListQuery(limit = 0)`，在查询网关上返回全部匹配 | 不写 `limit` 取入口的默认列表大小，`0` 会被拒绝；要读全部匹配，用 `queryApi.trusted().unbounded()` 或 `stream` |
| 不写 `size` 的 `CursorQuery`，一页 `CursorQuery.DEFAULT_SIZE`（10）条 | 不写 `size` 取入口的默认列表大小（`default-list-size`，100），至多 `max-page-size`（100）；`DEFAULT_SIZE` 已删除，要保持每页 10 条就写 `size = 10` |
| 自定义的 `MaskStrategy`，`mask(value: String)` | `mask(value: SensitiveValue)`：原始字符串是 `value.value`，旁边是字段声明的 `level`；lambda 写法读 `it.value` |
| 用 `limit(Int.MAX_VALUE)` 读全部匹配 | 会被拒绝：进程内的列表至多返回 `max-list-size`（1,000）行，更大的 limit 失败（`size-out-of-range`）。必须看到全部匹配的代码，例如任务、回填或迁移步骤，用 `NodeQueryApi.trusted().unbounded()` 读取或以流读取；代表调用方的读取应改为分页 |
| 在 `limit` 处按分组键截断的聚合 | 加上 `sort` 得到前 N 名；不带排序时，分组多于 `limit` 会以 `wow.query.too-many-groups`（400）失败 |
| 返回 `Int` 的 `Pagination.offset()` | 返回 `Long` |
| `wow-test`（`AggregateSpec`） | `wow-test`，带函数式的 `aggregateSpec` 和 `sagaSpec` |
| `wow-redis`、`wow-bi`、`wow-apiclient`、`wow-compensation-*` | 已移除：没有 Redis 后端，改用 ClickHouse Sink、`CommandClient` 和 `QueryApi` 客户端、每个节点的失败台账 |
| `wow-cocache` | V10 的 `wow-cocache`：由拥有聚合的上下文为每个聚合类型写一份共享缓存，读者注入 `StateViewCache<V>`；读者自建的索引缓存与关联缓存没有对应物 |
| 返回 `Mono` 或 `Flux` 的处理函数 | `suspend` 函数，或通过 `wow-reactor` 使用 Reactor |

这些插件需要 Gradle 9 或更高版本、Kotlin JVM 插件 2.3 或更高版本：先升级 Gradle 8 的 wrapper。KSP 读取整个源码目录，忽略源码集的 `exclude`，所以每次只编译领域模块一部分的迁移，要把这些文件同步到单独的目录。这样的一部分是一个聚合连同它的闭包，而不是单独一个聚合：一个状态的类型化查询（`<State>Properties`）只在它的根被编译的地方存在。从这部分代码的 import 找出闭包：它用来查询的每个其他状态的 `<State>Properties`，以及 Saga 发出的每个命令类；把它们的聚合加进这一部分，反复进行，直到不再加入任何东西。领域模块重新整体编译、源码集指回 `src/` 之后，先做一次干净构建（`./gradlew :<module>:clean build`）：Gradle 按源码在其根下的路径比较源码，所以这次切换可能让 Kotlin 编译和 KSP 保持 up to date，而它们的增量缓存仍描述同步出来的目录，下一次改动就可能交给处理器的文件太少。在 Wow 的插件下这两种情况都不会发生：它们把源码根作为这两个任务的输入，切换后会完整重跑它们。不经 Wow 插件运行 KSP 的模块，下一次构建会以编译器的 "No Manifest fragment and no model provider" 错误失败（[构建因契约失败](../operate/troubleshooting#a-build-fails-over-contracts)），而不是在缺少模型的情况下构建通过；做一次干净构建即可修复。

先迁移 api 模块时，可以在根构建上声明 api 插件：`id("me.ahoo.wow.api") version "…" apply false`。它不带来 KSP，所以 9.x 模块保留各自的 `id("com.google.devtools.ksp") version "…"`。在根构建上声明 `me.ahoo.wow` 或 `me.ahoo.wow.service` 则会把插件的 KSP 带到每个模块的类路径上，仍写着 KSP 版本的 9.x 模块随即在 Gradle 中失败，早于任何 Wow 代码运行：

```text
Error resolving plugin [id: 'com.google.devtools.ksp', version: '2.3.6']
> The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version, so compatibility cannot be checked.
```

去掉这些模块那一行的版本，写成 `id("com.google.devtools.ksp")`，它们就使用插件的 KSP；或者像[在已有服务中引入 Wow](../start/existing-project)那样，把 Wow 插件的版本写在 `settings.gradle.kts` 中，这样任何模块的类路径上都不会出现它没有要求的 KSP。领域模块若从只声明了 api 插件的根构建应用 `me.ahoo.wow`，就找不到 KSP，构建失败并写出该模块要加的那一行：`id("com.google.devtools.ksp") version` 加插件的 KSP 版本。

[V10 中的 9.x 能力](./capabilities)说明每项 9.x 能力的去向。

## 注解 {#annotations}

包仍然是 `me.ahoo.wow.api.annotation`，所以保留下来的名字不需要新的导入。V10 编译器把被移除的注解报告为错误，并指明它的替代。

| 9.x | V10 |
| --- | --- |
| `@BoundedContext(name, alias, …, aggregates)` | `@BoundedContext(name, description)`；上下文名是每个命令、事件和路由名的一部分，所以要有意地选择 |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle, published, publishState, topic, ownership, idSource, snapshot, stateRevision, retiredEvents)`；挂载的命令是带 `CommandTransformer` 的 `@MountedCommand`，它的类放在其聚合所在的领域模块里，编译器在那里找到它 |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])`，只有返回 `Decision` 的函数才需要 |
| `@Event(revision = "0.0.1")` | `@Revision(n)`；迁移来的 9.x 形状是修订号 1 |
| `EventUpgrader` | 在从 `ObjectNode` 到 `ObjectNode` 的函数上标注 `@Upgrade(event, from)` |
| `DroppedEvent` | `@AggregateRoot(retiredEvents = ["<stored name>"])` |
| `@StatelessSaga`、`@ProjectionProcessor`、`@EventProcessor` | `@Saga`、`@Projection`、`@Reactor` |
| `@OnEvent(vararg aggregates)`、`@OnStateEvent`、`@OnMessage` | `@OnEvent`，一种接收 `StateEvent` 的处理函数 |
| `@Retry(maxRetries, minBackoff, executionTimeout)` | `@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)` 用于进程内的预算；9.x 的值变成停放条目的重试，`wow.subscriptions.[<context>.<processor.function>].parked.*`（`executionTimeout` 对应 `claim-ttl`） |
| `@OnError` | 已移除：返回拒绝，带上要记录的事件（`reject(code, message, events)`），每个事件都在该函数的 `@OnCommand(produces = […])` 中声明（[拒绝](../guide/rejections#reject-with-events)），在 `@Saga` 的 `@OnRejected` 处理函数中补偿。把框架的重复 ID 错误改写成领域消息的处理函数，在 V10 中没有对应形式：创建已存在的聚合会在决策之前就得到 `wow.aggregate.exists`，客户端改为匹配这个错误码 |
| `@PreparableKey` | `DecideScope.reserve(namespace, key, tenancy)` |
| 命令属性上的 `@AggregateId` | 保留，含义不变：命令体指明聚合，命令的路由不带 `{id}`。9.x 从路径绑定的字段只是重复了路由：删除它（[命令体承载什么](#what-a-command-body-holds)） |
| 命令字段上的 `@TenantId`、`@OwnerId`、`@AggregateVersion` | 已移除：租户和所有者是调用方的，期望版本是 `If-Match` |
| `@StaticTenantId` | 宿主配置：`wow.aggregates.[<context>.<aggregate>].tenant` |
| `@StaticAggregateId` | 已移除：没有固定的聚合 ID（[V10 中的 9.x 能力](./capabilities)） |
| `@AggregateRoute`、`@CommandRoute` | 已移除：路由来自 Manifest |
| `@CommandRoute(enabled = false)` | 取决于谁发送这条命令。只由平台发送（Saga、定时器、进程内网关，以及经内部端口调用的其他服务）：在命令类上标注 `@InternalCommand`，公开端口不挂它的路由（[只在内部受理的命令](../guide/aggregates#keep-a-command-internal)）。由 9.x 服务手写的用户路由发送：命令保持公开，走生成的路由（[路由、租户与所有权](#routes-tenancy-and-ownership)） |
| `@AggregateOwner(OwnerPolicy.*)`、`@Spaced` | `@AggregateRoot(ownership = Ownership.OWNER \| TENANT)`；所有者和空间来自调用方。`OwnerPolicy.AGGREGATE_ID` 是 `@AggregateRoot(idSource = AggregateIdSource.OWNER)` |
| `@VoidCommand`、`@Order`、`@EntityObject`、`@ValueObject` | 已移除 |
| 无 | 拒绝码上的 `@ErrorCode`、用于之后重命名的 `@Alias`、`@QueryIndex`、`@Access` |

## 命令体承载什么 {#what-a-command-body-holds}

命令体保存业务数据。租户是调用方的，所有者和空间是 `command.scope`，取自已认证的调用方，期望版本来自 `If-Match`：删除租户、所有者和版本字段以及路由注解。聚合是 `command.ref`，取自路由，除非命令体指明了它。

9.x 从路径绑定的 ID 字段（`@CommandRoute.PathVariable` 加 `@AggregateId`）只是重复了路由：删除它，读取 `command.ref.id`，命令保留带 `{id}` 的路由。eShop 的订单由一个只携带订单项的命令创建，ID 来自路由（没有字段剩下的命令变成 `object`）：

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

9.x 由命令体自己的业务数据得出的 ID（`productId` 这样的字段，或 `"$warehouseId-$code"` 这样的 getter）保留 `@AggregateId`，名称、包和含义都不变（[在命令体中指明聚合](../guide/aggregates#name-the-aggregate-in-the-body)）。命令的路由于是不带 `{id}`，Saga 或客户端发送它时都不带 ID，也不需要 `CommandTransformer`。计算出的 ID 仍是一个 getter，由服务从字段算出：

<!-- sample: fragment -->

```kotlin
data class AdjustStock(val warehouseId: String, val code: String, val delta: Int) {
    @AggregateId
    val stockId: String get() = "$warehouseId-$code"
}
```

原先把这类命令发往带 ID 路由的客户端，改为发往不带 ID 的路由（[HTTP 与 TypeScript 客户端](./http-and-clients#routes)）。

9.x 命令没有 `@AggregateId`，但它的 `id`（常见的是 9.x 的 `Identifier.id`）指明了聚合时，9.x 按这个 id 路由命令。V10 不按属性名推断任何东西，所以要给这个 `id` 标注 `@AggregateId`；编译器的 `UnmarkedAggregateId` 会列出每个有这种 `id` 却没有标注的命令，无论命令在哪里编译，包括上下文的 api 模块：对创建聚合的命令是错误，对其他命令是警告（[在命令体中指明聚合](../guide/aggregates#name-the-aggregate-in-the-body)）。如果这个 id 是接口的默认 getter，而命令没法在接口上标注，比如接口来自外部服务的 jar，或者别的聚合的命令也实现了它，就在命令里覆写它：

<!-- sample: fragment -->

```kotlin
data class CreateShelf(override val warehouseId: String, override val code: String) : ShelfIdSpec {
    @AggregateId
    override val id: String
        get() = super<ShelfIdSpec>.id
}
```

不标注的话，不带 id 发出的创建命令，无论是 Saga 的 `CommandRequest.of(body)` 还是客户端发的，都会得到一个生成的 id：指向的不是 9.x 那个聚合，用同一业务键第二次创建也会被接受，而不是回答 `wow.aggregate.exists`。

在 decide 中，`CommandMessage<X>` 是 `Command<X>`，`command.aggregateId.id` 是 `command.ref.id`，`command.ownerId` 是 `command.scope.ownerId`，9.x 的 `CommandResultAccessor` 是 `DecideScope.putResult`，在 decide 中生成的 ID 来自 `scope.ids.next()`。

decide 中的查询读的是查询侧，它的快照在每次提交之后写入，而不是随提交一起写入，9.x 也是这样。同一流程片刻之前经 Saga 做的写入可能还看不到：因为某行不存在而拒绝的 decide 函数，可能拒绝得太早。让调用方重试这种拒绝，或者由 Saga 在它的命令里带上检查所需的事实。

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
- **状态。** `onSourcing` 函数不是私有的，命令产生的每个事件都有一个，哪怕是空的；每个属性在创建事件应用之后都有值：由该事件的 `onSourcing` 赋值的 `lateinit` 没有问题。9.x 在私有时也能找到的处理函数（`@OnCommand`、`@OnSourcing` 或 `@OnEvent` 函数），在 V10 中是编译错误，Kotlin 的 `protected` 处理函数也一样：把它改成 `internal`。只有仅靠名字绑定、没有注解的私有函数才会被忽略。9.x 的 `StateAggregateTagsExtractor`，以及 9.x 打上的标签，变成状态的访问属性（[标签与基于属性的访问](#tags-and-attribute-based-access)）。
- **没有 setter 的属性。** 9.x 的状态、事件或命令中，用 `val` 持有并边执行边填充的集合或映射（`val items = sortedSetOf<…>()`、`MutableList` 或 `MutableMap` 的 `val`），或类体中 `val` 的值，会像 9.x 那样读回：Wow 的 JSON 会写入这种属性的字段（`ALLOW_FINAL_FIELDS_AS_MUTATORS`，9.x 的 JSON 同样开启，Jackson 3 默认关闭）。计算属性（背后没有字段的 getter）只写出，不读回。
- **带偏移的日期时间。** Wow 的 JSON 读出的 `OffsetDateTime` 或 `ZonedDateTime`（从日志或快照重建的状态、事件、查询结果）保留写入时的偏移：`"2026-01-02T03:04:05+08:00"` 读作 `+08:00`。9.x 把它读为 UTC，即同一瞬间的 `2026-01-01T19:04:05Z`。用 `==` 把这样的值与另一偏移的值比较的代码，现在会看到两者不等：比较瞬间请用 `isEqual` 或 `toInstant()`。`ZonedDateTime` 写出的是偏移而非时区名，因此读回为固定偏移。
- **用时钟打时间戳的演化函数。** 9.x 中从时钟取时间（`System.currentTimeMillis()`、`LocalDateTime.now()`）的 `onSourcing`，改为取事件的时间：事件的字段，或其提交的 `commit.commitTime`。读取时钟的演化函数在规格中每次运行都会失败（[测试](../guide/testing#expect)）。
- **被删除的事件**要退役：删除类和它的溯源函数，在 `retiredEvents` 中列出存储时的名字，并用 `"retire": true` 映射它。
- **事件与状态中的 Wow 类型。** 存储下来的 `Condition`、`Sort` 或 `Projection`（例如保存的过滤条件）在 V10 中没有对应形式：`Condition` 协议已经没有了，内核的 JSON 没有查询协议的编解码，查询协议的枚举现在也是小写。改存应用自己的类型，用一个 `@Upgrade` 函数把 9.x 的 JSON 升级过去，并写测试覆盖。仍持有查询协议类型（`me.ahoo.wow.api.query`）的事件或状态会被构建拒绝，并给出每一处的路径。
- **事件的信封。** 接收 `DomainEvent<E>` 以读取 `createTime` 或操作人的 9.x 溯源函数，改为接收事件，并以第二个参数接收它所属的提交（`commit: EventStream`）：`createTime` 是 `commit.commitTime`，`header.operator` 是 `commit.operator?.id`，`version` 是 `commit.version`（[读取提交](../guide/aggregates#read-the-commit)）。数据迁移把每条 9.x 流的创建时间和操作人导入为其提交的提交时间和操作人，所以从 9.x 历史重建的状态读到的就是 9.x 记录的值。读取系统时钟、或接收 `Clock` 来读取时间的命令函数，改读 `scope.now`：决策时间，单位为毫秒（`LocalDateTime.ofInstant(Instant.ofEpochMilli(scope.now), zone)`）。参数默认取 `LocalDate.now()` 或 `LocalDateTime.now()` 的共享辅助函数仍会读取系统时钟，要把决策日期传给它。测试用 `aggregateSpec(…, start = instant)` 和 `advanceTime` 设定时间，而不是注入时钟。

## 处理器 {#processors}

Saga、投影或反应器恰好带有 `@Saga`、`@Projection`、`@Reactor` 之一；每个处理函数都是一个独立的订阅，名为 `processor.function`。处理函数接收提交的 `StateEvent`（或其中的一个事件，`StateTypedEvent<S, E>`），Saga 以 `CommandRequest` 的形式返回它推导出的命令，运行时把它们转成 ID 确定的命令：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>
): CommandRequest<AwaitStockValidation> = CommandRequest.of(AwaitStockValidation, event.ref.id)
```

处理过 9.x `onError` 的 Saga，要为被拒绝的推导命令加上 `@OnRejected` 处理函数。没有处理它的 Saga（9.x 只记录派生命令的失败并继续）有两种移植方式，按命令逐一决定。返回 `Unit`、接受这次拒绝的 `@OnRejected` 处理函数，保留 9.x 的行为。不写处理函数是 V10 的默认：发往 Saga 自身上下文的命令被拒绝，会停放 Saga 的源聚合，直到它被恢复（[处理拒绝](../guide/sagas#handle-a-rejection)）。读取 9.x 只读状态聚合标签的 Saga，改读 `event.state` 的访问属性，即提交之后的状态，而不是快照里的。处理其他上下文事件的处理器，通过那个上下文的 `*-api` jar 读取它发布的事件流（[发布与消费事件流](../guide/published-streams)）。

9.x 服务在 Simba 或 `@Scheduled` 里运行的周期作业，如果是从到期的内容推导命令，就是 Saga 的一个 `@OnSchedule` 处理函数，按 cron 规则运行；要等每条命令的结果再发下一条的作业，留在 Wow 之外，通过 `CommandClient` 发送（[定时命令](../guide/scheduling)）。9.x 把已存储事件重新投递给某个处理函数的回填，在 V10 中是一个新名字的处理函数，带 `@OnEvent(start = OnEvent.Start.BEGINNING)`，从事件流的开头读一遍（[重建与重放](../operate/replay)）。

### 定时作业 {#scheduled-jobs}

每个移植到 `@OnSchedule` 的 9.x 作业都会带来四处不同，其中三处没有任何提示：

- **时区。** 9.x 调度器按服务器的时区读取 cron。V10 作业按 UTC 读取，除非它指明时区：`@OnSchedule(timeZone = "Asia/Shanghai")` 或 `wow.schedules.<name>.time-zone`，从不使用服务器的时区。原样移植的每日作业会在 UTC 的同一钟点运行，偏移了时区的差值，而且没有任何提示。
- **每个租户。** 在租户之间循环的 9.x 作业没有一行就能完成的移植。`SYSTEM` 作业（默认）只运行一次，在默认租户里：`context.queries` 读取那个租户，`context.queries.trusted()` 读取每个租户，发往其他租户的命令用 `targetTenant` 指明租户。`TENANT` 作业在每个租户里各运行一次，但只在启用了它的租户里：在那个租户里发送 `wow.schedule.EnableJob`（`POST /<context>/wow.schedule/<job>/enable-job`）。宿主不会为任何租户启用实例，所以移植作业时要为每个已有租户启用它，新租户则在创建时启用。
- **没有立即运行。** 作业无法手动运行：它的实例只接受启用、重新调度和停用，除了它的规则，没有什么能触发它的一次运行。9.x 中按需运行作业的端点，仍是服务自己的代码，发送作业本会发送的命令。
- **来自配置的频率。** V10 作业只按 cron 规则运行，没有固定频率或固定延迟。9.x 中放在配置属性里的频率，变成作业在 `wow.schedules.<name>.cron` 下的 cron，例如每五分钟一次是 `0 */5 * * * *`。

发出一条命令、等它处理完再发下一条的 9.x 调度器，没有作业形态。作业推导它的命令，不等待任何一条：指明等待或超时的推导请求会让处理函数永久失败，作业的实例随之停放。受支持的形态：

- **发往其他上下文的命令：** 作业逐条调用，`context.call(request)` 在那个上下文提交之后返回，并在 `@Sends(calls = [...])` 中声明。调用作业自身的上下文会让启动被拒绝。
- **发往自身上下文的命令，或要等到提交之后：** 调度器留在服务之外，在一个进程里用 `CommandClient` 的 `wait` 发送每条命令，拿到结果后再发下一条。只在一处运行它：Wow 不为这样的调度器提供锁，放在服务节点里的会在每个节点上运行。


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

标了 `@CommandRoute(enabled = false)` 的命令在 9.x 中没有生成的路由，原因有两种，移植前先弄清是哪一种。如果只有服务自己的流程或其他服务发送它，标注 `@InternalCommand`。如果是 9.x 服务手写的路由应用户的请求发送它，它就是用户的命令，保持公开：生成的路由取代手写的路由。手写路由算出的东西由 `CommandTransformer` 来做，例如由调用方和命令体推导出的 ID；调用方可以操作的另一个范围由 `Wow-Scope` 请求头指明。9.x 视图存储的 `SetViewPreferences`、`SetDatasetWording`、`ClearDatasetWording` 和 `ClaimView` 属于第二种。它们由手写路由发送，所以在 10 中保持公开。前三条走生成的路由，由转换器推导 ID；`ClaimView` 带上指明共享所有者的 `Wow-Scope` 发送。

## 标签与基于属性的访问 {#tags-and-attribute-based-access}

V10 没有标签。聚合的标签原先说明谁能读取它，现在这些变成它的**访问属性**，即状态中标注了 [`@Access`](../reference/annotations#access) 的成员；服务的**访问策略**把它们与调用方的声明比较（[归属与访问](../guide/ownership#declare-access-attributes)）。

- **状态推出的标签。** 9.x 的 `StateAggregateTagsExtractor` 变成每个键一个 `@Access` 成员：状态的一个属性，或者一个不带参数的函数，类型就是键的类型（`String`、数字、`Boolean` 或枚举；一个键有多个值时，是它们的 `Set`）。V10 在每次提交时推出每一个，就像 9.x 推出标签那样。
- **命令打上的标签。** 9.x 的 `ApplyResourceTags` 设置的东西是数据：状态中标注了 `@Access` 的字段，由聚合自己的一条领域命令和一个领域事件设置（`TagOrder`、`OrderTagged`），这样由领域检查谁能修改它，日志也记下它。事件的载荷以访问属性的名字指明每个字段，它的溯源函数替换全部这些字段，载荷里没有的字段替换为无值，就像 9.x 替换整个标签映射那样。9.x 的路由 `PUT …/{id}/tags` 现在就是这条命令的路由。
- **既被打上、又被推出的键**，在 9.x 中取每次提交之后提取器返回的值，由它说了算：它是一个 `@Access` 函数，标签事件设置的值被略去。
- **没有策略读取的键**被略去，原因写在映射文件里：略去它会改变谁能读取数据。
- **ABAC 规则。** 9.x 的 `abac.*` 匹配变成聚合的访问策略，即其状态的 `AccessPolicy` Bean。规则匹配的每个标签键，是它的属性与调用方同名声明的一次比较，这些键要全部满足（`allOf`）：

<!-- sample: fragment -->

```kotlin
@Bean
fun orderAccess(): AccessPolicy<OrderState> = accessPolicy {
    read { caller ->
        allOf(
            OrderStateAccess.REGION isIn caller.claims("region"),
            OrderStateAccess.TEAMS containsAny caller.claims("teams"),
        )
    }
    write { caller -> ownerAndSpace(caller) }
}
```

声明是调用方同名的主体属性，前缀也算在名字里：9.x 当作 `abac.region` 匹配的属性就是声明 `abac.region`，除非身份提供方把它命名为 `region`，就像这个示例所假定的那样。策略为它的聚合取代所有者和空间的检查，读取和命令都一样，所以在 9.x 依赖这项检查的地方要用 `ownerAndSpace(caller)` 重新写出它，就像这里的 `write` 所做的那样（[编写访问策略](../guide/ownership#write-an-access-policy)）。9.x 默认允许的三种情况，在 V10 中被拒绝：

- 没有 `abac.*` 属性的调用方能读取每个聚合。与调用方没有的声明作比较，结果是拒绝：给这样的调用方单独一条规则（`allowIf(caller.hasRole("auditor"))`）。
- 没有某个键的聚合，对这个键的规则是公开的。没有该属性值的聚合不匹配任何比较：在策略里写明什么是公开的（在 `anyOf` 中写 `OrderStateAccess.REGION.isNull()`，或者 `allowAll()`）。
- 9.x 开始记录标签之前写入的流，每个调用方都能读取。尚未盖上访问属性的流，在访问策略下对任何调用方都不显示；每条导入的流在访问遍历完成之前都是这样（[迁移数据](./data#_7-the-access-attributes)）。

迁移映射以 `"tags": true` 把 9.x 的标签事件映射到这个领域事件，并把每个标签键映射到它的属性（[映射文件](../reference/migrate-data#tags)）。

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
| `inject { register(service) }` | 生成的模型工厂接收的服务（`orderModel(name, inventory)`，第一个参数是带默认值的上下文名，只传服务时按工厂的参数名传：每个参数按其服务的类型命名，`InventoryService` 对应 `inventoryService`，而不是 9.x 注册时的名字或命令函数的参数名；同一类型的两个服务用 `@DependencyName` 区分（[测试领域代码](../guide/testing#arrange-and-act)））；某个分支需要服务给出不同回答时，用 `fork(name, model = …)` |
| `inject { register(clock) }`，命令函数接收的 `Clock` | `aggregateSpec(model, start = instant)`：`DecideScope.now` 读取 spec 的时钟，它从 `start` 开始，`advanceTime(duration)` 推进它 |
| `name("…")` | 测试的名字：`@Test` 函数的名字；在 `aggregateSpecTests` 中，是每个步骤和分叉的名字 |
| `givenOwnerId(…)`、`givenSpaceId(…)` | `given`、`givenState` 或 `whenCommand` 上的 `scope = Scope(owner, space)` |
| `givenEvent(event) { … }`、`givenEvent(arrayOf(…)) { … }` | `given(vararg events)`，一个独立的步骤；`givenState(state, version)` 不变，`givenState(stateAggregate)` 是 `givenState(state, version, scope)` |
| `whenCommand(command, header, ownerId, spaceId)` | `whenCommand(command, requestId, expectedVersion, scope, headers, operator) { … }` |
| 同一 given 上并列的 `whenCommand` | `fork(…)` 分支（见上文） |
| 已验证阶段上的 `ref("name")`、`fork("name") { … }` | spec 中的 `ref("name")`、`fork(name, from = "name") { … }`；或者在检查中使用 `fork { … }` |
| `.verify()`、`.verify(false)` | 不需要调用：检查在它们的块结束时运行，第一个失败的检查会结束这个块。9.x 收集全部失败的做法没有对应形式；在 `aggregateSpecTests` 中，每个步骤让自己的测试失败，其余照常运行 |
| 已验证阶段的 `stateRoot`、`stateAggregate`、`verifiedResult` | 状态用 `expectState { … }`；聚合用 `expectVersion`、`expectScope`、`expectDeleted`；结果用检查中的 `error` 和 `results` |
| 经由 `TestValidator` 的命令校验 | 已去掉：校验是准入层的 `CommandValidator`，内核和 spec 都不经过它；在 HTTP 层测试它，或在领域测试中，用加入测试并按 9.x 方式配置的 Bean Validation 实现校验命令（[命令的约束](../guide/testing#command-constraints)） |
| `TestGlobalIdGeneratorFactory` | 不需要设置：在 spec 中，`DecideScope.ids` 给出顺序的 ID |

### 聚合的期望 {#aggregate-expectations}

| 9.x | V10 |
| --- | --- |
| `expectNoError()` | `expectNoError()` |
| 基于 9.x `ExpectedResult` 的 `expect { … }` | 本表中的各个期望；它们没有覆盖的，用 `error` 和 `results` |
| `expectError()`、`expectError { … }`、`expectErrorType(X::class)` | `expectError<X> { … }`，用于 decide 函数抛出的异常；`expectRejected(code)` 用于它返回或抛出的拒绝（`reject`、`require`、`check`） |
| `expectEventType(A::class, B::class)` | `expectEventTypes(A::class, B::class)` |
| `expectEventCount(n)` | `expectEventCount(n)` |
| `expectEvent<E> { … }`、`expectEventBody<E> { … }`：该类型的第一个事件 | `expectEvent<E> { … }`：恰好一个事件，且是 `E`；多个事件按值用 `expectEvents(…)` |
| `expectEventStream { … }`、`expectEventIterator { … }` | 整个流用 `expectEvents(…)` 或 `expectEventTypes(…)`；单个事件连同信封用 `expectEventEnvelope<E> { … }` |
| 事件的 `aggregateId`、`version`、`commandId`、`requestId`、`header` | `expectEventEnvelope<E> { aggregateId; version; commandId; requestId; headers }` |
| 事件的操作者和创建时间 | `expectEventEnvelope<E> { operator; commitTime }`，用于提交了一个事件的命令 |
| `expectState { … }`、`expectState(Consumer)` | `expectState { … }` 或 `expectState(expected)` |
| 所有 `Consumer` 与 `Class` 重载 | 已去掉：测试 DSL 只支持 Kotlin，Java 领域代码也用它测试（[用 Java 编写领域](../guide/java#test-it)）。在每个 lambda 旁加一个 `Consumer` 重载，会让 Kotlin 的调用产生歧义 |
| `expectStateAggregate { aggregateId }` | spec 的 `id` |
| `expectStateAggregate { version }` | `expectVersion(n)`：提交后的版本，或命令什么都没追加时聚合停留的版本 |
| `expectStateAggregate { deleted }` | `expectDeleted()` |
| `expectStateAggregate { ownerId; spaceId }`、标签 | `expectScope(scope)`、`expectAccess(attributes)`：访问属性，按名字给出 |

较小的差异：`expectEvent` 和 saga 的 `expectCommand` 现在要求恰好一个，而 9.x 取第一个匹配项；什么都没提交的命令用 `expectUnchanged()`。

### Saga spec {#saga-specs}

| 9.x | V10 |
| --- | --- |
| `class XSpec : SagaSpec<T>({ on { … } })`、`sagaVerifier<T>()` | `sagaSpec(<saga>Handlers(…)) { … }`，测试树用 `sagaSpecTests` |
| `inject { … }` | 处理器的依赖，交给生成的处理器工厂；读取用 `givenQueries(queries)` |
| 命令网关和消息工厂 | 已去掉：`answerCalls { … }` 回答同步调用 |
| `functionFilter { … }`、`functionName("onX")` | `whenEvent(event, handler = "onX")` |
| `whenEvent(event)`、`whenEvent(event, state, ownerId, spaceId)` | Saga 自己上下文的聚合：先 `givenAggregate(model, id, history…)`，再 `whenEvent(events, by = …)`；本模块没有模型溯源的状态：`whenEvent(stateEvent(ref, state, events…, scope = …))`；对端上下文的事件：`publishedEvent(name, body)` |
| `expectCommandType(X::class)` | `expectCommandTypes(X::class)` |
| `expectCommandCount(n)` | `expectCommandCount(n)` |
| `expectCommandBody<C> { … }`：第一条命令的命令体 | `expectCommand<C> { … }` 或 `expectCommand(body, to = ref)`：恰好一条命令，检查它的命令体；多条用 `expectCommands(…)` |
| `expectCommand<C> { … }`：第一条命令的消息，它的聚合 ID 与请求头 | `expectCommandEnvelope<C> { … }`：恰好一条命令，在 `body` 旁给出它的 `ref`、各个 ID、作用域、请求事实和操作人 |
| `expectCommandStream`、`expectCommandIterator` | `commands`、`expectCommands(…)`、`expectCommandTypes(…)` |
| `expectNoCommand()`、`expectNoError()`、`expectError…` | 名称不变；`expectError<X>()` 与聚合相同 |
| `expect { … }`、`.verify()` | 本表中的各个期望；其余用 `commands`、`calls`、`handled` 和 `error`。步骤的检查块是可选的 |

V10 新增、9.x 没有对应的：

- 用于 saga 处理回执的 `whenRejected` 和 `whenCommitted`，以及用于同步调用的 `answerCalls` 和 `expectCalls`；
- `whenRedelivered` 和 `whenDeliveredLate`，像订阅可能做的那样，把一次提交再交一次或迟交；
- `expectRetried` 和 `expectParked`，说明运行时如何处理失败的处理；
- 只用于 Saga 的 `expectDeterministic`，以及 `expectHandledBy`；
- 处理器的分叉和测试树，9.x 的 `SagaSpec` 两者都没有；
- 基于内存快照的 `givenQueries`（`wow-test-query` 的 `givenSnapshots`）、定时相关的期望，以及 `projectionSpec`、`reactorSpec` 和 `scheduleSpec`，每个都有对应的 `Tests` 孪生函数。

[测试领域代码](../guide/testing)逐一说明。

## Skill 无法完成的事 {#what-the-skill-cannot-do}

有些 9.x 构造没有 V10 的形式。Skill 会报告服务用到的每一个，以及所做的决定；它从不模拟其中任何一个：

- 自定义路由、方法以及路径或请求头变量：客户端和网关规则要随之改变；
- `bindingErrors`、`@VoidCommand`、`@Order`、swagger 注解；
- 9.x 的 Redis 事件存储：数据工具只读取 MongoDB；
- 基于 `AggregateVerifier` 的 Java 测试，以及对 `suspend` API（`DecideScope.reserve`、`SagaContext.call`）的 Java 调用：只支持 Kotlin；
- 在类路径上发现的 Jackson 模块，以及没有公共 getter 的字段：`WowJson` 两者都不应用；
- 宿主的 Jackson 设置：Wow 的 JSON 是它自己的映射器，所以 `spring.jackson.*`、Jackson 定制器 bean，以及 `ObjectMapper` 或 `JsonMapper` bean，都影响不到 Wow 读写的任何内容，包括命令体。通过 Spring 配置不区分大小写地读取枚举值的 9.x 服务，会拒绝把 `"paid"` 读作 `PAID`；应当接受任意大小写的属性要自己声明，`@field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES])`（标在枚举类上不起作用）；
- 发送了 JSON 种类与属性不同的值的客户端，比如数字写成 `"7"`、`String` 写成 `1`、整数写成 `1.5`：9.x 的 JSON 会强制转换它，Wow 的 JSON 以 `wow.command.malformed` 拒绝它，并指出属性（[JSON 中的值](../reference/json-values)）。客户端发送 Manifest 的 schema 给出的形式；
- 状态事件重发和按版本重新分发：10.x 之前都没有；改为重试停放的条目；
- 在运行时注册的 `MessageFunction`：把它改写为带注解的处理器。

下一步：[配置](./configuration)、[HTTP 与 TypeScript 客户端](./http-and-clients)，然后是[数据](./data)和[切换](./cutover)。
