# TODO (JVM)

规则，先读再加：

- 只记**已经决定、但还没做**的事；尚无结论的问题记在「待议」，由维护者定了再移上来。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（哪个文件或文档）。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- TypeScript 视图引擎的 TODO 在 [typescript/wow-view-engine/docs/design/todo.md](../typescript/wow-view-engine/docs/design/todo.md)；wow-client、wow-react、wow-generator 的在 [typescript/TODO.md](../typescript/TODO.md)。

## 下一个补丁

## 以后

- **Reactor Netty 1.3.8 进 BOM 后去掉 `KeepAliveReads`**（v10）：宿主的两个 HTTP 服务器在 Reactor Netty 的 HTTP traffic handler 之后挂了这个 handler，只为绕开 Reactor Netty 1.3.7 的缺陷：上一个请求结束前到达的 keep-alive 请求被缓冲，从缓冲里立即答完后不再有人请求读，下一个请求留在 socket 里，直到客户端超时关连接（当初在端到端测试里表现为 30 s 无应答；上游修复是 reactor/reactor-netty@a115965，随 1.3.8 发布）。判据：Spring Boot BOM 带来的 reactor-netty ≥ 1.3.8 时 `KeepAliveReadsTest` 的「without the handler」用例失败，即删掉 handler、它在 `InternalServer` 与 `WowAutoConfiguration` 的安装和这个测试。落点：`host/spring-boot/src/main/kotlin/me/ahoo/wow/spring/boot/KeepAliveReads.kt`。

## 待议

