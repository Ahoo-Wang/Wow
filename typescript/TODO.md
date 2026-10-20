# TODO (wow-client, wow-react, wow-generator)

规则同 [docs/todo.md](../docs/todo.md)，先读再加：

- 只记**已经决定、但还没做**的事；每条写清 **为什么** / **做完的判据** / **落点**。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 视图引擎与视图存储的 TODO 在 [wow-view-engine/docs/design/todo.md](wow-view-engine/docs/design/todo.md)；服务端（Kotlin）的在 [docs/todo.md](../docs/todo.md)。

以下各条来自 9.2.0 首发前的第二轮审查（2026-09-25，基于 `b17f36a3c`）定为「以后再做」的 P2，2026-10-10 在 `main` 上逐条复核仍然成立；括号里是原审查编号。

## 以后

### wow-generator

- **生成代码里仅剩的 `any`**（X-3）：无类型的事件流生成为 `JsonServerSentEventStream<any>`。判据：改为 `unknown`（或可推出的类型），golden 随之更新；作为生成代码的形状变化，只在 `x.Y.0` 做。落点：`wow-generator/src/emitters/apiClients.ts`。
