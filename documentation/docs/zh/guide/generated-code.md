---
title: 编译器生成什么
description: 编译器为领域模块写出的模型工厂、处理器处理函数、查询字段常量、绑定索引和 Manifest 片段，以及它为 api 模块写出的契约代码。
outline: deep
---

# 编译器生成什么

Wow 编译器是一个 KSP 处理器。它在构建时读取领域模块中带注解的类，在它们旁边的 `build/generated/ksp/main/` 中写出 Kotlin 源码和资源。服务运行时不靠扫描或反射寻找任何东西：宿主加载编译器写出的内容。编译器无法绑定的模型会让构建失败，消息会指出是哪个函数、违反了哪条规则。本页列出它写了什么，以及每一部分在哪里使用。

对 eShop 的 ordering 领域模块，编译器写出的文件中有：

```text
build/generated/ksp/main/
├── kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/
│   ├── EshopOrderingDomainModelProvider.kt    the binding index
│   ├── OrderModel.kt                          fun orderModel(context, paymentPolicy, configure)
│   ├── OrderProcessSagaSubscriptions.kt       fun orderProcessSagaHandlers(services… | dependencies)
│   └── OrderStateProperties.kt                the state's query fields
└── resources/META-INF/
    ├── services/me.ahoo.wow.kernel.ModelProvider
    └── wow/manifest/eshop-ordering-domain.json    the Manifest fragment
```

## 模型工厂 {#model-factories}

每个聚合根得到一个以它命名的函数 `<root>Model`，首字母小写：`Order` 得到 `orderModel`。它的参数依次是上下文（默认为模块所在的上下文）、命令函数接收的每个领域服务各一个，然后是一个可选的、作用于模型构建器的 `configure` 块。测试直接用它构建模型：`orderModel(paymentPolicy = PaymentPolicy(30.minutes))`。宿主则从应用的 Bean 传入服务。与它并列的 `<Root>Model`（`OrderModel`）持有聚合的 `CONTEXT`、`NAME` 与 `TYPE`，供看不到自己契约的领域模块使用。

## 处理器处理函数 {#processor-handlers}

每个 `@Saga`、`@Projection` 或 `@Reactor` 类得到 `<processor>Handlers(services…)`，以命名参数接收构造函数的服务；以及 `<processor>Handlers(dependencies)`，从宿主的 `Dependencies` 中查找这些服务：`OrderProcessSaga` 得到 `orderProcessSagaHandlers`。两者都构建一次处理器，为每个处理函数返回一个绑定，名为 `<processor>.<function>`，并附上 Saga 的 `@OnRejected` 和 `@OnCommitted` 处理函数。处理器测试把假实现作为服务传入（`orderSummaryProjectionHandlers(summaries = …)`，[测试领域代码](./testing)）；缺少依赖时，错误会指出正在构建的处理器或模型。

## 查询字段 {#query-fields}

模块的类路径上有 `wow-query-api` 时，每个状态得到 `<State>Properties`：快照查询能指明的每条路径一个 `QueryField` 常量，例如 `state.price` 对应 `ProductStateProperties.STATE__PRICE`，与 `SnapshotProperties` 中的系统字段放在一起（[查询快照](./queries#write-a-query-in-kotlin)）。以任何集合形态持有元素的属性，列表、集合、`SortedSet`、`Deque`、数组或声明为 `Iterable` 的属性，也会得到其元素的字段，在它的 `elementMatch { }` 内指明；映射是叶子。类的 `Sequence` 是指明该属性的编译错误：Jackson 把它的元素读回为映射，所以请用 `List` 或 `Set` 持有它们。

## 绑定索引 {#the-binding-index}

每个模块得到一个实现 `ModelProvider` 的类，为 `ServiceLoader` 注册，以模块命名：`eshop-ordering-domain` 得到 `EshopOrderingDomainModelProvider`。它指明模块的上下文和片段，用 `Dependencies` 构建它的模型和处理器，宿主加载类路径上的每一个。它还记录生成时所针对的绑定版本：由另一个 Wow 版本的编译器构建的模块会在启动时被拒绝，并提示重新构建，而不是等到第一条命令才失败。测试可以加载它，检查模块构建了什么（[测试领域代码](./testing#what-the-compiler-generated)）。

## Manifest 片段 {#the-manifest-fragment}

编译器把模块那部分服务契约写到 `META-INF/wow/manifest/<module>.json`，按模块的项目路径命名、路径的各段作为目录（`:eshop:ordering-domain` 写成 `eshop/ordering-domain.json`，`:eshop-ordering-domain` 写成 `eshop-ordering-domain.json`，根项目写成 `.root.json`），所以两个项目绝不会共用一个：上下文；它的聚合，带着它们的命令、事件、生命周期命令、查询模型和定时；它的订阅，带着每个订阅派生、定时和调用的东西；它发布的事件流和错误码。在 Gradle 插件用按 Jackson 规则得出的 JSON Schema（包括查询关键字）补全之前，其中的类型 schema 都是引用。它接下来会怎样，见 [Manifest 与兼容](./manifest)。

## api 模块的契约代码 {#the-contract-code-of-the-api-module}

api 模块在插件 `me.ahoo.wow.api` 下声明它的领域模块，`wowContract(project(":ordering-domain"))`，插件从片段中挑出对端需要的内容，即每个上下文的命令、事件、状态 schema 和错误，即 `META-INF/wow/contract.json`，api 模块的契约。契约还写明承载这些上下文的服务，由域模块的构建声明：

<!-- sample: fragment -->

```kotlin
wow {
    contractPackage = "me.ahoo.wow.example.eshop.ordering.api.order"
    service = "eshop-ordering-service"
}
```

依赖这个 api 模块的对端按这个名字找到服务，服务若以其他 `spring.application.name` 启动则会拒绝启动。服务自己的 Manifest 从它承载的上下文的契约中取得名字，所以承载这些上下文的服务器模块无需重复写明；契约写明不同服务时构建失败，都没写明时以服务器模块的项目路径命名。eShop 的领域模块也是这样声明的，例如 ordering 的 `wow { service = "eshop-ordering-service" }`。

api 模块还从中编译出 `<Context>Contract`，例如 `EshopOrderingContract`，放在领域模块的 `contractPackage` 指定的包里。别的服务从这里 import 它，所以这个包由构建声明，不按类恰好所在的位置推导：上下文以后增加多少聚合，它都不动。有上下文而没有 `contractPackage` 的领域模块会使 `generateWowContract` 失败，并打印要加的那一行，值是上下文的命令与事件类今天共有的包。某一段是 Kotlin 关键字（`object`、`in`、`fun`……）时，生成的代码给它加上反引号。`<State>View` 放在它的聚合的命令与事件类共有的包里，没有共有的包时放在 `contractPackage` 里。

一个上下文的命令与事件类都在同一个 api 模块里，即在 `wowContract` 中声明其领域模块的那个。上下文的类如果在领域模块类路径上的另一个项目里，而那个项目没有应用 `me.ahoo.wow.api` 或没有声明该领域模块，`generateWowContract` 就会失败，并指出该项目和要加到它构建里的行。上下文的类如果分散在几个 api 模块里，契约的组装会失败，并指出不在该模块里的类：没有 api 模块携带契约的上下文，对端看不到。所以只持有上下文一部分的 api 模块，要把这些类移到携带它的那个模块里。


| 成员 | 包含 |
| --- | --- |
| `NAME` | 上下文的名字 |
| `<Aggregate>.TYPE`、`<Aggregate>.ref(tenant, id)` | 聚合的类型，以及指向其中某个聚合的 ref |
| `<Aggregate>.Errors.*` | 它的错误码，按完整名字 |
| `<Aggregate>.STATE__*` | 它的状态的查询字段，需要 `wow-query-api` |
| `Wait.*` | 上下文里等待链的跳点：`ACCEPTED`、`COMMITTED`、`SNAPSHOT`、`QUERYABLE`，以及契约列出的每个处理提交的订阅一个（Saga 只处理拒绝的 `@OnRejected` 处理器没有：它处理的拒绝已结束了等待），`<PROCESSOR>__<FUNCTION>`（`ORDER_PROCESS_SAGA__ON_ORDER_SUBMITTED`）；TypeScript 生成的 `<Context>Contract.Wait` 给出同样的跳点 |

对端不用手写任何路径、名字或错误码：`EshopOrderingContract.Order.ref(tenant, id)` 寻址一个订单，`EshopOrderingContract.Order.Errors.WRONG_STATUS` 用于比较拒绝（[调用其他服务](./calling-services)、[拒绝与错误码](./rejections#what-the-caller-receives)）。聚合的对象以其名称的帕斯卡命名形式命名；上下文对象自身声明的名称 `NAME`、`Errors`（上下文共享的错误码）或 `Wait`（它的等待环节）则加上 `Aggregate`：名为 `errors` 的聚合是 `ErrorsAggregate`，名为 `wait` 的聚合是 `WaitAggregate`，与 TypeScript 契约一致。

契约和这些代码是 api 模块的特性 `contract`，在同一模块内以分类器为 `contract` 的 jar 发布；模块自己的 jar 只装类型，所以每个模块都针对类型编译，不必等任何领域模块，包括 api 自己的领域模块。这个特性的能力（capability）是 `<group>:<项目名>-contract`，同一构建中的模块按它请求；模块以另一个名字发布时，还有 `<group>:<artifactId>-contract`，按坐标请求的构建按它请求。谁能看到契约：

- **服务**无需写一行就能看到它所触及的每个 api 模块的契约，包括它自己的 api。
- **领域模块的测试**能看到本构建中各 api 模块的契约，包括自己的 api：多数测试用它的 `<Context>Contract`。从另一个仓库发布的对端要显式索取：`testImplementation(wow.contract(...))`。
- **用到另一个上下文生成代码的代码**要索取它：`implementation(wow.contract(project(":eshop-ordering-api")))`、`wow.contract("com.example:ordering-api:1.0.0")` 或 `wow.contract(libs.ordering.api)`。没有应用 Wow 插件的模块写 `implementation(project(":eshop-ordering-api")) { capabilities { requireFeature("contract") } }`；Maven 构建加上该模块分类器为 `contract`、版本相同的 jar。

## 覆盖率 {#coverage}

编译器和契约插件生成的每个类都带有 `@WowGenerated`（`me.ahoo.wow.api.annotation`，在 `wow-annotations` 中，由 `wow-api` 带入）：模型、访问属性和查询字段的对象，绑定索引，每个服务路由的对象，`<Context>Contract` 及其中嵌套的每个对象，`<State>View` 及它复制的每个类，以及生成函数的文件门面类（`<Root>ModelKt`、`<Processor>SubscriptionsKt`）。它的保留策略是 binary：写在类文件里，运行时什么也不需要。覆盖率工具按它排除生成代码，而不是按名字（名字还会误伤你自己的类）：

- **JaCoCo** 无需任何配置，就会跳过带有保留策略为 class 或 runtime、简单名包含 `Generated` 的注解的类。它只看每个类文件自己的注解。Kotlin 编译器在生成函数内部合成的类（挂起 lambda、内联函数的副本）不带注解，所以按名字排除两个门面类的这些类：`*ModelKt$*` 和 `*SubscriptionsKt$*`。手写的类不会叫这些名字。
- **Kover** 用 `annotatedBy` 排除它：

```kotlin
kover {
    reports {
        filters {
            excludes {
                annotatedBy("me.ahoo.wow.api.annotation.WowGenerated")
            }
        }
    }
}
```

  在 Kover 0.9.8 上观察到：这个过滤也排除了带注解的门面类的合成类，不需要名字模式。

生成的视图上的 `@StateView` 是另一回事：读取端靠它找到聚合类型，手写的视图也带着它。生成的视图两者都带。
