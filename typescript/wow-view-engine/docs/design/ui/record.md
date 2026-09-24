# UI 层：Record 视图

Record 工作台的结果区组件。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；控制器见 [react.md#userecordtable](../react.md#userecordtable)。

## 提示说屏幕上的名字

内核的发现只有它手里的键可说：字段路径（`field`）、协议拼法的汇总与操作符（`fn`、`operator`）、布局键（`layout`）。记录视图的状态条经 `nameIssue` 过 `ui/record/issueNames.ts` 的 `recordIssueNamer`，与分析视图的 `analysisIssueNamer` 同一条规矩：字段按标签（数组条目的字段按条目自己的标签），汇总按汇总选择框的词（`summaryFunctionKey`，时间点的 `MAX` 是「最晚」），操作符按操作符选择框的词，布局按布局开关的词（`LAYOUT_LABEL`）。中文文案里名字用「」括起来：「「Warehouse」不能用来排序。」「这里没有「卡片」布局。」定义里没有的字段原样留着——它在屏幕上本来就没有名字。（见 test/recordIssueNames.test.tsx）

## ResultToolbar 的三组

- 工具栏是「批量操作 + 展示设施」。**左端**：有选中时是「已选 N 条」加一颗紧贴计数的 ✕（`label.toolbar.clear-selection`，`ghost` 图标按钮——贴着它清掉的那个数，是全包「去掉这个」的形状），再是宿主的 `bulk` 槽位；没选中时左端为空、整组不画——从前宿主交了 `bulk` 时这里有一句「勾选行以批量处理」，它在每个视图、每次打开都重复每一行复选框已经说过的话，是一栏控件里唯一一句散文（2026-09-23 视觉走查删去）（右组本身撑着 32px，不必留 `min-h-8` 占位）。**右端**按职责分组，组间 8px、组内无缝：`[表格｜卡片]`、`[列设置][排序]`（这些行**怎么画**）、导出（什么也不改，只把行带走）。刷新是框架功能，在标题栏右组（[README.md#工作台骨架](README.md#工作台骨架)）；
- **工具栏是 ARIA 的 toolbar**：壳是 Base UI 的 `Toolbar` 原语（registry 没有，按 `popups.tsx` 的做法在 `ui/toolbar.tsx` 放一层只加 `data-slot` 的薄包装）——`role="toolbar"`、**整条栏一个 Tab 站**、方向键在栏内走并回绕。`ToggleGroup` 自己登记进漫游序；列设置／排序用 `ButtonGroup` 画合缝。宿主 `bulk` 里的按钮登记不进来，仍是普通 Tab 站。栏有名字（`label.toolbar.title`「结果工具栏」）（test/resultToolbar.test.tsx「ResultToolbar keyboard」）；
- **右边几组是一个块，一起换行**：包在 `ml-auto flex flex-wrap justify-end` 里，而不是与 `flex-1` 撑条并排——撑条会把布局切换单独顶在第一行（回归 story `ToolbarWrapsAsGroups`）；
- **份量**：功能一律是**带边的图标按钮**（`outline`），带名字与 tooltip——裸文字 `ghost` 像标签；只有报告状态的控件带文字（排序按钮）。布局切换按[版式](README.md#版式三块一套间距一种选项控件)的房规用 `ToggleGroup spacing={0}`；同屏唯一的 primary 是筛选的 Apply（test/resultToolbar.test.tsx「ResultToolbar grouping and weight」）。

### 列设置（面板）

- 列设置是一个 popover：**列显示什么、什么顺序、固定与否、汇总什么**是同一个问题——"一行长什么样"。一行一列：手柄 · 显隐勾选框 · 列名 · （声明了 `summary` 时）汇总下拉 · 固定开关（固定时图标实心）；
- **规矩挂在它管的那一行上**：说明写在控件旁（`data-slot="column-note"`，由该行的 `aria-describedby` 指到），顶部的 `label.columns.hint` 只说这份列表就是表格的列序。手柄靠可及名字（"调整 {列名} 的顺序"）与 `label.columns.instructions` 说用法；失效列与"仅剩一列"的说明画出来，隐藏列那句只给读屏（`sr-only`，五行同一句就又成散文）（test/columnSettings.test.tsx「a column the definition dropped」）；
- **区域就是固定方式**：`projectRecord` 按「固定（左） → 可滚动」排布列，列设置照同一口径分区——`sticky` 只把元素钉在它本来的位置，固定在中间的列照样滚走。一行靠**被固定**换区域而不靠拖（`columns/drag.ts` 的 `columnDrop` 挡掉跨区），每区一个拖放组。没有第三个区域（[decisions.md#D19](../decisions.md#d19-固定只有左侧右边那一列是操作列)）。区域标题看得见，就是可及名字，用的正是开关的两个词（`label.columns.pin.left`／`pin.none`），`h3`、侧栏分组标签那一档；`<ul>` 用 `aria-labelledby` 指它而不另写 `aria-label`——同一个词写两处，总有一天只改一处（test/columnSettings.test.tsx）；
- **首尾两列固定，由投影说了算**（[decisions.md#D13](../decisions.md#d13-首尾两列固定阴影常在)）：`projectRecord` 给主键列发 `pinned: 'left'` 并排在最前，给**真正画在最后**的那一列发 `pinned: 'right'`——首列说明是哪条记录，末列是眼睛回到的界线。末列不算删掉的、关掉的（`hidden`）列；只剩主键时不算；每列都固定时也不算。有行操作时 `ui/record/columns.ts` 的 `heldColumns` 让末列放手，操作列接替——右侧永远只有一格冻结、偏移为 0，`ACTION_CELL` 不必问；
- **主键的位置是定义的性质**：无论配置怎么写，`RecordTable` 都把它冻在第一格，不让用户改；固定在左却画在第二位的列会盖住前一列，所以排序与固定写在投影同一处。主键那一行的固定开关与手柄照常显示、一律禁用；勾选框也禁用，并在那一行画出 `label.columns.primary-required`——横滚出去不带「这是哪一单」的行谁也读不懂，也因此「至少一列」不必另守。**宿主的操作列不在列表里**（D19）：它是 render 槽位，永远最后、冻在右边；
- **读不出的值当作默认**：配置不可信，`pinned` 可能是 `'left'`、`'top'` 或数字，而固定是布尔（D19）。`model/record.ts` 的 `columnPinned()` 是唯一读法（只有 `true` 算固定），投影、控制器的 `pinnedOf` 与草稿都走它；`validateColumns` 另报 `record.column.pin-invalid`——下一次改动会把它写成能读的形状（`recordColumns`）。`hidden` 同理（`columnHidden()`，`record.column.hidden-invalid`）。渲染从不拿读不出的值去索引文案表；
- **报了错的那一项一定够得着**，配置说不了算的事（主键位置）放了什么都不算错。新增 Issue 码时自检：屏幕上有没有控件能改它抱怨的东西，包括"仅剩一列"。落到面板上：
  - 定义里删掉的列照样列出（`data-broken`，`CircleSlashIcon` 加 `label.columns.unknown`，只留勾选框，取消即移除）——区别不能只靠那一档灰（WCAG 1.4.1）；
  - 同一字段列两次只画一行，一次取消两条都走；
  - 字段丢了汇总能力时，下拉仍列出当前函数，好让"不汇总"够得着；
  - 保存的布局已不被允许时，布局切换照常显示、无一项按下；
  - **汇总落在不是列的字段上时自占一行**（D17-9，`validateSummaries` 报 `record.field.unknown`）：按 broken 行画（`summaryOnly`），句子 `label.columns.summary-unknown`，勾选框 `label.columns.keep-summary`，**取消只调 `setSummary(field, null)`**——否则残留汇总会被写回成一列用户从没加过的列；
- **关掉的列留在原处（D17-8）**：`table.columns[]` 带 `hidden?: true`，勾回来回到原来那一格；照常有一行、可拖，列在它固定到的区域里（宽度与固定都留着）；`projectRecord` 不画、导出不写。它**不能固定、也不能加汇总**（写不出看得见的东西），两个控件禁用、`aria-describedby` 指 `sr-only` 的 `label.columns.hidden`（先显示，再固定或汇总）。失效字段与重复项没有可回来的位置，取消即删除——否则挡着查询与保存的正是刚按下的控件。旧配置没有这个成员，读作显示；
- **关掉一列，汇总跟着走**：汇总属于列，留着会让运行时要一次没格子可画的聚合。`setColumns` 在**同一次** `edit` 里一并删掉；
- **拖动覆盖配置里的每一列**；配置从未提到的字段没有位置，手柄禁用、列在可滚动区末尾，勾上接在末尾。提交的是两个区域按绘制顺序拼出的完整列序，与 `projectRecord` 是同一份列表；区内不可拖的行（主键、失效列）留在原位；
- 汇总下拉的选项是"不汇总"加字段声明的函数，写入 `config.summaries`；配置可一字段多函数（表格照画），控件一列只给一个，选中即替换；
- 固定开关写入 `table.columns[].pinned`；取消时**删掉**那个键而不是置 `undefined`——`dequal` 会把 `{ pinned: undefined }` 读成改动，视图一直"未保存"；
- **拖放用现成的库**（`@dnd-kit/react` + `@dnd-kit/dom`，走 catalog）。只有可拖的行注册成 sortable item。`OptimisticSortingPlugin` 按 [interaction-primitives 设计](../../../../docs/superpowers/specs/2026-09-13-view-engine-interaction-primitives-design.md) 关掉——它在指针移动时重排 DOM，让落点下标失效；落点由 drop 报出的 source／target 两个 id 算出。真指针链路只能在浏览器里跑（jsdom 里盒子都是 0×0），是 `stories/view-engine/RecordWorkbench.test.stories.tsx` 的一条故事，断言表头列序与保存后的 `table.columns`；
- **键盘**：手柄可聚焦，方向键在区内移一位；空格拾起后方向键交给库（`isDragging` 时本地让路），各有单一播报源，库的英文句子换成目录里的，落定由设置自己的 live region 说一次。**手柄只有一份**：`ui/DragHandle.tsx`，列设置、排序编辑器、视图管理器共用；放下由 `ui/dragDrop.ts` 的 `dropped()` 判定（test/dragHandle.test.tsx；test/accessibility.test.tsx「record, with the column settings open」）。

### 列设置的目录分组与搜索

一张 20 列的表让这份列表一屏装不下、一眼找不着。

- **滚的是列表，不是整个弹层**：`ui/popups.tsx` 的 Popover 带 `max-h-(--available-height)` 与 `overflow-y-auto`（[README.md](README.md#主题弹层与明暗)）；列设置再写 `overflow-y-hidden`、列表 `min-h-0 flex-1 overflow-y-auto`，搜索框不随行滚走；
- **顶上一个搜索框**，与字段选择器同一控件同一套词（`label.field.search`；无匹配 `label.field.none` 走 `Empty`），按**行上那个词**匹配（操作列用 `label.toolbar.actions`），不分大小写。**它只少显示几行**：区域、顺序、关掉的列都照旧，面板要数的东西仍按整份列表数。弹层一关就清空；
- **筛选时不能拖，也不能用方向键挪**：移动是相对邻居说的，而搜索拿走了邻居。过滤期间手柄禁用，输入框的 `aria-describedby` 指 `label.columns.filtered`「清空搜索即可调整列的顺序。」；
- **目录只嵌在中间区**：左右两区按构造就短。定义的 `fieldGroups`（由 `ResultToolbar` 透下来）是中间区的第二层：未分组字段在最前、不带标题，其后各组一个 `h4` 标题，空组不画；
- **组内按表格画的顺序，不按组的 `fields` 顺序**——与另外两个选择器唯一的分歧（[README.md#字段目录与选择器分组](README.md#字段目录与选择器分组)），因为这份列表就是列序。落点按整区顺序算（`movableIndex`），每节是它的子序列，其余各节不动；每节一个拖放组（`columns-<区域>:<组 id>`），列拖不出它的分组（test/columnSettings.test.tsx「finding a column in a wide list」；浏览器故事 `WideTableColumnSettings`／`NarrowHostColumnSettings` 量 1280 与 420 两屏的几何）。

## 导出

工具栏右端的 `ExportButton`：带边的图标按钮（`DownloadIcon`，可及名字 `label.export.title`），**点开是一个模态 shadcn `Dialog`，整件事都在里面**（D14），四步同一个壳。窗口本身是受控的 `ExportDialog`（`open`／`onOpenChange`／`finalFocus`，每次打开重新挂一趟：范围与文件名属于这一趟），所以仪表盘记录面板「⋯」里的「导出数据…」打开的是同一个窗口（[dashboard.md](dashboard.md) 面板菜单）。交给它的东西由 `useExportOffer`（`ui/record/exportOffer.ts`）一次给齐——运行、条件、文件名、列与上限（`exportPlan`）；调用方只传 `runtime, table, filter, title`，界面语言、时区与措辞由它在视图表面里自己取，所以它挂在表面之内（工作台里是工具栏那一格，`RecordParts` 的 `RecordToolbar`）。分析视图用同一扇窗、不给范围，窗里按组说文件里有什么（`ExportOffer.holds`，见 [analysis.md](analysis.md#导出数据表格读法下的行d25-q28)）。

**按钮只在有结果可导时存在**，判据是 `table.hasResult` 而不是 `status`：首查失败时结果块还在但只会导出空文件；刷新失败留住的行仍可导。缺席而不是置灰；宿主用 `features.export` 关掉时同理（test/resultToolbar.test.tsx「ResultToolbar export」；浏览器故事 `QueryFailed` 与 `WithData`）。

- **一、先摆清楚要导什么**：有勾选时一组 `RadioGroup`——「选中（N）」（默认）与「所有（N，按当前筛选）」；没勾选只有「所有」（D4）。「所有」是唯一其行不在屏幕上的口径，所以要说「按当前筛选」；总数报不出时（游标源）不编数。底下四行说**文件里会有什么**：多少条；按什么条件（`summaryText`，视图条件加宿主作用域，导出跑的就是合并后那份）；哪几列（`table.columns` 可见列，投影顺序，按 `label.filter.join` 连起来）；文件名（`<视图名>-<yyyy-MM-dd>.csv`，`ui/download.ts` 的 `fileName`）。**名字在窗口打开那一刻定下**，这一趟（含重试）都用它——跨午夜再算，承诺的与交出去的就不是同一个名字。条数超过 `limits.exportMax` 时多一行「超过上限 {max} 条，文件只会有前 {max} 条」——**按下「导出」就是同意**，所以 `useRecordExport` 没有另外的超上限问句；
- **二、跑起来**：shadcn `Progress`（`role="progressbar"`）加「已拉取 {fetched} / {total} 条」，总数未知时不定态（`value={null}`）；只有「取消」，**在途时 Esc 与点遮罩就是取消**。「选中」那一路直接到结果，不闪进度条；
- **三、结果**：「已导出 {count} 条」加文件名，被上限截断时多一句（总数未知时不编数），只有「关闭」。关窗 `reset()`，下次重新问；
- **四、失败**：`export.failed` 旁边「重试」与「关闭」，原地重试。所以状态行里没有导出的事，`RecordWorkbench` 的 `strips` 只有查询失败；取消什么也不说；
- **值就是表上那一份**：值走 `cellText`（枚举取标签、时间按 `ViewSurface` 的时区与语言、数字按 `numberFormat`）；但**文件里的数字是数**（`csvCellText`）：没配格式的写成 534897 而不是 534,897，否则表格软件当文本、没法求和；声明了格式的照声明写（test/display.test.ts「csvCellText」）；
- **公式缺省中和**（[D36](../decisions.md#d36-导出文件缺省中和公式2026-09-24)）：文件会离开页面、在别人的 Excel 里打开，所以文本以 `=`、`+`、`-`、`@`、制表符或回车开头的格子（表头也算）前面加 `'`；值是数的格子与文本就是一个纯数的格子不动（规则见 [kernels.md#导出序列化](../kernels.md#导出序列化)）。宿主要原样的文本，把 `limits.exportNeutralizeFormulas` 设成 `false`（test/recordExportUi.test.tsx「neutralizes a formula in the file, unless the host turned that off」）；
- **分层**：序列化在内核（`record/export.ts` 的 `serializeCsv`，见 [kernels.md#导出序列化](../kernels.md#导出序列化)），拉全量在运行时（[runtime.md#导出](../runtime.md#导出)），口径与状态在 `useRecordExport`（[react.md#userecordexport](../react.md#userecordexport)），**只有下载在 `/ui`**：`ui/download.ts` 的 `downloadFile`（Blob → object URL → `<a download>` → 立刻 revoke）。宿主要留痕的，`RecordWorkbench` 的 `onExported` 把文件交出来；
- **工具栏只认一个 `exporter`**：`ResultToolbar` 收 `{ control, conditions, nameFile }`（`ExportOffer`），列与上限自己从 `table` 与 `runtime.limits` 取。`nameFile()` 是一次**问**：窗口开时问一次，名字随 `run(scope, fileName)` 交给 `deliver`，工作台不在 render 里读时钟。不传就没有按钮（test/resultToolbar.test.tsx「ResultToolbar export」、test/recordExportUi.test.tsx「DataWorkbench export」，跨午夜在后者；故事「Record 工作台/导出」）。

## SortSettings：按钮上读得出的排序

- 表头一次只表达一列，说不清谁先谁后。排序按钮把当前排序读成话——"订单编号 ↓"，多于一条加 `+{n}`；有排序时它的可及名字是 `label.sort.button`「排序：{字段} {方向}」（接法同 `label.sort.at`），屏幕文字不变——否则读屏只听到字段与方向，不知道这是排序控件（D12）。方向只在名字里说，不在内容里另放 sr-only 副本（`aria-label` 会盖掉内容）（test/sortSettings.test.tsx「what the sort button says」）；
- **按钮上只有一枚箭头**：中性的 `↕` 只在没排序时戴，排了序由方向箭头接替，与表头（`SortableHeader`）同一朝向；它不占 `data-icon` 槽位（会收紧内边距，而多条时结尾是 `+{n}`）；
- 编辑器逐条列出手柄、序号、字段与方向，方向可翻、条目可删；字段选择器只列**可排序且未用到**的字段，用完即禁用。新字段追加在末尾、升序——它是并列打破者，插在别处等于悄悄改了主排序；
- **顺序可拖**（`label.sort.hint`「先按第一个字段排序，相同时再按下一个」），接列设置同一套（`@dnd-kit`，`OptimisticSortingPlugin` 关掉，播报用 `label.sort.*`）。**按位置认身份**（`sort-entry-{i}`）：重复字段是内核会拒的（`record.sort.duplicate`），编辑器仍列出两条好删掉一条。落点算出整份顺序，经 `table.setSort([...])` 一次写出。只有一条时手柄禁用。每条是一个 `Item`（D16 裁定三：`ItemMedia` 手柄与序号、`ItemContent` 字段名、`ItemActions` 方向与移除）；
- **键盘**：同一个 `ui/DragHandle.tsx`（只给名字、`total < 2` 时禁用、移动回调）。落点是 `ui/sort/drag.ts` 的纯函数（先过 `dropped()`，再问两个 id 是否都指向本列表），jsdom 可测；真指针链路是 `stories/view-engine/RecordWorkbench.test.stories.tsx` 的一条故事，断言表头 `aria-sort` 与按钮摘要；
- **引擎追加的行键不是排序的一项**：记录查询以行键升序收尾，好让翻页不重不漏（[kernels.md「Record 内核的规则」](../kernels.md#record-内核的规则)）。排序编辑器、表头 `aria-sort` 与位次、按钮摘要一律读配置里的 `sort`（test/recordWorkbenchInteraction.test.tsx「keeps the tie-breaking row key out of every sort control」）；
- 没有任何 `sortable` 字段时控件不渲染；
- **选择器停在内核开始拒绝的地方**：游标源的排序上限是 `MAX_CURSOR_SORT_FIELDS`（含收尾行键），超了 `validateRecord` 报 `record.sort.too-many`。内核的 `maxSortFields(definition)` 经控制器的 `maxSortFields` 送到控件，满了禁用并以 `label.sort.full` 说明；
- **方向读不出也不崩**：`validateSort` 报 `record.sort.direction-invalid`，渲染仍当升序画——渲染自己也是第二道防线；
- **没排序是空态，排满了不是**：零条画 `Empty` + `EmptyDescription`（`label.sort.unsorted`）；`label.sort.full` 是一行普通字——它是天花板，小浮层里的 `Alert` 比它谈论的列表还大；
- 改完一次性写出（`setSort`），与表头切换同一路径、同一条规矩：草稿里没有别的待应用修改时当场应用，有就并进待应用（见下「表头排序」）。卡片布局没有表头，这里就是它唯一的排序入口（test/sortSettings.test.tsx；test/recordWorkbenchInteraction.test.tsx「holds the card layout’s sort editor back the same way」）。

## 结果块：有结果才有，工具栏是第一行

- **没有结果也没有在途查询时，整块不画**：那圈边是**结果**的边。判据是 `ui/workbench/ResultBlock.tsx` 的 `resultBlockShown`：有结果、有在途、或 `strips` 真会画东西。不带边的（仪表盘，`resultFramed={false}`）不受此管：面板网格本身就是结果；
- **留白配方按作者分两半**：那圈边与外壳自己的两个槽——工具栏（`border-b px-3 py-2`）、状态条 `m-3`——在 `ui/variants.tsx` 的 `resultFrameChrome`，跟着 `ResultBlock`，因为 `toolbar`、`strips` 是 `WorkbenchShell` 三种视图同名的槽。记录视图自己的三件家具——分页行（`border-t bg-muted/40 px-3 py-2`）、空结果 `my-6`、卡片 `p-3`——由 `RecordParts` 用 `resultSlots('caption','empty','cards')` 拼出，经 `WorkbenchShell.resultSlots` 交给 `ResultBlock.slots`：共用外壳不记某种视图的家具、不长 `if`（D18-1）。配方是写死的字面量，Tailwind 读的是源码（故事 `BlockSpacing` 与 `QueryFailed` 量边与留白）；
- **工具栏是第一行，失败条在它之下**：红条压在工具栏上会读成盖住整个视图的横幅，而它说的只是这批行。两个槽各在一道 `RenderBoundary`（都叫 `result`）里，`strips` 不在任何一道里——查询失败那一句无论如何都得读得到；
- **只有一条 error 就直接说，并给出路**：`ErrorStrip` 与 `WarningStrip` 一样，一条发现就是那一行本身，两条起才加标题与折叠——否则折起来的正是唯一说得出要修什么的句子。行尾动作由 surface 给（`action` 槽）：Record 给「打开列设置」（`label.status.open-columns`），用 `ColumnSettings` 的 `trigger` prop 换掉图标按钮（此时没有工具栏）；两种布局都给；
- **callout 宽按容器**：`LineAlert` 用 `w-auto` 覆掉 `Alert` 的 `w-full`（不扣 `m-3`）；暗色下去掉行尾按钮的 `dark:bg-input/30`，tone 字色是按 callout 底量的（回归 story `ErrorCalloutInDarkTheme`）（test/workbenchShell.test.tsx「the result block exists only where there is a result」「the order inside the block」、test/statusStrip.test.tsx「ErrorStrip」、test/recordTable.test.tsx「a record view with no result」；回归 story `NeedsFixing`）。

## RecordTable 与 RecordCards

- **没有结果、也没在跑时表不画**：列来自结果，画出来是空表头加一个选不中任何东西却 Tab 可达的「选择全部行」。`RecordTable` 在 `hasResult` 为假且非 `loading` 时返回 `null`。刷新失败留住的行照画、状态转 `error`（test/recordWorkbenchInteraction.test.tsx「keeps the rows a failed refresh could not replace」），首次 `loading` 画骨架行。`hasResult` 是控制器上独立的成员（`state.result != null`），不拿 `rows.length` 或 `status` 猜（test/recordTable.test.tsx「a record view with no result」）；
- **两种"没有行"是两句话**：查询失败由 `QueryStrip` 说、配置跑不起来由 `ErrorStrip` 说，都在表之上，表不再说（与 `AnalysisWorkbench` 以 `view &&` 把关同一条）；**跑完没匹配上**才是表自己的 `label.record.empty`「没有可显示的内容」；
- **空结果给一个出口，只一个**，由问的是什么决定（`record/emptyWayOut.ts` 的 `emptyWayOut`；`RecordParts` 传 `emptyWayOut` 与 `onEmptyAction`）：
  - 已保存视图、又加了条件 → 「回到保存的条件」（`label.record.empty-restore`，草稿其余改动不动），而不是清空——清空是顶着它名字的另一个视图；
  - 已保存视图、条件未改 → 「这个视图现在没有记录」（`label.record.empty-view`），出口「修改条件」（`label.record.empty-edit`）；
  - 未保存、有条件 → 「清空条件」（`label.record.empty-clear`），`filter.clear()` 之后必须 `submit()`；
  - 没有条件 → 「还没有任何记录」（`label.record.empty-none`），出口「添加条件」；
  - `onEmptyAction` 不给就不画——仪表盘面板与 `EmbeddedView` 没有条件编辑器（test/recordWorkbenchInteraction.test.tsx「emptyWayOut」「takes a saved view back to its saved conditions from the empty result」）；
- **骨架按列名给不等宽条**（列名字数 `ch`，夹在 4–16）：说的是"**这张**表在加载"；首次加载没有列时一行一条；
- **卡片标题最多两行，全文在悬停上**（`data-slot="card-title-text"`，`line-clamp-2` 加 `title`）：标题常是一个长名字——处理器 `OrderItemReservedTrackEventProcessor` 比一张卡片宽——从中间硬截只剩前几个字母，什么也说不出。（见 test/recordCards.test.tsx「gives a card title two lines, and the whole of it on hover」）
- **卡片正文是一列 `Item`**（D16 裁定三）：`ItemDescription` 字段名（灰、`TEXT_UI` 13px，走 `RowItem` 的 `description="label"` 变体——D16 裁定八：调用处不往 vendored 组件上写排版），`ItemTitle` 值（`text-sm`、中等字重），基线对齐；`ItemGroup`（`role="list"`）里每行显式 `role="listitem"`；
- **卡片说的话和表格一样多**（D18 Ⅴ／Ⅵ）：`RecordCards` 守同样三道门（骨架卡片 `ui/record/SkeletonCards.tsx`、同一个 `EmptyResult`）；**汇总留在卡片下**（`ui/record/CardSummaries.tsx`，`data-slot="record-summaries"` + `data-layout="card"`），走同一个 `SummaryValue`，几份 scope 由 `useSummaries`（`ui/record/useSummaries.ts`）定——只有一页时一份（见下「两行汇总」）；**标题走 `cellValue`**，定义里已没有的标题字段退回行键；**`renderCell` 两种布局同一签名**（卡片把字段补成列的形状，`sortable: false`），一个值不该有两个渲染器；
- **卡片设置复用列设置那颗按钮**：`ResultToolbar` 按 `table.layout` 换成 `ui/CardSettings.tsx`（同一个 `data-control="columns"`，名字「卡片设置」）：标题字段（`Select`）、正文字段（复选框，勾上接末尾、已存顺序不动）、图片字段（含「不显示图片」）、每行几张（1–4 的 `ToggleGroup`），全走 `table.setCard(patch)`（test/recordCards.test.tsx、test/cardSettings.test.tsx；故事 `CardsAreSetUpFromTheSameButton`）；
- `selectable` 默认 true；关掉时汇总行的口径标签（`total`／`page`）标在首列之上。汇总行的安静字走 `--quiet-foreground`——`muted-foreground` 压在 muted 上跌破 4.5；它是 `--foreground` 的七成（`color-mix` 推导，阶段 5 的 5A），宿主改前景色它跟着走，也可单独改 `--fve-quiet-foreground`／`--fve-dark-quiet-foreground`；
- **不接 TanStack**（用户拍板）：它只解决「列状态有标准结构」，代价是：列状态本在视图配置里、要存库，它会成第二份；排序筛选分页全在服务端，客户端行模型用不上；冻结偏移按声明宽度算，而本包列宽由内容决定；D13、`aria-sort` 只落主列、操作列接替末端这些规矩它不带。加客户端分组、展开或虚拟滚动时再评估。

文件：`ui/RecordTable.tsx` 只留行与三层；`ui/record/` 下 `EmptyResult.tsx`、`SkeletonRows.tsx`、`cells.tsx`（表格卡片共用）、`SortableHeader.tsx`、`SummaryRows.tsx`、`Filler.tsx`、`columns.ts`（冻结列与表头／数字类名）（test/recordTable.test.tsx「RecordTable on its own」「sorting from the headers」「enum cells」「the table chrome」、test/recordCards.test.tsx「RecordCards on its own」、test/recordSummaries.test.tsx「the summary row」「the summary rows」）。

## 勾选：Shift 连选一段

- **Shift 勾选是连选**：从**锚点**（上一次不带 Shift 的勾选）到这一行，两头都算、按结果顺序，整段跟着这一行走（原本没选就整段选中，选着就整段取消）；段外不动。**连选不挪锚点**；没有锚点时就是一次平点并落锚（test/rangeSelection.test.tsx「selects every row from the anchor down to the pressed one」「selects upwards just as it does downwards」「keeps the anchor through a range, so a second range re-draws from it」「clears the range when the pressed row is being cleared」「is a plain toggle with nothing to extend from, and sets the anchor」）；
- **键盘同一条路**：Shift+空格等价，不另写处理——Base UI 勾选框把空格转成带修饰键的点击，Shift 从 `onCheckedChange` 的 `eventDetails.event` 读（test/rangeSelection.test.tsx「extends with Shift+Space on a focused checkbox」）；
- **Shift+按下不带走文字**：`RowCheckbox` 在带 Shift 的 `mousedown` 上拦掉默认（文字选区延伸），并手动把焦点交给勾选框。**没有自动化守护**：只有真输入设备触发得了选区延伸，故事又不能引 Playwright 真鼠标——改这里时手动核一遍；
- **锚点属于屏幕上这一批行**：记在控制器（`useRecordTable` 的 ref），不在运行时。连同 `RowsMark`（`ViewResult.own` 按身份比，加页码或下一个游标）一起记：换页或换了问题就作废，同一页的刷新保留——否则自动刷新让连选时灵时不灵（test/rangeSelection.test.tsx「lets the anchor go on another page, whose rows are another set」「keeps the anchor through a refresh of the same page」「lets the anchor go when another question is applied」「stands only on the page and question it was set on, over a row still there」）；
- **读屏只听一次**：表外一句 `sr-only`（`label.record.select.hint`，`RangeHint`），每行勾选框 `aria-describedby` 指它，「选择全部行」不指。表格与卡片共用 `ui/record/RowCheckbox.tsx`（test/rangeSelection.test.tsx「tells a screen reader once what Shift does」「selects and clears ranges of cards the way the table does」；故事 `ShiftSelectsARange`）。

## 记录详情：把一条读全

列表只给记录它的列那么宽：错误信息被截断，堆栈不在任何一列里。所以每一行都能打开成一侧的抽屉（`ui/record/RecordDetail.tsx`，控制器 `useRecordDetail`）。

- **怎么打开**：点一行的空白处（点复选框、复制、链接、行操作或刚选中文字都不算）；键盘把一页行当**一个 Tab 停靠**（同 `roving.ts`），↑/↓、Home/End 走，Enter 或空格打开；按键说明只渲染一次，每行 `aria-describedby` 指它（`record/openRows.ts` 的 `useOpenRows`）。关掉后焦点回到那一行。卡片同样。**不加「查看」按钮**：它要么自占一列，要么让固定的操作列越过一半宽度的上限——而它要打开的就是这一行（test/recordDetail.test.tsx「a record read whole」）；
- **先显示已有的，再补全**：完整记录用 `runtime.fetchRecord`（按行键、叠宿主作用域、不带页上的条件与投影）——详情关于记录而不是列表。视图有新结果时再读一次；
- **怎么排**：按定义的字段分组一节一节（`detailSections`），未分组的收在「其他」；搜索、删除开关这类非值字段不列。读法与卡片一致（`cardField`，`cellValue` 的 `detail` 呈现面）；长文本（带换行或超一行）整段原样、等宽、可滚、可复制（`LongText`）；
- **头部放这一行的操作**：读完就要动手；
- **结构读全**：数组对象**逐个元素**展开——「第 n 项」加标题徽章，再按元素声明的子字段逐项读，最后是未声明的部分；未声明元素的对象**逐个键**展开（原键名、等宽）；纯值数组连成一行；嵌套超过六层整段写出。结构与长文本放在名字**下面**、占满整宽（左侧竖线表示层级，`blockOf`），短值放在旁边——否则每深一层都再让出一列名字宽度（`record/DetailStructure.tsx`，子字段由 `cardField` 解析为 `elements`）（test/detailStructure.test.tsx「a structure in a record detail, read whole」）；
- **读不到与已不在**：失败说出源的原因（`sourceReason`），下面仍是已有字段；记录已删除或出了作用域，说「这条记录已不在了」（test/recordDetail.test.tsx「a record read whole」「detailSections」）。

## 两行汇总：本页与全部

- **两个口径各占一行**，哪怕数字一样：「本页」（`page`）是这一页的行，「全部」（`total`）是同条件下全范围的聚合。把二十行的平均当四万行的平均是这一行唯一能犯的错，所以口径是行的一部分：首列一个灰底标签格（有选择列时占选择列），`data-scope` 在 `<tr>` 上；
- **只有一页时只留「全部」一行**（[D26](../decisions.md#d26-阶段-34-联合审查的十一条拍板2026-09-24) Q40，D18 Ⅴ 两行汇总的例外）：这一页装着同条件下的每一条——分页模式、第 1 页、源报了总数且总数不超过每页条数——「本页」与「全部」是同一批行，第二行只是换个名字重复一遍。留下的是「全部」：结果长过一页时它仍然对。规矩看的是分页，不是数字：多页时两行数字碰巧一样也照样两行。游标源与没报总数的分页源分不清「唯一一页」与「许多页的第一页」，照样两行。判断在 `ui/record/useSummaries.ts`，表格与卡片、工作台、仪表盘面板与嵌入视图走的都是它，所以处处一致；聚合失败退回 `page` 时仍是那一行「本页」（下一条）（test/recordSummaries.test.tsx「shows only the total when the page holds every record」「says the same under the cards」「keeps both where the page cannot know it is the only one」「keeps the page row when the totals query failed, one page or not」「draws the one row in a dashboard panel as in the workbench」；故事 `WithData` 一行、`Paged` 两行）；
- **只有「全部」需要查询**：`compileSummaries` 产出 `AggregationQuery`，`projectSummaries` 按别名读回（[kernels.md](../kernels.md)）；`page` 由 `record/project.ts` 的 `pageSummaries(cells, rows)` 在屏幕上的行上算，与 `projectSummaries` 的 `page` 分支同一份，不会漂移；
- **降级只剩本页**：聚合失败时 `runtime/execute.ts` 退回 `scope: 'page'`，只剩 `本页` 一行（不编数），并报 warning（`runtime.summary.page-only`）——少一行而不说读者未必注意，只报不改词则那个词在撒谎（test/resultIssues.test.tsx「what the screen says about a downgraded total」）；
- 一个字段可配多个函数，格子按字段分组；函数名按目录措辞（`label.summary.fn.*`），与数值一起右对齐；没配汇总的列留空而不是 0；算不出的格子显示 `label.summary.unavailable` 的破折号；
- **时刻列的最早与最晚**：`date`／`datetime` 列（含 `cell: 'date'`）的 `MIN`／`MAX` 各是这一列的一个单元格——`page` 用 `readInstant` 比时刻、留下原值，`total` 把答复（ISO 串或毫秒）原样读回——页脚走 `cellText`，按宿主语言与时区画时刻；词是 `label.summary.fn.date.MIN／MAX`：最早／最晚（一词一义）；`COUNT` 仍是个数，合计与平均被准入拒绝（test/record.test.ts「a date column summarised」、test/recordTable.test.tsx；故事 `EarliestAndLatest`）。

## 表头排序

- 点击表头在**升序 → 降序 → 取消**间循环（`toggleSort`）。**平击独占，Shift 追加**（legacy 的规矩）：平击是 `toggleSort(field, { exclusive: true })`，按住 Shift／Ctrl／⌘ 才追加；键盘 Shift+Enter／Shift+Space 走同一处理器。规矩由 `label.sort.additive`「按住 Shift 追加排序」说出：`<table>` 外一个 `sr-only` 句子，按钮 `aria-describedby` 指它——不放进 `<th>`（读屏会在每个值旁重念），不用 `aria-description`（只有 Chromium 的草案）。独占是控制器的入口而不是 UI 模拟：模拟要对每列反复 `toggleSort`，每次都是一次查询；
- **按下去当场跑，除非草稿里另有待应用的修改**（与分析表头的 `sortNow` 同一条规矩，判法是 `runtime/pending.ts` 的 `pendingBesides`）：排序是问题的一员，对着结果按表头却什么也没变，读起来就是表头坏了，所以平时当场应用；但范围里还有没应用的条件、或别的修改等着「应用」时，跑就连那条修改一起替用户应用了——这一下只并进待应用，「应用」上亮起那颗点，按「应用」时一起跑。先前被拦下、还没跑的排序不算「别的修改」：下一下正是替换它，只剩它时照跑（test/recordTableCommands.test.tsx「a sort never applies what else waits」、test/recordWorkbenchInteraction.test.tsx「holds a header press back while a condition waits for Apply」；故事 `HeaderSortWaitsForApply`）；
- **两种读法，各有其主**：箭头、`aria-sort` 与位次按**跑过的那份配置**的排序画（`table.ranSort`）——它们说的是屏幕上这些行的次序，排序等着「应用」时指向下的箭头压在升序的行上就是在撒谎；下一下按**草稿**的排序算（`table.sort`），没有东西等着时两者是同一个，等着时连按两下仍是先升序、再降序。工具栏的排序按钮与编辑器是编辑草稿的地方，读草稿——等着时它说的正是「应用」将要跑的次序；
- `aria-sort` **只落在主排序那一格**：ARIA 里"按哪列排"只有一列。次级位次由可及名字说：多于一列时每个表头带序号，并把 `label.sort.at`（第几个、共几个）接在名字后；
- 按钮的可及名字说**点下去会发生什么**（`label.sort.ascending`／`descending`／`none`），列名在句中。它按**下一下实际按的那份**算——草稿的排序（`SortableHeader` 的 `drafted`，记录表给 `table.sort`；分析表另给 `upcoming`，见 [analysis.md](analysis.md)）——而不是按箭头：等着「应用」时两者不同，从前名字按箭头说「升序」，按下去却是按草稿算出的降序。这一列在草稿里的方向与箭头不同时，名字后面接一句待应用的是什么（「按订单号降序排序 · 升序待应用」，`label.sort.waiting.asc`／`desc`／`none`），读屏听到的名字与眼前的箭头对得上（2026-09-23 审查 P2；test/recordWorkbenchInteraction.test.tsx「holds a header press back while a condition waits for Apply」，故事 `HeaderSortWaitsForApply`）；
- **一枚箭头，跟在列名内侧**：数字列右对齐（`NUMERIC_CELL`），整行反过来——名字守对齐的那条边，标记朝里。表头右缘的列宽手柄（`ColumnResizer`）不画字形，所以表头里每个 `svg` 都是这枚标记；
- **可排序没排序的列一直带中性的 ↕**（`muted-foreground/60`）：用过之后才出现的可供性不是可供性，悬停淡入在触屏上等于不存在；
- 表头就是按钮，Enter／Space 即点击；整行表头一个 Tab 站（见「列宽」）。

## 一列怎么读，由定义说了算

一列是状态、标签组、外链还是一段话，是**定义**知道而渲染层猜不出的事。`FieldDefinition.cell` 是**闭合**取值（[model.md](../model.md)）：六个是各 kind 自己的读法（`string`／`number`／`boolean`／`date`／`datetime`／`enum`，可借用——存毫秒的数字声明 `cell: 'date'` 就读成日期），另五个是没有 kind 蕴含的读法。闭合是因为 `/ui` 没有渲染器注册表，没人分派的键会悄悄走默认渲染；准入拒绝未知值（`definition.field.cell-invalid`，[kernels.md#定义准入](../kernels.md#定义准入)）：引擎给得出的，定义才写得出（D4）。未声明就是默认渲染，这五个读法都排在它之前、不改它。

- **`status`**：一枚徽章。`enum` 是推断的（有 `options` 且至少一个值被命名），`status` 是**点名要的**，所以未命名的码照样戴徽章；
- **`tags`**：一枚一枚画，有 `options` 用标签、否则原值——拼成一枚会读成名字里带逗号的一个标签。空数组画零枚；普通数组留给默认渲染（照字段读法、以目录的列表分隔符连成一行）；
- **`link`**：字符串且过得了 `isSafeContentUrl` 才是外链，`target="_blank" rel="noopener noreferrer"`——记录里的 URL 是数据，打开的文档不能顺着 `window.opener` 摸回来。否则**落回纯文本**。与 `DashboardPanels` 的 markdown 链接同一个函数；
- **`text`**：表格里截成一行（`block truncate`），卡片上三行（`line-clamp-3`，`whitespace-pre-wrap`），整段在 `title` 里。宽度上限 `--fve-record-text-max-w`（默认 `24rem`）：表格按内容布局，没天花板的一段话会撑宽整列。`block` 是给 `max-width` 与省略号一个盒子；
- **`copyable`**：值按 kind 格式化、**用等宽字**（`font-mono`，字号 0.9em——等宽字同号读起来大一号、也更宽，1em 时 `PinnedEdges` 的主键列把冻结组撑过了上限、操作列丢了钉；与详情页标题里的主键同一种字——要逐字带走的值，比例字里 `0`/`O`、`l`/`1` 长得一样，等宽也让一列 ID 上下对齐；2026-09-23 视觉走查），旁边一颗复制按钮（`ui/CopyButton.tsx`）：
  - **在单元格里，不在行操作里**：一行有几样可复制的东西时，行尾一颗「复制」说不出复制哪个。名字带值（`label.copy-of`＝「复制 {value}」）；
  - **悬停才现身，键盘永远够得到**：静息 `opacity-0`，行悬停（`group/row`）或单元格悬停（`group/copyable`）显形，`focus-visible` 显形；**绝不 `display:none`**（会摘出 Tab 路线）；**`(hover: hover)` 守的是「藏」**，触屏上一直在；
  - **结果要说出来**：成功翻成对勾、改说「已复制」（`label.copied`），约 1.5 秒复原；失败说「复制失败」（`label.copy-failed`），值仍可手动框选。两者都挂一块**按结果挂载**的 `role="status"` `sr-only` 区域（读屏不重念正待着的按钮，同 `SaveActions`；不每行常驻，理由见 `DashboardGrid`）；
  - 空值不画按钮。`cellText` 不变：CSV、`title` 与剪贴板是同一份读法。

徽章连**原值**一起交出，React key 用原值加下标而不是标签：`FieldOption.label` 与数组值都可能重复。

**表格里一行就是一行**：表顺着一列往下读，行高不齐那条直线就成了台阶。所以同一读法在表格与卡片上**只差能占几行**——`text` 一行／三行，`tags` 并排／折行，`link` 本就一行。由 `cellValue` 的第五个参数 `CellSurface`（`'table' | 'card'`）说，宿主自画 `renderCell` 时也得说——比表格按 slot 名往下够进单元格（D16 不许）直白。**宽度归内容，高度归行**（test/recordCells.test.tsx「a text cell」「a tags cell」；浏览器故事 `WideTable` 量 50 行行高一致）。

**表格与卡片读同一份**：读法都在 `ui/record/cells.tsx` 的 `cellValue`，上面那条是唯一分歧（test/recordCells.test.tsx；浏览器故事「Record 工作台/单元格读法」）。

**这份读法是导出的**：`renderCell`／`selectable` 与两句空态文案由 `DataWorkbench.record` 经 `RecordParts` 透传；宿主改一格就得够得到其余每一格的默认读法，所以 `/ui` 导出 `cellValue`（节点）、`cellText`（单行文本）、`displayValue`（字段种类那一层，无话可说时 `undefined`），上下文从 `useViewMessages` 与 `useSurfaceDisplay` 读。**vendored 的 shadcn 原语不导出**：许诺的是值怎么读，不是外面的 markup（同 README 的 `fve-tokens`）。`selectable` 表格与卡片一同关（test/recordWorkbenchHost.test.tsx「a DataWorkbench a host draws cells in」）。

### 数组对象的一列

一列落在对象数组上（Wow 事件流的 `body`、订单明细），读法由定义的 `elementTitle` 说（[model.md#数组对象在单元格里按元素的标题读](../model.md#数组对象在单元格里按元素的标题读)）：每个元素一枚 `ToneBadge`，字是标题字段的读法，语气取那个选项的语气（单选且带语气时），否则中性。没声明标题就是「3 项」，对象是「2 个字段」——**单元格里从来不出现 JSON**。读法在 `display.ts` 的 `heldReading`，`cellValue` 与 `cellText` 都先问它，表格、卡片、CSV 与 `title` 说同一份。

- **表格里最多三个位置**（`TABLE_ELEMENTS`）：对象数组没有天花板，「宽度归内容」会把列撑出屏幕；三个一眼收得下，也够一条事件流。**超过三个画前两个、第三位写 `+k`**——画三个再写 `+1` 是拿藏起来的那个的位置说它被藏了；
- **什么都没丢**：整张清单在 `title` 里；`+k` 只给眼睛（`aria-hidden`），读屏听到元素本身（`sr-only`）；CSV 全写；卡片全画、折行；
- 徽章按位置作 key：元素标题可以相同（「准备重试」出现两次正是重试的样子）；
- `+k` 不上色、继承正文墨色（同 `label.sort.more`）：穿上徽章就读成一个叫「+2」的元素。

（test/recordCells.test.tsx「an array of objects」；浏览器故事「Record 工作台/按元素读」（`ElementColumns`））

### 徽章的颜色由定义说了算，但只能从主题里挑

- 哪个状态是好消息属于业务，渲染层猜不得。语气写在 `FieldOption.tone`（`'neutral' | 'success' | 'warning' | 'danger'`，闭合），`badgeEntries` 连语气交出，单元格交给 `ToneBadge`（`ui/variants.tsx` 的 cva variant，vendored `Badge` 不动，调用处只说语气——D16-8）；缺省 `neutral`（`secondary`）；准入拒绝未知语气（`definition.field.tone-invalid`）；
- **闭合的是语气而不是颜色**：每档映射主题 token（`--success`／`--warning`／`--destructive`），宿主改 `--fve-success` 就改掉所有徽章；
- **有语气的徽章是软配方**（2026-09-23 v16 视觉稿，用户确认）：那一档 token 的 10% 淡底、token 本身写字、字前一个同色圆点、一圈 30% 的同色边（`bg-success/10 text-success border-success/30`）。不再实底：状态列是一列一列往下读的，整列实心红在每一行都像警报，而「失败里的一条失败」才是常态；字说状态，淡底和圆点只负责归组，所以可以安静；
- **软配方照样要守住实底守过的两条线**，都在三档行底（静息、悬停、选中——选中时行底是 `--muted`，淡底落在灰上）上量：**字 ≥4.5:1**——浅色 danger 5.33／4.90、success 6.07／5.59、warning 6.09／5.61（静息／选中）；暗色三档都用自己的 token 写字：danger 曾在选中行上配注册表的 20% 暗色淡底只有 **3.92:1**，靠向 `--foreground` 混五分之一补上；暗色状态色降饱和、`--destructive` 提到 0.76 之后（阶段 5，Q44），它在选中行的 10% 淡底上直接有 5.48:1（slate 暗色 5.37:1），补丁已删，每套预设的每一档由 test/presetContrast.test.ts 量（浏览器故事 `ToneBadgeInkInLightTheme`／`InDarkTheme` 在三档行底上逐枚量，并守住四档都在场、字非空、两档不共用一个词）；**徽章在行上仍是一枚徽章（≥1.5:1）**——10% 淡底单独离行底只有 1.16–1.22:1（P-21 因此判过软配方一次），答案是那圈 30% 的同色边：各档、各行底、明暗两套都在 1.8–2.5:1；它是徽章自己颜色的线，不是实色外那圈读成光晕的灰（浏览器故事「BadgesOnRowsInLightTheme／InDarkTheme」量每枚最外 1px 对三档行底）；
- **浅色三档的 token 留足余量**：徽章字是最小一号（13px）。浅色 success／warning 取调色板 800 档、destructive 取 700 档，暗色用 400 档——这是实底时代为白字留的余量，软配方下同样是深字能在淡底上过 4.5 的原因。`--success-foreground`／`--warning-foreground` 随实底一起删掉（没人读的 token 是没人守的契约），`--destructive-foreground` 留给还写在实底上的执行那一颗（`DestructiveAction`）；
- **没有语气的徽章靠 `border-input` 的边活着**：选中行底 `bg-muted` 与 `secondary` 底色同档，没边就只剩一个词。用 `input` 不用 `border`：主题里 `input` 是一个东西自己的边、按 ≥3:1 守着，`border` 是东西之间的线、在选中行上同样消失（`styles.css`）。注册表本就画 `border border-transparent`，这里只给它颜色。它没有圆点：圆点说「这是一个状态」，没有语气的值只是一个值；
- **`danger` 的淡底写两遍 `bg-destructive/10 dark:bg-destructive/10`**：注册表 `destructive` variant 是 `bg-destructive/10` 加 `dark:bg-destructive/20`，不带前缀的覆盖换不掉暗色那条，暗色就会留着上面那条过不了线的 20%；
- **颜色从来不是唯一的区别**：字已说了状态，语气只是重音（WCAG 1.4.1）。`data-tone` 写在元素上，宿主着色、测试读语气都不必比颜色。

## 表格 chrome：层次与冻结列

自上而下三层，首尾两层是**同一档灰带**，数据行夹在中间，行间只有发丝线。表**自己不画外框**：结果区那圈边就是它的边（[README.md#工作台骨架](README.md#工作台骨架)）。

```
┌───────────────────────────────────────────────┬───────┐
│ 表头带  sticky top-0 · bg-muted（= 汇总带）            │  ← 列名是正文墨色 + medium
├───────────────────────────────────────────────┼───────┤
│ 行 · 静息 bg-background                        │       │
│ 行 · 悬停 不透明的 muted 半色（color-mix）      │ 操作  │  ← 选中 data-state=selected：
│ 行 · 选中 bg-muted（悬停不冲淡）                │ 冻结  │     bg-muted，且 hover 不改写
├───────────────────────────────────────────────┼───────┤
│ 汇总带  sticky bottom-0 · bg-muted                     │  ← 本页 / 全部 各一行
│   本页 │ …右对齐的数字…                                │     （只有一页时只有「全部」）
│   全部 │ …右对齐的数字…                                │
└───────────────────────────────────────────────┴───────┘
  ←──────────── 横向滚动条在汇总行之下 ────────────────→
```

- **两条灰带**：表头与行同底时第一行读起来像表头的一部分。两带同一档 `--muted`，共用 `ui/record/columns.ts` 的 `BAND`／`BAND_ROW`（同一个常量而不是两处巧合）；底色写在 `<tr>` 上，因为冻结格子 `bg-inherit` 从行上取；
- **列名用 registry 的 `text-foreground font-medium`**：灰字压灰带跌破 4.5，`HEAD_CELL` 不再覆盖它；
- **排序按钮悬停亮成行底**（`--background`）：`ghost` 的 `bg-accent` 在本主题与 `--muted` 同档，压在带子上看不见；
- **分隔只有格子的 1px**：分开的边框模型里行与行组没有自己的边；分隔主要靠带子，发丝线只是收边（浏览器故事 `HeaderBandInLightTheme`／`InDarkTheme`）；卡片布局没有表头，汇总在卡片下自己一行（D18 Ⅴ）；
- **富余归末尾那一格**（`ui/record/Filler.tsx`）：`w-full` 的自动布局表按比例把富余分给各列，宽列更宽，一眼扫过要横穿屏幕。每行末尾一格 `width: 100%` 的空格子吃掉富余，各列落在内容要的宽度。**它不是一列**：`aria-hidden`、不在 `table.columns`、不固定、封顶扫描跳过；排在一切之后（含操作列），`ColumnResizer` 按 `cellIndex` 找格子不受影响；D13 右边那道线仍在最后一个数据列上。不用 `max-width: max-content`：那会让行线、悬停与汇总停在表右缘，表浮在盒子里；
- **表头按钮可占满格子的内边距盒**（`HEAD_BUTTON`，`calc(100% + 1rem)`）：按钮用 `-mx-2` 抵消 `px-2`，而 `max-w-full` 卡在内容盒上会截掉列名；一分不多，末列按钮溢出会让每张表自报比端口宽；
- **一个滚动容器，且真的会滚**：`scrolls` 默认 true 时 `RecordTable` 自己是容器（`overflow-auto`），高度由它站的位置定：在工作台里吃掉工作列留给它的高度（见下一条，不再量视窗）；别处（宿主页面里的嵌入视图）上限取 `--fve-record-table-max-h`，缺省 70vh。注册表 `Table` 自带的容器取消滚动：嵌套时粘性认里层；
- **工作台永远填满它的容器，页脚永远贴底——高度布局只有一种**（2026-09-23，用户按推荐定，借鉴 legacy 控制台）：从前有三种——页面流里表格按量出来的「到视窗底」封顶，行少、加载中、空结果、编辑器展开时分页浮在半屏；宿主给了高度时靠把根量两次探测出来再按高度排；铺满屏幕又是第二种的一份拷贝。当天两个缺陷都是测量过期。现在工作台根永远是 `h-full`，外加保底 `min-h-[var(--fve-workbench-min-height,36rem)]`（容器没有确定高度时，保底就是它的全部高度）；铺满屏幕是同一条链、容器换成视口（`min-height: 0`，短屏上不越过视口）；什么都不量。`styles.css`「A workbench fills its container」：侧栏与工作列各自滚动；结果块 `flex: 1 0 auto` 吃掉上方剩下的高度、但不小于自身内容；装行的那一块——记录表的滚动口、卡片、分析结果——从 0 起分高度（`flex: 1 1 0`，下限 12rem）并在其中滚动，图表长满给它的高度而不是停在自己的最小高度、下面留白；分页 `margin-top: auto` 贴结果块底边。**合计行跟着贴底**：行留下的空间由 `useRoomBelowRows`（`ui/record/roomBelowRows.ts`）量出、画成表体后一个不画任何东西的 `tbody[data-slot=row-room]`（`aria-hidden`、单格跨全部列），本页／全部因此紧挨分页——表格多出的高度会被分摊给每一行，所以只能量。嵌入视图与仪表盘面板里的表格不在此列，它们随外层排版（浏览器故事「FooterStaysAtTheBottom」在 640px 容器里量分页、合计与框底，「HeldAtItsFloor」量没有高度的容器里停在 36rem 保底）；
- **外面有东西在滚就关掉 `scrolls`**：横向能滚的盒子在两个轴上都是 scrollport，表头会粘在没人滚的盒子上。`DashboardGrid` 的记录面板传 `scrolls={false}`，表头、汇总与冻结列顶着面板；
- **静着读的面板不钉末列**（`holdEnd={false}`，2026-09-23，D13 修正）：右冻结列只要表格溢出，**不滚也压在中间**——窄面板五列就溢出，首页「最近的活动失败」里 181px 的「最近更新」钉在 506px 可视宽的右缘，盖掉「已重试次数」整列和「错误码」23px，表头读成「已重试次」。实测封顶（D17-4）量得没错（端口 `clientWidth` 506／`scrollWidth` 649），只是一列 36% 不到一半，按规则留着——封顶管的是钉住的一组吃掉多少中间，不管一列盖住邻列多少。工作台里这是 D13 的框在干活（表是拿来滚着看的）；面板是一眼读完、很少横着滚的读数，框就是卡片本身。所以 `DashboardGrid` 的记录面板不钉末列（`TableLayout.end`，`heldColumns` 与操作列同一处放手，封顶也不再称它）；主键与左冻结列照旧——它们不滚时什么也不盖（test/dashboardUi.test.tsx「holds no end on the right」、test/pinnedColumns.test.tsx「holds no end where the surface is read at rest」；故事 `首页/回归` 的 `Fixture` 先断言面板溢出，再量每个冻结表头与其余表头的重叠为 0）；
- `<thead>` 与 `<tfoot>` 各自 `sticky`；**首次加载（`loading` 且 `hasResult` 为假）不画表头**：列来自结果，空 `<th>` 读屏念空列头、axe 算缺陷，拿草稿列名凑是替结果许诺。骨架行照画，刷新不受影响（test/recordTable.test.tsx「a record view with no result」）；
- **冻结列**：投影的 `pinned: 'left' | 'right'`（`ColumnEdge`）由 `tablePins` 折成每格的 `position: sticky` 与偏移——表头、数据行、汇总行同一列每格都带；
- **偏移按实测，只有左边有**：列宽由内容决定，声明 `width` 只是建议，选择列与操作列没声明。`usePinnedOffsets` 在布局后量带 `data-pin` 的表头格，写成 `--fve-pin-left-{i}`，格子以 `var(--fve-pin-left-0, calc(…))` 读——量不到时退回按声明宽度累加（从选择列 `2.5rem` 起）。右边最多一列（D19），一个 `right-0` 说完。宽度变化不经过 React，直接写 DOM；`ResizeObserver` 逐格盯参与累加的表头格（字体加载、宿主按钮变宽都会在表盒子不变时改列宽）；
- 冻结格 `bg-inherit` 跟着行走，**所以行的每种底色都必须不透明**：半透明悬停会让冻结格下滚过的列透出来。`TableDataRow`（`ui/variants.tsx`，D16-8，调用处只说 `data-state`）用 `--row-hover`（`color-mix(in oklab, var(--muted) 50%, var(--background))`，明暗各一份，宿主可改 `--fve-row-hover`／`--fve-dark-row-hover`），`has-aria-expanded` 同样处理；
- **边框模型 `border-separate`**（`TABLE_CELLS`）：collapse 下 Chromium 不画单元格外阴影。行线因此落在格子上（`[&_th]:border-b [&_td]:border-b`），汇总最后一行不画——那条是分页行的 `border-t`（故事 `PinnedEdges` 断言 `border-collapse: separate` 与悬停不透明）；
- **冻结列的边常在——只要能滚**（D13）：`--border` 发丝线加向外软阴影（`--pin-shadow`），静止即在——边说的是「两端钉着」，没被滚过的表没有框线会像布局散架。暗色里投影的是光，黑影在暗卡片上看不见。2026-09-23 视觉走查把软阴影减半：8px 短衰减、亮色 12% 黑／暗色 10% 白——30%／25% 投 12px 时两根固定列各披一条灰带，一张表读成三块面板；发丝线已说了列在哪结束，阴影只需说「行从它下面过」；
  - **只有边界格画边**：最后一个左冻结列与第一个右冻结列；选择列永远不是边界，操作列一定是（D19）；
  - **一个落点**：`ui/record/sticky.ts` 放 `sticky z-10 bg-inherit`、两侧 `in-data-[overflowing]:shadow-[…]`、左侧偏移与两条带子；`RecordTable`、`columns.ts`、`SummaryRows`、`SkeletonRows`、`Filler` 都从它取，三层同起同落；
  - **状态写在元素上**：`data-pin`（哪一边）、`data-pin-edge`（边界格）、`data-pin-index`（左冻结表头格的偏移变量）、带子的 `data-sticky="top"／"bottom"`。`usePinnedOffsets`、`usePinnedCap` 与测试读属性不读类名——类名证明不了屏幕上的事；类名只在 test/pinnedColumns.test.tsx「the sticky chrome recipe」断言；
  - **装得下时不画**：`useOverflowing`（`ui/record/overflow.ts`）量 `scrollWidth > clientWidth` 写成滚动口的 `data-overflowing`——装得下时那道边只会把行末富余切开、把填充格读成空列；溢出从填充格归零开始，末列不跳（test/pinnedColumns.test.tsx「the pinned edges」；故事 `PinnedEdges`：装得下无边、窄到 420 才有、滚到底仍有）；
- **列宽把手的层叠关在自己格子里**：把手 `absolute z-20`，表头格是 `relative isolate`，否则滑到冻结列头下的把手会透出来（stories/view-engine/RecordWorkbench.test.stories.tsx `PinnedEdges`）；
- **钉住的一组封顶为结果区可视宽的一半**（D17-4）：冻结列宽度固定，窄屏上能吃掉八成宽度。`usePinnedCap`（`ui/record/pinCap.ts`）超过一半就**从最外侧往里逐个放掉冻结**：
  - **顺序**：操作列、勾选列、左冻结列（从主键往外），最后才是末列（有行操作时它已放手）。最外两列是布局加的，先放；勾选与主键并排说「是谁、选没选」，留后；末列是 D13 的框，但很宽时会吃光中间，不死守。宽度为 0 的跳过；
  - **主键（`RecordColumnView.primary`）永远不放**，所以封顶是尽力而为；
  - **放得下就一个都不放**：判据 `clientWidth` 与 `scrollWidth`，容差 1px（`scrollWidth` 向上取整）——放掉冻结拿不回像素，却拆了 D13 的框；
  - **渲染而非编辑，但要说出来**：配置不动，表格经 `onReleasedPins` 报给工作台，列设置的固定按钮在那一行淡化并加「栏太窄，暂时未冻结」（`label.columns.pin-released`，`data-released`）——`aria-pressed` 说不出的那一半。操作列与卡片布局不报。栏拉宽即恢复（`ResizeObserver` 盯结果区与表头格）。判断在 **layout effect** 里，首次测量先于首次绘制；
  - **不管一列盖住邻列多少**：一组不到一半就一个不放，哪怕右冻结的末列在不滚时压着邻列——那是 D13 的框；静着读的面板为此干脆不钉末列（见上「静着读的面板不钉末列」）；
  - **代价**：操作列放掉冻结时右边 D13 的边跟着走（test/pinnedColumns.test.tsx「the pinned group against a narrow port」；故事 `PinnedGroupCapped`：420 一栏上中间可视宽不低于一半，拉宽收窄都走）；
- 选择列在**有列冻结在左**时一并冻结（封顶放掉时除外）；
- 数字列（`cell` 为 `number`）单元格与表头右对齐并用 `tabular-nums`。

## 列宽：拖表头的右边

每个数据列表头右边是一个手柄（`SortableHeader` 里的 `ColumnResizer`），改的是 `RecordColumn.width`：

- **手柄是 `separator`**：`role="separator"`、`aria-orientation="vertical"`、`aria-valuenow`／`aria-valuemin`，名字「调整 {列} 宽度」（`label.columns.resize`）。**静息就画 1px `--border` 发丝线**，悬停或聚焦 2px `--ring`——用过才出现的可供性不是可供性；
- **拖动写 DOM，不写 state**：每次 `pointermove` 一次 `setState` 会重渲整份结果。手势把宽度写到本列每个格子（表头、数据行、汇总行、骨架行），格子由 `<th>` 的 `cellIndex` 找；**松手**才落 `table.setColumnWidth(field, px)`（一次 `edit` 加一次 `apply`，[react.md#userecordtable](../react.md#userecordtable)）；
- **键盘**：←／→ 8px，Shift 32px，**每一下都提交**；Enter 与双击恢复自适应（`setColumnWidth(field, null)`，删键）。每步从**当前**宽度算——提交的宽度要等下一份结果才进投影；
- **整行表头一个 Tab 站**：`ui/record/headerRoving.ts` 的 `useRovingHeader` 给表头行 roving tabindex，←／→ 在列间走、Home／End 到两端；把手 `tabIndex=-1`，宽度改由 **Alt+←／→** 在焦点列上调（Shift 长步、Alt+Enter 恢复），以 `aria-keyshortcuts` 说出——否则每列两个站，宽表要按几十下 Tab（test/headerRoving.test.tsx）；
- **下限 48px**：列的边是找回它的唯一入口。这是手柄的规矩，住在 `ColumnResizer` 而不是 `validateRecord`（手写配置只要正数）；
- **宽度三个属性一起写**：`width` 单给只是建议，`min-width` 与 `max-width` 同值才没得商量；`<col>` 管不到格子；
- **有宽度的格子裁字并带 `title`**（`truncate`）；拖动中裁切临时写在格子上；
- 冻结列的偏移自己跟上：`usePinnedOffsets` 的 `ResizeObserver` 盯的正是表头格。

## RecordPagination

结果下面的一行，不进工具栏：翻页不留在配置里。

**外框借 `@shadcn/pagination`**（[decisions.md#D16](../decisions.md#d16-站在-shadcnbase-ui-肩膀上审计后的八条裁定) 裁定五）：`Pagination`（`nav`）+ `PaginationContent`（`ul`）+ `PaginationItem`（`li`），名字取 `label.pagination.nav`（「分页」）——唯一一组「带我去看其余记录」的控件得是有名字的地标。只借外框与语义，不用注册表的页码链接：游标源算不出页数。

整行**放得下一行，放不下两行**（`flex-wrap`）：计数 `whitespace-nowrap`，控件组 `ml-auto` 贴右缘，让步的从来不是那句话。

- **左：一共多少条**。`label.pagination.total`（「共 18 条记录」）说条件选中了多少。没有总数时（游标源）用 `label.pagination.on-page` 说数得出的那个数，不拿一页满不满推总数；
- **左：窗口那一句**。源声明了分页窗口（`RecordCapability.maxWindow`）且够得到的页装不下总数时，多一句 `label.pagination.window`「只能翻到前 10,000 条，缩小范围看其余」——Wow 走 Elasticsearch，窗口外的页是一个 400。它**不是警告**（没失败，出路在条件），弱色、无图标；是分页条（`nav`）与跳页框的 `aria-describedby`。数字按界面语言分组（`valueText`）；
- **右：每页几条**。`label.pagination.page-size`（「每页」）即控件名字，不另写 `aria-label`。档位来自 `table.pageSizes`（表格取 `limits.pageSizes`、卡片取 `limits.cardPageSizes`——一页卡片是整行；按 `runtime.limits.maxPageSize` 裁剪、并入当前值）。切换布局时条数在同一次编辑里换到另一套梯子上最近的一档（`nearestPageSize`，平局取大）：表格 20 条切到卡片是 24 张，切回来还是 20 条；按卡片打开的新视图默认也在卡片梯子上（24）。（见 test/useRecordTable.test.tsx「offers whole rows of cards, and moves the size between ladders」），选项用 `label.pagination.page-size-option`「20 条」——量词跟着数字走。`setPageSize` 立即应用——草稿里另有待应用修改时只并进待应用（与表头排序同一条规矩，[react.md](../react.md)）；
- **右：第几页**。`label.toolbar.page-of`（「第 1 / 4 页」），页数是内核的**够得到的**页数（`paging.pages`），出自**跑过的**每页条数——新值在路上时屏幕上还是旧那一批。总数未知时退到 `label.toolbar.page`（「第 1 页」）；
- **右：跳到第…页**（D18 裁定 Ⅷ）。总数已知时句子旁一个输入框（`Input`，`inputMode="numeric"`，名字 `label.pagination.go-to`），调 `goTo`：
  - **挨着句子而不嵌进去**：劈开「第 1 / 4 页」读屏会听到半句话、一个控件、再半句话；
  - 框里装**已落定**的那一页，落定（`index` 变）后跟着回来；跳页失败 `index` 不变，框子也退回；读者正在改的数不被刷新抹掉；
  - **Enter 或失焦才提交**：一次提交一次查询，`4` 是通往 `40` 的一个键；
  - **只认整数**：空、`abc`、`1.5`、`-2`、`1e3` 退回所在页（`Number('')` 是 0）；**超出夹到两端**，末尾是够得到的最后一页；
  - 游标源不画（无从校验）；只有一页也不画（D12 Ⅶ）；
- **右：上一页／下一页**，**只有一页时一个也不画**（D12 Ⅶ）：两个死箭头仍占两个 Tab 站说一个「没有」。判据是**页数数得出且停在第一页**：游标源分不清，照画；第 2 页及以后照画。下一页停在够得到的最后一页（`paging.hasNext`）。焦点顺序：每页选择器 → 跳页 → 上一页 → 下一页。

三条不随组成改变的规矩：

- 游标源既无页码也无退路，两样都不画；
- **首次加载（`loading` 且 `hasResult` 为假）整条不画**：这时每个数都是编的；刷新时照画那批行的数；
- 结果为空且已落定时不画（空结果自己说明），但**分页源停在第 2 页及以后时照画**——否则收走了「上一页」，人留在空页上无处可按。查询在途时计数跟着手上那批行。

（test/recordPagination.test.tsx「RecordPagination under a source window」、test/recordPaging.test.ts、test/accessibility.test.tsx「the pagination bar, mid-way through a paged result」；回归 story「Paged」「PagedWindow」）
