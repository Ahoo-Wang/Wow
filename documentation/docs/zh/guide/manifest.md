---
title: Manifest 与兼容
description: 服务的 Manifest 怎样构建和合并、用于什么、对端怎样得到它们那一部分，以及兼容检查怎样防止改变破坏客户端。
outline: deep
---

# Manifest 与兼容

服务的 Manifest 就是一份文档形式的契约：它的上下文、聚合、命令、事件、状态 schema、订阅、发布的事件流和错误码。路由、OpenAPI 文档、生成的客户端、等待计划和查询准入都从它推出。本页跟随它从编译器到服务、到对端、再到兼容检查。它为什么存在，见[一份契约：Manifest](../concepts/contract)。

## 从片段到服务的 Manifest {#from-fragments-to-the-service-s-manifest}

1. **编译器**写出每个领域模块的片段 `META-INF/wow/manifest/<module>.json`（[编译器生成什么](./generated-code#the-manifest-fragment)）。
2. **Gradle 插件**（`generateWowManifestFragment`）用片段中类型的 JSON Schema、查询关键字和状态的指纹补全片段。补全后的片段替换模块资源中编译器写出的那个。
3. **在构建时**，`mergeWowManifest` 把服务运行时类路径上的片段合并成 `build/wow/manifest.json`。
4. **在启动时**，宿主合并同样的片段，加上框架的错误码，并在 `GET /wow/manifest` 上提供结果。

快照查询从补全后的片段读取状态 schema：只有插件补全之后，状态字段才能被过滤和排序。

## 对端得到它们那一部分 {#peers-get-their-part}

发送另一个上下文的命令、或读取它发布的事件流的服务，需要那个上下文的命令、事件、状态和错误。插件从对方上下文的片段中挑出它们，以 `META-INF/wow/contract.json` 放进它的 api 模块的 jar。依赖这个 api 模块的服务在类路径上找到这份契约，用它来指明它发送的命令、检查跨进对方上下文的等待计划，并解码对方发布的事件。不需要任何配置：依赖 api 就够了。

无法引入 api jar 的工具，例如控制台或网关，改为从对端的 `GET /wow/manifest` 读取它的 Manifest（[调用其他服务](./calling-services#call-a-command-by-name)）。

## 兼容检查 {#the-compatibility-check}

每个服务把合并后的 Manifest 作为基线 `manifest/baseline.json` 提交。`checkWowManifest`（`check` 的一部分）把每次构建合并出的 Manifest 与基线比较，并为每处差异归类：

| 类别 | 例子 | 在基线的主版本内 |
| --- | --- | --- |
| 增量的 | 新的命令、事件或聚合；新的可选字段 | 允许 |
| 破坏性的 | 删除命令或事件；新的必填字段；删除字段或改变字段类型；改变了事件的 schema 却没有新的修订号和升级函数 | 构建失败，并列出每处改变及其路径 |

`build/wow/manifest-compatibility.txt` 列出每处改变，包括增量的改变。有意的改变之后，`updateWowManifestBaseline` 记录新的基线：把它与改变一起提交，这样评审时就能看到客户端和对端会看到什么。eShop 从基线重新生成它的 TypeScript 客户端，所以客户端与它的服务永远不会在不知不觉中偏离。

破坏性改变需要服务契约的一个新主版本，或者在旧的命令或事件旁边新增一个，待最后一个客户端迁移之后再让旧的退役。

## 谁读取 Manifest {#what-reads-the-manifest}

| 读者 | 用于 |
| --- | --- |
| 宿主 | 它的路由、等待计划、查询准入（归属、schema）、内部端口的调用权限 |
| OpenAPI 文档 | 每个路由及其请求和响应的 schema（[OpenAPI 与 springdoc](./openapi)） |
| TypeScript 生成器 | 类型化的命令客户端和查询客户端 |
| 视图引擎 | 视图可以使用的字段和能力 |
| 对端 | 它们与这个上下文之间的契约，经由它的 api jar |
| ops 控制台和 migrate-data | 按名字使用命令、订阅和类型 |
