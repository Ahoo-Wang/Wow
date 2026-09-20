# UI 层：Record 视图

Record 工作台的结果区组件。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；控制器见 [react.md#userecordtable](../react.md#userecordtable)。

## RecordTable 与 RecordCards

- `RecordTable` 的 `selectable` 默认为 true，工作台与 `EmbeddedView` 不受影响；
- 关掉时汇总行的口径标签（`total`／`page`）没有多出来的格子可占，于是标在首列之上，而不是顶掉首列自己的汇总。

- `RecordTable` 暂不接 TanStack：控制器已经是表格模型，列语义、排序、选择与分页都从它来，再叠一层只是把同一份状态写两遍。等列宽拖拽与列序拖拽真的要做时再引入，那时它提供的才是新能力。

落到文件上：`ui/RecordTable.tsx` 只留结果本身——空态、骨架、行与单元格；表头与排序在 `ui/record/SortableHeader.tsx`，汇总行在 `ui/record/SummaryRows.tsx`，冻结列（含实测偏移）与表头／数字的类名在 `ui/record/columns.ts`。（见 test/recordTable.test.tsx「RecordTable on its own」「RecordCards on its own」「the summary row」「the summary rows」「sorting from the headers」「enum cells」「the table chrome」）

## 两行汇总：本页与所有

- **两个口径各占一行**：`本页`（`page`）是屏幕上这一页的行加起来，`所有`（`total`）是同一套条件下全范围聚合的答复。二十行的平均数被当成四万行的平均数，是这一行唯一能犯的错，所以口径不是注解而是行的一部分：每行首列一个灰底标签格（有选择列时占选择列，没有则标在首列之上，见上），`data-scope` 同时带在 `<tr>` 上；
- **只有 `所有` 需要查询**。内核这两半都在：`compileSummaries` 产出全范围的 `AggregationQuery`，`projectSummaries` 按别名读回（见 [kernels.md](../kernels.md)）；`page` 口径不需要往返，因此 `record/project.ts` 的 `pageSummaries(cells, rows)` 用已执行配置给出的那些格子，在屏幕上的行上再算一遍——渲染层手里只有结果，没有配置，而这份算术与 `projectSummaries` 的 `page` 分支是同一份，放在一起才不会分头漂移；
- **降级只剩本页**。聚合失败时 `runtime/execute.ts` 退回 `scope: 'page'`，于是表里只剩 `本页` 一行——没有的数不编——并在状态条上报一条 warning（`runtime.summary.page-only`）说明为什么只剩它。两处缺一不可：只剩一行而不说，读者未必注意到少了什么；只报 warning 而行上的词不改，那个词仍然在撒谎。（见 test/resultIssues.test.tsx「what the screen says about a downgraded total」）；
- 一个字段可以配多个函数（`amount` 同时求和与求平均），内核为每个函数投影一格，所以格子按字段分组而不是按字段做键；函数名按目录措辞显示（`label.summary.fn.*`：合计／平均／最小／最大／计数）而不是配置里的 `SUM`，与数值一起右对齐在列的右缘；没有配汇总的列留空，而不是显示 0；某一格算不出来（字符串列求和、聚合没答这一格）显示 `label.summary.unavailable` 的破折号。

## 表头排序

- 点击表头在**升序 → 降序 → 取消**之间循环，走控制器的 `toggleSort`，改完立即 `apply`；
- `aria-sort` **只落在主排序那一格**（`ascending`／`descending`）：ARIA 的表格语义里"按哪一列排"只有一列，给每个可排序表头都标上，未排序的会挨个念 `none`，两级排序则念出两个"升序"而不说先按哪个。次级排序的位次改由按钮的可及名字说（见下）；
- 按钮的可及名字说的是**这一下点下去会发生什么**（`label.sort.ascending`／`descending`／`none`），列名在这句话里面，所见即所闻；
- **可排序但没排序的列带中性的 ↕**：用过之后才出现的可供性不是可供性；
- 控制器的 `toggleSort` 把新字段**追加**在末尾（`sort` 的顺序就是列间优先级），所以多列排序是天然的：同时排序多于一列时，每个表头带上自己的序号，并把 `label.sort.at`（第几个、共几个）接在可及名字后面——两个箭头只说了按什么排，没说先按哪个。legacy 的「按住 Shift 添加排序」要求平击是**独占**排序，而控制器没有能一次落下整份 `sort` 的成员，见 [todo.md](../todo.md)；
- 键盘：表头就是一个按钮，Tab 可达，Enter／Space 即一次点击，没有需要按住的修饰键，也就没有要另找的键盘等价物。

## 枚举单元格是徽章

- `cell ?? kind` 为 `enum`、且定义声明了 `options` 的字段，单元格渲染为 `Badge`，文字取选项标签（判定在 `ui/display.ts` 的 `badgeEntries`，与 `displayValue` 共用"至少有一个值被选项命名"的规则）；数组值一值一枚，拼成一枚会读成"名字里带逗号的一个状态"；
- 每枚徽章连**原值**一起交出，React 的 key 用原值加下标而不是标签：`FieldOption.label` 是自由文本、可以重复，数组里的值本身也可以重复，同一个 key 下两个子节点的协调结果是未定义的，React 还会为此告警；
- 没有 `options`、或者没有一个值被选项命名的，照旧是纯文本——没人命名过的码套上徽章，只会让这个码看起来是有意为之；
- **颜色不猜**：一律 `secondary` 中性徽章。哪一个状态是好消息属于业务，`FieldOption` 今天只有 `value`／`label`／`group`／`disabled`，要着色就得由定义带出来（见 [todo.md](../todo.md)）。

## 表格 chrome：层次与冻结列

自上而下三层，每层有自己的底色，行之间仍只有发丝线：

```
┌───────────────────────────────────────────────┬───────┐
│ 表头层  sticky top-0 · bg-background · border-b-2      │  ← 列名是元数据：text-xs、muted
├───────────────────────────────────────────────┼───────┤
│ 行 · 静息 bg-background                        │       │
│ 行 · 悬停 hover:bg-muted/50                    │ 操作  │  ← 选中 data-state=selected：
│ 行 · 选中 bg-muted（悬停不冲淡）                │ 冻结  │     bg-muted，且 hover 不改写
├───────────────────────────────────────────────┼───────┤
│ 汇总层  sticky bottom-0 · bg-muted                     │  ← 本页 / 所有 各一行
│   本页 │ …右对齐的数字…                                │
│   所有 │ …右对齐的数字…                                │
└───────────────────────────────────────────────┴───────┘
  ←──────────── 横向滚动条在汇总行之下 ────────────────→
```

- **一个滚动容器，且它必须真的会滚**：`scrolls` 默认 true 时 `RecordTable` 自己是那个容器（`overflow-auto`，高度上限 `--fve-record-table-max-h`，默认 `70vh`），注册表 `Table` 自带的那层容器被取消滚动——两层嵌套时粘性会认里面那层，表头就粘不住了。横向滚动条因此在整张表之下，也就在汇总行之下；
- **外面有东西在滚，就把 `scrolls` 关掉**：CSS 没有"只在一个轴上溢出"——横向能滚的盒子在两个轴上都是 scrollport——所以一个永远不需要滚的外壳干脆不能是 scrollport。仪表盘面板（`DashboardGrid` 的 `CardContent`）比表自己的 70vh 矮，滚的是面板；此时若表还带着自己的滚动容器，表头会牢牢粘在那个没人滚的盒子上，随面板一起滑出视野。关掉之后表头、汇总行与冻结列改为顶着**真正在滚的那一层**，`DashboardGrid` 的记录面板即照此传 `scrolls={false}`；
- 表头 `<thead>` 与汇总 `<tfoot>` 各自 `sticky`，行在中间滚；
- **冻结列**：列配置的 `pinned: 'left' | 'right'` 由 `columnPins` 折成每个格子的 `position: sticky` 与偏移量——表头、数据行、汇总行同一列的每一格都要带，只粘表头而让格子滑走比不粘更糟；
- **偏移量按实测**：列宽由内容决定而不是由配置决定——声明的 `width` 只是建议，自动布局的表可以超过它，选择列与操作列则根本没有声明。`usePinnedOffsets` 在每次布局后量表头（带 `data-pin` 的那几格），把结果写成表上的 `--fve-pin-left-{i}`／`--fve-pin-right-{i}`，格子以 `var(--fve-pin-left-0, calc(…))` 读它：拿不到实测值时退回按声明宽度累加的那份算术。**这不是洁癖**：选择列的 class 写的是 `w-10`（2.5rem），而汇总行的口径标签在这一格里，实测渲染成 76.6px，照 class 冻结的下一列会盖住复选框 37px。宽度变化不经过 React，所以直接写 DOM 而不进 state；
- **盯的是那几格表头，不是整张表**：字体加载完、宿主的行内按钮变宽，都能在表自身的盒子纹丝不动的情况下改掉某一列的宽度，此时按上一次布局发布的偏移量会让冻结列压住邻居。`ResizeObserver` 因此逐格 observe 参与累加的表头格（没有这个 API 的环境退回初次实测）；
- 退路里，左侧从选择列的 `2.5rem` 起算，右侧从 `--fve-record-actions-width`（默认 `6rem`）起算，同侧第二个起按前一列声明的 `width` 累加；
- 冻结格子的底色是 `bg-inherit`：行自带不透明底色，格子跟着行走，选中与悬停因此不会在冻结列上断开；
- 选择列在**有列冻结在左**时一并冻结，否则会被冻结列盖过去；
- 数字列（`cell` 为 `number`）单元格与表头一律右对齐并用 `tabular-nums`，表头的排序标记跟在列名内侧。

## RecordPagination

结果下面的一行，不进工具栏：翻页不留在配置里，用户翻到第几页也不是他看记录的方式。一行从左到右读完——

- **左：一共多少条**。`label.pagination.total`（「共 18 条记录」）说的是条件选中了多少，而不是这一页来了多少：那才是上面那串条件被问到的问题。源没有给总数时（cursor 分页）这句不出现，改由 `label.pagination.on-page` 说它确实数得出来的那个数，不拿一页满不满去推总数；
- **右：每页几条**。`label.pagination.page-size`（「每页」）是控件的名字，不另写 `aria-label`——屏幕上读到的那几个字就是它的可及名称。档位来自 `table.pageSizes`（控制器按 `runtime.limits.maxPageSize` 裁剪后的结果，并入当前值），每个选项由 `label.pagination.page-size-option` 写成「20 条」／「20 per page」：量词跟着数字走，否则中文会读成「每页 20」。改每页条数是一次编辑，走 `setPageSize` 立即应用；
- **右：第几页**。`label.toolbar.page-of`（「第 1 / 4 页」）；总数未知或页大小非正时退到 `label.toolbar.page`（「第 1 页」）——没有总数就除不出页数，只说到达的这一页；
- **右：上一页／下一页**两个图标按钮。焦点顺序即阅读顺序：每页选择器 → 上一页 → 下一页，左边那句是句子不是控件，不抢焦点。

两条不随组成改变的规矩：

- cursor 源既无页码也无退路，于是两样都不画，而不是画成死的；
- 结果为空且已落定时整条不画（空结果自己会说明），但**分页源停在第 2 页及以后时照画**——那一页可能是因为记录被删掉、或无总数的源多翻了一页才空的，收起来就把「上一页」一并收走，人留在空页上无处可按。查询在途时同理：手上还是上一批行，计数跟着它们，不闪成空再跳回来。

（见 test/recordPagination.test.tsx、test/accessibility.test.tsx「the pagination bar, mid-way through a paged result」，以及回归 story「Paged」）
