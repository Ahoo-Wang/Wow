# 宿主接入：事实归机器，选择归宿主

**状态**：方向与六条裁定已定（用户 2026-09-28，[D67](decisions.md#d67-宿主接入事实归机器选择归宿主2026-09-28)）；批次 H1～H3 见第 8 节，排在 A、C 之后、B（[D66](decisions.md#d66-引擎的工具类带前缀-fve与宿主不再同名2026-09-28)）之前，D 并入 H1。
**依据**：补偿控制台是引擎的第一个真实宿主（`compensation/dashboard`，2026-09-27 的看板真实接入）；[capabilities.md](capabilities.md)（N5：定义只收窄）；Skill `wow-view-definition`。

## 1. 问题：宿主的行为是按「外壳」接的，它本来属于「资源」

控制台为了接上引擎写的东西：

| 宿主写的                 | 在哪                                                                                                                          | 本该是                                                                                                               |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| 视图定义，约 3,600 行    | `src/views/`                                                                                                                  | 大半是把描述符再抄一遍（路径、`kind`、枚举值、`sortable`、算子、聚合能力）；属于宿主的只有选哪些、叫什么、默认怎么看 |
| 按定义分派数据源         | `views/engine.ts` 的 `resolveSource: key => key === HISTORY ? … : …`                                                          | 资源上的一项声明                                                                                                     |
| 按定义分派路由           | `views/navigation.ts` 的去向函数：`definitionId === EXECUTION_FAILED ? '/executions' : …`                                     | 资源上的一项声明                                                                                                     |
| 按定义分派行操作         | `features/Overview/boardHost.ts` 的 `recordPanel: panel => panel.runtime?.definition.id === EXECUTION_FAILED ? … : undefined` | 资源上的一项声明                                                                                                     |
| 操作的交互               | `features/Executions/` 的命令控件、可用性判断、操作接线与确认框，约九百行                                                     | 业务规则属宿主；位置、确认、批量、部分拒绝、进度、刷新、播报属引擎                                                   |
| 每页一个引擎、按语言重建 | 三个页面各 `useViewEngine(executionEngineOptions({ locale, … }))`                                                             | 一个应用一个引擎，语言是呈现时的参数                                                                                 |
| 调引擎的参数             | `limits: { maxQueuedQueries: 64 }`                                                                                            | 引擎按看板规模自己留位                                                                                               |

同一份失败执行在工作台、看板面板、嵌入视图里各接一次；换一个宿主，交互机制再写一遍。

## 2. 原则：三方分工

检验一条东西归谁，问：**换一个宿主，答案会不会变？** 不变的是事实，归机器；会变的是选择，归宿主。机器再按「事实什么时候拿得到」分两处。

| 谁                         | 做什么                                                                                           | 依据     | 产物                           |
| -------------------------- | ------------------------------------------------------------------------------------------------ | -------- | ------------------------------ |
| wow-generator（构建时）    | 它今天做的：模型类型、枚举、命令与查询客户端                                                     | OpenAPI  | 库；**不生成定义、不生成操作** |
| 引擎（运行时）             | 从描述符推出字段的缺省（第 3 节）；按当下的描述符收窄（N5）；操作的交互机制（第 5 节）           | 描述符   | 无文件                         |
| 宿主，由智能体按 skills 写 | 受众、选哪些字段、口径、分组、系统视图、看板；哪些命令成为操作、何时可用、拒绝怎么说、要不要确认 | 业务场景 | `defineView(…)`、`bind(…)`     |

- 生成器的产物是宿主代码 import 的库，重新生成不覆盖宿主写的东西，破坏了就是编译错误。
- 查询一侧的事实只有一个来源：描述符。生成器不再从 OpenAPI 另产一份字段目录。
- **后端对齐事实，不对齐选择**（第 7 节）：描述符把数据是什么、能做什么说全，不承载某类读者怎么看。

## 3. **defineView**：事实从描述符来，宿主只能收窄

```ts
export const executionFailed = defineView(executionFailedDescriptor, {
  id: EXECUTION_FAILED,
  source: EXECUTION_FAILED_SOURCE,
  title: text('title'),
  recordNoun: text('recordNoun'),
  timeField: 'state.executeAt',
  fields: {
    'state.status': {
      label: text('status'),
      cell: 'status',
      options: {
        FAILED: { label: text('statusFailed'), tone: 'danger' },
        PREPARED: { label: text('statusPrepared'), tone: 'warning' },
        SUCCEEDED: { label: text('statusSucceeded'), tone: 'success' },
      },
    },
    'state.retryState.retries': text('retries'),
    'state.error.stackTrace': { label: text('stackTrace'), analysis: false },
  },
  groups: [/* … */],
  views: [/* … */],
});
```

合并规则只有一条：**描述符给的是上限，宿主在它之内选、命名、收窄**（与 capabilities.md 第 2 节同一原则）。

描述符里有两类东西，来处不同，**defineView** 分开对待（用户 2026-09-28 定，D67 修订）：

- **事实**：路径、`kind`、枚举值、语义、敏感级别、元素结构、角色——只随领域模型变，从提交的快照取，固化进定义。
- **能力**：算子、排序、聚合、检索、上限——随存储变（同一个模型在 MongoDB 与 Elasticsearch 上不同，如明细里检索只有 ES 有）。**不从快照固化**：宿主没收窄的，定义写「数据源给什么就是什么」，由运行时当下的描述符填（N5）；宿主收窄了的，取宿主写的 ∩ 当下的描述符。没有当下描述符的数据源（9.2 之前的服务端、非 Wow 的源、测试替身）退回快照的能力，行为与今天相同。

所以同一份定义可以原样部署到不同存储，各自得到那种存储的能力。这修订 N5 的「描述有、定义没写的，不自动加」，只修订 **defineView** 留空的能力；手写的完整定义语义不变。

| 项               | 描述符给                       | 宿主能做                                                                       | 越界                                          |
| ---------------- | ------------------------------ | ------------------------------------------------------------------------------ | --------------------------------------------- |
| 字段是否出现     | 全部路径                       | `fields` 里列出的才出现，列出的次序就是次序                                    | 列了不存在的路径：准入错误                    |
| 类型、枚举值     | `kind`、`semantic`、`enum`     | 给值写口径与语气，隐藏某个值                                                   | 写了没有的值：错误                            |
| 算子、排序、聚合 | `filter`、`sort`、`aggregate`  | `operators` 取子集、`sortable: false`、`analysis: false`；不写即随当下的描述符 | 超出快照：`admit` 提示（另一种存储可能给）    |
| 敏感、机密       | `sensitivity`                  | 不写                                                                           | 自动退出分析；机密自动无算子                  |
| 已弃用           | `deprecated`                   | 保留时写理由                                                                   | 警告                                          |
| 元素与事件体     | `scope`、`variants`            | 在 `elements` 里列元素字段                                                     | `elementMatch` 与 `bodyType` 的结构由引擎搭   |
| 记录与分析能力   | 分页、`rowKey`、上限、分析能力 | 选布局与默认；上限只能压低；不写即随当下的描述符                               | 超出快照：`admit` 提示                        |
| 口径             | 领域描述 `description`         | 写受众的口径                                                                   | 没写时退回领域描述，再退回路径，并报一条 info |

- **描述符用提交的快照**（Q2）：**defineView** 在模块加载时同步从快照取事实，页面不等 `describe`，测试可复现；能力由服务端当下的描述符按 N5 在运行时给出。快照随定义一起提交，文件里记 `version`，漂移在评审里看得见。
- **没列出的字段不出现**（Q1）：描述符多了字段，界面不会悄悄多一列；与 N5「描述有、定义没写的，不自动加」一致。
- **元素字段写嵌套**：`fields` 的键是根路径；数组字段写 `{ elements: { … } }`，键相对元素。点号只用于路径，不兼作层级。
- **结果仍是一份 `DataViewDefinition`**：内核、控制器与 `/ui` 不知道 **defineView** 的存在，与 N5「交集的结果仍是一份定义」同理。手写完整定义仍然可以，**defineView** 是推荐的写法，不是唯一的入口。
- **时刻只按日历分组**：描述符对时间戳报的是数的能力（TERMS、HISTOGRAM、SUM…），**defineView** 对时刻只给日历分组（`DATE_HISTOGRAM`／`DATE_PART`）与最早／最晚（`MIN`／`MAX`），日历分组也只给时刻。
- **看板的时间筛选**是它唯一的日期筛选；有两个日期筛选的板不自动接。定义声明 `timeField`，系统视图可覆盖（`null` 即读全量），面板可声明 **ignoresTime**。
- **自动接的时间线只推导、不存**（用户 2026-09-28，#3744 审查）：每次读板按 `timeField` 现算（`auto: true`），保存时剥掉；只存手写的绑定与 **ignoresTime**，所以视图的 `timeField` 改了，已存的板跟着变。手接时间筛选不连带 **ignoresTime** 与读全量的面板；加面板、换视图时时间线按视图重算。此前存下的板，没接日期筛选的视图面板读作 **ignoresTime**，数字不变。
- **字段路径没有编译期检查**。这是不生成代码的代价，由三道补上：准入（C 起连跨定义引用一并核对）、A（坏一块只坏那一块）、`/testing` 的 `admit`（第 6 节），在宿主的 CI 里就拦下。

### 3.1 口径是键，不是某种语言的字符串

今天控制台按语言各建一份定义（`executionFailedDefinition(locale)`），语言一换，页面重建引擎。**defineView** 的口径写 `text(key)`，宿主在 Provider 上给措辞表，定义与引擎因此与语言无关：一个应用一个引擎，换语言只重画。字面字符串仍可写，适合单语的宿主。

**键在叶子上翻**（用户 2026-09-28，#3744 审查定「渲染时翻」，#3774 落成「叶子上翻」）：定义、配置、每个运行时的状态与快照、编辑器拿到与交回的、存储里的，一律是键；只有显示与离开引擎的地方——React 渲染、ECharts 选项、导出、可达性名称与播报、标题——才把键说成文字，统一经 `/ui` 的 **useSay**（React 之外用根入口的 **say**）。说的是最近一层 Provider 的措辞，缺时回落到引擎起始的措辞，再缺就是键本身；最外层带引擎的 Provider 只负责核对缺词。编辑器显示文字、交回键（**keptKey**、**useSaidText**，宿主写自己的编辑器也用它们）。换语言只重画：不重开、不重查、不变「已修改」。H1 的注册时翻译与 H2a 的快照读时翻译都已被取代。

## 4. 资源注册：数据在核心，行为在 React

引擎核心是无头的，所以注册分两处，都按定义的 id 对上：

```ts
// 核心：数据
const engine = new ViewEngine({
  store,
  resources: [
    { definition: executionFailed, source: executionFailedSource() },
    { definition: executionHistory, source: executionHistorySource() },
    { definition: overview },
  ],
});
```

```tsx
// React：行为
<ViewHost
  engine={engine}
  router={useReactRouter()}
  locale={locale}
  messages={consoleText}
  preset="porcelain"
  bindings={[
    bind(EXECUTION_FAILED, {
      route: view => withView('/executions', view),
      actions: executionActions,
      reading: { render, title },
    }),
    bind(EXECUTION_HISTORY, { route: view => withView('/events', view) }),
    bind(OVERVIEW, {
      route: board => (board === HOME ? '/' : withView('/boards', board)),
    }),
  ]}
>
  <App />
</ViewHost>
```

- `resources` 替掉 `definitions` 与 `resolveSource`；没有 `source` 的资源（看板）不查数据。
- `route(instanceId | null, target?)` 替掉宿主按定义分派的路由：`instanceId` 是要打开的视图或看板，`null` 是没人存过的视图（追问、看板自己的分析），落在页面的缺省上、整份交接；`target` 是这次的去处，引擎为导航要链接时（4.3）不给。引擎按目标资源的 `route` 把 `ViewNavigation` 解析成 `ViewDestination`——`{ kind: 'route', path, state, target }`，`state` 是 `ViewRouteState`（`handOver`、`filters`、`tab`）——交给路由端口（4.2）；网址与没有 `route` 的目标原样交出。宿主要自己接每一条去处时写 `navigate(to: ViewDestination)`，它优先于路由端口。同一套解析在 `/testing` 的 `resolveNavigation` 里，宿主的路由单测跑的就是引擎自己的。
- `actions`（声明式操作，第 5 节；H3 落地）、`slots`（插槽 `row()`／`bulk()`／`global()`，逃生口）与 `reading`（[D60](decisions.md)）替掉 `recordPanel`：失败执行出现在工作台、详情抽屉、看板的记录面板、嵌入视图与追问的结果里，都自动带上；外壳自己的 prop 仍优先。
- 外壳只要 id：`<DataWorkbench definitionId={EXECUTION_FAILED} />`。props 只留「这一处与别处不同」的：嵌入的交互档位、标题开关、这一处独有的操作。
- **ViewHost** 可以嵌套，内层的 `bindings` 按 id 覆盖外层，也可以在外壳上显式传 `engine`：一页两个引擎的宿主照样写得出。只有最外层画 `<html>`（明暗、预设、品牌）。
- **一个应用一个引擎**：注册在应用启动时做一次；页面之间共享查询缓存、偏好与描述符。
- 查询队列按看板规模自己留位，控制台的 `maxQueuedQueries: 64` 删去。
- 开发期的 `onIssue` 缺省按资源分组打印，每条带改法；宿主接了自己的就用宿主的。
- **地图不按引擎注册，是页面全局的**：`registerChartMap` 不进 `ViewHost`、不进 `resources`，一页上共用同一份包的宿主（微前端）共用一张地图表，同名后注册的生效，撤销只撤自己那一次（[extension.md「宿主实现的接口怎样长」](extension.md#宿主实现的接口怎样长)第 3 条）。两个宿主要不同的地理数据就起不同的名字。

### 4.1 主题接入：先选一条路（用户 2026-09-28 定）

今天的主题机制是对的（每个预设在两种模式下守 4.5:1 与 3:1，`theme-check` 与对比度矩阵核对，样式全在边界内），但宿主要先读懂四种变量前缀、预设、`tokens`、`theme`、品牌与六个边界、桥接、两种边界、密度与涨跌色，README 光主题就约 400 行，没有「先选哪条路」的入口。参考宿主控制台有 shadcn 主题却没用桥接，反把 `fve-tokens` 挂在 `<body>` 上（README 说该挂在用到它的外壳上：边界内有 preflight），B（D66）的断点失效正由此撞出；它还自写约 60 行的明暗切换。

改为两条路，宿主第一步只做一个选择，都在 **ViewHost** 上写：

| 路             | 适合                                  | 写法                                             | 引擎做                                                                                                      |
| -------------- | ------------------------------------- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| **引擎跟宿主** | 已有 shadcn 主题（Tailwind v4）的宿主 | **theme="host"**                                 | 等同今天的 `shadcn-bridge.css`：预设层读宿主的同名变量；`input`、`ring`、状态色与图表色仍用本包的（对比度） |
| **宿主跟引擎** | 没有主题、或愿意用引擎主题的宿主      | **preset="porcelain"**，可加 **brand="#1d4ed8"** | 预设与品牌；宿主自己的外壳挂 `fve-tokens` 拿到同一套 shadcn 名（`--background`、`--primary`…）              |

- **明暗归 ViewHost**：**colorMode**（`system`／`light`／`dark`，可选记住读者的选择）写 `<html>` 的 `.dark` 与 `color-scheme`，跟随系统变化；控制台的明暗切换删去。面上的 `theme` 仍可把某一块钉在一种模式。
- **进阶不挡路**：逐个 `--fve-*` 覆盖、`tokens`、品牌边界、密度、涨跌色与 `theme-check` 照旧，挪到文档站的进阶页；README 的主题一节压成约 30 行，只讲两条路与明暗。
- **暗色的另一种写法**（H2b 定：不收）：暗色值仍写 `--fve-dark-*`。shadcn 习惯的写法——宿主在 `.dark` 下重写同一个 `--fve-*`——与「钉模式」冲突：变量从 `<html class="dark">` 一路继承下来，钉在浅色的面（`theme="light"`）拿到的就是宿主的暗色值，面分不出哪个值是给哪种模式的。两半分开写，面才能按自己的模式挑。文档站主题页「Light, dark and system」讲清这一条；走「引擎跟宿主」的宿主不受影响：桥接读的是宿主自己按 `.dark` 切换的 shadcn 变量。
- **控制台走推荐的路**：`fve-tokens` 从 `<body>` 挪到用到它的外壳上，明暗交给 ViewHost；它是参考宿主，就是示范。
- 与 B（D66）互补：前缀消掉同名工具类互压，这里消掉「不知道怎么接」。

### 4.2 **ViewHost**：宿主唯一要认的入口（用户 2026-09-28 定）

H2a 的 Provider 解决了「按资源注册」，但宿主周围的胶水还在。控制台在 H2a 分支上为接入写了约 600 行：外壳接线约 110、把去处翻成 react-router 跳转与 history state 约 114、记录详情与地址栏 `?id=` 双向同步约 96、明暗跟随与记忆约 97、主题挂载约 40、资源与数据源约 153——其中一半以上换任何宿主都要重写。

**ViewHost** 由几个端口组成，引擎带常见适配器（先例：Refine 的 router／i18n／data provider）：

| 端口       | 引擎负责                                                                                   | 宿主只写                                       |
| ---------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------- |
| 路由       | 去处 → 路径；`?view=`、`?id=` 与地址双向同步；交接、筛选、标签页进 history state；站外链接 | 每个资源一行路由表；用哪个路由库（一个适配器） |
| 主题       | 宿主主题或预设（4.1）；明暗由引擎管或交宿主；`fve-tokens` 挂在哪                           | 一个选项                                       |
| 语言       | 渲染时译（3.1）                                                                            | `locale` 与措辞表，或宿主 i18n 的适配          |
| 数据与存储 | `resources`、`store`（第 4 节）                                                            | 资源列表                                       |
| 命令       | 声明式操作（第 5 节）                                                                      | 经 `bind` 给                                   |

- **唯一公开入口**：**ViewHost** 取代 H2a 的 **ViewEngineProvider**（降为内部：`src/ui/workbench/ViewEngineProvider.tsx` 仍是上下文本身，不再从 `/ui` 导出），首发前改名，不留别名、不留两种写法。
- **路由端口**是两个成员的 **ViewRouter**：`location`（`pathname`、`search`、`state`）与 `go(path, { state, replace })`。**契约在对象的身份上**：`location` 一变就给一个新的 `ViewRouter` 对象，不变就给同一个——`ViewHost` 经上下文往下传，读地址的一切（打开的视图、打开的记录、看板的筛选与标签页）只在对象换了时重读；原地改的路由永远读不到它动了，每次渲染都新建的则每次渲染都重读。照 `useReactRouter` 那样按 `location` 的几个部分 `useMemo`；`go` 在调用那一刻才读，可以每次都是新函数（`runtime/routes.ts` 的注释写明）。有了它，引擎：把每条去处交给 `go`（`ViewRoute` 带 `state` 进 history；宿主自己的路径——以 `/` 开头、不是 `//`——也走 `go`；别的站点 `window.open` 另开；没有路由的目标不去）；工作台（`DataWorkbench`、`DashboardWorkbench`）在宿主没给 `instanceId`／`onInstanceChange` 时打开地址的 `?view=`，读者换视图时写回（新的一条历史）——**只认自己定义的视图**：一页上两个不受控的工作台共用一个 `?view=`，另一个定义的视图这个工作台搁着不管（保持手上那个，刚打开时就是默认），也不把自己的默认写回去盖掉别人的；声明的视图 id 里带着定义，存下的视图按定义的列表认（列表没答之前，刚打开的先照地址开、由 `useWorkbench` 在跑之前拒掉别的定义的——`OpenOptions.definitionId`、`view.open.other-definition`——答了再换成默认；已经开着的就保持），`useWorkbench` 本身也拒绝宿主给错的别的定义的 id；打开交接来的视图时把 `?view=` 写在交接的那一条上（替换，交接留着，刷新还在）；工作台在宿主没给 `handOver` 时读 history state 的 `handOver`（按内容认同一个，浏览器每写一次 state 都是新拷贝）；看板（工作台与 `EmbeddedDashboard`）在宿主没给那一对 prop 时从 state 读 `filters`、`tab`，读者一改就替换当前这一条——**每块板各记各的**：写在 `state.boards[板]` 下（嵌入按它的 `instanceId`，工作台按打开的那块、停在默认时按定义），一页上几块板各自找回自己的，不再是最后写的那块说了算；顶层的 `filters`、`tab` 是去处交给这一页的（`resolveNavigation`），也是旧条目里的，一块板自己还没写过时读它，从不被覆盖；每个绑定了的资源的记录详情，在 `reading` 没有自己的 `open` 时跟着地址的 `?id=`（替换，不加历史）。参数名固定为 `view` 与 `id`：`route` 是宿主的函数，引擎读不回它拼的路径，只能与宿主约定这两个名字。
- **适配器**：首发只带 react-router，放在单独入口 `/react-router`（`useReactRouter()`，约 400 B），react-router 作可选 peer（`catalog:peers` 的 `^7.0.0 || ^8.0.0`：适配器只用 `useLocation` 与 `useNavigate`，两版一样；7.6.2 与 7.15.0 下这一页的端口测试照过、类型照过），只有这个入口导入它（`scripts/verify-package.mjs` 核对）；其余路由照端口的两个成员写，i18n 就是 `locale` 与 `messages`。
- **明暗**：**colorMode** 缺省 `system`，由引擎管——在第一次绘制前写 `<html>` 的 `.dark` 与 `color-scheme`，跟随系统变化；`light`／`dark` 从钉住开始；**rememberColorMode** 给一个 `localStorage` 键，读者经 **useColorMode**（`{ mode, setMode }`）选的就记在这台机器上，选回宿主的起始模式即忘掉；宿主已在管（如 next-themes）时写 `host`，引擎不碰 `<html>`，面照旧跟 `.dark`。只有最外层的 **ViewHost** 画；它卸下时把 `<html>` 还原。
- **主题**：`preset` 与 `brand` 由最外层写到 `<html>` 的 `data-fve-preset` 与 `--fve-brand`；`theme="host"` 什么也不写（桥接只在 `<html>` 不点名预设时生效）。样式表仍由宿主导入（`styles.css`，加 `themes/<name>.css` 或 `shadcn-bridge.css`）：打包器的事，端口替不了。
- **边界**：不接管宿主的应用——路由库、i18n、主题系统仍是宿主的，端口只做桥。
- **落地（H3）**：`PageCommands.tsx` 删去——命令成了声明（`views/executionActions.ts`），读法成了一个不用钩子的对象（`executionDetail(commands)`，它读引擎经 **useEngine**），两者与路由一起写在 `views/routes.ts` 的 `consoleBindings()` 里；控制台的接线剩 `ConsoleHost.tsx`、`views/routes.ts`、`views/engine.ts` 三个文件，共 145 行代码（H2b 是 193 行）。
- **落地（H2b）**：控制台的接线剩三处——`features/App/ConsoleHost.tsx`（引擎、路由、语言、主题）、`views/routes.ts`（路由表）、`features/App/PageCommands.tsx`（失败执行的读法与命令，H3 前仍是插槽）——加上资源与数据源 `views/engine.ts`；`views/navigation.ts`、`colorMode.ts`、`?id=` 的同步与 `<body>` 上的 `fve-tokens` 都删了，控制台自己的外壳与弹层各自挂 `fve-tokens`。

### 4.3 导航数据，不做整页外壳（用户 2026-09-28 定）

不做整页布局组件：引擎画视图、宿主管页面（D17 的 `fve-tokens` 就是给宿主外壳的）；做成可配的外壳就成了后台框架，也加重首发前的公开面与 `./ui` 的体积。做的是导航**数据**：**useViewNavigation** 从 **ViewHost** 的资源、路由、系统视图与看板推出 `{ id, title, path, current, kind, views }`，宿主用自己的组件画（shadcn `Sidebar`、顶栏都行）；标题按渲染时译，资源一改导航跟着变。Storybook 的外壳与控制台的外壳改用它作示范，文档给 shadcn `Sidebar` 的例子。

**页面的高度是宿主的事，而且必须是确定的高度**（2026-09-30）：工作台永远填满它的容器、页脚贴底（[ui/record.md](ui/record.md)「工作台永远填满它的容器」），所以容器要有一个确定的高（`height`，不是 `min-height`）。外壳写成视口那么高、顶栏不动、内容区拿余下的高度，比屏幕高的页面在自己的容器里滚：

```css
.app {
  display: flex;
  flex-direction: column;
  height: 100svh;
  overflow: hidden;
} /* 顶栏 flex: none；内容区 flex: 1; min-height: 0 */
```

只写 `min-height: 100svh` 时内容区跟着内容长，工作台落到 36rem 的保底（`--fve-workbench-min-height`）或更高，文档会滚——控制台曾在 863px 高的视口下多滚 70px，分页在折线下面，读者要先滚页面、再滚表格。Storybook 的外壳（`.story-app`，`height: 100dvh`）与控制台（`App.tsx`，`h-svh`）是示范；`compensation/dashboard/e2e/layout.spec.ts` 量文档高等于视口、分页整个在屏幕内。

落地（H2b）：**useViewNavigation()** 返回每个绑了 `route` 的资源，按注册的次序：`{ id, kind, title, path, current, views }`，`path` 是 `route(null)`，`views` 是它的系统视图（看板定义的就是系统看板），各带 `{ id, title, path, current }`，`path` 是 `route(instanceId)`；标题经 **useSay** 说成最近一层的措辞，换语言即重画。`current` 读路由端口的地址：路径相同、且那条路径自己写的每个参数地址里都一样——所以按路径分页的宿主（控制台）与按查询参数分页的宿主（Storybook 的 `?path=`）都认得出；没有路由端口时都不是当前。没有 `route` 的资源不是一个去处，不出现。存储里的共享视图与看板不在其中（要异步读存储，交给宿主自己的视图列表）。宿主给地方起自己的名字（控制台的「事件流」「看板」），数据给去处与「在不在这里」：控制台顶栏的四处——概览是 `overview` 的系统板 `home`、看板是 `overview` 的页——与 Storybook 左栏「业务场景」里五个零售工作台都由它推出。

## 5. 声明式操作：宿主声明做什么，引擎负责怎样做

H3 之前的操作是插槽：`row()`、`bulk()`、`global()` 各返回一段 React，交互机制全在宿主。改为声明（控制台的 `views/executionActions.ts`，摘录）：

```ts
const executionActions = (commands: ExecutionCommands) =>
  actions([
    {
      id: 'prepare',
      label: t.prepare,
      primary: true,
      available: (row, { now }) => allowed(row, now, 'canPrepare'),
      changesAt,
      confirm: { title: t.prepareConfirm, body: t.prepareBody, ask: 'bulk' },
      run: row => commands.prepare(String(row.key)),
    },
    {
      id: 'forcePrepare',
      label: t.forcePrepare,
      tone: 'danger',
      available: (row, { now }) => allowed(row, now, 'canForcePrepare'),
      changesAt,
      confirm: { title: t.forcePrepareConfirm, body: t.forcePrepareBody },
      run: row => commands.forcePrepare(String(row.key)),
    },
    {
      id: 'markRecoverable',
      label: t.markRecoverability,
      form: { recoverable: { label: t.markAs, options: RECOVERABILITY } },
      available: (row, { input }) =>
        input?.recoverable === stateOf(row).recoverable
          ? t.alreadyMarked
          : true,
      confirm: ({ recoverable }) => ({
        title: t.markConfirm,
        action: t.markAsValue,
        ...(recoverable === 'UNRECOVERABLE'
          ? { body: t.markUnrecoverableBody, tone: 'danger' }
          : { body: t.markBody }),
      }),
      run: (row, { recoverable }) =>
        commands.markRecoverable(
          String(row.key),
          recoverable as RecoverableType,
        ),
    },
  ]);
```

| 宿主写（业务）                                                                                                  | 引擎做（机制）                                                                        |
| --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 有哪些操作、各调哪条命令（`run` 用生成的命令客户端）                                                            | 放在哪：行内主操作、溢出菜单、多选条、工具栏、详情抽屉                                |
| 何时可用、不可用时怎么说（`available` 返回 `true` 或理由）                                                      | 多选里部分不可用：「5 条里 3 条能重试」，确认框列出被拒的记录与理由，可一键只选能做的 |
| 可用性何时自己翻转（**changesAt**）                                                                             | 到点重算，不必宿主开定时器                                                            |
| 口径、危险程度、要不要确认、要什么输入（`form`）                                                                | 确认框、输入表单（复用条件值编辑器）、键盘、读屏播报                                  |
| 命令何时算完成：`run` 的 Promise 在读模型反映之后才 resolve（控制台今天用 `waitStrategy({ stage: SNAPSHOT })`） | 批量：并发、进度、停止、部分失败的汇总（**useBulkCommand** 收进来，5.1），完成后刷新  |

- `run` 只写一条记录；批量由引擎按并发调度。命令有批量版本时再加 **runMany**，不先做。
- **刷新的前提写进契约**：`run` 必须等读模型反映了命令再 resolve，否则紧接着的刷新读到旧状态。skill 与 README 写明 Wow 命令用 `CommandStage.SNAPSHOT`（或宿主投影所需的阶段）。
- 插槽 `row()`、`bulk()`、`global()` 保留作逃生口，排在声明的操作之后，不作主路。
- `form` 今天由宿主声明字段；命令链路的方案定下后，再由命令的 schema 推出缺省（与第 3 节同一原则），届时另议。
- 权限：`available` 就是宿主表达「这个人不能做」的地方；不可见与不可用的区别用 `hidden: row => …`。

### 5.1 落地（H3，2026-09-28）

- **声明**在根入口：`actions([...])` 检查 id 唯一、有 `run`，冻结后交回 `RecordActions`；每项 `RecordAction` 是 `id`、`label`、`primary`、`tone`（`default`／`danger`）、`on`（`row`／`bulk`／`detail`，缺省三处都有）、`hidden(row, ctx)`、`available(row, ctx)`（`true` 或理由）、`changesAt(row, ctx)`、`confirm`、`form`、`run(row, input)`、`timeout`（毫秒，可缺：过了就不再等这一条，结局记为「结果未知」）。规则都带第二个参数 `{ now, input? }`：时间由引擎给（`/testing` 里由测试给），不让宿主读钟；`input` 在已知时给——选择的某一项、填好的表单——所以「已经是这个值」可以只拒这一项。读法本身（`actionState`、`splitFor`、`nextChange`、`runOne`…）在 `runtime/actions.ts`，界面与 **actionHarness** 读的是同一份。
- **选择**：只有一个带 `options` 的字段的 `form` 是一个选择——菜单里直接列出选项（行菜单里一组、多选条上一个下拉），选中的就是输入，不再弹表单。控制台的「标记可恢复性」因此与 H3 之前一样是菜单里的三项，e2e 不变。其余的 `form` 在确认框里画表单，字段复用条件编辑器的值控件（`FilterValueEditor`：选项、数字、是否、文字），缺省必填。
- **确认**：多选一律先问（多少条、哪些不会发、为什么），这是引擎的规矩；单条只在声明了 `confirm` 时问，`confirm.ask: 'bulk'` 表示「一条直接做，多选才问」（控制台的「准备」）。`confirm` 可以是输入的函数：标成「不可恢复」时换一句后果、换成危险色。确认框外点不关、焦点困在框内；只有**危险且无表单**的问题是 `alertdialog`（读屏当紧急念），带表单的与例行的问题是 `dialog`（第二轮复审 A11Y-9）。标题与正文按 `{count}`／`{value}`／`{record}`（一条时是记录键）填，键另写 `-one` 形式时按语言的复数规则挑（**useSayWith**）。宿主没写 `confirm` 的多选用引擎的一句「对 {count} 条记录执行「{action}」？」；**一条时点名不计数**（UX-8）：引擎的句子是「对 {record} 执行「{action}」？」／表单标题「{action}：{record}」，宿主的句子下面加一行「记录 {record}」。宿主算出问题的 `confirm` 函数抛错时按动作名问（`confirmOf`），不跳过确认、不拖垮整个面；菜单项与按下读同一条 `asksFirst`（带同样的输入），所以「打开对话框」的判断两处一致。表单字段：标签指向控件、必填控件带 `aria-required` 并以「必填」为描述；提交键始终可按，缺项时按下才标 `aria-invalid` 并把焦点送到第一个缺的字段；只有一条都不能做时提交键才停用（`focusableWhenDisabled`，描述指向那句「没有一条现在能…」）。
- **部分可用**：框里说「{count} 条里 {able} 条能{action}」，按理由分组列出被拒的记录（理由、条数、前三个键与「等另外 N 条」），「只选能做的 N 条」一键把选中收窄到能做的；照样确认时，被拒的不发送、**记为「未执行」而不是「失败」**（`BulkOutcome.refused`，第二轮复审 UX-6）并留在选中里；确认键数要发出的条数（「催发货 2 条」）。多选条上的主操作按钮在不是全都能做时写「{action} {able}/{count} 条」，一条都不能做时停用并以最常见的理由为提示与描述——留在选中里的被拒记录不再诱导一次必然被拒的重跑。
- **执行器**：**useBulkCommand** 收进引擎，成为每个记录面（工作台的记录视图、看板的记录面板）自己的一个执行器（`/react` 内部的 `useActionRunner`，并发 4、进度、停止、逐条原因、失败与未执行的留在选中、跑完刷新）；行、多选与详情里的命令都走它，所以一行字说它们全部。动作自己拒绝的（`ActionRefused`）理由按宿主写的原样保留（键还是键），在状态条上才说出。**结果未知**（第二轮复审 R2-05，用户选 A）：发出之后没有回音的——超时（`TimeoutError`／`FetchTimeoutError`／HTTP 504）、中止（`AbortError`）、断网（fetch 的 `TypeError`）、过了动作的 `timeout`、读者第二次按「停止」（「不再等待」）——记在 `BulkOutcome.unknown`，状态条说「N 项结果未知，先刷新核对」，**这些行取消选择**，不留着诱导一次可能重复退款的重跑；所以宿主的命令要幂等（请求 id／幂等键，README 与 wow-view-host skill 写明）。第一次「停止」只是不再开始新的，按钮随即变成「不再等待」：再按一次，还在飞的记为结果未知、这一趟当场落定，挂起的 `run` 不再让面永远「正在停止」。读失败的原因本身出错（`sourceFailure` 抛）时用通用理由，空白的错误消息说「失败，未给出原因」；落定（解除忙、清选择、刷新）在 `finally` 里，任何一步出错都不会把面卡在忙。失败与结果未知的原错误经 `environment.onError` 报给宿主（`kind: 'action'`，`operation` 是动作 id，`recordKey`、`definitionId`、`instanceId`、`runtimeId`；D40），`ActionRefused` 与 `AbortError` 不报。一条记录的结局点名：「催发货 · SO-1002 已完成」「… SO-1002 失败：理由」；单条与详情的命令不说「失败与未执行的仍选中」，停止后没有失败的也不说「0 项失败」——只数发生了的。`bind` 不再有 `bulk`，`/ui` 不再导出 `BulkStatus`，`/react` 不再导出 **useBulkCommand**。
- **插槽**改名 `slots`（`bind` 与 `record` 上），画在声明的操作之后；插槽上下文多了 `run(command)` 与 `busy`，逃生口里仍要发的命令走同一个执行器、报在同一行。
- **位置**：行（与卡片）上 `primary` 的是按钮，其余在「{record} 的操作」菜单里；不可用的停用，理由是按钮的提示（获焦也打开）与无障碍描述，并去重后列在菜单顶端。**停用不用原生 `disabled`**（第二轮复审 A11Y-1／A11Y-15）：按钮 `focusableWhenDisabled`（`aria-disabled`、按下被吞），所以 Enter 跑起的命令不会把焦点交给 `<body>`，灰掉的按钮也在 Tab 序里、理由键盘够得到；命令运行时「⋯」照样能开，菜单里的项停用。问题框关上时焦点回到按下的那个按钮（菜单项则回到「⋯」）；结局落定后、刷新画完之前，焦点若掉了（行被筛掉、选择清空带走了多选条），落到状态条的「知道了」。多选条上的按钮是结果工具栏的 roving 项（一个 Tab 站、方向键穿过，A11Y-11），宿主自己的批量插槽照旧。状态条画在**行的下面**（分页之上），出现时不再把每一行往下推（UX-8）。详情抽屉的头部画同一套（`on` 含 `detail` 的）。多选条上 `primary` 的写「{action} {count} 条」，其余按名字，选择是一个下拉。看板的记录面板照工作台，命令后整块板重读（`boardWideHost`）；静态档不给多选。
- **到点重算**：面上取所有看得见的记录（页上的行与详情里那条）的最早 `changesAt`，只开一个定时器，到点把引擎的钟拨到现在；时钟落后只会让规则「早问」、拒绝并报出翻转时刻，于是立即更正。定时器至少等 1 秒：一个总说「再过一刻」的 `changesAt`（目标随 `now` 走）不会让面不停重算。
- **播报**：命令开始与结局在面的播报区各说一次（`useSurfaceAnnouncer`／工作台的记录播报区），状态条本身是 `role=status`（全失败是 `alert`）。用 props 传措辞的宿主（`DataWorkbench messages`）上也说宿主的话：记录视图的部件在面的 Provider 之上，措辞由 `RecordParts` 传给 `useActionSurface`（A11Y-2）。命令之后的刷新不再单独念「共 N 条记录」盖掉结局：结局先说并留着，刷新期间不说「正在查询」，落地后合成一句「发货 · SO-1003 已完成；共 4 条记录」（`useQueryAnnouncement` 的 `lead`）。
- **没做**：**runMany**（命令有批量版本时再加）、嵌入视图（`EmbeddedView`）上的声明式操作（它今天是只读的，行命令只有宿主自己的 `rowActions`）、从命令 schema 推出 `form`（等命令链路的方案）。

## 6. 测试与 skills

- `/testing` 加 `admit(resources, descriptors)`：用提交的快照把全部定义与看板过一遍准入，返回问题列表；宿主一行单测。控制台 `overview.test.ts` 的「each naming a view there is」只删一半：它还拦存储里的视图 id 与钉看板 id，准入判断不了。`admit` 连带运行时，`/testing` 的体积约与根入口相当（上限 111,000 B，只用 **memorySource** 的包摇掉它）。
- 操作的单测：`/testing` 给一个无头的 `actionHarness(actions, rows, { now })`，断言某行可用与否、拒绝理由、确认与表单的形状，不渲染界面。**落地（H3）**：`at(place)`、`state(id, key, input?)`、`bulk(id, keys?, input?)`（能做的、被拒的、按理由分组）、`asks(id, place, input?)`、`form`、`choice`、`missing`、`changesAt(key?)`、`run(id, key, input?)`（照引擎发送：不接的记录带理由拒绝，`ActionRefused`）；读的是 `runtime/actions.ts`，与界面同一份规则。控制台的 `views/executionActions.test.ts` 用它。
- **`wow-view-definition` 改写**：从「对着描述符抄路径、别编字段」改为只讲判断——受众、列哪些、口径、默认、系统视图与看板；自检就是 `admit`。**落地**（`skills/wow-view-definition`）：`SKILL.md` 只列选择（列哪些字段、键与措辞、为受众收窄、受保护与弃用、`timeField`、记录、事件流、系统视图与看板）；`references/` 三页——`choices.md`（一份 **defineView** 逐项讲怎么选）、`views-and-boards.md`（记录与分析系统视图、看板、漂移后修订）、`admit.md`（准入测试、每类发现回到哪个选择、`admit` 判断不了的复核与报告）；描述符与引擎的参考只链接 README 与本目录，不再抄一份。
- **新增 `wow-view-host`**：接入一个宿主——`resources`、**ViewHost**、`bind`、路由，以及「从命令到操作」：哪些命令上界面、可用规则从聚合状态怎么读、拒绝理由用业务话、破坏性一律确认、批量是否允许、`run` 等到哪个阶段。与定义分开，是因为写定义的人与接宿主的人常常不是同一个，两者的自检也不同。**落地**（`skills/wow-view-host`）：`references/wiring.md`（数据源与 `describe`、一个引擎、**ViewHost**、`bind`、路由与 **useViewNavigation**、读法，主题与 CSP 只给指引）、`actions.md`（逐个命令的取舍表、命令客户端上的示例、**actionHarness** 测试）、`storage.md`（三种存储怎么选，`WowViewStore` 与 fetcher-cosec、不登录的宿主、`permissions`，以及视图存储路径的 CoSec 网关规则：claim 与 share 要 `sub == {ownerId}` 且有写共享视图的角色）；自检是 **actionHarness**、`resolveNavigation` 与 `admit`。
- **验收（2026-10-01，通过）**：一个只拿到两个 skill 的新智能体从零给零售场景写定义与操作——`admit` 首跑中英文皆 `[]`，评分 20/20，类型检查干净。它报告的缺口（行键与命令 id、宿主测试在 Node 里跑、`dateUnits` 的枚举、相对日期与预设、指标卡、`opens` 与标题面板、看板时间与面板自身时间条件的合取、确认语里的 `{count}`／`-one`／`{value}`、**actionHarness** 的返回形状、命令客户端的等待头与路径参数）已补进 skill 的示例；补完后的第二轮验收同样通过（`admit` 首跑皆 `[]`，评分 19/19，类型检查干净），它再挖到的几处（操作文案的检查、`SystemView.timeField: null`、`--root` 与 Node 配置、例行而不可撤销的一步怎样确认、`asks` 的形状、命令客户端的测试、宿主的 tsconfig、看板的默认日期）也写进了示例。
- **两个 skill 的示例都编译**：文档站的 `documentation/test/typescript-samples.mjs` 把它们与包 README 一样对照构建后的包编译，`ci-scope.mjs` 让这两个 skill 的 Markdown 变更跑 docs 作业。

## 7. 描述符要补的事实（交后端）

宿主里手写的每一项事实都是描述符的缺口。经交接协议交给「查询模块架构重构」会话，从领域模型（Kotlin 注解与类型）推出，TS 随后镜像：

| 缺口                               | 今天宿主怎样补                       | 之后（后端已定）                                                                                                                                                                    |
| ---------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 时长与单位                         | 口径里写「最小退避（秒）」           | 语义 **DURATION**（**timeUnit**），数值字段用 **@QueryDuration(unit)** 声明，单位必填；按时长格式化                                                                                 |
| 比率、百分比                       | —                                    | 不加。零售的 `discountShare` 是分摊到行上的优惠金额，属 MONEY；领域模型里没有存储的比率，看板上的比率是指标算出来的                                                                 |
| 引用：字段指向哪个聚合的 id        | 引用候选（`OptionSource`）与跳转手接 | 语义 **REFERENCE**：固定的 **contextName** 与 **aggregateName**（**@QueryReference**），或逐条记录由同级字段给出（`AggregateId` 类型的属性自动推断）；只指向聚合的 id，没有 `field` |
| 时间角色：创建、事件、首次事件时间 | `timeField` 手写                     | 角色 **EVENT_TIME**（Snapshot 的 `eventTime`、EventStream 的 `createTime`）与 **FIRST_EVENT_TIME**（Snapshot 的 `firstEventTime`）；`timeField` 没写时由 **EVENT_TIME** 给缺省      |

- 领域字段不带时间角色：窗口按哪个时间取是选择（总览一块板就按三个时间绑定），`state.executeAt` 这类仍由宿主写。
- 记录自身的 `aggregateId` 不带引用：角色 `AGGREGATE_ID` 与所在端点已说明是哪个聚合。
- 旧客户端遇到新语义不出错：Kotlin 的 **QuerySemanticType** 把不认识的 `type` 读作 **Unknown**；TS 侧在镜像时照此处理。

不补的：选哪些字段、受众口径、语气色、分组、系统视图、看板、操作——换一个宿主会变。

都不挡 H1：先在宿主里写，后端补上后删去宿主的那几处。

## 8. 批次

| 批  | 内容                                                                                                 | 判据                                                                                                                                                                                                                |
| --- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| H1  | **defineView**（含 `text(key)` 与 `timeField`，吸收 D 的时间窗口自动绑定）；`/testing` 的 `admit`    | 控制台三份定义改用它：准入结果、全部故事与截图不变；三块板删去手写的时间绑定后数字不变；`views/` 的行数写进 PR                                                                                                      |
| H2a | `resources`、Provider、`bind`、一个应用一个引擎、键在渲染时译、队列自动留位、`onIssue` 缺省（#3761） | 控制台里没有按 `definition.id` 写的分支，没有 `maxQueuedQueries`；三个页面共用一个引擎；换语言不重建引擎                                                                                                            |
| H2b | **ViewHost**（4.2）：改名、路由端口与 react-router 适配器、主题两条路与明暗（4.1）；导航数据（4.3）  | 控制台接入胶水 ≤150 行（资源、路由表、命令之外没有接线）；控制台不再自写明暗、`fve-tokens` 不在 `<body>` 上；Storybook 与控制台的导航都由 **useViewNavigation** 推出；截图与对比度矩阵不变；README 主题一节约 30 行 |
| H3  | 声明式操作、**actionHarness**；两个 skill；README「Integrating a host」                              | 控制台的操作改为声明，插槽不再使用，行为与 e2e 不变；智能体按 skill 从零给零售场景写一份定义与操作，一次通过 `admit`（H3 已落地，5.1；两个 skill 已落地，第 6 节；验收已过，2026-10-01）                            |

- 公开面上的破坏性改动（`definitions`、`resolveSource`、`recordPanel` 与插槽为主路）趁首发前一次改到位，不留兼容层；控制台与 Storybook 在同一个 PR 里跟上。
- 体积：H3 把确认框与表单收进 `./ui`，它已贴近上限；按「功能优先」在 PR 里抬上限并写明。
- 与队列：A、C 先做（C 的跨定义核对在 H2 的 `resources` 上直接可用）；D 并入 H1；B 在 H3 之后，它的 codemod 覆盖新代码。
