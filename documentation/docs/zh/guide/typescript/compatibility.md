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

### 版本范围

版本号跟随 Wow，不遵循 semver：次版本（`x.Y.0`）可能带来 TypeScript API 的破坏性改动，只有补丁版本（`x.y.Z`）保证兼容。`pnpm add` 和 `npm install` 默认保存插入符范围（`^x.y.z`），之后的某次安装就可能在没人决定升级的情况下装上下一个次版本。安装 Wow 包之前，在项目的 `.npmrc` 里加上这一行，改为保存只接受同一次版本补丁的波浪号范围；pnpm 和 npm 都认这个设置：

```ini
save-prefix=~
```

或者锁定精确版本：

```bash
pnpm add --save-exact @ahoo-wang/wow-client
pnpm add -D --save-exact @ahoo-wang/wow-generator
```

升到下一个次版本要有意为之：先读它发布说明里的 “Breaking” 一节，再把所有 Wow 包一起升级。Fetcher 的 peer 遵循 semver，用插入符范围即可。

### 支持期

TypeScript 的 npm 包与 Wow 的其余部分采用同一个支持策略，写在[安全策略](https://github.com/Ahoo-Wang/Wow/blob/main/SECURITY.md)里：修复发布在最新的稳定版本线上，旧版本线是否修复逐案评估。用 `~` 或精确版本停在一个次版本上可以维持一段时间，要获得修复就得计划升级到最新的次版本。

## Wow 服务端

`@ahoo-wang/wow-client` 的根入口和所有默认值都使用 Wow 8.11 引入的 `FilterExpression` 查询模型。Wow 8.10 只认更早的 `Condition` 模型，本包把它放在单独的子路径 `@ahoo-wang/wow-client/legacy` 里，保留到 v10。

| 服务端 | 查询 | 应用怎样构建查询 | CI 验证了什么 |
|---|---|---|---|
| Wow 9.x | `FilterExpression` | 根入口：`filter.*`、`singleQuery` / `listQuery` / `pagedQuery` | 改到服务端、客户端或生成器时：从同一提交构建的服务端生成的代码必须与提交的客户端逐字节一致并能编译，集成测试也对该服务端运行。改到客户端或生成器时，还对已发布的 9.1.3、9.1.5 示例服务端做运行时冒烟测试，并对从它们生成的代码做类型检查 |
| Wow 8.11.x | `FilterExpression`；不能用 `raw()` | 根入口，与 9.x 相同 | 改到客户端或生成器时：对已发布的 8.11.5 示例服务端做运行时冒烟测试，并对从它生成的代码做类型检查 |
| Wow 8.10.x | 只有 `Condition` | 查询用 `@ahoo-wang/wow-client/legacy`，其余一切用根入口 | 改到客户端或生成器时：只对从已发布的 8.10.8 示例服务端生成的代码做类型检查，不运行任何请求 |
| 8.10 之前 | — | 不支持 | — |

各行在实践中的含义：

- **8.10。** 用 `@ahoo-wang/wow-client/legacy` 构建查询；查询客户端两种模型都接受。`getById` 和 `getStateById` 发送的是 `FilterExpression`，所以对 8.10 要用 `aggregateId(id)` 构建 `/legacy` 查询，再调用 `single` 或 `singleState`。生成器从 8.10 的文档生成代码时，会自动从 `/legacy` 导入 `Condition` 类型。这一行只有类型检查覆盖：请用自己的 8.10 服务端测试应用实际用到的查询。见 [Wow 8.10 服务端](./migration.md#wow-8-10-服务端)。
- **`raw()`**，即把原始查询直接交给存储的 `Condition` 操作符，只存在于 `/legacy`，8.11.0 起的服务端对它应答 400。它没有 `FilterExpression` 的替代品。
- **8.11.1 之前**，OpenAPI 文档不发布 `x-wow-query-fields`；生成器改从 `Condition` schema 读取聚合的查询字段。
- **客户端较新、服务端较旧。** 服务端还没有其端点的客户端方法——例如 `EventStreamQueryClient.load` 或 `WowMetadataClient`——在那里会失败，通常是 404。服务端已有的一切照常可用。
- **客户端较旧、服务端较新。** 服务端可能发送客户端类型里没有的字段；它们照样出现在 JSON 里，只是类型不认识。重新生成即可看到。

### 不带 limit 的列表查询

`listQuery()` 只在给出 `limit` 时才发送它；客户端不补默认值，列表大小交给服务端决定，这是 Wow 9.1.5 的契约。不带 `limit` 的列表或列表流查询得到什么，取决于服务端：

| 服务端 | 不带 `limit` 的列表查询 |
|---|---|
| Wow 9.1.5 及以后 | 服务端的默认列表大小：未通过 `wow.webflux.query.default-list-size` 另行配置时为 100 |
| Wow 8.11.0～9.1.3 | `IllegalArgument: HTTP list query limit[0] must be between 1 and 1000.`：列表以 HTTP 400 失败，列表流先应答 200，再以这个错误事件结束。请显式传 `limit`；这次拒绝得到的 `WowError` 会在消息里这样提示 |
| Wow 8.10.x（经 `/legacy`） | `/legacy` 的 `listQuery` 默认发送 `limit: 10` |

应用要兼容 9.1.5 之前的服务端，就在每个 `listQuery()` 里传 `limit`。CI 用已发布的服务端逐行核对：对 8.11.5、9.1.3、9.1.5 镜像的冒烟测试发送不带 `limit` 的列表和列表流，断言的正是上表的结果。wow-react 的 Hook 原样使用传入的查询，所以 `useListQuery`、`useListStreamQuery` 也一样。

CI 任务在 [`typescript-contract.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/typescript-contract.yml)；它们保护的兼容代码列在 [`docs/compat-debt.md`](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md)。

## 运行环境与 peer 依赖

| 要求 | 版本 | 说明 |
|---|---|---|
| Node.js | `>=22.12.0` | 每个包的 `engines` 都这样声明；生成器 CLI 同样需要 |
| 浏览器 | 当前的常青浏览器 | 各包使用 `fetch`、`ReadableStream` 和 `TextDecoderStream` |
| React（`wow-react`、`wow-view-engine` 的 UI） | `^19.3.0` | 不支持 React 18：构建产物导入 `react/compiler-runtime` |
| TypeScript | `>=6.0` | 最低 6；CI 以使用者身份在 TypeScript 6.0 和最新的 7.x 上对打包后的包做类型检查。生成的客户端需要 `experimentalDecorators: true`；`moduleResolution` 用 `Bundler`、`NodeNext` 或 `Node16` |
| `@ahoo-wang/fetcher`、`fetcher-decorator`、`fetcher-eventstream` | `^5.1.5` | peer 依赖：由应用安装；5.1.4 起类型声明在 `require`（Node16、NodeNext）下能正确解析；5.1.5 起同一次编译里同时有 ESM 与 CJS 消费者也能通过类型检查。Fetcher 6 尚未发布，范围不对它做承诺；6.0 发布并验证后，在一个补丁版本里放宽 |
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

完整清单及实现每一项的代码见 [`docs/compat-debt.md`](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md)。
