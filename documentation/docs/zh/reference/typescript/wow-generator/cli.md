---
title: '生成器 CLI'
description: '生成器 CLI — @ahoo-wang/wow-generator'
---

# 生成器 CLI

`wow-generator generate` 读取 JSON/YAML 并写入 TypeScript 模型和装饰器客户端。请在可解析 TypeScript 配置及已安装 Fetcher 包的目录运行。

## 选项

| 选项                               | 必填/默认值                       | 含义                                           |
| ---------------------------------- | --------------------------------- | ---------------------------------------------- |
| `-i, --input <file>`               | 必填                              | 本地路径或 HTTP/HTTPS URL                      |
| `-o, --output <path>`              | `src/generated`                   | 输出根目录，相对当前工作目录解析               |
| `-c, --config <file>`              | `./fetcher-generator.config.json` | 可选生成器配置；读取/解析失败会记录日志并忽略  |
| `-t, --ts-config-file-path <file>` | 不指定                            | ts-morph 项目配置；应提供启用装饰器的 tsconfig |
| `-v, --version`                    | 根命令                            | 输出包版本                                     |
| `-h, --help`                       | 根命令/子命令                     | Commander 帮助                                 |

没有实现 watch、dry-run、framework、client-name 或 model-only 开关。

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

## 失败与生命周期

输入预检查拒绝值时 CLI 退出码为 2，生成错误为 1，SIGINT 为 130。缺少必填输入也会被 Commander 拒绝。格式根据内容识别，不依赖扩展名。输入缺失、JSON/YAML 无效、组件引用无法解析、tsconfig 无效或写入失败都会使生成失败。

HTTP 加载使用 fetch 后读取 text，不检查 response.ok；HTTP 错误正文可能在后续作为文档解析时失败。CLI 没有 timeout/abort 选项。输入预检查阻止部分私有 IP 字面量，但允许回环地址，且不检查 DNS/重定向；它不是不可信 URL 的沙箱。程序化构造不会执行该 CLI 预检查。配置读取/解析错误例外：生成继续使用 `{}`。依赖进程成功退出前，应检查日志及[输出所有权](./generated-output)。

## 实现源码

[typescript/wow-generator/src/cli.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/cli.ts#L17)

[typescript/wow-generator/src/utils/clis.ts:90](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/clis.ts#L90)

[typescript/wow-generator/src/utils/parsers.ts:25](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/parsers.ts#L25)

[typescript/wow-generator/src/utils/resources.ts:16](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-generator/src/utils/resources.ts#L16)
