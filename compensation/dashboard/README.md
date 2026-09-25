# Wow Compensation Dashboard

该 React 应用是 `wow-compensation-server` 的运营客户端：查询 `ExecutionFailed` 队列和历史，通过 Dashboard 查看补偿压力与趋势，修改恢复性/重试规格/目标函数，以及发起准备或强制准备。服务端状态机才是最终决策边界。

控制台建在 `@ahoo-wang/wow-view-engine` 上：列表、筛选、分析、看板、详情抽屉都是引擎的，控制台只写补偿的定义（`src/views/`）、领域命令与外壳。重构的方案、分批记录与对真服务的验证报告见 [docs/design/view-engine-rebuild.md](docs/design/view-engine-rebuild.md)；运营口径见文档站的[补偿控制面](../../documentation/docs/zh/reference/example/compensation.md#补偿控制面)。

## 开发

从仓库根目录执行：

```shell
pnpm install --frozen-lockfile
pnpm --filter wow-compensation-dashboard^... build

VITE_API_BASE_URL=http://127.0.0.1:18083/ \
pnpm --dir compensation/dashboard dev --host 127.0.0.1
```

Wow 客户端与视图引擎来自同仓的工作区包 `@ahoo-wang/wow-client`、`@ahoo-wang/wow-react`、`@ahoo-wang/wow-view-engine`（`workspace:*`），它们通过 `dist` 被引用，所以第二行先构建 Dashboard 依赖的工作区包；修改 `typescript/` 下的 SDK 后重新执行这一行，SDK 与视图引擎的改动在同一个 PR 里由 Dashboard 的构建与测试验证。`src/main.tsx` 引入视图引擎的 `styles.css` 与 `shadcn-bridge.css`，引擎视图穿控制台自己的 shadcn 主题。其余 Fetcher 包来自 npm。

`VITE_API_BASE_URL` 是所有 Fetcher 请求的基地址。`.env.development` 默认指向开发集群服务；连接本地服务时必须像上面一样显式覆盖。本地补偿服务的安全启动命令见[补偿参考案例](../../documentation/docs/zh/reference/example/compensation.md#本地服务启动、健康与路由验证)。

## 概览

`/` 是「概览」：视图引擎的系统板「补偿概览」（定义在 [`src/views/overview.ts`](src/views/overview.ts)），以 `EmbeddedDashboard` 的 `interactive` 档铺满内容区（[重构方案](docs/design/view-engine-rebuild.md)批 6）。读者能改时间范围、点进面板、铺满屏幕、看「更新于」并手动刷新（`withRefresh`），什么也不存（嵌入一律不写）；`/dashboard`、`/analytics` 与未知地址都回到这里。

- **时间范围**是板上唯一的筛选，缺省「近 7 天」（今天与之前 6 个整天，与旧首页一致），收窄失败执行的 `state.executeAt` 与事件流的 `createTime`；「全部活动」不接它。筛选值记在这一条浏览记录里，刷新与从工作台返回都还在。
- **积压**：范围内活动、全部活动、可立即处理（即「已到重试时间」队列）、已超时、不可恢复；旧首页的「更早／更新」积压改说成「范围内」与「全部」两个数（方案 G9）。
- **补偿成效**：新增失败、准备重试、重试失败、重试成功（事件流里含该事件的流数，带每日走势），净积压与重试成功率由服务端按事件名计数后算出。
- **失败集中度**：前 5 个集群，面板只读六列（错误码、处理器、函数、活动失败、最早执行、最早下次重试）；面板的「在工作台中打开」开系统视图「失败集中度」，五维身份与按状态拆开的全部十列；点一个集群进「失败执行」的「活动中」视图，带着这个集群与时间范围作为条件，「返回 补偿概览」回到板上。
- **可恢复性构成**与**重试次数分布**（0、1–2、3–5、6 次及以上）。
- **最需要处理的记录**：「已到重试时间」队列，按下次重试时刻升序，行上与勾选后的命令与「失败执行」一致（D39 的记录面板）；「在工作台中打开」进那张队列。
- 右上角「在仪表盘工作台中打开」进 `/boards`：视图引擎的 `DashboardWorkbench`，可以另存、搭自己的板（存在本机）。事件流的面板「在工作台中打开」进 `/executions/events`：事件流的工作台，缺省打开「全部事件流」（按时间降序，每行带执行 ID），另有单条执行的「执行历史」。
- 板与工作台都能「铺满屏幕」：`index.css` 把引擎的 `--fve-expanded-z-index` 设为 20，铺满的面盖住控制台固定的侧栏（`z-index: 10`），仍在引擎的弹层（50）之下。

## 失败执行

`/executions` 是「失败执行」：`@ahoo-wang/wow-view-engine` 的 `DataWorkbench`（[重构方案](docs/design/view-engine-rebuild.md)批 1～5），定义在 [`src/views/`](src/views/)：`execution_failed` 快照的字段与分组、旧控制台七个队列对应的系统视图（活动中、待重试、执行中、已到重试时间、不可重试、不可恢复、已成功）加「全部」、四张分析（按状态分布、活动失败按处理器、每日新增失败、失败集中度）。宿主侧栏只有「概览」「失败执行」两项，队列与个人视图都在工作台自己的视图列表里（方案 Q3）。

- **旧地址都还在**（批 5）：`/active`、`/to-retry`、`/executing`、`/next-retry`、`/non-retryable`、`/succeeded`、`/unrecoverable` 跳到对应的系统视图（`/executions?view=system:execution-failed:<队列>`），带来的参数原样带过去，跳转替换历史记录。打包服务器对这些地址与 `/executions`、`/executions/events`、`/boards` 都返回控制台入口，直接打开或刷新都可以。
- **链接带来的范围**：`?cluster=`（仪表盘失败集群的完整函数身份、错误码与统计窗口）与 `?start&end`（仪表盘的执行时间窗口，`end` 不含）转成打开的那张视图的作用域条件：条件栏上带「由页面设定」、不能单独移除，工作台上方一行说明「此视图按打开它的链接限定了范围」并给「移除限定」；换到别的视图时范围随之放下、地址里的参数一并去掉。参数读不出来时只说「集群筛选无效／时间范围过滤条件无效」并给清除按钮，不退回无筛选查询。概览板上的点击不经过这两个参数：板把视图连同条件直接交给工作台（引擎的 `handOver`，随这条浏览记录保存）。
- 「已到重试时间」（原 `/next-retry`）表示已经到达自动调度时间的候选。

- **行动作与成批动作**（批 3）：每行有「准备」按钮与「⋯」菜单（强制准备、标记可恢复性）；勾选后工具栏左端出现「准备 N 条」「强制准备」「标记可恢复性」。可操作性与旧页面同一条规则（`compensationCapabilities.ts`，与命令侧的 `canRetry()`／`canForceRetry()` 一致）：未超时的已准备记录按不了，按钮的提示与菜单顶端写出原因。成批命令先确认（写明条数，并列出控制台已知不会发送的记录及原因）；命令经生成的 `ExecutionFailedCommandClient` 发出，等到快照写入再返回（`Command-Wait-Stage: SNAPSHOT`），由引擎的 `useBulkCommand` 每次 4 条并发地跑，工作台上方一行报告进度（可停）与结局，服务端拒绝的记录带着服务端的原因仍保持勾选，跑完刷新当前页。一行的命令也走同一个 `useBulkCommand`，结局报在同一行里。
- **详情抽屉**（批 4）：点一行（或键盘回车）从侧边打开这一条，引擎按定义的字段分组读全；控制台的节插在相关分组之后：「变更函数」表单在处理函数之后，「堆栈跟踪」在错误之后（行号、Java 高亮、自带滚动区、换行开关、一键复制；引擎的「错误」分组因此不再列这一字段），「应用重试规格」表单在重试之后，「执行历史」在最后——补偿事件流的系统视图以 `EmbeddedView` 嵌入，作用域是这一条的 ID，按版本降序、每页 10 条、每条事件流按事件类型读，明暗随抽屉；按一行打开叠在执行详情上的只读记录详情（引擎的 G20）：整条读出这一条事件流，事件逐个按类型、载荷逐键读全（含那次失败的堆栈），Esc 只关这一层、焦点回到那一行。两份表单经生成的客户端发命令、等到快照写入再返回，成功后重读这一条，服务端拒绝时把原因写在表单下。抽屉头部是这一行的「准备」与「⋯」，与行上同一套。
- **打开的是哪一条在地址的 `id` 参数里**（`?id=`，旧页面的告警链接同样带它）：按行或关掉都改写地址；不在当前页上的也能打开，读取中、已不在、无权限、读取失败（可重试）四种状态由引擎说。整条读到之前只画执行历史，表单与堆栈等整条到了再画。
- **复制 ID** 是定义里 `copyable` 字段旁的按钮（ID、事件 ID、事件聚合 ID），纯 HTTP 部署没有剪贴板 API 时退回文档的 `copy` 命令。
- 打开的视图在地址的 `view` 参数里，视图可以当链接发出去。
- 条件、搜索、列、排序、分页、卡片、导出都是引擎的。个人视图存在**这台电脑的这个浏览器**里（`MemoryViewStore` 的快照写 `localStorage`，键 `wow-compensation-dashboard:views`），视图列表与保存对话框都这样说；共享视图等 Wow 存储后端（阶段 6）。
- 定义的显示名只有一种语言，所以中英各建一份定义，换语言时重建引擎（方案 G12）。
- **能用什么由服务端说**（N5 C6）：`src/views/engine.ts` 的两个数据源带上 `describe`（wow-client 的 `QueryDescriptorClient.describeSnapshot`／`describeEventStream`，同一个 `fetcher`），引擎先读 `execution_failed/snapshot/schema` 与 `execution_failed/event/schema` 的能力描述，把定义收窄到描述准入的算子、排序、分组与上限，再发第一条查询；描述每个源读一次，之后按引擎的节奏带版本重新验证（304）。定义不再替服务端写上限（页大小、窗口、分析组数都读描述）。服务端没有描述（Wow 9.2 之前）时照定义运行。
- 「搜索错误」是全文检索，**只在存储答得出时出现**：Elasticsearch 快照存储按短语检索；MongoDB 快照存储只在集合上建了文本索引时才描述全文能力，没建时搜索框不画、也不会发出注定被拒的检索（方案 G15，随 C6 关闭）。
- **只用引擎的公开面**（方案判据 7）：ESLint 只放行 `@ahoo-wang/wow-view-engine` 的根、`/react`、`/ui` 与 CSS 入口，拒绝字符串里的 `fve-` 类名；`src/engineBoundary.test.ts` 断言样式表里没有 `.fve-` 选择器——引擎的外观只经 `--fve-*` 变量调。开发构建里引擎对定义的发现（本部署缺的能力、读不到描述）以 `console.debug` 打出，生产构建不打。
- `src/main.tsx` 先引入 `index.css` 再引入引擎样式：两者都写 Tailwind 的 `utilities` 层，引擎的放在后面，它的响应式类才不被控制台的全局工具类盖掉（方案 G16）。

## 验证命令

| 目的                                          | 命令                                                                                         |
| --------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 类型检查（应用、构建配置与 `e2e/`）与生产构建 | `pnpm --dir compensation/dashboard build`                                                    |
| 单次运行 Vitest                               | `pnpm --dir compensation/dashboard exec vitest run`                                          |
| 代码检查                                      | `pnpm --dir compensation/dashboard lint`                                                     |
| 覆盖率门禁                                    | `pnpm --dir compensation/dashboard coverage`                                                 |
| 构建后浏览器测试                              | `pnpm --dir compensation/dashboard test:browser`                                             |
| 对真实服务端的冒烟（可选）                    | `WOW_COMPENSATION_URL=http://127.0.0.1:18083 pnpm --dir compensation/dashboard test:browser` |
| 本地预览                                      | `pnpm --dir compensation/dashboard preview --host 127.0.0.1`                                 |

`pnpm --dir compensation/dashboard test` 直接调用 `vitest`，在交互终端中可能进入 watch；CI 和一次性验证使用表中的 `vitest run`。Playwright 会在 `127.0.0.1:4174` 运行已构建的 preview，首次使用前需确保 Chromium 已安装。

浏览器测试默认只跑打桩的一套（`e2e/*.spec.ts`，接口由 `page.route` 桩住）。其中 `e2e/accessibility.spec.ts` 用 `axe-core` 在两个视口检查每张系统视图、概览、详情抽屉（含嵌套的事件流详情）、事件流与仪表盘工作台，WCAG 2.0／2.1 A、AA 须 0 违规。设了 `WOW_COMPENSATION_URL` 时改为只跑 `e2e/real-server/`：不起 preview，直接打开那台服务端，它须从仓库根目录启动、提供上一步构建的 `dist/`（启动命令见 [RELEASING.md §C′](../../typescript/RELEASING.md) 第 3 步）。冒烟自己写入两条失败执行（处理器名带本次运行的标记），直接打开 `/executions`，断言真实的行渲染出来、按处理器加一个条件后只剩一行；再直接读服务端的快照能力描述，断言「搜索错误」只在描述里有全文检索时出现（MongoDB 未建文本索引时不出现、也不发检索）；再打开「待重试」与「已到重试时间」，断言服务端按自己的时钟接受 `BEFORE_NOW`／`AFTER_NOW`：新写入的两条在前者、不在后者（首次重试排在最小退避之后）；再按 `?id=` 打开第一条，断言抽屉画出它的处理器、重试规格表单、「没有堆栈」，且执行历史由服务端的事件流答出、第一条是「首次失败」；对第三条自己写入的记录按行上的「准备」，断言命令带 `Command-Wait-Stage: SNAPSHOT` 到达服务端、结局行报「1 项完成」、这一行读回「已准备」且准备按钮变为不可用；再打开旧地址 `/to-retry?start&end`（最近一小时），断言它跳到「待重试」、限定说明在、新写入的两条都在，刷新后服务端照样答出这个地址；在第二条的执行历史里打开「首次失败」那一行，断言读出的载荷里有写入时的错误信息；最后打开概览，断言「全部活动」等于服务端直接答的活动失败数、「范围内活动」与「新增失败」至少是本次写入的条数、净积压与重试成功率由服务端答出、「可立即处理」等于记录面板的总数；全程没有 4xx、5xx 与页面错误。它会写数据，只对测试环境跑；CI 不跑（要 JDK、Gradle 构建补偿服务端与 MongoDB，不适合放进 `dashboard-test.yml`）。

## 生成客户端边界

[`src/generated/`](src/generated/) 是 `wow-generator`（工作区包 `@ahoo-wang/wow-generator`）根据补偿服务 OpenAPI 产生的输出，连同生成清单 `.wow-generator.json` 逐字节提交，不是手工维护源码：

1. 先在 `wow-compensation-api`/服务端修改公开合同并生成运行时 `/v3/api-docs`；
2. 构建生成器：`pnpm --filter @ahoo-wang/wow-generator build`；
3. 开发集群可访问时执行 `pnpm --dir compensation/dashboard generate`（读取 `package.json` 中的集群 OpenAPI 地址）；否则按[补偿参考案例](../../documentation/docs/zh/reference/example/compensation.md#本地服务启动、健康与路由验证)在本机启动补偿服务，再执行 `pnpm --dir compensation/dashboard exec wow-generator generate -i http://127.0.0.1:18083/v3/api-docs -o src/generated`；
4. 审查生成 diff，再运行 build、Vitest 和 lint。

业务代码通过 [`src/services/`](src/services/) 包装生成的 command/query client；基地址和 CoSec 策略也在该层组装。不要为规避后端/OpenAPI 缺陷而手改 `src/generated/`。覆盖率统计排除该目录；ESLint 照常检查它，只关闭 `@typescript-eslint/no-explicit-any`，因为生成器把自由结构的 OpenAPI schema 和装饰器的 `attributes` 参数映射为 `any`。

指标口径、补偿状态、运营权限与部署要求见[补偿控制面](../../documentation/docs/zh/reference/example/compensation.md#补偿控制面)和[事件补偿指南](../../documentation/docs/zh/guide/event/compensation.md)。
