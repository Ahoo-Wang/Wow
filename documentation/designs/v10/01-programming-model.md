# V10 领域编程模型

日期：2026-10-11。基线：`main` `b5813af56f`。状态：草案。上位文档：[00 目标架构](00-architecture.md)（A3、A4、A6）。相关决定：D7、D8、D9、D22、D36、D37、D38、D39、D49。

## 1. 五类函数

| 函数 | 签名 | I/O | 失败语义 |
|---|---|---|---|
| Decide | `DecideScope.(S, C) -> Decision`，可以是同步、`suspend` 或 Reactor（§4） | 允许：依赖在构造期绑定，副作用的补偿经 `DecideScope` 登记（D49） | 命令被拒绝；不写入 |
| Evolve | `S.(E) -> Unit`（可变）或 `(S, E) -> S`（不可变） | **禁止**：非 `suspend`，上下文里没有依赖 | 模型缺陷：快速失败 |
| React | `(StateEvent[, ReactContext]) -> Unit`，三种调用形态同 Decide | 允许，自行幂等 | 进入失败台账，停放该聚合 |
| Saga | `(StateEvent[, SagaContext]) -> List<Command>`，三种调用形态同 Decide | 允许 | 派生命令请求 ID 确定。同上下文的命令要等到 `COMMITTED`，跨上下文的命令等到持久 `ACCEPTED`（D33），Saga 才算完成；派生命令被拒绝视为处理失败，进入失败台账，除非 Saga 声明该拒绝是预期结果 |
| Project | `(StateEvent[, ProjectContext]) -> Unit`，三种调用形态同 Decide | 允许，写入按版本守卫 | 同 React |

- React、Saga、Project 的上下文提供确定性的 `executionId = (订阅, AggregateRef, 版本)`，用户拿它给外部调用做幂等键（KaeX mvp 的 `executionId` 做法）。
- `Decision` 有三种：`Events(list)`、`NoOp` 与 `Rejected(Rejection)`。拒绝是一个值，不抛异常；一个返回类型表达全部结局（实现时由「`Decision` 加单独的 `Rejection` 返回」收敛而来）。
- `@OnError` 删除：错误路径就是返回 `Rejection` 的分支；失败后的补偿动作写成 Saga。
- 9.x 的证据：处理器运行时发现、参数无法解析时注入 `null`（B11、B15）；`@OnError` 在重试循环内执行（B4）。

## 2. 状态：可变与不可变都支持

不可变状态的好处是真实的，但它的代价落在每一行用户代码上：嵌套集合的更新要层层 `copy`，像前端 React 的 reducer 一样冗长。按「领域模型之外的每一行代码都是 Wow 欠用户的」，这个代价不应由用户承担。逐一看不可变状态带来的好处，内核都能代为保证：

| 好处 | 不可变状态的做法 | 可变状态下内核的做法 |
|---|---|---|
| 失败不污染状态 | 旧值天然不变 | 演化或追加失败时丢弃该实例，从缓存外重新加载（9.3 已实施并验证，Q14）。失败是少数路径，成本只落在失败时 |
| 并发安全 | 值可以共享 | 每个聚合一个邮箱，同一时刻只有一个协程访问该实例 |
| 每个版本有稳定的视图 | 每个版本是不同的值 | 提交时把状态序列化一次（快照与 Sink 本来就需要），作为该版本的不可变视图；订阅与快照只拿这个视图，不拿活的实例 |

所以 V10 两种写法都支持，**可变是默认的惯用写法**：`evolve` 以状态为接收者、直接修改字段；喜欢不可变的用户把 `evolve` 写成返回新状态的函数，KSP 按签名识别。测试 DSL 对两者一样：given 事件，when 命令，then 事件与状态。

**可变状态的代价**（对抗验证 F4）：没有任何东西阻止 decide 或 after 钩子直接修改状态，这样的修改不经过事件，内存状态会悄悄偏离日志，重启后又「自愈」，极难排查（9.x 同样存在）。V10 的防线：测试 DSL 在每次 decide 前后比较状态的序列化结果，发现修改就让测试失败；运行时提供一个调试开关做同样的检查；编译器对注解写法中 decide 直接给状态字段赋值的代码报警告。

## 3. 两种写法，一个模型

```kotlin
// Decider DSL：Kotlin 一等写法
val cart = aggregate<CartState>("example", "cart") {
    initial { CartState(it) }
    decide<AddCartItem> { state, cmd ->
        if (state.items.size >= MAX) reject(CartFull)
        else emit(CartItemAdded(cmd.productId, cmd.quantity))
    }
    evolve<CartItemAdded> { e -> items[e.productId] = e.quantity }   // 可变：this 是 CartState
}

// 注解：由 KSP 编译成同一个模型
@AggregateRoot
class Cart(private val state: CartState) {
    @OnCommand
    fun onCommand(cmd: AddCartItem): CartItemAdded = /* ... */
}

class CartState(val id: String) {
    val items = mutableMapOf<String, Int>()

    @OnSourcing
    fun onSourcing(e: CartItemAdded) { items[e.productId] = e.quantity }
}
```

两者都编译成同一个 `AggregateModel`：命令表、演化表、身份规则、创建语义、守卫。注解写法由 KSP 在编译期生成绑定代码，运行时不扫描、不反射（D37）。Java 用户照样写注解：KSP 能分析 Java 源文件，构建里加上 Kotlin Gradle 插件与 KSP 即可；不想引入 Kotlin 插件的 Java 项目，用 Builder 写 Decider（D8）。V10 的注解是重新设计的，不沿用 9.x 的注解；9.x 代码由迁移 Skill 改写（[07](07-migration.md)）。

**注解写法怎样绑定**（H4 的结论，D49）：

- 命令处理函数的第一个参数是命令体 `X`，或者被准入的命令 `Command<X>`；后者带着准入得到的请求事实：所有者、空间、请求头（例如调用方应用）。这些事实不进命令指纹。
- 其后的参数是领域服务，成为生成的工厂函数 `xxxModel(context, 服务…)` 的参数，由宿主在构建模型时绑定一次；`DecideScope` 类型的参数传入本次决定的作用域。依赖因此在构造期绑定，运行时不按类型解析。
- 返回给调用方的附加结果（9.x 的 `CommandResultAccessor`）经 `DecideScope.putResult` 写入，随提交结果返回，不持久化，所以重复请求得到的回答不含它。
- 创建语义三态：`@CreateAggregate` 必须新建，`@AllowCreate` 新建或已有皆可，其余只作用于已有聚合。
- 非 private 的函数都会被找到（Java 的包级可见函数也算），生成代码与聚合根同包。
- 9.x 框架类型（`CommandMessage`、`ServerCommandExchange`、`CommandResultAccessor` 等）不能出现在 V10 的签名里，编译期报错，由迁移 Skill 改写成上面的形式。

**V10 注解集**（D67）。H4 为了度量覆盖率，直接绑定了 9.x 的注解；V10 的注解集从 38 个收敛为下表，KSP 只认它们，9.x 注解由迁移 Skill 映射：

| 注解 | 用途 | 取代 9.x 的 |
|---|---|---|
| `@BoundedContext` | 声明限界上下文 | 同名 |
| `@AggregateRoot` | 聚合根；状态类可分离 | 同名 |
| `@OnCommand`、`@OnSourcing` | 非约定命名的命令函数、溯源函数（约定名 `onCommand`、`onSourcing` 不需要注解） | 同名 |
| `@CreateAggregate`、`@AllowCreate` | 创建语义三态（`Creation`） | 同名 |
| `@AfterCommand` | after 钩子（D39） | 同名 |
| `@Name`、`@Revision`、`@Alias` | 命令与事件的名字、事件的 revision 与旧名（D22、D55） | `@Name`、`Revision` |
| `@Projection`、`@Reactor`、`@Saga` | 订阅处理器的种类 | `@ProjectionProcessor`、`@EventProcessor`、`@StatelessSaga` |
| `@OnEvent` | 订阅处理函数，唯一一个；种类由所在类的注解决定，每个函数编成一个订阅（[04](04-subscriptions.md) §1） | `@OnEvent`、`@OnStateEvent`、`@OnMessage` |
| `@Blocking` | 阻塞调用，派发到虚拟线程（§4） | 同名 |
| `@Description`、`@Summary` | 写进 Manifest 的说明 | 同名 |
| `@WowSpi` | 标注可替换的端口 | 同名 |

删除且没有对应物：`@OnError`（改用 Saga 补偿）、`@VoidCommand`、`@PreparableKey`（改用 `DecideScope.reserve`）、`@Retry`（改为订阅配置）、`@Order`、`@EntityObject`、`@ValueObject`、`@Generated`、`@OwnerPolicy`、`@Spaced`、`@AggregateRoute`、`@CommandRoute`（路由由 Manifest 物化，[05](05-read-and-contract.md) §3）。

**命令体里不放身份**（D68）：命令字段上的 `@AggregateId`、`@TenantId`、`@OwnerId`、`@AggregateVersion` 删除。聚合身份在 `Command.ref`，来自路径（HTTP）或命令消息本身（Kafka、Saga 派生），所有者与空间来自认证主体（[05](05-read-and-contract.md) §3）；命令体只放业务数据。创建命令要由客户端指定 ID 时用带 `{id}` 的路由，不带则由服务端生成。迁移 Skill 从 9.x 命令里删去这些字段，并把读取它们的代码改为读 `Command<X>.ref`。

## 4. 调用形态与快速路径

很多处理函数是纯计算：一条命令只凭当前状态就能算出事件，一个投影只是把字段搬进内存结构。它们不该为协程付费。

**三种调用形态，编译期选定。** KSP 按签名为每个处理函数生成一种调用，运行时没有任何判断：

| 形态 | 识别方式 | 生成的调用 |
|---|---|---|
| 同步 | 普通函数 | 直接调用：没有续体分配，没有调度器切换，在邮箱的工作线程上内联执行 |
| `suspend` | `suspend` 修饰 | 在当前协程里调用，只在函数真正挂起时让出线程 |
| Reactor | 返回 `Mono`、`Flux`、`Publisher` | 经 `wow-reactor` 转换（§5） |

**同步不等于不阻塞。** 同步函数默认被视为非阻塞的纯计算，内联在共享工作线程上执行。做阻塞 I/O 的同步函数（例如用 JDBC 写投影）必须标 `@Blocking`（沿用 9.x 的注解与语义），生成的调用把它派发到阻塞调度器；JVM 基线升到 21 后（D29），阻塞调度器用虚拟线程。未标注却阻塞的函数会卡住同一工作线程上的其他聚合，由观测接缝统计处理耗时并告警。

**内核本身也只在真正的 I/O 处挂起。** 状态缓存命中、处理函数为同步时，一条命令从邮箱取出到追加之前全是直线调用，唯一的挂起点是追加；内核实现不得在这条路径上插入 `withContext` 或调度器切换。冲突重试时会重新调用 decide，所以纯计算的同步 decide 让重试也几乎没有成本。

H3 的基准分别测同步与 `suspend` 处理函数，作为这条快速路径的验收。

## 5. Reactor 写法：只做适配

协程是内核与 SPI 的唯一模型（D9）。用户的编程模型仍然可以用 Reactor，由独立模块 `wow-reactor` 适配，内核不感知：

- **处理函数**：Decide、React、Saga、Project 可以返回 `Mono`、`Flux` 或 `Publisher`。KSP 在编译期识别返回类型，生成经 `wow-reactor` 转换的调用；类路径上没有 `wow-reactor` 时编译报错。
- **调用方**：`wow-reactor` 提供 `ReactiveCommandGateway` 与 `ReactiveQueryGateway`，是协程网关的薄包装。Java 用户可以用它，或用 `CompletableFuture` 形式。
- **边界**：只适配用户编程模型。SPI（日志、快照、租约）只有协程形式；存储实现者需要桥接 Reactor 驱动时，自己在实现里用 kotlinx-coroutines-reactor。
- **上下文**：`ReactorContext` 在适配时传递，返回 `Mono` 的处理函数仍能读到它。

## 6. 内核提供的部分

- **after 钩子**（D39）：Decide 的一部分，用于在命令函数之后追加事件（例如对一组命令统一补一个事件）。按 include/exclude 匹配命令类型，按声明顺序执行；看到的状态与命令函数相同（尚未演化）；它的事件拼在命令函数的事件之后，组成**同一条**事件流，随后一起演化、一次追加。调用形态与 Decide 相同（§4），可以做 I/O；任何一个失败，整条命令失败，不写入。语义与 9.x 的 `@AfterCommand` 一致（`CommandEntry.invokeWithAfter`）。
- **生命周期事件**：删除、恢复、转移所有者、转移空间、打标签，是每个聚合都有的系统命令与系统事件，由内核提供 decide 与 evolve，作用于快照元数据（[03](03-log-and-snapshot.md)）；用户可以覆盖。
- **身份**：`AggregateRef(上下文, 聚合, 租户, ID)`，由准入决定一次（[02](02-write-path.md)）。
- **授权**：在准入里，与查询准入共用策略接口；内核不做授权。

## 7. 跨聚合的唯一性：预留键

「用户名在租户内唯一」这类约束跨越聚合实例，单个聚合的版本唯一键管不到。9.x 用 `PrepareKey` 解决：在 Mongo 或 Redis 上持久地预留一个键，支持预留、重新预留、回滚与过期（`infra/prepare`）。V10 保留这个能力（D38）：

- 作为 `@WowSpi` 端口，由日志后端（PostgreSQL、MongoDB）实现，与日志同库，不引入新的存储依赖。
- 在 Decide 里经 `DecideScope` 使用（Decide 允许 I/O）：先预留，再产出事件；命令被拒绝或追加失败时，回滚本次命令做的预留。
- 用法：`suspend` 的 decide 调用 `DecideScope.reserve(命名空间, 键)`，返回 false 即键已被其他聚合占用；预留归聚合所有（`AggregateRef`），所以 Saga 改名时知道该释放谁的键。
- 回滚建立在一个通用机制上：`DecideScope.onAppendFailure { … }` 登记「追加没有成功时」要执行的补偿，Decide 里做的任何外部副作用都可以用它（KaeX mvp 的 `CommandUnitOfWork`）；预留键是它的第一个使用者。
- KaeX mvp 曾为稳住核心把 `PrepareKey` 整体移除，那是范围取舍，不是否定这个能力；9.x 用户在用，所以 V10 保留（D38）。
- 释放与转移（例如改名）由领域事件驱动的 Saga 完成，失败进入失败台账。
- **崩溃不泄漏预留**（R8）：`onAppendFailure` 只在进程活着时执行；在预留之后、追加之前崩溃，补偿永远不会跑，键会被永久占住。所以 Decide 里做的预留先是「待确认」状态并带短过期时间（9.x 的 `PrepareKey` 本来就有过期），追加成功后由 `afterAppend` 确认为永久；崩溃留下的只是一个会自动过期的待确认预留。
- **提交过的预留不会过期丢键**（D50）：追加成功、确认之前崩溃（或确认失败），待确认预留会过期，键被别人拿走，而占用它的事实已经提交。所以本次尝试的预留随事件流写入日志条目的传输元数据，内置订阅 `reservation` 按条目确认（幂等）；`afterAppend` 只是快路径。
- **附加结果不持久化**（D51）：`putResult` 只随本次提交返回给调用方；重复提交得到「已提交」而不含附加结果，需要可重取的结果应写进事件。

## 8. 领域事件的演进

日志永久保存，领域事件结构必然演进，所以「读旧事件」是领域模型的常态，不是框架的兼容债（9.x 的 `EventUpgrader` 就是这个需求）。

- 事件类型声明 `revision`；结构变化时，用户写一个从上一个 revision 到当前 revision 的升级函数，由 KSP 串成链，读日志时按 revision 升级。
- **事件类型的身份是名字，不是类名**：`上下文.聚合.事件名`（可用注解指定），所以重命名或移动类不影响读取历史事件（KaeX 检查表 C9）。
- Manifest 记录每个事件类型的 revision 历史。
- 与迁移层的分工（D22）：**框架**格式的不兼容变化只在迁移层处理；**领域**事件的 revision 属于用户模型。
- **加法不是格式变化**（R5-1）：「运行时只读当前格式」若理解为任何改动都要迁移，框架格式在主版本之间就冻结了。所以只有不兼容的变化才升格式版本、进迁移层；新增带默认值的可选字段、新增枚举值之外的扩展点，是同一格式版本内的加法，新旧代码都能读。

## 9. 待细化

- DSL 的完整清单（守卫）；注解集见 §3（D67）。
- Java Builder 的形状（D8 的退路）；纯 Maven 的 Java 项目如何接入 KSP。
- 可变状态丢弃重载的范围：只丢该聚合的实例，还是连同它所在邮箱的在途命令一起重试。
- 升级函数能否删除字段、拆分事件、丢弃事件。
