---
title: 'wow-client 参考'
description: '@ahoo-wang/wow-client 包：它的两个入口、各自导出什么，以及生成的符号索引和 API 报告在哪里。'
---

# wow-client 参考

`@ahoo-wang/wow-client` 是 Wow 服务路由的 TypeScript 客户端，基于 [Fetcher](https://fetcher.ahoo.me/)：带等待的命令、状态读取、快照查询、事件查询和数据集查询、Manifest，以及服务回答的 problem。`wow-generator` 写出的客户端构建在它之上。怎样使用它，见 [TypeScript 指南](../../../typescript/)；本参考列出它导出什么。

## 入口 {#entries}

| 入口 | 包含 | 符号 |
| --- | --- | --- |
| `@ahoo-wang/wow-client` | `CommandGateway` 及命令选项、`StateClient`、`SnapshotQueryClient`、`EventQueryClient`、`QueryModelClient`、`DatasetClient`、`fetchManifest`、`WowProblemError` 与 `FrameworkErrorCodes`、`wait` 的各跳、线上类型和精确数字的 JSON 辅助函数，以及 `/dsl` 导出的全部内容 | [根入口](/reference/typescript/wow-client/symbols)（英文） |
| `@ahoo-wang/wow-client/dsl` | 只有查询 DSL：`filter`、`aggregation`、排序、投影、分页、游标查询、查询工厂、能力描述符的类型和 `QueryErrorCodes`，没有 Fetcher，也没有 `fetcher-eventstream` 安装的任何流补丁 | [`/dsl`](/reference/typescript/wow-client/symbols-dsl)（英文） |

当一个 bundle 只构建查询，并通过它自己的客户端发送时，使用 `/dsl`。

## 生成的部分 {#the-generated-half}

两个符号索引页由包的 `scripts/api-report.mjs` 从构建出的声明生成：每个导出的名字、它的种类以及它 TSDoc 的第一句话。同一次运行把每个入口的完整签名保存在它的 API 报告 `test/api/root.api.md` 和 `test/api/dsl.api.md` 中，构建结果与提交的报告或页面不同时就会失败。因此对导出面的改动会在审查中显现，报告中的 `-` 行会给拉取请求打上 `breaking-change` 标签。

## 另见 {#see-also}

- [发送命令](../../../typescript/commands)、[读取与查询](../../../typescript/queries)和[生成客户端](../../../typescript/generate)：这个包的实际用法。
- [兼容性与版本](../../../typescript/compatibility)：peer 依赖、运行时和版本范围。
- [查询语言](../../query-language)和 [HTTP API](../../http)：它所发送内容的服务端。
