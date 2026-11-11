---
title: 测试服务
description: 在测试的 JVM 中经 HTTP 测试整个服务：主题在内存中、对着容器里的真实存储，以及对照它提交的 Manifest。
outline: deep
---

# 测试服务

领域测试检查决定和处理器（[测试领域代码](./testing)）。服务测试检查其余部分：宿主能用服务的模块和配置构建出服务、它的路由能回答、它的 Saga 和定时能运行，以及它能在生产要用的存储上工作。本页在三个层次上测试 eShop 的 ordering 服务。

| 层次 | 运行 | 证明 |
| --- | --- | --- |
| 进程内 | 服务运行在测试的 JVM 里，使用内存后端，主题在内存中 | 装配、路由、Saga、等待、定时器 |
| 真实存储 | 同上，但使用容器里的 PostgreSQL 和 ClickHouse | schema、查询、Sink |
| 契约 | 构建合并出的 Manifest 对照提交的基线 | 没有改变破坏客户端，基线记录了每处改变 |

## 在测试中运行服务 {#run-the-service-in-the-test}

服务测试用 `wow-test-spring-boot` 的 `wowServer<App>(profiles…)` 启动应用，使用随机端口和回环地址，并经它的 `http` 客户端与它通信：

<!-- sample: examples/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#start -->

```kotlin
private val server = wowServer<OrderingServer>(
    "dev",
    properties = mapOf(
        "wow.timers.poll-interval" to "200ms",
        "eshop.ordering.payment-grace-period" to "2s",
    ),
)
```

- 这个 fixture 把两个端口都绑定在回环网卡上的空闲端口。内部端口只绑定在回环网卡上、又没有存储 Bean 时，节点使用内存后端，它从内存提供快照查询和事件查询，除非 `wow.query.memory.enabled` 为 `false`。
- 它缩短节点关闭时等待的超时（`wow.cluster.drain-timeout`、`wow.subscription.stop-timeout`），让测试类之间的关闭很快；`properties` 加上测试自己的属性，这里是 `wow.timers.poll-interval=200ms`，让定时命令及时触发。
- 应用自己的设置缩短测试要等待的东西：这里的支付宽限期是两秒，而不是三十分钟。

ordering 的 Saga 会发送 inventory 的命令，所以节点需要命令传输。测试服务器为此什么都不用写：它的内部端口在回环网卡上，所以节点运行于开发模式，`wow.transport=auto` 就把命令主题和发布流留在它的 JVM 里，除非应用配置了传输。测试要在节点启动前往主题里写入（例如 Saga 要从头读的别的上下文的发布流），或者几个服务器共用主题，就用 `topics` 给出：

<!-- sample: test/test-spring-boot/src/test/kotlin/me/ahoo/wow/test/server/TestTopicsTest.kt#topics -->

```kotlin
@Test
fun `servers given the same topics should share them, which outlive each`() {
    val topics = InMemoryTopics(4)
    wowServer<BankServer>("dev", topics = topics).use { first ->
        first.bean<InMemoryTopics>().assert().isSameAs(topics)
        runBlocking { first.commands(Caller.ANONYMOUS).wait(OpenAccount("Bea"), "bea") }
        wowServer<BankServer>("dev", topics = topics).use { second ->
            second.bean<SourceTopics>().source.assert().isSameAs(topics)
        }
    }
    eventually("the account's stream published") { topics.messages(ACCOUNTS).size == 1 }
}
```

- 节点用给它的主题代替自己的，并设 `wow.transport=memory`；关闭时不关闭这些主题，所以它们比共用它们的每个服务器都活得久。
- `server.bean<InMemoryTopics>()` 是节点的主题，无论是给的还是它自己的，用来读它的 Saga 发出的命令、它的流发布的记录。

然后测试用服务器的类型化客户端驱动服务（[以调用者身份发送与读取](#send-and-read-as-a-caller)）：等待计划能到的，等到 Saga 链尾；等待计划到不了的，才轮询。库存已确认却没有支付的订单，在它的定时命令到期时会取消自己：

<!-- sample: examples/eshop/ordering/ordering-server/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingServerTest.kt#grace -->

```kotlin
@Test
fun `an order not paid within its grace period should cancel itself`() {
    submit("order-unpaid")
    confirm("order-unpaid", "mug", "cap")
    order("order-unpaid").status.assert().isEqualTo(OrderStatus.STOCK_CONFIRMED)
    // The order cancels itself by a scheduled command of its own, which no command of the test's waits for.
    eventually("order-unpaid cancelled") { order("order-unpaid").status == OrderStatus.CANCELLED }
    order("order-unpaid").cancelReason.assert().contains("payment grace period")
    rejected { orders().wait(PayOrder("p-2", BigDecimal("29.00")), "order-unpaid") }
        .code.assert().isEqualTo("eshop-ordering.order.wrong-status")
}
```

## 以调用者身份发送与读取 {#send-and-read-as-a-caller}

服务器的类型化客户端以调用者的身份发送服务 api 模块里的命令、读取它的状态，测试里不写路径也不写 JSON：`commands(caller)` 是一个 `CommandClient`，`queries(caller)` 是这个调用者的 `Queries`，都在服务器的公开端口上。它们就是 `wow-client`，服务调用对端用的那个客户端，处于用户模式。调用者以开发身份头发送（`Wow-User`、`Wow-Owner`、`Wow-Roles`、`Wow-Tenant`、`Wow-App`，即 `UserCredential.DEVELOPMENT`），宿主在开发 profile 下、应用没有 `CallerResolver` 且 CoSec 关闭时读取它们（[调用者是谁](./http#who-the-caller-is)），所以测试在开发 profile `dev` 下启动服务器。快速开始的 bank 服务这样测试：

<!-- sample: test/test-spring-boot/src/test/kotlin/me/ahoo/wow/test/server/TypedClientsTest.kt#typed-clients -->

```kotlin
@Test
fun `the typed clients should send commands and read states as the caller`() {
    val alice = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
    val commands = server.commands(alice)
    runBlocking {
        commands.wait(OpenAccount("Alice"), "alice").status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        commands.wait(Deposit(100), "alice").version.assert().isEqualTo(2)

        val account = server.queries(alice).snapshots(AccountState::class.java).state("alice")!!
        account.state.balance.assert().isEqualTo(100L)
        account.operator!!.id.assert().isEqualTo("alice")

        val rejected = assertThrows<CommandRejectedException> { commands.wait(Withdraw(150), "alice") }
        rejected.code.assert().isEqualTo("bank.account.insufficient-balance")
    }
}
```

- `wait` 回答命令的等待计划最终的结果，等待计划可以一直等到 Saga 链尾。拒绝，无论来自聚合还是来自准入（无效或未知的命令、被禁止的调用者），是带错误码的 `CommandRejectedException`；其他问题是 `WowProblemException`。
- 系统命令也一样按类发送，发往请求指名的聚合类型：生命周期命令（`CommandRequest.of(DeleteAggregate.of(), id, type = …)`）、快照重建（`RebuildSnapshot.of(ids)`）、作业命令（`DisableJob`，按作业名发往 `<context>.schedule`）。
- 以其他方式认证调用者的服务器（在 CoSec 之后，或有应用自己的 `CallerResolver`）接受它读取的凭据：`commands(caller, credential)` 与 `queries(caller, credential)`，用一个把调用者写成那台服务器所读请求头的 `UserCredential`。
- 等待计划看不到的东西，例如到期触发的定时命令、另一个服务收到的调用，测试用 `eventually("what") { condition }` 轮询，默认每 100 毫秒一次，最多 20 秒。条件抛出异常（包括断言失败）视为尚未成立；失败时说明哪件事没有达到，附上给出的 `detail`（例如各服务的输出），并以条件最后一次的失败为原因。
- `server.http` 留给线上契约本身：路由的形状、请求头、problem 的成员。请求可以像类型化客户端那样指名调用者（`server.http.post(path, json, caller = alice)`，写成开发身份头）。`Answer.problem` 把 `application/problem+json` 的回答读成类型化的 `Problem`：它的状态、错误码和违规项，被拒命令的结果，聚合的版本，以及重试能做什么；`Answer.result` 读出命令的结果，无论成功还是 problem 里的。

<!-- sample: test/test-spring-boot/src/test/kotlin/me/ahoo/wow/test/server/TypedClientsTest.kt#problem -->

```kotlin
@Test
fun `a problem the server answered should be typed`() {
    server.http.post("/bank/account/carol/open-account", """{"owner":"Carol"}""").status.assert().isEqualTo(200)
    val invalid = server.http.post("/bank/account/carol/deposit", """{"amount":0}""").problem!!
    invalid.status.assert().isEqualTo(400)
    invalid.code.assert().isEqualTo("wow.command.invalid")
    val violation = invalid.violations.single()
    violation.path.assert().isEqualTo("amount")
    violation.code.assert().isEqualTo("positive")

    val rejected = server.http.post("/bank/account/carol/withdraw", """{"amount":1}""").problem!!
    rejected.status.assert().isEqualTo(409)
    rejected.code.assert().isEqualTo("bank.account.insufficient-balance")
    rejected.result!!.status.assert().isEqualTo(CommandResult.Status.REJECTED)
    rejected.version.assert().isEqualTo(1)
    rejected.retry.assert().isEqualTo(Retry.NONE)
}
```

## 伪造它调用的服务 {#fake-the-services-it-calls}

Saga 同步调用另一个服务（向那个服务的内部端口发命令）时，测试不需要那个服务：`fakePeer(context)` 替它出场，按它的 api 模块在测试类路径上携带的契约构建；`wowServer(…, peers = listOf(peer))` 让服务器调用它（`wow.peers.<context>.url`）。假对端记录收到的每次调用：解码成命令类的命令、发出调用的服务，以及调用所代表的根命令及其租户、范围与操作者。basket 的结账这样调用 ordering：

<!-- sample: fragment -->

```kotlin
fakePeer("eshop-ordering").use { ordering ->
    wowServer<BasketServer>("dev", peers = listOf(ordering)).use { basket ->
        val alice = Caller.of(subject = "alice", scope = Scope(ownerId = "alice"))
        runBlocking { basket.commands(alice).wait(CheckoutBasket, "alice") }
        val call = ordering.called<SubmitOrder>()
        call.service.assert().isEqualTo("eshop-basket-service")
        call.operator!!.id.assert().isEqualTo("alice")
        call.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        (call.command as SubmitOrder).items.single().productId.assert().isEqualTo("mug")
    }
}
```

- `called<C>()` 取命令类 `C` 中尚未交出的第一个调用，最多等 20 秒；`calls` 列出全部调用。
- 假对端按 `answer { call -> … }` 答复每次调用。默认的 `PeerAnswer.succeeded(id, version)` 把命令提交到调用的聚合，版本 1；没有指名聚合的创建则提交到一个新聚合。`PeerAnswer.rejected(code, message)` 用对端契约给这个错误码的状态答复：取它的错误表，领域拒绝没有声明时是 422。`PeerAnswer.timedOut()` 答 504 `wow.wait.timeout`，调用方可以用同一个请求 ID 再发一次。
- 没有指名服务（`Wow-Service`）或根命令（`Wow-Call`）的请求，它以 403 `wow.auth.forbidden` 拒绝，与对端的内部端口一样。

## 在真实存储上运行 {#run-it-on-real-stores}

内存后端用内存引擎从内存回答快照查询和事件查询，但不写数仓。测试存储的 schema、存储自己的查询引擎和数仓，要让服务运行在存储本身上，由 Testcontainers 启动它们。`me.ahoo.wow.service` 为它们注册了单独的测试套件 `integrationTest`，`check` 不会运行它，这样单元测试保持快速，也不需要 Docker：源码放在 `src/integrationTest/kotlin`，用 `./gradlew integrationTest` 运行。这个套件在 JUnit Jupiter 上运行，能用到模块的类以及 `testImplementation` 提供的一切；插件不再添加别的东西，测试需要什么，包括 Testcontainers，由构建脚本声明：

```kotlin
testing {
    suites {
        named<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
                implementation("org.testcontainers:testcontainers-postgresql")
            }
        }
    }
}
```

构建脚本在它的插件之后运行，所以它配置插件的套件（`named`），而不是再注册一个：同名的第二个套件会让构建失败。如果构建在插件应用之前就自己准备好了集成测试，即名为 `integrationTest` 的套件或源码集（来自先应用的约定插件，或来自根构建），插件会原样保留它们，什么也不注册。

测试启动容器，让服务运行在它们上面：

<!-- sample: examples/eshop/ordering/ordering-server/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingAnalyticsTest.kt#containers -->

```kotlin
private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
private val clickhouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).also { it.start() }
private val server = wowServer<OrderingServer>(
    "postgres",
    "clickhouse",
    "dev",
    properties = mapOf(
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        "wow.clickhouse.endpoint" to clickhouse.httpUrl,
        "wow.clickhouse.username" to clickhouse.username,
        "wow.clickhouse.password" to clickhouse.password,
        "wow.log.partitions" to 4,
    ),
)
```

- 应用的 `postgres` profile 定义了它的 `DataSource`，所以宿主选择 PostgreSQL；`clickhouse` profile 打开数仓。
- 存储在启动时创建它们的 schema（`wow.ddl: auto`），所以一个全新的容器不需要别的东西。
- `wow.log.partitions=4` 让日志保持很小：测试不需要 64 个分区。

## 检查契约 {#check-the-contract}

Gradle 插件 `me.ahoo.wow.service` 从服务的模块合并出它的 Manifest，并在 `checkWowManifest`（`check` 的一部分）中与提交的基线 `manifest/baseline.json` 比较。在基线的兼容线（服务的主版本号）之内破坏客户端的改变会让构建失败，并列出每个破坏性改变及其路径；基线尚未记录的改变、缺少基线也会让构建失败。有意的改变之后，`updateWowManifestBaseline` 记录新的基线（记录兼容线而不是版本），并拒绝记录兼容线内的破坏；把它与改变一起提交，这样评审时就能看到客户端会看到什么。什么算破坏性改变，见[一份契约](../concepts/contract#compatibility)。

## 跨服务测试 {#test-across-services}

相互调用的服务以同样的方式一起测试：在测试的 JVM 中启动每个服务，让一个指向另一个的内部端口，再驱动整个流程。eShop 的 `:eshop-e2e` 模块就是这样每次运行两个服务的；它运行全部五个服务的测试套件让每个服务各在自己的 JVM 中运行，基于容器中的 PostgreSQL、Kafka 和 ClickHouse。
