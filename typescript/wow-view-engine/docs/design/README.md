# View Engine 架构设计

**状态**：重写设计稿，替代 `refactor-spec.md`、`invariants.md`、`first-deliverable.md`。
**基线**：当前 `typescript/wow-view-engine` 以 tag 冻结为只读参考；新树在同一包名下自下而上重建。
**原则**：行为约束以测试存在，本文只写模型、边界、合同与顺序。
**组织**：本目录一页一层。本页是稳定内核——定位、范围、分层规则、质量守护与交付顺序；模型、内核、运行时、管理、React 与 UI 各有自己的页。

## 目录

| 页                                         | 内容                                                                                                          |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------- |
| [model.md](model.md)                       | 核心模型（一）：定义、三类配置、查询筛选规则、配置模型原则                                                    |
| [model-shapes.md](model-shapes.md)         | 核心模型（二）：图表规格、Filter 树、实例与偏好、`Issue`                                                      |
| [kernels.md](kernels.md)                   | 四个纯内核的签名与校验、编译、投影规则，以及定义准入                                                          |
| [runtime.md](runtime.md)                   | `ViewRuntime` 状态与命令、执行规则、自动刷新、`DashboardRuntime`、`ViewEngine`、环境                          |
| [management.md](management.md)             | 实例生命周期、范围与许可、列表与偏好、冲突与未知结果、`ViewStore` 与一致性                                    |
| [react.md](react.md)                       | `/react` 的钩子与控制器合同，一节一个钩子                                                                     |
| [ui/README.md](ui/README.md)               | `/ui` 三种视图共用的规则：措辞、状态条、值显示、主题作用域、工作台骨架、保存与管理、FilterPanel               |
| [ui/record.md](ui/record.md)               | Record 的结果区组件                                                                                           |
| [ui/analysis.md](ui/analysis.md)           | Analysis 的编辑器、表格与图表                                                                                 |
| [ui/dashboard.md](ui/dashboard.md)         | Dashboard 的栅格、面板 chrome 与面板级告警                                                                    |
| [ui/embed.md](ui/embed.md)                 | 嵌入：两个入口、交互档、开关、仪表盘筛选三态、高度                                                            |
| [extension.md](extension.md)               | 扩展点（`FieldKind`、数据源、持久化、动作槽位、外观）与 Wow 协议的对应                                        |
| [phase5-themes.md](phase5-themes.md)       | 阶段 5 内置多主题的方案：现状审计、选项与取舍、批次（裁定见 D30）                                             |
| [themes.md](themes.md)                     | 内置主题系统与经典风格预设的方案：目标与边界、token 分层、预设目录、选择与切换、质量门、批次，待拍板 Q60～Q63 |
| [analysis-echarts.md](analysis-echarts.md) | 分析视图全面释放 ECharts 的方案：现状审计、能力地图、首发前批次、待拍板 Q51～Q59                              |
| [decisions.md](decisions.md)               | 界面已经体现的产品决定，以及搁置待议的问题                                                                    |
| [todo.md](todo.md)                         | 已决定但尚未做的事；完成即删                                                                                  |
| [progress.md](progress.md)                 | 本轮到哪了：已落地的、复验过的、暂停点与下一步；每个暂停点重写                                                |

## 定位与第一性原理

View Engine 让业务应用**用配置而不是页面代码**表达对 Wow 查询数据的观察方式：明细（Record）、分组分析（Analysis）、组合概览（Dashboard），并让有价值的配置可以保存、重开、共享与嵌入。

**解决的问题。** 业务系统的大多数页面是"列表加筛选、排序、分页，偶尔一张图"，每个业务对象各写一套；观察方式的每次调整都要改代码与发版。数据没有变，变的只是观察方式，而观察方式被写死在页面代码里。引擎把观察方式抽成可校验、可执行、可保存的配置，让用户自行调整，让研发对每个业务对象只接入一次。

价值链只有一条：

```text
ViewDefinition + ViewConfig ──compile──▶ Wow 查询 ──execute──▶ 结果 ──project──▶ 呈现
                    └──────────── save / open ────────────┘
```

设计建立在三个事实上：

| 事实                                                                                  | 推论                                                                                           |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **定义是代码。** 字段、类型、操作符、聚合能力来源于 Wow 聚合的查询 schema，随应用部署 | 没有定义维护服务、定义版本或定义重载协议；定义变更就是一次发版，实例在打开时按当前定义校验一次 |
| **配置是数据。** 用户保存的是观察方式，不是数据快照                                   | 持久化对象只有 `ViewInstance` 和个人偏好；一致性策略是乐观版本加幂等 requestId                 |
| **运行状态是临时的。** 草稿、结果、分页、选择只活在一次打开中                         | 运行时是每个打开的视图一个小 store，不持久化、不进入契约                                       |

## 范围

**做**：Filter 树编辑与编译；Record 分页明细、排序、列、汇总、行动作；Analysis 分组与指标、图表与表格投影；Dashboard 面板组合、全局筛选、栅格布局；实例保存、另存、改名、删除、个人排序与默认；`ViewStore` 端口与 Memory 实现；无样式钩子与默认 UI 两种消费方式；独立嵌入。

**不做**：视图种类插件；定义 CRUD 后端与定义版本；写入回执核对、读屏障、精确一次；并发与页大小以外的资源预算账本；SSR 预载；通用 region 或事件总线；跨页全选、单元格编辑、Dashboard 嵌套。

## 分层与依赖规则

```text
src/
  model/        类型与常量
  filter/       Filter 树：校验、编译到 FilterExpression、FieldKind 注册表、编辑器描述
  record/       Record：校验、编译到 FilterPagedQuery / CursorQuery、结果投影
  analysis/     Analysis：校验、编译到 AggregationQuery、结果投影为图表或表格数据
  dashboard/    Dashboard：校验、面板绑定解析、全局筛选合并
  runtime/      ViewRuntime、DashboardRuntime、RequestRunner、ViewEngine、命令、validateDefinition
  store/        ViewStore 端口；MemoryViewStore
  react/        钩子与无样式控制器
  ui/           shadcn 组件、默认视图、工作台、主题边界
```

依赖规则六条，全部由 `test/architecture.test.ts` 强制：

1. `model` 不 import 任何目录。
2. `filter` 只 import `model`。
3. `record`、`analysis`、`dashboard` 只 import `model` 和 `filter`，彼此不引用。
4. `runtime` 只 import `model`、`filter`、`record`、`analysis`、`dashboard`、`store` 的端口类型；不 import `react`、`ui`。
5. `store` 只 import `model`。
6. `react` 不 import `ui`；`ui` 可以 import 一切。

`model` 到 `store` 六个目录不出现 React、DOM、`window`、`document`。第三方库落点固定，清单在 `test/architecture.test.ts` 的 `HEADLESS_DEPENDENCIES`，未列出的依赖一律只许在 `ui`：`@ahoo-wang/wow-client` 只在根入口与 `model`、`filter`、`record`、`analysis`、`runtime`（`dashboard`、`store` 都没有），且不导入其弃用的 `Condition` 系符号；`dayjs` 在 `filter`、`record`、`analysis`、`runtime`、`ui`；`dequal` 只在 `runtime`；`culori` 在 `analysis` 与 `ui`（`ui` 用它把主题色转成图表库认的 `rgb()`）。只在 `ui` 的是 `@base-ui/react`、`@dnd-kit/dom`、`@dnd-kit/react`、`class-variance-authority`、`cn`、`lucide-react`、`react-day-picker`、`react-error-boundary`、`react-grid-layout`、`react-markdown`、`echarts`；`react`／`react-dom` 是可选 peer，只在 `react` 与 `ui`。表格库不在其中——D16 裁定 1 否掉了 `@tanstack/react-table`，Record 表格用 registry 的 `Table` 加本包自己的列模型。

包入口：

| 入口                         | 内容                                                                                                                             |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `@ahoo-wang/wow-view-engine` | `model`、四个纯内核、`runtime` 的公开面（`ViewEngine` 与运行时合同，见 `runtime/index.ts`）、`ViewStore` 端口、`MemoryViewStore` |
| `/react`                     | 钩子与控制器                                                                                                                     |
| `/ui`                        | 默认组件、默认视图、工作台                                                                                                       |
| `/styles.css`                | 主题；宿主通过 `:root` 上的 `--fve-*`／`--fve-dark-*` 变量定制                                                                   |

每个代码入口导出什么逐名记在 `test/surface/`，由 `test/publicSurface.test.ts` 守着（[D29](decisions.md#d29-公开面逐名守着运行时只导出宿主要握的2026-09-24)）：层的 `index.ts` 照旧 `export *` 自己的文件，唯独 `runtime/index.ts` 逐名导出——调度器、运行时共用的 store、计时器、监听者集合、运行时的类都不出包，`react`、`ui` 从各自的文件里取。

## 质量守护

| 层     | 手段                                                                                                    | 占比预期 |
| ------ | ------------------------------------------------------------------------------------------------------- | -------- |
| 架构   | 六条依赖规则 + 第三方库落点测试                                                                         | 常量     |
| 纯内核 | Vitest 单元测试；从旧树搬迁日期时区、操作符、编译、投影用例                                             | 最大     |
| 运行时 | Memory store + 假数据源；替代在途、冲突、未知结果、待修复                                               | 中       |
| React  | Testing Library 钩子与控制器测试                                                                        | 中       |
| UI     | 组件测试 + Storybook 交互测试（Record 工作台优先）                                                      | 小       |
| 包产物 | `verify-package`：入口可导入且运行时导出与公开面清单一致、核心入口无 DOM 类型、CSS 不被 JS 入口自动导入 | 常量     |

不再维护不变量索引。一条规则若值得存在，它是一个测试。

### 本地门禁

合并前在本机跑齐，每条单独看退出码，全部为 0 才算过；命令、顺序与环境的全文在仓库的 [stories/README.md「本地门禁」](../../../../stories/README.md#本地门禁)，真实后端场景怎样在本机连上服务在同一页「真实后端」。这里只记本包的部分：

- 新 worktree 先在仓库根 `pnpm build:typescript`：本包的测试与构建按 `dist/` 引用 wow 等依赖；
- 包目录里三条：`pnpm lint:check`、`pnpm test`（vitest 加覆盖率阈值——阈值在本包的 `vitest.config.ts`，不达标即非零退出，哪怕每个用例都绿——然后 `test:type` 的三个 tsc 工程）、`pnpm build`（vite build 后 `test:package` 检查产物）；
- 仓库根三条：`pnpm lint:stories`、`pnpm typecheck:stories`、`pnpm test:storybook`——最后一条带 `PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright-user`，Chromium 装在自己拥有的目录里（默认缓存可能是旧版本或归 root 所有，做法见上面那页）；
- 改动过的每个文件 `prettier --check`；
- 根目录 `pnpm build` 会经 wiki 的 `prebuild` 重写 `wiki/llms-full.txt`，不是改动的一部分就还原。

## 交付顺序与搬迁规则

自下而上，每步独立 PR、独立可用：

| 步   | 交付                                                      | 搬迁                                                                       |
| ---- | --------------------------------------------------------- | -------------------------------------------------------------------------- |
| 1 ✅ | `model/`、架构测试、本设计                                | 全新                                                                       |
| 2 ✅ | `filter/` 内核与 FieldKind 注册表                         | 先搬测试改为新类型，再搬实现；只有"改 import 即可编译"的文件才搬，否则重写 |
| 3 ✅ | `record/`、`analysis/` 内核                               | 同上；analysisCompiler、analysisProjection、recordValidation 为主要来源    |
| 4 ✅ | `runtime/`、`store/` 端口、Memory                         | 全新；旧 engine 测试中描述行为的用例改写为 ViewRuntime 测试                |
| 5 ✅ | `/react` 最小钩子 + 朴素表格示例                          | 全新；**闭环一在此跑通，之后才进入视觉工作**                               |
| 6 ✅ | `/ui` Record 工作台：FilterPanel、RecordTable、列表、保存 | shadcn 组件与主题 CSS 直接搬；复合视图重写                                 |
| 7 ✅ | Analysis 编辑器与图表                                     | 内核已就位，UI 重写                                                        |
| 8 ✅ | `dashboard/` 内核、DashboardRuntime、DashboardGrid        | 内核搬，运行时重写                                                         |
| 9 ✅ | Storybook 状态集；README 双语；`verify-package`           | 一个用 fetcher 实现 `ViewStore` 的示例放在 examples，作为端口的第二消费者  |

不搬迁清单：旧 `contracts/`、`engine/`、`StatefulViewHost`、三个 `*View.tsx`、`AnalysisEditor.tsx`、`view/` 目录。

闭环一定义：用户从默认订单视图筛选待出库记录，调整列与排序，保存为个人视图，重开后恢复配置但不恢复选择与页码，数据变化后刷新得到新数据。第 5 步以此为退出条件，由 `test/closedLoop.test.tsx` 驱动 `examples/PlainRecordWorkbench.tsx` 逐条验证，因此退出条件是可执行的而不是口头的。

## 后续方向

- **定义生成。** `wow-generator` 从 Wow 聚合元数据生成 `ViewDefinition`，业务方零成本获得记录、分析与概览。这是"配置代替页面"相对于手写 React 页面的决定性杠杆，也是定义作为代码的直接结果。
- **共享与嵌入。** `scope: 'shared'`、服务端配置的 `scope: 'system'` 与嵌入（`EmbeddedView`、`EmbeddedDashboard`）在业务应用的 `ViewStore` 落地后由业务服务授权；嵌入页面锁定的条件不是安全边界（[ui/embed.md](ui/embed.md#锁定不是安全边界)）。
- **服务端实现。** 若需要官方后端，另立设计文档随后端代码放置；[management.md](management.md) 的 `ViewStore` 合同是它的输入。
- **更多图型。** 图表按族扩展，新增一族只增加一个子对象、一个 `type` 字面量、一条校验分支、一段投影与一个渲染器，不改既有类型。候选：帕累托（combo 加投影层累计占比，依赖结果集完整）、矩形树图、箱线图（百分位指标已能支撑）。
