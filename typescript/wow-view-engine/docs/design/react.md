# React 层：钩子与控制器

`react/` 只依赖运行时与纯内核。每个钩子一节：先是签名，再是它承担的规则。默认组件与视觉规则见 [ui/README.md](ui/README.md)。

## useViewEngine

```ts
useViewEngine(options): ViewEngine
```

- 建一个并在卸载时释放；
- 需要更长生命周期由应用自建后传入。

## useViewRuntime

```ts
useViewRuntime(runtime): ViewRuntimeState | null
```

- useSyncExternalStore。（见 test/useViewEngine.test.tsx「useViewRuntime」）

## useOpenView

```ts
useOpenView(engine, instanceId, scopeFilter?): { runtime | null; loading; error; scopeIssues }
```

- 拥有所开 runtime：换 id 或卸载即释放；
- runtime 在其下被释放（如实例被删除）时不再交出，按同一 id 重新打开，得到新 runtime 或 not_found；
- 注入的 scopeFilter 被拒时，由 `scopeIssues` 交出被拒的那几条，宿主据此提示；**第一次打开就被拒与后来被拒是同一回事**（D17-5）：被拒的收窄根本不生效，视图照自己的配置跑，屏幕上是**没收窄的那份结果**加一条告警。它读的是 `runtime.refusedScope`（runtime 自己的状态，改变时会通知）而不是某一次注入的返回值——一开就带上的那个条件是在构造里进去的，那里没有谁接得住返回值；
- warning 不算拒绝，条件照常生效，warning 留在 runtime 的 `issues` 里由 UI 按 warning 呈现——否则旧的、更宽的条件仍在运行却无人知晓。（见 test/useViewEngine.test.tsx「useOpenView」与 test/embeddedView.test.tsx）

## useViewList

```ts
useViewList(engine, definitionId, options?: { kinds }): { items; all; preferences; permissions; defaultInstanceId; loading; error; preferencesError; reload }
// error 是 engine.list 答里的 failed（store 那一半没读到）或整个列表被拒的 Issue；两种情况下 items 都不清空：前者仍有代码声明的系统视图，重读时还留着上一份答案（F-05）
```

- 给出 `kinds` 时先按顺序排好再过滤、再解析默认，见 [management.md#列表偏好与默认视图](management.md#列表偏好与默认视图)；数据工作台给的是 `['record', 'analysis']`，宿主收窄成一种就给一种（D20）；数组按内容持有（inline 写也不会每次重算）；
- useViewManager 因此管的是过滤后的可见列表，而 move 提交的是 `all`（未过滤的完整顺序）里对调两项的结果，未列出的种类因此各守其位；
- `all` 是未经 `kinds` 过滤、同序的全部摘要，供 moveTo 在完整顺序里落子；
- reload 可带 `{ without?: id }`：重载期间把该 id 从留存的摘要里去掉，`preferences.defaultInstanceId` 命中它也读作 null，答案落地即恢复由 store 说了算——重载只刷新不清空，否则刚删掉的那一行会继续被列出、继续被当作默认视图，骑在默认视图上的工作台就会去重开一个刚被释放的 runtime（瞬时 not_found）；
- 它订阅 `engine.subscribe`（[runtime.md#viewengine](runtime.md#viewengine)）：本定义的通知到来时自动重载，删除那一条自带 `without`，卸载时退订。所以经引擎的创建／保存／改名／删除——无论出自管理器、视图头，还是宿主自己直接调 `engine.delete`——列表都自己跟上，没有哪个调用方需要记得通知（D15）；`reload` 仍然公开，它答的是「我从别处知道外面变了」，比如宿主自己的服务端推送。（见 test/useViewList.test.tsx「useViewList」「narrowed to one kind」）

## useFilterEditor

```ts
useFilterEditor(runtime): FilterController
```

- 按路径增删改、模式、清空、提交。**树的编辑本身只有一份**：`treeController`（同文件）绑到 runtime 就是这个钩子——`current()` 让每个动作读点击那一刻 runtime 手里的草稿（所以一次事件里先加分组再往里加条件是成立的），`onChange` 写回 `edit({ filter })`；嵌套编辑器（元素匹配）把同一个控制器绑在一片叶子的值上。从前钩子把九个方法逐字重写了一遍，嵌套走的反而是不显眼的那份（A-06，2026-09-21 收口）；
- `discard()` 把草稿筛选退回 `state.applied.filter`，是一次 **`edit` 而没有 `apply`**：屏幕上的结果本来就是在 `applied` 下取回来的，把这棵树放回去，查询与行原样不动——再跑一次只是花一个请求去拿已经在那儿的答案。做完 `pending` 归 false，因为两棵树又一致了，这正是它的意思；
- `discard()` 不是 `commands.revert`。revert 管的是**存下来的配置**：它丢掉这个视图一切未保存的编辑，把库里那份重新变成草稿。`discard()` 只丢掉**没应用的那些条件**，既不碰存下来的配置，也不碰列、排序与分页；
- Enter 提交排除 IME、修饰键、内部弹层与本身就吃 Enter 的控件，由 UI 层处理（[ui/README.md#filterpanel-的布局](ui/README.md#filterpanel-的布局)）；
- applied 读 result.own.filter（描述产出当前结果的、视图自有的那部分条件，无结果为空；路径因此仍指向 draft，badge 的删除即 clearValue(path)），Dashboard 例外：它自己没有结果（查询在各面板里），改读 state.applied.filter，即面板被要求执行时的全局条件；
- 宿主注入的作用域由 scoped 读 runtime.scopeFilter 单独描述、不可删除——读 result.config.filter 会把作用域混进同一串 badge，且 or／nor 的 draft 被包成第一个子节点后路径整体下移一层，删除会落到别的叶子上；
- 草稿超预算不清空 applied：产出结果的那份配置是先过准入才跑的，本就在预算内，摘要要一直描述它身旁的数据；
- pending／pendingCount／isPending(path) 以 state.applied 为基准（叶子比字段＋操作符＋值，分组只比 op，不比子节点）；
- pendingCount 同时走两棵树，只在 applied 里的路径也计一次（删掉一条、清空筛选同样是未应用的改动）；
- 草稿超出树预算（filter.tree.too-deep／too-many-nodes）时这三者一律为 false／0——面板本就不画它，比较也不走它（applied 不在其列，见上）；
- 超预算同样按路径认领（根路径或 `['children', …]`，与 issues 同一组，同一个判断：`filter/issuePath.ts` 的 `isRootFilterIssue`），分析的指标／元素筛选与仪表盘面板筛选报的是同样的 code、只是重定址到 `['metrics', …]`／`['elements', …]`／`['panels', …]`，只看 code 会让根编辑器为一棵它不画的树关掉 pending；
- 比较本身是迭代加计数的，过深或成环的草稿返回 false 而不是爆栈；
- blocked 是落在条件上的 error 条数——包括编辑器画不出 pill 的那些（畸形节点），它们同样阻塞 Apply，只是由状态条而不是 pill 报出；
- unmarked 与 blocked 成对：blocked 数的是 pill 上那些（外加画不出 pill 的），unmarked 给出的是**没有一处标记**的那些——畸形节点、分组自身的 error，以及配置里条件以外的 error（掉了的列、不再受理的页大小）。两者合起来把"拦住视图的每一条 error"交代完，各说一次、互不重复：pill 一份，编辑器上方的状态条一份。判断由 `filter/marks.ts` 的 `unmarkedErrors(issues, tree)` 做，它只问树——哪些路径解析得到一条渲染得出的条件（谓词内部的条件也算）——所以它属于内核而不属于编辑器；unmarked 走的是整份 `state.issues` 而不是被本树收窄过的 `issues`。（见 test/useFilterEditor.test.tsx「useFilterEditor」「useFilterEditor pending and applied」「useFilterEditor under a host scope filter」与 test/statusStrip.test.tsx「ErrorStrip」）

## useValueCandidates

```ts
useValueCandidates(source, query, active): ValueCandidatesController
// { status: 'idle' | 'loading' | 'success' | 'error', query, values, complete, reason, retry() }
```

一个文本条件的值从数据里挑：`FilterTreeController.valueCandidates(path)` 交出那一格的 `ValueCandidateSource`（由 `useFilterEditor` 从 `runtime.valueCandidates(field)` 接来），**只在操作符比的是整个值**（`EQ`／`NE`／`IN`／`NOT_IN`）、kind 为它画的是文本框时才给——子串、区间、不要值的操作符都是 `null`；嵌套谓词与指标条件的控制器不带它，它们的字段是元素的，视图的数据数不出来。

- **打开才问**：`active` 为假时什么也不问，一个摆着十几个条件的面板画出来不该花十几次聚合；不带字的那份立即问，打的字**停手 250 毫秒**（`VALUE_CANDIDATE_DEBOUNCE_MS`）才问；
- **每次变化中止上一次**，关掉列表（`active` 转假）也中止；晚到的答案丢掉；
- **答案按源保存**：换了字段就是另一份列表，上一个字段的值不会在它下面闪一下（状态以源为键推导，不在副作用里同步）；
- `query` 是 `values` 回答的那段字——与眼下打的不同时，新答案还在路上，控件先在手里的那份上缩，列表跟着键盘走而不是跟着网络；
- 失败保留上一份，`reason` 是 `sourceReason` 读出的源自己的话，`retry()` 再问一次。界面规则见 [ui/README.md#filterpanel-的布局](ui/README.md#filterpanel-的布局)。（见 test/useValueCandidates.test.tsx「useValueCandidates」「the filter editor offers value candidates」）

## 控制器是边界

**控制器交给 UI 的列表，永远是可安全遍历、成员格式良好的列表**，无论存储里放的是什么。配置来自存储：`sort` 可能是一个对象、一个字符串，或一个含 `null` 的数组，`summaries` 同理。准入会报出形状（`record.sort.invalid`、`record.summaries.invalid`）、草稿因此停在错误态——但**能让用户删掉那一条的编辑器，正是从这同一份草稿渲染出来的**，一次 `.map` 落在字符串上就把工作台带走，用户根本够不着那一条。所以归一化发生在控制器这道边界上（`src/react/recordDraft.ts`），面板不再各写一遍防御性读取，下一个人写的面板也自动继承这条保证。

整份列表读不出来时（`sort` 是个字符串），它归一化成空列表——于是那些条目一个都不在屏幕上，也就没有任何控件能删掉它们，而准入仍在报 `record.sort.invalid`。所以控制器的**任何一次写入都顺带把读不出的列表按归一化结果写回去**（`editAndApply` 里的 `repairing`）：用户改一个列，那个曾经是字符串的 `sort` 就变成他们看得见的列表。否则定义里一个可排序字段都没有时，那份 `sort` 根本没有出路。

读不出来的条目就地丢掉，这本身也是修复路径：用户编辑的是那份读得出的列表，他们做的第一个改动就把它写回去，那条从来读不出的条目随之消失，准入也就不再报了。方向这类**值**的问题则不丢条目而是取默认（`'DESC'` 以外读作升序）——字段是用户选的，方向是他们看得见之后一按就能翻的那一半。

（见 test/recordTableCommands.test.tsx「the shapes a store can hold」，其中一条直接拿真实控制器把 `ResultToolbar` 渲染出来。）

**命令先算清楚要写什么，这一步里没有 React**：表头一按走完的那个循环（`cycledSort`）、每页条数那把梯子（`offeredPageSizes`）、"没有汇总"该按哪种写法作答（`summariesOf`）、以及上面那段的 `repairing`，都住在 `src/react/recordEdits.ts`；列表本身的那几个——重建一列（`recordColumn`）、固定／宽度／开关（`repinned`／`resized`／`shown`）、整表开关（`withColumnsShown`）与整表重排（`reordered`）——住在 `src/react/recordColumns.ts`。两份都是纯函数：`useRecordTable` 只剩 `edit` 加 `apply` 的接线，而这些规则各自一条断言读得出来，不必先渲染一个 hook 再造一个 runtime。（见 test/recordColumns.test.ts）

## useRecordTable

```ts
useRecordTable(runtime): RecordTableController
```

- 列语义、排序、列宽列序、选择、分页；
- 无 TanStack 类型；
- layouts 为定义允许的布局，selectedRows 为当前结果中被选中的行（结果顺序），pageSizes（梯子来自 `runtime.limits.pageSizes`，卡片布局来自 `cardPageSizes`，切换布局时条数跳到另一套梯子最近的一档；按 `maxPageSize` 裁短并折进当前每页数；自动刷新的梯子同理来自 `limits.refreshIntervals`）为可供选择的每页条数（标准档位按 runtime.limits.maxPageSize 裁剪，并并入当前值）。（见 test/useRecordTable.test.tsx「useRecordTable」）
- `toggle(key, { range })`——勾选一行；`range: true` 是 Shift 连选：从锚点（上一次平点落在的行）到这一行、按结果顺序整段跟着这一行走（选中或取消）。锚点记在控制器里而不在运行时，连选不挪它；它连同这批行是哪个问题的哪一页一起记（`recordSelection.ts` 的 `RowsMark`），换页或应用了另一个问题就作废，同一页的刷新留着；没有站得住的锚点时 `range` 就是一次平点并落下锚点。`toggleAll` 与 `clearSelection` 不动锚点。规则见 [ui/record.md#勾选shift-连选一段](ui/record.md#勾选shift-连选一段)。（见 test/rangeSelection.test.tsx「lets the anchor go on another page, whose rows are another set」「adds only the rows not yet held, in result order」）
- `hasResult`——这个视图**是否曾经拿到过结果**（`state.result != null`），哪怕它已经过期。它不是 `rows.length > 0`，也不是 `status === 'success'`：失败的刷新会留住它替换不掉的行并转为 `error`，匹配零行的成功结果则根本没有行。判断只有一处（`runtime/viewRuntime.ts` 的 `hasResult`），控制器、结果条件带与结果块问的是同一个函数。表格靠它区分"结果是空的"与"从来没有结果"——后者连列都没有，画出来是一格空表头加一个选不中任何东西的「选择全部行」（见 [ui/record.md](ui/record.md)）。
- `hasAsked`——更宽的一问：**是否已经向数据源问过**——有结果、查询在途、或上一次失败。围着结果的那圈 chrome（结果条件带、分析结果的工具栏）从问出去那一刻就有真话可说，所以问的是它：只等有结果才画，它们会在第一批行落地时一起冒出来把结果推下去，第一次就失败时只剩一条失败。问出去的查询都已通过准入，所以此时的 `applied` 可以被描述、被投影。被拒而没跑的配置不算问过——那时说话的是状态行。`useFilterEditor().applied` 在没有结果时据此改读 `state.applied.filter`，`useAnalysisResult` 据此给出 `question` 与它的列（见 [ui/analysis.md](ui/analysis.md#结果的三种等待态加载失败没有组)）。

改动配置的命令一律是一次 `edit` 加一次 `apply`，与既有的 `toggleSort`（`toggleSort(field, { exclusive })`：默认追加，`exclusive` 时这一列就是整份排序——表头平击走它，Shift 追加走默认）／`setColumns` 同一条路径：表格画的是内核按**执行时**的配置投影出来的列与行，不重跑就看不到改动（筛选则等提交）。列设置与排序控件（[ui/record.md](ui/record.md)）所需的那几条：

- `setColumnOrder(fields)`——按给定顺序重排草稿的列，**关掉的那些也在内**（它们有位置，正是这个位置让它们勾回来时回到原处，所以要整表重排就得连它们一起点名）。不是列的名字忽略，重复的名字只算一次（否则会落成同一字段的两列，`validateRecord` 随即拒绝），**没被点到名的列保留在末尾**：只了解表格一部分的控件（列设置的一个区域）不该因为没提到其余部分就把它们删掉；每一列按原样搬运，宽度与固定不会在下次保存时丢失；
- `setLayout(layout)`——表格还是卡片。两种布局画的是同一份结果，所以平时只 `edit` 不 `apply`；但保存的布局已不在定义允许之列时，`apply` 在打开时就被拒、`refresh` 在有东西被准入之前是空操作，此时这个切换如果只 `edit`，屏幕修好了也还是空的——所以**什么都没跑过时**（`result === null` 且查询 `idle`）它顺带 `apply` 一次；
- `cardSpec`／`setCard(patch)`——卡片那一半配置（草稿里的那份，与 `columnFields` 读草稿同理）与改它的命令：标题字段、正文字段、图片、每行几张，任一项都是一次 `edit` 加一次 `apply`，卡片按跑过的配置画（[ui/record.md](ui/record.md)）；
- `setColumns(fields)`——表格显示哪几列。**配置已经认识的列是就地开关**（`hidden: true`／删掉这个键），不是加进列表或从列表里删掉：一列关掉之后仍留着自己的那一格，勾回来就回到原处而不是排到末尾（D17-8，见 [ui/record.md](ui/record.md)）。**因此它不管顺序**——顺序是 `setColumnOrder` 的事，而一个不在列表里的字段本来也没有顺序可给；配置还没提到过的字段接在末尾。**被关掉的列的汇总在同一次写入里一并删掉**：汇总属于列，留下的那条只会换来一次没处可画的聚合；留下的列照旧按原样搬运，宽度与固定关掉也不丢。**两种条目反而是删掉**：定义里已经没有的字段，以及同一列的第二条——它们没有可回来的位置，而且正是内核拒绝这份配置的理由（`record.field.unknown`／`record.column.duplicate`），留成隐藏等于让刚按下的那个控件把查询与保存继续挡着；
- `hiddenOf(field)`——草稿里这一列是不是关着的。读的时候走 `columnHidden()`（只有 `true` 算关掉），所以旧配置与存进来的怪值都读作显示；
- `pinnedOf(field)` / `setPinned(field, pinned)`——草稿有没有把某列固定在左侧，一个布尔（D19：固定没有"侧"，右边那一列是操作列）。读的时候走 `columnPinned()`，所以存进来的 `'left'` 或 `'top'` 读作"不固定"，签名声明的类型对不可信配置也成立。取消固定时删键而不是写 `false` 或置 `undefined`（理由见 [ui/record.md](ui/record.md)）；
- `setColumnWidth(field, width)`——某列的像素宽度，`null` 为恢复自适应。与 `setPinned` 同一条路径（`edit` 加 `apply`），同样**删键而不是置 `undefined`**，其余成员（固定）原样留下。数字按给的数写：一列最窄能拖到多少是关于手柄的问题而不是关于配置的问题，所以下限住在 `ColumnResizer` 里（[ui/record.md#列宽拖表头的右边](ui/record.md#列宽拖表头的右边)）；内核拒绝的是**不是正有限数**的宽度（`record.column.width-invalid`）——`width: 0` 画出一列谁也看不见、也再抓不住的列，手写 `"120px"` 进来变成的 `NaN` 则落成一条浏览器直接丢掉的内联样式，列还是老样子而配置声称不是；
- `summaryOf(field)` / `setSummary(field, fn)`——某列底下汇总用的函数。**删掉最后一条时按已保存配置的写法还原**：`summaries` 是可选成员，"没有汇总"有两种写法（空数组、没有这个成员），而 `dirty` 是与已保存配置的一次相等比较——分不清换了写法和改了内容。加一条又删掉，视图会就此一直显示"未保存"、离开守卫还会问一句用户早就撤销过的改动。`ViewRuntime.edit` 因此把值为 `undefined` 的成员**删掉**而不是置为 `undefined`（配置是 JSON，没有这个成员与成员为 `undefined` 是同一份配置、却不是同一个对象），控制器则按已保存那份的写法作答；配置允许一个字段带多个函数、表格也全画出来，而这条命令写**一个**：控件一列只给一个下拉，设一个就替换掉该列原有的，`null` 则该列不汇总，其余列不受影响；
- `paging`——分页条读的事实，全部出自内核（`record/paging.ts`）：跑过的那一页与**跑过的那个每页条数**、够得到的页数（声明了 `maxWindow` 时只数窗口里的）、`hasNext`、窗口截短总数时的 `reachable`。`pageSize` 是草稿里的那个，只给每页条数的选择器用；新每页条数还在路上时，分页条仍按旧的那一批算，不拿草稿去除结果的总数。`hasNext` 就是 `paging.hasNext`，`goTo` 越过够得到的最后一页时落在那一页（`clampPage`）；
- `card`——卡片布局与列一样由内核投影（`projectRecord` 的 `card`），控制器不再自己对着定义解析一遍字段；`rowKey`／`fieldGroups` 由控制器转交，列设置不直接读定义；
- `maxSortFields`——这个视图一次最多按几个字段排序：游标源取 Wow 的上限减一（查询以行键收尾，占掉一格），分页源取定义里字段的个数。规则在内核（`record/maxSortFields`），控制器只转交，所以"控件停在哪"与"内核从哪开始拒绝"是同一个数；
- `setSort(sort)`——整份排序按优先级顺序替换。`toggleSort` 是单列的答案、只能往后追加，而把排序当作一张列表来编辑要能说清谁先谁后、翻转其中一条、删掉其中一条，三件事是同一次写入。（见 test/recordTableCommands.test.tsx）

## useRecordDetail

`useRecordDetail(runtime)` 管一条记录的详情：`open(row)` 立即以这一行在页上的数据打开（`record`，`complete` 为假），同时 `runtime.fetchRecord(key)` 取整条，到了就换上；`loading` 按「这次读取回答的是不是最新的一次结果」推导，而不是在副作用里同步置位；视图每落地一次新结果（`state.result.receivedAt`）就重读一次，被更新的读取或关闭顶掉的旧读取丢弃。失败是 `error`（经 `sourceIssue`，说数据源的原因），记录已不在是 `missing`；`sections` 是 `detailSections(definition)`。界面规则见 [ui/record.md#记录详情把一条读全](ui/record.md#记录详情把一条读全)。

## useRecordExport

```ts
useRecordExport(runtime, table, { deliver }): {
  scopes: { selected?: number; all: number | null };
  running: 'selected' | 'all' | null;
  progress: { scope; fetched; total? } | null;
  outcome: { scope; rows: number; capped: boolean; total? } | null;
  error: Issue | null;
  run(scope, fileName): void;
  cancel(): void;
  reset(): void;
}
```

- **两种口径各带条数**，条数就是这两项唯一的区别：`selected` 只在有选中时**存在**（D4——"导出选中的 0 条"是一个点了不做事的条目），`all` 是分页源报出的总数、报不出时为 `null`（游标源没有总数，与其猜不如不说）。**没有 `page` 了**：「本页」是分页留下的痕迹而不是一个意图——结果只有一页时它就是「所有」说了两遍，不止一页时它是被排序与每页条数切出来的一刀，谁也没要那一刀；要取样是表头全选加「选中」（D14）；
- **选中那一路的行已经在手上**（就是当前结果），直接交给 `deliver`；`all` 走 `runtime.exportRows`（[runtime.md#导出](runtime.md#导出)），因此翻页、选择与屏幕上的行都不受影响；
- **`deliver` 由 `/ui` 注入**：值怎么读、文件怎么交给浏览器都是那一层的答案，钩子只负责把行凑齐。它抛出来的错与拉取失败同样处理——从用户那边看"导出没成功"是一件事而不是两件；
- **文件名由窗口定、钩子原样捎带**：`run(scope, fileName)` 收的就是窗口打开时已经承诺出去的那个名字，`deliver(rows, scope, fileName)` 拿到的是同一个。钩子自己不算名字——名字里带着一个日子，而一次导出可能跨过午夜，答应的与交出去的那一刻各算一次，就是两个文件名（D14，见 [ui/record.md#导出](ui/record.md#导出)）；
- **它不问任何问题**。从前那一句「超过上限了，还导吗」现在是窗口的第一步：条数与上限在按下按钮之前就摆在眼前，按下去**就是**那句同意，钩子再问一遍等于问两次（D14）。因此没有 `overLimit`，也没有 `run(scope, { force })`。条数不知道时本来就没有可问的，跑完由 `outcome.capped` 说明文件被截断；
- **`outcome` 是这次导出交出了什么**：口径、文件里几行、有没有被上限截断、以及总共匹配多少（源报得出时）。窗口的「已导出 N 条」「文件只含前 {max} 条（共 {total} 条匹配）」都是从它来的——截断不是失败，是被同意过的那份文件，所以它不走 `error`；
- **一次只跑一个**：在途时 `run` 不接第二个（以一个 ref 把关，因为选中那一路不发请求、没有可当闸门的 controller），`cancel` 停掉在途的那个，`reset` 只是忘掉上一次的结果或失败、不动在途的那个——关窗时用它，好让下次打开是重新问一遍而不是把读过的结果再报一次。取消不报错——它是用户自己的答复。失败经 `react/issues.ts` 成为一条 `export.failed`，由导出窗口自己说出来（[ui/record.md#导出](ui/record.md#导出)），结果区上方的状态行里不再有导出的事。（见 test/useRecordExport.test.tsx）

## useSaveCommands

```ts
useSaveCommands(engine, runtime): { save; saveAs; rename; delete; revert; retry; abandon; resolveConflict; can; state }
```

- can 增 revert（dirty 且已保存过）；
- state 增 blocked（pending／含 error／write 为 unknown——冲突与拒绝是确定的答复，其可选解法里含"另存一份"这类新意图，不应被禁用；需要在冲突时拦住盲目 Save 的 UI 自行查 state.write）与 lastSavedAt（最近一次真正写入成功的时间戳，按 environment.now()，新写入开始即清空）；
- retry 与 resolveConflict 兑现 `RecoveredWrite { landed; written; instance }`，landed 说"该结局已结清"，written 说"确实把这个视图存下来了"——只有正文 action 为 create／save 的恢复才算（取调用当时 state.write.payload.action）：冲突选 reload 是取服务端状态并丢弃草稿，什么也没写；
- 改名与删除的重试或覆盖确实写了 store，但写的不是屏幕上这份配置，报"View saved"等于告诉用户未保存的编辑已经安全。两者都 landed 为 true 而 written 为 false，因此都不计入 lastSavedAt；
- 只有 create／save 的重试与覆盖两者皆真；
- state 另有 hasErrors（只说草稿自身有没有 error，不说任何结局，因此调用方可以自行决定拦住哪一种未结清的写入）；
- abandon(write?) 可按传入的 WriteState 以 handle 寻址——冲突里"另存一份"之后该写入已不再由 runtime 报告，engine 却仍在其 map 里记着它；
- 结局词汇取自 `src/react/writes.ts`（见下），`blocked` 里"结局挡不挡新意图"这一项即 `blocksNewIntent`；命令走 `manager/queue.ts` 的队列、以 runtime 打标，因此同一视图至多一个写入在途，换视图即另起一条队列。（见 test/useSaveCommands.test.tsx「useSaveCommands」）

## useViewManager

实现拆在 `src/react/manager/`：`outcomes.ts`（按 key 成图的结局账本；归属与替换的规则本身在 [writes.ts](#writests)，从那里直接取用）、`queue.ts`（按输入打标的串行队列，React 无关，`useSaveCommands` 同走这一份）、`order.ts`（同受众组内的位置与乐观顺序）、`abilities.ts`（许可投影）、`useCommandRunner.ts`（结局账本与队列的 React 一侧）；`useViewManager.ts` 只做组合。公开面只从 `/react` 入口导出。

```ts
useViewManager(engine, definitionId, list): { rename; delete; setDefault; moveTo; placeOf; outcomes; retry; abandon; resolveConflict; resubmit; canResubmit; pending; can }
```

- can 另有 anything（顺序、默认，或任一行的改名／删除中有一个可用），为假时工作台根本不把 manager 交给 ViewList——管理入口通向一屏只读的行，就是一个只能教人它通向哪儿也不去的按钮；
- 管未打开的实例：命令一律以状态兑现，成功即 list.reload()；
- outcomes 与 pending 按 engine＋definitionId 打标（同 useViewList 的"手上的答案属于哪一次请求"），换定义或换引擎即读作空，旧输入的完成不再回填；
- 命令按 ref 里的 promise 队列串行，同时至多一个写入在途，pending 恒是它的 key——队列本身也按同一组输入打标，换定义或换引擎后的命令另起一条队列立即开始，旧队列独自结清且结果无人读取，否则新列表的第一条命令会排在一个没人看的挂起写入之后；
- outcomes 按实例 id 或 PREFERENCES_KEY（`'system:preferences'`，取 store 不得签发的 `system:` 保留命名空间，避免与实例 id 撞键）记结局（ViewWriteError 的 state，handle 私有，供三个恢复动作寻址），引擎在发出前拒绝的记为 rejected 且无从重放——但拒绝绝不覆盖仍持有 handle 的结局，否则 [management.md#冲突与未知结果](management.md#冲突与未知结果) 的"unknown 未结清时拒绝新意图"反而会把该 unknown 的重试与放弃一并抹掉；
- 同一 key 的结局仍持有 handle（unknown 或 conflict）时，新意图根本不入队（直接兑现 false，并在队首再查一次——排在前面的命令可能正好把这个 key 变成 unknown），只有该 key 的 retry／abandon／resolveConflict 放行：一行只有一个结局槽位，新命令记下自己的结局就会顶掉那个 handle，被它寻址的写入从此留在 engine.pendingWrites() 里而界面上无人能重试、覆盖或放弃它——unknown 是 Engine 直接拒绝，conflict 则是 Engine 照发不误、一步之后才出同样的问题；
- rejected 不持有 handle 或只持有一个已成定论的答复，[management.md#冲突与未知结果](management.md#冲突与未知结果) 的"改正后作为新意图再保存"照常放行；
- moveTo(id, index) 把一行放到**它自己那一受众组内**的某个位置，`index` 按可见列表里该组的行计数（列表分个人／共享两组展示，跨组移动写了偏好却什么都没动，所以根本没有一个下标表达得出它）；越界的下标被夹到组内，送到它已经在的位置则不写，一律兑现 false；
- 提交的却是 `list.all`（未经 kind 过滤的完整顺序）里把该组占的那几个位置按移动后的组序重写后的结果，store 一个定义只存一份 order，提交可见的那份会把工作台没画的种类整批抹掉；
- moveTo 还在 ref 里记住已提交的乐观顺序（完整顺序与移动后的可见顺序各一份）——列表要等落地后的 reload 才追上，同一行连动两次否则第二次会被送到第一次已经把它放到的那个位置（同组的 `[A,B,C]` 中 C 连续上移两次提交 `[A,C,B]` 与 `[C,A,B]`）——算位置走它记住的可见顺序，重写走它记住的完整顺序，两份必须出自同一次移动；
- 列表身份一变（reload 已落地）即回到渲染顺序，未落地的 moveTo 也把它撤回；`placeOf(id)` 读的是同一份「此刻的顺序」，因此**要在下手那一刻问，不要在渲染里读**——排队中还没落地的那一次移动屏幕上看不见，而下一次移动必须用它那一份的下标；
- 偏好冲突按 [management.md#列表偏好与默认视图](management.md#列表偏好与默认视图) 重载后保留本次意图待再次确认（`canResubmit` 为真，按 `resubmit` 以刚读回的 revision 重新提交，它同走这条队列，且因为是一次新写入而照样受未结清守卫约束），改名／删除冲突按 [management.md#冲突与未知结果](management.md#冲突与未知结果) 推进基线后清除；
- can 取自 list.permissions，系统视图恒不可改名、删除。（见 test/viewManager.test.tsx）

## writes.ts

`src/react/writes.ts` 是"一次写入结局"的唯一词汇，纯函数、不含 React，两个钩子共用一份定义，并且**在 `/react` 入口上导出**——自己画保存命令的宿主同样要知道"引擎还在为哪一次写入负责"，不摆在入口上，每个宿主都会把这条规则再推一遍，而这个模块存在就是为了不再有第二份，[management.md#冲突与未知结果](management.md#冲突与未知结果) 那张表因此只被实现一次：`settle(caught, code, intent)` 把抛出的命令变成 `{ state, handle }`（`ViewWriteError` 交出自己的结局与 handle，其余一概没发出去，记为引用意图的 `rejected` 且无 handle）；`recovered` / `UNRECOVERED` / `RecoveredWrite` 是恢复动作的答复，`savesView(action)` 说这次恢复算不算把屏幕上这份配置存下来。

粗的那一问是 `unsettled(state)`：引擎还在为这次写入负责吗——`conflict` 与 `unknown` 都是（一个等用户选，一个等重试或放弃），`rejected` 不是（store 根本没收）。两个钩子只差在手里攥着几个结局，这个差别写在函数名里，不在几份重复的判断里：`blocksNewIntent(state)` 是已打开 runtime 的规矩——只有 `unknown` 挡新意图；`holdsHandle(outcome)`（管理器里叫 `blocks`）是一行一个槽位的规矩——就是 `unsettled` 再加上那个 handle，`conflict` 因此同样挡，新命令占位就会把它的 handle 丢掉；`strandedHandle` 给出新命令该先结清的那个 handle，`mayReplace(existing, incoming)` / `mayRefuse` 说什么样的结局可以顶掉槽里已有的。

**这几条不在别处重写**：`leaveGuard` 的「走了会丢什么」第二项问 `blocksNewIntent`，`manager/outcomes.ts` 的 `kept` 问 `holdsHandle`，`/ui` 的 `SaveActions`（保存按钮组与「已修改」标记上的还原）问 `unsettled`——三处从前各自把 kind 再列一遍。`manager/outcomes.ts` 只留下按 key 成图的那部分（`PREFERENCES_KEY`、`Outcome`、`kept`、`withOutcome`、`projectStates`），不转出任何规则——一份定义，一条 import 路径。（见 test/writes.test.ts）

## useAnalysisEditor 与 useDashboard

```ts
useAnalysisEditor(runtime): AnalysisEditorController
useDashboard(runtime): DashboardController
```

- `AnalysisEditorController` 改一条指标有**两个**动作，差别是形态变不变：`updateMetric(index, patch)` 打补丁，只用在同一形态里换一个数（百分位那个数）；`replaceMetric(index, metric)` 整只换掉，用在**换汇总方式**——合计变成去重计数、再变成任一值，那是换指标类型，补丁会把上一形态的 `function` 或 `expression` 留在对象里让准入绊倒。别名由调用处带进新指标：它是查询的名字，图表与排序都指着它。维度那边同理，`groupOfType` 造一个完整的维度交给 `updateGroup`；
- **取消一个设置写的是删键，不是 `undefined`**：`renameGroup(index, label | undefined)`、`renameMetric(index, label | undefined)`（显示名）、`setMissingBucket(index, on)`（`missingKey: DEFAULT_MISSING_KEY` 或整个键不在）、`setDense(index, on)`（`dense: true` 或整个键不在）都走 `model/json.ts` 的 `without`。配置是普通 JSON，一个挂着 `undefined` 的成员是任何新建配置都不会长成的样子，`comparePending` 的比较也会为它多报一次「改过没应用」；
- `sortNow(sort)` 是**结果表头那一下**（`ui/analysis/headerSort.ts` 的 `useHeaderSort` 调它）：写进排序，草稿里没有别的待应用修改就当场 `apply`，有就只并进待应用、交回 `false`——跑就连那条修改一起应用了，那不是这个手势的事，与 `useAnalysisResult` 另问整体、`choose` 修漏斗同一条规矩。「别的」按把这份排序同时放到草稿与已应用两边再 `comparePending` 来判，所以托盘里改过、还没跑的排序不拦它：这一下正是替换它。`setSort` 只写不跑，托盘用它；
- `dateUnitFor(field)` 是**新时间维度从哪个粒度起步**（K4）：先读已应用范围在这个字段上圈出的跨度（`rangeSpan`），没有就读现有结果的桶（`resultSpan`），再没有就是字段的第一个单位；推荐规则本身在内核里（见 [kernels.md#粒度推荐k4](kernels.md#粒度推荐k4)），控制器只负责把「范围优先于结果」这个顺序说清楚。它只播种，手选过的单位永远优先；
- `AnalysisFieldOption.missingKey` 是「这个字段担得起哨兵桶吗」（`isSingleStringField` 加运行时的 kind 注册表），托盘据此决定那一项出不出现、新维度带不带哨兵；`AnalysisFieldOption.cell` 是字段的读法（`cell ?? kind`），汇总方式与提到指标的地方据此把日期的 `MIN／MAX` 说成「最早／最晚」，公式据此不把日期当操作数；
- `AnalysisEditorController.moments` 是草稿里**是时间点的指标**（`momentMetrics`，日期字段的最早／最晚／百分位／任一值）：「只保留」与派生指标不列它们，`addDerived()` 跳过它们，`reshape` 与 `setChartType` 把它们交给 `fitChartSlots`——量的槽不放时间点（[kernels.md#时间的最早与最晚](kernels.md#时间的最早与最晚readsasitsfield-与-momentmetrics)）。结果那一侧（`useAnalysisResult`）从投影出来的列读同一件事：一列指标读作日期，它就是时间点；当前图型因此画不了时交出表格（`picked === 'table'`、没有 `chartData`），而不是一张空框；
- **指标自己的条件**（D20 屏 H）：`conditionFields` 是这种条件能指名的字段——作用域里 `kind.scalar !== false` 且不是 fieldless 的那些，因为指标条件是逐条记录判「这条算不算」，数组与全文检索拿不出那一个值；`kinds` 与 `optionSource` 让托盘就地拼出一个跟范围一模一样的条件编辑器（`treeController`），而不必把 runtime 传进卡片。`setMetricFilter(index, tree | undefined)`：空树留着（"写了但没写完"，准入据此让查询等着），`undefined` 才是删键；`duplicateMetric(index)` 把复制件插在原件后面并**交出它的别名**（规则本身是内核的 `metricWithCondition`），因为托盘接着要把那张新卡的条件块打开——菜单那一项叫「复制并加条件」，交出一张什么也没开的卡片不算许了这个条件。别名而不是下标：它是卡片的身份（React 的 key 也是它），下标会被后来的增删挪动；
- **展开链**（D20 屏 G）：`elements` 是在跑的链，`expansible` 说能力声明了链没有（槽在不在），`expandable` 是声明出来的下一步（`{ path, label }` 或 `null`），`unit` 是现在数的是什么——最内层那一层的显示名，没展开就是定义自己的标题。`elementLabel(i)` 与 `elementFields(i)` 是第 i 层的名字与它的门认得的字段；`expand(path)` 与 `collapse(i)` 都走内核的 `withElements` 重新划范围，所以它们跟加减一个维度一样过 `reshape`，图表、排序与表列一起跟上；`setElementFilter(i, tree | undefined)` 与指标那一个同形，`undefined` 把这一层的 `filter` 键删掉、留下光秃秃的 `{ path }`；
- **写出来的指标与「只保留」也是能力说了算**（D20 屏 B）：`expressionsAllowed` 与 `havingAllowed` 直接读能力的 `expressions` 与 `having`，控件据此存在或不存在（D4）。`addFormula()` 在这个作用域**能度量的头两个字段**上造一条公式（只有一个字段就用它两次——一张填不满的卡片不该被端出来），`addDerived()` 在**已有的头两条非 `ANY`、非时间点的指标**上造一条派生指标，公式的头两个字段也跳过日期；两者都走 `reshape`，所以新别名的列、槽与排序一起跟上。`having` 不是配置里那棵表达式而是 `havingRows` 读出来的那几行——**读不出来就是 `null`**，界面据此把「说不出口」与「什么都没写」分开（`[]`）；`setHaving(expression | undefined)` 走的是 `edit`，`undefined` 删键而不是留一个 `undefined`。`ranHaving` 是另一回事：**结果跑的那份配置**上的 having 原样（取自 `state.result.config`，没跑过或没有就是 `undefined`），给结果第一行的读法说「只保留……」用——读法说的是屏幕上那些数，所以不读草稿（2026-09-23 审查 P0-2，见 [ui/analysis.md](ui/analysis.md#结果第一行读法与看法)）。规则都在 `react/analysisEditing.ts`，钩子只剩接线；
- **`stale`**（「改了就跑」，D20）：屏幕上这些行答的是上一个问题，而草稿马上就要自己跑了——它就是 runtime 那一条 `autoApplyDue(state)`，控制器不另算一份。开关关着、草稿有 error、改的是范围，三种情况下它都是 false（那几条同时也是"不会自己跑"的理由，见 [runtime.md#改了就跑](runtime.md#改了就跑)），所以它说的始终是「有一次运行在路上」而不是「有改动没应用」——后者是 `pending`，那是应用按钮上那颗点的事。结果据此画淡而不是清空（[ui/analysis.md#托盘范围--维度--指标--结果一个应用](ui/analysis.md#托盘范围--维度--指标--结果一个应用)）。（见 test/autoRun.test.tsx「改了就跑: an analysis runs as it is edited」）
- `DashboardController.loading` 是「任一面板的查询在途」，由控制器自己订阅各个子 runtime 得来：仪表盘不跑自己的查询（`state.query` 恒为 `idle`），而子 runtime 的查询变化**不会**通知仪表盘的订阅者——那是有意的，否则每个面板每次请求都要让整张栅格重渲染——所以栅格之外还要知道这件事的控件（刷新按钮）只能由这里代为订阅。它与 `DashboardViewRuntime` 自己的计时器开火前问的是同一件事。

两者的界面规则见 [ui/analysis.md](ui/analysis.md) 与 [ui/dashboard.md](ui/dashboard.md)。（见 test/analysisTray.test.tsx「swaps the whole metric when the summary changes」、test/analysisCards.test.tsx「a display name」「the sentinel bucket」「the granularity a new time dimension starts at」、test/metricCondition.test.tsx「a metric’s own conditions」与 test/elementsSlot.test.tsx「the expansion slot」）

## useAnalysisResult

`useAnalysisResult(runtime, analysis, workbench)` 是分析结果那一半的控制器（阶段 2 审查·研发 #9）：跑出来的是哪批行（`view`）、它们按哪份配置跑的（`ran`）、画哪张图（`chart`）与图的数据（`chartData`）、可视化面板上哪些图型可选（`fits`）与选了哪个（`picked`、`choose`），以及按下一组之后能做什么（`followUp(row)`）。它读内核、写运行时；画它的组件（`ui/workbench/AnalysisParts.tsx`）只留屏幕上的事——哪一层面板开着、键盘在哪、按下的是哪一行和按在哪。`/ui` 的入口写着「每个组件只消费 `/react` 的控制器」，这一条从前在分析结果上不成立：组件自己调八个内核函数、直接 `runtime.edit` 再 `apply`，换一套界面就要把追问与可视化重写一遍。

- **行读跑出它们的那份配置，怎么看读草稿**：草稿与跑出来的形态不一致时（托盘里加了还没跑的维度），图表规格先经 `fitChartSlots` 落到跑出来的形态上；一致时原样画——`fitChartSlots` 会把作者故意收窄的槽重新放开，只在形态真的变了时才该这样做。
- **`choose` 在有行时只重画不重跑**：布局与图表是呈现成员（D20）；选一个图型时顺手按屏幕上的行填好槽、给按组分阶段的漏斗排好阶段，于是选了立刻画得出。**没有行时它修配置并跑**：一个存下来图被拒的视图什么也没跑过，没有行可重画，于是按草稿的形态装槽（`setChartType`），视图合法了就 `submit`；选表格时若图被拒，图换成这个形态推荐的（或第一个画得出的）图型，因为图在表格布局下照样校验。草稿里另有待应用的改动时不替它跑。
- **`fits` 带着行判**：有行时是跑出来的形态加上那批行（按维度分阶段的漏斗靠行填阶段），结果区的 `drawable` 与图型网格读的是同一份；没有行时是草稿的形态加上草稿的图（漏斗只有它已点名的阶段）。
- **追问是一张动作列表**（`FollowUpAction`：`records`／`split`／`focus`），按这个顺序；`canDrill` 为假时没有 `records`，没有可拆的维度时没有 `split`。菜单按种类配图标与文字，加一种追问就是这张联合类型多一员、`followUp` 多一项，而不是菜单再多一对 props（审查 E4）。条件读的是跑出这批行的配置而不是草稿：按下去的是那个结果的一组。`FollowUp.groups` 按维度交出这一组（`drillGroups`）：那一维在结果里的列（表头与怎么读值，日期桶的宽度在内）、这一行在那一列的值、以及选出它的条件——菜单拿它说出「我按的是哪一组」，日期桶读作那一列画出来的样子。三项开的都是新视图，名字是文案，所以它们带着 `subject`（记录是定义的标题，拆一层与只看这一组是这个视图的名字），名字由菜单说出来（`run(title)`，拆一层是 `run(field, title)`）；`split`／`focus` 把「跑过的配置 + `splitBy`／`focusOn` + 屏幕上的布局与图」交给 `workbench.follow`，不改这个视图、也不在这个视图里重跑。展开了元素的分析没有根条件能说出它的行，`pickable` 为假，行与标记都不可按。（见 test/analysisResult.test.tsx「useAnalysisResult」与 test/drillMenu.test.tsx「the follow-up menu on one group」）

## useAutoRefresh

```ts
useAutoRefresh(runtime): RefreshController
RefreshController {
  interval; chosen; intervals; unsound; setInterval(interval); now(); loading;
  dueAt; remaining()
}
useRefreshCountdown(refresh): number | null   // 每秒重画的剩余整秒
```

- 两个数，一个成员的两个时刻，不是两份状态。`interval` 是 **`applied`** 的 `refresh.interval`——**正在生效**的那一档，计时器读的就是它，所以凭据只能说它（与 `AppliedBar` 读 `result.own` 同一条理由）；`chosen` 是 **草稿** 的那一档——「这个视图被设成什么」「`Save` 会写下什么」，菜单勾的是它，与布局、每页条数读草稿一致；
- 选中即 `edit` 加 `apply`，所以两者通常相等；**只有草稿被准入拒绝、`apply` 落不下去时**才分开，此时 `applied` 那一档仍然是真的（把刷新关掉也一样：什么都没关掉）。合成一个数就会让按钮挂着一个没有东西在跑的节奏。控制器里**没有**与配置并行的第二份状态：那会让配置、计时器与屏幕各说一个数；
- `intervals` 是裁剪后的档位（升序）：梯子 ∩「内核会跑的数」——**整数**且落在 `[minRefreshInterval, maxRefreshInterval]` 内，因为 `validateRefresh` 拒绝小数与越界是同一件事——再并进 `chosen`（同样要跑得起来，否则菜单里没有一项勾得上）。不允许的档位不出现而不是禁用（D4）；`interval` 也照这条读：`applied` 里一个跑不起来的数报 `null`，不冒充节奏。梯子本身只有**三档：30 秒、1 分钟、5 分钟**——半分钟是屏幕不在手底下乱跳的前提下还读得出「实时」的最短一档，五分钟是再长就说不上「自己保持最新」的那一档；一刻钟与一小时没人选、人人要读过去，一个一年问两次的问题不值得摆七个答案。已经保存成别的数的视图照旧并进自己那一档（上一条），所以收窄梯子不会把谁钉住；
- `unsound` 是「准入对这个成员有话说」（`issues` 里路径以 `refresh` 开头的任意一条：缺失、不是对象、小数、越界）。它存在只为一件事——控件据此知道自己**还有事可做**：「关闭」写下的 `{ interval: null }` 是这几种拒绝的通用修法，梯子空时若连菜单都收起来，用户就被钉在一份 Apply 与 Save 都过不去、却没有控件能修的配置上。判断读 `issues` 而不在这里重算，免得控件与内核对同一份配置给出两种结论；
- `now()` 就是 `runtime.refresh()`，一次性的那一下；`loading` 是本视图查询在途。没有开着的视图时全部是空操作，因为工作台在视图还在打开时就已经画出了这个控件；
- `dueAt` 就是 runtime 的 `nextRefreshAt`，原样转手：**倒数的是计时器自己的那个数**，控制器不另算一份。`remaining()` 是「此刻到 `dueAt` 还有几整秒」，按 `runtime.environment.now()` 读，最小为 0（迟到的计时器是 0，不是负数）。它是函数而不是数，因为它答的是「现在」，而控制器一次渲染只建一次、倒计时每秒问一次；它按 runtime 记忆，所以依赖它的定时器不会每次渲染重来；
- `useRefreshCountdown(refresh)` 是那只每秒重画的钟摆，**由画倒计时的那个控件调用**，不是由工作台调用：一张表为了走一位数字每秒重渲一次是这个 hook 存在的理由。它只在 `dueAt` 非空时起跳，卸载即停；每一次读数都来自 `dueAt` 与注入的时钟，所以跳晚了、跳早了、没跳，都只影响什么时候重画，不影响它说了什么。（见 test/refreshControl.test.tsx「useAutoRefresh」与「RefreshControl」）

## useWorkbench

```ts
useWorkbench(engine, definitionId, { kinds, instanceId?, onInstanceChange?, guardUnload?, newView?, onDrilldown? }): WorkbenchController
WorkbenchController {
  kinds; autoRun; setAutoRun(on); list; manager; openId; choose(id); creatable; create(kind); held; canDrill; drill(conditions, title); follow(config, title, conditions); back(); opened; runtime; state; unopenable;
  commands; filter; refresh; leave; onSaved; onRenamed; onDeleted; onRecovered
}
```

一个工作台除了自己的编辑器与结果之外的全部：哪些视图、开着哪个、它说了什么、换一个时会发生什么。默认工作台只在编辑器与结果上不同，装配一模一样，所以装配只写这一处；每种视图的编辑器与结果是一个**注入件**（`ui/workbench/RecordParts.tsx`、`AnalysisParts.tsx`），把自己那一份外壳槽位（`WorkbenchParts`，外壳 props 的 `Pick`）经 render prop 交回，外壳画在它里面。注入件常驻挂载，没开着它那一种视图时交回空槽位——每次切换都先释放一个 runtime 再开下一个，外壳若跟着注入件来去，铺满与侧栏折叠这些「这一屏的姿态」会在中间那一帧丢掉。

- 列表按 `kinds` 收窄后再定默认（`useViewList(engine, definitionId, { kinds })`）：侧栏不提供这一页画不出的视图，默认视图也只在这些里解析。仪表盘定义本就只有 dashboard 实例，收窄对它是恒等；控制器把 `kinds` 原样交出去，外壳据此在没有视图开着时给切换器一张脸（只画一种时）或不给；
- `openId` 是"此刻在开的那个"：显式选择（`choose` 写下的 pin），没有则是列表的有效默认。`choose` 一律经离开守卫，因为切换会释放当前 runtime，未保存的草稿只活在里面；
- **`instanceId` 是受控的**，语义照 input 的 `value`：**不传**（`undefined`）是非受控形态，开着哪个视图由工作台自己拿着，从有效默认开始；**传了**——一个 id，或 `null` 表示"那个有效默认"——就是宿主在说开哪个，此后每一次**变化**都打开它所指的视图。`null` 不是"没传"：传 `null` 的宿主是拿着一个值的，只不过那个值叫"我的默认"；
- 它**收敛**而不是**渲染**（`react/workbench/instanceSync.ts`）。一个视图不是一个字符串：里面有未保存的草稿，换掉它是一次损失，所以宿主推进来的值和侧栏上的一次点击走同一道离开守卫，不能直接当成渲染结果铺上去。规则只有一条——**后动的那一方说话，另一方跟上**：
  - 宿主点名了一个没开着的视图 → 打开它，经守卫；
  - 工作台自己动了（用户切换、save-as 打开的副本、改名、随被删视图放掉的 pin）→ 告诉宿主，路由跟上；
  - 守卫拦下了这次推入、用户选择留下 → 告诉宿主**留下的是哪个**，宿主的 URL 因此不会停在一个没开着的视图上；
  - 两边一致、或守卫的问题还挂在屏幕上 → 什么都不说；说过一次就不再重复，宿主不跟是宿主的事。
- `onInstanceChange(id)` 报的正是能原样传回 `instanceId` 的那个值——`null` 在出口与入口同义，都指有效默认——所以「一键重开」在浏览器里就是一条可以发出去的链接。两种形态之间中途切换不受支持，和 input 一样：第一个值决定哪一边拿着这个状态。（见 test/workbench.test.tsx「a workbench a host routes」、test/closedLoop.test.tsx「closed loop two」，参照实现 `examples/PlainRecordWorkbench.tsx` 的 `HashRoutedRecordWorkbench`）；
- 种类不在 `kinds` 里由 `kindMismatch` 判为 `unopenable`，与"打不开"同一个出口：一个定义同时容纳 record 与 analysis 实例，宿主仍可点名任一个，画不出的那一页要说明白，而不是在标题栏下留一片空白。`unopenable` 为真时 `runtime`／`state` 皆为 null；
- pin 指向的视图被删除后由 `react/workbench/releaseDeleted.ts` 放手：引擎随实例释放 runtime，同一 id 再开只会一直答 not_found，页面因此永远走不到还在的那个视图上。只放手**开过**的 id——宿主点名而 store 从来没有的 id 是一个要报出来的错，不是一个要导航离开的状态；
- `leave` 是无对话框的离开守卫（`react/workbench/leaveGuard.ts`）：`asking` / `request(next)` / `confirm()` / `cancel()`。`dirty` 或写入结局为 `unknown` 时才问，`confirm` 先结清（`commands.abandon()`）再走——`next` 会释放这个 runtime，结局就再没有它可依附，handle 会指向一个谁也够不着的 runtime 而 `engine.pendingWrites()` 把它留到会话结束。怎么问是宿主的事，`/ui` 用 `LeaveDialog`。**同一条判据也挂在 `beforeunload` 上**（D18 Ⅸ）：关标签、后退、点走一个外链都会带走草稿，而这些路径一次也不经过 `request`——这个包甚至不会知道它发生过。脏或未知时挂上、其余时候一条也不挂（一个每次关闭都要争辩的标签，人会学会按两下），`guardUnload: false` 可以整个关掉，留给把工作台当页面一部分挂着、或在服务端渲染的宿主。处理器不带任何措辞：浏览器十年前就不再显示自定义文案了，能由我们措辞的那一句是 `LeaveDialog`，这一道只是那些到不了它的出口的兜底；嵌入视图不在其列——`EmbeddedView` 没有编辑器、没有草稿，压根不调这个钩子；
- `filter` 是这次打开的筛选编辑器，在这里建一次：外壳画已应用条件条要用它，error 条要用它的 `unmarked`，三个工作台本来也各建一个；
- `refresh` 是 `useAutoRefresh(runtime)` 的结果，在这里装配而不是在三个工作台里各调一次：`refresh` 在 `ViewConfigBase` 上，三种视图都有，取法也一样；
- **`autoRun` 与 `setAutoRun(on)`**（「改了就跑」，D20）：`autoRun` 读的是这个定义的偏好（`list.preferences?.autoRun ?? true`，没说过就是开着），`setAutoRun` 写下它再重读列表——它是一次偏好写入，与排序、默认视图同一份、同一条路（[management.md#列表偏好与默认视图](management.md#列表偏好与默认视图)）。装配在这里而不在托盘里，因为偏好是**列表**那一层的东西而托盘只认得一个打开着的视图；钩子随之把它推给那个视图（`runtime.setAutoApply(autoRun)`，只在 `runtime.kind === 'analysis'` 时——记录视图的编辑本来就便宜，仪表盘编的是布局不是问题，两者的 `setAutoApply` 要么不调要么是空操作）。偏好或打开的视图一变就再推一次，所以换一个分析视图不会把上一个的开关状态带过去。（见 test/autoRun.test.tsx「改了就跑: the tray’s switch」）
- `create(kind)` 从零做一个那种视图，经离开守卫；`creatable` 是哪几种做得成的全部依据（定义有这一种、用户可在某受众创建、`newView.title` 给了名字），按 `kinds` 的顺序；控件按它存在或不存在——一种是一颗按钮，几种是一张菜单（todo 批 3），没有就没有控件。`newView.templates` 按种类给宿主自己的第一份配置，种类不符的模板当作没给。新视图的 runtime 由钩子自己拿着（`opened` 就是它，此时不按 id 开任何东西），没改过就切走不问，第一次保存走 `commands.saveAs` 后按存下的 id 重开——细节在 [management.md#列表偏好与默认视图](management.md#列表偏好与默认视图)。`SaveCommandState.isNew` 说的是这个视图从没存过，UI 据此把主按钮换成问名字与受众的那张表；
- **持有的视图**（`held: HeldView | null`）：工作台不按 id 开、而是自己拿着的那个视图——从零做的，或从另一个视图的一组开出来的（D20 追问的三项）。两条命令共用一条持有的路：`follow(config, title, conditions)` 把 `config` 按 `title` 建成一个未保存的视图（**不是许可**：这样开出的视图只看不写，`engine.create` 不再问许可，第一次保存才问，H1），`scopeFilter` 原样继承来源（H4），连同 `origin { runtime, title, conditions }` 一起持有；`drill(conditions, title)` 只在 `canDrill` 时有效（开着的是分析视图、`kinds` 含 record、定义有 record 能力），配置是 `defaultRecordConfig` 加上「已应用的条件 + 这一行的条件」（`analysis/drill.ts` 的 `drillFilter`，简单树平铺、否则嵌套）。**名字由调用方给**：它是文案，这一层不带文案——`/ui` 按它是什么起名（[ui/analysis.md](ui/analysis.md) 的追问一节），于是 `newView.title` 与下钻无关，没给它的工作台照样能下钻。来源 runtime **不关**——按 id 开着的那个继续开着（`useOpenView` 在有 origin 时不放手），持有着的那个（从零做的，或本身就是跟进出来的）记在 `held.from` 里——所以 `back()` 回到的是原来那次结果，不重跑，连着跟进几层就一层一层退回去。开出去**不经**离开守卫：来源连同它的草稿与在途写入都还开着，什么也没丢，问「不保存就离开吗」说的是一件不会发生的事；`back()` 则与 `choose` 一样经离开守卫——那一刻被放下的是开出来的这个，没动过的直接走。宿主给了 `onDrilldown(target)` 就把 `{ definitionId, origin, title, config, scopeFilter }` 交给它、自己什么也不持有（H5；只管记录，`follow` 不经它）。「返回」条由外壳直接读 `held.origin` 画（`ui/workbench/OriginBar.tsx`），任何一种视图的注入件都不知道它；有 origin 的视图编辑器默认收着。（见 test/workbenchDrill.test.tsx「drilling from an analysis view」「following a group into a view of its own」、test/originBar.test.tsx「the origin bar」）
- 四个 `on*` 是标题栏结局的工作台语义，已经接好：存下的副本随即打开、改名留在原视图（先 pin 再 reload，否则骑在默认视图上的工作台会关掉 runtime 连草稿一起丢）、删除即移开（只清 pin——列表由引擎的通知带着被删的 id 自己重读，再补一次不带 `without` 的重读反而会把那一行放回去；默认视图顺位接上，或随列表一起空掉）、恢复则重读列表。（见 test/workbench.test.tsx「useWorkbench」）

宿主写自己的标记时调这一个钩子就够，规则一条也不会掉——`examples/PlainRecordWorkbench.tsx` 是那份参照。`test/architecture.test.ts` 禁止 `ui/*Workbench.tsx` 直接 import `useViewList`／`useOpenView`／`useViewManager`／`useLeaveGuard`：绕过去就是把装配重建一遍。

## RecordActionSlots

```ts
RecordActionSlots { global?; bulk?; row? }
```

- 三层业务动作的 render 槽位（react/actions.ts），由宿主传给工作台；
- 动作是代码，不进配置也不进 ViewInstance。
- 动作拿到的行（`row.data`、`rows[].data`）**只有这一页要回来的字段**：行键、可见列、卡片字段、排序与汇总字段（[kernels.md#一页要哪些字段](kernels.md#一页要哪些字段)）。动作要读别的——补偿控制台的行菜单读 `state.status`、`state.isRetryable`、`state.isBelowRetryThreshold`、`state.recoverable`——就在定义的 `record.rowFields` 里写出来。
- 槽位里抛错由 `/ui` 的渲染边界接住（[ui/README.md#渲染边界](ui/README.md#渲染边界)）：只毁掉它所在的那一块，并经 `onRenderFailure` 交还宿主。

## useSearchBox

```ts
useSearchBox(runtime): SearchBoxController | null   // 定义没有 search 字段时为 null
SearchBoxController { field; value; applied; set(text); submit(); clear() }
```

视图的搜索常驻在手边，而不是藏在条件编辑器里。定义声明了 `kind: 'search'` 的字段（有几个取第一个，`searchFieldOf`）时，它就是那个框：读写的是**草稿根上的那一条搜索条件**（`filter/search.ts` 的 `rootSearch`／`withRootSearch`）——编辑器里那枚 pill、已应用条读出的那一句，都是同一条条件，不是第二份状态。`set` 只改草稿（空白就把这条拿掉，而不是留一枚空 pill），`submit` 即 `apply`，`clear` 拿掉并应用。只认**根上**的那一条：嵌在某个组里的搜索是用户在编辑器里组合出来的，框子既不当它是自己的，也不覆盖它；根是 `or` 的高级树被整个包进一个新的 `and`、再与搜索并列，于是搜索是收窄它而不是成为它的又一个「或」。（见 test/searchBox.test.tsx「the root search」「the search box」）

## useBulkCommand

```ts
useBulkCommand(options?: { concurrency?: number }): BulkCommand;   // 默认 4
BulkCommand { run(selection, { title, each(key) }); stop(); running; outcome; dismiss() }
running  { title; progress: { total; done; failed }; stopping } | null
BulkOutcome { title; succeeded: RecordKey[]; failed: { key; reason }[]; skipped: RecordKey[] }
```

批量命令里**不属于宿主的那一半**全在这里：宿主只写「对**一条**记录做什么」（`each(key)`：做成就 resolve，被拒就抛错）与那颗按钮。此前宿主要自己把整份选择一次 `Promise.all` 发出去、自己拼结局、自己挑一句原因代表所有失败，补偿控制台为此还得用 ref 记住按的是哪条命令；三件事做得各不相同，也都做得不对。

- **几条几条地跑**（`concurrency`，默认 4）：命令是写入，一百条选择一次发出去就是一百个写同时砸在一个服务上；一条落定，下一条才开始。
- **进度**：跑的时候 `running.progress` 说做完几条、失败几条、一共几条。
- **可停止**：`stop()` 之后不再开始新的，已经发出去的照常落地（写出去的不收回）；没开始的记在 `skipped`。
- **逐条原因**：`each` 抛出的东西交给 `sourceReason` 读出数据源自己的话（Wow 的错误体），宿主把错误原样抛出即可，不必自己措辞；`failureReasons` 按条数从多到少归并同一句原因。
- **没做完的留着选中**：落定后选择**恰好**是被拒的与没开始的（按原选择顺序），全做成就放开——这些行正是下一步要处理的。随后刷新，刷新留在当前页（[runtime.md](runtime.md)）。
- **一次只跑一趟**：在途时第二次 `run` 直接不受理；空选择什么也不跑；宿主已卸载时照常跑完（记录已经收到命令），只是不再汇报。
- **选择的接口**：`run` 收 `BulkSelection`（`keys`、`select`、`refresh`，都来自 bulk 槽位的 `RecordBulkActionContext`）；行上的命令传 `select() {}`，不动选择。
- 结局不自行消失，`dismiss()` 是它唯一的出口；下一趟 `run` 一开始也把它换掉。**状态条由工作台画**：宿主把钩子交给 `record.bulk`，`/ui` 的 `BulkStatus` 在工具栏与行之间（查询失败条所在的地方）说出进度与结局——结局比它作用的那份选择活得久，而 bulk 槽位随选择一起卸掉，所以它属于结果区而不属于槽位；宿主不再需要在工作台外面自己套一层 `ViewSurface` 来画它。（见 test/bulkCommand.test.tsx「useBulkCommand」「BulkStatus」）
