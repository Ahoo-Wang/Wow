---
title: 编写聚合
description: 编写聚合的命令、命令函数和状态；在决定中使用领域服务、创建聚合、附加结果、预留键和定时命令。
outline: deep
---

# 编写聚合

聚合是做出决定的单位：它一次接收一条命令，查看自己的状态，决定发生哪些事件。本页以 eShop 的订单为例写一个聚合。背后的模型见[决定与演化](../concepts/programming-model)。

## 代码放在哪里 {#where-the-code-goes}

一个聚合跨越它所在上下文的两个模块：

| 模块 | 包含 | 由谁读取 |
| --- | --- | --- |
| `*-api` | 命令和事件，普通的数据类 | 服务本身，以及发送它的命令或读取它的事件的每个对端 |
| `*-domain` | 根类及其状态类 | 只有服务本身 |

编译器在构建期读取领域模块，生成聚合的模型、它的绑定以及它那部分 Manifest。运行时不扫描任何东西。

## 编写命令 {#write-the-commands}

命令是 api 模块里的一个数据类。它承载调用方要求的内容，从不承载调用方是谁：租户、所有者和空间来自调用方，期望版本来自 `If-Match`。聚合 ID 来自路由或发送命令的 Saga，除非命令体本身已经指明了聚合（[在命令体中指明聚合](#name-the-aggregate-in-the-body)）。

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

`@Summary` 为命令提供一行摘要，用于 Manifest 和 OpenAPI 文档。不带数据的命令是一个 `data object`，例如 `ShipOrder`。

### 检查它的形状 {#check-its-shape}

只关乎命令本身的规则，例如数量为正、名称不为空白、订单有条目，是命令类上的 Jakarta Bean Validation 约束，用 Kotlin 的 `@field:` 目标：

<!-- sample: examples/eshop/basket/basket-api/src/main/kotlin/me/ahoo/wow/example/eshop/basket/api/basket/BasketCommands.kt#add-item -->

```kotlin
/**
 * Adds [quantity] of [productId] at [unitPrice] to the basket, creating the basket on its first item. Adding a product
 * already in the basket adds to its quantity, at the new price. The constraints are the command's shape: the gateway
 * checks them before the basket decides, and answers a violation `wow.command.invalid` (400).
 */
@AllowCreate
@Summary("Add an item to the basket")
data class AddBasketItem(
    val productId: String,
    @field:PositiveOrZero val unitPrice: BigDecimal,
    @field:Positive val quantity: Int,
)
```

网关在聚合决定之前检查它接收的每条命令的约束：来自路由、进程内 `CommandClient`、Saga 或另一个上下文的命令都一样。违反约束时回答 `wow.command.invalid`（400），problem 列出每个违反项的属性路径（`items[0].quantity`）、约束（`Positive`）和消息。属性标注 `@field:Valid` 时检查嵌套的值，`SubmitOrder` 的条目就是这样。api 模块依赖 `jakarta.validation:jakarta.validation-api`，服务需要一个 Bean Validation 实现（[托管在 Spring Boot 上](./spring-boot#what-the-class-path-decides)）。

约束是命令契约的一部分。它们是 Manifest 和 OpenAPI 文档中命令 schema 的校验关键字（`quantity` 是 `{"type": "integer", "format": "int32", "exclusiveMinimum": 0}`），生成的客户端或 Swagger UI 在发送之前就能看到它们。收紧一个约束，例如提高最小值，是破坏性改变（[Manifest 与兼容](./manifest#the-compatibility-check)）。

需要聚合状态或调用方的规则留在命令函数里，作为带错误码的拒绝（[拒绝与错误码](./rejections)）：下面订单的函数已经没有要检查的了。

命令能否创建它的聚合，由它的类决定：

| 命令类 | 何时被接受 |
| --- | --- |
| `@CreateAggregate` | 聚合尚不存在；否则 `wow.aggregate.exists` |
| `@AllowCreate` | 无论聚合是否存在 |
| 两者都没有 | 聚合已存在；否则 `wow.aggregate.not-found` |

每条命令的路由是 `POST /<context>/<aggregate>/{id}/<command>`，命令名采用 kebab case，与 Manifest 列出的路由一致：`POST /eshop-ordering/order/{id}/submit-order`。创建命令还提供不带 `{id}` 的路由，由服务端为聚合分配 ID。准入会先查找同一个请求 ID 已经创建过的聚合并复用它的 ID，所以同一个创建发两次只会创建一个聚合。ID 即所有者 ID 的聚合（每个所有者一个）的每条命令也都提供不带 `{id}` 的路由，指调用方自己的聚合，并且不生成 ID（[每个所有者一个聚合](./ownership#one-aggregate-per-owner)）。

### 在命令体中指明聚合 {#name-the-aggregate-in-the-body}

有些命令的业务数据里本来就有它的聚合 ID：一次补货指明要给哪个商品的库存补货。在这个属性上标注 `@AggregateId`，命令体就指明了聚合，发送方不必再另给 ID：

<!-- sample: fragment -->

```kotlin
@AllowCreate
@Summary("Restock a product")
data class RestockProduct(
    @AggregateId val productId: String,
    @field:Positive val quantity: Int,
)
```

这样的命令只提供 `POST /<context>/<aggregate>/<command>` 一条路由，不带 `{id}`，作用于命令体指明的聚合。Saga 用 `CommandRequest.of(body)` 发送它，客户端发送时也不带 ID。发往另一个上下文的这类命令进入该上下文的命令主题时按聚合分区，同一个聚合的命令因此保持顺序。准入在授权之前读取命令体，所以策略看到的仍是聚合 `<aggregate>/<id>`。该聚合的生命周期命令保留带 `{id}` 的路由。

| 属性 | 承载 |
| --- | --- |
| 有字段或构造参数的 `String` | ID，即命令体里的值 |
| 计算属性 `String`，如 `val stockId: String get() = "$warehouseId-$sku"` | 由其他字段算出的 ID：只发送那些字段的发送方可以不带它，由服务算出 |
| 可创建聚合的命令上的 `String?` | ID；为 null 时交给服务端分配，与任何不指明 ID 的创建一样，重试沿用第一次的 ID |

准入会检查这个 ID：空白的 ID 是 `wow.command.malformed`（400）；在命令体之外又指明了另一个聚合的请求（例如 Kotlin 客户端的 `CommandRequest.to(ref, body)`）同样是 `wow.command.malformed`。指明同一个聚合的请求会被接受。

这个属性由命令类或它实现的接口声明，是 public 或 internal，并且 Wow 的 JSON 会写出它（没有 `@JsonIgnore`），所以每个发送方的命令体里都有它。一条命令只有一个属性指明聚合。编译器拒绝把它用在 `@Unaddressed` 命令或挂载命令上（它们的聚合由转换器找到），也拒绝用在 ID 即所有者 ID 的聚合上。接收这类命令的 `CommandTransformer` 会让服务在启动时失败。

## 编写根 {#write-the-root}

根是一个标注了 `@AggregateRoot`、由它的状态构造的类。它的命令函数做出决定：每个函数接收一条命令，返回它的决定。

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
```

编译器找出每个标注 `@OnCommand` 或名为 `onCommand` 的非私有函数。第一个参数是命令；返回值是决定：

| 返回 | 含义 |
| --- | --- |
| `Decision.events(e1, e2, …)` | 这些事件，作为一条事件流一起追加 |
| `Decision.NoOp` | 命令被接受但什么都不改变；不写入任何东西 |
| `Decision.reject(code, message)` | 命令被拒绝；不写入任何东西，调用方拿到错误码（[拒绝与错误码](./rejections)） |
| 一个事件，或一个事件列表 | 这些事件 |
| `Unit`、`null` 或空列表 | 什么都不改变 |

返回 `Decision` 的函数没有告诉编译器它能产生哪些事件，所以要在 `@OnCommand(produces = [...])` 里列出它们。Manifest 会发布这份列表。返回类型本身已经指明事件的函数，例如返回某个事件类或它们的密封类型，不必重复列出。

返回类型是接口或抽象类、又不是密封的，会导致编译错误。这样的函数返回的是几个实现中的一个，而这个类型说不出是哪几个。改为把它们列出来：把这个类型改成以这些事件为子类的密封接口，直接返回事件类本身，或者返回 `Decision` 并写上 `produces`。元素类型（`List<OrderEvent>`）和密封类型的子类也适用同样的规则。

聚合以根类名的 snake case 命名（`Order` 是 `order`，`ViewPreferences` 是 `view_preferences`），或者用 `@Name` 指定。

## 读取准入后的命令 {#read-the-admitted-command}

需要准入从请求中得知的信息的命令函数，接收 `Command<X>` 而不是 `X`：

| 成员 | 包含 |
| --- | --- |
| `body` | 命令 `X` |
| `ref` | 聚合：它的上下文、名字、租户和 ID |
| `scope` | 调用方的所有者和空间 |
| `headers` | 宿主保留的请求事实（`wow.http.request-facts`），例如调用的应用 |
| `causation`、`operator` | 命令所属的链，以及它以谁的名义记录 |

记录提交来源应用的反馈表单读取请求事实：

<!-- sample: fragment -->

```kotlin
fun onCommand(command: Command<SubmitFeedback>): FeedbackSubmitted =
    FeedbackSubmitted(command.body.text, app = command.headers["app_id"])
```

函数不必检查调用方能否接触这个聚合：内核根据作用域检查它（[内核检查什么](./ownership#what-the-kernel-checks)）。每个所有者只有一个的聚合，例如购物车，以所有者的 ID 为 ID（[每个所有者一个聚合](./ownership#one-aggregate-per-owner)）。

## 使用领域服务 {#take-domain-services}

命令之后的参数是领域服务，或者 `DecideScope`。服务会成为生成的模型的一个参数，宿主在构建模型时绑定一次：取应用中该类型唯一的 Bean，或者它的 primary Bean。在参数上写 `@Name("…")` 则取这个名字的 Bean，于是同一类型的两个参数可以取两个不同的 Bean。

订单把它的支付策略作为服务接收，并在库存确认后用 `DecideScope` 为自己定时取消：

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#confirm-stock -->

```kotlin
/**
 * Confirms one product. A confirmation the order does not wait for changes nothing: one read again from the
 * stock stream, or one that arrives after another product's shortage cancelled the order (that reservation is
 * released when the inventory hears of the cancellation).
 */
// Decision: the last confirmation produces two events, the item's and the order's.
@OnCommand(
    produces = [OrderStockItemConfirmed::class, OrderStockConfirmed::class],
    schedules = [CancelOrder::class],
)
fun onCommand(command: ConfirmOrderStock, paymentPolicy: PaymentPolicy, scope: DecideScope): Decision {
    val waiting = state.status == OrderStatus.AWAITING_VALIDATION &&
        command.productId in state.products() &&
        command.productId !in state.confirmedProducts
    if (!waiting) {
        return Decision.NoOp
    }
    val confirmed = OrderStockItemConfirmed(command.productId)
    if (state.confirmedProducts + command.productId != state.products()) {
        return Decision.events(confirmed)
    }
    scope.schedule(
        PAYMENT_GRACE_PERIOD,
        ScheduleRule.after(paymentPolicy.gracePeriod),
        CancelOrder(PAYMENT_TIMEOUT)
    )
    return Decision.events(confirmed, OrderStockConfirmed)
}
```

服务可以做 I/O。会阻塞线程的函数，例如调用 JDBC 的函数，要标注 `@Blocking`，它会在虚拟线程上运行。函数也可以是 `suspend`，或者返回 Reactor 的 `Mono`，这需要类路径上有 `wow-reactor`。

### 查询读取端 {#query-the-read-side}

命令函数需要聚合状态以外的信息时，例如它引用的账户是否存在，就把节点的读取 API 作为服务接收：`QueryApi`，或者用于受信读取的 `NodeQueryApi`。两者都在 `wow-api`（`me.ahoo.wow.api.read`）里，领域模块不需要别的依赖，宿主会把节点自己的读取 API 交给模型。如果调用方已经知道这个事实，就把它放进命令（用 `CommandTransformer`，或由发送命令的 Saga 放入）：读取会在每次版本冲突后的重试中再跑一遍，而且读到的内容可能在事件追加之前就变了。

以命令的调用方身份读取，调用方从命令重建：它的租户、所有者和空间，以及它的操作员。读取能看到的，就是这个调用方能看到的：

<!-- sample: fragment -->

```kotlin
@OnCommand
suspend fun onCommand(command: LendBook, queries: QueryApi, scope: DecideScope): BookLent {
    val admitted = scope.command
    val caller = Caller.of(admitted.ref.tenantId, admitted.scope, admitted.operator?.id)
    queries.of(caller).snapshots<AccountState>().state(command.accountId)
        ?: reject("library.book.no-account", "No account ${command.accountId}.")
    return BookLent(command.accountId)
}
```

重建的调用方没有角色、策略或 ABAC 属性。它们只随请求存在，而命令可能在另一个节点上决定，或者从它的命令主题取来。读取这些属性的查询策略什么也看不到，要求某个角色的策略会拒绝这次读取。

不应受任何策略限制的读取，接收 `NodeQueryApi` 并用 `trusted()` 读取。

::: warning 受信读取跨越租户
`trusted()` 读取所有租户。在有多个租户的服务里，函数要自己按命令的租户过滤，如示例所示。
:::

<!-- sample: fragment -->

```kotlin
@OnCommand
suspend fun onCommand(command: LendBook, queries: NodeQueryApi, scope: DecideScope): BookLent {
    val lent = queries.trusted().snapshots<BookState>().count(
        countQuery {
            filter {
                tenantId(scope.command.ref.tenantId)
                "state.account" eq command.accountId
            }
        },
    )
    if (lent.count >= MAX_BOOKS) {
        reject("library.book.too-many", "Account ${command.accountId} has $MAX_BOOKS books.")
    }
    return BookLent(command.accountId)
}
```

读取失败时，命令以 `wow.command.failed`（500）失败，与任何意外异常一样。读取端拒绝的读取，以及代码写错的读取（例如没有聚合使用的状态类），都是如此：过错在代码，不在调用方。在聚合规格里，把 `memoryQueries { snapshots(...) }` 作为 `QueryApi` 交给模型工厂（[测试](./testing)）。

## 向内核要更多东西 {#ask-the-kernel-for-more}

`DecideScope` 是一次决定除了事件之外还能向内核要的东西：

| 成员 | 作用 |
| --- | --- |
| `command` | 正在决定的命令，类型为 `Command<*>` |
| `now` | 这次决定在节点时钟上的时间（纪元毫秒）：它的事件被记录的提交时间。用它而不是系统时钟计算截止时间或过期时间，测试就能设定它（[测试](./testing#arrange-and-act)） |
| `ids` | 节点的 ID 生成器，用于决定需要的 ID |
| `putResult(key, value)` | 往事件追加之后给调用方的回答里加一个值。它不存储：重复的请求不会再得到它 |
| `reject(code, message)` | 用一个错误码拒绝命令，可以附带用于 problem 的 `extensions`（[拒绝](./rejections#reject-with-a-code)） |
| `reserve(namespace, key)` | 为这个聚合预留一个唯一键，例如用户名；被另一个聚合持有时返回 `false`。`ReservationTenancy` 参数扩大它的范围（[唯一键](./unique-keys#reserve-a-key)） |
| `release(namespace, key)` | 在事件追加之后，释放这个聚合持有的一个键；使用预留它时的 `ReservationTenancy` |
| `onAppendFailure { … }` | 在确定事件没有追加时，撤销一个外部动作（[见下文](#undo-an-outside-act)） |
| `afterAppend { … }` | 事件追加之后运行；失败只记日志，不抛出（[见下文](#undo-an-outside-act)） |
| `schedule(key, rule, command, target)` | 定时一条命令，发往这个聚合，除非 `target` 指定了另一个。它与事件一起写入，要么都写入，要么都不写 |
| `cancelSchedule(key)` | 取消这个聚合在 `key` 下保持的定时 |

决定可以通过 `putResult` 把它算出的值（比如一个报价）回答给调用方；调用方从结果的 `results` 里读取，这里是 `{"cost": 4.50}`：

<!-- sample: fragment -->

```kotlin
fun onCommand(command: QuoteShipping, scope: DecideScope): ShippingQuoted {
    val cost = rates.costOf(state.weight, command.destination)
    scope.putResult("cost", cost)
    return ShippingQuoted(command.destination, cost)
}
```

命令创建的东西最好按它的聚合 ID 查找，重复的请求也会再次得到它。eShop 的结账不回答订单 ID：订单由 ordering 编号（`SO-261031-42`），等到订单提交（`committed(eshop-ordering)`）的结账，从这一跳的聚合 ID 读到订单号（[Saga](./sagas#call-another-context-synchronously)）。

定时命令的函数要在 `@OnCommand(schedules = [...])` 里声明命令的类，就像订单对 `CancelOrder::class` 做的那样：编译器读不到函数体里的调用，而 Manifest 按完整类型名列出它们。

## 撤销对外部的操作 {#undo-an-outside-act}

决策可能在事件追加之前就对聚合之外做了事：预授权一张卡，在另一个系统里占一个座位。`onAppendFailure` 登记怎样撤销这件事，内核在确知事件没有追加时执行它：

<!-- sample: fragment -->

```kotlin
@OnCommand
suspend fun onCommand(command: PayOrder, scope: DecideScope): Decision {
    val authorization = payments.authorize(state.totalAmount, key = scope.command.requestId)
    scope.onAppendFailure { payments.void(authorization) }
    return Decision.events(OrderPaid(authorization.id))
}
```

以下情况会执行补偿：

- 决策拒绝了命令（[三种方式](./rejections)中的任意一种），或者什么也不追加（`Decision.NoOp`）；
- 决策函数抛出异常；
- 追加遇到版本冲突。每次尝试先撤销自己做的事，命令再基于更新后的状态重新决定；
- 存储拒绝了追加，或者以确定没有写入的方式失败；
- 追加时发现这个请求已经由另一次尝试提交。

追加结果未知时不执行。内核会先读取这次追加本应占用的版本；只有仍然无法判断时，才回答 `wow.command.unknown`，此时事件可能已经提交。进程崩溃时也无法执行。所以，命令丢失后不能留下的外部操作，还需要自己的清理手段：外部系统里的过期机制，或者读取日志的对账。

- **顺序。** 补偿按登记的逆序逐个执行，每个都会等待完成，调用方已经离开时也一样。没有超时：卡住的补偿会挡住这个聚合的命令，所以要给它的调用设上限。
- **失败。** 一个补偿失败不会阻止其余的补偿。失败会被记日志，或附加到它所撤销的那个失败上，命令的应答不变。
- **两次。** 基于已落后于日志的缓存状态被拒绝、或未作改变的命令会重新决定，第一次尝试先被补偿。外部操作因此可能做了两次、撤销一次，所以要以命令为键，如上例中的 `requestId`。
- **预留的键。** `reserve` 会登记自己的补偿：事件没有追加时，放弃这次尝试的持有，而这个聚合原本就持有的键继续持有。一次尝试预留和释放的键随它追加的事件一起记录，所以结果未知、或进程在追加之后崩溃时，内置的 `reservation` 订阅按日志确认和释放它们。只有没有任何已追加条目记录的持有（进程在追加之前崩溃）才会过期，30 秒后由这个键的下一次预留取得。

`afterAppend` 是另一面：事件追加之后、应答调用方之前，按登记顺序执行。重复请求不执行，任何没有追加的结果也不执行，拒绝也不执行，即使是提交了事件的拒绝：那时执行的是补偿。失败只记日志，事件仍然已提交。订阅可能在它结束之前就看到这次提交；进程在两者之间崩溃，它就被跳过。所以它适合可以丢失的工作，例如预热缓存。必须跟随每次提交的工作应放在反应器或 Saga 中，由它们读取已提交的事件。

## 编写状态 {#write-the-state}

状态类为每个事件提供一个演化函数，标注 `@OnSourcing` 或名为 `onSourcing`。演化函数应用一个事件：它不接收服务，也不做 I/O。

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderState.kt#sourcing -->

```kotlin
fun onSourcing(event: OrderSubmitted) {
    items = event.items
    totalAmount = event.items.fold(BigDecimal.ZERO) { total, item ->
        total + item.unitPrice.multiply(BigDecimal(item.quantity))
    }
    status = OrderStatus.SUBMITTED
}
```

- 聚合能产生的每个事件都需要一个演化函数，即使它什么都不改变。
- 原地修改状态的演化函数不返回任何东西。返回状态类型的演化函数则返回新的状态，用于不可变状态。
- 在第一个事件之前就给每个属性一个值。内核按状态的原样序列化它，用于快照和发布的事件流。
- 命令函数从不修改状态，只读取它。测试 DSL 比较每次决定前后的状态，若有改变就让测试失败。

### 读取提交 {#read-the-commit}

演化函数可以在事件之后再接收该事件所属的提交：一个 `EventStream`，带有日志记录的提交时间、操作人、版本和命令的 ID。内核在决定时和重放时（从日志，或从快照及其后的事件流）给出同一个提交，所以之后重建的状态是同一个状态。要保存时间的状态从提交读取它，绝不从时钟读取：

<!-- sample: fragment -->

```kotlin
fun onSourcing(event: OrderPaid, commit: EventStream) {
    paidAt = commit.commitTime
    paidBy = commit.operator?.id
}
```

- 不可变状态以同样的方式接收它：`fun onSourcing(event: OrderPaid, commit: EventStream): OrderState`。
- 以代码编写模型时，写作 `evolve<OrderPaid> { event, commit -> … }` 或 `evolveTo<OrderPaid> { state, event, commit -> … }`。
- 提交时间就是这次决定的 `scope.now`：决定据此计算的截止时间与状态读到的时间一致。
- 从 9.x 导入的历史把每条流的创建时间和操作人保留为其提交的提交时间和操作人（[迁移](../migrate/source#events-and-state)）。

## 在每条命令之后运行代码 {#run-code-after-every-command}

标注 `@AfterCommand` 或名为 `afterCommand` 的函数，在它适用的命令函数之后运行：`include` 里的命令函数，`include` 为空时是全部，再减去 `exclude` 里的。它看到的是命令函数看到的那个状态，返回事件，这些事件在同一条流里追加在命令自己的事件之后。命令被拒绝时它不运行，它也不能拒绝。它失败时整条命令失败，什么都不写。

## 生命周期命令 {#lifecycle-commands}

每个聚合还处理 `me.ahoo.wow.api.lifecycle` 中的五条系统命令：`DeleteAggregate`、`RestoreAggregate`、`TransferOwner`、`TransferSpace` 和 `ApplyTags`。它们改变的是聚合的元数据，而不是它的状态；已删除的聚合拒绝除 `RestoreAggregate` 之外的每条命令。`@AggregateRoot(lifecycle = [...])` 可以收窄这份列表；空列表则一条都不启用。

## 测试 {#test-it}

用 `aggregateSpec` 测试每个命令函数：给定这些事件，执行这条命令，期望得到这些事件和这个状态。做法见[测试领域代码](./testing)。

## 下一步 {#next}

- [拒绝与错误码](./rejections)：声明命令被拒绝时用的错误码。
- [编写 Saga](./sagas)：从聚合的决定派生命令。
