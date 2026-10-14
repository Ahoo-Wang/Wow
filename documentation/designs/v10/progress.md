# V10 进度

日期：2026-10-14。状态：持续维护（每次合并或定下决定后更新）。上位文档：[路线图](roadmap.md)。

本文记录 V10 现在在哪、正在做什么、接下来做什么，以及推进的工作方式。决定在[决策台账](decisions.md)（D1～D216 已定；D123 由 D124 取代），工作包定义在[路线图](roadmap.md)；已合并的明细以 `git log origin/v10` 为准。

## 1. 位置

| 里程碑 | 状态 |
|---|---|
| M1～M5 | 完成 |
| M6 平台能力 | 大半完成：6.00、6.0a～6.0h、6.1a～6.1e、6.2a～6.2c、6.3a～6.3e、6.3g、6.4a～6.4c、6.5、6.6、6.8、6.8b 已合并 |
| M6e eShop | E.0～E.6、E.8、E.D、E.R 完成；E.7 移到 M7（D181）；eShop 是唯一的 V10 示例（D204） |
| M6p 能力补齐 | P.1～P.5、P.9～P.13 已合并；P.6～P.8 在 M8 前 |
| M7 平台迁移 | 进行中：7.2a view-store（#4474，系统视图与 Skill 适用范围 #4477）与 7.1a ops 服务（#4475）已合并；下一步 7.3 控制台与 view-engine 接 V10 → 7.1b ops 控制台、E.7 Web UI；7.2b、7.2c 延期到发版之前（D201） |
| M8～M9 | 未开始 |

推进原则：**先稳住核心，再完成全能力**（D196）；**核心阶段与全能力阶段均已完成（2026-10-14）**；测试模块（`:test-dsl`、`:tck`、`:tck-containers`）是每次迭代的门禁。发版（D202）：除 7.2b、7.2c 外全部完成后才发第一个预发布；alpha、beta 自行发布，rc 须用户确认。

## 2. 最近合并

- **2026-10-14**：6.9 可观测性（#4452）；H2 阶段 2 确认 D6。
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
| C.1 核心加固 | **完成**：两轮最终浸泡连续干净（运行 38543092708、38563608189）；夜间浸泡持续运行（`v10-soak.yml`） |

**全能力阶段**：已完成（6.9 #4452、P.11 #4455、5a.4 #4456、6.8c #4458、5a.2 #4460、6.3f #4461、5a.3 #4462、6.2d #4465（6.2f 已确认完成）、6.2e #4466、P.12 #4468、5a.1c #4469）。

**延期到发版之前**（D201）：7.2b dataset-registry 与 7.2c analytics（M9 的 9.3b），开工前先与用户讨论设计。

## 4. 已知问题

- DX G34：等待链在计划终点前结束时看起来像成功，按 D212 修复（待派）。
- 测试夹具「先挑空闲端口再绑定」的竞态：修复中（#4472，改为绑定 0 号端口）。
- 7.2a 的已知缺口：生命周期删除不再检查应用；无快照查询后端时认领看不到共享看板；服务的调用方解析器读请求头（部署时换 CoSec）。系统视图路由不在 Manifest 与 OpenAPI 里，7.3 手写调用。
- 2026-10-14 磁盘两次写满（子代理 worktree 的构建产物、Gradle 构建缓存与守护进程日志），本地 Docker 引擎随之停止，待用户重启；期间容器测试由 CI 验证。
- 6.2e 的已知缺口：滚动升级期间旧版本节点拒绝保存后、标记待重建前崩溃，该聚合的快照会落后到它下次写入或手动重建（命令处理不受影响）；后续项：维护过程定期比对快照版本与日志头。
- H2 阶段 2（D6 已确认）留下两个待查项：均匀负载下 Partitioned 的尾延迟尖峰（最差一秒 p99 370 ms，Local 83 ms）；Zipf 下 356 条命令客户端判为失败而实际已提交（结果不确定，可按请求 ID 重试），须查明超时来源。
- 环境类偶发失败：ClickHouse 容器启动失败、`HostKafkaRoutingTest` 端口冲突、`KafkaCommandIntakeTest` 容器启动超时、Maven Central 429。
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
- **合并前**（D207）：单个 PR 以 CI 为准：`gh pr checks` 全绿（红色逐项确认与本 PR 无关）、Codecov 补丁覆盖率 ≥ 90% 且组件下降不超过 1%（compare API：`https://api.codecov.io/api/v2/github/Ahoo-Wang/repos/Wow/compare/?pullid=<N>` 与 `.../compare/components?pullid=<N>`）。改动核心模块的 PR 与可能互相冲突的批次，先在独立 worktree 里把 2～3 个 PR 叠在最新 `origin/v10` 上跑完整验证命令。合并用 `gh pr merge <N> --squash --admin --match-head-commit <完整 SHA> --subject "<标题> (#N)" --body "<正文>"`；取不到 PR 编号时不合并。
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
- `--admin` 合并会绕过红色 CI：合并前必须看 `gh pr checks`，核心改动与批次做本地叠加演练（D207）；squash 合并显式写 `--subject` 与 `--body`。
- 发版（D202）：除 7.2b、7.2c 外的工作全部完成后才发第一个预发布；alpha、beta 不需确认，rc 与 GA 须用户确认；发布配置的改动须用户批准。
- 新增第三方依赖、改发布自动化、改安全设置或 Dependabot 告警须先问用户。
- 7.2b dataset-registry 与 7.2c analytics 延期到发版之前（D201），开工前先与用户讨论设计。
- 每周额度不作为限制，只看 5 小时窗口；同时最多 2～3 个长任务子代理（D207，用户 2026-10-14 由 6 改回）；不用 Fable 模型。
- Gradle 需要 JDK 21，设 `JAVA_HOME`；**不要 `./gradlew --stop`**。演练用独立 worktree；改设计文档用另一个 worktree。
- Reactor Netty 1.3.8 GA 发布后提议升级（需批准）并删除 `KeepAliveReads`；用户拒绝了 1.4.0-M2。
- main 上两个无上游修复的开发依赖告警（braces、sprintf-js）由用户决定暂挂。
