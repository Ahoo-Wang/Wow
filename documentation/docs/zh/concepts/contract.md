---
title: 一份契约：Manifest
description: Manifest 描述什么、编译器怎样构建它、从它推出什么，以及它怎样防止客户端和其他服务被破坏。
---

# 一份契约：Manifest

一个服务有很多读者：它自己的运行时、HTTP 客户端、TypeScript 前端、其他服务、视图引擎、运维工具和 Agent。如果它们各有一份对服务的描述，迟早会彼此偏离。Wow 只保留一份描述，即 Manifest，其他一切都从它推出。

## Manifest 描述什么 {#what-the-manifest-describes}

Manifest 是编译器从你的领域模型构建的一份 JSON 文档。对服务的每个限界上下文，它列出：

- **聚合**：每个状态的 schema、是否保留快照、聚合归谁所有，以及它处理的生命周期命令；
- **命令**：名字、schema、创建语义、每条命令可能产生的事件、可能拒绝时用的错误码，以及它的路由；
- **事件**：名字、旧名字、修订号和 schema；
- 每个聚合提供的**查询**；
- **订阅**：每个投影、Saga 和反应器函数，它读什么，对 Saga 还有它发出的命令；
- **发布的事件流**：哪些聚合被发布、发布在哪个主题、带不带状态；
- **错误码**，以及它们的 HTTP 状态和说明。

schema 用 JSON Schema 表示。名字是稳定的模型名 `context.aggregate.Name`，而不是类名。

## 怎样构建 {#how-it-is-built}

```text
annotations ─▶ compiler ─▶ Manifest ─┬▶ HTTP routes (at run time)
                                     ├▶ OpenAPI
                                     ├▶ TypeScript client (wow-generator)
                                     ├▶ views and their capabilities
                                     ├▶ peer contracts for other services
                                     └▶ agent Skills
```

一个服务的领域常常分布在几个 Gradle 模块里。编译器一次只看一个模块，所以每个模块产出 Manifest 的一个**片段**。构建时，一个 Gradle 任务补全每个片段的 schema，并把片段合并成服务的 Manifest。运行时，服务合并它在类路径上找到的片段。两个片段声明了同一个上下文、聚合、命令、事件或订阅时，服务拒绝启动。

schema 来自 Jackson 本身。构建过程加载编译好的类，询问 Jackson 会怎样序列化它们，所以命名策略、`@JsonProperty`、自定义序列化器和 Kotlin 默认值，都按它们在线上的真实样子体现出来。这只发生在构建时；运行时不做任何内省。

服务在公开端口的 `GET /wow/manifest` 上发布它的 Manifest，与其他路由一样需要授权。

## 从它推出什么 {#what-is-derived-from-it}

- **HTTP 路由。** 运行时从 Manifest 构建命令、查询和状态路由。没有路由注解。
- **OpenAPI。** 服务提供一份从 Manifest 推出的 OpenAPI 文档。在带 springdoc 的 Spring Boot 宿主里，Wow 的路由也会合并进 springdoc 的文档。
- **TypeScript 客户端。** `wow-generator` 读取 Manifest，生成类型化的客户端：命令及其错误、查询字段、事件。前端提交生成的代码，并在 CI 里重新生成。
- **视图。** 视图引擎从 Manifest 推出默认视图，以及一个视图可以请求什么。
- **准入。** 运行时按类型名解码命令、对照订阅检查等待计划、把错误码映射到 HTTP 状态，依据都是 Manifest。

数据集不在 Manifest 里。Manifest 描述的是代码在构建时定义的东西；数据集在运行时于分析目录中定义。视图引擎两者都读。

## 服务之间的契约 {#contracts-between-services}

向另一个上下文发命令、或读它发布的事件流的服务，需要那个上下文契约的一部分：它的命令、事件、状态和错误。这部分从对方上下文的 api jar 里得到。

对方上下文的领域模块编译时，一个构建任务从它的片段里挑出对端需要的内容，以 `META-INF/wow/contract.json` 放进它的 api jar。依赖这个 api jar 的服务，运行时在类路径上就能找到这份契约。它用契约在发送之前检查命令、检查跨进对方上下文的等待计划，并在启动时检查它订阅的发布的事件流。不需要任何配置：依赖对方上下文的 api 就够了。

## 兼容 {#compatibility}

Manifest 是客户端和其他服务依赖的契约，所以它的变化要受检查。服务把上一次发布的 Manifest 作为基线保存在仓库里。构建过程把当前的 Manifest 与基线比较，并给每处差异归类：

- **增量的**，例如新命令、新的可选字段，或者删除一个事件但保留其名字为退役：在同一主版本内允许。
- **破坏性的**，例如删除命令或事件、增加必填字段，或者改了事件的 schema 却没有新修订号和升级函数：在同一主版本内构建失败。

新修订号只豁免服务自己的存储。一旦事件或状态有了外部读者（通过事件查询、快照查询或发布的事件流），删除它的字段或改变字段类型仍然是破坏性的。

Kotlin API 的二进制兼容是另一回事，由 ABI 转储检查。线上契约由 Manifest 检查守住。
