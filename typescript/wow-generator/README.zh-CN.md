# `@ahoo-wang/fetcher-generator`

从本地或远程 OpenAPI 文档生成 TypeScript 模型、Fetcher Decorator 客户端与 Wow
客户端。

## 安装与运行

```bash
pnpm add -D @ahoo-wang/fetcher-generator
pnpm exec fetcher-generator generate \
  --input ./openapi.yaml \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

生成客户端会导入对应 Fetcher 运行时包。请把生成结果实际使用的 peer 包加入应用依赖。

## 配置

```json
{
  "apiClients": {
    "Catalog": {
      "ignorePathParameters": ["tenantId", "ownerId"]
    }
  }
}
```

默认配置路径为 `./fetcher-generator.config.json`。该可选文件不存在时，CLI 会记录解析
失败，并使用默认值继续执行。

## 核心能力

- 本地 JSON/YAML 与 HTTP(S) OpenAPI 输入。
- 按 tag 分组的 TypeScript 模型与 Decorator API 客户端。
- Wow bounded-context、命令、快照、事件与查询发现。
- 递归生成 `index.ts`，并通过 ts-morph 格式化。
- 支持注入日志的编程式 `CodeGenerator` API。

每次契约变更后重新生成，并在发布前编译结果。

## 文档

- [OpenAPI 生成实战](https://fetcher.ahoo.me/zh/recipes/openapi-client)
- [Generator 参考](https://fetcher.ahoo.me/zh/reference/generator)

[English](./README.md) · [许可证](../../LICENSE)
