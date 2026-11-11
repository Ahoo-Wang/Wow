---
title: 'wow-generator 参考'
description: 'Generator 参考 — @ahoo-wang/wow-generator'
---

# wow-generator 参考

从 Wow Manifest（`GET /wow/manifest`）生成基于 `@ahoo-wang/wow-client` 的类型化客户端，或从 OpenAPI 3 文档生成它的模型与装饰器 API 客户端。包根导出 CodeGenerator、默认配置路径、日志器、错误类型与选项类型，见[符号索引](./symbols.md)。

## 安装

```bash
pnpm add -D @ahoo-wang/wow-generator @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/fetcher-decorator @ahoo-wang/wow-client typescript
```

包要求 Node **>=22.12.0**；仓库开发另固定 pnpm **10.34.9**。命令包含全部递归 peer（wow-generator → wow-client/Decorator/EventStream → Fetcher）及下文使用的编译器。ts-morph、commander、yaml 作为普通依赖自动安装。这些是生成阶段依赖；消费应用需把生成客户端导入的包作为运行时依赖安装，见[生成产物](./generated-output.md)。

包版本跟随 Wow，`@ahoo-wang/wow-client` 必须处于同一个小版本。从 `@ahoo-wang/fetcher-generator` 迁移过来？命令是 `wow-generator`，配置文件是 `wow-generator.config.json`，生成的代码导入 `@ahoo-wang/wow-client`，因此需要重新生成已有产物，见迁移指南。

## 最小示例

```bash
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
```

## 选择入口

可复现的构建步骤使用 CLI（CI 中加 `--strict` 让警告失败），需要自定义日志的 Node 脚本使用 `CodeGenerator.generate()`。两者都生成源码，不执行 API 操作。先准备完整的 [CLI 输入和 tsconfig](./cli.md)，重新生成前确认[输出所有权](./generated-output.md)。

## 专题

- [生成器 CLI](cli)
- [生成器配置](configuration)
- [程序化 API](programmatic-api)
- [生成产物与重新生成](generated-output)
- [Wow 聚合识别](wow-discovery)

[完整公开符号索引](./symbols.md)
