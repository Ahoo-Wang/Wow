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
  },
  "readModel": {
    "nonNullRequired": false
  }
}
```

默认配置路径为 `./fetcher-generator.config.json`。该可选文件不存在时，CLI 会记录解析
失败，并使用默认值继续执行。

### `readModel.nonNullRequired`

未列入 `required` 的属性会生成为可选属性。这对请求侧是准确的，但导出器通常会把带默认
值的属性排除在 `required` 之外，于是响应被低估——服务端其实总会返回这些字段。

开启 `readModel.nonNullRequired` 后，状态聚合与领域事件 schema 中所有非空属性都会生成
为必填。该规则的适用范围被刻意收窄：

- 可空属性仍保持可选，涵盖 null 的各种写法：OpenAPI 3.0 的 `nullable` 标记、3.1 类型
  数组中的 `null`、枚举或 const 中的 `null`、某个可空的 `anyOf` 分支、恰好一个可空的
  `oneOf` 分支，以及每个分支都可空的 `allOf`。所有关键字必须一致同意：枚举里的 null
  成员若与 `type: string` 同级会被该 type 排除，`not` 的子 schema 若接受 null 也等于
  排除 null，这两种情况下属性都是必填而非可选。
- `writeOnly` 属性仍保持可选，标记写在被引用的 component 上也算。它们属于请求侧，
  无论类型怎么写，响应都可以不返回。任何值都无法满足的属性（如 `{ not: {} }`、空
  `enum`）同样保持可选——响应必须省略它们。
- 请求 schema 不受影响，且"请求"涵盖所有操作的请求体与参数，不只是 Wow 命令。把请求的
  必填属性写多了，会拒绝客户端本可以发起的调用。
- 请求与读模型共享的 schema 保持文档声明的形态，其中会被该规则改变的 schema 会在生成
  日志中列出。

默认值为 `false`，即完全按文档声明生成。只有当服务端确实会序列化每个非空属性时才应开
启——Jackson 的 `NON_NULL` 满足该前提，`NON_DEFAULT` 不满足。

## 核心能力

- 本地 JSON/YAML 与 HTTP(S) OpenAPI 输入。
- 按 tag 分组的 TypeScript 模型与 Decorator API 客户端。
- Wow bounded-context、命令、快照、事件与查询发现。
- 递归生成 `index.ts`，并通过 ts-morph 格式化。
- 支持注入日志的编程式 `CodeGenerator` API。

每次契约变更后重新生成，并在发布前编译结果。

## 文档

- [OpenAPI 生成实战](https://fetcher.ahoo.me/zh/guides/services/generated-client)
- [Generator 参考](https://fetcher.ahoo.me/zh/reference/generator)

[English](./README.md) · [许可证](../../LICENSE)
