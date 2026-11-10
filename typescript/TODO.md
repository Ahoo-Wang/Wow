# TODO (wow-client, wow-react, wow-generator)

规则，先读再加：

- 只记**已经决定、但还没做**的事；每条写清 **为什么** / **做完的判据** / **落点**。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 视图引擎与视图存储的 TODO 在 [wow-view-engine/docs/design/todo.md](wow-view-engine/docs/design/todo.md)；服务端（Kotlin）的在 [路线图](../documentation/designs/roadmap.md) §6。

2026-10-20 对照 V10 复核：此前唯一的一条（X-3，`wow-generator` 生成的 `JsonServerSentEventStream<any>`）落在从 OpenAPI 生成 9.x 客户端的路径上（`src/emitters/apiClients.ts`），这条路径随 9.x 的 TypeScript 清理离开 Wow 10，已删去。

## 以后

### 源码注释不引用设计编号

- **为什么**：Kotlin 侧（R5.C-7）已规定源码注释陈述理由、不引用设计编号，由 `:architecture` 的 `DesignIdRule` 守住；TypeScript 只清理了导出符号 TSDoc 的首句（生成的 API 参考页读它）。其余注释仍引用 Wow 的决策（`decision D253`）、评审编号（`review R4-Q`）和视图引擎自己的编号（`D22 H`、`D23 Q17`）：`wow-view-engine` 约 1,400 处，`wow-client` 约 70 处，`wow-generator`、`wow-view-store` 各几处。
- **做完的判据**：`typescript/*/src` 的非测试源码不再出现这些编号，引用处改写成理由；规则（或等价的检查）覆盖 `typescript/*/src`，设计记录（`wow-view-engine/docs/design`）仍在规则之外。
- **落点**：按包分 PR，`wow-client`、`wow-generator`、`wow-view-store` 一个，`wow-view-engine` 一到两个；之后扩展规则。

### Manifest 中 Wow 自己的线上类型用客户端的导出

- **为什么**：Manifest 的 `$defs` 也收录 Wow 自己的线上类型（如 `me.ahoo.wow.api.result.CommandResult`、`Hop`），生成器把它们当作领域类型再生成一份；`@ahoo-wang/wow-client` 已经导出这些类型，两份会分叉。#5593 之后它们按包名限定（分析目录控制台里的 `ResultCommandResult`、`ResultHop`），名字不再冲突，但仍是副本。
- **做完的判据**：`$defs` 中属于 Wow 线上类型的 key 解析为 `@ahoo-wang/wow-client` 的导出（按与 `test/fixtures/wire-names.json` 同源的清单对应），生成的 `types.ts` 不再声明它们；分析目录控制台的生成客户端引用 `CommandResult`、`Hop`。
- **落点**：`wow-generator` 的 `manifest/` 命名与类型解析，一个 PR；重新生成各应用的 `src/generated`。

### 命令结果的 `results` 中的精确数字

- **为什么**：`CommandResult.results` 是服务自己的值，Manifest 不给出它们的 schema，客户端读取时不带标记（#5598 的路径表）；命令在其中返回的 `Long`、`BigDecimal` 会被取整，生成的类型也只能写 `Record<string, unknown>`。
- **做完的判据**：Manifest 为命令的 `results` 给出 schema（或等价的声明），生成器据此派生标记，命令客户端读取结果时带上它们；一个返回 `Long` 的命令在 `wait` 与 `stream` 中都精确读取。
- **落点**：先在服务端（Manifest 格式，需协调者决定），再在 `wow-generator` 与 `wow-client` 的命令路径。

### 聚合查询指标值中的精确数字

- **为什么**：聚合查询的行是查询自己的形状，客户端读取时不带标记；对 `int64`、`decimal` 字段求和或取最大值，超出 `number` 能表示的范围时会被取整。
- **做完的判据**：聚合的指标按其字段与运算推出是否精确（求和、最大最小值沿用字段的格式，计数与平均另行规定），`aggregate()` 的行据此带上标记；一个对 `decimal` 字段的求和精确读取。
- **落点**：`wow-client` 的 `/dsl` 聚合描述与 `QueryModelClient.aggregate`；生成的查询客户端传入字段的格式。
