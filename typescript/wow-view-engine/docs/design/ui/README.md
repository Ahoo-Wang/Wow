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
- **key 是类型**：`MessageKey = keyof typeof en`，`MessageFormatters.label(key, params?, fallback?)` 只收它，所以删掉一个键或拼错一个键是编译错误而不是屏幕上的一行 code。由运行时值拼出的 key 走收紧后的源类型（`ViewKind`、`ViewAudience`、`RecordLayout`、`DateShape` 都是闭合联合，模板字面量因此可判）。界面上能选的闭合枚举一律整套命名，不留派生拼写：`label.operator.*` 覆盖 `FilterOperator` 全部成员（`operatorKey()` 只是把这个家族拼一次，不再有断言），`label.chart.type.*`、`label.group.type.*`、`label.metric.function.*` 同理——目录只命名一半的集合根本没法翻译，`messages={zhCN}` 时下拉里照样是 `eq`、`between`、`date histogram`。`label.relative.unit.*` 与 `label.relative.preset.*` 是同一条规则的后两套——相对日期控件以前把标识符本身摆在屏幕上（`hour`、`thisWeek`），于是换了目录它照旧是英文；单位沿用控件原本的拼写，时段沿用摘要条原本读出的那个带空格的写法。派生拼写只作为宿主能力数据给出未知成员时的兜底，`test/messages.test.tsx` 按枚举与这两套集合逐个核对，缺一个就失败。全包已无 `MessageKey` 断言；
- **屏幕上的名字用字段的标题，不用字段的 id**。`label.relative.unit.*` 那条规则对一行条件同样成立：`ConditionPill` 的四个无障碍名（`condition-of`、`operator-of`、`value-of`、`remove-of`）都拿 `field.label`（取不到才退回 `leaf.field`），所以「状态 条件／状态 操作符／状态 值」是一个词，而不是两个词加一个标识符。（见 test/filterPanel.test.tsx「names every control on a row after the field, not its id」）
- **vendored 组件写死的文案在调用处合缝**。`ui/components/**` 不手改，而 `spinner.tsx` 把 `aria-label="Loading"` 写死在里面，宿主换目录也换不掉它；所以目录里有 `label.status.loading`，三处渲染 `<Spinner>` 的地方（`RefreshControl`、`SaveActions`、`SaveAsDialog`）把它当 `aria-label` 传进去——`DashboardWorkbench` 只把 `busy` 交给 `RefreshControl`，自己不画 spinner。目录测试只证明键在两本目录里都有，调用处那一条断言才证明它真的传了。（见 test/refreshControl.test.tsx 与 test/saveActions.test.tsx「announces … in the host catalogue, not in English」）
- 宿主的覆盖仍是 `Readonly<Record<string, string>>`：宿主可以放自己的 key，本包从不读它不认识的 key。包里带第二份目录 `zhCN`（`ui/messages/zh-CN.ts`，类型是 `satisfies Record<MessageKey, string>`，少一键多一键都编译不过），与 `en` 一同从 `/ui` 导出；宿主整份交出去，或者铺开再覆盖几句：`messages={{ ...zhCN, 'label.x': '…' }}`。zh-CN 是没有任何组件 import 的叶子模块，只引组件的打包不会把它带进去；
- 工作台自己的提示渲染在它所画的面之外，所以用 `useViewMessages(messages)` 读同一份合并结果。缺失的 key 沿点号回退到最长的已知前缀（`/react` 把命令与 store 结果拼成 `view.open.failed.not_found` 这类 code），再退回 key 本身，因此永远不会渲染空白。`test/messages.test.tsx` 扫描源码里所有 `issue(...)` 的 code，少一条就失败——否则 `record.summary.unsupported` 这样的键会直接出现在界面上；同一份测试断言 `Object.keys(zhCN)` 与 `Object.keys(en)` 相同，且英文句子里的每个 `{param}` 在中文里也在。（见 test/messages.test.tsx「the message catalogue」「the Chinese catalogue」「components write no copy of their own」）

## 两级 severity 与 StatusStrip

- 两级 severity 在界面上分开呈现，而且都是**单行状态条**（`StatusStrip`）而不是整块 Alert：结果区始终留着上一次成功的结果，一条阻塞不了什么的提示不该把它顶下屏幕。状态条有 warning／error／info 三种 tone，error 以 `role="alert"` 播报，其余以 `role="status"`；
- 多于一条时只显示一句概述与「{count} more」（`label.status.more`），展开才逐条列出。**那颗按钮两态两句话**：折着时说还有几条（`label.status.more`），展开之后那几条已经在屏幕上，再说「还有 N 条」等于指着看得见的东西说没给你看，所以换成收起（`label.status.less`）——读屏靠 `aria-expanded` 不会被骗，这一条修的是眼睛看到的那句；`error` 阻塞：条件级的 error 就地标在 pill 上并禁用应用按钮，所以工作台的 error 条只报**条件以外**的那些，由编辑器控制器交出的 `filter.unmarked` 给定（判断在 `filter/marks.ts` 的 `unmarkedErrors`：code 以 `filter.` 开头、且路径在草稿树上确实解析到一条被渲染的条件——谓词内部的条件也算——才交给编辑器；畸形子节点被条件带跳过、分组本身也不带标记，这些没有 pill 的照样报在状态条里，否则它们会禁用 Apply 却无处可看）。`ErrorStrip` 自己不做这件事，它只画交给它的那些；标题 `label.view.needs-fixing`／`label.dashboard.needs-fixing`（`WorkbenchShell` 按 kind 选）；
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

组件清单：`FilterPanel`、`RecordTable`、`RecordCards`、`AnalysisEditor`、`AnalysisChart`（recharts 适配，覆盖 bar／line／area／combo／pie／scatter）、`Heatmap`（自绘网格）、`Funnel`、`MetricCard`、`DashboardGrid`（react-grid-layout 适配）、内容面板 `MarkdownPanel`（react-markdown，不启用原始 HTML）、`ImagePanel`（加载失败显示占位）、`LinksPanel`（外链带 `rel="noopener"`）、三个工作台（侧栏列表 + 标题栏 + 编辑带 + 状态条 + 已应用条件条 + 结果 + 分页）、`ViewHeader`、`SaveActions`、`ViewManager`、`EditorBand`、`ViewExpandToggle`、`StatusStrip`、`AppliedBar`、`ResultToolbar`、`RowActions`、`RecordPagination`、`EmbeddedView`。每个默认组件只消费对应控制器，不直接调用 runtime 以外的对象。独立筛选器与值编辑器不需要 Engine。

## 主题、弹层与明暗

- 主题的全部 token 与基础规则都挂在 `.fve-root` 上，而弹层（Select 列表、菜单、Popover、Tooltip、Dialog、Combobox）经 Portal 渲染到 body、在根之外，因此本包自己的组件一律从 `ui/popups.tsx` 引入各 `*Content`——它给弹层加上 `fve-root` 类与所在 `ViewSurface` 的主题（钉住的值，或面从自己的 `color-scheme` 计算值里解析出的跟随结果，因此宿主的 `.dark` 不必在 `<html>` 上），并把层级 `z-index: var(--fve-popup-z-index, 50)` 以 style 写在 **positioner** 上（D11：positioner 被写上 `transform` 自成 stacking context，内容里的 `z-50` 出不去；而它不带 `fve-root`，registry 给它的 `isolate z-50` 在钉过作用域的样式表里谁也没匹配上）。为了够得着 positioner，这六个弹层都是照 registry 的结构**自己组装** Portal／positioner／popup，而不是包一层 vendored 组件；vendored 的 `ui/components/**` 原样不动、照旧用 `shadcn add --diff` 更新，抄来的那份由 `test/popups.test.tsx` 两边各渲染一次、逐字比对类名与内部结构守住，层级则由 `RecordWorkbench.test.stories.tsx` 的 `PopupsOverRaisedHostLayer` 在真浏览器里对着宿主抬起的一层逐种弹层验。`architecture.test.ts` 按导入记录守住引入规则——整模块导入、`export *`、转导出、动态导入与 `.ts` 文件都算在内。明暗也只有一套判定：`dark:` 变体与 token 用同一组选择器——`.fve-root[data-theme='dark']` 与 `.dark .fve-root:not([data-theme='light'])` 及其后代——宿主用祖先上的 `.dark` class 让视图跟随，`ViewSurface` 的 `theme` 则用 `data-theme` 把一处钉住，`verify-package` 断言构建产物里这两处集合一致。面不嵌套：每个工作台与 `EmbeddedView` 各渲染一个根，包内没有根套根；
- 宿主若把钉成相反模式的面嵌进另一个面里，`dark:` 工具类会跟随外层根——CSS 没有"最近祖先"选择器——这不受支持，同一模式或跟随宿主的嵌套则正常。每个 token 读 `--fve-<token>`（暗色块读 `--fve-dark-<token>`）并以内置值兜底，宿主在 `:root` 上赋值即可定制，portal 弹层同样继承到；`--fve-popup-z-index` 是同一条路上的第二个旋钮：宿主自己的 chrome 堆得比 50 还高时，在 `:root` 上改这一个值，所有弹层一起抬；
- （见 test/popups.test.tsx「popups carry the theme out of the root」「a dialog themes its backdrop as well as its surface」）`verify-package` 断言两个 token 块无一例外。根默认涂 `--background`——钉住另一种模式的视图必须自带底色——宿主要让嵌入视图透出自己的底色，就把 `--fve-background` 设为 `transparent`；
- 控件的那圈边（未勾选的 `Checkbox`／`RadioGroupItem`、`Input`、`Select` 触发器）画的是 `--input` 而不是 `--border`——分隔线可以淡，而"边就是控件"的那一类按 WCAG 1.4.11 要 3:1，两个主题都钉在 ≥3:1 上，由 `RecordWorkbench.test.stories.tsx` 的 `ControlBordersInLightTheme`／`ControlBordersInDarkTheme` 在真浏览器里量层叠之后的真颜色（连控件自己那层 `bg-input/30` 一起合成）守住；宿主覆盖 `--fve-input`／`--fve-dark-input` 时同样欠这一条。

## 字段目录与选择器分组

- 列出字段的三个选择器（添加条件、列选择、分析的分组与指标）都经 `fieldGroups(fields, definition.fieldGroups, key)` 分组：未被任何分组列出的字段在前、无标题，其后按目录顺序列出各分组并带标题，组内按该分组自己的 `fields` 顺序；
- 选择器常常只列一个子集（尚未成为条件的字段、能做列的字段），所以没有字段的分组不显示。目录声明在 `DataViewDefinition.fieldGroups`（`{ id, label, fields }[]`），字段定义本身不记录归属：一个组有哪些字段在一处读完，组序与组内序都不被字段顺序绑住（字段顺序同时决定默认列序），列了未声明的字段名或把一个字段列进两个组都在定义准入时报错。Dashboard 的全局字段与元素字段没有目录，不分组。

## 版式：三块、一套间距、一种选项控件

三条规则，后来的改动按它们判。落到代码上是 `ui/layout.ts` 一处（`SPACE`、`SURFACE`、`SEGMENTED`），不是各处手写的 class。

- **主列是三块，不是一摞行**。视图头（identity + 保存 + 视图级控件）是 **banner**：`border-b` 划一条线，不做卡片——给"说明这是哪一页"的那一行套个卡片，等于给整页套卡片。条件区与结果区各是一个 **surface**：`rounded-lg border border-border bg-card`、`p-3`。块只在有内容时才存在：空卡片是一个"这儿有东西"的空头承诺。这条对结果块同样成立——分析视图跑之前既没有结果、也没有已应用条件条、也没有状态条，三样都不画的时候整块就不渲染，而不是留一个空边框。结果块由工作台组合，外壳只给框，`resultSurface={false}` 可以不要那层框——**Dashboard 就不要**，它的结果本来就是一格格面板卡片，再套一层是框里套框。
- **间距是一把有级差的尺子**，不是到处 `gap-2`：块与块 16px（`SPACE.BLOCKS`）、块内行与行 12px（`SPACE.ROWS`）、行内控件组之间 8px（`SPACE.GROUPS`）、组内 4px（`SPACE.WITHIN`）。分组要看得见，而两块之间的距离若等于两个按钮之间的距离，就没有什么是成组的，眼睛无处落脚。**主列的块间距归外壳**：`WorkbenchShell` 已经把 `SPACE.BLOCKS` 发给 `<main>`，工作台不再往 `className` 里塞自己的 `gap-*`——`cn` 让调用处赢，Record 曾因此把三块压到 8px，比块里的行还紧。vendored 组件自带的、不在尺子上的间距同样在调用处合缝：`FieldGroup` 的 `gap-5`（20px，比块与块的 16 还宽）由 `AnalysisEditor` 以 `className={SPACE.ROWS}` 覆掉，和 `SEGMENTED` 是同一条规矩。（见 test/recordWorkbench.test.tsx 与 test/analysisUi.test.tsx 钉 class，stories/view-engine/ 的 `BlockSpacing`、`EditorRowSpacing` 量真实像素）
- **互斥选项是一个控件，永远不是一排按钮**。判据按选项的多少与长短走：**≤3 个短选项** → 分段控件（segmented），一圈外框、内部无缝（`SEGMENTED`）；**选项是一句话，或多于三个** → `Select`；**同一职责下的几个动作** → `ButtonGroup`；**一个主动作带几种变体** → 拆分按钮（`SaveActions` 就是）。踩过的坑是 `ToggleGroup variant="outline"`：每一项自带边框、组又给了 gap，读起来就是三个各自独立、碰巧挨着的按钮。vendored 的 `ui/components/**` 不手改，所以缝在调用处合——`className={SEGMENTED}`。筛选的简单／高级已经进了标题栏下拉，分组操作符已经是"满足…"选择器，剩下的两处（结果工具栏的布局、分析编辑器的表／图）都用 `SEGMENTED` 合成一个控件。

## 工作台骨架

- 工作台的骨架自上而下是：**标题栏 → 编辑带 → 状态条 → 已应用条件条 → 结果工具栏 → 结果 → 分页**，顺序按"离结果多近"排：结果是视图的目的，它上面的每一样都要为自己的高度负责。`ViewHeader` 一行说清这是哪个视图——种类图标、受众标签、标题，以及"屏幕上的东西存过没有"的标记（`saved === null` 是 `label.header.new-view`，`saved && dirty` 是 `label.header.unsaved`），右端是宿主的全局动作与 `SaveActions`，上一次写入的结局（`WriteOutcome`：冲突、未知、拒绝）落在标题栏下方一行而不是压在结果上。`EditorBand` 是编辑器所在的折叠带：已保存的视图打开时折起——作者已经决定过了，结果才是要看的东西——没存过的展开，折叠状态属于这一次打开，以 `runtime.id` 为 key 重置，不入库也不记忆。（见 test/editorBand.test.tsx「EditorBand」与 test/viewHeader.test.tsx「ViewHeader」）Analysis 与 Dashboard 本轮只接标题栏、状态条与已应用条件条，编辑器形态照旧。

- 这副骨架由 `WorkbenchShell` 画，三个工作台共用一份：侧栏（`ViewList`，`manager` 只在 `manager.can.anything` 时交出去）、`LeaveDialog`，以及主列——打不开时只有一条 `label.view.unopenable` 的 Alert，否则是 `ViewHeader` → `editor` 槽 → `ErrorStrip`(`filter.unmarked`) + `WarningStrip` + `strips` 槽（只有一种视图才有的，如 Record 的查询失败条） → `AppliedBar` → `result` 槽。props 是 `{ workbench, kind, title, theme?, messages?, locale?, timeZone, actions?, freshness?, editor, editorLabel?, editorModeLabel?, editorModes?, editorOpen?, defaultEditorOpen?, onEditorOpenChange?, editorPending?, strips?, result, defaultSidebarOpen?, onSidebarOpenChange?, expandable?, hasResult?, warnings?, className? }`——`hasResult` 与 `warnings` 留给自己没有结果、且告警由面板分担的 Dashboard 覆盖，其余两种读开着的那个视图本身。要画什么几乎全部读自一个 `WorkbenchController`（[react.md#useworkbench](../react.md#useworkbench)），只有三样例外，因为它们既不属于视图也不属于列表：**侧栏收起没有**、**编辑带展开没有**、**视图有没有铺满屏幕**。这三样都只属于此刻这一屏——不入库、也没有一条离开守卫要问的东西——所以由外壳自己持有，而不是让三个工作台各写一遍同样的 `useState`；
- `ViewHeader` 的视图名是 `h2`（`headingLevel` 可由宿主定级），`main` 以它的 id `aria-labelledby`——只在标题真的在屏幕上时才指，指向不存在的 id 是一个坏标签而不是一个缺标签。（见 test/accessibility.test.tsx「the open view names the region it is drawn in」）
- **标题栏是两组，之间不串**。左边是「我在看哪个视图」：`data-slot="view-identity"`——`leading` 槽、种类图标、受众标签、视图名（`h2`）、未保存标记，以及 `SaveActions`。保存命令跟着视图名走而不是站在行尾：它改的就是这个名字底下的那份配置。右边是「我在怎么看它」：`data-slot="view-controls"`——先是视图级控件（编辑带的开关，然后是铺满屏幕的开关），宿主自己的全局动作永远排在最后，这样宿主加一个按钮不必知道它挨着什么。视图级控件由内向外读：编辑带管「视图问什么」，铺满管「答案得到多大地方」，刷新管「答案多久换一次」。最后这一个是 `WorkbenchShell` 的 `freshness` 槽（`RefreshControl`，`outline`），**只有 Analysis 与 Dashboard 用它**——Record 的结果工具栏本就有「数据新鲜度」那一组，同一个设置给两个入口就是多了一个。
- **标题栏窄下来时，只有名字让步**。这一行按「一组一根弹簧」摊开：每一组按**自己内容**定尺寸，组里恰好有一个可以缩到零的成员——`view-identity` 里是视图名（`w-0 grow`），收起侧栏后是 `view-collapsed` 那一组（`grow`）里的 `ViewSwitcher`（`w-0 grow`）。`view-header` 自带 `flex-wrap`，于是两组并排放不下时右边那组整组换行，而不是压在左边那组上面。
  - **`min-w-0` 只给底下真有弹簧的组**。`min-w-0` 允许一个 flex 项被压到比自己内容还窄；`view-identity` 的成员除名字外一律 `shrink-0`，所以它以前会报「我 96.8px 就够」而装着 206.9px 的东西——父级信了这句话，`flex-wrap` 因此永远不触发，`SaveActions` 被画在 `view-controls` 底下 102px。375px 视口上，`Save` 左缘、正中、右缘三点 `elementFromPoint` 全答筛选开关：键盘够得着，指针一下也点不到；
  - **`truncate` 自己做不到这件事**。一行不折的标题即便 `overflow: hidden`，仍按整串文字向父级要宽度，所以按内容定尺寸的组会跟着名字一起长出去——溢出换了条路而已（`max-w-fit` 同理无效）。`w-0` 是一个**确定**的尺寸，组据此把名字算作零，`grow` 再把剩下的空间全给它：名字因此是这一行唯一会缩、也唯一会被截断的东西；
  - **被截断的名字仍带着全名**。`h2` 身上是 `title={state.title}`：截断只是视觉的，读屏照旧读得到整串，省略号之后鼠标用户却没有别的路回到全名。同一个包里 `ConditionPill` 的字段名就是这么做的（`title={label}`），这里沿用它。（见 test/viewHeader.test.tsx「carries the whole name it may have to truncate」）
  - **缩无可缩时溢出，而不是互相盖住**。图标、受众标签与保存命令缩不动——一个被截断的「共享」比一次老实的溢出更糟——所以栏宽小于这些东西的和时，标题栏比它所在的那一栏宽，由外面裁掉。它不许做的是靠把自己的一组画到另一组上面来「放下」。（见回归 story「标题栏/回归」：三套内容各走一趟宽度阶梯，逐档量 `getBoundingClientRect()` 与 `elementFromPoint()`——jsdom 不排版也不做命中测试，这条只能在浏览器项目里跑）
- **侧栏可折叠**。`ViewList` 的标题行带一个折叠按钮（`onCollapse` + `collapseRef`）；收起后 `aside` 与它的 `Separator` 都不渲染，标题栏的 `leading` 槽接过三样东西：展开按钮、定义标题（窄于 `sm` 时先让位）、以及 `ViewSwitcher`。收起时 `ViewHeader` 收到 `namesView={false}`：种类图标不画、`h2` 转 `sr-only`——它仍在 DOM 里、仍带着 `main` 指过来的那个 id，只是不再和切换器把同一个名字说两遍。折叠是视图状态，不入库、不记忆、不经离开守卫。
- **`ViewSwitcher` 是侧栏收起后的那份列表**：触发器是种类图标 + 当前视图标题 + chevron，菜单按受众分两组（个人在前）、当前项打勾、系统视图带标签，末尾用分隔线隔出「管理视图」。选择一律走 `workbench.choose`，所以离开守卫照样先问一句——快捷方式是同一扇门的近路，不是绕过去的路。
- **管理器一个对话框、两个入口**：侧栏的齿轮与切换器的末项开的是同一个 `ViewManager`，所以它的开关状态被提到 `WorkbenchShell`，`ViewList` 只负责给出入口（`onManage`）而不再自己渲染对话框。两个入口各自持有状态就是两个对话框。入口本身仍按 `manager.can.anything` 决定给不给。
- **编辑带的开关在标题栏**，不在编辑器上方自占一行：`EditorBandToggle`（图标 + 名字 + `aria-expanded` + `aria-controls`）带着 `pending > 0` 时的那个点与计数——折起来时那是「改过、没应用」的唯一凭据，不能跟着编辑器一起消失。开关右侧可挂一个 chevron 下拉（`editorModes`，`ButtonGroup` 里的第二个按钮，形状同 `SaveActions`），装这个编辑器提供的几种编辑方式；Record 与 Dashboard 把筛选的简单／高级放进去，Analysis 本轮不给（编辑器形态仍是 [decisions.md](../decisions.md) 的 Q2）。`EditorBand` 自此只画内容：折起时整段**不挂载**而不是隐藏，编辑器的输入就是视图的草稿，折起的带子不该在页面上留下可聚焦的控件。默认展开与否照旧——没存过的展开，存过的折起——并以 `runtime.id` 为准重置。
- **视图能铺满屏幕**，开关（`ViewExpandToggle`，`data-slot="view-expand"`）在标题栏右组、编辑带开关之后，带 `aria-expanded`；措辞是 `label.workbench.expand-view`／`collapse-view`（「铺满屏幕」／「退出铺满」——不叫「全屏」，浏览器自己那个 F11 才是，它连地址栏一起收走）。状态归 `WorkbenchShell`，与侧栏折叠同一类——只属于此刻这一屏，不入库、不记忆、不经离开守卫；三个工作台各自声明并透传 `expandable`（默认 true），宿主用主入口就能把这个控件去掉，不必放弃工作台自己去拼 `WorkbenchShell`。规则与理由：
  - **就地展开，不是 portal**。`useViewExpansion(target, toggleRef, enabled)`（`ui/ViewExpansion.tsx`）只在一个 `.fve-root` 上写一个 `data-view-expanded`——`target` 指到视图里面任何一个元素都行，hook 自己 `closest('.fve-root')` 解析到那个根，否则页面会被锁在一个根本没长大的面后头。重新挂载会把用户正看着的草稿、选择、滚动位置与正在组字的 IME 一起丢掉，面还会离开宿主那个带 `.dark` 的祖先，所以什么都不搬家。`ViewSurface` 因此把调用方的 `ref` 与自己那个合并（它自己要靠根读回明暗），并**把 callback ref 的清理函数原样透传**：React 19 允许 callback ref 返回一个卸载清理，宿主用 ref 装 `ResizeObserver` 就是这么还的，吞掉返回值等于让那个观察器跟着页面活到最后；
  - **顶层（top layer）不用**：`requestFullscreen()` 与 `popover="manual"` 都能一举绕开 containing block，也都被否掉，理由是同一个——本包所有弹层都 portal 到 `document.body`，字段选择器、筛选菜单、保存拆分按钮与视图切换器会整片落到铺满的面**后面**去（全屏下干脆不渲染）。一个打不开菜单的视图不算铺开了；
  - **留在普通层，只与宿主比高低**：铺满的面取 `z-index: 0`——自成一个 stacking context，但不高出一级。弹层不在这笔账里：它们自带一层（D11，`z-index: var(--fve-popup-z-index, 50)` 写在 positioner 上），怎么都在面前面。所以这个 `0` 说的只是面与**宿主页面**的关系：盖住宿主的普通内容，而宿主自己刻意抬起来的东西（一条 `z-index: 10` 的吸顶栏）仍会盖住它。自成 stacking context 是为了里面的部件（下面那个吸顶的出口）只和自己人比，不去和宿主的比。这条当初是反过来写的——那会儿弹层停在 `z-index: auto`，面取 `1` 或 `40` 就把列设置弹层埋了（Chromium 1280×800 实测），面因此不得不留在 0；D11 把层级搬到 positioner 上之后，约束没了，取值不变；
  - **`position: fixed` 不等于视口**：祖先只要有 `transform`、`filter`、`perspective`、`backdrop-filter`、`will-change`、`contain`、`container-type`，它就成了 containing block，`inset: 0` 于是只铺满**那个容器**——动画面板与要 GPU 提示的栅格外壳天天这么写，而 `EmbeddedView` 本就要落进任意宿主。逐个列举触发条件是一份会过期的清单，所以改为**量**：面上去之后读一次 `getBoundingClientRect()`，不是视口，差值就是修正量，写回 `--fve-expanded-x/y/w/h`（样式表里这四个的兜底就是 `inset: 0` 的长写法，常见情形一行 JS 也不写），`resize` 时重算。祖先若不只是**移动**、还**缩放**（`transform: scale()`、`scale`、`zoom`），还要再量一次：`getBoundingClientRect()` 报的已经是屏幕像素，而这四个属性是按元素自己的坐标读的，一个本地像素等于 `scale` 个屏幕像素——把差值原样写回去，偏移和尺寸会一起差同一个倍数。所以第二遍也是量出来的：写完朴素值再读一次盒子，**要的和到手的之比就是那个 scale**，除掉即可（回归故事 `FillTheScreenInScaledHost`）。剩下三种它治不了、也没有任何「留在原地」的方案能治：祖先自己**裁剪**（`overflow: hidden`、`contain: paint`）仍会裁掉，祖先把自己的 stacking context 抬到 portal 弹层之上仍会盖住，祖先**旋转或斜切**则根本不存在一个能盖住视口的轴对齐盒子——一个 inset 加一个尺寸表达不了一次转动；宿主遇到这三种应把工作台放到那个容器外面；
  - **不是模态，并且以「什么都不声明」说出这一点**：没有 `aria-modal`、不困住焦点、页面其余部分也不设 inert。什么也没在问、没有要回答的东西——这就是刚才那些内容，还在原处，还是宿主 DOM 的后代。声称模态等于告诉读屏器宿主页面不可用，而那是一个关于**内容**的承诺，我们没有资格做；把「其余部分」设成 inert 则要从这个元素一路往上、在每一层给兄弟节点加 inert——那是 portal 的活，而 portal 正是那个要拿草稿去换的东西。困住焦点更糟：非模态里唯一的出口会变成一个要靠猜的键。`test/accessibility.test.tsx` 在铺满与还原两种状态下都跑 axe；
  - **从模态那里只借一样**：背景不滚。一次看不见效果的滚动就是一个悄悄丢掉的滚动位置。锁写在**真正滚这一页的那个元素**上（`document.scrollingElement`，标准模式下就是 `<html>`）：body 的 overflow 只有在 html 还是 `visible` 时才传到视口，宿主只要写了 `html { overflow: auto }`，一个只锁 body 的面背后照样能滚。锁**按 important 写**——宿主的样式表可能正拿 `html { overflow: auto !important }` 或 Tailwind 的强制 utility 管着这一页，普通内联声明压不过它——并且**两个轴分开存、分开还**（`overflow-x`／`overflow-y`，各带各的优先级）：简写既读不出「只设了一个轴」「两轴值或优先级不同」的页面，写简写又会把两个长写法一起替掉，从简写还原等于把宿主另一个轴上原有的样式永久抹掉。锁按 `document` 计数：同一页上一个仪表盘面板的工作台与一个嵌入视图可以同时铺满，先收的那个不能把页面还回去，后收的那个必须还。计数归零时连记下的值一起丢掉，下一次铺满读的是那时候的页面而不是当初那个。卸载走的是同一条清理路径，所以「铺着屏幕直接跳走」也不会把页面锁死；
  - **Esc 收起，但前面有东西时轮不到它**：对话框、菜单、列表框，以及**原生 `<dialog open>`**（它的 role 是隐含的，不是属性，`[role="dialog"]` 永远匹配不上；document 上的监听器又跑在浏览器自己的默认关闭之前，不点名它，第一次 Esc 会把对话框和视图一起收掉），还有焦点仍停在 `data-popup-open` 触发器上的那种，都先于它拿到这个键（判据与 `FilterPanel` 的 Enter 提交同一个标记），`defaultPrevented` 的也一样放过。**正在用输入法组字时这个键也不归它**：Esc 那一下是给候选框用的，事件本身照样是 `Escape`、照样没被 prevent，收起视图等于把打了一半的词和焦点一起带走——判据是 `event.isComposing`，外加 `keyCode === 229` 这个不设 `isComposing` 的浏览器上的同义写法。判定用的是**事件自己那个文档的 `Element`**：iframe 里的节点对顶层 realm 的 `instanceof Element` 答 false，用错构造器等于对最可能被嵌入的那些面跳过整套检查。同时铺开的几个面共用一个 document，所以谁答这个键按**文档顺序**判——它们的 `z-index` 是同一个，谁盖着谁只由文档顺序决定；按「谁后铺的」记，宿主先铺了文档里靠后的那个、再铺靠前的那个时，Esc 会收起**底下**那个而用户正看着的那个纹丝不动。**焦点交给收完之后仍在前面的那个面的控件**——而且是那个面**身上**的那一个：前面那个面盖住宿主自己的按钮，和盖住这个面的按钮一样彻底，所以它的开关若不在它里面（嵌入视图的形态），焦点该落在它露出来的那个出口上——都收完了才回到自己那个：点按钮收起时什么都没重新挂载、焦点本来就在那儿，而在铺满的一整屏里按下 Esc，焦点会掉到 body 上；另一个面还铺着时把焦点丢回自己那个按钮，等于把它丢到一块看不见的内容里去。Esc 这条路由按钮上的 `aria-keyshortcuts`（只在铺着的时候）说出来，不为它单开一个 tooltip；
  - **控件没了，铺满就结束，不是存着**：`enabled` 转 false 时内部状态在渲染里就被清掉（不是放进 effect——那会先让屏幕上存在一个没有出口的铺满面），否则控件一回来视图就会在没人要求的情况下重新占满整页。**同一扇门的另一侧**：`enabled` 已经是 false 时调 `toggle` 直接什么都不做，而不是翻一个被 `on` 遮住的状态——宿主的控件可能比 `enabled` 活得久（快捷键还绑着、按钮还没卸载），存下那一下等于让控件一回来就占满整页，和上面那条是同一个陷阱。工作台传的是 `expandable && open`，所以切换视图释放旧 runtime 的那一下也一并结束——和编辑带折叠一样，属于这一次打开；
  - **目标节点晚到、或者中途没了，都得算数**：`target` 是一个 `RefObject`，React 填它、清它都不出声，而那个 effect 没有别的理由再跑一次——宿主在内容还没挂载时就按下开关，API 会一边报 `expanded: true` 一边没有任何元素带上属性、页面也没被锁；铺着屏的那个面被宿主摘掉，页面会被永远锁在那儿而上面什么都没有。所以**铺着的时候盯 document**（`MutationObserver`，只在铺着时装，且只有「解析出来的节点和上一轮定下来的那个不是同一个」才惊动 effect）。宿主若只是把一个还活着的 `RefObject` 悄悄指向另一个已在屏上的面，这里无从看见，应当先收起再铺开；
  - **高度给结果，因为铺满是为了行**。只有根被拉到视口高而结果仍停在自己的 `max-height`，换来的是更多的**列**而不是更多的**行**，下半屏还空着。所以铺满时这条链成为定高弹性列：根 `overflow: hidden`，侧栏与主列各自 `min-height: 0` + `overflow-y: auto`，结果块 `flex: 1 1 auto; min-height: 0`，`RecordTable` 的滚动容器 `flex: 1 1 auto; min-height: 0; max-height: none`——它因此吃掉标题栏、状态条、工具栏与分页之外的全部高度。**只给自己在滚的那一个**：`RecordTable` 带 `data-scrolls` 标出自己是哪一种，`scrolls={false}`（仪表盘面板里顶着外层滚的那种）不吃这条规则，否则粘性层底下会被重新塞进一个滚不动的 scrollport。编辑带与条件块封顶在 `max-height: 50%` 并自己滚：它是条件的折叠带，不是视图被铺开的理由，一棵长筛选树把行顶下屏等于把整个手势作废；
  - **表格的粘性两种状态下都成立**：铺满改的是「哪个盒子高」，不是谁在滚——表头粘在顶、两行汇总粘在底、冻结列粘在侧（见 [record.md#表格-chrome层次与冻结列](record.md#表格-chrome层次与冻结列)），回归故事 `FillTheScreen` 与 `FillTheScreenInTransformedHost` 在真浏览器里按实测几何两种状态各量一次；
  - **嵌入视图铺满时，根自己就是 scrollport**：上面那几条把高度交给工作台的 `main`，再交给自己在滚的那个 `RecordTable`；而 `EmbeddedView` 把结果直接画在根上——没有 `main`，`RecordCards`、`AnalysisTable`、`DashboardGrid` 一个也不匹配——长结果会被根自己的 `overflow: hidden` 在屏幕下沿切掉，而背后那一页正锁着、够不着剩下的部分。所以 `:not(:has(> main))` 的根拿 `overflow-y: auto`；嵌入里的记录表仍吃上面那条、自己滚自己的，这条只兜它兜不住的；
  - **`EmbeddedView` 不长自己的开关**：它是结果本身、别无他物——没有标题栏、没有工具栏、没有保存——为了装一个按钮给它造一行 chrome，就是在宿主的订单详情页上摆一件那页没要、也放不到合适位置的东西。它欠宿主的是**办法**而不是按钮：`ref` 透传到它画的那个根，`useViewExpansion` 从 `/ui` 导出，宿主把控件放在自己 chrome 里，指向拿到的那个元素（或视图里任意一个元素）即可；
  - **但开关不在面里时，面自己长一个出口**：宿主的控件是这个面的兄弟节点，面一铺满整屏就把它盖住了——桌面用户也许会猜 Esc，触屏根本没有 Esc，那就是一块没有出口的屏幕。`ViewSurface` 因此在每个面上都画一个 `data-slot="view-exit"`（文案复用 `label.workbench.collapse-view`），平时带 `hidden`——不在页面里、也不在无障碍树里——只有 `useViewExpansion` 发现管着这次铺满的控件**不在**这个元素里时才把它露出来；工作台的开关本来就在标题栏里，所以工作台上永远看不到它。它不带自己的 handler：知道怎么收起的是持有这次铺满的那个面，点击由它接（于是 `ViewSurface` 一点铺满状态都不必知道）。它在根的 flex 列里占一整行（`order: -1`，右对齐）而不是浮在内容上——把一个按钮压在别人第一行数据上，正是嵌入视图存在的理由所反对的那种 chrome——并且 `position: sticky; top: 0`，因为它出现的正是根在滚的那种形态，滚走了的出口不算出口。（见 test/viewExpansion.test.tsx 与回归 story「FillTheScreen」「FillTheScreenInTransformedHost」）
- 留给扩展的缝还在：`ViewHeader.leading` 与 `ViewHeader.trailing`、`data-slot="view-sidebar"`、以及 `defaultSidebarOpen`／`expandable` 这两个布尔——折叠与铺满都是一处的改动，不是它旁边每一块的布局改动。

## 刷新是一个拆分按钮

- **主键一次刷新，`▾` 选间隔**（`RefreshControl`，`ui/RefreshControl.tsx`）。两半是一个控件，因为它们答的是同一个问题——屏幕上这些数字怎么更新；拆成两个按钮会把难得用一次的那个抬到天天按的那个的份量上。主键的行为与从前一字不差；
- **间隔改的是 `ViewConfigBase.refresh.interval` 本身**，选中即 `edit({ refresh: { interval } })` 加 `apply`——与排序、列设置同一条路（D3 的例外）：计时器读的是 `applied`，只 `edit` 不 `apply` 就只改了配置、没改任何会跑的东西。它因此像任何一次编辑一样让视图变脏，`Save` 才落库；配置里没有第二份间隔，草稿、计时器与屏幕说的是同一个数；
- **可选档位按内核会不会跑来裁剪**：整数且落在 `runtime.limits` 的 `[minRefreshInterval, maxRefreshInterval]` 里——`validateRefresh` 拒绝小数（`config.refresh.not-an-integer`）与越界是同一件事，所以这里也是同一个判断；不允许的档位**不存在**而不是禁用（D4）。选中的那个若跑得起来就并进梯子里（与每页条数同一条读法，梯子要能勾得上正选着的那一档），跑不起来则不并——那份配置本就被准入拒绝、状态条已经在说，菜单要给的是出路（关闭，或一个能用的档位）而不是那个坏掉的数；同理，`applied` 里一个跑不起来的数也不摆到按钮上冒充节奏；
- **`▾` 什么时候不出现**：梯子空、没有在跑的间隔、`refresh` 这个成员也没有被准入说过话，三者同时成立才不给——那时它能开出来的唯一一项就是视图已经在的那个状态。反过来说，**梯子空也要给**：一个正在跑的间隔总得有地方关掉；而 `refresh` 成员本身被拒时（缺失、不是对象、小数、越界）更要给，因为「关闭」写下的 `{ interval: null }` 正是这几种拒绝的修法——菜单此时把自己藏起来，用户就被钉在一份 Apply 与 Save 都过不去、屏幕上却没有任何控件能修的配置上——**报了错的那一项，一定够得着**（[record.md](record.md) 的同一条规矩，这里照办）；
- **开着时按钮上带一处凭据**：那一档的读法（`30s`、`5 min`），加一句 `label.refresh.on` 作 `aria-description`。它说的是「这个视图在自己刷新」，是关于视图的事实，不是三态凭据（D2）之外的第四个点——点属于「改过、没应用」，在编辑带的开关上；
- **凭据读 `applied`，菜单勾 draft**——控件两半答的是同一个成员的两个时刻。凭据是一句「正在发生什么」，只能跟着计时器真正读的那一份走，与 `AppliedBar` 读 `result.own` 而不读 `applied` 是同一条理由；菜单勾的是草稿，因为那是编辑器的值、也是 `Save` 会写下的那个数，和布局切换、每页条数读草稿一样。两者只在**草稿被准入拒绝、`apply` 落不下去**时分开：此时结果上方的状态条正在说这份草稿为什么被拒，而旧的那个数才是真的。若两处都读草稿，按钮就会挂着一个没有任何东西在跑的节奏——把「关掉刷新但被拒绝」也一并说反：什么都没关掉，计时器还在原来那一档上等着；
- **停表的时机一律由 runtime 说了算**（草稿有 error、编辑器有焦点、页面不可见、请求在途，四条见 [runtime.md#自动刷新](../runtime.md#自动刷新)），界面不发明第五条。查询在途时禁用的只有主键那一半——再按一次只会把自己顶掉；仪表盘上「在途」要问面板：它自己不跑查询、`state.query` 永远是 `idle`，所以那一半读 `dashboard.resolving || dashboard.loading`（后者聚合子 runtime，与 `DashboardViewRuntime` 自己的计时器在开火前问的是同一件事），否则十二个面板正在查也照样点得下去，把它们全部顶掉且毫无提示；`▾` 照常可选，因为换间隔是关于下一段时间的决定，而 `applied` 变化时替换在途请求本就是 runtime 的既定行为。（见 test/refreshControl.test.tsx 与 stories/view-engine 的 `AutoRefresh`）

## 三态各有一处凭据

- 三处，不是四处：刷新按钮上那一档 cadence 说的是「这个视图在自己刷新」（见[刷新是一个拆分按钮](#刷新是一个拆分按钮)），是关于视图的事实，不参与下面这三处说的「屏幕上的东西走到哪一步了」；
- 三种状态各有一处凭据，互不重复：**草稿未应用**是条件 pill 与应用按钮上的那个点（`data-pending`，基准是 `state.applied`），折起来时汇总成折叠行上的 `label.editor.pending` 计数；
- **已应用**是结果块顶部的 `AppliedBar`——它是这批行的说明文字，所以属于结果块而不是浮在编辑器与工具栏之间；它读 `state.result.own.filter` 而不是 `applied`——应用会启动一次查询，在查询答复之前 `applied` 已经走在前面，跟着它的条会描述还没到屏幕上的行；
- Dashboard 没有自己的结果，面板各跑各的查询，所以那里读 `state.applied.filter`，而「有没有结果可描述」由工作台看面板来答（任一面板已有结果或已经开始查询）；
- **未保存**是标题旁的标记，Save 按钮只在 `dirty && can.save && !pending` 时可按。AppliedBar 保留树的逻辑：根下每个直接子节点一个 badge，分组子节点合成一个；
- badge 的文字由 `ui/display.ts` 的 `summaryText` 按部件拼，不是 `FilterSummaryItem.text`——那句是内核给宿主的英文兜底，摆在中文页面上就是唯一没被翻译的一行。字段名来自定义，操作符走 `label.operator.<OP>`（未知成员仍回退到派生拼写），值按 [值按字段显示](#值按字段显示) 的规则，与表格里同一套：定义给过名字的值用那个名字，其余按 `cell ?? kind`——所以 `cell` 和 `kind`、`numberFormat` 一样跟着摘要项走，否则声明了 `cell: 'date'` 的数字字段在表里是日期、在条上是十三位数——日期按 `ViewSurface` 的 `locale`／`timeZone` 显示，数字按 `numberFormat`，相对值按 `bound` 分成两句——区间用 `label.relative.window.<direction>`（`last {amount} {unit}`／最近 {amount} {unit}），边界用 `label.relative.instant.<direction>`（`{amount} {unit} ago`／{amount} {unit}前）——命名时段用 `label.relative.preset.*`。多值与组内各项之间的分隔符本身也是措辞（`label.filter.join`：英文 `, `，中文 `、`）；
- 分组以自己的操作符词起头（`label.filter.all-of`／`any-of`／`none-of`）再接各项，再嵌套的分组加括号；只有一条条件时 `all-of` 与 `any-of` 都不加那个词——它们什么也没多说——而 `none-of` 是否定不是连接词，有几条都要说。谓词条件（`ELEMENT_MATCH`）按同样的方式读出它持有的那些条件，谓词里什么也没问时说 `label.filter.any-entry`；
- 值读不出来的那一项只说字段名（这正是内核给的 `blank`）；字段已消失的那一项连操作符一起说——值读不出来，问题还在。✕ 的可访问名用的是同一段文字，所以读屏器听到的与屏幕上的一致；
- 每个 badge 带一个 ✕，把对应条件的值设回未填写（分组则组内每条）并重新应用，字段行留在编辑器里，这是 `clearValue(path)` + `submit()`；
- 没有条件而已有结果时显示 `label.applied.all`，还没有结果时整条不渲染。宿主注入的作用域条件（`scoped`）排在可编辑 badge 之后，以 `variant="outline"` 加 `data-scoped` 单独成组，不带 ✕，并由 `label.applied.scoped` 说明它由页面设定——它不在 draft 里，也没有一条编辑器的路径指向它，给一个删不掉的 ✕ 等于许诺一次做不到的放宽；
- 嵌入式视图（`EmbeddedView`）整条 bar 都是只读的（`readOnly`），连自有条件的 ✕ 也不渲染。（见 test/appliedBar.test.tsx「AppliedBar」）

## 保存与视图管理

- 保存与视图管理分两处。`SaveActions` 是一个拆分按钮组：主按钮说此刻该做的那一件事（能存就是 Save，否则是 Save as），菜单里放其余的（Save as、Revert）；
- `pending` 时显示 `label.save.saving`，落地后 2.5s 内显示 `label.save.saved` 并向读屏器播报一次；
- 写入在途、或结局仍为 `unknown`／`conflict` 未结清时，Revert 与菜单一并禁用——在保存自己的编辑时撤销，会把被保存掉的那份配置变成新基线之上的脏草稿；
- 结局未结清时撤销，紧接着落地的 Retry 或「保留我的」会把它一并撤回。`hasErrors` 只在主按钮真的是就地 Save 时禁用它：不能写就地时主按钮做的是 Save as，副本去的是另一个受众，那里的准入由对话框自己判（共享仪表盘引用个人视图这类，正是在当前受众被拒而在个人受众成立），拿当前受众的判决锁住它，等于把只读视图的唯一出口一并锁死。改名、删除、排序、设默认都不在这里——它们改的是列表而不是眼前这个视图——而在侧栏标题旁的管理器（`ViewManager` + `useViewManager`）里，每个按钮按许可**存在或不存在**而不是置灰，系统视图没有改名与删除；
- 未打开实例的写入结局显示在它自己那一行。删除确认把后果拼出来：基础句，shared 再加一句，目标正是当前打开且 dirty 时再加一句；
- 删除遇到冲突时「保留我的」不直接覆盖，而是用 `write.remote` 刷新后的摘要再确认一次（见 [management.md#冲突与未知结果](../management.md#冲突与未知结果)）——第一次确认说的是列表里的那个视图，冲突报回来的已经不是它。上移／下移只在行所在的受众组内移动，到组的首尾即禁用（`canMove`）。一行的动作分两组而不是一排散按钮：排序（上移／下移）与处置（设为默认／改名／删除）各是一个 `ButtonGroup`，两组之间是 `SPACE.GROUPS`；因为按钮按许可存在或不存在，各行带的动作数不同，动作簇**左对齐在一个定宽槽位**（五个 `icon-sm` 按钮加一个组间距，148px）里，好让同一个动作在每一行都落在同一个横坐标——不为对齐把缺席的动作画成置灰按钮（见 [management.md#列表偏好与默认视图](../management.md#列表偏好与默认视图)）。`rejected` 的那一行也带一个 `label.rejected.dismiss`，所有恢复按钮在 `manager.pending` 非空时禁用（见 [management.md#冲突与未知结果](../management.md#冲突与未知结果)）。管理入口本身按 `manager.can.anything` 决定给不给：顺序、默认与任一行的改名／删除全都不可用时，工作台根本不把 manager 交给 `ViewList`。（见 test/saveActions.test.tsx「SaveActions, the split button group」「WriteOutcome」与 test/viewManagerUi.test.tsx「ViewManager rows」「the manage button on the view list」）

落到文件上：「结局→一句话加一排按钮」只有一处，`ui/OutcomeActions.tsx`，打开的视图（`ui/WriteOutcome.tsx`）与管理器的行（`ui/ViewManagerRow.tsx`）共用它，两边的差别只是 `surface`——行在对话框里是一行小字小按钮，视图是标题栏下的一条框。各自留下的是只有自己有的那部分：`WriteOutcome` 的复制出口与落地通知，加上 `ui/ConflictConfirm.tsx` 的双栏确认；管理器的删除后果对话框是 `ui/DeleteDialog.tsx`，第一次确认与冲突后的二次确认是同一个。

## 离开保护

从侧栏切到另一个视图会释放当前 runtime，而未保存的草稿只活在 runtime 里，所以那是一次删除工作：`useLeaveGuard`（`/react`，见 [react.md#useworkbench](../react.md#useworkbench)）在 `dirty` 或写入结局为 `unknown` 时先问一句，没有东西可失去时一句也不问——每次切换都拦的守卫，人会学会不读就点掉。该问的时候问什么是无状态的，`/ui` 这边只剩 `LeaveDialog({ leave, messages? })` 照着 `leave.asking` 画，两个按钮分别接 `confirm` 与 `cancel`；它渲染在承载文案的 `ViewSurface` 之外，所以要单独把 `messages` 递给它。（见 test/workbench.test.tsx「useLeaveGuard」）

## 动作槽位

- 三层业务动作走 render 槽位（`RecordActionSlots`）：`global` 在标题栏，`bulk` 在有选择时的结果工具栏，`row` 在表格最后一列（sticky，滚不走）与卡片页脚，统一裹在 `RowActions` 里。动作是代码——它开表单、发命令、跳页面——所以由宿主交出来，不按字符串键注册，也不进配置：存下来的是"看法"，能对记录做什么属于挂载工作台的那个应用。（见 test/rowActions.test.tsx「RowActions」）

## 渲染边界

- **宿主交进来的 React 各有一道边界**。三层动作槽位（`RecordActionSlots`，D6）与仪表盘面板里的 markdown 都在工作台自己的渲染树里，React 遇到抛错会从最近的边界往上整棵卸掉——没有边界时，一个抛错的行动作会把标题栏、编辑带和还没保存的草稿一起带走。所以 `WorkbenchShell` 给**标题栏的全局动作槽**、**编辑带**与**结果块**各一道 `RenderBoundary`（`ui/RenderBoundary.tsx`，`react-error-boundary`），`DashboardPanel` 给**每个面板的正文**一道，`EmbeddedView` 给自己的正文一道。落到边界上的那一块换成可复原的错误态——一句"这一块没能画出来"（`label.render.failed`）、错误自己的原话、一颗"重试"——`role="alert"` 就地播报；边界之外照常可用。全局动作槽的那道是单行的（`compact`），因为它站在一排控件里。
- **错误不被吞掉**。每次落到边界都以 `RenderFailure { boundary, panelId?, error, componentStack? }` 交给宿主的 `onRenderFailure`——三个工作台、`DashboardGrid` 与 `EmbeddedView` 都收这个 prop——能修它的只有宿主。重试只是再画一次：原因还在就再落一次、再报一次。
- **失败属于它发生的那一次打开**。边界以 `runtime.id`（面板以子 runtime 的 id）为 reset key：切到另一个视图时各块重画，错误态不跟着人走。（见 test/renderBoundary.test.tsx「RenderBoundary」「the workbench boundaries」「the dashboard panel boundaries」；故事「Record 工作台/回归」的 `RenderFailure`）

## FilterPanel 的布局

- `FilterPanel` 的布局：分组是带边框的块，头部是**操作符选择器**与删除，主体是一条条件带：等宽栅格，能放几列放几列，pill 在格子里对齐，字段名、操作符、值上下对齐；操作符读作它对底下条件下的那句话——「满足全部条件」／「满足任一条件」／「全部条件均不满足」——而不是编译成的那个布尔：一次只显示一个，它就得自己把意思说全。三个操作符在任何位置、任何模式下内核都受理，所以没有一个会以禁用的样子出现（`filter/validate.ts` 的 `GROUP_OPERATORS` 是平的一组）；
- 持双输入的条件（区间、日期）在条件带放得下两列时占两格；
- 条件是内联的紧凑 pill（字段 · 操作符 · 值编辑器 · 删除），不独占一行，未填写时虚线边框，校验有 error 时标为 invalid（`data-invalid`，destructive 色），只有 warning 时标为 `data-warning`（主题的 `warning` 色）——条件照常执行，颜色只说"值得看一眼"；
- 持有谓词的 `ELEMENT_MATCH` 条件和分组一样渲染为块，头部是字段与操作符，主体是它持有的分组。简单模式只显示根分组的条件带，高级模式显示根分组的块。**模式切换看外面有没有地方放**：它是「怎么编辑」而不是条件本身，所以外面一旦有更合适的位置，面板就把它交出去（`modes={false}`）——Record 与 Dashboard 的编辑器就是这块面板，模式因此挂在标题栏折叠带的下拉里（[工作台骨架](#工作台骨架)）。Analysis 不交：它的编辑器是这块面板**加上**分析编辑器，没有哪一个折叠带能用「筛选」这一个名字把两块都折起来，所以控件留在面板顶部（分段控件，见[版式](#版式三块一套间距一种选项控件)）。**一个存在却够不着的模式是丢掉的能力，不是更干净的界面**——`defaultAnalysisConfig` 从 `simple` 起步，控件一旦两头都没有，分析视图就再也写不出 OR／NOR 与嵌套分组。两处控件读的都是**生效的**模式而不是存下来的那个，简单模式画不出这棵树时那一项禁用，并把原因（`config.filterMode.not-simple`）挂在项上，而不是摊在旁边当一段散文。唯一的出口在底部一行：**添加筛选 · 撤销筛选修改 · 清空条件 · 查询**。Apply 是同屏唯一的 primary 按钮——提交是显式的，面板里的任何输入都不会自己重跑查询；
- **添加筛选是一张勾选表，不是一次性的菜单**。点开是一个浮层：标题「选择筛选字段」、右上角「完成」、一个搜索框，底下是按目录分组的字段（未分组的在前、无标题，与其余字段选择器同一套 `fieldGroups` 布局），两列复选框、可滚动。已经在面板上的字段是勾上的：勾上加一条条件（`addLeaf`），取消勾选把那条拿掉（`remove`）——「一个分组一个字段一条条件」这条规则让这个映射唯一，所以不存在「取消的是哪一条」的问题；已经填了值的字段也一样，勾就是这条条件在不在。浮层一直开着，`完成` 或 Escape 关闭，焦点回到触发它的按钮。建一个筛选本来就是挑好几个字段，每挑一个就关一次的菜单，四条条件要来回四趟；
- **嵌套分组另起一个控件**：「添加筛选」右边紧挨一个 chevron，菜单是 `AND 满足全部条件`／`OR 满足任一条件`／`NOR 全部条件均不满足`（操作符代码在前，句子在后：已经用 AND／OR 思考的人一眼找到，没有的人读那句话）。它不再是字段列表的最后一段——一张让人勾字段的表里，容不下一个不是字段的条目；
- **Enter 即提交**：面板根上一个键处理器，`aria-keyshortcuts="Enter"`。四种情况不算：IME 组字中（`isComposing`）、按着修饰键、落在面板自己控件的弹层里（弹层 portal 到面板之外，React 事件却照样冒泡回来；判据与 `leavesEditor` 同一个 `[data-popup-open]` 标记），以及目标本身就吃 Enter 的控件（按钮、链接、textarea——在「添加筛选」上按 Enter 是打开选择器，不能顺带把查询也跑了）。`blocked > 0` 时和 Apply 按钮一样拒绝；
- **未填写的值显示「未设置」**（`label.filter.not-set`）：没填值是正常的编辑状态而不是错误，框里说清缺的是什么，比空着看起来已经填完要好；
- 落在条件上的 error 数（`filter.blocked`）大于零时它禁用，并在左侧以 `label.filter.blocked` 说还有几条要改；
- 草稿与已应用不一致时按钮上也带那个点。`submit={false}` 时底行整行不渲染，留给从别处提交的编辑器（比如 Dashboard 的全局条件带）。已应用条件的摘要不在面板里，在结果上方的 `AppliedBar`：根是 OR／NOR 时整体折成一个 badge 并说明；
- 宿主注入的作用域条件在那里另起一组只读呈现，不带删除。（见 test/ui.test.tsx「FilterPanel tree editing」）

落到文件上：`ui/FilterPanel.tsx` 只留根——焦点边界、Enter 提交、超预算提示与底行；块与 pill 在 `ui/filter/` 下分为 `GroupBlock.tsx`（含条件带）、`ConditionPill.tsx`（含元素匹配块与 `PendingDot`）、`AddEntry.tsx`（字段选择器 + 加分组菜单两个控件的装配）、`FieldChecklist.tsx`（那张勾选表本身，单独成文件是因为它是整段浮层而不是一个按钮）、`FilterModes.tsx`（标题栏下拉里的那两项）、`groupOperators.ts`（三个操作符的措辞，选择器与加分组菜单共用一份）与 `FilterActions.tsx`（撤销、清空、查询）。值编辑器同理：`ui/FilterValueEditor.tsx` 只剩按 `EditorDescriptor.input` 分派的 switch——**这是全包唯一知道这个封闭联合的地方**，也是 [extension.md](../extension.md) 所说的按 kind 注册渲染器将来要切开的缝——每种输入各自一个文件在 `ui/filter/inputs/`（`text`／`number`／`select`／`remote`／`date`／`daterange`／`relative`，公共部分在 `shared.tsx`）。同一种输入的 `multiple` 与 `range` 是两种形状而不是一种：`range` 是两个端点，`multiple` 是一份可增删的值列表——每个值一枚带删除按钮的 chip（按钮以值命名，因为并排的几个只靠值区分），末尾一个录入框加一个添加按钮，Enter、添加按钮与**离开录入框**都把里面的东西收进列表（这一下 Enter 是「添加」而不是面板的提交；离开也算，是因为「查询」是面板上另一个按钮，去按它先把录入框 blur 掉，刚打的那个数不能被那一下点击自己吞掉），空值与半个数字不提交，重复值也不再进去；数值的 `IN`／`NOT_IN` 因此和内核一样收任意多个值，条件带上它与区间同样占两格（见 test/filterValueEditor.test.tsx「takes as many values into a number list as are entered」、test/filterPanel.test.tsx「builds a numeric IN of as many values as are entered」）。
