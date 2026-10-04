# 进度

**读法**：这一页只记「现在到哪了」与走过的里程碑。要做的事在 [todo.md](todo.md)（完成即删），产品决定在 [decisions.md](decisions.md)。细节在各 PR 的描述与 git 历史里，这里不重抄。

## 现在到哪了（2026-10-03）

六个阶段都已落地：记录视图、分析视图、仪表盘、嵌入视图、内置多主题（四套预设 `neutral`、`azure`、`porcelain`、`contrast`，主题架构 S1～S11），分析视图释放 ECharts（批 A～E、D41 全部图型、Wow 查询 N1～N6 的采用）。本包已迁入 Wow 仓，补偿控制台以它为基础重写，是第一个真实宿主。首发前的收口也已完成：宿主接入 H1～H3（[host-integration.md](host-integration.md)，[D67](decisions.md#d67-宿主接入事实归机器选择归宿主2026-09-28)）、工具类前缀 `fve:`（[D66](decisions.md)）、ui 根目录拆成三层、架构与代码质量审查（含查询后端与 wow-react）、Storybook 的两轮审查与零售场景、严格 CSP 下整个引擎零违规（[D74](decisions.md#d74-严格-csp-的门是一组-storybook-故事库加的样式一律带页面-nonce引擎不载-data-图片2026-09-29)）。阶段 6 的 Wow 存储后端也已落地（[view-store-backend.md](view-store-backend.md)）：视图与偏好是两个 Wow 聚合，示例服务端与补偿服务内嵌、另有独立服务端，`WowViewStore` 在两种服务端上过端口一致性套件、隔离测试与引擎端到端，补偿控制台的视图存进补偿服务（共享，不登录）。两个 skill 也已写好（[host-integration.md](host-integration.md) 第 6 节）：`wow-view-definition` 改写为只讲判断、自检就是 `admit`，新增 `wow-view-host`（资源、**ViewHost**、`bind`、路由、存储与「从命令到操作」），示例随文档站测试编译。第二轮全面审查已由用户 2026-10-01 明确通过，推迟的条目在 [todo.md](todo.md)「首发后再议」。**本包已随 Wow 9.2.0 发布**（2026-10-03，npm `latest`，带 provenance），与 `@ahoo-wang/wow-view-store` 一起。阶段 7 的文档站也已落地：文档站上有面向宿主研发的一整套页，中英两版，从入门、概念、定义、宿主接入、操作、CSP、存储到 Kotlin 的视图存储与 API 参考。首发后的模块内部重构（todo 的 R2-94、R2-95）也已做完，公开面逐字未变。

接下来（用户 2026-09-29 的顺序：存储后端 → skills → 发版 → 文档；四项都已完成）：推迟到首发之后的两项，真人读屏走查与 Firefox／WebKit 视觉回归（[D73](decisions.md#d73-真人读屏走查与多浏览器视觉回归放到首发之后2026-09-29)）。文档站的两项后续——面向最终用户的使用文档、9.3 精简包 README——在 [todo.md](todo.md)「首发后再议」。

从 9.2.0 起，补丁版本不破坏本包的公开面（导出、CSS 合同、消息键与 issue code、`wow-view-engine` 命令）；破坏性改动只进 `x.Y.0`，并写进发布说明的「Breaking」。

## 里程碑

- **阶段 1 记录视图与布局**（2026-09-21～22）：审计 F／P／A 三类清单与收尾审查，#1661～#1712；裁定 [D18](decisions.md#d18-阶段一审计的十二条裁定2026-09-21)、D19。
- **阶段 2 分析视图**（2026-09-22）：八个批次与五维审查，#1716～#1748；交互 [D20](decisions.md#d20-分析视图的交互2026-09-22)。
- **企业级视觉与真实服务走查**（2026-09-23）：#1750～#1806；图表换成 ECharts（[D21](decisions.md#d21-图表渲染层换成-apache-echarts-612026-09-23)，#1809～#1813），分析审查收尾 #1804～#1860。
- **阶段 3 仪表盘、阶段 4 嵌入视图**（2026-09-23～24）：批 A～D #1831～#1865，嵌入 #1863，联合审查处置到 #1902；参照 Metabase（[D22](decisions.md#d22-仪表盘与嵌入视图参照-metabase2026-09-23)），细化 D23～D25。
- **迁入 Wow 仓**（2026-09-24）：#3293～#3339；公开面快照（D29）、R3 界面重构（D28）、阶段 5 的 5A～5D（D30）。迁移记录在 `typescript/MIGRATION.md`。
- **首发前的各包重构与审查**（2026-09-24～25）：ECharts 批 A～E（D33）、wow-client／wow-react／wow-generator 各批，#3334～#3405；第二轮包审查 P0／P1 #3411～#3444；D41 新图型 #3432、#3448、#3449；真服务端 e2e #3412；可访问性走查与 WCAG 2.2 AA 声明 #3424。
- **能力描述与主题架构**（2026-09-25～26）：能力描述 C1～C6（[capabilities.md](capabilities.md)，D47，#3482～#3541）；主题架构 S1～S11（[ui/theme.md](ui/theme.md)，D46）；Wow 查询 N1～N6 的采用。
- **Storybook 两轮审查与零售场景**（2026-09-26～27）：P0／P1 全部处置（#3606～#3663、#3684～#3705，D50～D58）；零售数据与业务场景七批（`typescript/storybook/docs/scenarios.md`）。
- **补偿控制台重写与质量审查**（2026-09-27）：控制台 #3706～#3711（D60）；严格 CSP 下拖动带 nonce（D61，#3712）；入口逐名导出（D64，#3719）；内存数据源是公开入口 `/testing`（D65，#3725）；porcelain 还原（D63，#3715）。
- **看板真实接入与宿主接入**（2026-09-28～29）：面板配错只坏自己 A #3732、跨定义引用 C #3734；H1 #3744、H2a #3761、H2b **ViewHost** #3780、H3 声明式操作 #3783；措辞在叶子上翻 #3774；查询后端与 wow-react 补审 #3754～#3768、F9 #3770。
- **看板与分析打磨**（2026-09-29）：D68／D70 与红条措辞 #3779；D71 分析编辑区按依赖排行、D72 图型选项从卡片角进 #3781；随后的修复 #3782。
- **首发前的结构收口**（2026-09-29）：工具类前缀 `fve:`（D66）#3785；ui 根目录拆成 kit、功能目录、入口与外壳三层 #3786；严格 CSP 下整个引擎零违规、Storybook 的门（D74）#3790。
- **阶段 6 Wow 存储后端**（2026-09-29～30）：V0 没开 `spaced` 的聚合不写 spaceId #3791；V1 后端四个模块 #3795；V2 端口的改受众与一致性套件 #3797；V3a `@ahoo-wang/wow-view-store` #3810；V3b 示例服务端与补偿服务内嵌 starter、视图存储的 Kafka 主题前缀、控制台迁移、引擎端到端与网关规则文档 #3814（[D75](decisions.md#d75-首发前做-wow-存储后端用真服务端验证-viewstore2026-09-29)）。
- **两个 skill**（2026-09-30～10-01）：`wow-view-definition` 改写、`wow-view-host` 新增，示例由文档站编译；验收通过——只凭 skill 写零售场景，`admit` 首跑中英文皆 `[]`（host-integration.md 第 6 节）。
- **第二轮全面审查**（2026-09-30～10-02）：各批修复 #3826～#3863；视图存储随 9.2.0 发布（[D78](decisions.md)）、存储的系统视图（[D81](decisions.md)，#3854、#3855）；用户 2026-10-01 明确通过，推迟的条目进 [todo.md](todo.md)「首发后再议」（#3857）；可访问性声明在 rc 上重走一遍（#3858）。
- **首发 9.2.0**（2026-10-03）：`9.2.0-rc.0` 手工发到 npm（`next`），用户的 VoiceOver 抽查通过（D73）；补偿控制台在 rc 上试用通过（`typescript/RELEASING.md`「C′」，#3870 修掉 rc 之外的问题）；CI 带 provenance 发出 9.2.0（[release](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.2.0)，#3869）。记录在 `typescript/MIGRATION.md`「进度」。
- **阶段 7 文档站**（2026-10-03）：面向宿主研发，中英两版，侧边栏里视图引擎自成入门、概念、指南、参考四组。B1 #3872 落地页改写与「视图引擎入门」（`guide/typescript/view-engine`、`view-engine-getting-started`）；B2 #3873「核心概念」「写好一份定义」（`view-engine-concepts`、`view-engine-definitions`）；B3 #3875「把视图引擎接进宿主」「声明式操作」「内容安全策略」（`view-engine-host`、`view-engine-actions`、`view-engine-csp`）；B4 #3874「视图存在哪里」与 Kotlin 的「视图存储」（`view-engine-storage`、`guide/extensions/view-store`）；B5 #3876 API 参考（`reference/typescript/wow-view-engine/` 的八个手写专题页、按入口生成的英文符号索引及其过期检查、由公开面清单生成的 issue code 表）。收尾 #3881：页面互链（落地页、参考与指南、入门的下一步），入门里「只取显示的列」改为一行实际带回的字段，`model-shapes.md`、`view-store-backend.md` 5.3、`management.md` 对齐代码，主题页的令牌表检查进 `test:docs`。
- **模块重构 R1～R4**（2026-10-03，todo 的 R2-94、R2-95）：四批都守着同一条硬约束——公开面（`test/api`、`test/surface`、符号索引、消息键、CSS 合同）逐字不变，行为不变，测试断言一行不改。R1 #3891 失败类收进 `runtime/failure/`、去掉 233 个多余 export、主题文档数据移到 `theme-check/`；R2 #3893 拆掉文件夹内的值导入环；R3 #3894 约 14 处图型族 `switch` 收成图型族表（`FAMILY_RULES`、`FAMILY_VIEWS`，对 `ChartFamily` 穷尽，加一个图型族只填一行）；R4 #3895 函数长度绊线。如今守着的：`test/architecture.test.ts` 的「文件夹内无值环」（允许名单为空）、图型族表的穷尽类型、`eslint.config.js` 的 `max-lines-per-function` 200 代码行（实测 p99 之上；19 个拆不自然的函数逐个列出理由，只降不升）。
- **Kotlin ABI 基线**（2026-10-04，todo 的 R2-91）：29 个发布的 Maven 模块（`build.gradle.kts` 发布的模块去掉两个 BOM）各有一份 `api/<模块>.api`，由 Kotlin Gradle 插件 2.4 自带的 ABI 校验（`kotlin { abiValidation }`，即并入插件的 binary-compatibility-validator，存档格式相同，不加依赖）生成，`@InternalWowApi` 不计入；`check` 依赖 `./gradlew checkKotlinAbi`，`local-test.yml` 在测试前跑它。存档分别在 `v9.2.1` 与 `v9.2.0` 上生成，与 main 逐字相同。验证：删掉 `AggregationGroup.Histogram` 的 9.1 构造器（#3827 补回的那种）检查失败并指出那一行，加一个成员只多出一行 `+`。什么时候可以改存档写在根 AGENTS.md「Binary Compatibility」。#3900
- **9.2.2 稳健性，lane 1**（2026-10-04，todo 的 R2-102 与 G）：`bind` 一个没登记的定义 id 经 `onIssue` 报 warning `binding.definition.unknown`（`engine.checkBinding`，**ViewHost** 逐个核对）；`admit` 与引擎启动说同一件事——都判声明时的原始定义，传完整的资源列表（有资源带 `source`）时也查 `definition.source.unregistered`，只有 `{ definition }` 的列表照旧；枚举字段宽容存下的 `EQ`，按一个值的 `IN` 读（可选钩子 `FieldKind.readLeaf`、`readFilter`）；控制台执行页自管 `?view=` 的偏离写进控制台 README。公开面只增不减。
- **9.2.2「稳健性」：焦点与开发期提示**（2026-10-04，todo 的两项「首发后再议」）：弹层的打开者不在时，关上交还的键盘落到离它最近、还在页面上的控件，不掉到 `<body>`（`kit/focus.ts`，`kit/popups.tsx` 里交还键盘的六种弹层都经它；`test/openerGone.test.tsx` 照清单逐个验，[host-integration.md](host-integration.md) 4.4）；开发构建里声明式操作的规则读了行上没取回的字段时，经 `onIssue` 报 `record.action.unfetched`，每个视图每个字段一次（`runtime/actionReads.ts`，host-integration.md 5.1），生产构建不做代理。

## 协作规则

- **PR 描述就是交接说明**：写清完成了什么、定了什么、有哪些坑；接手的人从分支加 PR 描述接着做。
- **合并前审查**：功能 PR 合并前有一轮独立的只读审查，发现修完再合。合并用 `gh pr merge <N> --squash --auto`，靠分支保护的必需检查（PR Safety、typescript-gate、typescript-contract-gate、typescript-storybook-gate）。
- **合并前核对改动范围**：`gh pr diff <N> --name-only` 里出现范围之外的路径就不合（#3573 曾因用旧 base 压缩提交，悄悄回退了后端的改动）。压缩提交只用 `git reset --soft $(git merge-base HEAD origin/main)`。
- **推送前本地验证**（rebase 后也要重跑）：`pnpm --filter @ahoo-wang/wow-view-engine test`（含三份 tsc）、`lint:check`、`build`（含体积上限）、清单型测试、受影响的单测与故事、prettier；按改动范围追加控制台（e2e 带 `CI=1`）、截图、契约、文档的门禁。CI 负责全量。
- **查询后端的契约变更走交接协议**：凡是 TS 要镜像的契约，后端先开 PR 不合并，TS 侧推一个普通提交后回复「TS ready: #N」；镜像的门禁包含视图引擎的措辞与查询拒绝的测试和 `test:type`。
