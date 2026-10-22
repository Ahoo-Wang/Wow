---
title: TypeScript 客户端故障排查
description: Wow TypeScript 包、编译器和服务给出的消息是什么意思，以及怎样修复。
---

# TypeScript 客户端故障排查

在本页搜索你看到的消息。

## 安装 {#installing}

| 消息 | 原因 | 修复 |
| --- | --- | --- |
| `unmet peer @ahoo-wang/wow-client@~x.y.z: found …` | 应用的 Wow 包处于不同的次版本 | 每个 Wow 包都安装同一个版本（[兼容性与版本](./compatibility#versions)） |
| `Cannot find module 'react/compiler-runtime'` | React 版本低于 19 | `wow-react` 和视图引擎需要 React 19 或更高版本 |
| `pnpm add` 安装了 9.x | `latest` 指向最新的稳定版本；Wow 10 的预发布版本在它自己的 dist-tag 上 | 安装你的服务所用的版本，或者预发布版本的 dist-tag（[发布状态](./compatibility#release-status)） |

## 编译 {#compiling}

| 消息 | 原因 | 修复 |
| --- | --- | --- |
| 过滤字段上的 `TS2345`，例如 `'status'` 或 `'state.statsu'` | 查询客户端为它的字段提供类型：系统字段，以及生成的 `<Aggregate>StateField`。状态字段位于 `state.` 之下 | 写成 `state.status`；服务改变了的话就重新生成 |
| 金额或 ID 上的 `TS2322: Type 'number' is not assignable to type 'string'` | `Long`、`BigInteger` 或 `BigDecimal` 字段是 `string`，精确地读取和写入 | 以文本传入它的数字：`unitPrice: '19.90'`（[精确数字](./generate#exact-numbers)） |
| 查询记录上的 `TS2339: Property '…' does not exist` | 查询的 `projection.include` 没有包含那条路径，结果被收窄到它包含的内容 | 包含这条路径，或者去掉投影 |
| `NodeNext` 下的 `TS2307: Cannot find module './generated'` | Node 解析需要指向文件 | 导入 `./generated/index.js` |
| `TS2882: Cannot find module or type declarations for side-effect import of '…/styles.css'` | TypeScript 6 会检查副作用导入 | Vite 项目通过 `vite/client` 声明 `*.css`；其他项目加上 `declare module '*.css';` |

## 发送请求 {#sending-requests}

| 消息 | 原因 | 修复 |
| --- | --- | --- |
| `Failed to parse URL from /eshop-ordering/…` | 客户端没有 `fetcher`，而默认的 Fetcher 没有基础 URL | 用 `{ fetcher }` 创建客户端（[发送命令](./commands#create-the-clients-once)） |
| 带 `about:blank` problem 的 `404` | 请求所到之处没有这个路由：网关没有路由该上下文的前缀，或者 `basePath` 不是它所期望的 | 发往提供 `/<context>/…` 的服务或网关 |
| `TypeError: The path … needs id.` | 作用于已有聚合的命令带着空 ID 发送 | 传入聚合的 ID |
| 浏览器中的 `TypeError: Failed to fetch`，控制台中有 CORS 消息 | 服务不允许页面的来源 | 在服务或网关上配置 CORS，或者从页面的同源提供 API |
| 每个请求都是 `401` `wow.auth.unauthenticated` | 没有令牌，或者客户端所用的 Fetcher 没有发送令牌 | 对那个 Fetcher 应用 CoSec 并登录（[认证与拦截器](./authentication)） |

## 服务的回答 {#service-answers}

拒绝以 `WowProblemError` reject；它的 `code` 是下面这些之一，或者生成的 `<Context>ErrorCodes` 中的某个领域错误码。全部错误码见[错误码](../reference/error-codes)。

| `code` | 状态 | 原因 | 修复 |
| --- | --- | --- | --- |
| 领域错误码 `<context>.<aggregate>.<name>` | 除非服务另行指定，否则为 422 | 聚合拒绝了命令 | 显示 `problem.detail`；请求本身需要改变 |
| `wow.command.invalid` | 400 | 命令未通过校验 | 在每个 `violations` 的 `path` 旁边显示它 |
| `wow.aggregate.not-found` | 404 | 没有那个 ID 的聚合 | 处理不存在的情况 |
| `wow.aggregate.version-mismatch` | 409，带 `expectedVersion` 时为 412 | 另一次写入抢先了 | 重新读取状态，再决定一次 |
| `wow.command.request-id-reused` | 409 | 请求 ID 已被另一条命令使用 | 为新命令生成新的请求 ID |
| `wow.wait.timeout` | 504 | 等待用完了时间；命令可能已经提交 | 用同一个请求 ID 重试，或者读取状态 |
| `wow.wait.invalid` | 400 | 等待计划不是服务能跟随的链 | 用每个上下文生成的各跳构建计划 |
| `wow.query.invalid` | 400 | 查询违反了查询模型的规则 | 读取每个 violation 的 `code` 和 `path` |
| `wow.query.min-version-not-reached` | 409 | 读取的 `minVersion` 在等待时间内没有达到 | 在 `retry.afterMs` 之后重试；problem 的 `version` 是当前的版本 |
| `wow.command.busy`、`wow.query.busy` | 429 | 聚合、节点或存储繁忙 | 在 `retry.afterMs` 之后重试 |
| `wow.node.standby` | 503 | 服务在迁移切换之前处于待机，只提供读取 | 稍后重试 |

`error.retry` 说明重试能否成功，以及是否保留请求 ID。

## 流 {#streams}

| 症状 | 原因 | 修复 |
| --- | --- | --- |
| 命令流在计划的最后一跳之前结束 | 途中的某个 Saga 没有再发出任何东西：最后一个结果带有 `chainEnded`，`pending` 列出不会到来的各跳 | 把它当作链的终点，就像 eShop 的结账在支付被拒时那样 |
| 命令流的最后一个结果不是 `SUCCEEDED` | 命令，或链所导向的某条命令，在准入之后被拒绝或失败 | 读取结果的 `status` 和 `error` |
| 查询流以 `WowProblemError` 出错 | 服务在中途以一个客户端无法越过续读的 problem 失败 | 像处理失败的请求一样处理它 |
| 组件已经消失，连接仍保持打开 | 流从未读到结束，也没有被取消 | 传入一个 `signal` 并在清理时中止它，或者取消读取器 |

## 生成 {#generating}

| 退出码或消息 | 原因 | 修复 |
| --- | --- | --- |
| 退出码 2 | 输入无法读取或获取，既不是 Manifest 也不是 OpenAPI 3 文档，或者某个选项不对 | 阅读消息；需要凭据的 http(s) 输入要带 `-H 'Authorization: Bearer …'` |
| 退出码 3 | 配置无法读取、解析或校验 | 修正消息指出的文件（[配置](../reference/typescript/wow-generator/configuration)） |
| 退出码 4 | 文档无法生成，或者设置了 `--strict` 而运行记录了警告 | 阅读这次运行的警告 |
| 退出码 5 | 输出无法写入、某条路径越出了输出目录，或者更新版本的生成器写了它的 `.wow-generator.json` | 用写出它的那个版本的生成器生成 |
| 提交的客户端与新一次运行的结果不同 | Manifest 基线变了，或者生成器的版本变了 | 把新的输出与导致它的改变一起提交 |

用 `--verbose` 运行，可以看到每一步以及失败的堆栈。

## 仍然解决不了 {#still-stuck}

收集失败请求的方法和 URL、状态、problem 的内容及其 `Wow-Request-Id`、包的版本（`pnpm ls @ahoo-wang/wow-client @ahoo-wang/fetcher`）以及服务的 Wow 版本，然后在 [Ahoo-Wang/Wow](https://github.com/Ahoo-Wang/Wow/issues) 提交 issue。
