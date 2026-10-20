---
title: V10 编译器生成什么
description: Wow V10 编译器生成的模型工厂、处理器绑定、ModelProvider 绑定索引、Manifest 片段与路由。
outline: deep
---

# V10 编译器生成什么

`wow-compiler` 是一个 KSP 处理器。在 `generate` 模式下（Gradle 插件会设置它），它读取领域模块中带注解的类，把 Kotlin 源码与资源写到 `build/generated/ksp/main/`。运行时不靠扫描或反射发现任何东西：宿主加载编译器写出的内容。编译器无法绑定的模型会让编译失败，错误信息指出函数与违反的规则。

对 eShop 的 [`eshop-ordering-domain`](https://github.com/Ahoo-Wang/Wow/tree/v10/example/eshop/ordering/ordering-domain) 模块，编译器写出：

```text
build/generated/ksp/main/
├── kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/
│   ├── EshopOrderingDomainModelProvider.kt   绑定索引
│   ├── OrderModel.kt                         fun orderModel(context, paymentPolicy, configure)
│   ├── OrderProcessSagaSubscriptions.kt      fun orderProcessSagaHandlers(dependencies)
│   └── OrderStateProperties.kt               状态的查询字段（有 wow-query-api 时）
└── resources/META-INF/
    ├── services/me.ahoo.wow.kernel.ModelProvider
    └── wow/manifest/eshop-ordering-domain.json   Manifest 片段
```

## 模型工厂

每个聚合根在它的包里得到一个顶层工厂函数，以根命名：`<root>Model`，首字母小写（`Order` 得到 `orderModel`）。

```kotlin
public fun orderModel(
    context: String,
    paymentPolicy: PaymentPolicy,
    configure: AggregateModelBuilder<OrderState>.() -> Unit = {},
): AggregateModel<OrderState>
```

- **`context`** 总是第一个参数，即使模块只声明了一个 `@BoundedContext`：绑定索引传入它编译时的上下文。测试自己传入：`orderModel("eshop-ordering", paymentPolicy)`。
- **服务**随后，命令函数或 after 钩子需要的每个服务一个参数。宿主在构建模型时绑定一次。
- **`configure`** 在生成的绑定之后作用于模型构建器：通用扩展口，很少需要。

工厂通过内核的 DSL（`aggregate<S>(context, name) { … }`）构建模型：每条命令一个 `decide`（或 `decideSuspending`）并带创建规则，每个事件一个 `evolve`（或 `evolveTo`）并带 revision，每个 `@Upgrade` 函数一个 `upgrade`，以及旧名与生命周期命令。

## 处理器绑定

每个 `@Saga`、`@Projection` 或 `@Reactor` 类在它的包里得到 `<processor>Handlers(dependencies)`（`OrderProcessSaga` 得到 `orderProcessSagaHandlers`），文件名为 `<Processor>Subscriptions.kt`。它构造处理器一次，构造函数的参数按类型（与限定符）从 `dependencies` 取得，并为每个处理函数返回一个内核 `ProcessorBinding`，名为 `<processor>.<function>`，Saga 的 `@OnRejected` 处理函数绑定在其上。生成的代码只依赖 `wow-kernel`：运行时把这些绑定变成订阅。

## 查询字段：`<State>Properties`

模块的类路径上有 `wow-query-api` 时，每个聚合的状态还会得到 `<State>Properties`：快照查询能命名的 `QueryField` 组成的对象，包括系统字段与每条 `state.*` 路径，如 `state.items.productId` 对应 `OrderStateProperties.STATE__ITEMS__PRODUCT_ID`。查询 DSL 接受它们；见[查询语言](./query.md#生成的字段常量)。

## 绑定索引：`ModelProvider`

每个模块得到一个实现 `me.ahoo.wow.kernel.ModelProvider` 的类，登记在 `META-INF/services/me.ahoo.wow.kernel.ModelProvider` 中供 `ServiceLoader` 使用：

```kotlin
public class EshopOrderingDomainModelProvider : me.ahoo.wow.kernel.ModelProvider {
    override val context: String = "eshop-ordering"
    override val fragment: String = "META-INF/wow/manifest/eshop-ordering-domain.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(
        orderModel(context, dependencies.get(PaymentPolicy::class.javaObjectType)),
    )

    override fun processors(dependencies: Dependencies): List<ProcessorBinding<*>> =
        listOf(orderProcessSagaHandlers(dependencies)).flatten()
}
```

- **类名**来自模块（KSP 选项 `wow.manifest.module`，使用插件时是 Gradle 项目名）：`eshop-ordering-domain` 得到 `EshopOrderingDomainModelProvider`。不以字母开头的名字加前缀 `Module`。
- **包**是模块所有模型与处理器共有的最深的包。
- **依赖**按类型查找，带 `@Name` 的参数再按限定符查找。在宿主上，`Dependencies` 是应用的 Bean 工厂：带限定符的依赖是该名字的 Bean；不带的是该类型唯一的 Bean，或多个中的 primary Bean。缺少 Bean 时启动失败，并指出类型（`MissingDependencyException`）。

宿主加载类路径上的每个 `ModelProvider`（`ModelProvider.load()`），并提供它们构建的每个模型：默认由服务的类路径决定它提供哪些上下文。`wow.contexts.include` 与 `wow.contexts.exclude` 可以只选其中一部分（见[配置项](./configuration.md#服务与节点)）。

## Manifest 片段

编译器把模块在服务契约中的部分写到 `META-INF/wow/manifest/<module>.json`。它写出上下文，并为每个聚合写出：

| 字段 | 内容 |
| --- | --- |
| `name`、`description` | 聚合名与它的 `@Description` |
| `state` | 状态 Schema 的引用，补全后还有它的指纹 |
| `snapshot` | 聚合是否保存快照 |
| `commands` | 每条命令的 `name`、`aliases`、`schema`、`creation`（`REQUIRED`、`ALLOWED`、`NONE`）、`produces` 与 `routes` |
| `events` | 每个事件的 `name`、`aliases`、`revision` 与 `schema` |
| `lifecycle` | 它处理的生命周期命令（`wow.lifecycle.DeleteAggregate` 等） |
| `queries` | 它的查询模型（`snapshot`、`events`）及路由 |
| `schedules` | 它的命令函数可能定时的命令 |

在聚合之外，上下文还列出它的 `subscriptions`（每个处理函数及其 `kind`、`source`、`derives`、`rejected`、`schedules` 与 `start`）、它发布的流 `published`（`aggregate`、`topic`、`includeState`）与它的 `errors`（每个 `@ErrorCode` 及其 `status` 与 `description`）。

编译器把 Schema 写成引用（`#/$defs/<JVM 类名>`）。插件的 `generateWowManifestFragment` 补全它们：按 Jackson 的规则内省每个类型，把 JSON Schema（2020-12）写入 `$defs`，附带查询关键字（`x-wow-*`）与状态指纹。补全后的片段在模块资源中取代编译器写出的片段。快照查询从中读取状态 Schema，所以只有片段补全之后，状态字段才能用于筛选与排序。

## 服务的 Manifest

宿主启动时把类路径上的片段合并成服务的 Manifest，在 `GET /wow/manifest` 提供，框架的错误码位于顶层。插件的 `mergeWowManifest` 在构建期把同样的片段合并为 `build/wow/manifest.json`，以 `wow.serviceName` 命名，供其他服务作为对端（`wowPeers`）以及客户端生成器读取。

## 路由

宿主只依据 Manifest 构建路由；路由由编译器决定。

| 路由 | 来源 |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | 每条命令；`<command>` 是命令名的 kebab case（`ChangePrice` 为 `change-price`） |
| `POST /<context>/<aggregate>/<command>` | 创建类命令（`@CreateAggregate`、`@AllowCreate`）另有这一条；ID 由服务端生成 |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.<command>` | 其他生命周期命令（`wow.lifecycle.restore-aggregate`、`transfer-owner`、`transfer-space`、`apply-tags`） |
| `GET /<context>/<aggregate>/{id}/state` | 聚合的状态 |
| `POST`、`GET /<context>/<aggregate>/snapshot/query` | 快照查询模型及其能力描述 |
| `POST`、`GET /<context>/<aggregate>/events/query` | 事件查询模型及其能力描述 |

每条路由接收什么、回答什么，见 [HTTP API](./http-api.md)。
