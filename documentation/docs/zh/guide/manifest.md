---
title: Manifest 与兼容
description: 服务的 Manifest 怎样构建和合并、用于什么、对端怎样得到它们那一部分，以及兼容检查怎样防止改变破坏客户端。
outline: deep
---

# Manifest 与兼容

服务的 Manifest 就是一份文档形式的契约：它的上下文、聚合、命令、事件、状态 schema、订阅、发布的事件流和错误码。路由、OpenAPI 文档、生成的客户端、等待计划和查询准入都从它推出。本页跟随它从编译器到服务、到对端、再到兼容检查。它为什么存在，见[一份契约：Manifest](../concepts/contract)。

## 从片段到服务的 Manifest {#from-fragments-to-the-service-s-manifest}

1. **编译器**写出每个领域模块的片段 `META-INF/wow/manifest/<module>.json`（[编译器生成什么](./generated-code#the-manifest-fragment)）。
2. **Gradle 插件**（`generateWowManifestFragment`）用片段中类型的 JSON Schema、查询关键字和状态的指纹补全片段。类型上的 Jakarta Bean Validation 约束成为它的 schema 的校验关键字，客户端在发送之前就能看到：`@Positive` 是值为 0 的 `exclusiveMinimum`，`@NotBlank` 是值为 1 的 `minLength`，`@Size` 是长度或条目数的上下界，`@Pattern` 是 `pattern`，`@Email` 是 `email` 格式。字段是否必填、能否为 null，仍然由它的 Kotlin 类型决定。补全后的片段替换模块资源中编译器写出的那个。
3. **在构建时**，`mergeWowManifest` 把服务运行时类路径上的片段合并成 `build/wow/manifest.json`。
4. **在启动时**，宿主合并同样的片段，加上框架的错误码，并在 `GET /wow/manifest` 上提供结果。

快照查询从补全后的片段读取状态 schema：只有插件补全之后，状态字段才能被过滤和排序。

## 对端得到它们那一部分 {#peers-get-their-part}

发送另一个上下文的命令、或读取它发布的事件流的服务，需要那个上下文的命令、事件、状态和错误。插件从对方上下文的片段中挑出它们，以 `META-INF/wow/contract.json` 放进它的 api 模块的 jar。依赖这个 api 模块的服务在类路径上找到这份契约，用它来指明它发送的命令、检查跨进对方上下文的等待计划，并解码对方发布的事件。不需要任何配置：依赖 api 就够了。

无法引入 api jar 的工具，例如控制台或网关，改为从对端的 `GET /wow/manifest` 读取它的 Manifest（[调用其他服务](./calling-services#call-a-command-by-name)）。

## 兼容检查 {#the-compatibility-check}

每个服务把合并后的 Manifest 作为基线 `manifest/baseline.json` 提交。基线记录服务的兼容线，即其版本的主版本号（`"line": 10`），而不是随每次发布变化的版本。`checkWowManifest`（`check` 的一部分）把每次构建合并出的 Manifest 与基线比较，并为每处差异归类：

| 类别 | 例子 | 在 `check` 中 |
| --- | --- | --- |
| 增量的 | 新的命令、事件或聚合；新的可选字段；放宽命令的约束 | 基线记录它之前失败 |
| 破坏性的 | 删除命令或事件；新的必填字段；删除字段或改变字段类型；收紧命令的约束，例如提高最小值或新增 `pattern`；删除事件或状态的字段、改变其类型或使其必填，无论修订号如何 | 在兼容线内让构建失败，并列出每处改变及其路径 |

关于约束的这两行说的是命令，它由客户端发送：更紧的约束会拒绝它们以前发送过的值。状态或事件的 schema 是读者读取的：每个聚合都有的事件查询的客户端，以及快照查询的客户端和发布的事件流的订阅者。所以对状态或事件，这两行反过来：在那里收紧约束是增量的，放宽约束是破坏性的；服务自己存储的记录从不按约束检查。删除字段、改变其类型或使其必填，无论修订号如何，都会破坏这些读者：新的修订号和它的 `@Upgrade` 只让服务自己的历史仍可读，而不是读者的。要在兼容线内改变一个事件，就在旧事件旁边新增一个事件，待它的读者都迁移之后再停用旧事件；否则就等下一个主版本。

`build/wow/manifest-compatibility.txt` 列出每处改变，包括增量的改变。没有基线时 `check` 也会失败。有意的改变之后，`updateWowManifestBaseline` 记录新的基线：把它与改变一起提交，这样评审时就能看到客户端和对端会看到什么。每次失败都会写明这个任务。eShop 从基线重新生成它的 TypeScript 客户端，所以客户端与它的服务永远不会在不知不觉中偏离。

破坏性改变需要服务的下一个主版本；或者在旧的命令或事件旁边新增一个，待最后一个客户端迁移之后再让旧的退役。服务的主版本号等于基线的兼容线时，`checkWowManifest` 让破坏失败，`updateWowManifestBaseline` 拒绝记录它。版本的主版本号大于兼容线之后，更新会在同一次改变中记录这次破坏和新的兼容线。一个主版本首个版本的预发布版（例如 `11.0.0-beta.1`）让它的兼容线保持开放：它可以记录破坏，因为这条线上还没有发布过任何东西。其他预发布版只有带上 `-Pwow.manifest.allowBreaking` 才能记录破坏，更新的报告 `build/wow/manifest-baseline.txt` 会记下这个标志和它放过的改变。

## 谁读取 Manifest {#what-reads-the-manifest}

| 读者 | 用于 |
| --- | --- |
| 宿主 | 它的路由、等待计划、查询准入（归属、schema）、内部端口的调用权限 |
| OpenAPI 文档 | 每个路由及其请求和响应的 schema（[OpenAPI 与 springdoc](./openapi)） |
| TypeScript 生成器 | 类型化的命令客户端和查询客户端 |
| 视图引擎 | 视图可以使用的字段和能力 |
| 对端 | 它们与这个上下文之间的契约，经由它的 api jar |
| ops 控制台和 migrate-data | 按名字使用命令、订阅和类型 |
