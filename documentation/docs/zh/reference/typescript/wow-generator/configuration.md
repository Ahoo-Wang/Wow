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
    "Orders": { "ignorePathParameters": ["tenantId"] }
  }
}
```

| 设置                                   | 查找与默认值                          | 效果                                      |
| -------------------------------------- | ------------------------------------- | ----------------------------------------- |
| `apiClients`                           | 缺失时没有按 tag 覆盖                 | 键为 OpenAPI tag 的精确名称               |
| `apiClients[tag].ignorePathParameters` | 缺失/null → `['tenantId', 'ownerId']` | 普通 API 客户端按精确名称省略位置路径参数 |
| 空数组                                 | 显式覆盖                              | 保留普通 API 的全部路径参数               |
| 命令客户端路径参数                     | 始终使用 `['tenantId', 'ownerId']`    | 按 tag 的 API 配置不影响命令客户端        |

数组替换默认值，不是追加。忽略参数不会删除 URL 模板占位符或自动提供参数值。请通过请求 URL 参数、客户端默认配置或 [CoSec 归属](https://fetcher.ahoo.me/zh/reference/cosec/interceptors-and-attribution)提供。

## 解析与失败

`options.configPath ?? DEFAULT_CONFIG_PATH` 选择配置文件，`DEFAULT_CONFIG_PATH` 为 `./fetcher-generator.config.json`。路径相对 process.cwd()，不是输入文档所在目录。JSON/YAML 根据内容识别。读取/解析失败会记录日志并吞掉错误，恢复 `{}`；解析后的形状没有验证，因此结构错误可能在生成阶段失败。没有环境变量合并或自动多文件配置层级。

内部接口 GeneratorOptions、Logger、GeneratorConfiguration、ApiClientConfiguration 描述这些形状，但不是包根命名导出。请按[程序化 API](./programmatic-api)示例推导公开构造器参数类型。

## 完整使用

把上述 JSON 保存为 `fetcher-generator.config.json`，确保规范中使用 `Items` 或 `Orders` 操作 tag，再运行：

```bash
pnpm exec wow-generator generate -i ./openapi.json -c ./fetcher-generator.config.json -o ./src/generated -t ./tsconfig.json
```

检查生成方法签名：Items 保留 tenantId/ownerId；Orders 省略 tenantId 但保留 ownerId。命令不执行生成的方法，也不连接其 API 服务。

## 实现源码

[typescript/wow-generator/src/generateContext.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/generateContext.ts#L24)

[typescript/wow-generator/src/types.ts:21](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/types.ts#L21)

[typescript/wow-generator/src/index.ts:35](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/index.ts#L35)
