# Storybook 维护约定

Storybook 是可运行的接入文档，也承载浏览器交互回归。目录按「导览 → 业务场景 → 能力 → 组件状态 → 真实后端」组织（[docs/scenarios.md](docs/scenarios.md) 第 5 节），代码按模块就近维护。

面向真实交易订单的场景化方案（零售数据集、能力到场景的对照、目录与迁移批次）见 [docs/scenarios.md](docs/scenarios.md)，第 1～5 批已落地：零售数据集、数据源，以及「View Engine/业务场景/…」下的订单工作台、售后工作台、分析工作台、运单宽表与订单事件流（各有一个轻量孪生，断言种子定下的黄金值）；第 4 批是三块仪表盘、两张嵌入页与首页的运营日报，决定与偏差见它的 4.2；第 5 批是目录与导览，见它的 5.4。展示用零售数据，回归夹具不动，孪生不一定用和展示一样的数据（例如「能力/长时间轴」的展示是零售的日 GMV 与逐时 GMV，孪生仍是一年与一万天的每日发货）。

## 示例与回归

- `*.stories.tsx`：展示组件、初始参数、说明和可手动操作的场景。允许初始化读取和无副作用的渲染断言。
- `*.test.stories.tsx`：导入展示故事，复用参数和演示实现，安装复杂 `play`。使用 `['!dev', '!autodocs', 'test']`，保留测试执行并隐藏默认导航与文档入口。
- `*.play.ts`：较长交互需要单独成文件时的具名实现，就近维护（目前没有这样的文件，`play` 都写在孪生故事里）。不要求为简单断言单独建文件。
- `shared/`：只有真实复用的场景外壳（View Engine 全部场景的宿主应用外壳 `AppShell`，其余包的文档场景外壳 `ScenarioFrame`）和 Ant Design Provider。模块显式声明装饰器，不通过故事标题选择 Provider。

普通展示不能依赖自动测试来创建初始数据或完成异步请求。打开页面后，筛选、保存、创建和删除均由使用者触发。

## 文档与状态

文档页跟着工具栏的明暗走：`.storybook/ThemedDocsContainer.tsx` 读同一个 `theme` 全局，暗色时用 Storybook 的暗色文档主题，并像 `withMode` 一样在 `<html>` 上挂 `.dark`（不挂载故事的文档页——导览——没有装饰器会替它挂）。

`.storybook/DocsPage.tsx` 使用原生文档块展示一个主示例、参数和独立场景链接，避免将所有场景同时挂载。复杂包装器的代码面板引用真实接入源码。

修改全局 fetch 或 Viewer 默认注册器的示例使用独立 iframe，并通过 `beforeEach` 返回清理函数。共享夹具的数据可以复用，可变状态不能跨场景共享。未知来源的请求交给原始 fetch；受控失败只作用于示例 API。

View Engine 的故事在 `view-engine/`，目录分五块：**导览**（`Intro.mdx`，一页文档，Storybook 打开时就在这一页）；**业务场景**（零售数据集上的三块板、五个工作台与两张嵌入页）；**能力**（一项能力一页：显示收口、参考与算出的系列、长时间轴、框选与追问、板上的搜索、主题与预设）；**组件状态**（记录工作台、分析工作台、仪表盘、EmbeddedView、EmbeddedDashboard、筛选编辑器，每个故事只呈现一种状态：有数据、空结果、加载中、查询失败、待修复、面板不可用）；**真实后端**。一个故事只进一处，次序由 `.storybook/preview.tsx` 的 `storySort` 定。组件状态的状态由 `fixtures.ts` 里的假数据源决定，引擎与存储每次挂载都新建，因此保存、改名与删除是真写入，也不会跨场景残留。

假数据源按引擎实际发出的查询作答：`rowSource.ts` 把 Wow 查询翻译成 MongoDB 查询，交给 `mingo` 做筛选、排序、分页与聚合，所以表格、汇总行和图表就是这些条件选出的结果，不预聚合。按日期分桶（`DATE_HISTOGRAM`）在管道外按分组的时区算出桶起点（毫秒，与服务的答法相同），时区换算用平台的 `Intl.DateTimeFormat`（dayjs 的 timezone 插件按宿主机自己的时区规则换算，宿主机调表的那几天会差一小时）；周从周一开始、季度从 1/4/7/10 月开始，与 `wow-mongo` 的 `$dateTrunc` 相同。`dense` 按 Wow 的规则补空桶：只在它是唯一分组时，只补有数据的首末桶之间，空桶的计数为 0、其余为 null。`PERCENTILE` 是精确值（排序后在秩 `(n − 1) · p / 100` 处线性插值；服务是近似值，落在同样的两个相邻值之间），`STDDEV`/`VARIANCE` 是总体标准差与方差（与 `$stdDevPop` 相同），`ANY` 取最大的非空值（与 `wow-mongo` 的 `$max` 相同）；数组的 `CONTAINS_ALL` 是 `$all`，元数据的 `OWNER_ID`、`AGGREGATE_ID(S)` 读快照信封上的 `ownerId`、`aggregateId`。翻译不了的算子直接报错，表现为查询失败，而不是给出一个看似合理的错误答案。每个界面的 `*.test.stories.tsx` 断言这些结果；`rowSource` 自己的语义由 `rowSource.test.ts`（vitest 的 `unit` 工程，node 里跑）守着。

### 假数据源的速度

零售数据集约 2 万张子订单（[docs/scenarios.md](docs/scenarios.md) 2.8）。`rowSource` 在 mingo 前面加了四样东西，都不改变答案：

1. **时间列存成纪元毫秒**，和 Wow 快照一样；分桶与切片直接读数字，不逐行解析文本。
2. **日期桶按日历日缓存**：一天以上的桶都从本地零点开始，所以桶是那一天的属性。每个 (单位, 时区) 每个日历日只换算一次，之后查表；一天以内的桶从当天零点按宽度数（那天没有调表时），调表的那天逐行读墙上时钟。缓存按 (单位, 时区) 共享，与字段无关，因为桶只由日历决定。
3. **按时间列切片**：`rowSource(rows, { timeField: 'firstEventTime' })` 让行按这一列排好序；筛选顶层 AND（嵌套的 AND 会展平）里对这一列的 `GT`/`GTE`/`LT`/`LTE`/`EQ`/`BETWEEN` 数字条件先二分切出那一段，完整的筛选仍在这一段上跑一遍。不传 `timeField` 时行的顺序与行为都和以前一样，现有夹具都没有传。
4. **同一个数据源实例里，相同的聚合查询只算一次**（以查询的 JSON 为键，每次返回一份副本）。

实测（2026-09-24，Apple Silicon 笔记本；数据是 `generateRetail()` 默认 showcase 规模的全部子订单，20,360 张，Chromium 里生成一次约 100 ms；见 `rowSource.bench.ts`）：

| 场景（中位数）                                                                          | 改前（Chromium） | Chromium | node 24 |
| --------------------------------------------------------------------------------------- | ---------------- | -------- | ------- |
| 筛选（状态不在待付款与已取消）加 `$group`（按渠道：计数、实付合计、买家去重），不限时间 | 25 ms            | 27 ms    | 43 ms   |
| 按天分桶跨 25 个月（约 750 个桶），全部行                                               | 1039 ms          | 23 ms    | 37 ms   |
| 运营日报的 8 个面板，新数据源第一次作答（含建源排序约 2 ms）                            | 3285 ms¹         | 48 ms    | 73 ms   |
| 同一数据源再答一遍这 8 个面板（记忆）                                                   | —                | <0.1 ms  | <0.1 ms |

¹ 改前不能答 `dense`、`PERCENTILE`、`STDDEV`，这一格是去掉它们之后的 8 个面板（改后同样的查询 40 ms）。8 个面板是：昨日指标卡与前一日对比（各一条合计）、昨日逐时（`HOUR`，dense）、近 30 天逐日（`DAY`，dense）、近 30 天渠道构成、近 30 天省份前 10、全量状态分布、按月趋势（含中位数与标准差）。

只有筛选加分组、不涉及时间的查询，速度与改前相同：瓶颈在 mingo 的 `$match` 与 `$group`，这正是不预聚合要付的代价，仍在方案估的 30～80 ms 以内。改前慢的是逐行做时区换算的日期分桶，一块带趋势的板要三秒多。这里只量数据源作答，不含图表绘制；整块板画完的时间由首页的孪生量（第 4 批）：从页面第一次渲染到 8 张卡、4 张图与明细都画完，本机 Chromium 约 560～870 ms（含冷启动时生成数据集），孪生只守 6 秒的回退护栏。

复现：`pnpm --filter wow-storybook exec vitest bench --run --project=unit`（node）。

## 宿主外壳

View Engine 的每个场景都放在宿主应用里评判：`shared/AppShell.tsx` 画出宿主自己的顶部导航与左侧应用导航（可折成图标），视图引擎只是中间那一块——真实产品里它从来不是一整屏，只对着白底或文档框评判它的观感是对错了地方。外壳是宿主的标记，经 `fve-tokens` 读主题 token（D17-10），明暗两套随之成立。

- **导航**第一项是单独的「首页」（不在任何分组下，宿主打开时就在那一页），其后按目录的四块分组列出其余 View Engine 场景：业务场景（运营日报、销售复盘、履约与售后、订单工作台、售后工作台、分析工作台、运单宽表、订单事件流、会员详情页、订单详情页）、能力（显示收口、参考与算出的系列、长时间轴、框选与追问、板上的搜索、主题与预设）、组件状态（记录工作台、分析工作台、仪表盘、嵌入视图、嵌入仪表盘、筛选编辑器）、真实后端（每个服务一组——补偿、客户、交易订单、商品定价——各有快照控制台与事件流分析台，补偿一组另有「运营概览」）。导览是一页文档，不是宿主的页面，不进这列导航。每项链接到该场景的第一个故事，当前场景标 `aria-current="page"`；图标与工作台里视图种类的图标一致（`ui/kinds.ts`）。链接写成 `./?path=/story/<id>`（`target="_top"`）：锚点在 `iframe.html` 里，它所在的目录就是 Storybook 的根——本地是 `/`，GitHub Pages 上是 `/storybook/`——写成 `/?path=` 会在 Pages 上跳出 Storybook。
- **首页**（`view-engine/Home.stories.tsx`，目录里导览之后的第一项）是宿主应用的落地页：栖木生活的**运营日报**（[docs/scenarios.md](docs/scenarios.md) 4.1、6.3）。宿主画页头「运营日报」、读的是哪一天、数据截至何时，以及宿主自己的「催发货（超时 N 单）」；下面整块是 `EmbeddedDashboard` 嵌入的「运营日报」板（`interaction="interactive"` + `expandable`：只读的报告，嵌入一律不写，D36）——8 张指标卡、逐时 GMV、渠道分布（点一根柱交叉筛选）、「付款超过 48 小时仍未发货」明细（接板上的搜索筛选）、售后退款最多的商品（点一个去销售复盘）与值班手册。离开这块板的路经宿主的路由（`retail/RetailHost.tsx`）：追问与「在工作台中打开」进宿主的订单工作台，行上有「催发货」「订单详情」。数据是零售数据集，时钟钉在 2026-09-22 10:00 Asia/Shanghai；四个状态变体（加载中、一个面板出错、没有权限、没有数据）各一个故事。回归孪生 `Home.test.stories.tsx` 断言黄金值（`retail/goldens.ts`）、A7、只读、搜索、交叉筛选、追到订单、四种状态与手机宽度。原来的补偿概览搬到「真实后端/补偿控制台/运营概览」（`CompensationOverview.stories.tsx`，仍连 `host` 或用 `home.ts` 的夹具）。
- **「服务」一行与环境标记**说的是场景真实连接的东西：真实后端写 `host` 并标「测试环境」，夹具场景写各自的数据源（如「内存 ViewStore · 六条订单」）并标「示例数据」。不放假条目。
- **页面区有确定的高度**，像宿主的内容区一样：工作台填满这个高度（包里只有一种高度布局：永远填满容器，容器没高度时停在 36rem 保底），页脚（合计与分页）贴在底边；比页面区高的内容在页面区里滚动，顶栏不随之滚走。首页、嵌入视图、嵌入仪表盘（一张客户详情页）和筛选编辑器用 `padded`，得到宿主给页面的留白。
- 全部场景 `layout: 'fullscreen'`。场景说明——领域、摘要、数据源、准备、操作、观察——写在文档页（`parameters.docs.description.component`），不再压在画布上方。回归孪生显式写 `parameters: { ...displayMeta.parameters }`：Storybook 会把孪生文件自己的注释写进其 meta 的 `parameters`，只靠展开会被整个替换，全屏布局随之丢失。
- 专门验证「容器没高度」的回归故事自己把工作台放进按内容定高的外层（`HeldAtItsFloor`：停在保底高度、页脚仍贴底），不借外壳的高度。
- 其余包（`http/`、`events/`、`react/`、`storage/`）的故事是接入文档，仍用 `ScenarioFrame`：标题、摘要、夹具与 Setup／Action／Observe 三格。

## 真实后端

按「服务 → 场景」组织：一个真实服务一组，组里每个场景是这个服务上的一种观察方式，场景内部记录视图与分析视图同在一个工作台。**补偿控制台**是 Wow 补偿服务这一组，有两个场景：

`view-engine/DataConsole.stories.tsx` 是**快照控制台**，执行失败的当前状态，用来处理：一个工作台直连 Wow 补偿服务，用真实数据和真实数据量检验体验。记录视图与分析视图在同一个视图列表里切换；每行带操作列（重试、强制重试、标记可恢复性，按这条执行的状态开放），选中多行时同样的命令成批执行，一条结果条说明做成了几条。字段定义按服务的查询 Schema（`GET /execution_failed/snapshot/schema`）人工对齐，见 `compensation.ts` 里 `executionFailedDefinition` 的说明。

`view-engine/EventStreamConsole.stories.tsx` 是**事件流分析台**，同一个服务上 `execution_failed` 的事件流：一条记录是一次命令追加的事件流（执行 ID、版本、命令 ID、事件时间），事件在数组 `body` 里。按事件筛选是对 `body` 的元素匹配，按事件分析展开 `body`、以事件为计数单位。视图列表里有最近的事件、执行历史（按执行 ID 填写的模板，按版本排序）、重试成功、人工干预，以及事件类型分布、每月／每日事件量、每日重试成功、重试最多的执行。事件流只读，没有命令。字段定义按 `GET /execution_failed/event/schema` 人工对齐，舍弃了什么、为什么舍弃，见 `eventStream.ts` 里 `executionFailedEventsDefinition` 的说明。

**客户**是 CRM 服务（`customer` 聚合）这一组，同样两个场景，只读：

`view-engine/CustomerDataConsole.stories.tsx` 是客户的**快照控制台**：客户明细、公海（没有负责人的客户）、最近变更、已禁用，以及按负责人／行业／租户分布、联系人按决策角色、每日新增客户。联系人是数组 `state.contacts`：按联系人筛选是元素匹配，按联系人分析展开数组、以联系人为计数单位。字段定义按 `GET /customer/snapshot/schema` 人工对齐，见 `customer.ts`。

`view-engine/CustomerEventStreamConsole.stories.tsx` 是客户的**事件流分析台**：客户历史（按客户 ID 填写的模板）、归属变更、联系人变更，以及事件类型分布、每月事件量、每日新建客户、变更最多的客户。31 种事件的名字由定义按事件本身写（服务 Schema 给每种事件的标题都相同），见 `customerEvents.ts`。

**交易订单**是交易服务（`trade_order` 聚合）这一组，同样两个场景，只读：

`view-engine/TradeOrderConsole.stories.tsx` 是订单的**快照控制台**：待处理（等评审或改单）、待付款、已取消，以及按状态分布、每日下单、客户排行、商品排行。订单行是数组 `state.items`：按订单行筛选是元素匹配，按商品分析展开数组、以订单行为计数单位；金额按元计。字段定义按 `GET /trade_order/snapshot/schema` 人工对齐，见 `tradeOrder.ts`。

`view-engine/TradeOrderEventConsole.stories.tsx` 是订单的**事件流分析台**：订单历史、评审驳回、改单、取消与关闭，以及事件类型分布、每日事件量、变动最多的订单。29 种事件同样由定义命名，见 `tradeOrderEvents.ts`。

**商品定价**是定价服务（`product_pricing` 聚合）这一组，同样两个场景，只读：

`view-engine/ProductPricingSnapshot.stories.tsx` 是定价的**快照控制台**：生效中、半年内到期、已停用或过期，以及按状态分布、价格区间分布、各品牌价格、货期类型分布。字段定义按 `GET /product_pricing/snapshot/schema` 人工对齐，见 `productPricing.ts`。

`view-engine/ProductPricingEvents.stories.tsx` 是定价的**事件流分析台**：定价历史、状态变更，以及事件类型分布、每月／每日事件量、改动最多的定价，见 `productPricingEvents.ts`。

- 这些场景就是操作员用的产品，与其余 View Engine 场景一样放在宿主外壳里（见上一节）；「服务」一行就是场景连接的 `host`。补偿快照控制台的文档页还写着「命令真实写入」的提醒。
- 服务地址是故事的 `host` 参数，可在 Controls 面板随时切换；初始值取环境变量，未设置时为开发集群服务在本机的端口转发（集群内地址命令行能连，但桌面应用的内置浏览器解析不了 `*.svc.cluster.local`）。同一个服务的两个场景共用一个地址：

  | 服务        | 环境变量                          | 默认                    | 集群内                                              |
  | ----------- | --------------------------------- | ----------------------- | --------------------------------------------------- |
  | 补偿        | `STORYBOOK_WOW_COMPENSATION_HOST` | `http://localhost:8080` | `http://compensation-service.dev.svc.cluster.local` |
  | 客户（CRM） | `STORYBOOK_WOW_CRM_HOST`          | `http://localhost:8085` | `http://crm-service.dev.svc.cluster.local`          |
  | 交易订单    | `STORYBOOK_WOW_TRADING_HOST`      | `http://localhost:8088` | `http://trading-service.dev.svc.cluster.local`      |
  | 商品定价    | `STORYBOOK_WOW_PRICING_HOST`      | `http://localhost:8089` | `http://pricing-service.dev.svc.cluster.local`      |

- **在本机跑起来**：先把开发集群的四个服务转发到上表的端口（集群内地址不带端口，即服务的 80 端口；需要能访问 `dev` 命名空间的 kubeconfig），每条一个终端、一直开着：

  ```bash
  kubectl -n dev port-forward svc/compensation-service 8080:80
  kubectl -n dev port-forward svc/crm-service 8085:80
  kubectl -n dev port-forward svc/trading-service 8088:80
  kubectl -n dev port-forward svc/pricing-service 8089:80
  ```

  再在仓库根运行 `pnpm --filter wow-storybook storybook`（`http://localhost:6006`），打开导航里「真实后端 · …」的场景。只看其中一个服务，就只转发那一个；服务在别处时，启动前设环境变量（如 `STORYBOOK_WOW_CRM_HOST=http://127.0.0.1:9085 pnpm --filter wow-storybook storybook`），或打开后在 Controls 面板改 `host`。场景连不上时画的是查询失败，不是空结果——先看端口转发是否还活着。

- 补偿快照控制台的写操作会真实写回服务，只连接测试环境；客户、交易订单与商品定价只读，不发命令。
- 真实数据每次都不同，这些故事标记为 `!test`，不进入回归测试；文档页用 `docs.autoMount: false` 只列出场景链接，不挂载示例，因此打开目录不会调用服务。
- 变的只是数据；定义、系统视图和读取数据的方式是确定的，View Engine 的规则一变就可能让它们失效。每个场景都有一个回归孪生故事（`*.test.stories.tsx`），指向录制服务：`recordedWowService.ts` 用 `rowSource` 按服务的方式应答一个查询资源的 `paged`／`cursor`／`aggregation`（包括元素展开）。`compensationService.ts` 录了几条快照，并按服务的规则应答三条补偿命令；`eventStreamService.ts` 录了几条事件流。客户、交易订单与商品定价各有自己的录制服务（`customerService.ts`、`tradeOrderService.ts`、`productPricingService.ts`），录了快照与事件流各几条；录制服务按服务的方式计算去重计数（`DISTINCT_COUNT`，由 `rowSource` 应答）。这类失效因此在 CI 里暴露，不必等有人打开目录才发现。

## 本地门禁

合并前在本机跑齐下面每一条，**每条单独看退出码**（`命令 > 日志 2>&1; echo "名字 exit $?"`），全部为 0 才算过——view-engine 的 `pnpm test` 末尾还有一段 `test:type`，只 grep 测试摘要会漏掉它的失败。View Engine 包自己的约定见 [`typescript/wow-view-engine/docs/design/README.md`](../wow-view-engine/docs/design/README.md#本地门禁)。命令都在仓库根运行。

**0. 先构建依赖（新 worktree 必做一次）。** 各包之间按 `dist/` 互相引用，没构建时 vitest 报 `Failed to resolve import "@ahoo-wang/wow-client"`，`typecheck` 找不到类型声明：

```bash
pnpm install
pnpm build:typescript
```

**1. 改动所在的包**（以 `typescript/wow-view-engine` 为例，在包目录里运行）：

```bash
pnpm lint:check   # eslint，--max-warnings 0
pnpm test         # vitest + 覆盖率阈值，然后 test:type（三个 tsc 工程）
pnpm build        # vite build，然后 test:package 检查构建产物
```

覆盖率阈值写在包的 `vitest.config.ts`，不达标时 `pnpm test` 非零退出，即使每个用例都绿。

**2. 故事门禁：**

```bash
pnpm --filter wow-storybook lint
pnpm --filter wow-storybook typecheck   # 对照第 0 步构建出的类型声明
PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright-user pnpm --filter wow-storybook test
```

`test` 在无头 Chromium 里跑每个 `*.test.stories.tsx` 的 `play`（`vitest.config.ts` 的 `storybook` 工程，`@vitest/browser-playwright`），并在 node 里跑 `stories/**/*.test.ts` 的单元测试（`unit` 工程，Wow 的包按源码解析，不需要先构建）。它要的 Chromium 版本跟随 `playwright` 的版本；默认缓存 `~/Library/Caches/ms-playwright` 可能是旧版本，也可能归 root 所有、装不进新版本，此时测试一启动就报找不到浏览器。办法是把 Chromium 装进一个自己拥有的目录，之后每次运行都用同一个 `PLAYWRIGHT_BROWSERS_PATH` 指向它：

```bash
PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright-user pnpm --filter wow-storybook exec playwright install chromium
```

机器同时跑多组测试时给 vitest 限并发，例如 `pnpm --filter wow-storybook test --maxWorkers=3`（view-engine 的 `pnpm test` 同理拆成 `pnpm exec vitest run --coverage --maxWorkers=3` 加 `pnpm test:type`，两个退出码都要看）；某个故事因超时失败，先单独重跑那一个文件再判断是不是真失败。

**3. 格式：** 对每个改动过的文件跑 `prettier --check`，失败时 `--write` 后再查一遍：

```bash
git diff --name-only --diff-filter=d origin/main... | xargs pnpm exec prettier --check --ignore-unknown
```

**4. 静态 Storybook（改了导航、故事 id 或标签时）：**

```bash
pnpm --filter wow-storybook build
```

`build` 包含静态索引检查（`scripts/verify-storybook.mjs`）：故事与 `shared/` 里的每个 `./?path=` 链接（含宿主导航 `AppShell` 各项的 `story`）都在索引里，以及回归标签。导览（`Intro.mdx`）里的每个链接也在索引里，且 `/docs/` 链接落在文档页、`/story/` 链接落在故事上；索引的第一项是导览。

`scripts/verify-storybook-browser.mjs` 在独立的无头 Chrome 里检查：View Engine 首页（`Home.stories.tsx` 的「运营日报」）渲染出仪表盘；宿主导航（`stories/shared/AppShell.tsx`）的每个链接都落在 `index.json` 里的故事上、在顶层窗口打开，每个离线场景都标出自己的链接，点击会让整个 Storybook 换到目标场景；窄屏不横向滚动；暗色模式到达宿主页面；每个文档页都渲染且「独立场景」链接有效，导览这类独立文档页的每个链接都落在索引里对的那一类页上。连真实后端的故事（没有 `test` 标签）只检查存在，不打开。

先运行 `pnpm --filter wow-storybook storybook`，再在 `typescript/storybook` 里运行：

```bash
node scripts/verify-storybook-browser.mjs
```

也可检查刚构建的 `storybook-static`，把服务地址传给脚本。用 `vite preview` 提供静态文件——Python 的 `http.server` 在并发加载模块时会重置连接，预览偶尔起不来：

```bash
pnpm exec vite preview --outDir storybook-static --host 127.0.0.1 --port 6007 --strictPort
```

```bash
node scripts/verify-storybook-browser.mjs http://127.0.0.1:6007
```

移动故事时同步检查首页、导览（`Intro.mdx`）、宿主导航（`AppShell.tsx`）、验证脚本、测试中的地址，以及文档站里指向故事的链接（`documentation/docs/**/guide/typescript/*.md`，文档站的 `storybook-links` 测试按 `index.json` 检查它们）。

## CI

`.github/workflows/typescript-storybook.yml` 在改到故事、view-engine、wow-client 或 wow-react 时运行（范围由 `.github/scripts/ci-scope.mjs` 的 `storybook` 输出决定）：`build` 先构建这几个包，再跑上面第 2 步的 `typecheck`、`lint` 和第 4 步的 `build`；`interactions` 把 `test` 拆成四片在 Chromium 里并行（Playwright 浏览器按 `playwright` 的版本缓存）。`typescript-storybook-gate` 是合并信号。

浏览器工程一次只跑一个文件，一片的时长就是它那些文件时长之和。所以 `--shard` 不按路径哈希分，而由 `scripts/shard-sequencer.mjs` 按 `test-durations.json` 里测得的时长分成几片等长的（没测过的新文件按中位数算）。时长过时只会让几片不那么均匀，不会漏跑或重跑文件。某一片明显变长时，用 CI 的报告刷新：每片把 JSON 报告上传为 `storybook-durations-<n>`，

```bash
tmp=$(mktemp -d)
gh run download <run-id> -p 'storybook-durations-*' -D "$tmp"
node scripts/test-durations.mjs "$tmp"/*/*.json
```

`.github/workflows/typescript-storybook-browsers.yml` 每晚（UTC 18:00）和手动触发时在 Firefox 与 WebKit 里跑同样的交互测试（只跑 `storybook` 工程，每种浏览器两片，同样按测得的时长分片，`STORYBOOK_BROWSERS` 选浏览器）。它不在拉取请求上运行，不是合并信号，发布准入也不读它；失败说明是某个浏览器特有的问题，按 [`typescript/AGENTS.md`](../AGENTS.md#flaky-tests) 的「Flaky Tests」处理，不重试。本地复现：

```bash
PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright-user pnpm --filter wow-storybook exec playwright install firefox webkit
STORYBOOK_BROWSERS=firefox PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright-user pnpm --filter wow-storybook exec vitest run --project=storybook --maxWorkers=2
```

本机的 WebKit 是 macOS 版，CI 上是 Linux 版，字体、ICU 与合成方式都不同；Linux 上才出的问题，可以在 Docker 里起 Playwright 的 Linux 浏览器（`mcr.microsoft.com/playwright:v<版本>-noble` 里跑 `npx playwright run-server`），让 `@vitest/browser-playwright` 用 `connectOptions: { wsEndpoint, exposeNetwork: '<loopback>' }` 连过去复现。

三种浏览器下故事要量的是同一件事，几条写法因此是约定：

- **拖动用浏览器自己的鼠标**（`pointerDrag.ts`）。`@dnd-kit/dom` 开始拖动时捕获这根指针（`setPointerCapture`），浏览器只捕获它认识的指针：`user-event` 的鼠标编号是 1，这是 Chromium 与 WebKit 的鼠标，Firefox 的鼠标是 0、而且要真鼠标动过才存在，所以合成的拖动在 Firefox 第一步就被取消——人拖是好的。测试运行器里，`.storybook/realMouse.ts` 经 Playwright 驱动真鼠标（`vitest.config.ts` 的 `commands`，`vitest.setup.ts` 交给故事），拖完把鼠标移出故事，免得停在上面的鼠标把下一个布局悬停出来；Storybook 自己的面板里没有运行器，退回 `user-event`（只在 Chromium 与 WebKit 里成立）。
- **量字按字本身**（`chartDom.ts` 的 `typeBox`、`flatLine`）：SVG 文字的 `getBoundingClientRect` 是字体的行高，Firefox 取 CJK 回退字体的度量、还把描边（数值标签的白边）算进去，同一个 12px 的刻度在三种浏览器里高 15～20px。两段字隔多远、碰没碰，按 `getBBox` 取的字行缩到一个 em、再经文字自己的变换放到屏幕上量。
- **等画完再数标记**：先 `chartsDrawn`（图表写 `data-drawn`），再数柱、扇区。标记出现前要走完查询、按需加载图表库与第一次绘制，冷启动在慢 runner 上超过 `waitFor` 的 1 秒。
- **量颜色等过渡走完**（`contrast.ts` 的 `colorsSettled`）：控件带 `transition-colors`，换主题、摘掉预设时颜色 150ms 才到；WebKit 的 `getComputedStyle` 会解析整份文档，于是量到半路的颜色。
- **合成滚轮给出 `wheelDelta`**：图表库按旧的 `wheelDelta` 读滚轮，Chromium 合成事件时照抄 `deltaY` 的符号，Firefox 与 WebKit 按真滚轮取反；故事发出往上滚（`deltaY` 为负，触控板双指张开就是这样）并写明 `wheelDeltaY`，三种浏览器读到同一个方向。
- **日期的写法取平台的**：macOS 的 ICU 在「星期二」前加空格，浏览器自带的 ICU 不加；断言日期与星期本身，不断言空白。
