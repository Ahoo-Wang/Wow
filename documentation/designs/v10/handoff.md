# V10 交接与协调手册

日期：2026-10-13（夜间更新）。状态：持续维护（每次交接前更新）。上位文档：[路线图](roadmap.md)。

本文是换会话、换机器继续推进的入口：先读本文，再读[决策台账](decisions.md)（D1～D183 已定；D123 由 D124 取代）、[08 数据协议](08-data-protocols.md)与 `example/eshop/DX-GAPS.md`。已合并的明细以 `git log origin/v10` 为准。

## 1. 位置

- **M1～M5 完成；M6 大半；M6e 进行中（E.0、E.D、E.R 完成）；M7～M9 未开始。** 决定 D1～D183 已定（D123 由 D124 取代）。不发预发布（D124）。
- **M6 已合并**：6.00、6.0a～6.0d、6.1a～6.1e、6.2a～6.2c、6.3a～6.3e、6.4a～6.4c、6.6、6.8；另有 #4345（按类型查重）、#4346（Reactor Netty keep-alive 绕行，等 1.3.8 删除 `KeepAliveReads`）、#4377（示例的 Manifest schema 补全、Codecov 按模块路径）。
- **M6e 已合并**：E.0 骨架；E.R 上下文名规则、`wow.contexts.include/exclude`、G9 启动检查、G12（#4380）；E.D V10 开发指南（`documentation/docs/{en,zh}/guide/v10/`）与 `wow-develop` 的 V10 参考（#4379）。G9 之后 shop 需要 Kafka（测试用内存 topic）。
- **在做（子代理）**：6.0e 调用方与操作人（`claude/v10-operator`）；E.1 catalog（`claude/v10-eshop-catalog`）；E.2～E.4 basket、ordering、inventory 与下单流程（`claude/v10-eshop-order-flow`）。
- **能力核对完成**（6.0i，#4371、#4372）：[09 能力对照](09-capability-parity.md) §13 列出 17 项缺失。**等用户逐项确认推荐**（补齐或放弃）后登记为决定与工作包；M7 入口门槛是缺失项补齐或获批豁免（D183）。
- **范围冻结（D181）**：M6e 期间新议题只登记，除非阻塞 eShop E.1～E.6；E.7 移到 M7。
- **当前协调者**：本机会话（Claude Code 桌面，原账号），自 2026-10-12；额度用完后由用户在新机器上用另一个账号接手。

## 2. 下一步队列（M6e 关键路径优先）

| 顺序 | 工作包 | 要点 |
|---|---|---|
| 1 | 审查合并 6.0e、E.1、E.2～E.4 | 本地演练后合并 |
| 2 | E.5 payment → E.6 `:eshop-e2e` 全链路 | 5 个服务、PostgreSQL、Kafka、ClickHouse；故障场景与压测基线 |
| 2 | H2 阶段 2（D155） | 机器空闲时跑：3 个 JVM、持久化 PostgreSQL、属主被杀的不可用窗口 |
| M7 前 | 6.0f、6.0g、6.0h、6.3f、6.3g、6.5、6.7、6.9、6.2d～f、5a.2～5a.4，以及 09 §13 的补齐项 | 按 09 的阻塞分级排期 |

待补进文档的实现偏离：6.4b 把事件存为 `Array(Tuple(name, revision, payload))`、另加 `state_numbers`（ClickHouse 的 JSON 函数把数字读成 Float64，D122），尚无 `operator` 列（待 6.0e）；6.6 新增 `Subscription.context`（来源订阅按读它的上下文发信号）。这两条应补进 D164、08 §8 与 04。

E.D 写指南时发现、尚无工作包的不一致（待定是改代码还是改决定）：

- D173 说内部端口默认只绑内网地址，代码默认绑所有网卡（除非设 `wow.internal.host`）。
- D176 说客户端指定 ID 用 `PUT /{ctx}/{agg}/{id}/{cmd}`，代码给创建命令两条 `POST` 路由（带与不带 `{id}`）。
- 快照不能按聚合关闭：编译器总写 `SnapshotManifest()`，但 `WaitPlan` 与 Manifest 仍描述无快照的聚合。
- 失败台账的 `SubscriptionRuntime.resume`/`skip` 没有路由或宿主操作（并入 09 §13 第 2 项）。
- `wow.subscription.read-retry.max-attempts` 已绑定但未使用。
- 手写的 Decider DSL 模型没有 `ModelProvider` 与片段，宿主无法承载，也无文档路径。
- V10 模块未发布（连 mavenLocal 也没有），无 BOM：属 M8。

已知的待观察项：`ClusterAdmissionTest`「转发到属主后保留准入身份」偶发失败（已见两次，重跑通过）。

## 3. 工作方式（协调者须知）

- **分工**：主会话只做协调、审查与设计文档；实现交给隔离 worktree 里的子代理。子代理从最新 `origin/v10` 开分支，开 PR 到 `v10`，不合并。
- **子代理说明里必须写明**：JUnit 测试函数返回 `Unit`（否则被静默跳过，按 `build/test-results` 的 XML 核对数量）；M7 出口前 V10 模块不做 ABI 转储（D88、D147）；先 `detekt --auto-correct`；不改 `documentation/designs/v10/**`；并行的子代理不碰同一批文件（列出禁区）；分步提交并尽早推送；不 rebase、不 force-push，需要时 merge `origin/v10`；新增第三方依赖须先问用户。
- **合并前**：在本地把 PR 合到最新 `origin/v10` 上演练，跑完整验证命令（下方）并按 XML 核对测试数；看 `gh pr checks`，红色检查要逐项确认与本 PR 无关（Codecov 的组件状态常在上传未齐时先报红，可查 `https://api.codecov.io/api/v2/github/Ahoo-Wang/repos/Wow/compare/components?pullid=<N>`）。合并用 `gh pr merge <N> --squash --admin --match-head-commit <完整 SHA> --subject "<标题> (#N)" --body "<正文>"`：显式写标题与正文，**不带 `Co-authored-by`**。
- **完整验证命令**（本机需 JDK 21；不要 `./gradlew --stop`，会杀掉其他代理的守护进程）：

```text
./gradlew -Pwow.scope=v10 v10Check v10IntegrationTest
./gradlew checkKotlinAbi :architecture:test detekt   # 全量范围：9.x 的 ABI 与基准编译
```

- **设计文档**：只由主会话改，也走 PR 与 squash 合并；改 Markdown 后跑 `npx prettier --check` 与 `node .github/scripts/docs-layout.mjs`。新决定在 `decisions.md` 登记一次，同步受影响的专题文档、路线图与 README 的评审日志。
- **前向合并**（main → `v10`，约每周一次，D86）：本地合并并验证，推到旁路分支；直接推 `v10` 被拦截，由用户执行 `git push origin <SHA>:v10`。
- **额度**：每周额度是硬约束；同时最多 2～3 个长任务子代理，用量高时不派新代理、只收尾。用户在额度用完后会换账号继续。
- **9.x 与 V10 同名类**：V10 模块若与 9.x 模块有同包同名类，要在 `test/code-coverage-report/build.gradle.kts` 的 `classesSharedWithNineXWhenBoth` 登记，否则 9.x 的 JaCoCo 汇总报告失败。

## 4. 避免冲突（多会话、多机器）

- **同一时间只有一个协调会话。** 交接时在第 1 节末尾写明「当前协调者：<机器或账号>，自 <时间>」；接手前先确认上一个会话已停止派工（没有在跑的子代理），再改成自己。
- **接手第一步**：`git fetch`，看 `gh pr list --base v10 --state open` 与各 PR 的分支，确认哪些工作包已有人在做，不重复派工；对仍开着的 PR，先审查合并或与其作者（子代理分支）对齐，再开新的。
- **设计文档**：本文与决策台账分开维护，减少两边同时改同一文件；改决策台账前先拉最新 `origin/v10`，新决定编号顺延，冲突时合并而不是覆盖。
- **子代理分支**一律 `claude/v10-<工作包>` 命名，并行的子代理在说明里列出彼此的禁区；一个工作包只对应一个开着的 PR。
- **前向合并**只由当前协调者准备，推送仍由用户执行。

## 5. 维护

- 交接前（额度将尽、换机器、长时间离开）更新第 1、2 节；工作方式变了更新第 3 节。
- 本文只放「现在在哪、接下来做什么、怎么做」；决定进[决策台账](decisions.md)，工作包定义进[路线图](roadmap.md)。
