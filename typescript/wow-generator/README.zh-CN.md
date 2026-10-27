# `@ahoo-wang/wow-generator`

从 Wow 服务的 Manifest 生成带类型的 TypeScript 客户端；也可从任意 OpenAPI 3 文档生成
TypeScript 模型与 Fetcher Decorator 客户端。

## 从 Wow Manifest 生成

Wow 服务在 Manifest（`GET /wow/manifest`，或服务提交的 `manifest/baseline.json`）里描述它的
限界上下文、聚合、命令、事件与状态。生成器据此为每个聚合生成基于 `@ahoo-wang/wow-client` 的客户端：

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator typescript
curl -s http://localhost:8080/wow/manifest > manifest.json
pnpm exec wow-generator generate \
  --input ./manifest.json \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

每个限界上下文写出 `types.ts`（Manifest 中 schema 对应的命令、事件与状态类型）、
`contract.ts`（`<Context>Contract`：上下文名、错误码与各聚合，以及等待节点）和
`client.ts`（`<Context>Client`：网关与每个聚合的客户端）；每个聚合写出：

- `aggregate.ts`：聚合的描述、状态字段与事件联合类型；
- `commandClient.ts`：`<Aggregate>CommandClient`，每条命令一个方法，另有按等待的每个节点逐个返回结果的 `…Stream` 方法；
- `queryClient.ts`：`<Aggregate>SnapshotQueryClient`、`<Aggregate>EventQueryClient` 与 `<Aggregate>StateClient`。

<!-- typecheck-generated: examples/eshop/web/src/generated/basket -->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { EshopBasketClient } from './generated/index.js';

const fetcher = new Fetcher({ baseURL: 'http://localhost:8080' });
const { basket } = new EshopBasketClient({ fetcher });

await basket.commands.addBasketItem(
  { productId: 'p-1', quantity: 2, unitPrice: '10.00' },
  { id: 'buyer-1' },
);
const page = await basket.snapshots.paged({ filter: filter.matchAll() });
console.log(page.total);
```

`--strict` 让有警告的运行失败；在 CI 里从服务提交的 Manifest 重新生成客户端时正需要它。

## 从 OpenAPI 文档生成

任意 OpenAPI 3 文档——服务自己的路由或第三方接口——为每个组件 schema 生成一个模型，为每个
tag 生成一个 Fetcher Decorator 客户端：

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-decorator \
  @ahoo-wang/fetcher-eventstream
pnpm add -D @ahoo-wang/wow-generator typescript
pnpm exec wow-generator generate \
  --input ./openapi.yaml \
  --output ./src/generated \
  --ts-config-file-path ./tsconfig.json
```

生成的 API 客户端是装饰器类，编译它们的项目需要在 `tsconfig.json` 中设置
`"experimentalDecorators": true`。文档只按 OpenAPI 读取：schema 键、tag 与路径参数就是它们在文档里的
含义，不叠加 Wow 的约定。

## 版本

版本号跟随 Wow，次版本可能带有破坏性改动：用 `save-prefix=~` 或 `--save-exact` 让 Wow 包停在同一个次版本上，见[版本范围](https://wow.ahoo.me/zh/typescript/compatibility#版本范围)。`wow-client` 须与生成器的次版本一致。需要 Node 22.12 或更高版本，以及 TypeScript 6 或更高版本：CI 测试 6.0 到最新的 7.x。

生成成功时先输出警告（如有），最后输出一行摘要：

```text
Generated 12 files into src/generated with /work/app/wow-generator.config.json, 1 warning
```

## 选项与退出码

| 选项                               | 含义                                                             |
| ---------------------------------- | ---------------------------------------------------------------- |
| `-i, --input <file>`               | Wow Manifest 或 OpenAPI 3.x 文档：文件路径或 http(s) URL（必填） |
| `-o, --output <path>`              | 输出目录，默认 `src/generated`                                   |
| `-c, --config <file>`              | 配置文件，默认 `./wow-generator.config.json`                     |
| `-t, --ts-config-file-path <file>` | 项目的 `tsconfig.json`                                           |
| `-H, --header <header>`            | 读取 http(s) 输入或配置时发送的 `Name: value` 请求头，可重复     |
| `--timeout <ms>`                   | http(s) 请求超过这个毫秒数即放弃，默认 30000                     |
| `--schema-docs <mode>`             | `summary`（默认）或 `full`，后者还嵌入每个模型的 JSON schema     |
| `--strict`                         | 本次运行有警告时以退出码 4 结束                                  |
| `--verbose`                        | 输出每一步（带时间戳），失败时输出堆栈                           |
| `--quiet`                          | 只输出警告和错误                                                 |

http(s) 输入的响应不是 2xx 或超时都会失败。Swagger 2.0 文档会被拒绝，请先转换为 OpenAPI 3。

| 退出码 | 含义                                                                                     |
| ------ | ---------------------------------------------------------------------------------------- |
| 0      | 生成成功；或已输出 `--help`、`help`、`--version`                                         |
| 1      | 内部错误；加 `--verbose` 重跑可看到堆栈                                                  |
| 2      | 输入或用法：读不到、取不到、不是 OpenAPI 3.x；或选项值无效、缺少必填选项、未知选项或命令 |
| 3      | 配置：读不到、解析不了或校验不通过                                                       |
| 4      | 规范：文档无法生成代码，或开启 `--strict` 且本次运行有警告                               |
| 5      | 输出：清单解析不了或由更新版本的生成器写出、路径越出输出目录，或写入、删除文件失败       |
| 130    | 被中断（Ctrl-C）                                                                         |

失败时只输出一行，说明哪里失败；`--verbose` 会附上原因。

## 配置

配置作用于 OpenAPI 文档的 API 客户端：

```json
{
  "apiClients": {
    "Catalog": {
      "ignorePathParameters": ["tenantId", "ownerId"],
      "methodNames": { "getCatalog_1": "getArchivedCatalog" }
    }
  }
}
```

默认配置路径为 `./wow-generator.config.json`，相对 CLI 的运行目录解析。只有这个默认路径是
可选的：该位置没有文件时，CLI 按默认值生成。`--config` 指定的路径必须
存在；配置读不到、解析不了，或某个选项的形态不对，都会让本次生成失败，而不是退回默认值。
生成器不认识的选项——例如拼错的 `apiClient`——会被逐个点名警告并忽略。

摘要行会写出实际读取的配置的绝对路径，一次运行就能回答"我的配置生效了吗"；`--verbose` 还会
写出解析出的设置：

```text
[10:42:07] Configuration loaded from /work/app/wow-generator.config.json: apiClients=Catalog
```

如果摘要里没有配置路径，说明配置文件根本没送到生成器——请检查 CLI 的运行目录。

生成器把写出的文件记录在输出目录根下的 `.wow-generator.json` 中，之后的运行据此删除不再生成的
文件。请把它和输出一起提交。

`ignorePathParameters` 指定某个 tag 的客户端省略的路径参数（由拦截器填充），默认不省略。
`methodNames` 为 operationId 指定方法名。

## 生成的代码

- 每个文件以 `// Code generated by wow-generator. DO NOT EDIT.` 开头。相对导入以 `.js` 结尾，
  所以输出在 `NodeNext` 和 `bundler` 下都能编译，且写到哪里都一样。
- API 方法名依次取 `methodNames`、`x-fetcher-method`，否则取 operationId 最后一段的 camelCase
  （`catalog.get_item` → `getItem`）。新增操作不会让已有方法改名；同一客户端的两个
  操作同名时以退出码 4 失败。
- `--schema-docs full` 会在每个模型的文档注释中嵌入其 JSON schema；默认只含摘要。

## 属性可选性

OpenAPI schema 声明的每个属性都生成为必填。强类型后端根本没有"缺席的 `int`/`boolean`"可以返回，
满屏 `?` 的模型描述的是导出器而不是线上格式——导出器常把带默认值的属性排除在 `required`
之外，由此产生的空值判断纯属噪音。

文档真正想表达的可选性，则由各自合适的位置承载：

- **可空**留在类型里。可空属性生成 `T | null`，可能缺席的值依然不可能被忘记。
- **API 客户端请求体**也同样处理：JSON 请求体生成为 `PartialBy<Model, 'field' | ...>`，覆盖 schema
  未列入 `required` 的属性。

有一点值得知道：引用自身 schema 的非空属性没有有限字面量，因为每一层都还要下一层。能终止
的递归模型会把这条链声明为可空，生成 `T | null`，构造起来毫无问题。

## 核心能力

- Wow Manifest 输入：每个聚合基于 `@ahoo-wang/wow-client` 的命令、查询与状态客户端。
- 本地 JSON/YAML 与 HTTP(S) OpenAPI 输入：按 tag 分组的 TypeScript 模型与 Decorator API 客户端，path、query、header 参数和请求体都带类型，必填的在前。
- 递归生成 `index.ts`，并通过 ts-morph 格式化。
- 支持注入日志的编程式 `CodeGenerator` API，返回写出的文件与警告数。

每次契约变更后重新生成，并在发布前编译结果。

## 文档

- [从 Wow 服务生成客户端](https://wow.ahoo.me/zh/typescript/generate)
- [在 CI 中重新生成](https://wow.ahoo.me/zh/typescript/generate)
- [从任意 OpenAPI 文档生成客户端](https://wow.ahoo.me/zh/typescript/openapi-client)
- [wow-generator 参考](https://wow.ahoo.me/zh/reference/typescript/wow-generator/)
- [兼容性与版本](https://wow.ahoo.me/zh/typescript/compatibility)

[English](./README.md) · [许可证](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
