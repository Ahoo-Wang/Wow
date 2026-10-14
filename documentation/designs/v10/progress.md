# V10 进度

日期：2026-10-14。状态：持续维护（每次合并或定下决定后更新）。上位文档：[路线图](roadmap.md)。

本文记录 V10 现在在哪、正在做什么、接下来做什么，以及推进的工作方式。决定在[决策台账](decisions.md)（D1～D206 已定；D123 由 D124 取代），工作包定义在[路线图](roadmap.md)；已合并的明细以 `git log origin/v10` 为准。

## 1. 位置

| 里程碑 | 状态 |
|---|---|
| M1～M5 | 完成 |
| M6 平台能力 | 大半完成：6.00、6.0a～6.0h、6.1a～6.1e、6.2a～6.2c、6.3a～6.3e、6.3g、6.4a～6.4c、6.5、6.6、6.8、6.8b 已合并 |
| M6e eShop | E.0～E.6、E.8、E.D、E.R 完成；E.7 移到 M7（D181）；eShop 是唯一的 V10 示例（D204） |
| M6p 能力补齐 | P.1～P.5、P.9、P.10、P.13 已合并；P.11、P.12 在全能力阶段；P.6～P.8 在 M8 前 |
| M7～M9 | 未开始 |

推进原则：**先稳住核心，再完成全能力**（D196）；测试模块（`:test-dsl`、`:tck`、`:tck-containers`）是每次迭代的门禁。发版（D202）：除 7.2b、7.2c 外全部完成后才发第一个预发布；alpha、beta 自行发布，rc 须用户确认。

## 2. 最近合并

- **2026-10-14**：C.1 浸泡工具（#4440）、F2 存储故障的错误码（#4442）、F1 共享消费者卡住导致分区无人接管（#4448）、定时等待上限与时间槽到期（#4447）、夜间浸泡工作流（#4444、`main` #4445）、H2 阶段 2（#4449、`main` #4450）。
- **2026-10-14**：D203 状态构造函数的 `id` 可选、示例状态全部无参（#4430）；E.8 示例收拢到 eShop，删除 shop、inventory 与 `example/e2e`（#4432）。
- **2026-10-14**：6.0g 按聚合配置存储与日志组（#4420）；6.8b 内置定时事件、每节点触发循环、发送前领取（#4425）；`KafkaTransportTest`、`KafkaTopicAdminTest` 偶发失败（#4427）。
- **2026-10-14**：P.13 订阅起点、快照可关闭、内部端口绑定、读重试、派生命令的作用域（#4419）。
- **2026-10-13～14**：P.10 归属、作用域继承、等待链放宽、不带 ID 的创建、编译器 `@Retry`（#4408）；P.9 测试模块与门禁（#4414）；`ClusterAdmissionTest` 与 `KafkaRouterTest` 的偶发失败（#4415、#4418，均为测试问题，无产品缺陷）。
- **决定**：D192～D201（成功回执与同步调用、订阅起点、指南发现的不一致、先核心后全能力与测试门禁、6.0g 进 10.0、统一的定时设计、派生命令的作用域、`TestValidator` 不进测试模块、7.2b 与 7.2c 延期）。
- **此前**：6.0f ID 生成器（#4395）、6.3g Kafka 主题管理与 6.5 Kafka Router（#4396）、P.2～P.5（#4392～#4394）、G32/G33（#4390）、E.6 全链路（#4386，压测基线在 `example/eshop/e2e/README.md`）、P.1（#4389）、6.0h（#4388）。

## 3. 进行中与下一步

**核心阶段**

| 工作包 | 状态 |
|---|---|
| C.1 核心加固 | F1（#4448）、F2（#4442）与定时器重试早于到期的缺陷（#4447）已修；最终两轮 1 小时浸泡在 GitHub 上跑（`v10-soak.yml`），须连续两轮干净 |
| H2 阶段 2（D206） | 测量已合并（#4449、`main` #4450），正式测量在 GitHub runner 上跑 |

**全能力阶段**（D206 定序，可与核心阶段剩余修复并行）：6.9 可观测性进行中（`v10-6.9-observability`）；其后 P.11（D192 `@OnCommitted`）、P.12（D193 同步调用客户端，审查时核对服务身份的信任边界）、6.8c（D198：cron、`@OnSchedule` 与租户作业）、6.3f、6.7、6.9、6.2d～f、5a.2～5a.4；M8 前：P.6～P.8。

**延期到发版之前**（D201）：7.2b dataset-registry 与 7.2c analytics（M9 的 9.3b），开工前先与用户讨论设计。

## 4. 已知问题

- 已获批准（D205）：夜间浸泡工作流（已合并：`v10` #4444、`main` #4445）、F1 定位用的测试依赖 `kotlinx-coroutines-debug`、发布配置（R.1，第一个预发布之前做）。
- `HostKafkaRoutingTest` 端口冲突（Address already in use）偶发失败，并入 C.1 清理。
- `KafkaCommandIntakeTest`（`:spring-boot` 集成测试）的 Kafka 容器启动偶发超时（E.8 的门禁上见到一次），并入 C.1 清理。
- P.13 之后用户文档有三处过时（读重试、内部端口的默认绑定、eShop README 的 `start: beginning`），留给 9.1 重写。
- 实现偏离登记在对应决定的「实现」说明里；专题文档 02 §1、04 §1～§2、05 §1、08 §4 已按 D163～D191 同步。
- V10 模块未发布、无 BOM：属 M8。
- `KeepAliveReads`（#4346）等 Reactor Netty 1.3.8 GA 后删除。

## 5. 工作方式

- **分工**：主会话做协调、审查与设计文档；实现交给隔离 worktree 里的子代理，从最新 `origin/v10` 开分支 `v10-<工作包>`（不以 `claude/` 开头，用户 2026-10-14；`v10/` 前缀与 `v10` 分支冲突），开 PR 到 `v10`，不合并；一个工作包只对应一个开着的 PR。
- **子代理说明里必须写明**：JUnit 测试函数返回 `Unit`（否则被静默跳过，按 `build/test-results` 的 XML 核对数量）；M7 出口前 V10 模块不做 ABI 转储（D88、D147）；先 `detekt --auto-correct`；不改文档；并行的子代理列出彼此的禁区；分步提交并尽早推送；不 rebase、不 force-push，需要时 merge `origin/v10`；新增第三方依赖须先问用户；不跑占满 CPU 的压力进程。
- **测试门禁**（D196）：每个工作包合并前须过 `v10Check`、各后端的全部 TCK 规格与 eShop 全链路；新能力须同时带测试 DSL 或 TCK 的覆盖，测试模块缺的能力先补。
- **合并前**：在独立 worktree 里把 PR 合到最新 `origin/v10` 上演练，跑完整验证命令并按 XML 核对测试数；看 `gh pr checks`，红色检查逐项确认与本 PR 无关（Codecov 以 compare API 为准：`https://api.codecov.io/api/v2/github/Ahoo-Wang/repos/Wow/compare/?pullid=<N>`，补丁覆盖率须 ≥ 90%）。合并用 `gh pr merge <N> --squash --admin --match-head-commit <完整 SHA> --subject "<标题> (#N)" --body "<正文>"`。
- **完整验证命令**（需 JDK 21；不要 `./gradlew --stop`）：

```text
./gradlew -Pwow.scope=v10 v10Check v10IntegrationTest
./gradlew checkKotlinAbi :architecture:test detekt   # 全量范围：9.x 的 ABI 与基准编译
```

- **设计文档**：只由主会话改，走 PR 与 squash 合并；改 Markdown 后跑 `npx prettier --check` 与 `node .github/scripts/docs-layout.mjs`。新决定在 `decisions.md` 登记一次，同步受影响的专题文档、路线图、本文与 README 的评审日志。
- **前向合并**（main → `v10`，约每周一次，D86）：本地合并并验证，推到旁路分支；直接推 `v10` 被拦截，由用户执行 `git push origin <SHA>:v10`。
- **9.x 与 V10 同名类**：V10 模块若与 9.x 模块有同包同名类，要在 `test/code-coverage-report/build.gradle.kts` 的 `classesSharedWithNineXWhenBoth` 登记。

## 6. 用户的长期要求

- **先稳住核心，再完成全能力**；测试模块是每次迭代的门禁（D196）。
- **用户文档最后统一重写**：开发期间不维护 `documentation/docs/**`（含 V10 指南）；设计文档 `documentation/designs/v10/**` 照常维护；M9 的 9.1 以代码与设计文档为准重写。
- 与用户用中文交流；代码、提交与 PR 用英文。默认按推荐推进，只有真正需要用户拍板的才问（标注推荐项）；用户说「先讨论」「先记录」的议题不派工。
- `--admin` 合并会绕过红色 CI：合并前必须看 `gh pr checks` 并在本地演练；squash 合并显式写 `--subject` 与 `--body`。
- 发版（D202）：除 7.2b、7.2c 外的工作全部完成后才发第一个预发布；alpha、beta 不需确认，rc 与 GA 须用户确认；发布配置的改动须用户批准。
- 新增第三方依赖、改发布自动化、改安全设置或 Dependabot 告警须先问用户。
- 7.2b dataset-registry 与 7.2c analytics 延期到发版之前（D201），开工前先与用户讨论设计。
- 每周额度不作为限制，只看 5 小时窗口；同时最多 3～4 个长任务子代理（用户 2026-10-14 放宽）；不用 Fable 模型。
- Gradle 需要 JDK 21，设 `JAVA_HOME`；**不要 `./gradlew --stop`**。演练用独立 worktree；改设计文档用另一个 worktree。
- Reactor Netty 1.3.8 GA 发布后提议升级（需批准）并删除 `KeepAliveReads`；用户拒绝了 1.4.0-M2。
- main 上两个无上游修复的开发依赖告警（braces、sprintf-js）由用户决定暂挂。
