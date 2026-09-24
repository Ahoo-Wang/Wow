---
title: 兼容性与版本
description: Wow TypeScript 包支持哪些 Wow 服务端、Node.js、React、TypeScript 与 Fetcher 版本，CI 验证了什么，以及 v10 移除什么。
---

# 兼容性与版本

本页回答：**哪个版本的 Wow TypeScript 包能配哪些 Wow 服务端和运行环境，把握有多大？**

## 发布状态

| 包 | 状态 |
|---|---|
| `@ahoo-wang/wow-client` | 随 Wow **9.2.0** 发布；在此之前尚未上 npm |
| `@ahoo-wang/wow-generator` | 随 Wow **9.2.0** 发布；在此之前尚未上 npm |
| `@ahoo-wang/wow-react` | 随 Wow **9.2.0** 发布；在此之前尚未上 npm |
| `@ahoo-wang/wow-view-engine` | 尚未发布；还没有日期，也没有兼容承诺 |

Wow 9.2.0 发布之前，`pnpm add @ahoo-wang/wow-client` 会以 `E404` 失败。候选版本可能先以 dist-tag `next` 出现（`pnpm add @ahoo-wang/wow-client@next`）；`latest` 从 9.2.0 开始。在此之前，应用继续使用 5.x 的 `@ahoo-wang/fetcher-wow` 和 `@ahoo-wang/fetcher-generator`，9.2.0 发布后再按[迁移指南](./migration.md)切换。

## 版本

TypeScript 包与 Kotlin 模块共用一个版本号，从同一个 tag 发布：`@ahoo-wang/wow-client` 9.2.0 与 Wow 9.2.0 一起发布。破坏性改动只在 `x.Y.0` 版本发布，并在[发布说明](https://github.com/Ahoo-Wang/Wow/releases)的 “Breaking” 一节列出。

- **一个应用的所有 Wow 包用同一个版本。** `wow-generator` 和 `wow-react` 以 `~x.y.z` 把 `@ahoo-wang/wow-client` 声明为 peer，次版本不一致时包管理器会告警。
- **优先选择所调用服务的版本。** 这个组合是 CI 端到端测试过的。
- **用同一版本的生成器重新生成**，与生成代码编译时所用的 `wow-client` 版本一致。

## Wow 服务端

`@ahoo-wang/wow-client` 的根入口和所有默认值都使用 Wow 8.11 引入的 `FilterExpression` 查询模型。Wow 8.10 只认更早的 `Condition` 模型，本包把它放在单独的子路径 `@ahoo-wang/wow-client/legacy` 里，保留到 v10。

| 服务端 | 查询 | 应用怎样构建查询 | CI 验证了什么 |
|---|---|---|---|
| Wow 9.x | `FilterExpression` | 根入口：`filter.*`、`singleQuery` / `listQuery` / `pagedQuery` | 改到服务端、客户端或生成器时：从同一提交构建的服务端生成的代码必须与提交的客户端逐字节一致并能编译，集成测试也对该服务端运行 |
| Wow 8.11.x | `FilterExpression`；不能用 `raw()` | 根入口，与 9.x 相同 | 改到客户端或生成器时：对已发布的 8.11.5 示例服务端做运行时冒烟测试，并对从它生成的代码做类型检查 |
| Wow 8.10.x | 只有 `Condition` | 查询用 `@ahoo-wang/wow-client/legacy`，其余一切用根入口 | 改到客户端或生成器时：只对从已发布的 8.10.8 示例服务端生成的代码做类型检查，不运行任何请求 |
| 8.10 之前 | — | 不支持 | — |

各行在实践中的含义：

- **8.10。** 用 `@ahoo-wang/wow-client/legacy` 构建查询；查询客户端两种模型都接受。`getById` 和 `getStateById` 发送的是 `FilterExpression`，所以对 8.10 要用 `aggregateId(id)` 构建 `/legacy` 查询，再调用 `single` 或 `singleState`。生成器从 8.10 的文档生成代码时，会自动从 `/legacy` 导入 `Condition` 类型。这一行只有类型检查覆盖：请用自己的 8.10 服务端测试应用实际用到的查询。见 [Wow 8.10 服务端](./migration.md#wow-8-10-服务端)。
- **`raw()`**，即把原始查询直接交给存储的 `Condition` 操作符，只存在于 `/legacy`，8.11.0 起的服务端对它应答 400。它没有 `FilterExpression` 的替代品。
- **8.11.1 之前**，OpenAPI 文档不发布 `x-wow-query-fields`；生成器改从 `Condition` schema 读取聚合的查询字段。
- **客户端较新、服务端较旧。** 服务端还没有其端点的客户端方法——例如 `EventStreamQueryClient.load` 或 `WowMetadataClient`——在那里会失败，通常是 404。服务端已有的一切照常可用。
- **客户端较旧、服务端较新。** 服务端可能发送客户端类型里没有的字段；它们照样出现在 JSON 里，只是类型不认识。重新生成即可看到。

CI 任务在 [`typescript-contract.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/typescript-contract.yml)；它们保护的兼容代码列在 [`docs/compat-debt.md`](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md)。

## 运行环境与 peer 依赖

| 要求 | 版本 | 说明 |
|---|---|---|
| Node.js | `>=22.12.0` | 每个包的 `engines` 都这样声明；生成器 CLI 同样需要 |
| 浏览器 | 当前的常青浏览器 | 各包使用 `fetch`、`ReadableStream` 和 `TextDecoderStream` |
| React（`wow-react`、`wow-view-engine` 的 UI） | `^19.3.0` | 不支持 React 18：构建产物导入 `react/compiler-runtime` |
| TypeScript | CI 检查的是 6.0 | 生成的客户端需要 `experimentalDecorators: true`；`moduleResolution` 用 `Bundler`、`NodeNext` 或 `Node16` |
| `@ahoo-wang/fetcher`、`fetcher-decorator`、`fetcher-eventstream`、`fetcher-openapi` | `^5.1.0 \|\| ^6.0.0` | peer 依赖：由应用安装 |
| `@ahoo-wang/fetcher-react`（`wow-react`） | `^5.1.3 \|\| ^6.0.0` | 5.1.3 是 Wow Hook 迁出后的第一个版本 |
| `@ahoo-wang/wow-client`（其他 Wow 包依赖它） | `~x.y.z` | 同一个次版本 |

pnpm 8 及以后、npm 7 及以后会自动安装缺失的 peer；Yarn 不会，Yarn 项目需要逐个显式添加。每个[参考页](../../reference/typescript/wow-client/)的安装命令都列出了它们。

## v10 移除什么

下列内容在整个 Wow 9.x 期间保持可用；v10 在一个破坏性版本里一并移除：

| 9.x 期间保留 | 替代 |
|---|---|
| Wow 8.10 与 8.11 服务端 | Wow 9.x 或更高 |
| `@ahoo-wang/wow-client/legacy`（`Condition` API、`Operator`、操作符文案）以及查询客户端和 Hook 的 `Condition` 重载 | `FilterExpression` 与 `filter.*` |
| `fetcher-generator` 命令 | `wow-generator` |
| 读取 `fetcher-generator.config.json` 与 `.fetcher-generator.json` | `wow-generator.config.json` 与 `.wow-generator.json` |
| `LogicalField` | `QueryField` |

完整清单及实现每一项的代码见 [`docs/compat-debt.md`](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md)。
