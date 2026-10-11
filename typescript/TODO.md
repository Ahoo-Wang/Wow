# TODO (wow-client, wow-react, wow-generator)

规则同 [docs/todo.md](../docs/todo.md)，先读再加：

- 只记**已经决定、但还没做**的事；每条写清 **为什么** / **做完的判据** / **落点**。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 视图引擎与视图存储的 TODO 在 [wow-view-engine/docs/design/todo.md](wow-view-engine/docs/design/todo.md)；服务端（Kotlin）的在 [docs/todo.md](../docs/todo.md)；保留到 v10 的兼容在 [docs/compat-debt.md](../docs/compat-debt.md)。

以下各条来自 9.2.0 首发前的第二轮审查（2026-09-25，基于 `b17f36a3c`）定为「以后再做」的 P2，2026-10-10 在 `main` 上逐条复核仍然成立；括号里是原审查编号。

## 以后

### wow-client

- **命令流把未知阶段当成错误**（C-5）：`CommandResultEventStreamResultExtractor` 只认本版本的 `CommandStage`，服务端以后新增一个阶段时，旧客户端会把这个阶段的结果当作错误事件结束流。判据：事件数据是 `ErrorInfo` 且没有 `stage` 字段时才判错，带 `stage` 的未知阶段照常作为结果交出；有一个未知阶段的测试。落点：`wow-client/src/transport/eventStreams.ts`。
- **客户端文件的 JSDoc 示例没有类型检查**（C-6）：`test/jsdocExamples.test.ts` 只列了 dsl 与少数客户端文件，`client/command/*`、`client/query/{snapshot,event,descriptor}/*`、`client/routing.ts`、`client/metadata/*`、`error/headers.ts` 的示例不受检查，审查时其中两处编译不过。判据：带 `@example` 的源文件全部列入（或改为按目录收集），全部编译通过。落点：`wow-client/test/jsdocExamples.test.ts`。
- **层边界规则可以被绕过**（C-7）：`eslint.config.js` 的层正则是 `(^|/)<dir>/`，`'../transport'`（不带斜杠）和 `'../dsl.js'` 匹配不上。判据：正则改为 `(^|/)<dir>(/|\.js$|$)`，`layerBoundaries.test.ts` 各加一个这两种写法的违例。落点：`wow-client/eslint.config.js`、`wow-client/test/layerBoundaries.test.ts`。
- **线协议类型放在 `client/` 下**（C-8）：`MaterializedSnapshot`、`DomainEventStream` 在 `client/query/{snapshot,event}/`，`dsl.ts` 要从 `client/` 再导出。判据：移到 `model/`，公开名字与签名不变，`test/api`、`test/surface` 无变化。落点：`wow-client/src/client/query/snapshot/snapshot.ts`、`…/event/domainEventStream.ts`、`src/model/`。
- **覆盖率门槛远低于实测**（C-10）：门槛 98/97/98/98，实测约 100/99.8/100/100，回退几个点也不会失败。判据：抬到 99.5/99/99.5/99.5，注释写明依据。落点：`wow-client/vitest.config.ts`。
- **`test/client/` 与 `test/clients/` 并存**（C-11）：同一层的测试分在两个目录。判据：合并为一个目录，`AGENTS.md` 里的路径随之更新。落点：`wow-client/test/`。
- **聚合参考页按任务重写**（X-1）：「聚合构造器」一页信息密度过高、读起来像译文。判据：按「分组、指标、过滤、派生、排序与分页」等任务组织，每节一个可运行示例，中英两版结构一致。落点：`documentation/docs/{en,zh}/reference/typescript/wow-client/aggregations.md`。

### wow-react

- **README 写明 SSR 的边界与同端点判定**（R-8、R-14）：SSR 只做到「首帧 loading、不请求」，没有 `initialResult`；两个没有名字、`baseURL` 相同的 Fetcher 被当作同一个端点（例如按租户各建一个只差拦截器的 Fetcher，切换时不会重新查询）。判据：中英 README 各有一段说明这两点及绕法（给 Fetcher 起名）。落点：`wow-react/README.md`、`README.zh-CN.md`。
- **测试文件头还写着 fetcher-react 5.1.3**（R-9）：三处测试的头注释说「pins the behaviour of today, fetcher-react 5.1.3 underneath」，迁移后已不依赖 fetcher-react。判据：注释改为它们现在固定的契约（§3.3 状态表等）。落点：`wow-react/test/callbacks.test.tsx`、`queryIdentity.test.tsx`、`requestStateTable.test.tsx`。
- **覆盖率门槛远低于实测**（R-10）：门槛 95/83/92/98，审查时实测约 98.5/97/98.4/99.4。判据：抬到约 97/95/96/99。落点：`wow-react/vitest.config.ts`。
- **lint 的缺口**（R-11）：eslint 忽略全部测试文件；构建依赖 React Compiler，`react-compiler/react-compiler` 却只是警告。判据：测试文件纳入 lint（可用较宽的规则集）；`react-compiler` 改为 error。落点：`wow-react/eslint.config.js`。
- **集成测试缺口**（R-15）：对真服务端的用例只有 `cartQueryHooks.test.tsx` 的分页与计数，没有 `useSingleQuery`／`useListQuery`、经 `toWowError` 的 HTTP 错误、abort 与 reset。判据：各有一个对示例服务端的用例。落点：`integration-test/test/wow/react/`。

### wow-generator

- **`--help` 不说退出码；以旧名调用时不提示弃用**（G-7）：退出码只在 `EXIT_CODES` 与 README 里；以 `fetcher-generator` 调用时没有任何提示。判据：`generate --help` 列出退出码；以 `fetcher-generator` 调用时向 stderr 打印一行弃用提示（v10 移除别名）。落点：`wow-generator/src/cli/program.ts`。
- **覆盖率门槛低于实测**（G-10）：门槛 97/92.5/98.5/98，审查时实测约 98.4/95.2/100/99.2。判据：抬到约 98/94.5/99.5/98.5。落点：`wow-generator/vitest.config.ts`。
- **`SchemaDocs` 没有导出**（G-11）：`GeneratorOptions.schemaDocs` 的类型 `SchemaDocs` 不在包入口，调用方只能写字面量。判据：入口导出 `SchemaDocs`（新增导出，不破坏兼容），API 报告随之更新。落点：`wow-generator/src/index.ts`。
- **生成代码里仅剩的 `any`**（X-3）：无类型的事件流生成为 `JsonServerSentEventStream<any>`。判据：改为 `unknown`（或可推出的类型），golden 随之更新；作为生成代码的形状变化，只在 `x.Y.0` 做。落点：`wow-generator/src/emitters/apiClients.ts`。
