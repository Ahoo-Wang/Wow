# Storybook 维护约定

Storybook 是可运行的接入文档，也承载浏览器交互回归。导航按能力组织，代码按模块就近维护。

## 示例与回归

- `*.stories.tsx`：展示组件、初始参数、说明和可手动操作的场景。允许初始化读取和无副作用的渲染断言。
- `*.test.stories.tsx`：导入展示故事，复用参数和演示实现，安装复杂 `play`。使用 `['!dev', '!autodocs', 'test']`，保留测试执行并隐藏默认导航与文档入口。
- `*.play.ts`：较长交互的具名实现，就近维护。不要求为简单断言单独建文件。
- `shared/`：只有真实复用的场景外壳（View Engine 全部场景的宿主应用外壳 `AppShell`，其余包的文档场景外壳 `ScenarioFrame`）和 Ant Design Provider。模块显式声明装饰器，不通过故事标题选择 Provider。

普通展示不能依赖自动测试来创建初始数据或完成异步请求。打开页面后，筛选、保存、创建和删除均由使用者触发。

## 文档与状态

`.storybook/DocsPage.tsx` 使用原生文档块展示一个主示例、参数和独立场景链接，避免将所有场景同时挂载。复杂包装器的代码面板引用真实接入源码。

修改全局 fetch 或 Viewer 默认注册器的示例使用独立 iframe，并通过 `beforeEach` 返回清理函数。共享夹具的数据可以复用，可变状态不能跨场景共享。未知来源的请求交给原始 fetch；受控失败只作用于示例 API。

View Engine 的故事在 `view-engine/`，按界面分为数据视图、分析视图与仪表盘视图，每个故事只呈现一种状态：有数据、空结果、加载中、查询失败、待修复、面板不可用。状态由 `fixtures.ts` 里的假数据源决定，引擎与存储每次挂载都新建，因此保存、改名与删除是真写入，也不会跨场景残留。

假数据源按引擎实际发出的查询作答：`rowSource.ts` 把 Wow 查询翻译成 MongoDB 查询，交给 `mingo` 做筛选、排序、分页与聚合，所以表格、汇总行和图表就是这些条件选出的结果。按日期分桶（`DATE_HISTOGRAM`）在管道外用 dayjs 按分组的时区算出桶起点（毫秒，与服务的答法相同）；周（起始日由服务决定）与 `dense` 补空桶不作答。翻译不了的算子直接报错，表现为查询失败，而不是给出一个看似合理的错误答案。每个界面的 `*.test.stories.tsx` 断言这些结果。

## 宿主外壳

View Engine 的每个场景都放在宿主应用里评判：`shared/AppShell.tsx` 画出宿主自己的顶部导航与左侧应用导航（可折成图标），视图引擎只是中间那一块——真实产品里它从来不是一整屏，只对着白底或文档框评判它的观感是对错了地方。外壳是宿主的标记，经 `fve-tokens` 读主题 token（D17-10），明暗两套随之成立。

- **导航**第一项是单独的「首页」（不在任何分组下，宿主打开时就在那一页），其后按目录分组列出其余 View Engine 场景：真实后端（快照控制台、事件流分析台）、数据视图（Record 工作台、嵌入视图、筛选编辑器）、分析视图（分析工作台）、仪表盘视图（仪表盘）。每项链接到该场景的第一个故事，当前场景标 `aria-current="page"`；图标与工作台里视图种类的图标一致（`ui/kinds.ts`）。链接写成 `./?path=/story/<id>`（`target="_top"`）：锚点在 `iframe.html` 里，它所在的目录就是 Storybook 的根——本地是 `/`，GitHub Pages 上是 `/storybook/`——写成 `/?path=` 会在 Pages 上跳出 Storybook。
- **首页**（`view-engine/Home.stories.tsx`，目录里 View Engine 下的第一项）是宿主应用的落地页：宿主只画日期、「运营概览」标题与一句说明，下面整块是 `EmbeddedView` 嵌入的仪表盘——补偿服务执行失败的三个计数、本月每日新增、状态分布、最近的活动失败与失败最多的处理器。仪表盘是 `home` 定义在代码里的系统视图，面板引用运营组共享的视图与快照控制台自己的「按状态分布」（`view-engine/home.ts`）。宿主不另画数字卡片：那些数字是同一份数据上的计数，是仪表盘的指标面板。「示例数据」用 `rowSource` 在内存里应答一组按序号生成的执行，时钟与时区钉在 2026-09-22 10:00 Asia/Shanghai，回归孪生 `Home.test.stories.tsx` 断言每个面板的数字与页面不横向滚动；「真实后端」连 `host`，标 `!test`。
- **「服务」一行与环境标记**说的是场景真实连接的东西：真实后端写 `host` 并标「测试环境」，夹具场景写各自的数据源（如「内存 ViewStore · 六条订单」）并标「示例数据」。不放假条目。
- **页面区有确定的高度**，像宿主的内容区一样：工作台填满这个高度（包里只有一种高度布局：永远填满容器，容器没高度时停在 36rem 保底），页脚（合计与分页）贴在底边；比页面区高的内容在页面区里滚动，顶栏不随之滚走。首页、嵌入视图（一张客户详情页）和筛选编辑器用 `padded`，得到宿主给页面的留白。
- 全部场景 `layout: 'fullscreen'`。场景说明——领域、摘要、数据源、准备、操作、观察——写在文档页（`parameters.docs.description.component`），不再压在画布上方。回归孪生显式写 `parameters: { ...displayMeta.parameters }`：Storybook 会把孪生文件自己的注释写进其 meta 的 `parameters`，只靠展开会被整个替换，全屏布局随之丢失。
- 专门验证「容器没高度」的回归故事自己把工作台放进按内容定高的外层（`HeldAtItsFloor`：停在保底高度、页脚仍贴底），不借外壳的高度。
- 其余包（`http/`、`events/`、`react/`、`storage/`）的故事是接入文档，仍用 `ScenarioFrame`：标题、摘要、夹具与 Setup／Action／Observe 三格。

## 真实后端

按「服务 → 场景」组织：一个真实服务一组，组里每个场景是这个服务上的一种观察方式，场景内部记录视图与分析视图同在一个工作台。**补偿控制台**是 Wow 补偿服务这一组，有两个场景：

`view-engine/DataConsole.stories.tsx` 是**快照控制台**，执行失败的当前状态，用来处理：一个工作台直连 Wow 补偿服务，用真实数据和真实数据量检验体验。记录视图与分析视图在同一个视图列表里切换；每行带操作列（重试、强制重试、标记可恢复性，按这条执行的状态开放），选中多行时同样的命令成批执行，一条结果条说明做成了几条。字段定义按服务的查询 Schema（`GET /execution_failed/snapshot/schema`）人工对齐，见 `compensation.ts` 里 `executionFailedDefinition` 的说明。

`view-engine/EventStreamConsole.stories.tsx` 是**事件流分析台**，同一个服务上 `execution_failed` 的事件流：一条记录是一次命令追加的事件流（执行 ID、版本、命令 ID、事件时间），事件在数组 `body` 里。按事件筛选是对 `body` 的元素匹配，按事件分析展开 `body`、以事件为计数单位。视图列表里有最近的事件、执行历史（按执行 ID 填写的模板，按版本排序）、重试成功、人工干预，以及事件类型分布、每月／每日事件量、每日重试成功、重试最多的执行。事件流只读，没有命令。字段定义按 `GET /execution_failed/event/schema` 人工对齐，舍弃了什么、为什么舍弃，见 `eventStream.ts` 里 `executionFailedEventsDefinition` 的说明。

- 这两个场景就是操作员用的产品，与其余 View Engine 场景一样放在宿主外壳里（见上一节）；「服务」一行就是场景连接的 `host`。文档页还写着「命令真实写入」的提醒。
- 服务地址是故事的 `host` 参数，可在 Controls 面板随时切换；初始值取 `STORYBOOK_WOW_COMPENSATION_HOST`，未设置时为 `http://localhost:8080`。两个场景共用这个地址。
- 快照控制台的写操作会真实写回服务，只连接测试环境。
- 真实数据每次都不同，这些故事标记为 `!test`，不进入回归测试；文档页用 `docs.autoMount: false` 只列出场景链接，不挂载示例，因此打开目录不会调用服务。
- 变的只是数据；定义、系统视图和读取数据的方式是确定的，View Engine 的规则一变就可能让它们失效。每个场景都有一个回归孪生故事（`*.test.stories.tsx`），指向录制服务：`recordedWowService.ts` 用 `rowSource` 按服务的方式应答一个查询资源的 `paged`／`cursor`／`aggregation`（包括元素展开）。`compensationService.ts` 录了几条快照，并按服务的规则应答三条补偿命令；`eventStreamService.ts` 录了几条事件流。这类失效因此在 CI 里暴露，不必等有人打开目录才发现。

## 检查命令

```bash
pnpm test:storybook
pnpm build-storybook
pnpm lint:stories
pnpm typecheck:stories
```

`build-storybook` 包含静态索引检查：验证首页地址与回归标签。`typecheck:stories` 按应用看到的方式检查故事：对照各包构建出的类型声明，因此先运行 `pnpm -r --filter './packages/*' build`。

先运行 `pnpm storybook`，再运行以下真实浏览器检查；使用独立的无头浏览器：

```bash
node scripts/verify-storybook-browser.mjs
```

文档浏览器检查也可接收服务地址：`node scripts/verify-storybook-browser.mjs http://127.0.0.1:6006`。

移动故事时同步检查首页、验证脚本和测试中的地址。历史迁移记录见 `docs/superpowers/plans/2026-09-08-storybook-migration.md`。
