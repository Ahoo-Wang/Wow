---
title: TypeScript 参考
description: 各个 Wow TypeScript 包的参考，以及它们的状态与入口。
---

# TypeScript 参考

每个包一份参考。按任务阅读请从 [TypeScript 指南](../../guide/typescript/)开始；支持的服务端与运行环境见[兼容性矩阵](../../guide/typescript/compatibility.md)。

| 包 | 状态 | 入口 | 参考 |
|---|---|---|---|
| `@ahoo-wang/wow-client` | Wow 9.2.0 起在 npm 上 | `@ahoo-wang/wow-client`、`/dsl`（不含 HTTP 的查询 DSL）、`/legacy`（供 Wow 8.10 使用的 Condition API，v10 移除） | [wow-client](./wow-client/) |
| `@ahoo-wang/wow-generator` | Wow 9.2.0 起在 npm 上 | 命令 `wow-generator`；`CodeGenerator` | [wow-generator](./wow-generator/) |
| `@ahoo-wang/wow-react` | Wow 9.2.0 起在 npm 上 | `@ahoo-wang/wow-react` | [wow-react](./wow-react/) |
| `@ahoo-wang/wow-view-engine` | Wow 9.2.0 起在 npm 上 | `@ahoo-wang/wow-view-engine`、`/react`、`/ui`、`/testing`、`/react-router` 与样式表；命令行 `wow-view-engine theme-check` | [wow-view-engine](./wow-view-engine/) |
| `@ahoo-wang/wow-view-store` | Wow 9.2.0 起在 npm 上 | `@ahoo-wang/wow-view-store` | [wow-view-store](./wow-view-store/) |

五个包都已带 provenance 发布到 npm；Wow 9.2.0（2026-10-03）是它们在 npm 上的首个版本。
