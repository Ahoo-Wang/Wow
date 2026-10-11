# TODO (wow-client, wow-react, wow-generator)

规则同 [docs/todo.md](../docs/todo.md)，先读再加：

- 只记**已经决定、但还没做**的事；每条写清 **为什么** / **做完的判据** / **落点**。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 视图引擎与视图存储的 TODO 在 [wow-view-engine/docs/design/todo.md](wow-view-engine/docs/design/todo.md)；服务端（Kotlin）的在 [docs/todo.md](../docs/todo.md)；保留到 v10 的兼容在 [docs/compat-debt.md](../docs/compat-debt.md)。

以下各条来自 9.2.0 首发前的第二轮审查（2026-09-25，基于 `b17f36a3c`）定为「以后再做」的 P2，2026-10-10 在 `main` 上逐条复核仍然成立；括号里是原审查编号。

## 以后

### wow-react

- **README 写明 SSR 的边界与同端点判定**（R-8、R-14）：SSR 只做到「首帧 loading、不请求」，没有 `initialResult`；两个没有名字、`baseURL` 相同的 Fetcher 被当作同一个端点（例如按租户各建一个只差拦截器的 Fetcher，切换时不会重新查询）。判据：中英 README 各有一段说明这两点及绕法（给 Fetcher 起名）。落点：`wow-react/README.md`、`README.zh-CN.md`。
- **测试文件头还写着 fetcher-react 5.1.3**（R-9）：三处测试的头注释说「pins the behaviour of today, fetcher-react 5.1.3 underneath」，迁移后已不依赖 fetcher-react。判据：注释改为它们现在固定的契约（§3.3 状态表等）。落点：`wow-react/test/callbacks.test.tsx`、`queryIdentity.test.tsx`、`requestStateTable.test.tsx`。
- **覆盖率门槛远低于实测**（R-10）：门槛 95/83/92/98，审查时实测约 98.5/97/98.4/99.4。判据：抬到约 97/95/96/99。落点：`wow-react/vitest.config.ts`。
- **lint 的缺口**（R-11）：eslint 忽略全部测试文件；构建依赖 React Compiler，`react-compiler/react-compiler` 却只是警告。判据：测试文件纳入 lint（可用较宽的规则集）；`react-compiler` 改为 error。落点：`wow-react/eslint.config.js`。
- **集成测试缺口**（R-15）：对真服务端的用例只有 `cartQueryHooks.test.tsx` 的分页与计数，没有 `useSingleQuery`／`useListQuery`、经 `toWowError` 的 HTTP 错误、abort 与 reset。判据：各有一个对示例服务端的用例。落点：`integration-test/test/wow/react/`。

### wow-generator

- **生成代码里仅剩的 `any`**（X-3）：无类型的事件流生成为 `JsonServerSentEventStream<any>`。判据：改为 `unknown`（或可推出的类型），golden 随之更新；作为生成代码的形状变化，只在 `x.Y.0` 做。落点：`wow-generator/src/emitters/apiClients.ts`。
