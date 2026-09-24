---
title: '生成器 CLI'
description: '生成器 CLI — @ahoo-wang/wow-generator'
---

# 生成器 CLI

`wow-generator generate` 读取 JSON/YAML 并写入 TypeScript 模型和装饰器客户端。输出不取决于运行目录装了哪些包，但编译输出的项目需要安装它们，见[生成产物](./generated-output)。

## 选项

| 选项                               | 必填/默认值                   | 含义                                                                        |
| ---------------------------------- | ----------------------------- | --------------------------------------------------------------------------- |
| `-i, --input <file>`               | 必填                          | OpenAPI 3.x 文档的本地路径或 HTTP/HTTPS URL                                 |
| `-o, --output <path>`              | `src/generated`               | 输出根目录，相对当前工作目录解析                                            |
| `-c, --config <file>`              | `./wow-generator.config.json` | 生成器配置，见[配置](./configuration)；显式指定的文件必须存在               |
| `-t, --ts-config-file-path <file>` | 不指定                        | ts-morph 项目配置；应提供启用装饰器的 tsconfig                              |
| `-H, --header <header>`            | 无；可重复                    | 输入或配置为 HTTP/HTTPS URL 时发送的 `Name: value` 请求头                   |
| `--timeout <ms>`                   | `30000`                       | 获取 HTTP/HTTPS 输入或配置的超时毫秒数，超时即放弃                          |
| `--schema-docs <mode>`             | `summary`                     | 模型文档注释的内容：`summary`（标题、描述、约束）或 `full`（另含 JSON schema） |
| `--strict`                         | 关闭                          | 本次运行有警告时以退出码 4 结束                                             |
| `--verbose`                        | 关闭                          | 输出每一步（带时间戳），失败时输出原因和堆栈                                |
| `--quiet`                          | 关闭                          | 只输出警告和错误                                                            |
| `-v, --version`                    | 根命令                        | 输出包版本                                                                  |
| `-h, --help`                       | 根命令/子命令                 | Commander 帮助                                                              |

没有实现 watch、dry-run、framework、client-name 或 model-only 开关。

需要令牌的文档可以通过请求头获取，例如从环境变量读取令牌：

```bash
pnpm exec wow-generator generate -i https://api.example.com/v3/api-docs \
  -H "Authorization: Bearer $API_TOKEN" --timeout 60000 -o ./src/generated -t ./tsconfig.json
```

## 输出

默认只输出警告、错误和一行摘要，摘要包含文件数、输出目录、读取的配置和警告数：

```text
Generated 3 files into ./src/generated with /work/app/wow-generator.config.json, 1 warning
```

`--verbose` 输出每一步并带时间戳；`--quiet` 不输出摘要。只有在终端（TTY）且未设置 `NO_COLOR` 时才带符号，CI 日志和管道保持纯文本。

## 完整本地运行

把下面完整的 JSON 文档保存为 `openapi.json`。它定义一个 GET 操作及响应模型；生成期间不会请求该服务。

```json
{
  "openapi": "3.0.3",
  "info": {
    "title": "Items",
    "version": "1.0.0"
  },
  "tags": [
    {
      "name": "Items"
    }
  ],
  "paths": {
    "/items/{id}": {
      "get": {
        "operationId": "getItem",
        "tags": ["Items"],
        "parameters": [
          {
            "name": "id",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string"
            }
          }
        ],
        "responses": {
          "200": {
            "description": "Found",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/Item"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "Item": {
        "type": "object",
        "required": ["id"],
        "properties": {
          "id": {
            "type": "string"
          }
        }
      }
    }
  }
}
```

把下面配置保存为 `tsconfig.json`。编译生成结果前安装[运行时依赖](./generated-output)。[文档与操作](https://fetcher.ahoo.me/zh/reference/openapi/documents-and-operations)中的 TypeScript 编写示例是另一种格式，不是 CLI 的 JSON 输入。

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "skipLibCheck": true,
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true,
    "noEmit": true
  },
  "include": ["src/**/*.ts"]
}
```

```bash
pnpm add -D @ahoo-wang/wow-generator @ahoo-wang/fetcher-openapi typescript
pnpm exec wow-generator generate -i ./openapi.json -o ./src/generated -t ./tsconfig.json
pnpm exec tsc --noEmit -p ./tsconfig.json
```

仓库 fixture 构建后，从 `typescript/wow-generator` 目录运行：

```bash
node dist/cli.js generate -i "$PWD/test/demo.spec.json" -o /tmp/wow-demo-generated -t tsconfig.json
```

## 失败与退出码

| 退出码 | 类别 | 触发条件                                                                               |
| ------ | ---- | -------------------------------------------------------------------------------------- |
| 0      | 成功 | 文件已写出                                                                             |
| 1      | 内部 | 意外错误，例如写入失败或 tsconfig 无效；加 `--verbose` 重跑可看到堆栈                  |
| 2      | 输入 | 输入读不到或取不到、既不是 JSON 也不是 YAML、不是 OpenAPI 3.x 文档，或选项值无效       |
| 3      | 配置 | 配置读不到、解析不了或校验不通过                                                       |
| 4      | 规范 | 文档描述的代码无法生成：`$ref` 指向不存在的目标、两个 schema 生成同一个模型、两个操作生成同一个方法、Wow 元数据格式错误；或开启了 `--strict` 且本次运行有警告 |
| 130    | 中断 | SIGINT（Ctrl-C）                                                                       |

失败时只输出一行，写明文件或 URL 以及出错原因；`--verbose` 会附上原因和堆栈。缺少必填输入也会被 Commander 拒绝。格式根据内容识别，不依赖扩展名。

HTTP 加载在响应不是 2xx（如 `HTTP 401 Unauthorized`）、网络错误或 `--timeout` 超时时失败，不会把错误页当作文档解析。任何 `http:` 或 `https:` URL 都被接受，包括内网地址：CLI 不过滤主机，只传入可信的 URL。Swagger 2.0 文档会被拒绝，并提示先转换（例如用 swagger2openapi）；OpenAPI 3.1 文档可以没有 `paths`。

只有默认配置文件不存在时不报错。`-c` 指定的配置不存在，或任何配置读不到、解析不了、校验不通过，都以退出码 3 失败，见[配置](./configuration)。依赖进程成功退出前，应检查警告及[输出所有权](./generated-output)；CI 可用 `--strict` 把警告变为失败。

## 实现源码

[typescript/wow-generator/src/cli.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/cli.ts#L17)

[typescript/wow-generator/src/utils/clis.ts:90](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/clis.ts#L90)

[typescript/wow-generator/src/utils/parsers.ts:25](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/parsers.ts#L25)

[typescript/wow-generator/src/utils/resources.ts:16](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/resources.ts#L16)
