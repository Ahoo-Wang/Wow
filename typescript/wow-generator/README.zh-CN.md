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

默认配置路径为 `./fetcher-generator.config.json`，相对 CLI 的运行目录解析。只有这个默认
路径是可选的：该位置没有文件时，CLI 会明确说明并按默认值生成。`--config` 指定的路径必须
存在；配置读不到、解析不了，或某个选项的形态不对，都会让本次生成失败，而不是退回默认值。
生成器不认识的选项——例如拼错的 `apiClient`——会被逐个点名警告并忽略。

日志会写出实际读取的绝对路径与解析出的设置，一次运行就能回答"我的配置生效了吗"：

```text
ℹ️  Configuration loaded from /work/app/fetcher-generator.config.json: apiClients=Catalog
```

如果这一行不存在，说明配置文件根本没送到生成器——请检查 CLI 的运行目录。

## 属性可选性

schema 声明的每个属性都生成为必填。强类型后端根本没有"缺席的 `int`/`boolean`"可以返回，
满屏 `?` 的模型描述的是导出器而不是线上格式——导出器常把带默认值的属性排除在 `required`
之外，由此产生的空值判断纯属噪音。

文档真正想表达的可选性，则由各自合适的位置承载：

- **可空**留在类型里。可空属性生成 `T | null`，可能缺席的值依然不可能被忘记。
- **命令**在命令类型上保留声明的可选性。命令客户端会依据文档的 `required` 把请求体包成
  `PartialBy<Command, 'field' | ...>`，调用方仍可省略 API 允许省略的字段——
  `AddCartItemCommand = CommandBody<PartialBy<AddCartItem, 'quantity'>>`。把模型改成必填
  绝不会收紧客户端能发的内容。

有一点值得知道：引用自身 schema 的非空属性没有有限字面量，因为每一层都还要下一层。能终止
的递归模型会把这条链声明为可空，生成 `T | null`，构造起来毫无问题。

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
