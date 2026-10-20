# TODO (wow-client, wow-react, wow-generator)

规则同 [docs/todo.md](../docs/todo.md)，先读再加：

- 只记**已经决定、但还没做**的事；每条写清 **为什么** / **做完的判据** / **落点**。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 视图引擎与视图存储的 TODO 在 [wow-view-engine/docs/design/todo.md](wow-view-engine/docs/design/todo.md)；服务端（Kotlin）的在 [docs/todo.md](../docs/todo.md)；保留到 v10 的兼容在 [docs/compat-debt.md](../docs/compat-debt.md)。

2026-10-20 对照 V10 复核：此前唯一的一条（X-3，`wow-generator` 生成的 `JsonServerSentEventStream<any>`）落在从 OpenAPI 生成 9.x 客户端的路径上（`src/emitters/apiClients.ts`），这条路径随 9.x 的 TypeScript 清理离开 `v10`，已删去。

## 以后

当前没有条目。
