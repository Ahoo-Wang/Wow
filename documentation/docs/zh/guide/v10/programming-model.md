---
title: V10 编程模型
description: Wow V10 的聚合、命令、事件与拒绝；Saga、投影与反应器；定时命令与其他上下文。
outline: deep
---

# V10 编程模型

V10 的领域模块是普通的 Kotlin（或 Java）代码，加上 `me.ahoo.wow.api.annotation` 的注解。编译器在构建期把它编译成内核模型：运行时不扫描、不反射。每个注解沿用 9.x 同名注解的包名，但注解集是重新设计的、更小的：V10 模型上出现 9.x 独有的注解会编译报错，并指向迁移 Skill（`wow-migrate`）。

## 五类函数

| 函数 | 形态 | 可否 I/O | 失败时 |
| --- | --- | --- | --- |
| Decide | 聚合根的命令函数：命令（或 `Command<X>`），其后是服务与 `DecideScope` | 可以，经由它的服务 | 命令被拒绝或失败；不写入 |
| Evolve | 状态的溯源函数：一个事件 | 不可以 | 模型缺陷：立即失败 |
| React | `@Reactor` 的处理函数：一个 `StateEvent`，可选 `ReactContext` | 可以，自行幂等 | 重试，之后为该处理函数停放这个聚合 |
| Saga | `@Saga` 的处理函数：一个 `StateEvent`，可选 `SagaContext`；返回命令 | 可以 | 重试，之后停放 |
| Project | `@Projection` 的处理函数：一个 `StateEvent`，可选 `ProjectContext` | 可以，按版本守卫写入 | 重试，之后停放 |

Decide、React、Saga、Project 函数可以是同步函数、`suspend` 函数，或返回 Reactor 的 `Mono`、`Flux`、`Publisher`；编译器按签名选择调用方式。同步函数被视为纯计算、内联执行；做阻塞调用（例如 JDBC 写入）的同步函数要标 `@Blocking`，它会被派发到阻塞调度器，运行在虚拟线程上。返回 Reactor 类型的函数需要类路径上有 `me.ahoo.wow:wow-reactor`，否则模块编译失败。

## 聚合

聚合由标注 `@AggregateRoot` 的根类与它的状态组成。根由状态构造（`class Order(private val state: OrderState)`），或者根本身就是状态。

```kotlin
@AggregateRoot
class Order(private val state: OrderState) {
    @OnCommand(produces = [OrderCreated::class])
    suspend fun onCommand(
        command: Command<CreateOrder>,
        pricingService: PricingService,
        scope: DecideScope,
    ): Decision { /* ... */ }

    fun onCommand(command: PayOrder): List<OrderPayment> { /* ... */ }
}
```

### 命令函数

编译器找出每个标注 `@OnCommand` 或名为 `onCommand` 的非 private 函数。

- **第一个参数**是命令体 `X`；需要准入得到的信息时取被准入的 `Command<X>`：`command.ref`（聚合的上下文、名字、租户与 ID）、`command.scope`（调用方的所有者与空间）、`command.headers`（宿主保留的请求事实，`wow.http.request-facts`）与 `command.causation`。
- **其后的参数**是领域服务或 `DecideScope`。服务成为生成的模型工厂的参数，宿主在构建模型时绑定一次：取应用中该类型唯一的 Bean，或其中的 primary Bean。参数上的 `@Name("…")` 是限定符：宿主取该名字的 Bean，所以同一类型的两个参数可以取两个 Bean。
- **返回值**就是决定：

| 返回 | 含义 |
| --- | --- |
| `Decision.events(e1, e2, …)` | 这些事件，作为一条事件流一起追加 |
| `Decision.NoOp` | 接受，但不改变任何东西，不写入 |
| `Decision.reject(code, message)` | 拒绝：不写入，调用方收到错误码 |
| 一个事件、事件的列表或数组 | 这些事件 |
| `Unit`、`null`、空列表 | 不改变任何东西 |

返回 `Decision`（或 `Any`、`Mono<Decision>`）的函数无法让编译器知道它会产生哪些事件：用 `@OnCommand(produces = [...])` 列出，Manifest 才知道。返回类型已经说明事件的函数（一个事件类型、一个 sealed 类型、它们的列表）不能再次声明。

### 创建聚合

| 命令类 | 何时接受 |
| --- | --- |
| `@CreateAggregate` | 聚合尚不存在；否则 `wow.aggregate.exists` |
| `@AllowCreate` | 聚合存在与否都接受 |
| 都没有 | 聚合已存在；否则 `wow.aggregate.not-found` |

创建类命令（`@CreateAggregate` 或 `@AllowCreate`）有两条路由：带 `{id}` 的由客户端指定 ID，不带的由服务端分配 ID。不带 ID 时，准入先找该命令的请求 ID 已经创建的聚合，用它的 ID；否则由聚合类型的生成器取新号。Saga 的创建类命令也可以这样把 ID 留给目标：`context.command(type, body)`。已提交的 ID 永不改变，所以用作单据号的 ID（例如 `SO-20261013-001`）始终是给客户的那一个。ID 不保证连号：严格连号要由专门发号的聚合来发。

命令不带身份字段（命令体里没有聚合 ID、租户、所有者或版本）：ID 来自路由或发送命令的 Saga，租户、所有者与空间来自调用方，期望版本来自 `If-Match`。

### `DecideScope` 提供什么

| 成员 | 作用 |
| --- | --- |
| `command` | 正在决定的命令，带准入得到的事实 |
| `putResult(key, value)` | 在事件追加后给调用方的回答里加一个值；不持久化，所以重复请求不会再得到它 |
| `reserve(namespace, key)` | 为本聚合预留一个唯一键（例如用户名）；被其他聚合占用时返回 `false`。事件确定未追加时释放 |
| `onAppendFailure { … }` | 事件确定未追加时撤销一次外部动作 |
| `afterAppend { … }` | 事件追加后执行；失败只记日志，不抛出 |
| `schedule(key, at / after, command, target)` | 定时发送一条命令，默认发给本聚合（见[定时命令](#定时命令)） |
| `cancelSchedule(key)` | 取消 `key` 下的定时 |

### after 钩子

标注 `@AfterCommand(include = [...], exclude = [...])` 或名为 `afterCommand` 的函数，在它适用的命令函数（`include` 为空时是全部）之后执行。它看到的状态与命令函数相同，返回事件，这些事件接在命令自己的事件之后，组成同一条事件流。它不能拒绝；它失败时整条命令失败，不写入。

### 溯源函数

状态类持有溯源函数，标注 `@OnSourcing` 或名为 `onSourcing`，每个事件一个。可变状态是默认写法：函数原地修改状态，不返回值。返回状态类型的函数是不可变写法：它返回新的状态。

```kotlin
class CartState(val id: String) {
    var items: List<CartItem> = emptyList()
        private set

    fun onSourcing(event: CartItemAdded) {
        items = items + event.added
    }
}
```

- 模型要演化它的命令产生的每一个事件，所以每个事件都需要溯源函数，哪怕它什么也不改（`fun onSourcing(event: OrderShipped) = Unit`）。
- 溯源函数不接收服务，不做 I/O。
- 第一个事件之前，每个属性都要有值：内核按原样序列化状态，用于快照与测试 DSL 的纯度检查。
- 命令函数与 after 钩子绝不修改状态。测试 DSL 在每次决定前后比较状态，状态被改了就让测试失败。

### 拒绝与错误码

拒绝码是一个 `String` 类型、标注 `@ErrorCode` 的 `const val`，放在领域模块里、用它拒绝的函数旁边。写它的本地名，放在嵌套于 `@AggregateRoot` 类中的 `object` 里（决策 D259）：

```kotlin
@AggregateRoot
class Order(private val state: OrderState) {
    // … command functions: Decision.reject(Rejections.WRONG_STATUS, "The order is ${state.status}.")

    object Rejections {
        @ErrorCode(description = "The order has no item.")
        const val NO_ITEMS = "no-items"

        @ErrorCode(description = "The order's status does not allow the command.", status = 409)
        const val WRONG_STATUS = "wrong-status"
    }
}
```

编译器给本地名补上上下文与聚合，成为 `example.order.wrong-status`；内核也以这个全名给出拒绝，所以结果、回执、HTTP 应答与 Manifest 里只出现全名，测试 DSL 的 `expectRejected` 两种写法都接受。api 模块生成的契约为对端提供全名常量：`ExampleContract.Order.Errors.WRONG_STATUS`。含点号的错误码是全名、原样使用，例如上下文内各聚合共用的 `example.closed`。编译器把模块的错误码列入 Manifest 中该上下文的 `errors`，带上 HTTP 状态码（400 至 599，默认 422）与说明。错误码不能以框架的 `wow.` 开头，一个模块里每个错误码只声明一次。内核自己的错误码有 `wow.command.unhandled`、`wow.aggregate.not-found`、`wow.aggregate.exists`、`wow.aggregate.version-mismatch`、`wow.command.request-id-reused`、`wow.aggregate.deleted` 与 `wow.aggregate.forbidden`（`KernelRejections`）。

### 生命周期命令

每个聚合都处理 `me.ahoo.wow.api.lifecycle` 的五个系统命令，除非 `@AggregateRoot(lifecycle = [...])` 缩小了范围（空列表表示一个都不启用）：`DeleteAggregate`、`RestoreAggregate`、`TransferOwner`、`TransferSpace` 与 `ApplyTags`。它们作用于快照的元数据；已删除的聚合拒绝除 `RestoreAggregate` 之外的所有命令。它们的路由见 [HTTP API](./http-api.md#生命周期路由)。

### 所有者、空间与租户

聚合的作用域是它的所有者与空间。第一条命令的调用方给出作用域；此后，调用方的所有者或空间与聚合不同的命令会被内核拒绝（`wow.aggregate.forbidden`）。调用方没有值的那一部分不检查：不带所有者的租户级调用方可以触达本租户的全部聚合（[两级调用方](./http-api.md#两级调用方)）。

`@AggregateRoot(ownership = ...)` 说明聚合除租户外还属于谁：

| `Ownership` | 聚合 | 例如 |
| --- | --- | --- |
| `OWNER`，默认 | 属于创建它的命令的所有者（命令有所有者时） | 订单、购物车 |
| `TENANT` | 只属于它的租户：不记所有者，读写都不按调用方的所有者限制。空间照常适用，且它不处理 `TransferOwner` | 每个顾客都要读的商品 |

```kotlin
@AggregateRoot(ownership = Ownership.TENANT)
class Product(private val state: ProductState) { /* ... */ }
```

编译器把归属写进 Manifest，查询准入从那里读取。`TENANT` 聚合的写由动作级授权（`CommandAuthorizer`，例如 CoSec 的）保护，由它决定谁可以修改商品。需要把调用方与聚合 ID 比较的命令函数（例如购物车 ID 就是顾客的用户 ID）取 `Command<X>`，读取 `command.scope.ownerId`。为某个聚合类型固定租户属于宿主配置：`wow.aggregates.[<context>.<aggregate>].tenant`。

### 发布的流

每个聚合的状态事件都是它的上下文为其他上下文发布的一条流，由 Manifest 的 `published` 列出。`@AggregateRoot(published = false)` 不发布该聚合；`publishState = false` 发布事件但每次提交后不带状态；`topic` 指定 Kafka 主题，为空时是 `wow.<context>.<aggregate>.state`。

### 事件演进

- `@Revision(n)` 给出事件当前的 revision；没有它的事件是 revision 1。新增带默认值的可选字段不算新的 revision。
- 顶层函数（或 `object` 的成员）上的 `@Upgrade(event = X::class, from = n)` 把 revision `n` 存储的 JSON 升级到 `n + 1`。它接收并返回 Jackson 3 的 `JsonNode` 或 `ObjectNode`（`tools.jackson.databind`），是纯函数，不挂起。处于 revision `n` 的事件需要从 1 到 `n - 1` 每个 revision 各一个升级函数；缺档或重复都会让编译失败。
- `@Alias("OldName")` 让改名后的命令或事件仍可读取：不带点的值是同一聚合内的旧名，带点的值是完整类型名。

```kotlin
@Revision(2)
data class OrderCreated(val items: List<OrderItem>, val address: ShippingAddress, val fromCart: Boolean)

@Upgrade(event = OrderCreated::class, from = 1)
fun upgradeOrderCreatedFrom1(node: ObjectNode): ObjectNode {
    node.remove("orderId")
    return node
}
```

### Decider DSL

注解写法的聚合根会被编译成内核的 Kotlin DSL，测试与库也可以直接手写它：

```kotlin
val productModel = aggregate<ProductState>("catalog", "product") {
    initial { ref -> ProductState(ref.id) }
    decide<ChangePrice> { state, command -> Decision.events(PriceChanged(command.price)) }
    evolve<PriceChanged> { event -> price = event.price }
}
```

`decide` 以 `DecideScope` 为接收者；另有 `decideSuspending`、`after`、`evolveTo`（不可变状态）、`upgrade`、`alias` 与 `lifecycle`。这样写的模型没有生成的绑定索引与 Manifest 片段，宿主不会提供它；服务请用注解写法。

## 处理器：Saga、投影与反应器

处理器是恰好标注了 `@Saga`、`@Projection` 或 `@Reactor` 之一的类。它的每个处理函数（标注 `@OnEvent` 或名为 `onEvent`）各自是一个订阅，名为 `<processor>.<function>`。处理器的依赖是它构造函数的参数，像命令函数的服务一样从应用的 Bean 中获取。

处理函数接收 `StateEvent<S>`：一个聚合的一条已提交事件流，以及提交之后的状态。只关心某类事件的处理函数改为接收 `StateTypedEvent<S, E>`，或者直接接收事件 `E`，流中每个 `E` 按顺序各调用一次（决策 D264）：`event` 是该事件，`eventIndex` 是它在 `events` 中的位置，`state` 仍是整次提交之后的状态。

| 属性 | 内容 |
| --- | --- |
| `ref`、`version` | 聚合及其提交后的版本；`id` 是 `ref@version` |
| `events` | 这条流的事件，已解码并升级到当前 revision |
| `state` | 提交后状态的副本，绝不是活的实例 |
| `commandId`、`requestId`、`commandType`、`commitTime` | 产生它的命令及时间（毫秒时间戳） |
| `scope` | 提交后聚合的所有者与空间，不是调用方的 |
| `causation`、`headers` | 命令所属的链路，以及它携带的请求事实；`correlationId` 是根命令的 ID |

`S` 决定读哪些聚合：处理函数读取本模块上下文中状态类型为 `S` 的聚合，除非 `@OnEvent(aggregates = ["<context>.<aggregate>"])` 指定了它们。按 `E` 处理的函数两者都不需要：`E` 决定聚合，编译器把这些事件写进订阅的来源，节点遇到不含 `E` 的流直接跳过，连状态都不解码。同一流里的多个 `E` 共用该流的检查点：失败时整条流重试，每个 `E` 都会再调用一次。他上下文的事件，`E` 是对端 api 的类，并用 `@OnEvent(aggregates)` 指定它的聚合（见下文“读取另一个上下文的流”）。

处理函数的上下文提供 `executionId`，即 `subscription:ref@version`，同一订阅每次处理同一次提交都相同；还提供 `invocationKey`，作为外部调用的幂等键：按流处理时就是 `executionId`，按每个 `E` 处理时是 `executionId:eventIndex`。

### 投影

投影从已提交的事件写出读模型。它不返回值；它的写入按版本守卫，因为同一次提交可能被处理不止一次。

```kotlin
@Projection
class ProductSummaryProjection(private val summaries: ProductSummaries) {
    @Blocking
    @OnEvent
    fun onEvent(event: StateEvent<ProductState>) {
        summaries.upsert(event.ref.id, event.state.name, event.state.price, ifNewer = event.version)
    }
}
```

快照本身已经是读模型：每个聚合都有快照，可在 `/{context}/{aggregate}/snapshot/query` 查询。读模型形状不同或要放到别的存储时，才写投影。

### 反应器

反应器对已提交的事件做动作（发通知、调用其他系统），不返回值。用 `context.invocationKey` 作为外部调用的幂等键：

```kotlin
@Reactor
class OrderNotifier(private val mail: Mail) {
    @OnEvent
    suspend fun onShipped(event: StateTypedEvent<OrderState, OrderShipped>, context: ReactContext) {
        mail.send(event.state.customer, "Order shipped", key = context.invocationKey)
    }
}
```

反应器不能发送命令：发送命令的处理器就是 Saga。

### Saga

Saga 从已提交的事件派生命令。它的处理函数返回一个 `Command`、它们的列表，或者 `null`、空列表、什么也不返回（`Unit`）表示没有命令；每条命令由 `SagaContext.command(ref, body)` 构造：

```kotlin
@Saga
class CartSaga {
    @OnEvent
    fun onOrderCreated(event: StateTypedEvent<OrderState, OrderCreated>, context: SagaContext): Command<RemoveCartItem>? {
        val created = event.event
        val owner = event.scope.ownerId ?: return null
        return context.command(
            AggregateType("example", "cart").ref(event.ref.tenantId, owner),
            RemoveCartItem(created.items.mapTo(linkedSetOf()) { it.productId }),
        )
    }
}
```

- 派生命令的 ID 与请求 ID 由这次执行和调用顺序确定，所以再次处理同一次提交会派生出相同的请求，它们只被决定一次。
- 同上下文的命令提交后、跨上下文的命令被 broker 接收后，Saga 的这一跳完成。
- 派生命令带上它所派生自的那次提交的作用域（所有者与空间）与操作人，租户取自 Saga 给出的 `ref`。它创建的聚合属于同一个所有者：购物车结账创建的订单属于购物车的买家。目标的作用域守卫会检查这个作用域，所以发给别的所有者的聚合的命令会被拒绝，除非目标属于租户（`Ownership.TENANT`，例如库存）。Saga 可以收窄它返回的命令的作用域：订单可以有空间而购物车没有，所以示例的 `CartSaga` 返回 `command.copy(scope = command.scope.copy(spaceId = null))`。补偿（`@OnRejected`）与定时命令同样带上它们所跟随的命令的作用域。
- `context.command(type, body)` 用 `AggregateType` 代替 ref，派生一条把聚合 ID 留给目标准入的创建类命令：目标取该请求已创建的聚合的 ID，否则取新号，所以再次处理同一提交仍得到第一次的 ID。若在 Saga 里用 `context.ids` 取 ID，再次处理提交时会取到新号，所以只能用于它所命名的那条创建命令，靠请求 ID 防止目标重复创建。
- 处理函数的返回类型没有说明命令时（例如 `List<Command<*>>`），用 `@OnEvent(derives = [ConfirmPayment::class, FailPayment::class])` 声明这些命令类，等待链才能跟随 Saga 的这一跳进入那些上下文。密封类型代表它的各个子类，在 `derives` 里和返回类型 `Command<S>` 里都是如此，后者因此不必再写 `derives`。服务构建时，若某个类既不由本服务、也不由类路径上对端 api 的任何上下文决定，构建失败。

### Saga 预期的拒绝：`@OnRejected`

派生命令可能被拒绝：购物车已被删除、库存不足。拒绝是业务结果，所以 Saga 在 `@OnRejected` 处理函数里处理它。该函数接收该命令类型的 `CommandRejected<C>`（或 `CommandRejected<*>`，接收 Saga 派生的所有命令），可选 `SagaContext`，返回用于补偿的命令：

```kotlin
@OnRejected
fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: SagaContext): Command<CancelOrder> =
    context.command(rejected.executionId.ref, CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"))
```

`CommandRejected` 给出命令（`commandId`、`commandType`、`ref`）、`error`（错误码、消息、字段违例）、`causation`，以及派生它的那次 Saga 执行的 `executionId`；它不带命令体。运行时对每个拒绝只交付一次。Saga 没有处理函数的拒绝，会像失败一样为该 Saga 的订阅停放这个聚合。处理函数什么也不返回（`Unit`，或 `null`）表示接受拒绝、不做补偿，也不派生命令；`@OnCommitted` 处理函数同样可以返回 `Unit`。

### 订阅从哪里开始

在某个分区没有检查点的订阅按种类决定起点：投影从日志开头开始（补齐历史），反应器与 Saga 从日志末尾开始（上线不对历史事件补发命令）。可以在宿主里按订阅覆盖：

```yaml
wow:
  subscriptions:
    "[OrderStockSaga.onOrderShipped]":
      start: beginning # 或 end，或一个 ISO-8601 时刻
```

这个设置只在订阅还没有检查点时生效；之后它从检查点继续。

### 失败

抛出异常的处理函数会被重试（`wow.subscription.retry`：默认 5 次，退避从 100 ms 到 5 s）。最后一次之后，这个聚合在失败台账里为该订阅停放：订阅继续处理其他聚合，被停放的聚合等待恢复（resume）或跳过（skip）。运行时提供这两个操作，目前还没有路由或控制台暴露它们。

调用慢外部系统的处理函数，可以给它的订阅单独的预算。`timeout` 限制一次尝试：运行更久的尝试被取消，算一次失败的尝试。`@Retry` 声明在处理器类上时作用于它的全部处理函数，声明在 `@OnEvent` 处理函数上时只作用于它；处理函数的设置逐项覆盖处理器的，没写的项视为未给出。时长用 Kotlin 写法（`500ms`、`2s`）或 ISO-8601（`PT2S`）：

```kotlin
@Reactor
@Retry(maxAttempts = 3, timeout = "2s")
class ShippingNotifier(private val carrier: CarrierClient) {
    @OnEvent
    @Retry(initialBackoff = "1s")
    suspend fun onShipped(event: StateTypedEvent<OrderState, OrderShipped>) = carrier.notify(event.state.id)
}
```

编译器校验这些设置，把预算写进生成的绑定与 Manifest 中该订阅的条目（`retry`）。宿主配置可以按订阅覆盖它；给出的每一项取代注解的，两者都没给的仍取全局的：

```yaml
wow:
  subscription:
    retry:
      max-attempts: 5 # 全局预算
  subscriptions:
    "[ShippingNotifier.onShipped]":
      retry:
        max-attempts: 3
        initial-backoff: 1s
        timeout: 2s
```

`@Blocking` 处理函数运行在超时无法中止的虚拟线程上：调用返回时这次尝试才算失败。预算只决定聚合何时停放；停放之后的重试（`wow.subscription.parked.*`）不变。

## 定时命令

命令函数或 Saga 可以定时发送一条命令：未支付的订单自动取消、提醒。定时是提交的一部分：它与事件一起写入，或者都不写入。

```kotlin
@OnCommand(schedules = ["shop.order.CancelOrder"])
fun onCommand(command: PlaceOrder, scope: DecideScope): OrderPlaced {
    scope.schedule("pay-timeout", 30.minutes, CancelOrder("unpaid"))
    return OrderPlaced(command.items)
}

@OnCommand(produces = [OrderPaid::class])
fun onCommand(command: PayOrder, scope: DecideScope): Decision {
    scope.cancelSchedule("pay-timeout")
    return Decision.events(OrderPaid(command.amount))
}
```

- `schedule(key, at, command, target)` 或 `schedule(key, after, command, target)`：`key` 在聚合内命名这个定时，再次定时同一个 key 即改期；`target` 默认是本聚合。
- Saga 中用 `SagaContext.schedule(key, at / after, target, command)` 与 `cancel(key, target)`。
- 在 `@OnCommand(schedules = [...])` 或 `@OnEvent(schedules = [...])` 中声明被定时命令的完整名字：编译器读不到函数体里的调用，Manifest 要列出它们。
- 命令至少发送一次，绝不早于到期时间，可能略晚（交接或积压之后），精度取决于 `wow.timers.poll-interval`。它的请求 ID 是定时器的 ID，所以只被决定一次。
- 到期时，目标按当时的状态决定它。拒绝是正常的守卫（例如订单在此期间已支付）：定时器完成，拒绝不会交给 `@OnRejected`。技术故障会重试（`wow.timers.max-attempts`），之后停放在 `wow.timers` 下。
- 删除聚合会取消它待执行的定时。定时命令不在发起它的那条命令的等待链里。

## 其他上下文

每个上下文由自己的服务提供；服务之间经 Kafka 通信，从不访问对方的数据库或集群通道。

### 读取其他上下文的流

处理函数在 `@OnEvent(aggregates = [...])` 中写出其他上下文的聚合，并声明自己对状态的视图：

```kotlin
@Saga
class OrderStockSaga {
    @OnEvent(aggregates = ["example.order"])
    fun onOrderShipped(
        event: StateTypedEvent<OrderView, OrderShipped>, // OrderShipped：订单上下文 api 的类
        context: SagaContext,
    ): List<Command<CommitReservation>> =
        event.state.items.map { context.command(STOCK.ref(event.ref.tenantId, it.productId), CommitReservation(event.ref.id)) }
}

data class OrderView(val items: List<Item> = emptyList()) {
    data class Item(val productId: String, val quantity: Int)
}
```

- 处理函数读取上游发布的主题 `wow.<context>.<aggregate>.state`，偏移量记在本服务的检查点里。节点需要 `wow-kafka` 与 `wow.kafka.bootstrap-servers`。
- 它的 `events` 是上游 api 模块的类（DX-1c）：事件的修订号与该 api 契约声明的一致时解码；其他事件是 `PublishedEvent`（`me.ahoo.wow.api.subscribe`），带 `name`（`example.order.OrderShipped`）、`revision` 与 JSON `payload`。按 `E` 处理的函数（见上文）接收 api 的类，用 `@OnEvent(aggregates)` 指定聚合，遇到 `PublishedEvent` 不调用；类路径上的契约没有声明这个事件时，节点不会启动。
- 它的 `state` 从发布的状态解码成处理函数自己的类型。上游发布事件但不带状态（`publishState = false`）时，把它声明为可空（`StateEvent<OrderView?>`）：对不带状态的流声明不可空的视图会在启动时失败。
- 读取其他上下文流的 Saga 或反应器默认从末尾开始。不能漏掉自己首次启动之前已发布内容的服务，要为该处理函数设置 `start: beginning`（见[订阅从哪里开始](#订阅从哪里开始)）。
- 服务把上游的 Manifest 作为对端（见下文）。

### 向其他上下文发送命令

Saga 像发送其他命令一样发送其他上下文的命令，类路径上要有那个上下文的 API 模块：

```kotlin
context.command(AggregateType("inventory", "stock").ref(event.ref.tenantId, productId), ReserveStock(event.ref.id, quantity))
```

- 命令写入目标上下文的命令主题 `wow.<target>.commands`。broker 收到后，Saga 的这一跳完成。
- 目标服务设置 `wow.kafka.command-intake: true` 后执行它的命令主题。主题必须已存在，且分区数恰好等于目标的日志分区数（`wow.log.partitions`，默认 64），否则那个节点无法启动。
- 拒绝以回执的形式写回发送方的 `wow.<sender>.receipts`，交给 Saga 的 `@OnRejected` 处理函数。
- 发送方从目标的 Manifest 中找到命令的类型名，该 Manifest 是发送服务的对端。
- `ref` 的租户原样传递：一个系统中的所有上下文共用一个租户空间（见[部署](./deployment.md#一个租户空间)）。目标聚合类型固定了租户时，会拒绝其他租户的命令。

节点的某个 Saga 派生、定时或处理其他上下文命令的拒绝时，没有命令传输的节点无法启动。错误信息指出每个 Saga 与上下文：

```text
Node <node> has no command transport (with Spring Boot, set wow.kafka.bootstrap-servers), but example/OrderSaga.onOrderCreated sends commands to inventory: a command across contexts goes through its context's command topic (decision D33).
```

生产环境的传输是 Kafka（`wow.kafka.bootstrap-servers`）。没有 Kafka 的测试给节点提供内存主题：一个 `InMemoryTopics` Bean（`me.ahoo.wow.kernel.memory`）和基于它的 `SourceTopics` Bean（`me.ahoo.wow.runtime.node`），示例的测试就是这样做的：

```kotlin
class InMemoryShopTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)
}
```

Saga 需要立即拿到结果、目标在线是前提时，改为调用目标的命令网关（`SagaContext.call`，决策 D193），并声明它调用的命令类，这样 Manifest 会列出它们，等待链可以跟随这次调用，其他类的调用会被拒绝（决策 D259）：

```kotlin
@OnEvent(calls = [SubmitOrder::class])
suspend fun onBasketCheckedOut(event: StateTypedEvent<BasketState, BasketCheckedOut>, context: SagaContext) {
    val checkedOut = event.event
    context.call(context.command(EshopOrderingContract.Order.ref(event.ref.tenantId, checkedOut.orderId), SubmitOrder(items)))
}
```

调用不需要命令主题：节点通过 `wow.calls.contexts.<context>.url`（或 `wow.clients.contexts.<context>.internal-url`）找到目标；Saga 调用的上下文没有这个地址，或调用的命令没有任何已知 Manifest 决定，节点都不会启动。Saga 自己上下文的命令只派生、不调用。

### 从 Java 调用

命令与查询客户端（`CommandClient`、`QueryApi`）是 Kotlin 的 `suspend` 函数与 flow。Java 代码通过 `wow-client` 的门面调用它们：每个调用都有阻塞形式，以及返回 `CompletableFuture` 的 `…Async` 形式；列表返回 `List`，流返回 `java.util.stream.Stream`，超时用 `java.time.Duration`：

```java
JavaCommandClient commands = JavaCommandClient.of(commandClient).of(caller);
CommandResult submitted = commands.sendAndWait(new SubmitOrder(items), EshopOrderingContract.Order.ref(tenant, id),
    JavaCommandClient.options().timeout(Duration.ofSeconds(5)).build());

JavaSnapshotQueries<OrderState> orders = JavaQueryApi.of(queryApi).of(caller).snapshots(OrderState.class);
CompletableFuture<Integer> open = orders.listAsync(new ListQuery(MatchAllFilter.INSTANCE)).thenApply(List::size);
```

被拒绝时抛出 `CommandRejectedException`（带 `code`），技术性失败抛出 `WowProblemException`，两者都是非受检异常；异步调用的 future 以它们完成。阻塞形式只在你自己的线程上调用，不要在 Netty 或 WebFlux 的事件循环线程上调用。

### 对端

发送其他上下文命令或读取其流的服务，把那个服务合并后的 Manifest 作为对端。使用插件时，在服务端模块中：

```kotlin
dependencies {
    wowPeers(project(":inventory-server"))            // 同一构建中的项目
    wowPeers("com.example:inventory-server:1.0.0")    // 或已发布的服务
}
```

`collectWowPeers` 把每个对端的 Manifest 复制到模块资源的 `META-INF/wow/peers/<service>.json`，旁边附一个索引。两个服务可以互为对端。
