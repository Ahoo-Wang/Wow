---
title: TypeScript
description: Wow 的 TypeScript 包：每个包做什么、前端需要哪些，以及它们需要的版本和 peer 依赖。
---

# TypeScript

Wow 服务的路由、等待、problem 和查询语言，由 [`typescript/`](https://github.com/Ahoo-Wang/Wow/tree/main/typescript) 下的包在 TypeScript 中提供类型。前端从服务的 Manifest 为每个聚合生成一个客户端，并通过它调用服务；路由、请求头或错误，都不用手写。

## 这些包 {#the-packages}

| 包 | 作用 | 需要 |
| --- | --- | --- |
| `@ahoo-wang/wow-client` | 带等待的命令、状态读取、快照、事件和数据集查询、Manifest，以及服务回答的 problem。查询 DSL 是它的 `/dsl` 入口 | `@ahoo-wang/fetcher`、`@ahoo-wang/fetcher-eventstream` |
| `@ahoo-wang/wow-generator` | 从服务的 Manifest 生成它的类型化客户端；也能从任何 OpenAPI 3 文档生成模型和客户端。CLI 为 `wow-generator` | `wow-client` |
| `@ahoo-wang/wow-react` | 保存查询、查询结果和加载状态的 React hooks | `wow-client`、React 19 |
| `@ahoo-wang/wow-view-engine` | 基于 Wow 数据的可配置数据视图：记录、分析和仪表盘 | `wow-client`、React 19 |
| `@ahoo-wang/wow-view-store` | 视图引擎保存的视图，保存在 Wow 服务端 | `wow-view-engine`、`wow-client` |

发送命令和读取状态的前端需要 `wow-client`，构建时还需要 `wow-generator`。其他包用于 React 组件和可配置的视图。

每个包都基于 [Fetcher](https://fetcher.ahoo.me/)，一个有独立仓库的通用 HTTP 客户端。请求经过应用配置一次的 Fetcher，它带着服务的基础 URL 以及为请求签名的拦截器。

## 版本与 peer 依赖 {#versions-and-peers}

这些包随 Wow 一起发布，来自同一个 tag，版本号相同：`@ahoo-wang/wow-client` 10.0.0 属于 Wow 10.0.0。应用中的每个 Wow 包都使用同一个版本，即它所调用的服务的版本。版本号跟随 Wow 而不是语义化版本：次版本可能改变 TypeScript API，所以用 `~` 范围或确切版本，让这些包停在同一个次版本上。

| 要求 | 范围 |
| --- | --- |
| Node.js，用于生成器和服务端渲染 | `>=22.12.0` |
| TypeScript | 6 或更高；CI 在 6.0 和最新的 7.x 上检查这些包 |
| `@ahoo-wang/fetcher`、`fetcher-eventstream`（OpenAPI 客户端还需要 `fetcher-decorator`） | `^5.1.5 \|\| ^6.0.0` |
| React 和 React DOM，用于 `wow-react` 和视图引擎的界面 | `^19.0.0` |
| `@ahoo-wang/wow-client`，用于其他 Wow 包 | 同一个次版本，`~x.y.z` |

每个 peer 依赖都由应用安装，所以每个版本都由应用掌控。

## 安装 {#install}

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator typescript
```

## 按任务继续 {#continue-by-task}

| 任务 | 阅读 |
| --- | --- |
| 生成服务的客户端 | [生成客户端](./generate) |
| 发送命令，并等待它带来的结果 | [发送命令](./commands) |
| 读取聚合的状态，查询快照和事件 | [读取与查询](./queries) |
| 构建用户可以配置的视图 | [视图引擎](./view-engine/) |
| 查阅某个包的 API | [TypeScript 参考](../reference/typescript/) |

客户端发送和读取的路由、请求头和 problem 列在 [HTTP API](../reference/http) 中，查询列在[查询语言](../reference/query-language)中。
