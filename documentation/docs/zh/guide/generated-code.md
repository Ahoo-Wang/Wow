---
title: 编译器生成什么
description: 编译器为领域模块写出的模型工厂、处理器处理函数、查询字段常量、绑定索引和 Manifest 片段，以及它为 api 模块写出的契约代码。
outline: deep
---

# 编译器生成什么

Wow 编译器是一个 KSP 处理器。它在构建时读取领域模块中带注解的类，在它们旁边的 `build/generated/ksp/main/` 中写出 Kotlin 源码和资源。服务运行时不靠扫描或反射寻找任何东西：宿主加载编译器写出的内容。编译器无法绑定的模型会让构建失败，消息会指出是哪个函数、违反了哪条规则。本页列出它写了什么，以及每一部分在哪里使用。

对 eShop 的 ordering 领域模块，编译器写出：

```text
build/generated/ksp/main/
├── kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/
│   ├── EshopOrderingDomainModelProvider.kt    the binding index
│   ├── OrderModel.kt                          fun orderModel(context, paymentPolicy, configure)
│   ├── OrderProcessSagaSubscriptions.kt       fun orderProcessSagaHandlers(dependencies)
│   └── OrderStateProperties.kt                the state's query fields
└── resources/META-INF/
    ├── services/me.ahoo.wow.kernel.ModelProvider
    └── wow/manifest/eshop-ordering-domain.json    the Manifest fragment
```

## 模型工厂 {#model-factories}

每个聚合根得到一个以它命名的函数 `<root>Model`，首字母小写：`Order` 得到 `orderModel`。它的参数依次是上下文、命令函数接收的每个领域服务各一个，然后是一个可选的、作用于模型构建器的 `configure` 块。测试直接用它构建模型：`orderModel("eshop-ordering", PaymentPolicy(30.minutes))`。宿主则从应用的 Bean 传入服务。

## 处理器处理函数 {#processor-handlers}

每个 `@Saga`、`@Projection` 或 `@Reactor` 类得到 `<processor>Handlers(dependencies)`：`OrderProcessSaga` 得到 `orderProcessSagaHandlers`。它用构造函数的依赖构建一次处理器，为每个处理函数返回一个绑定，名为 `<processor>.<function>`，并附上 Saga 的 `@OnRejected` 和 `@OnCommitted` 处理函数。处理器测试通过 `Dependencies.of(...)` 传入它的假实现（[测试领域代码](./testing)）。

## 查询字段 {#query-fields}

模块的类路径上有 `wow-query-api` 时，每个状态得到 `<State>Properties`：快照查询能指明的每条路径一个 `QueryField` 常量，例如 `state.price` 对应 `ProductStateProperties.STATE__PRICE`，与 `SnapshotProperties` 中的系统字段放在一起（[查询快照](./queries#write-a-query-in-kotlin)）。

## 绑定索引 {#the-binding-index}

每个模块得到一个实现 `ModelProvider` 的类，为 `ServiceLoader` 注册，以模块命名：`eshop-ordering-domain` 得到 `EshopOrderingDomainModelProvider`。它指明模块的上下文和片段，用 `Dependencies` 构建它的模型和处理器，宿主加载类路径上的每一个。它还记录生成时所针对的绑定版本：由另一个 Wow 版本的编译器构建的模块会在启动时被拒绝，并提示重新构建，而不是等到第一条命令才失败。测试可以加载它，检查模块构建了什么（[测试领域代码](./testing#what-the-compiler-generated)）。

## Manifest 片段 {#the-manifest-fragment}

编译器把模块那部分服务契约写到 `META-INF/wow/manifest/<module>.json`：上下文；它的聚合，带着它们的命令、事件、生命周期命令、查询模型和定时；它的订阅，带着每个订阅派生、定时和调用的东西；它发布的事件流和错误码。在 Gradle 插件用按 Jackson 规则得出的 JSON Schema（包括查询关键字）补全之前，其中的类型 schema 都是引用。它接下来会怎样，见 [Manifest 与兼容](./manifest)。

## api 模块的契约代码 {#the-contract-code-of-the-api-module}

插件从片段中挑出对端需要的内容，即每个上下文的命令、事件、状态 schema 和错误，以 `META-INF/wow/contract.json` 放进 api 模块的 jar。api 模块还从中编译出 `<Context>Contract`，例如 `EshopOrderingContract`：

| 成员 | 包含 |
| --- | --- |
| `NAME` | 上下文的名字 |
| `<Aggregate>.TYPE`、`<Aggregate>.ref(tenant, id)` | 聚合的类型，以及指向其中某个聚合的 ref |
| `<Aggregate>.Errors.*` | 它的错误码，按完整名字 |
| `<Aggregate>.STATE__*` | 它的状态的查询字段，需要 `wow-query-api` |

对端只依赖 api 模块，不用手写任何路径、名字或错误码：`EshopOrderingContract.Order.ref(tenant, id)` 寻址一个订单，`EshopOrderingContract.Order.Errors.WRONG_STATUS` 用于比较拒绝（[调用其他服务](./calling-services)、[拒绝与错误码](./rejections#what-the-caller-receives)）。领域模块本身针对不含这些代码的 api 视图编译，所以它从不依赖它自己生成的契约。
