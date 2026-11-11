---
title: 归属与访问
description: 决定聚合归谁所有，让每个所有者拥有一个聚合，在状态上声明访问属性，编写访问策略来说明哪些调用方能读、能改哪些聚合，并测试它。
outline: deep
---

# 归属与访问

每个聚合都属于它的租户。大多数聚合还属于某个人：订单属于它的买家，保存的视图属于保存它的人。Wow 把这一点记为聚合的**作用域**，即它的所有者和空间，它们是每个聚合都有的两个**访问属性**。默认情况下，调用方读取和修改它自己的所有者和空间里的聚合。状态还可以声明更多的访问属性，例如部门、文档共享给了哪些用户，这时由服务的**访问策略**根据调用方的声明，决定谁能读取、谁能修改每个聚合。本页选择聚合的归属、让每个所有者拥有某类聚合中的一个、改变所有者和空间、声明访问属性、编写并测试访问策略，并说明它在哪里生效。模型见[身份、租户与访问](../concepts/identity)。

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

没有自己访问策略的聚合，按**默认规则**即它的所有者和空间修改。第一条命令的调用方为 `OWNER` 聚合确定作用域。此后，调用方的所有者或空间与聚合不同时，聚合不在调用方的可及范围内，内核像回答对不存在的聚合的命令一样回答它：`404 wow.aggregate.not-found`，消息也相同，与读取它时的回答一致。调用方得不到关于它的任何信息：既不知道它存在，也不知道它是否已删除、版本是多少。可能创建该聚合的命令则得到 `403 wow.aggregate.forbidden`，因为创建是否成功本身就说明了聚合是否存在。调用方没有值的那一项不检查：

- **所有者级**调用方的作用域有所有者，只能接触它自己的聚合。
- **租户级**调用方没有所有者，例如员工，可以接触它租户的每个聚合；它在某个空间里行事时，限于这个空间。它能做什么由动作级授权决定，例如 CoSec 的授权。

`TENANT` 聚合不记录所有者，所以它的写入不对照所有者检查；调用方的空间仍然检查。谁能修改商品只由动作级授权决定：没有授权器时，路由放行的任何调用方都能发送它的命令（[授权命令](../operate/security#authorize-commands)）。有访问策略的聚合改由它的策略检查（[策略在哪里生效](#where-a-policy-applies)）。

## 谁能读取它 {#who-reads-it}

读取遵循同样的默认规则。查询准入把它加进这个聚合类型的每个查询，包括它的事件流；状态路由在状态读取和 trace 时检查它：

- `OWNER` 聚合在调用方的租户、所有者和空间之内读取，每一项都以调用方有值为前提：所有者级调用方读取自己的，租户级调用方读取整个租户。
- `TENANT` 聚合在调用方的租户和空间之内读取，从不加上所有者。租户里的每个调用方都能读取商品，购物者与员工一样。

所以 `TENANT` 不按角色限制读取。属于租户、但只有部分调用方可以读取的聚合，例如运维控制台列出的失败、分析目录的数据源，需要服务自己的读取授权器：一个 `ReadAuthorizer` bean，拒绝没有该角色的调用方（[授权读取](../operate/security#authorize-reads)）。调用方能读到其中哪些聚合，由访问策略决定（[编写访问策略](#write-an-access-policy)）。受信代码和订阅的处理函数不带调用方地读取整个租户，既不询问读取授权器，也不询问访问策略。

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

- **所有者级**调用方的聚合就是它自己的：ID 是它的所有者，无论走不带 ID 的路由还是用它自己的 ID；通过 `Wow-Scope` 在另一个作用域中行事的调用方，接触的是那个作用域的所有者的聚合。指明另一个所有者的 ID 接触不到任何聚合：创建命令被以 `403 wow.aggregate.forbidden` 拒绝，其他命令得到 `404 wow.aggregate.not-found`，无论那个所有者的聚合是否存在。
- **没有所有者**的调用方，例如租户级的员工、服务或 Saga，没有自己的聚合：它在按 ID 的路由上指明 ID。它走不带 ID 的路由时得到 `400 wow.command.malformed`（"This caller has no owner; address the aggregate by its id."），读取状态时得到 `400 wow.query.malformed`。未认证的调用方得到 `401 wow.auth.unauthenticated`。
- Saga 派生的命令或定时命令没有指明 ID 时，取它所带作用域的所有者：跟随买家订单的 Saga 接触的是那个买家的购物车。

内核把聚合的 ID 记为它的所有者，无论是谁创建它；所有者不同的命令接触不到它，创建命令也一样。因此命令函数不必核对调用方：上面的购物车不需要自己的所有权检查。

## 在命令中核对调用方 {#check-the-caller-in-a-command}

作用域和访问策略都不够用的地方，命令函数接收 `Command<X>`，读取 `command.scope`（调用方的所有者和空间，按聚合的所有权保留：租户级聚合看不到所有者），并用自己的错误码拒绝命令（[读取准入后的命令](./aggregates#read-the-admitted-command)）。

## Saga 发出的命令 {#commands-a-saga-sends}

Saga 派生的命令、补偿和定时命令，带着它们所跟随的那次提交的作用域和操作人。所以 Saga 创建的聚合属于同一个所有者：购物车结账创建的订单属于购物车的买家。ID 即所有者 ID 的聚合则以这个 ID 作为所有者（[每个所有者一个聚合](#one-aggregate-per-owner)）。这些命令是服务自己的，不询问访问策略（[哪些是受信的](#what-is-trusted)）。见[编写 Saga](./sagas#build-the-commands)。

## 转移所有者或空间 {#transfer-an-owner-or-a-space}

两条生命周期命令改变聚合的作用域；除非 `@AggregateRoot(lifecycle = [...])` 把它们排除在外，每个聚合都处理它们：

| 命令 | 路由 | 请求体 |
| --- | --- | --- |
| `TransferOwner` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `{"ownerId": "…"}` |
| `TransferSpace` | `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `{"spaceId": "…"}` |

`TENANT` 聚合不处理 `TransferOwner`，ID 即所有者 ID 的聚合也不处理。每次提交都记录提交之后的作用域，所以投影或查询不用重放历史就能读到当前的所有者，聚合更早的事件流也随它而变（[策略在哪里生效](#where-a-policy-applies)）。

## 声明访问属性 {#declare-access-attributes}

访问属性是状态中决定谁能读取、谁能修改这个聚合的事实：部门、文档共享给了哪些用户、视图保存在哪个应用里。把状态的成员标注为 `@Access`。视图存储把每个保存的视图限于保存它的应用：

<!-- sample: platform/view-store/view-store-domain/src/main/kotlin/me/ahoo/wow/viewstore/domain/view/ViewState.kt#access -->

```kotlin
/**
 * The application it was created under: its creation's request fact `app_id`. An access attribute: each caller
 * reads the views of its own application.
 */
@Access
var appId: String = ""
    private set
```

- **标注什么。** 状态类的一个属性（存储的或计算的），或它的一个无参函数，继承来的也算，但不能是 private 或扩展。
- **状态的纯函数，开销小。** 每次提交都会派生每个属性，所以这个成员只读取状态：不做 I/O，不读时钟，不取随机数。编译器拒绝挂起的或标注 `@Blocking` 的成员。
- **它的值。** `String`、`Int`、`Long`、`Boolean` 或枚举，可空表示没有值；或者包装其中之一的值类，按它包装的值发布；或者它们的 `List`、`Set`、`Collection`（集合与元素都不可空），表示有多个值的属性。枚举按 Jackson 为其常量写出的名字发布。小数、映射、数组和嵌套集合不能作为属性。
- **它的名字。** `@Access("name")`，否则取成员的名字。采用小驼峰：一个小写字母，后跟字母和数字，至多 40 个字符，在聚合内忽略大小写唯一，并且不能是 `owner` 或 `space`，它们是每个聚合都有的内置属性。
- **它怎样改变。** 只随状态改变。要授予访问，就编写一条领域命令和它的事件（`ShareDocument`、`DocumentShared`），由领域校验，由日志记录。没有生命周期命令能设置属性。

编译器把每个属性写进其聚合在 Manifest 中的 `access` 一节（名字，类型 `string`、`integer` 或 `boolean`，以及是否有多个值；见 [Manifest](../reference/manifest)），并在状态旁生成 `<State>Access`：一个对象，为每个属性持有一个有类型的引用，按属性名以大写下划线命名（`appId` 对应 `ViewStateAccess.APP_ID`），还有内置的 `OWNER` 和 `SPACE`。删除、改名或改变属性的类型，或者改变它是否有多个值，都是契约的破坏性变更；新增属性则不是。以代码编写的模型用 `access(attribute) { state -> … }` 声明每个属性。

每个快照、每条事件流都把它的聚合的属性记为 `access`，查询以 `access.<name>` 指明其中一个（[查询语言](../reference/query-language)）；OpenAPI 文档和生成的 TypeScript 客户端按聚合声明的属性为它定类型。

## 编写访问策略 {#write-an-access-policy}

访问策略针对一个调用方，说明它能读取一种聚合中的哪些、能修改其中的哪些。每个聚合声明一个，作为其状态的 `AccessPolicy<S>` 类型的 Bean，由 `accessPolicy { … }` 构建。视图存储的策略：调用方读取它自己的应用里、它自己的所有者和空间之内的视图、偏好和数据集措辞，修改它的所有者和空间之内的那些：

<!-- sample: platform/view-store/view-store-server/src/main/kotlin/me/ahoo/wow/viewstore/server/ViewStoreConfiguration.kt#access -->

```kotlin
@Bean
fun viewAccess(): AccessPolicy<ViewState> = accessPolicy {
    read { caller -> allOf(ViewStateAccess.APP_ID eq caller.app, ownerAndSpace(caller)) }
    write { caller -> ownerAndSpace(caller) }
}

@Bean
fun preferencesAccess(): AccessPolicy<ViewPreferencesState> = accessPolicy {
    read { caller -> allOf(ViewPreferencesStateAccess.APP_ID eq caller.app, ownerAndSpace(caller)) }
    write { caller -> ownerAndSpace(caller) }
}

@Bean
fun wordingAccess(): AccessPolicy<DatasetWordingState> = accessPolicy {
    read { caller ->
        allOf(
            DatasetWordingStateAccess.APP_ID eq caller.app,
            ownerAndSpace(caller)
        )
    }
    write { caller -> ownerAndSpace(caller) }
}
```

- **`read` 与 `write`。** 策略两者都要给出：`read { caller -> … }` 说明调用方能读取什么，`write { caller -> … }` 说明它能修改什么，`readWrite { caller -> … }` 给出两者共用的一个谓词。没有什么是因省略而授予的，所以没有 `write` 的策略在构建时就被拒绝：`write { denyAll() }` 明确表示没有调用方能修改这个聚合。
- **它取代默认规则。** 调用方的所有者和空间只在策略比较它们的地方才比较。`ownerAndSpace(caller)` 就是默认规则，用来与策略自己的比较组合。它按聚合的所有权保留的作用域比较：属于租户的聚合不记录所有者，所以只比较空间，上面的措辞就是这样。
- **一种状态的多个聚合。** 服务的几个聚合共用策略的状态类型时，策略指明它管辖哪一个：`accessPolicy(aggregate = …)`。

谓词通过 `<State>Access` 的引用，用调用方的声明比较聚合的属性：

| 谓词 | 成立的条件 |
| --- | --- |
| `A eq value`、`A isIn values` | 单值属性 `A` 等于该值，或是其中之一 |
| `A startsWith prefix` | 单值字符串属性 `A` 以该前缀开头：物化路径的一层，例如 `/acme/sales` |
| `A.isNull()` | 聚合没有单值属性 `A` 的值 |
| `M contains value`、`M containsAny values` | 多值属性 `M` 的值包含该值，或包含其中之一 |
| `M.isEmpty()` | 多值属性 `M` 没有值 |
| `allOf(…)`、`anyOf(…)`、`not(p)` | 每个谓词都成立（一个也没有时总成立）、有一个成立（一个也没有时从不成立）、`p` 对聚合已有的值不成立 |
| `allowIf(condition)` | 只关于调用方的条件（例如角色）成立时为每个聚合，否则为没有 |
| `allowAll()`、`denyAll()` | 每个聚合，明确表示公开的数据；没有聚合 |

调用方是宿主为请求解析出的那个（[认证调用方](../operate/security#authenticate-callers)）。策略读取 `caller.subject`、`caller.app` 和 `caller.scope`，并按名字读取它的声明：`caller.claim("org")` 是一项声明的第一个值或 null，`caller.claims("departments")` 是它的值或空，`caller.hasRole("auditor")` 是它的 `roles` 声明是否含有该角色。

**缺失即拒绝。** 与调用方没有的声明（null 或没有值）比较，正反都不成立；与聚合没有的值比较也是如此。`not` 只对聚合已有的值成立，所以无论谓词怎样取反，缺失的值和缺失的声明都不会授予访问。公开的数据要在策略里说明：`allowAll()`，或者在 `anyOf` 中写 `A.isNull()`。

一份文档的策略：它的部门读取并修改它，它共享给一些用户，由一个人所有，并由一个角色审计：

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/AccessPolicySpecTest.kt#policy -->

```kotlin
private val documentAccess = accessPolicy<Document> {
    read { caller ->
        anyOf(
            DocumentAccess.DEPARTMENT isIn caller.claims("departments"),
            DocumentAccess.SHARED_WITH contains caller.subject,
            DocumentAccess.OWNER eq caller.subject,
            allowIf(caller.hasRole("auditor")),
        )
    }
    write { caller -> DocumentAccess.DEPARTMENT isIn caller.claims("departments") }
}
```

### 默认拒绝与启动检查 {#default-deny-and-the-startup-checks}

状态声明了访问属性的聚合只通过它的策略读取和修改：节点有这样的聚合却没有它的策略时拒绝启动，并指出这个聚合和它的属性。没有声明属性的聚合沿用默认规则；它仍然可以有策略，此时策略只比较它的所有者、空间和调用方的声明。

节点在启动时按 Manifest 一次性解析它的策略，遇到以下情况拒绝启动：

- 策略的状态不是服务中任何聚合的状态；
- 策略的状态由几个聚合共用，策略却没有指明它管辖哪一个，或者指明的聚合并不以它为状态；
- 一个聚合有两个策略。

只承载服务部分上下文的节点，略过由其他节点承载的聚合的策略。策略的引用按它的状态定类型，所以比较另一种状态的属性无法编译；比较其聚合没有声明的属性（按另一个版本的状态构建的策略）则什么也匹配不到，节点会记录日志。

## 策略在哪里生效 {#where-a-policy-applies}

策略取代默认规则，决定它的聚合的每次读取和每条命令；没有策略的聚合，在同样的路径上由默认规则决定。

**读取。** 每次读取都以同样的方式准入：

- **快照查询**（列表、分页、计数、聚合运算及它们的流）把策略的 `read` 作为存储执行的过滤条件（[查询快照](./queries#who-sees-what)）。
- **状态读取**（包括按版本和按时刻的读取）和 **trace** 都在聚合的最新状态上判定，所以调用方现在能读取的聚合，它能读取全部历史；现在不能读取的，历史也一概读不到。被隐藏的聚合答 `404 wow.aggregate.not-found`，与不存在的聚合完全一样（[读取状态](./reading-state#who-sees-a-state)）。
- **事件查询**按每条流记下的其聚合当前的属性判定。一次提交改变了属性时，内置订阅 `wow.access` 把新的属性盖章到该聚合更早的流上，通常在几秒之内完成，所以转移或撤销共享对全部历史生效。尚未盖章的流在策略下对任何调用方都不显示（[查询事件流](./event-queries#who-sees-what)、[重建与重放](../operate/replay#access-attributes)）。
- **绑定到该聚合的数据集**按每一行所属聚合当前的属性读取（[发布数据集](./datasets#rows-of-an-aggregate-with-an-access-policy)）。

读取授权器最先被询问，决定调用方能否读取这个模型（[授权读取](../operate/security#authorize-reads)）；接着由策略决定它读到哪些聚合，再由状态模式的掩码决定它看到其中哪些字段（[描述类型说明不了的东西](./queries#describe-what-types-cannot)）。

**命令。** 内核在它本就持有的聚合当前属性（包括所有者和空间）上对策略求值：

- **决定之前，** `write` 允许调用方修改聚合时，命令才执行。被拒绝的调用方，若 `read` 也对它隐藏该聚合，得到 `404 wow.aggregate.not-found`；若它能读取，得到 `403 wow.aggregate.forbidden`。创建命令得到 `403`，因为它是否成功本身就说明了聚合是否存在。
- **决定之后，** 创建，以及改变了聚合声明的属性的决定，会在它留下的聚合上再检查一次：调用方无法修改这个结果时以 `403` 拒绝，不追加，所以没有调用方能把聚合创建到、或移动到它自己够不着的地方。所有者或空间的转移只在决定之前检查，因为它就是要把聚合交出去。

命令授权器（例如 CoSec 的）仍然决定调用方能否发送这条命令（[授权命令](../operate/security#authorize-commands)）。

## 哪些是受信的 {#what-is-trusted}

平台内部是受信的：内部端口、集群通道、命令主题和存储只有平台自己的服务能到达，调用方从公开端口进入（[内部端口](../concepts/identity#the-internal-port)）。所以服务自己做的事不询问访问策略：

- **派生的命令。** Saga、定时器、补偿或同步调用派生的命令，带着它所跟随的提交的因果和根命令的作用域，根命令在到达时已由准入授权。它能接触每个聚合。
- **以自身身份行事的服务。** 服务的身份（`Caller.ofService(...)`）读取和发送命令时不受策略约束。另一个服务在内部端口上调用或读取本服务时，受本服务调用方清单的约束（`wow.callers.<service>.commands` 与 `reads`；[关上内部端口](../operate/security#close-the-internal-port)）。服务的代码以服务身份发送命令：`commandClient.of(Caller.ofService("<wow.service>"))`；进程内客户端的默认调用方是匿名的，没有任何声明，会被策略拒绝。
- **受信代码。** 不带调用方读取的代码（`NodeQueryApi.trusted()`）和订阅的处理函数读取整个租户。

既没有因果、也没有调用方的命令，例如生产者在命令主题上写入时没带调用方的，在策略下被拒绝（[限制命令主题](../operate/security#restrict-the-command-topics)）。被允许在另一个作用域中行事的调用方，改变的是它在哪里行事，而不是策略授予什么：必须读取每个聚合的客服角色，在策略里有它自己的规则（`allowIf(caller.hasRole("support"))`）。

## 测试策略 {#test-a-policy}

测试 DSL 的 `accessPolicySpec(model, policy)` 像内核和查询那样对策略求值来检查它：给定事件之后的聚合（`given(…)`）或某个状态下的聚合（`givenState(state, scope)`），检查每个调用方能做什么。`whenCaller(subject, claims, roles)` 指明一个调用方，`expectRead()`、`expectNoRead()`、`expectWrite()` 和 `expectNoWrite()` 检查它；内置的所有者和空间就是给定聚合时的作用域：

<!-- sample: test/test-dsl/src/test/kotlin/me/ahoo/wow/test/AccessPolicySpecTest.kt#spec -->

```kotlin
@Test
fun `a caller reads and changes what the policy grants on the aggregate its events leave`() =
    accessPolicySpec(model, documentAccess) {
        given(Created("sales"), Shared("bob"))
        whenCaller(subject = "bob", claims = mapOf("departments" to listOf("hr"))) {
            expectRead()
            expectNoWrite()
        }
        whenCaller(subject = "carol", claims = mapOf("departments" to listOf("sales"))) {
            expectRead()
            expectWrite()
        }
        whenCaller(subject = "dave") {
            expectNoRead()
            expectNoWrite()
        }
        whenCaller(subject = "erin", roles = listOf("auditor")) { expectRead() }
    }
```

模型就是 `aggregateSpec` 接收的那个，策略取自 Bean 函数。策略比较了模型没有声明的属性时，检查同样失败。在 `aggregateSpec` 中，`expectAccess(mapOf(…))` 检查命令之后聚合的属性（[测试领域代码](./testing#expect)）。

## 固定的租户 {#a-fixed-tenant}

所有聚合都放在同一个租户里（无论调用方是哪个租户）的聚合类型，从宿主获取租户：`wow.aggregates.[<context>.<aggregate>].tenant`。

## 在另一个作用域中行事 {#act-in-another-scope}

被允许在另一个租户或作用域中行事的调用方，例如客服工具，在 `Wow-Scope` 请求头中指明它，或者在命令客户端的 `CommandRequest`（`targetTenant`、`targetScope`）中指明。提交会记录操作人是在另一个作用域中行事的。它在那里读取和修改什么，仍由访问策略决定（[哪些是受信的](#what-is-trusted)）。
