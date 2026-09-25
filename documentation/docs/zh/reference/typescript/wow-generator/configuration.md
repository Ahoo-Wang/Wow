---
title: '生成器配置'
description: '生成器配置 — @ahoo-wang/wow-generator'
---

# 生成器配置

生成器有两类独立输入：构造器/CLI 的执行选项，以及可选的 JSON/YAML 自定义配置文件。自定义文件不能覆盖 inputPath、outputDir、logger 或 tsconfig。

## 配置契约

```json
{
  "apiClients": {
    "Items": { "ignorePathParameters": [] },
    "Orders": {
      "ignorePathParameters": ["tenantId"],
      "methodNames": { "listOrders_1": "listArchivedOrders" }
    }
  }
}
```

| 设置                                   | 查找与默认值                          | 效果                                      |
| -------------------------------------- | ------------------------------------- | ----------------------------------------- |
| `apiClients`                           | 缺失时没有按 tag 覆盖                 | 键为 OpenAPI tag 的精确名称               |
| `apiClients[tag].ignorePathParameters` | 缺失 → Wow 文档为 `['tenantId', 'ownerId']`，其他文档为 `[]` | 普通 API 客户端按精确名称省略位置路径参数 |
| 空数组                                 | 显式覆盖                              | 保留普通 API 的全部路径参数               |
| `apiClients[tag].methodNames`          | 缺失 → 由 operationId 推导方法名      | operationId 到方法名的映射；方法名必须是合法标识符 |
| 命令客户端路径参数                     | 始终使用 `['tenantId', 'ownerId']`    | 按 tag 的 API 配置不影响命令客户端        |

Wow 文档指带 `info['x-wow-context-alias']` 或含 Wow 聚合路由的文档，其中 `tenantId`、`ownerId` 由 Wow 的 CoSec 拦截器填充。其他文档除非配置另有说明，都保留这两个路径参数。

`methodNames` 优先于操作的 `x-fetcher-method` 扩展和由 operationId 推导的方法名（见[方法](./generated-output#方法)）。同一 tag 的两个操作推导出相同方法名时用它区分，否则本次运行以退出码 4 失败。

数组替换默认值，不是追加。忽略参数不会删除 URL 模板占位符或自动提供参数值。请通过请求 URL 参数、客户端默认配置或 [CoSec 归属](https://fetcher.ahoo.me/zh/reference/cosec/interceptors-and-attribution)提供。

## 解析与失败

`options.configPath`（CLI 的 `-c`）选择配置文件，也可以是 HTTP/HTTPS URL，获取时使用与输入相同的请求头和超时。未指定时读取 `DEFAULT_CONFIG_PATH`，即 `./wow-generator.config.json`；它不存在时再读取 Wow 之前的旧名 `./fetcher-generator.config.json`，并给出弃用警告，v10 起不再读取旧名。路径相对 process.cwd()，不是输入文档所在目录。JSON/YAML 根据内容识别。

| 情形                                                            | 结果                                                  |
| --------------------------------------------------------------- | ----------------------------------------------------- |
| 默认路径（及旧名）都没有文件                                    | 按默认值生成                                          |
| 文件为空                                                        | 按默认值生成，并给出警告                              |
| `configPath`/`-c` 指定的文件不存在                              | 失败：`GeneratorError` `configuration`，退出码 3      |
| 文件读不到，或既不是 JSON 也不是 YAML                           | 失败，退出码 3                                        |
| 块或选项形状错误（`apiClients` 不是对象、`ignorePathParameters` 不是字符串数组、`methodNames` 的值不是合法方法名） | 失败，退出码 3 |
| 未知键，例如拼错的 `apiClient`                                  | 警告并点名该键，然后忽略                              |

CLI 的摘要行会写出读取的配置，`--verbose` 还会记录解析出的设置（`apiClients=Items, Orders`）。没有环境变量合并或自动多文件配置层级。

`GeneratorConfiguration` 与 `ApiClientConfiguration` 作为类型从包根导出，脚本可以用它们给自己构造的配置加类型，见[程序化 API](./programmatic-api)。

## 完整使用

把上述 JSON 保存为 `wow-generator.config.json`，确保规范中使用 `Items` 或 `Orders` 操作 tag，再运行：

```bash
pnpm exec wow-generator generate -i ./openapi.json -c ./wow-generator.config.json -o ./src/generated -t ./tsconfig.json
```

检查生成方法签名：Items 保留 tenantId/ownerId；Orders 省略 tenantId 但保留 ownerId。命令不执行生成的方法，也不连接其 API 服务。

## 实现源码

[typescript/wow-generator/src/input/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/input/configuration.ts)

[typescript/wow-generator/src/analysis/apiClients.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/analysis/apiClients.ts)

[typescript/wow-generator/src/api/configuration.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/api/configuration.ts)

[typescript/wow-generator/src/pipeline/codeGenerator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/pipeline/codeGenerator.ts)
