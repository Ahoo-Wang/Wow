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
