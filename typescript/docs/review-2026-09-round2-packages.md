# 9.2.0 首发前第二轮审查：wow-client、wow-react、wow-generator

2026-09-25，基于 `main` 的 `b17f36a3c`（#3408，含 #3409 把 wow-client 重构方案并入 `architecture.md`）。只读审查，这一步不修任何东西。首发（9.2.0）要等用户审过本报告并明确点头。

审查维度沿用用户 2026-09-24 定的三条：

1. 架构质量：职责清晰、高内聚、低耦合、可扩展、可维护；
2. 企业级产品体验，对这三个包来说就是开发者体验：API 手感、文档、错误与提示；
3. 可用性与易用性（这三个包没有 UI，可访问性不适用）。

严重程度：

- **P0**：挡住 9.2.0；
- **P1**：GA 之前处理，或者必须在 9.x 冻结公开面之前定下来；
- **P2**：以后再做。

## 结论

| 包                         | 结论                                                                                                                                                                                                                                                                                       |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@ahoo-wang/wow-client`    | 架构到位，可以冻结。分层与 `architecture.md` §2.2 一致，`src/` 里没有环，没有孤立文件，也没有重构留下的旧名字。覆盖率 100 / 99.79 / 100 / 100，名字、签名、线协议、端点四条基线都齐。挡发布的只有 P0-1（`listQuery` 省略 `limit`），修起来很小。冻结前建议再删几个低价值的公开名（P1-1）。 |
| `@ahoo-wang/wow-react`     | 状态机自有、边界干净，35 个公开名一致，可以冻结。有一个真实的行为缺陷：受控 `query` 变成 `undefined` 时会重发上一个查询（P1-6），走查已复现。另有两处易用性陷阱：直接传客户端方法当 `execute`，类型能过、运行时报错；`FIELDS` 推断。还缺现行的设计文档。                                   |
| `@ahoo-wang/wow-generator` | B0～B7 做到了设计文档说的：层次有 lint 把守并且真的成立，分析是纯数据，生成物有 golden、probe 和确定性测试逐字节看住。没有 P0。冻结前要定下生成代码的几处公开形状：API 客户端的流类型（P1-9）、生成的名字（P1-13）。CLI 的用法错误退出码也不对（P1-10）。                                  |
| 发布工程与文档             | 流水线、准入、包检查、npm 冒烟、回滚手册都齐。两个问题挡发布：按快速开始照做，第 5 步编译失败（P0-2）；README 与兼容矩阵在 `limit` 上承诺了服务端做不到的事（P0-1）。                                                                                                                      |

**总体：现在还不能发 9.2.0。** 两个 P0 都是小修，合计约半天。修完、并由用户对下文「需要用户拍板」逐条定下后，可以进入 `RELEASING.md` 的 A 最后一项（发版 PR）和 C（`9.2.0-rc.0`）。

## 证据从哪来

1. **新用户走查。** 在仓库外的 `mktemp -d` 目录里，用 `pnpm pack` 打出三个 tarball（9.1.5，即发版提交前的版本号）。然后逐字照文档站的快速开始、错误处理、命令与查询、wow-react 参考页操作。服务端是已发布的镜像 `ghcr.io/ahoo-wang/wow-example-server:9.0.10`（MongoDB 8.0.14，存储与快照都用 mongo）；这是今天用户最可能在跑的已发布版本。环境：Node 24.11，pnpm 10.34，`pnpm add -D typescript` 装到的 TypeScript 是 **7.0.2**。
2. **三路只读审查**，各看一个包：对照 `architecture.md` 检查分层、死代码、环和公开面（`test/surface/`、`test/api/*.api.md`），并各跑一次带覆盖率的测试（`--maxWorkers=2`）。
3. **发布就绪核对。** tarball 内容与元数据、README、迁移指南、兼容矩阵、`RELEASE_NOTES_TEMPLATE.md`、`RELEASING.md`（含 C′）逐项对照。

走查脚本与日志没有提交，复现步骤见文末。

## P0：挡住 9.2.0

### P0-1 省略 `limit` 的列表查询在 Wow 8.12～9.1.3 上被拒，README 和兼容矩阵却承诺「用服务端默认值」

**证据**

- 对 9.0.10 服务端，`snapshots.listState(listQuery({ filter }))`、`listQuery()`、`events.list(listQuery({ filter }))` 都应答 400：`IllegalArgument: HTTP list query limit[0] must be between 1 and 1000.` 带上 `limit: 10` 就成功。
- wow-react 的 `useListStreamQuery` 照参考页的写法（不带 `limit`），`error` 同样是 `[IllegalArgument] HTTP list query limit[0] …`；带 `limit: 5` 就正常。
- 服务端的来历：
  - 这条校验在 `HttpQueryGuard.validateResultSize`，#3017 引入，从 **v8.12.0** 起的每个 tag 都有它；
  - 把 `limit = 0` 改写成服务端默认值 100 的 `applyListDefault` 是 #3267，**只有 v9.1.5 包含**；
  - 所以 8.12.0～9.1.3（没有 9.1.4 这个 tag）都会拒绝省略 `limit` 的列表查询。
- 文档与代码的承诺：
  - `typescript/wow-client/README.md`：「A list without a `limit` gets the server's default list size.」README 会原样冻结进 tarball；
  - `src/dsl/queryable.ts` 的 JSDoc：「Absent lets the server apply its default list size」；
  - 兼容矩阵把「Wow 9.x」「8.11.x」都列为根入口可用，没有提这一点；
  - 下列示例在这些服务端上都会失败：错误处理指南的 `readAll`（`listQuery()`）、wow-react 参考页的 `PaidOrders` 流示例、wow-client 参考页若干列表示例。
- 迁移回归：`/legacy` 的 `listQuery` 默认 `limit = 10`（`architecture.md` 第 373 行），fetcher-wow 的用户一直靠这个默认值。迁移指南只写了「`filter` 默认为 `filter.matchAll()`」，没写 `limit` 的默认值没有了。
- CI 为什么没发现：契约矩阵只有同源服务端（≥9.1.5，会补默认值）、8.11.5（没有这条校验）和 8.10.8（只做类型检查），恰好绕开了 8.12～9.1.3；集成测试也总是带着 `limit`。

**修法**（二选一，见「需要用户拍板」第 1 条）

- **推荐：** 根入口的 `listQuery()` 在省略 `limit` 时发送 `limit: 100`，与服务端的 `DEFAULT_LIST_SIZE` 一致。这样线协议对 9.1.5+ 不变（它本来就补 100），对 8.12～9.1.3 也不再报错。JSDoc 和 README 改为「省略时为 100，与 Wow 的默认列表大小一致」；`dsl-wire.json` 的 golden 随之变化（有意）。
- **或者**只改文档：README、JSDoc、兼容矩阵写明「Wow 8.12～9.1.4 必须带 `limit`」，所有示例都带上 `limit`。
- **两种做法都要：**
  - 迁移指南加一行「`/legacy` 的 `listQuery` 默认 10，根入口 …」；
  - `typescript-contract.yml` 的已发布服务端矩阵加一个 9.x 镜像（9.0.x 或 9.1.3），做运行时冒烟，并加一条不带 `limit` 的列表用例（另见 P1-17）。

### P0-2 快速开始照做，第 5 步编译失败

**证据**

- 照 `guide/typescript/quick-start.md` 第 2 步安装、第 2 步的 `tsconfig.json`、第 3 步生成、第 4、5 步写 `src/cart.ts`、`src/main.ts`。
- 然后执行 `pnpm exec tsc -p tsconfig.json`，报 3 处错：`TS2591: Cannot find name 'process'. Do you need to install type definitions for node? … add 'node' to the types field`。
- 安装命令里没有 `@types/node`。TypeScript 6 起 `types` 默认不再自动带上 `@types/*`，所以只装 `@types/node` 仍然报同样的错，还要在 `tsconfig.json` 里加 `"types": ["node"]`。
- 补上这两处以后，编译、运行都通过，输出与页面一致：`SNAPSHOT: cart … v1`、`items: [ { productId: 'book-1', quantity: 2 } ]`、`carts holding book-1: 1`。
- 根因：文档站的样例检查（`documentation/test/typescript-samples.mjs`）用它自己的编译选项（`moduleResolution: 'Bundler'`、`types: ['node']`、`typeRoots`），不是页面上写给用户的 `tsconfig.json`。所以样例「通过类型检查」，照做却编译不过。

**修法**

- 中英文快速开始的安装行加上 `@types/node`，`tsconfig.json` 加上 `"types": ["node"]`。
- 样例检查对带 `<!-- typecheck-generated -->` 的快速开始，用页面上的 `tsconfig.json` 原文编译。更好的做法是在 `package-check.mjs` 的干净项目里加一个「照快速开始」探针：同样的安装行、同样的 tsconfig、编译生成代码和 `main.ts`。见 P1-16。

## P1：GA 之前，或者冻结公开面之前

### wow-client

**P1-1 冻结前删掉几个低价值或重复的公开名**（原 C-1、C-3、C-4）

- `QueryEventStreamResultExtractor`、`CommandResultEventStreamResultExtractor` 和包住它们的 `COMMAND_STREAM_ENDPOINT`、`QUERY_STREAM_ENDPOINT` 同时在根入口导出（`root.api.md:465,1500`）。
  - 仓库里 wow-client 以外没有人导入它们（生成器只在注释里提到一个），AGENTS.md 也要求新代码用预设；
  - A3 以后流吐出的是行，名字里的 `EventStream` 已经名不副实。
- `ReadableDomainEventStream`（`domainEventStream.ts:151`）没有任何使用者。
- `AbacTagsApplied`、`ApplyAbacTags` 只在 wow-client 自己的 `src/` 里用，生成器也不映射它们。
- `LogicalField`：一个全新的包，首发就带着一个「v10 移除」的弃用别名，在根入口和 `/dsl` 上。
- 修法：发布前删掉，或者写明留下的理由；走 `test/surface` 加 `-u`。见「需要用户拍板」第 2 条。

**P1-2 约 55 个根入口导出没有文档注释**（原 C-2）

- `root.api.md` 里有 55 个顶层声明、236 个成员标着 `(undocumented)`，其中包括：
  - `QueryClientFactory` 类本身；
  - `LoadStateAggregateClient`、`LoadOwnerStateAggregateClient`；
  - `AggregationQuery`、`cursorQuery()`、`CommandBody`、`WowMetadata`；
  - 全部聚合枚举，以及 `CommandHeaderOptions` 的大多数字段。
- `filter.and`、`filter.or`、`filter.nor` 在编辑器悬停时显示成内部名 `typeof andFilter`。
- 修法：
  - 至少给上面这些类、`AggregationQuery`、`cursorQuery`、`CommandHeaderOptions` 补一行摘要；
  - `api-report.mjs` 在根入口新增未注释导出时失败。

### wow-react

**P1-3 直接把客户端方法传给 `execute`：类型能过，运行时报一个看不懂的错**（原 R-3，走查已复现）

- `useListQuery<CartState, Fields>({ execute: client.listState, … })` 能编译，渲染后 `error.message` 是 `Cannot read properties of undefined (reading 'requestExecutors')`：装饰器方法离开实例以后 `this` 是 `undefined`。
- README 说客户端方法「has exactly the `(query, attributes, abort)` shape `execute` takes」，参考页说「直接转交即可」，都在引人这么写（示例本身用的是箭头函数）。
- 修法：
  - README（中英）、`QueryExecutor` 和各 Hook 的注释写明「用箭头函数包一层，或 `.bind(client)`」；
  - 以后可以让 wow-client 的客户端方法自绑定（P2）。

**P1-4 `FIELDS` 推断：生成的客户端照参考页写法编译不过，错误信息是一整屏**（原 R-4，走查已复现）

- 参考页的流示例写成 `useListStreamQuery<OrderState>({ … execute: (q, a, c) => client.listStateStream(q, a, c) })`。它用的是字段为 `string` 的手写客户端。
- 换成生成的客户端（字段是 `` `${CartAggregatedFields}` ``）以后，同样的写法报 TS2345/TS2769，信息有 10 行以上，里面是 20 个字段名的联合。
- 不写类型参数时，`FIELDS` 从第一个查询推断，之后 `setQuery` 只接受那个查询里出现过的字段。
- 设计文档 B3 已记录这是 TypeScript 的限制。
- 修法：
  - 参考页和 README 的示例改用生成客户端的写法 `<CartState, Fields>`，并把 `Fields` 的写法 `` type Fields = `${CartAggregatedFields}` `` 放在第一个示例里；
  - 「不写类型参数会收窄 `setQuery`」写成一条显眼的提示；
  - 生成器顺带导出一个 `CartFields` 类型别名，省掉模板字面量（P2，也可以在 P1-13 里一起定）。

**P1-5 没有现行的设计文档**（原 R-5）

- `docs/design/refactor-2026-09.md` 读起来是审查加计划：§1.2、§2 还在描述基于 fetcher-react 的架构，引用的是 `fr core:…` 行号。§6 自己说「全部做完后，本页并入包的设计文档」，但这一步没有做。
- AGENTS.md 却把它的 §3.3 当作行为契约。wow-client 和 wow-generator 都有 `architecture.md`。
- 修法：写 `docs/design/architecture.md`，包含模块与依赖图、§3.3 状态表（加上 P1-6 那一行）、端点身份规则、B1～B4 的决定；AGENTS.md 改为指向它；旧文档标为历史。

**P1-6 受控 `query` 变成 `undefined` 时，Hook 用上一个查询再请求一次**（原 R-1，走查已复现）

- 证据：`useSingleQuery({ query: on ? singleQuery(…) : undefined, execute })`。把 `on` 切到 `false` 后，`execute` 的调用次数从 2 变成 3，状态回到 `loading`，然后又是 `success`，结果还是旧的那条。
- 原因：`src/internal/useQueryRunner.ts` 的第二个 `useEffect` 里，`query` 为 `undefined` 时不更新 `current`，却仍然 `execute()`。
- `id ? singleQuery(…) : undefined` 是条件查询的常见写法：用户清空选择以后，界面会拿旧 id 再请求一次。§3.3 的状态表没有这一行，测试也没覆盖。
- 修法：受控 `query` 变为 `undefined` 时中止进行中的请求、不执行，状态回到 `idle`、保留 `result`，与已定的 `abort()` 语义一致（我按第一原理定的，理由是「出错或中止后保留结果」是用户 09-24 定的方向）。在 §3.3 加这一行，在 `queryIdentity.test.tsx` 钉住。
- 这是缺陷修复，不属于破坏性改动，补丁版也能发。但它在冻结的状态机里，建议随首发修掉。

**P1-7 fetcher 的 peer 范围包含尚未发布的 6.x**（原 R-6，影响三个包）

- `pnpm-workspace.yaml` 的 `peers` 是 `^5.1.5 || ^6.0.0`。
- wow-react 依赖 fetcher 的 `getFetcher`、`DEFAULT_FETCHER_NAME`、`JsonResultExtractor`、`fetcher.urlBuilder.baseURL`；wow-client 与生成代码依赖装饰器语义。6.0 还没有发布，没有人验证过。
- 对一个没发布的大版本承诺兼容，等于把 6.0 的破坏性改动交给用户去发现。peer 范围以后只放宽不收紧（RELEASING「peer 范围」），现在去掉 `^6.0.0`、6.0 发布并验证后再加回去，是不破坏兼容的。
- 见「需要用户拍板」第 5 条。

**P1-8 可选的聚合查询 Hook 缺位**（原 R-2）

- 公开面只有 single、list、paged、count、list-stream 五种。wow-client 的 `aggregate`、`aggregateStream`、`cursor`、`getById`/`getStateById` 都没有对应 Hook，内部通用的 `useQueryRunner` 也不公开，所以 `AggregationQuery` 放不进任何一个 Hook。
- 聚合是 wow-client 的主打能力之一。
- 修法：公开 `useQuery<Q, R, E>`（即现在的 runner），再加 `useAggregateQuery`、`useAggregateStreamQuery`。都是新增，不破坏兼容，可以放在 9.2.0 或 9.3。见「需要用户拍板」第 4 条。

### wow-generator

**P1-9 生成的 API 客户端，流方法仍返回事件信封**（原 G-2，走查所见一致）

- 生成的 `CartApiClient.addCartItem` 用 `JsonEventStreamResultExtractor`，返回 `Promise<JsonServerSentEventStream<CommandResult>>`（见 `src/emitters/decorators.ts:67-70`、`src/emitters/apiClients.ts:233-247`），并直接导入 `@ahoo-wang/fetcher-eventstream`。
- 同一个 Wow 命令流因此出现两种类型：命令客户端（B1）和 wow-client（A3）已经改成行，服务端的错误事件也会变成 `WowError`；API 客户端这条路上错误事件不会变。
- 生成代码是公开面，冻结后改返回类型就是破坏性改动。
- 修法：流元素是 `CommandResult` 时，生成 `COMMAND_STREAM_ENDPOINT` 和 `CommandResultEventStream`。非 Wow 文档的流吐行还是信封，要有意定下来。见「需要用户拍板」第 3 条。

**P1-10 CLI 的用法错误以退出码 1 退出，而 1 在 README 里是「内部错误」**（原 G-1）

- `node dist/cli.js generate`（缺 `-i`）和 `generate -i x.json --bogus` 都以 1 退出。README 第 58～59 行说 1 是内部错误（「加 `--verbose` 重跑」），2 是「选项值不合法」；而 `--timeout abc` 确实以 2 退出，两条路径对不上。
- 修法：
  - `setupCLI` 里 `program.exitOverride()`，把 help、version 以外的 `CommanderError` 映射为 `EXIT_CODES.input`；
  - 用真实的 commander 测这条路径（`test/cli/program.test.ts` 现在 mock 了 commander）。

**P1-11 `@ahoo-wang/fetcher-openapi` 是一个运行时用不到的 peer**（原 G-3）

- 源码里对它的导入全是 `import type`。`verify-package.mjs` 已经保证构建产物不加载任何 `@ahoo-wang` 包、公开声明也不引用它；生成代码也不导入它。
- 可是 README 第 11、21 行、文档站 `index.md:13`、快速开始第 2 步和迁移指南的 peer 表都让用户安装它。
- 同理，wow-react 的 `@ahoo-wang/fetcher-eventstream` peer 也用不到（`src` 从不导入，原 R-12）。
- 修法：首发前把两者从 `peerDependencies` 移到 `devDependencies`，同步所有安装行与 peer 表。删一个 peer 不破坏兼容，但首发前做最干净。
- 2026-09-25 用户定：按修法做，首发前删掉运行时用不到的 peer。
  - wow-generator：`@ahoo-wang/fetcher-openapi` 从 peer 移到 devDependencies（类型检查与构建仍要它的声明）；`catalog:peers` 里不再有它。
  - wow-react：`@ahoo-wang/fetcher-eventstream` 从 peer 删掉，devDependencies 也不留（`src`、`test` 都不导入它；它仍是 wow-client 的 peer，所以安装行照旧带上它）。
  - 核对：两个包的 `dist` 都不出现这两个名字；从打好的 tarball 在临时目录安装生成器、不装 fetcher-openapi，`wow-generator generate` 能生成。
  - 同步：两个包的 README（中英）、文档站的 TypeScript 概览、快速开始、兼容性、迁移指南、通用 OpenAPI 生成页、生成器参考、wow-react 参考、`skills/wow-generator`、`RELEASING.md`（C′ 第 6 步核对它不在 `node_modules` 里；「peer 范围」一节记下规则），以及两个包的设计文档。

**P1-12 清单文件读不懂时的报错不说怎么办，也没有「新版本写的清单」规则**（原 G-4）

- `src/output/outputStore.ts:95-124` 只报「Invalid generation manifest: <path>」。提交的 `.wow-generator.json` 有合并冲突，和更新版本生成器写的 `version: 2` 清单，报的是同一句。
- 修法：
  - 分开说原因与办法：「解决冲突或删掉它；删掉意味着这次不清理过时文件」；
  - 「由更新的 wow-generator（版本 N）写出，请升级」；
  - 把清单 v1 的演进规则写进 `architecture.md` §3。

**P1-13 冻结前定下生成代码的名字**（原 G-13，并入走查所见）

- `MockVariableCommandCommand`、`MountedCommandCommand`：名字本来就以 `Command` 结尾，仍然再加一个后缀。
- `CartDomainEventTypeMapTitle`：枚举成员是 snake_case 的事件名，值有的是中文标题、有的是原名。
- 文件名大小写混用：`CartApiClient.ts` 和 `commandClient.ts`、`boundedContext.ts` 并列。
- 服务端默认命令生成的方法名带 `default` 前缀（`defaultDeleteAggregate`、`defaultRecoverAggregate`）。
- 路径变量按字母序作为位置参数（`mockVariableCommand(customerId, id, mockEnum, …)`，路径里的顺序是 `{id}/{customerId}/{mockEnum}`）。以后新增一个路径变量，已有调用的实参位置就会错开。
- 这些现在改很便宜，发布后改每一处都是破坏性改动。见「需要用户拍板」第 3 条。

**P1-14 「输出能编译才写盘」这条不变量，失败路径没有测试**（原 G-5）

- `src/finalize/verification.ts` 的覆盖率是语句 58%、分支 25%（第 52～57、63 行）。这是 `architecture.md` §1 的第 2 条不变量。
- 修法：加一个流水线测试，经 `PROJECT_SEAM` 注入一个引用未声明名字的模块，断言退出码 1、什么都没写、清单不变。

### 文档、发布与跨包

**P1-15 TypeScript 版本口径与实际不符**

- 快速开始写「TypeScript 5 或更高版本（样例用 TypeScript 6 检查）」，兼容矩阵写「CI 检查的是 6.0」。
- 今天 `pnpm add -D typescript` 装到的是 **7.0.2**。走查在 7.0.2 下编译、运行都通过，装饰器也正常；但 CI 从没测过 7，也从没测过 5。
- 修法：`package-check.mjs` 的干净项目按最低与最高支持版本（例如 5.x 的下限、6.0、7.0）各编译一遍；文档写明测过的范围。测不了 5 就把下限改成 6。
- 2026-09-25 用户定：下限是 TypeScript 6；CI 以使用者身份在 6.0 和最新的 7.x 上对打包后的包做类型检查。
  - `package-check.mjs` 在同一个干净项目里用 npm 别名装 `typescript@~6.0.0` 与 `typescript@^7.0.0`（`TYPESCRIPT_VERSIONS`），三种解析方式各编译两遍；仍在原来的 `package` 任务里（发布预检与 `npm-smoke` 同样），不新增必需检查。编译器没有给出诊断却失败时也算失败。
  - 没有哪个包声明 `peerDependencies.typescript`，所以不加。
  - 文档写「CI 测试 TypeScript 6.0～7.x；最低 6」：兼容性页（中英）、快速开始、三个包的 README（中英）、`typescript/AGENTS.md`、`skills/wow-generator`、`RELEASING.md`（C′ 第 6 步用 6.0 再编译一遍）。

**P1-16 CI 里没有「照文档做一遍」的检查**（P0-2 的根因）

- 样例检查只证明片段在一个宽松的编译环境里能过，不证明照页面操作能成功。
- 修法：在 `package-check.mjs` 的干净项目里（或新增一个探针）逐字执行快速开始：
  - 页面上的安装行和 `tsconfig.json`；
  - 对提交的规范文件（`typescript/wow-generator/test/demo.spec.json`）运行生成；
  - 编译第 4、5 步的代码。

  这样文档漂移就会让 PR 失败。

**P1-17 契约矩阵缺少已发布的 9.x 服务端**（P0-1 的根因）

- `typescript-contract.yml` 只有同源服务端、8.11.5（运行时冒烟）和 8.10.8（只做类型检查）。用户最常见的 9.0.x、9.1.x 从没有在运行时测过。
- 修法：矩阵加一个已发布的 9.x 镜像（本机已有 `wow-example-server:9.0.10`），跑与 8.11.5 同一套冒烟，加上不带 `limit` 的列表和列表流用例。

**P1-18 C′（rc 在 wow-project-template 上试用）覆盖不到 wow-react 和文档**

- C′ 只验证生成、构建，再用一条命令和一次按 id 查询冒烟。
  - 模板的 client 没有 React，wow-react 在 rc 上没人用过；
  - 照快速开始从 npm 走一遍放在了 F.2，也就是 `latest` 发出以后，出了问题只能发补丁。
  - 另外，C′ 没有写模板服务端跑的是哪个 Wow 版本；这个版本决定了 P0-1 这类行为。
- 修法：
  - C′ 增加一步，在空目录里用 `@next` 照快速开始走一遍（与 F.2 相同的步骤，提前到 rc）；
  - 增加一个 wow-react 冒烟，可以放在模板的一个测试里，或者直接用 integration-test 的 react 用例对 rc tarball 跑一遍；
  - 在 MIGRATION「进度」里记下模板服务端的 Wow 版本。
- 2026-09-25 更新：用户定 C′ 不再用 wow-project-template，改在补偿控制台（`compensation/dashboard`）上做。控制台同时用 wow-client、wow-react 和 wow-generator，服务端是 rc tag 上的补偿服务，所以 wow-react 与服务端版本这两条缺口不再成立；「照快速开始用 `@next` 走一遍」这条修法与此无关，仍然有效。

## P2：以后再做

以下是三路审查与走查的其余发现。每条都不影响首发，按包归类，编号保留原审查编号便于追溯。

**wow-client**

- **C-5** 未来新增一个命令阶段时，旧客户端会把它当作错误事件（`src/transport/eventStreams.ts:28-30,83-90` 只认自己的 `CommandStage`）。改为只在 `isErrorInfo(data) && data.errorCode === event.event && !('stage' in data)` 时判错，并补一个未知阶段的测试。
- **C-6** 客户端文件的 JSDoc 示例没有纳入 `test/jsdocExamples.test.ts`（`snapshotQueryClient.ts` 14 个、`eventStreamQueryClient.ts` 6 个等）。其中 `error/headers.ts:14`、`routing.ts:43` 两个示例编译不过。
- **C-7** 分层规则的正则可以被 `'../transport'` 或 `'../dsl.js'` 绕过。改为 `(^|/)<dir>(/|\.js$|$)`，并在 `layerBoundaries.test.ts` 加一个违例。
- **C-8** `MaterializedSnapshot`、`DomainEventStream` 这类线协议类型放在 `client/` 下，`dsl.ts` 从 `client/` 再导出。可以移到 `model/`，名字与签名不变。
- **C-9** 若干小的不一致，以后都能用放宽的方式修：
  - `waitStrategy` 接受字符串名，DSL 的枚举参数不接受；
  - `CommandClient`、`WowMetadataClient` 没有 `*Api` 接口；
  - `QueryClientOptions` 重复声明 `contextAlias`；
  - `QueryApi` 的参数名与工厂函数同名（F16 计划过，没做）。
- **C-10** 覆盖率门槛 98/97/98/98，实测 100/99.79/100/100。抬到 99.5/99/99.5/99.5，并更新注释。
- **C-11** 过时文档：
  - AGENTS.md 引用了不存在的 `test/command/commandHttpHeaders.test.ts`；
  - `docs/compat-debt.md:51` 说 `LogicalField` 在 `filter.ts`；
  - `docs/superpowers/` 下 7 个文件还是重构前的布局；
  - `test/client/` 与 `test/clients/` 并存。
- **X-1** wow-client 参考页「聚合构造器」有内部备注（「对齐 Wow `main` 的 `fd1b3cd46`」），整页读起来像机翻、信息密度过高。按任务重写，去掉内部提交号。

**wow-react**

- **R-7** 错误类型要靠用户自己处理：`toWowError` 是异步的，渲染时拿不到 `WowError`。在 README 里写出「`E` 传 `WowError`，在 `execute` 里 `throw (await toWowError(x)) ?? x`」的写法。
- **R-8** SSR 只覆盖到「首帧 loading、不请求」，没有 `initialResult`。在 README 里写明；以后可以新增。
- **R-9** 测试文件头还写着「fetcher-react 5.1.3 underneath」。
- **R-10** 覆盖率门槛 95/83/92/98，实测 98.46/96.95/98.36/99.41。抬到约 97/95/96/99。
- **R-11** eslint 忽略全部测试文件；`react-compiler` 规则只是警告，而构建依赖编译器；没有禁止导入 fetcher-react 的 lint 规则（只有构建后脚本在把关）。
- **R-12** `dist/internal/*.d.ts` 随包发布，但经 `exports` 访问不到。
- **R-13** `QueryExecutor<Q, R>` 与 `ListStreamExecutor<R, Q>` 类型参数顺序相反。为兼容 fetcher-react 迁移，我倾向保持现状并在设计文档里记一笔。
- **R-14** 两个没有名字、`baseURL` 相同的 Fetcher 被当成同一个端点（例如按租户各建一个只差拦截器的 Fetcher，切换时不会重新查询）。写进 README。
- **R-15** 集成测试缺：用客户端的 `useSingleQuery`/`useListQuery`、经 `toWowError` 的 HTTP 错误、对真服务端的 abort/reset。
- **X-2** 参考页还写着列表流「用 wow-client 的 `QueryEventStreamResultExtractor` 读取响应」「把每个事件的 `data` 收进 `items`」。这是 A3 之前的说法，A3 后是行。

**wow-generator**

- **G-6** `--timeout` 的说法三处不一致：CLI、README、`api/options.ts`。它其实也作用于远程配置文件。
- **G-7** `--help` 太单薄，没有退出码，也没有说明 `-t` 的必要。以 `fetcher-generator` 调用时不打印弃用提示。
- **G-8** `dist/cli.js` 内联了整个 `package.json`，包括 `devDependencies`、`scripts` 和 `catalog:`、`workspace:~` 字样。只导入 `version` 即可。
- **G-9** 与设计文档的小漂移：
  - §2.2 的图少了 `pipeline → emit`、`emitters → wow`、`emitters → openapi` 三条边；
  - `types/` 没有 ts-morph 导入限制；
  - `analysis/modelInfo.ts:25` 有一个违反「无 barrel」的再导出。
- **G-10** 覆盖率门槛 97/92.5/98.5/98，实测 98.39/95.22/100/99.16。抬到约 98/94.5/99.5/98.5。
- **G-11** `GeneratorOptions.schemaDocs` 用到的 `SchemaDocs` 没有导出。新增导出不破坏兼容。
- **G-12** 其他小项：
  - `runCLI` 的注释与行为不符；
  - 约 60 个导出只在本文件内使用，可以考虑在 lint 里加 knip；
  - `types/typeResolver.ts` 855 行，是唯一的热点；
  - 同一实例并发调用 `generate()` 的行为没有定义。
- **X-3** 生成事件流在无类型时是 `JsonServerSentEventStream<any>`，是生成代码里仅剩的 `any`。

**跨团队（Kotlin 服务端，记入各自待办）**

- **X-4** 流式命令等待 `PROJECTED`：示例的购物车没有投影器，所以 10 秒后以 `RequestTimeout` 结束，`errorMsg` 是 Reactor 的内部文字（`Did not observe any item or terminal signal within first signal from a Publisher in 'source(FluxDefer)' …`），用户看不出原因。
  - 服务端应给出「等待阶段 PROJECTED 在 10000ms 内未到达」这类信息；
  - 错误处理指南的 `addAndFollow` 示例对购物车用 `PROJECTED`，照做必然失败，应改为 `SNAPSHOT`。
- **X-5** 对数组路径做 `terms` 分组（`state.items.productId`），服务端回 `QuerySchemaValidation: Query compatibility [INCOMPATIBLE] is rejected by mode [COMPATIBLE].`，不说怎么办。正确写法是 `elements: [aggregation.element('state.items')]` 再按 `productId` 分组。建议服务端的信息指向 `elements`。
- **X-6** fetcher 的错误类在压缩产物里叫 `e`，日志里显示成 `e: fetch failed`。属于上游 fetcher 的构建配置。
- **X-7** 文档里的 `.npmrc` 设了 `save-prefix=~`，会让 fetcher 的依赖也存成 `~5.1.5`；兼容矩阵却说「Fetcher 的 peer 用插入符范围即可」。在版本范围一节补一句：对 fetcher 的安装显式写 `@^5.1.5`，或者接受 `~`。

## 新手走查记录

| 步骤        | 照做的是什么                                                                                            | 结果                                                                                                                                                                        |
| ----------- | ------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 安装        | 快速开始第 2 步（先按版本范围一节写 `.npmrc` 的 `save-prefix=~`；三个 Wow 包用 tarball 代替 npm）       | 通过。`typescript` 装到 7.0.2；fetcher 装到 5.1.5，并存为 `~5.1.5`（X-7）                                                                                                   |
| 生成        | `wow-generator generate -i http://…/v3/api-docs -o src/generated -t tsconfig.json`                      | 通过，输出 `Generated 14 files into src/generated`，与页面一致。名字问题见 P1-13，流类型见 P1-9                                                                             |
| 编译        | 第 4、5 步的代码，`tsc -p tsconfig.json`                                                                | **失败**（P0-2）；补 `@types/node` 与 `"types": ["node"]` 后通过                                                                                                            |
| 命令 + 查询 | `node dist/main.js`                                                                                     | 通过，输出与页面一致                                                                                                                                                        |
| 错误处理    | 错误处理指南的表格逐行核对                                                                              | 空商品：400 `CommandValidation`，`bindingErrors` 有 `quantity`、`productId`；不存在的 id：404 `NotFound`；没有服务监听：`toWowError` 为 `undefined`。与表格一致             |
| 流式命令    | 生成的 `CartStreamCommandClient`，`for await`                                                           | 等到 `SNAPSHOT`：得到 `SENT`、`PROCESSED`、`SNAPSHOT` 三条结果；非法命令体：以 `WowError(CommandValidation)` 结束，带 `bindingErrors`。等到 `PROJECTED`：见 X-4             |
| 查询流      | `listStateStream` 的 `for await`                                                                        | 带 `limit` 通过；不带 `limit` 被拒（P0-1）                                                                                                                                  |
| 列表        | `listState(listQuery({ filter }))`、`events.list(…)`                                                    | 不带 `limit` 被拒（P0-1）                                                                                                                                                   |
| 聚合        | `aggregation.query({ elements, groupBy, metrics })`                                                     | 通过，得到 `[{ product: 'pen', qty: 3 }]`；对数组路径直接分组见 X-5                                                                                                         |
| 事件        | `events.load(id, 1, 100)`                                                                               | 9.0.10 上 404，兼容矩阵「客户端较新、服务端较旧」已经写明                                                                                                                   |
| React       | jsdom + StrictMode，`usePagedQuery`、`useListStreamQuery`、`useListQuery`、`useSingleQuery`，对真服务端 | 分页、带 `limit` 的流、not found 经 `toWowError` 得到 `NotFound`：都符合文档。不带 `limit` 的流：P0-1。直接传方法：P1-3。受控查询置空：P1-6。照参考页的单类型参数写法：P1-4 |

走查中表现好的地方：

- `toWowError` 在命令、查询、流三条路径上给出的都是同一个形状；
- 流吐出的是行，`for await` 自然写得出来；
- 生成器一次成功，摘要清楚；
- 快速开始的输出与页面逐行一致；
- TypeScript 7 下装饰器与生成代码都没问题。

## 测试与覆盖率

| 包            | 测试                        | 覆盖率实测（语句/分支/函数/行） | 门槛                  | 契约与基线                                                                                                                                           |
| ------------- | --------------------------- | ------------------------------- | --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| wow-client    | 45 个文件，679 个用例，全过 | 100 / 99.79 / 100 / 100         | 98 / 97 / 98 / 98     | 公开面逐名、API Extractor 签名、DSL 线协议 golden、端点表（反射调用每个方法）、Wow 规则登记册、日期模式语料                                          |
| wow-react     | 14 个文件，221 个用例，全过 | 98.46 / 96.95 / 98.36 / 99.41   | 95 / 83 / 92 / 98     | 公开面逐名、Hook 契约类型测试、§3.3 状态表测试、integration-test 的 react 用例（6 个）                                                               |
| wow-generator | 59 个文件，759 个用例，全过 | 98.39 / 95.22 / 100 / 99.16     | 97 / 92.5 / 98.5 / 98 | 两份 Wow 文档逐文件 golden、OpenAI 文档哈希 golden、警告 golden、Wow 模型契约、确定性（两种 locale、两个工作目录）、probe（冷目录 + `tsc --strict`） |

缺口：

- 已发布 9.x 服务端的运行时契约（P1-17）；
- 照文档做一遍（P1-16）；
- 生成器的「编译不过不写盘」失败路径（P1-14）；
- wow-react 受控查询置空（P1-6）；
- 三个包的门槛都落后实测较多，回退不会被发现（C-10、R-10、G-10）。

## 发布就绪核对

| 项              | 结论                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 包元数据        | 三个包的 `description`、`keywords`、`homepage`、`repository.directory`、`bugs`、`license`、`author`、`engines.node >=22.12.0`、`sideEffects: false`、`exports`（wow-client 与 generator 有 import/require 两套类型，wow-react 只有 ESM）都齐。tarball 带 LICENSE（pnpm 从仓库根复制）、两份 README，不带 declaration map。JS source map 带 `sourcesContent`，可以接受。发布的 `package.json` 还带着 `scripts`、`devDependencies`（无害）。 |
| npm 上的 README | wow-client README 结构清楚（支持的服务端、安装、查询、命令、错误、流），但写着 P0-1 的错误承诺。wow-generator README 的退出码说明与实际不符（P1-10），安装行带多余的 fetcher-openapi（P1-11）。wow-react README 的「方法形状相同」措辞引出 P1-3。                                                                                                                                                                                          |
| 迁移指南        | 完整：包名、导入、Hook 行为差异两张表、首个版本的 API 变化逐条列出、生成器差异、8.10 路径、检查清单。缺两条：`listQuery` 的默认 `limit` 没有了（P0-1）；fetcher-openapi peer（P1-11）。                                                                                                                                                                                                                                                    |
| 兼容矩阵        | 结构好：服务端 × 查询模型 × CI 验证了什么。需要补 8.12～9.1.4 的 `limit` 一条（P0-1）、TypeScript 实测范围（P1-15），并按 P1-7 的结论修 fetcher 行。                                                                                                                                                                                                                                                                                       |
| 发布说明模板    | `RELEASE_NOTES_TEMPLATE.md` 与 `release.yml` 分类齐全，Breaking 必填，写法清楚。按设计不维护 `CHANGELOG.md`。可以考虑在 README 顶部链接 Releases 页，让 npm 上的读者找得到变更记录（P2）。                                                                                                                                                                                                                                                 |
| RELEASING.md    | 首发 A～F、日常发版、出错时、回滚都齐，命令可以直接复制执行。A 的最后一项（发版 PR）未勾，符合预期。C′ 见 P1-18。F.2 的「照快速开始走一遍」应提前到 C′。                                                                                                                                                                                                                                                                                   |

## 需要用户拍板

每条先给我的推荐，用户说「按推荐」即可。

1. **`listQuery` 省略 `limit`（P0-1）。** 推荐：客户端在省略时发送 100，与服务端默认一致，线协议 golden 有意变化。备选：只改文档，要求 8.12～9.1.4 的用户自己带 `limit`。推荐的理由：老服务端不再失败；对新服务端结果不变；迁移过来的用户不会踩坑。
2. **冻结前删减 wow-client 公开名（P1-1）。** 推荐删掉：
   - `LogicalField`：新包没有存量用户，迁移指南加一行；
   - 两个原始流提取器：预设是唯一入口；
   - `ReadableDomainEventStream`；
   - `AbacTagsApplied`、`ApplyAbacTags`：生成器以后要映射时再加，加是不破坏兼容的。
3. **生成代码的公开形状（P1-9、P1-13）。** 推荐：
   - Wow 命令流在 API 客户端上也吐 `CommandResult`，与命令客户端一致；非 Wow 文档的流保留 fetcher 的事件信封（事件名、id 在通用 SSE 里有意义）；
   - `…CommandCommand` 去重；
   - 文件名统一为 camelCase（`cartApiClient.ts`）；
   - 路径变量按路径中的顺序排列；
   - `default` 前缀和 `…DomainEventTypeMapTitle` 保持现状：前者来自服务端的 operationId，后者改名收益小。
4. **wow-react 的通用与聚合 Hook（P1-8）。** 推荐放 9.3：纯新增，不影响冻结；9.2.0 先在参考页写明「聚合暂时没有 Hook，在组件里直接调用 `client.aggregate`」，并给一个带中止的示例。如果用户认为聚合是首发卖点，就放进 9.2.0，约 1～2 人日。
5. **fetcher peer 范围去掉 `^6.0.0`（P1-7）。** 推荐去掉，6.0 发布并验证后在一个补丁里放宽。这会修改 09-24 定下的 `^5.1.5 || ^6.0.0`，所以要用户确认。

以下由我按第一原理定，不再请示：

- P1-6：受控查询置空时回到 `idle`、保留结果，与 `abort()` 一致；
- R-13：保持 `ListStreamExecutor<R, Q>` 的顺序；
- 操作名覆盖扩展继续叫 `x-fetcher-method`：9.x 期间改名只会多一个别名，放到 v10 再议。

## 建议的处置顺序

1. **P0 批**（约半天，一个 PR）：
   - P0-1：按第 1 条的结论改 `listQuery`、README、JSDoc、兼容矩阵、迁移指南、各示例；
   - P0-2：改快速开始（中英）。
2. **冻结批**（约 1～2 人日，按包各一个 PR），等用户对第 2、3、5 条拍板以后做：
   - P1-1、P1-9、P1-11、P1-13、P1-7；
   - P1-6、P1-10，两个行为和 CLI 修复一起放进来。
3. **护栏批**（约 1～2 人日）：P1-14～P1-18。
4. **文档批**（约 1 人日）：P1-2～P1-5，以及 X-1、X-2。
5. **其余 P2** 记入 `typescript-review-backlog`，按需排期。

## 复现

临时项目与日志不提交，下面是做法：

```bash
# 构建并打包（仓库根）
pnpm install --frozen-lockfile
pnpm --filter @ahoo-wang/wow-client --filter @ahoo-wang/wow-react --filter @ahoo-wang/wow-generator build
pnpm --dir typescript/wow-client pack --pack-destination "$TMP/tgz"   # 另外两个包同样

# 服务端：已发布的示例镜像，存储与快照用 mongo
docker network create wow-r2-net
docker run -d --name wow-r2-mongo --network wow-r2-net \
  -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root mongo:8.0.14
docker run -d --name wow-r2-server --network wow-r2-net -p 18480:8080 \
  -e SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration \
  -e 'SPRING_MONGODB_URI=mongodb://root:root@wow-r2-mongo:27017/wow_example_db?authSource=admin' \
  -e WOW_EVENTSOURCING_STORE_STORAGE=mongo -e WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
  ghcr.io/ahoo-wang/wow-example-server:9.0.10

# 然后在 $TMP/app 里逐字照快速开始操作（端口换成 18480）
```

mongo 8.3.x 在 Linux 内核 6.19 及以上启动不了（SERVER-121912），本机的 Docker 要用 8.0.x。CI 的契约任务用 8.3.11；GitHub 的 Ubuntu runner 升到 6.19 内核时会遇到同样的问题，值得提前记一笔。
