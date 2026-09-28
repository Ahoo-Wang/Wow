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

**键在渲染时翻**（用户 2026-09-28，#3744 审查）：定义里留着键，界面经措辞目录在渲染时译成文字，Provider 换语言只重画。H1 先落的是过渡形态——键是带标记的字符串、注册定义时一次译完（`ViewEngineOptions.text`），换语言仍要换引擎；H2 把翻译挪到渲染时，定义与键不变。

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
<ViewEngineProvider
  engine={engine}
  locale={locale}
  messages={consoleText}
  navigate={navigate}
  bindings={[
    bind(EXECUTION_FAILED, {
      route: view => `/executions?view=${view}`,
      actions: executionActions,
      reading: { render, title },
    }),
    bind(EXECUTION_HISTORY, { route: view => `/events?view=${view}` }),
    bind(OVERVIEW, { route: () => '/' }),
  ]}
>
  <App />
</ViewEngineProvider>
```

- `resources` 替掉 `definitions` 与 `resolveSource`；没有 `source` 的资源（看板）不查数据。
- `route` 替掉宿主按定义分派的路由：引擎按目标资源的 `route` 把 `ViewNavigation` 解析成具体的路径与 state 再交给 `navigate`；网址与没有 `route` 的目标仍原样交给宿主。
- `actions`（第 5 节）与 `reading`（[D60](decisions.md)）替掉 `recordPanel`：失败执行出现在工作台、详情抽屉、看板的记录面板、嵌入视图与追问的结果里，都自动带上。
- 外壳只要 id：`<DataWorkbench definitionId={EXECUTION_FAILED} />`。props 只留「这一处与别处不同」的：嵌入的交互档位、标题开关、这一处独有的操作。
- Provider 可以嵌套，内层覆盖外层，也可以在外壳上显式传 `engine`：一页两个引擎的宿主照样写得出。
- **一个应用一个引擎**：注册在应用启动时做一次；页面之间共享查询缓存、偏好与描述符。
- 查询队列按看板规模自己留位（todo.md「看板打开时查询队列按看板的规模留位」），控制台的 `maxQueuedQueries: 64` 删去。
- 开发期的 `onIssue` 缺省按资源分组打印，每条带改法；宿主接了自己的就用宿主的。

### 4.1 主题接入：先选一条路（用户 2026-09-28 定）

今天的主题机制是对的（每个预设在两种模式下守 4.5:1 与 3:1，`theme-check` 与对比度矩阵核对，样式全在边界内），但宿主要先读懂四种变量前缀、预设、`tokens`、`theme`、品牌与六个边界、桥接、两种边界、密度与涨跌色，README 光主题就约 400 行，没有「先选哪条路」的入口。参考宿主控制台有 shadcn 主题却没用桥接，反把 `fve-tokens` 挂在 `<body>` 上（README 说该挂在用到它的外壳上：边界内有 preflight），B（D66）的断点失效正由此撞出；它还自写约 60 行的明暗切换。

改为两条路，宿主第一步只做一个选择，都在 Provider 上写：

| 路             | 适合                                  | 写法                                             | 引擎做                                                                                                      |
| -------------- | ------------------------------------- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| **引擎跟宿主** | 已有 shadcn 主题（Tailwind v4）的宿主 | **theme="host"**                                 | 等同今天的 `shadcn-bridge.css`：预设层读宿主的同名变量；`input`、`ring`、状态色与图表色仍用本包的（对比度） |
| **宿主跟引擎** | 没有主题、或愿意用引擎主题的宿主      | **preset="porcelain"**，可加 **brand="#1d4ed8"** | 预设与品牌；宿主自己的外壳挂 `fve-tokens` 拿到同一套 shadcn 名（`--background`、`--primary`…）              |

- **明暗归 Provider**：**colorMode**（`system`／`light`／`dark`，可选记住读者的选择）写 `<html>` 的 `.dark` 与 `color-scheme`，跟随系统变化；控制台的明暗切换删去。面上的 `theme` 仍可把某一块钉在一种模式。
- **进阶不挡路**：逐个 `--fve-*` 覆盖、`tokens`、品牌边界、密度、涨跌色与 `theme-check` 照旧，挪到文档站的进阶页；README 的主题一节压成约 30 行，只讲两条路与明暗。
- **暗色的另一种写法**（待评估，H2 内定）：今天暗色值写 `--fve-dark-*`，好让钉在另一种模式的面也画对；另收 shadcn 习惯的写法——宿主在 `.dark` 下重写同一个 `--fve-*`——前提是与「钉模式」不冲突，冲突就只在文档里讲清为什么要 `--fve-dark-*`。
- **控制台走推荐的路**：`fve-tokens` 从 `<body>` 挪到用到它的外壳上，明暗交给 Provider；它是参考宿主，就是示范。
- 与 B（D66）互补：前缀消掉同名工具类互压，这里消掉「不知道怎么接」。

## 5. 声明式操作：宿主声明做什么，引擎负责怎样做

今天的操作是插槽：`row()`、`bulk()`、`global()` 各返回一段 React，交互机制全在宿主。改为声明：

```ts
const executionActions = actions<ExecutionRow>([
  {
    id: 'prepare',
    label: text('retry'),
    primary: true,
    on: ['row', 'bulk', 'detail'],
    available: row => refusalOf('prepare', row, now()) ?? true,
    changesAt: row => capabilitiesChangeAt(row, now()),
    run: row => commands.prepare(row.key),
  },
  {
    id: 'forcePrepare',
    label: text('forceRetry'),
    tone: 'danger',
    on: ['row', 'bulk', 'detail'],
    confirm: { title: text('forceRetryTitle'), body: text('forceRetryBody') },
    run: row => commands.forcePrepare(row.key),
  },
  {
    id: 'markRecoverable',
    label: text('markRecoverable'),
    on: ['row', 'bulk', 'detail'],
    form: {
      recoverable: { label: text('recoverable'), options: RECOVERABILITY },
    },
    run: (row, input) => commands.markRecoverable(row.key, input.recoverable),
  },
]);
```

| 宿主写（业务）                                                                                                  | 引擎做（机制）                                                                        |
| --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 有哪些操作、各调哪条命令（`run` 用生成的命令客户端）                                                            | 放在哪：行内主操作、溢出菜单、多选条、工具栏、详情抽屉                                |
| 何时可用、不可用时怎么说（`available` 返回 `true` 或理由）                                                      | 多选里部分不可用：「5 条里 3 条能重试」，确认框列出被拒的记录与理由，可一键只选能做的 |
| 可用性何时自己翻转（**changesAt**）                                                                             | 到点重算，不必宿主开定时器                                                            |
| 口径、危险程度、要不要确认、要什么输入（`form`）                                                                | 确认框、输入表单（复用条件值编辑器）、键盘、读屏播报                                  |
| 命令何时算完成：`run` 的 Promise 在读模型反映之后才 resolve（控制台今天用 `waitStrategy({ stage: SNAPSHOT })`） | 批量：并发、进度、停止、部分失败的汇总（`useBulkCommand` 收进来），完成后刷新         |

- `run` 只写一条记录；批量由引擎按并发调度。命令有批量版本时再加 **runMany**，不先做。
- **刷新的前提写进契约**：`run` 必须等读模型反映了命令再 resolve，否则紧接着的刷新读到旧状态。skill 与 README 写明 Wow 命令用 `CommandStage.SNAPSHOT`（或宿主投影所需的阶段）。
- 插槽 `row()`、`bulk()`、`global()` 保留作逃生口，排在声明的操作之后，不作主路。
- `form` 今天由宿主声明字段；命令链路的方案定下后，再由命令的 schema 推出缺省（与第 3 节同一原则），届时另议。
- 权限：`available` 就是宿主表达「这个人不能做」的地方；不可见与不可用的区别用 `hidden: row => …`。

## 6. 测试与 skills

- `/testing` 加 `admit(resources, descriptors)`：用提交的快照把全部定义与看板过一遍准入，返回问题列表；宿主一行单测。控制台 `overview.test.ts` 的「each naming a view there is」只删一半：它还拦存储里的视图 id 与钉看板 id，准入判断不了。`admit` 连带运行时，`/testing` 的体积约与根入口相当（上限 111,000 B，只用 **memorySource** 的包摇掉它）。
- 操作的单测：`/testing` 给一个无头的 `actionHarness(actions, rows)`，断言某行可用与否、拒绝理由、确认与表单的形状，不渲染界面。
- **`wow-view-definition` 改写**：从「对着描述符抄路径、别编字段」改为只讲判断——受众、列哪些、口径、默认、系统视图与看板；自检就是 `admit`。
- **新增 `wow-view-host`**：接入一个宿主——`resources`、Provider、`bind`、路由，以及「从命令到操作」：哪些命令上界面、可用规则从聚合状态怎么读、拒绝理由用业务话、破坏性一律确认、批量是否允许、`run` 等到哪个阶段。与定义分开，是因为写定义的人与接宿主的人常常不是同一个，两者的自检也不同。

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

| 批  | 内容                                                                                                                                       | 判据                                                                                                                                                                                       |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| H1  | **defineView**（含 `text(key)` 与 `timeField`，吸收 D 的时间窗口自动绑定）；`/testing` 的 `admit`                                          | 控制台三份定义改用它：准入结果、全部故事与截图不变；三块板删去手写的时间绑定后数字不变；`views/` 的行数写进 PR                                                                             |
| H2  | `resources`、**ViewEngineProvider**、`bind`（`route`、`reading`）、一个应用一个引擎、队列自动留位、`onIssue` 缺省；主题两条路与明暗（4.1） | 控制台里没有按 `definition.id` 写的分支，没有 `maxQueuedQueries`；三个页面共用一个引擎；控制台的明暗切换删去、`fve-tokens` 不在 `<body>` 上，截图与对比度矩阵不变；README 主题一节约 30 行 |
| H3  | 声明式操作、**actionHarness**；两个 skill；README「Integrating a host」                                                                    | 控制台的操作改为声明，插槽不再使用，行为与 e2e 不变；智能体按 skill 从零给零售场景写一份定义与操作，一次通过 `admit`                                                                       |

- 公开面上的破坏性改动（`definitions`、`resolveSource`、`recordPanel` 与插槽为主路）趁首发前一次改到位，不留兼容层；控制台与 Storybook 在同一个 PR 里跟上。
- 体积：H3 把确认框与表单收进 `./ui`，它已贴近上限；按「功能优先」在 PR 里抬上限并写明。
- 与队列：A、C 先做（C 的跨定义核对在 H2 的 `resources` 上直接可用）；D 并入 H1；B 在 H3 之后，它的 codemod 覆盖新代码。
