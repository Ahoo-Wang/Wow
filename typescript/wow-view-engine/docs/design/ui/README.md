# UI 层：三种视图共用的规则

本页是三种视图都适用的部分，控制器合同见 [../react.md](../react.md)。各自的组件另有一页：

| 页                           | 内容                                       |
| ---------------------------- | ------------------------------------------ |
| [record.md](record.md)       | Record 的结果区组件                        |
| [analysis.md](analysis.md)   | Analysis 的编辑器、表格与图表              |
| [dashboard.md](dashboard.md) | Dashboard 的栅格、面板 chrome 与面板级告警 |

## 措辞与 MessagesProvider

- 措辞在 `ui/`：`model` 只带 `code` 与 `params`，`ui/messages.ts` 给出每个 code 的英文句子，`ViewSurface` 与四个工作台（`RecordWorkbench`、`AnalysisWorkbench`、`DashboardWorkbench`、`EmbeddedView`）的 `messages` 属性按 key 覆盖，这也是本地化的入口。每层 `MessagesProvider` 合并在上一层之上而不是默认值之上，应用在外层设一次，面里面仍然生效；
- 目录按前缀分文件放在 `ui/messages/`（`save`、`header`、`record`、`filter`、`config`、`scope`、`view`、`manage`、`analysis`、`dashboard`、`status`、`definition`），每个文件 `as const satisfies Record<string, string>`，`messages/en.ts` 把它们铺成英文目录——一份平铺的目录看不出哪些键还活着。`ui/messages.ts` 仍是所有组件 import 的那一个模块，它组合这些文件，并导出 `ViewMessages`、`defaultMessages`、三个格式化函数与前缀回退；
- **key 是类型**：`MessageKey = keyof typeof en`，`MessageFormatters.label(key, params?, fallback?)` 只收它，所以删掉一个键或拼错一个键是编译错误而不是屏幕上的一行 code。由运行时值拼出的 key 走收紧后的源类型（`ViewKind`、`ViewAudience`、`RecordLayout`、`DateShape` 都是闭合联合，模板字面量因此可判）。界面上能选的闭合枚举一律整套命名，不留派生拼写：`label.operator.*` 覆盖 `FilterOperator` 全部成员（`operatorKey()` 只是把这个家族拼一次，不再有断言），`label.chart.type.*`、`label.group.type.*`、`label.metric.function.*` 同理——目录只命名一半的集合根本没法翻译，`messages={zhCN}` 时下拉里照样是 `eq`、`between`、`date histogram`。派生拼写只作为宿主能力数据给出未知成员时的兜底，`test/messages.test.tsx` 按枚举逐个核对，缺一个就失败。全包已无 `MessageKey` 断言；
- 宿主的覆盖仍是 `Readonly<Record<string, string>>`：宿主可以放自己的 key，本包从不读它不认识的 key。包里带第二份目录 `zhCN`（`ui/messages/zh-CN.ts`，类型是 `satisfies Record<MessageKey, string>`，少一键多一键都编译不过），与 `en` 一同从 `/ui` 导出；宿主整份交出去，或者铺开再覆盖几句：`messages={{ ...zhCN, 'label.x': '…' }}`。zh-CN 是没有任何组件 import 的叶子模块，只引组件的打包不会把它带进去；
- 工作台自己的提示渲染在它所画的面之外，所以用 `useViewMessages(messages)` 读同一份合并结果。缺失的 key 沿点号回退到最长的已知前缀（`/react` 把命令与 store 结果拼成 `view.open.failed.not_found` 这类 code），再退回 key 本身，因此永远不会渲染空白。`test/messages.test.tsx` 扫描源码里所有 `issue(...)` 的 code，少一条就失败——否则 `record.summary.unsupported` 这样的键会直接出现在界面上；同一份测试断言 `Object.keys(zhCN)` 与 `Object.keys(en)` 相同，且英文句子里的每个 `{param}` 在中文里也在。（见 test/messages.test.tsx「the message catalogue」「the Chinese catalogue」「components write no copy of their own」）

## 两级 severity 与 StatusStrip

- 两级 severity 在界面上分开呈现，而且都是**单行状态条**（`StatusStrip`）而不是整块 Alert：结果区始终留着上一次成功的结果，一条阻塞不了什么的提示不该把它顶下屏幕。状态条有 warning／error／info 三种 tone，error 以 `role="alert"` 播报，其余以 `role="status"`；
- 多于一条时只显示一句概述与「{count} more」，展开才逐条列出。`error` 阻塞：条件级的 error 就地标在 pill 上并禁用应用按钮，所以工作台的 error 条只报**条件以外**的那些，由编辑器控制器交出的 `filter.unmarked` 给定（判断在 `filter/marks.ts` 的 `unmarkedErrors`：code 以 `filter.` 开头、且路径在草稿树上确实解析到一条被渲染的条件——谓词内部的条件也算——才交给编辑器；畸形子节点被条件带跳过、分组本身也不带标记，这些没有 pill 的照样报在状态条里，否则它们会禁用 Apply 却无处可看）。`ErrorStrip` 自己不做这件事，它只画交给它的那些；标题 `label.view.needs-fixing`／`label.dashboard.needs-fixing`（`WorkbenchShell` 按 kind 选）；
- `EmbeddedView` 没有编辑器，没有任何一条被标在别处，因此全部报出，并以它取代结果（查询失败是例外：上一次成功的结果还在时，失败条与结果并排，取代结果的只有「什么都还没回来过」那一种——条里那句 `label.query.stale` 说的就是它身旁的东西）（warning 仍照常显示在旁，两级并存时两级都说）。`warning` 不阻塞，但"报出而不阻塞"要求它被看见：warning 条用主题的 `warning` token（`text-warning`／`border-warning`，与 `destructive` 并列，宿主用 `--fve-warning`／`--fve-dark-warning` 定制），只有一条时那句话本身就是标题，多条时标题是 `label.view.warnings-count`；
- 同 code 同 params 的 warning 只说一句（`dedupeIssues`）——Dashboard 对全局条件自己校验一次、映射到每个面板再校验一次，两片叶子也可能触发同一条规则，句子相同就不重复。查询失败也是一条 error 状态条：标题是失败本身那句话，右端一个 Retry，若上一次结果还在屏幕上，展开处附一句 `label.query.stale` 说明看到的是旧结果——失败从不清空结果，一次断线不该把人正在读的东西拿走。（见 test/statusStrip.test.tsx「StatusStrip」「dedupeIssues」与 test/ui.test.tsx「WarningStrip」「ErrorStrip」）

- **结果自己的 warning 与配置的 warning 并排。** 一条发现说的可能不是"这份配置有问题"，而是"屏幕上这些数字不等于它们看起来的意思"：汇总行退回本页口径（`runtime.summary.page-only`）、分析结果正好填满上限（`analysis.result.at-limit`）。这类发现随结果走（`ProjectedView.issues`，见 [../runtime.md#规则](../runtime.md#规则)），因为 `state.issues` 每次 `edit` 都重算，放进去就会在用户敲下一个键时消失，而它描述的数字还在屏幕上。`RecordWorkbench` 与 `AnalysisWorkbench` 把两者拼起来交给 `WorkbenchShell` 的 `warnings`，`EmbeddedView` 并进它自己那条——嵌入式视图没有编辑器、没有工具栏，那条状态条是页面上唯一能纠正行上那个词的东西。状态条对二者一视同仁，本来也该如此：都是视图必须说出口的 warning。

`FilterPanel` 的条件 pill 见 [FilterPanel 的布局](#filterpanel-的布局)。

## 值按字段显示

- 值按字段显示，规则在 `ui/display.ts` 的 `displayValue`：Wow 把时间存为毫秒时间戳、把枚举存为代码，原样打印就是一列十三位数字和一列常量。声明了 `options` 的字段按选项标签显示（数组逐项，未知的码原样）；
- `cell ?? kind` 为 `datetime` 时经 `Intl.DateTimeFormat` 以 `dateStyle`／`timeStyle: 'medium'` 显示，为 `date` 时只显示日期；
- 不带偏移的字符串（`2026-09-18`、`2026-09-18T09:30:00`，Java 的 `LocalDate`／`LocalDateTime` 即此形式）是挂钟时间，筛选内核按引擎时钟读它，显示时原样保留而不换算时区，否则日历日在西半球会显示成前一天，`09:30` 会随浏览器与引擎的时差移动；
- 日历上不存在的挂钟时间（`2025-02-29`、`24:00`）不交给 `Date` 顺延到下个月或下一天，原样显示；
- DATE_HISTOGRAM 的键按单位显示为它起始的年、季度（`2026 Q3`）、月、日或时刻，年、季度、月按公历命名——Wow 按公历切桶，波斯历或伊斯兰历的月份会指向桶并不覆盖的时段——季度序号按阿拉伯数字计算，不受语言数字体系影响。数字仍按 `numberFormat`，布尔仍走措辞——分析表格与图表类目共用 `valueText`，货币分组在坐标轴上也不是裸数字——宿主给了 `renderCell`／`renderValue` 就由宿主决定。语言取 `ViewSurface` 的 `locale`，缺省为运行环境的语言；
- 工作台的同名属性透传，与 `messages` 是同一个选择，一个管文字，一个管值。时区取 `ViewSurface` 的 `timeZone`，工作台传入引擎的 `environment.timeZone`：相对日期"今天"按它解析，未声明时区的 DATE_HISTOGRAM 按它切桶，界面也按它显示，一行按什么时钟被筛选、被分组，就按什么时钟显示；
- 分组自己声明了时区的，键按那个时区读。表格、卡片与图表的类目（坐标轴、透视系列、饼图、散点、热力图、分组漏斗的阶段、metric 卡片的趋势）共用这一规则：`RecordColumnView` 带上 `options`，`RecordCardField` 带上 `kind`、`cell`、`options` 与 `numberFormat`，`AnalysisColumnView` 见 [kernels.md#compileanalysis-与-projectanalysis](../kernels.md#compileanalysis-与-projectanalysis)，`AnalysisChart` 经 `AnalysisView.schema` 拿到它们，不受表格选列影响。`numberFormat` 若 Intl 构造不出来（`zh_CN` 这类语言标签、缺币种的货币样式），先放弃语言、再放弃格式，数字按无格式显示，而不是让整张表在渲染中抛错。`spec.colors` 的键仍是内核标出的原值，不随显示文本变。（见 test/display.test.ts「displayValue」）

## 控制器输出合同

- 控制器输出只读状态与动作函数，不输出 JSX、类名与供应商类型。受控值合同统一：是否受控由值属性决定，回调只通知，一次逻辑交互最多一次通知。命令一律以状态兑现而不是抛出：`useSaveCommands` 的每个动作都 resolve，结局落在 `state.error` 与 `state.write`，因此点击处理器不需要 try/catch。加载态由"手上的答案属于哪一次请求"推出而不是在 effect 里同步 setState，这也是 React Compiler 规则要求的形状。

- 排序与列的改动立即 `edit` 后 `apply`：表格渲染的列与行来自上一次成功结果，由内核按执行时的配置投影，因此不重跑就看不到改动；筛选则等提交。

## shadcn 与 `.fve-root` 作用域

- `ui/` 用 shadcn + Base UI 实现默认视觉。落地约定：注册表组件原样落在 `ui/components/`，由 `shadcn add --diff` 升级，因此不启用 Tailwind 前缀——前缀会让每个文件都要手改并从此无法跟随上游；
- 隔离改由 `.fve-root` 边界承担，全部 token 与 base 规则都挂在它上面，`ViewSurface` 渲染它，宿主页面不受影响。Tailwind 的 preflight 与工具类按其写法都是全局的——preflight 重置整页的 `*`、`html`、标题、列表、链接与按钮，工具类是宿主可能撞名的裸 class（`.flex`、`.container`、`.collapse`），栅格库的 `.react-grid-*` 亦然——所以 `scripts/scope-utilities.mjs`（`postcss-prefix-selector`）在构建时给每条规则的主体加上 `:where(.fve-root, .fve-root *)`：用 `:where` 是为了不改变特异性，用「根或根内」而不是后代前缀是因为弹层自己带着根类；
- `html` 这种主体永远不在根内的规则就此失效，宿主的排版因此保持。自定义属性也不例外——宿主会读它们：Tailwind 发到 `:root` 的主题变量（`--spacing`、`--radius-md`……）会与宿主自己的 Tailwind 互相覆盖，而 `--radius-md: calc(var(--radius) * .8)` 只有在 `--radius` 所在的根上才有效——所以 `:root`／`:host` 一律改写为 `.fve-root`；
- 只有 `@property` 注册天然全局，它注册的也只是宿主 Tailwind 会同样注册的 `--tw-*` 名字。`verify-package` 对构建产物守住这条：根之外没有任何规则，也不再有 `:root`。Storybook 走同一个插件、且只作用于主题文件，所以 story 看到的就是发布的规则。`cn` 取自同名包（shadcn 2026-09 起的约定），不再自写 `clsx + tailwind-merge`。主题以独立入口 `/styles.css` 交付，应用显式导入。

- 注册表组件是上游源码：覆盖率、Prettier、ESLint 与 Codacy 都排除 `ui/components` 与 `ui/lib`，它们保持与上游逐字一致，否则每次 `shadcn add --diff` 都会变成整文件冲突；本包测的、也负责的是其上的组合。

### 组件清单

组件清单：`FilterPanel`、`RecordTable`、`RecordCards`、`AnalysisEditor`、`AnalysisChart`（recharts 适配，覆盖 bar／line／area／combo／pie／scatter）、`Heatmap`（自绘网格）、`Funnel`、`MetricCard`、`DashboardGrid`（react-grid-layout 适配）、内容面板 `MarkdownPanel`（react-markdown，不启用原始 HTML）、`ImagePanel`（加载失败显示占位）、`LinksPanel`（外链带 `rel="noopener"`）、三个工作台（侧栏列表 + 标题栏 + 编辑带 + 状态条 + 已应用条件条 + 结果 + 分页）、`ViewHeader`、`SaveActions`、`ViewManager`、`EditorBand`、`StatusStrip`、`AppliedBar`、`ResultToolbar`、`RowActions`、`RecordPagination`、`EmbeddedView`。每个默认组件只消费对应控制器，不直接调用 runtime 以外的对象。独立筛选器与值编辑器不需要 Engine。

## 主题、弹层与明暗

- 主题的全部 token 与基础规则都挂在 `.fve-root` 上，而弹层（Select 列表、菜单、Popover、Tooltip、Dialog、Combobox）经 Portal 渲染到 body、在根之外，因此本包自己的组件一律从 `ui/popups.tsx` 引入各 `*Content`——它给弹层加上 `fve-root` 类与所在 `ViewSurface` 的主题（钉住的值，或面从自己的 `color-scheme` 计算值里解析出的跟随结果，因此宿主的 `.dark` 不必在 `<html>` 上），vendored 组件原样不动，`architecture.test.ts` 按导入记录守住这条引入规则——整模块导入、`export *`、转导出、动态导入与 `.ts` 文件都算在内。明暗也只有一套判定：`dark:` 变体与 token 用同一组选择器——`.fve-root[data-theme='dark']` 与 `.dark .fve-root:not([data-theme='light'])` 及其后代——宿主用祖先上的 `.dark` class 让视图跟随，`ViewSurface` 的 `theme` 则用 `data-theme` 把一处钉住，`verify-package` 断言构建产物里这两处集合一致。面不嵌套：每个工作台与 `EmbeddedView` 各渲染一个根，包内没有根套根；
- 宿主若把钉成相反模式的面嵌进另一个面里，`dark:` 工具类会跟随外层根——CSS 没有"最近祖先"选择器——这不受支持，同一模式或跟随宿主的嵌套则正常。每个 token 读 `--fve-<token>`（暗色块读 `--fve-dark-<token>`）并以内置值兜底，宿主在 `:root` 上赋值即可定制，portal 弹层同样继承到；
- （见 test/popups.test.tsx「popups carry the theme out of the root」「a dialog themes its backdrop as well as its surface」）`verify-package` 断言两个 token 块无一例外。根默认涂 `--background`——钉住另一种模式的视图必须自带底色——宿主要让嵌入视图透出自己的底色，就把 `--fve-background` 设为 `transparent`。

## 字段目录与选择器分组

- 列出字段的三个选择器（添加条件、列选择、分析的分组与指标）都经 `fieldGroups(fields, definition.fieldGroups, key)` 分组：未被任何分组列出的字段在前、无标题，其后按目录顺序列出各分组并带标题，组内按该分组自己的 `fields` 顺序；
- 选择器常常只列一个子集（尚未成为条件的字段、能做列的字段），所以没有字段的分组不显示。目录声明在 `DataViewDefinition.fieldGroups`（`{ id, label, fields }[]`），字段定义本身不记录归属：一个组有哪些字段在一处读完，组序与组内序都不被字段顺序绑住（字段顺序同时决定默认列序），列了未声明的字段名或把一个字段列进两个组都在定义准入时报错。Dashboard 的全局字段与元素字段没有目录，不分组。

## 工作台骨架

- 工作台的骨架自上而下是：**标题栏 → 编辑带 → 状态条 → 已应用条件条 → 结果工具栏 → 结果 → 分页**，顺序按"离结果多近"排：结果是视图的目的，它上面的每一样都要为自己的高度负责。`ViewHeader` 一行说清这是哪个视图——种类图标、受众标签、标题，以及"屏幕上的东西存过没有"的标记（`saved === null` 是 `label.header.new-view`，`saved && dirty` 是 `label.header.unsaved`），右端是宿主的全局动作与 `SaveActions`，上一次写入的结局（`WriteOutcome`：冲突、未知、拒绝）落在标题栏下方一行而不是压在结果上。`EditorBand` 是编辑器所在的折叠带：已保存的视图打开时折起——作者已经决定过了，结果才是要看的东西——没存过的展开，折叠状态属于这一次打开，以 `runtime.id` 为 key 重置，不入库也不记忆。（见 test/editorBand.test.tsx「EditorBand」与 test/viewHeader.test.tsx「ViewHeader」）Analysis 与 Dashboard 本轮只接标题栏、状态条与已应用条件条，编辑器形态照旧。

- 这副骨架由 `WorkbenchShell` 画，三个工作台共用一份：侧栏（`ViewList`，`manager` 只在 `manager.can.anything` 时交出去）、`LeaveDialog`，以及主列——打不开时只有一条 `label.view.unopenable` 的 Alert，否则是 `ViewHeader` → `editor` 槽 → `ErrorStrip`(`filter.unmarked`) + `WarningStrip` + `strips` 槽（只有一种视图才有的，如 Record 的查询失败条） → `AppliedBar` → `result` 槽。props 是 `{ workbench, kind, title, theme?, messages?, locale?, timeZone, actions?, editor, strips?, result, sidebarOpen?, hasResult?, warnings?, className? }`——`hasResult` 与 `warnings` 留给自己没有结果、且告警由面板分担的 Dashboard 覆盖，其余两种读开着的那个视图本身。它不持有任何状态，要画什么全部读自一个 `WorkbenchController`（[react.md#useworkbench](../react.md#useworkbench)）；
- `ViewHeader` 的视图名是 `h2`（`headingLevel` 可由宿主定级），`main` 以它的 id `aria-labelledby`——只在标题真的在屏幕上时才指，指向不存在的 id 是一个坏标签而不是一个缺标签。（见 test/accessibility.test.tsx「the open view names the region it is drawn in」）
- 留给扩展的缝还在：`ViewHeader.leading`（折叠侧栏后留在标题栏最左的那一块）、`data-slot="view-sidebar"`、以及 `sidebarOpen` 这一个布尔——折叠是一处的改动，不是它旁边每一块的布局改动。

## 三态各有一处凭据

- 三种状态各有一处凭据，互不重复：**草稿未应用**是条件 pill 与应用按钮上的那个点（`data-pending`，基准是 `state.applied`），折起来时汇总成折叠行上的 `label.editor.pending` 计数；
- **已应用**是结果上方的 `AppliedBar`，它读 `state.result.own.filter` 而不是 `applied`——应用会启动一次查询，在查询答复之前 `applied` 已经走在前面，跟着它的条会描述还没到屏幕上的行；
- Dashboard 没有自己的结果，面板各跑各的查询，所以那里读 `state.applied.filter`，而「有没有结果可描述」由工作台看面板来答（任一面板已有结果或已经开始查询）；
- **未保存**是标题旁的标记，Save 按钮只在 `dirty && can.save && !pending` 时可按。AppliedBar 保留树的逻辑：根下每个直接子节点一个 badge，分组子节点合成一个，内部条件用分组自己的操作符词连接、再嵌套的分组加括号；
- 每个 badge 带一个 ✕，把对应条件的值设回未填写（分组则组内每条）并重新应用，字段行留在编辑器里，这是 `clearValue(path)` + `submit()`；
- 没有条件而已有结果时显示 `label.applied.all`，还没有结果时整条不渲染。宿主注入的作用域条件（`scoped`）排在可编辑 badge 之后，以 `variant="outline"` 加 `data-scoped` 单独成组，不带 ✕，并由 `label.applied.scoped` 说明它由页面设定——它不在 draft 里，也没有一条编辑器的路径指向它，给一个删不掉的 ✕ 等于许诺一次做不到的放宽；
- 嵌入式视图（`EmbeddedView`）整条 bar 都是只读的（`readOnly`），连自有条件的 ✕ 也不渲染。（见 test/appliedBar.test.tsx「AppliedBar」）

## 保存与视图管理

- 保存与视图管理分两处。`SaveActions` 是一个拆分按钮组：主按钮说此刻该做的那一件事（能存就是 Save，否则是 Save as），菜单里放其余的（Save as、Revert）；
- `pending` 时显示 `label.save.saving`，落地后 2.5s 内显示 `label.save.saved` 并向读屏器播报一次；
- 写入在途、或结局仍为 `unknown`／`conflict` 未结清时，Revert 与菜单一并禁用——在保存自己的编辑时撤销，会把被保存掉的那份配置变成新基线之上的脏草稿；
- 结局未结清时撤销，紧接着落地的 Retry 或「保留我的」会把它一并撤回。`hasErrors` 只在主按钮真的是就地 Save 时禁用它：不能写就地时主按钮做的是 Save as，副本去的是另一个受众，那里的准入由对话框自己判（共享仪表盘引用个人视图这类，正是在当前受众被拒而在个人受众成立），拿当前受众的判决锁住它，等于把只读视图的唯一出口一并锁死。改名、删除、排序、设默认都不在这里——它们改的是列表而不是眼前这个视图——而在侧栏标题旁的管理器（`ViewManager` + `useViewManager`）里，每个按钮按许可**存在或不存在**而不是置灰，系统视图没有改名与删除；
- 未打开实例的写入结局显示在它自己那一行。删除确认把后果拼出来：基础句，shared 再加一句，目标正是当前打开且 dirty 时再加一句；
- 删除遇到冲突时「保留我的」不直接覆盖，而是用 `write.remote` 刷新后的摘要再确认一次（见 [management.md#冲突与未知结果](../management.md#冲突与未知结果)）——第一次确认说的是列表里的那个视图，冲突报回来的已经不是它。上移／下移只在行所在的受众组内移动，到组的首尾即禁用（`canMove`）。`rejected` 的那一行也带一个 `label.rejected.dismiss`，所有恢复按钮在 `manager.pending` 非空时禁用（见 [management.md#冲突与未知结果](../management.md#冲突与未知结果)）。管理入口本身按 `manager.can.anything` 决定给不给：顺序、默认与任一行的改名／删除全都不可用时，工作台根本不把 manager 交给 `ViewList`。（见 test/saveActions.test.tsx「SaveActions, the split button group」「WriteOutcome」与 test/viewManagerUi.test.tsx「ViewManager rows」「the manage button on the view list」）

落到文件上：「结局→一句话加一排按钮」只有一处，`ui/OutcomeActions.tsx`，打开的视图（`ui/WriteOutcome.tsx`）与管理器的行（`ui/ViewManagerRow.tsx`）共用它，两边的差别只是 `surface`——行在对话框里是一行小字小按钮，视图是标题栏下的一条框。各自留下的是只有自己有的那部分：`WriteOutcome` 的复制出口与落地通知，加上 `ui/ConflictConfirm.tsx` 的双栏确认；管理器的删除后果对话框是 `ui/DeleteDialog.tsx`，第一次确认与冲突后的二次确认是同一个。

## 离开保护

从侧栏切到另一个视图会释放当前 runtime，而未保存的草稿只活在 runtime 里，所以那是一次删除工作：`useLeaveGuard`（`/react`，见 [react.md#useworkbench](../react.md#useworkbench)）在 `dirty` 或写入结局为 `unknown` 时先问一句，没有东西可失去时一句也不问——每次切换都拦的守卫，人会学会不读就点掉。该问的时候问什么是无状态的，`/ui` 这边只剩 `LeaveDialog({ leave, messages? })` 照着 `leave.asking` 画，两个按钮分别接 `confirm` 与 `cancel`；它渲染在承载文案的 `ViewSurface` 之外，所以要单独把 `messages` 递给它。（见 test/workbench.test.tsx「useLeaveGuard」）

## 动作槽位

- 三层业务动作走 render 槽位（`RecordActionSlots`）：`global` 在标题栏，`bulk` 在有选择时的结果工具栏，`row` 在表格最后一列（sticky，滚不走）与卡片页脚，统一裹在 `RowActions` 里。动作是代码——它开表单、发命令、跳页面——所以由宿主交出来，不按字符串键注册，也不进配置：存下来的是"看法"，能对记录做什么属于挂载工作台的那个应用。（见 test/rowActions.test.tsx「RowActions」）

## FilterPanel 的布局

- `FilterPanel` 的布局：分组是带边框的块，头部是操作符切换（All of／Any of／None of）与删除，主体是一条条件带：等宽栅格，能放几列放几列，pill 在格子里对齐，字段名、操作符、值上下对齐；
- 持双输入的条件（区间、日期）在条件带放得下两列时占两格；
- 条件是内联的紧凑 pill（字段 · 操作符 · 值编辑器 · 删除），不独占一行，未填写时虚线边框，校验有 error 时标为 invalid（`data-invalid`，destructive 色），只有 warning 时标为 `data-warning`（主题的 `warning` 色）——条件照常执行，颜色只说"值得看一眼"；
- 持有谓词的 `ELEMENT_MATCH` 条件和分组一样渲染为块，头部是字段与操作符，主体是它持有的分组。简单模式只显示根分组的条件带，高级模式显示根分组的块。面板顶部只留模式切换，唯一的出口在底部一行：左边是添加字段，右边是 Clear 与 Apply。Apply 是同屏唯一的 primary 按钮——提交是显式的，面板里的任何输入都不会自己重跑查询；
- 落在条件上的 error 数（`filter.blocked`）大于零时它禁用，并在左侧以 `label.filter.blocked` 说还有几条要改；
- 草稿与已应用不一致时按钮上也带那个点。`submit={false}` 时底行整行不渲染，留给从别处提交的编辑器（比如 Dashboard 的全局条件带）。已应用条件的摘要不在面板里，在结果上方的 `AppliedBar`：根是 OR／NOR 时整体折成一个 badge 并说明；
- 宿主注入的作用域条件在那里另起一组只读呈现，不带删除。（见 test/ui.test.tsx「FilterPanel tree editing」）

落到文件上：`ui/FilterPanel.tsx` 只留根——模式切换、焦点边界、超预算提示与底行；块与 pill 在 `ui/filter/` 下分为 `GroupBlock.tsx`（含条件带）、`ConditionPill.tsx`（含元素匹配块与 `PendingDot`）、`AddEntry.tsx` 与 `FilterActions.tsx`。值编辑器同理：`ui/FilterValueEditor.tsx` 只剩按 `EditorDescriptor.input` 分派的 switch——**这是全包唯一知道这个封闭联合的地方**，也是 [extension.md](../extension.md) 所说的按 kind 注册渲染器将来要切开的缝——每种输入各自一个文件在 `ui/filter/inputs/`（`text`／`number`／`select`／`remote`／`date`／`daterange`／`relative`，公共部分在 `shared.tsx`）。
