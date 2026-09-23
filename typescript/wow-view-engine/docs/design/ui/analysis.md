# UI 层：Analysis 视图

分析托盘、分析表格与图表。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；图表规格见 [model-shapes.md#图表规格](../model-shapes.md#图表规格)，图表校验与整形见 [kernels.md#图表规则](../kernels.md#图表规则)。

## 托盘：范围 → 维度 | 指标，一个应用

分析视图的编辑器是 `ui/analysis/Tray.tsx`（D20 屏 B），位置与形态跟记录视图的筛选面板**完全一样**：折在标题栏「分析」按钮后面，已保存的视图打开时折起、新建的展开，折起时按钮上带「改过没应用」的点（`editorPending`，由 `filter.pendingCount` 与 `analysis.pending` 合成）。结果才是视图的意思，而一个已保存的分析是作者已经决定过的问题——它不该每次打开都先占掉半屏。

- **托盘只装问题，怎么看是结果的事**（D20）。槽按分析师的步骤排，每一个是一个有名字的 `section`（`EditorSlot`，`data-slot="analysis-slot-*"`）：`RangeSlot` 独占第一行——它就是记录视图那块 `FilterPanel`，`submit={false} modes={false}`，只是换了个标题「范围」；下一行是 `DimensionCard` 的「维度 · 按什么比」与 `MetricCard` 的「指标 · 看什么数」两列，窄屏纵向堆叠。**表格｜图表、图型与合计行不在这里**：它们答的是「我怎么看这个结果」——表格｜图表与「可视化」落在结果第一行的 `AnalysisToolbar` 上（下一节），合计行在「可视化」面板的表格选项里；
- **没有「简单／高级」两套托盘**（结清 Q2）。更多能力按定义声明长出来，不长出第二套界面。唯一保留的简单／高级是**条件树的语法**（记录视图已有的 `filterMode`），标在范围槽标题右端的一颗 ghost 菜单「条件：简单 ▾」里（`data-slot="conditions-mode"`，装的就是 `FilterModes`）——它管范围，也管将来每个指标自己的条件，所以它属于条件而不属于面板。没有它，`defaultAnalysisConfig` 从 simple 起步的分析视图**永远表达不出 OR、NOR 与嵌套组**，那是少了一种能力，不是少了一个控件；
- **一个应用，跑整份草稿**（D17-3）。托盘底下是 `FilterActions`，`pending={filter.pending || analysis.pending}`：清空与一颗「应用」。**没有「运行」了**——范围的条件与问题本来就是一份配置，`useAnalysisEditor.submit` 与 `useFilterEditor.submit` 调的是同一个 `runtime.apply()`，两个入口只是逼着屏幕把其中一个降成 `outline`。降级解决不了「哪个按钮跑查询」这个问题，删掉一个才解决。那颗点因此也只有一处：无论改动落在哪个槽，应用上带点，托盘折起时点在标题栏按钮上；
- **维度卡片**：字段显示名，加上它的类型要的那个控件——字段能被切两种以上时才画类型选择（`label.analysis.grouping-of`），只能切一种就只写那个词（一个改不了的选择不教人任何事，还白占一个 tab 位）；`DATE_HISTOGRAM` 多一个粒度选择（`label.date-unit.*`），`HISTOGRAM` 多一个区间宽度数字框；末尾一颗移除。「+ 添加维度」只列定义声明为可分组的字段，所以点不出一份跑不起来的查询；
- **指标卡片**：字段显示名加一个「汇总方式」选择——Wow 测量一个字段的六种方式（函数、去重计数、百分位、任一值）在卡片上是**一张单子**（D20 汇总方式），记录数自己一张卡、不带字段。百分位多一个数字框（Wow 的开区间，100 不是百分位、0 不是），最后一条指标的移除按钮禁用（聚合查询至少要一个指标）；
- **卡片自己的菜单**（`CardMenu.tsx`，D20 屏 B）：卡片上只放问题本身的那两三个控件，别的收进末尾一颗 `IconButton`（`data-slot="card-menu"`，`label.analysis.card-menu`「{name} 的更多设置」）。**不是多摆几个控件**——一张摆着六个控件的卡片读起来是张表单而不是一句话，而这些设置一个视图一辈子改一次；按卡片命名而不是叫「更多」，是因为一屏卡片不该有两颗同名的按钮。**同一张卡上的每个控件都按这张卡现在叫什么来命名**（`group.label ?? 字段显示名`）：改完名字，移除、维度设置、汇总方式与菜单一起改口，否则一张卡会一半叫「门店」、一半叫「仓库」；
- **显示名**（`CardName`，D20 显示名）：菜单第一项「改显示名…」（`label.analysis.rename`）把卡片上的名字换成一个输入框（`label.analysis.display-name`「{name} 的显示名」），回车或移开焦点提交，Escape 放弃，**清空则把名字收回去**而不是存一个空名（准入会以 `analysis.label.blank` 拒绝）。框子关掉时焦点回到打开它的那颗菜单按钮，不是丢回页面。离开只结算一次：回到菜单按钮本身就是一次失焦，若失焦也提交，Escape 就会把刚刚丢掉的字存进去；框子只在打开期间挂载，所以每次都从存着的名字起步，而不是从上次放弃的那半截；
- **空值单独一组**（`label.analysis.missing-bucket`，TERMS）是分析师的选择而不是内核的默认：勾上就是 `missingKey` 哨兵，缺值的记录单独成一组；不勾 Wow 直接把它们丢掉。所以新加一个按值的维度**默认是勾上的**（`groupOfType`）——维度不该不声不响地少数记录——而字段担不起哨兵（非单值文本，`AnalysisFieldOption.missingKey`）时这一项根本不出现：一个勾上之后会在应用时被拒的复选框是在骗人；
- **补齐空的时段**（`label.analysis.dense`，DATE_HISTOGRAM）：没有订单的那个月是折线上的一个豁口，跳过它的折线在形状上撒谎。Wow 只允许唯一分组这么填，所以**旁边还有第二个维度时这一项禁用并换一句话**（`label.analysis.dense-alone`「补齐空的时段（只有一个维度时）」），而不是让它消失——消失的控件什么也不教；
- **「+ 添加维度」不再列已经分组的字段**：按同一个字段切两刀切出来的还是第一刀那些组，是没人问的问题（追问菜单的「再按…拆一层」出于同一条理由也把它排掉）。新加的时间维度**从范围推荐的粒度起步**（K4，`analysis.dateUnitFor(field)`，见 [kernels.md#粒度推荐k4](../kernels.md#粒度推荐k4)）：一年的订单按小时切是八千个没人要的桶，一周按月切是一个。它只是个起点，粒度选择就在卡片上，手选过的永远优先；
- **`replaceMetric` 而不是 `updateMetric`**：换汇总方式是**换指标类型**——合计变成去重计数、再变成任一值——`updateMetric` 那种 patch 会把上一形态的 `function` 或 `expression` 留在对象里让准入绊倒。卡片按 `metricOfSummary` 造一个完整的指标整只换掉（别名留着：它是查询的名字，图表与排序都指着它）。`updateMetric` 只用于同一形态里的一个数，比如百分位那个数；
- **排序与前 N 组**（`SortRow.tsx`，`data-slot="analysis-sort"`）落在指标槽底部：「前 N 组」只有挨着「按什么排」才读得懂。没有维度就整行不画——Wow 拒绝对无分组聚合排序，而它本来就只有一行；
- **「改了就跑」的开关落在动作那一行的左端**（D20，todo 批 7；`data-slot="auto-run"`，`label.analysis.auto-run`「改了就跑」）。一个复选框，与「清空／应用」同住托盘底下那一行，靠左、与那两颗按钮隔着整行：它管的是**那颗应用按钮还用不用按**，读者的眼睛从改动扫到按钮，正好经过它。措辞说的是这件事本身（「改了就跑」，英文是 "Run as I change the question"）而不是机制——没有「自动」「延迟」「300 毫秒」，一个开关的名字该是它带来的那句话。
  - **它是用户的偏好，不是视图的成员**（`ViewPreferences.autoRun`，见 [../management.md#列表偏好与默认视图](../management.md#列表偏好与默认视图)）。进了配置它就会随共享视图发给别人——「我习惯改完自己跑」不是作者对这个视图的表述，而共享视图保存时会把它一并写下，别人打开就被改了习惯；它也会让一个只改了这个开关的视图变脏、离开时被问一句「要保存吗」。按定义存，因为它是"我看这类数据时的手感"，切到同一个定义的另一个分析视图仍然成立。
  - **结果淡着，不清空**（`data-slot="analysis-result"` 上的 `data-stale`，读的是 `analysis.stale`）。等着那一下里屏幕上的行答的是上一个问题，但下一个答案只有几百毫秒远：清掉它们，屏幕会在每次改动后闪一次白，读起来是"坏了"而不是"在算"；留着不作声，又会让读者把旧数字当成新答案念出来。淡一档是唯一说得清的第三种——还看得见，且明说它不是最终答案。范围改了不算（条件等应用，那时候结果一点也不淡），所以"淡"始终只意味着「有一次运行在路上」。**那一层包装自己占一个盒子**（一列 flex，`min-w-0`，与结果块原先摆这三件东西的方式一模一样），不是 `display: contents`——不生成盒子的元素照样算得出 `opacity: 0.6`，却一个像素也不会按它画，屏幕上于是什么都没发生而测试还在通过。故事因此量的是**浏览器拿这个属性做了什么**（盒子在不在、画出来有多淡），不是属性在不在。
  - 开关关着时托盘与从前一字不差：应用是唯一的跑法，点还在按钮上。（见 test/autoRun.test.tsx「改了就跑: the tray’s switch」「改了就跑: an analysis runs as it is edited」与 stories/view-engine 的 `RunsAsEdited`）
- **跑不起来的配置从状态行回到托盘**：`errorAction` 是一颗「打开分析」（`label.analysis.open-editor`），因为发现是关于托盘的，而托盘可能正折着（F11）。
- 纯规则分两处，卡片因此只剩标记：维度与指标的**形状**出自分析内核唯一的构造器（`groupOfType`、`metricOfSummary`、`summaryOf`、`summaryChoices`，以及「还能加哪些维度」的 `groupableFields`，见 [kernels.md](../kernels.md)），托盘只在 `ui/analysis/editing.ts` 里决定自己的那一份——别名（`freeAlias`）与字段起步时的类型和汇总（`defaultGroup`、`defaultMetric`），外加 `fieldOfMetric`。（见 test/analysisTray.test.tsx「the analysis tray」「opens a saved view folded, and the toggle opens the tray」「lays the slots out as range, then dimensions beside metrics」「carries one primary button on the screen, and it is Apply」「marks Apply while any slot holds something that has not run」「reaches the condition grammar from the range slot’s heading」「opens the tray from a config that will not run」「the tray’s dimension cards」「the tray’s metric cards」「swaps the whole metric when the summary changes」、test/analysisCards.test.tsx「a tray card’s menu」「a display name」「drops the edit on Escape, and starts fresh the next time」「titles the header and the reading once it has run」「the sentinel bucket」「filling in empty periods」「the granularity a new time dimension starts at」「the fields a dimension may be added on」与 stories/view-engine 的 `TrayFolds`、`TrayEdits`、`TrayCardMenu`）

### 指标的条件：只算满足条件的记录

D20 屏 H。一个数是在哪些记录上算出来的，跟这个数本身一样要紧：一屏卡片上两个「金额 的 合计」，差别只在其中一个「只算已付款的」——看不见这句话，两个数就都读不懂。规则本身（一个指标位的筛选能说什么）在 [kernels.md](../kernels.md#analysis-内核的规则) 与 `analysis/queryFilter.ts`，这里只说屏幕。

- **入口是卡片上的漏斗，不是菜单里的一项，也不是一个对话框**（`ui/analysis/MetricCondition.tsx` 的 `ConditionButton`，`data-slot="metric-condition-toggle"`，名字是 `label.analysis.condition-of`「{name} 只算满足条件的记录」）。条件属于它收窄的那个指标，所以它长在那个指标上：块就开在卡片下面（`data-slot="card-conditions"`，`role="group"`，与按钮同名），编辑的时候旁边那几张卡都还在，"这个数和那个数差在哪儿"是一眼能比的。对话框会把其余的卡片盖掉，正好盖掉唯一要对照的东西。按钮在块开着的时候 `aria-pressed`，指标带着条件的时候 `data-held` 并换成 `secondary` 变体——"这个数是收窄过的"是卡片折起来也要说的事；
- **块里就是范围那一套药丸**（`GroupBlock` 套在 `react` 层的 `treeController` 上，标题 `label.analysis.condition-title`「只算满足条件的记录」）：同样的字段清单、同样的操作符选择、同样的值控件、同样的「Add in this group」。写条件是一种手艺，不是两种；条件树的简单／高级也还是范围槽标题里的那一颗菜单管着，因为它管的是条件的语法，不是某一块面板；
- **只列拿得出单值的字段**（`analysis.conditionFields`：作用域里 `kind.scalar !== false` 且不是 fieldless 的那些）。指标条件是逐条记录判「这条算不算」，而数组字段一条记录里有好几个值、全文检索字段一个值也不指——Wow 在指标位上拒绝它们（`analysis.metricFilter.not-scalar`）。所以它们根本不在清单里，而不是列在清单里、勾上了再在应用时被顶回来：一个勾上之后会被拒的复选框是在骗人；
- **没写完就不跑，而且说得出是哪一条**。空条件编译成 `MATCH_ALL`，于是一个本该只算已付款的数悄悄覆盖了全部记录——数错了，还没有任何提示。所以「写了但空着」是错（`analysis.metricFilter.empty`／`.incomplete`）：药丸上带 `data-invalid`（块拿到的是 `['metrics', i, 'filter']` 下重新定位过的发现），编辑器上方的状态行把那句话说出来——这条发现落在范围的药丸带不动的一棵树上（`unmarkedErrors`），状态行是它唯一说得出口的地方；
- **两条出路**：「去掉条件，算全部记录」（`label.analysis.condition-remove`）把 `filter` 这个键整个删掉并收起块——配置是普通 JSON，留一棵空树在那儿等于留着一个「没写完」；「收起条件」（`label.analysis.condition-close`）只是收起来，条件留着；
- **收起之后卡片上留下一句话**（`data-slot="metric-condition-line"`，`label.analysis.only-where`「只算 {conditions}」，由 `describeFilter` + `summaryText` 说出来，与「正在显示」那条用的是同一套词）。一个数的读法不该藏在图标后面：漏斗只说"有条件"，这一句说的是"什么条件"；
- **复制「{name}」并加条件**（菜单第二项，`label.analysis.copy-with-condition`）：同一个字段的两个数在两组条件下比，是这颗漏斗存在的理由，而第二张卡是问这个问题的方式。复制件紧跟在原件后面，拿一个空出来的别名（`freeAlias`：别名是查询与图表指着的名字），**不继承显示名**——两张卡叫同一个名字正是显示名要消解的那种歧义，而它马上要写的那个条件才是消解它的东西——并带着一个空条件，**当场打开**。菜单那一项许的就是一个条件，交出一张长得一模一样、什么也没开的卡片不算许了：所以「哪张卡的条件开着」这颗状态由指标槽持有而不是每张卡自己持有（`MetricSlot` 按别名记一张，`duplicateMetric` 交出复制件的别名），一张卡要开的本来就是另一张卡的块。派生指标没有这一项，也没有漏斗：它是别的指标之间的算术而不是一次记录走查，协议里就没有 `filter`。一个永远做不了事的控件不是禁用，是不画。

（见 test/metricCondition.test.tsx「a metric’s own conditions」「opens from the funnel, and starts the condition empty」「offers the scope’s scalar fields, and nothing without one value」「sends the metric’s own filter with the query」「waits for an unfinished condition, and says where it is」「takes the condition away altogether, and closes」「says what it counts, on the card, at rest」「copies a metric with an empty condition to fill in」「gives a derived metric no funnel at all」；浏览器里走一遍的是 stories/view-engine 的 `MetricCondition`）

### 展开：一条链，计数单位跟着最内层走

D20 屏 G。订单里有明细项，明细项里有批次——「按货号看销量」问的是明细项，不是订单。展开就是把计数单位从记录换成某个数组里的一条。内核的规则（谁留下、谁离开）在 [kernels.md](../kernels.md#展开链把问题重新划一遍范围expandts)，这里说屏幕。

- **槽夹在范围与那两列之间**（`ui/analysis/ElementsSlot.tsx`，`data-slot="analysis-slot-elements"`，标题 `label.analysis.slot.elements`「展开」，提示「数什么」），因为它改的是问题问的是什么，不是问题的答案。**能力没声明链的定义根本没有这一槽**（`analysis.expansible`）：一个空着的「展开」是定义从没许过的诺；
- **一层一张卡，中间一个箭头**（`data-slot="element-card"`，`data-path`）。链是一条线而不是一丛并列的数组，所以画成一条线：订单 → 明细项 → 批次。每张卡上是这一层的名字（它展开的那个数组字段的显示名）、它自己的门，和一颗收起；
- **每一层有自己的门**（`label.analysis.element-condition-of`「{name} 只算满足条件的明细项」，块标题 `label.analysis.element-condition-title`）：它决定这一层**哪些条目被展开**，所以只认这一层持有的字段（`analysis.elementFields(i)`，发现落在 `['elements', i, 'filter']`）——仓库是订单的字段，在明细项里什么也不指。它和指标的条件是同一个块（`ConditionsBlock`），因为它们是同一件事的两处：一处收窄要数的记录，一处收窄要展开的条目。没写完同样不跑（`analysis.elementFilter.empty`／`.incomplete`）；
- **「展开：…」只给声明出来的下一步**（`data-slot="expand-into"`，`label.analysis.expand-into`）。链是能力的，不是用户拼的：越过明细项直接展开批次是 `analysis.element.out-of-chain`，所以界面上根本走不到那一步，而不是走到了再被拒；
- **收起一层带走里面所有层**（`label.analysis.collapse`「收起 {name}」，`withoutLevelsFrom` 从这一层切断）：批次只在明细项里存在，明细项没有了，它无处安身；
- **一步进出都把问题重新划一遍范围**（`withElements`）：不再指向新单位字段的维度与指标跟着这一步离开，指标自己的条件若指着外面的字段则那个条件离开而指标留下，操作数都走光的派生指标离开；**什么都不剩时指标从这个单位数得出来的第一样东西重新起头**（`firstMetric`），因为一份没有指标的聚合查询什么也答不上来。这是"跟着走"而不是"报错"：用户刚说的是「我要数明细项」，界面该照办，而不是端出一屏 `analysis.field.outside-scope`；
- **脚注说现在数的是什么**（`data-slot="counting-unit"`，`label.analysis.unit`「计数单位：{name}」）：展开之后一行不再是一条记录，而「记录数」这个词自己不会改口。没展开时它说的是定义自己的标题；
- **展开了的分析追问不了**（`pickable` 为假）：一行是最内层元素的一组，根文档上没有哪条条件选得出来，见[追问](#追问点一组弹三项)。

（见 test/elementsSlot.test.tsx「the expansion slot」「exists only where the capability declares a chain」「takes a step along the chain, and re-scopes the question」「gates a level over that level’s own fields」「sends the chain and each gate with the query」「collapses a level, and everything inside it」；浏览器里走一遍的是 stories/view-engine 的 `TrayExpansion`，它到托盘为止——故事的内存数据源不求值 `elements`）

## 结果第一行：读法与看法

- **结果的第一行是 `AnalysisToolbar`**（`data-slot="result-toolbar"`，D12 Ⅳ）。左边一句「按 仓库 · 记录数、金额 的 合计」（`label.analysis.reading`，无维度时 `label.analysis.reading-flat`，`data-slot="analysis-reading"`）——下面这些数是什么，按**产生这个结果的那份配置**（`view.schema ?? view.columns`）读出来，不是按正在编辑的草稿；右边是怎么看它：表格｜图表与「可视化」。**合计行开关不在这一行**（2026-09-23 用户走查）：它从前是只有表格布局才画的复选框，一切到图表整排按钮就挪位；合计行是表格的设置，与表格的其他设置同在「可视化」面板的表格选项里（`TableDisplay`）；
- **表格｜图表是重绘，不是重跑**（D20，`ANALYSIS_PRESENTATION_MEMBERS`）：结果的行来自跑过的那份配置，怎么看它来自草稿，所以换布局只是把同一批行画成表或画成图，不发查询、不算待应用；「可视化」在这一行打开左侧栏的图型网格（下一节），托盘里没有它。合计行是一次自己的查询，所以在面板里按下即 `setTotals` + `submit`。（见 test/analysisTray.test.tsx「the analysis result toolbar」「keeps the totals switch off the toolbar in either layout」「reads the result out as dimensions and metrics」「keeps the way into the visualization beside the layout switch, not in the tray」与 test/analysisUi.test.tsx「redraws the layout from the rows on hand, without a run」）

## AnalysisChart 与 shapeChart

- **行与列读的是产生当前结果的那份配置（`ViewResult.config`），怎么看它读的是草稿。** 别名只有那份配置说了算——类目按别名找列取标签，草稿的别名在应用之前可能已指向别的列；而"画成表还是画成图、画成哪种图"是结果的属性而不是问题的一部分（D20），所以它们读草稿，拿同一批行重画。两者的接缝在 `react/useAnalysisResult.ts`（[react.md#useanalysisresult](../react.md#useanalysisresult)），`ui/workbench/AnalysisParts.tsx` 只画它交出来的东西：**草稿的形态与跑出这批行的形态不一致时**，图表规格先过一遍 `fitChartSlots` 落到跑出来的那个形态上——托盘里刚加、还没应用的那个维度不是这批行的列，指着它的图什么也画不出来；一致时图表**原样**画，因为 `fitChartSlots` 会把作者收窄过的槽重新放开（两个指标只画一条系列的柱状图会变回两条），那在形态挪动时是对的，在每一次重绘里是错的。图表只画内核已经整形好的数据：透视、合并"其他"、漏斗累计与转化率、热力图矩阵、比较值都在 `shapeChart` 里完成，`AnalysisChart` 只选标记与配色，换一个图表库不触碰任何规则。`AnalysisChart` 按 `spec.colors` 给系列或分类上色，其余按 `--chart-1..5` 顺序取用；
- 五档色相在亮暗两种模式下各自校过分离度与对比度。热力图与漏斗自绘，用图表库画它们的成本高于收益。**布局与图型都是重绘，不是重跑**（D20，见下一节）：工作台拿回来的那批行用 `shapeChart` 按草稿的图表规格现整形，所以 `useAnalysisEditor.setLayout` 与 `setChartType` 只编辑草稿，不 apply；
- 托盘里的改动等「应用」，等着的时候那颗点在应用按钮上（`data-pending`，`filter.pending || analysis.pending`，基准是整份配置，见 [ui/README.md#三态各有一处凭据](README.md#三态各有一处凭据)），被拒的应用同样算没应用。**同屏唯一的 primary 就是它**（D17-3，[版式](README.md#版式三块一套间距一种选项控件)）：范围与问题是一份配置、一次 `runtime.apply()`，所以只有一颗按钮跑查询。（见 test/analysisChart.test.tsx「AnalysisChart」、test/analysisUi.test.tsx「useAnalysisEditor」、test/analysisTray.test.tsx「carries one primary button on the screen, and it is Apply」与 test/analysisChart.test.ts「shapeChart」）

### 一个家族一个文件

- `AnalysisChart.tsx` 只剩按 `data.type` 分派，外加把类目标签器交给家族；六个家族与它们共用的工具各自成文件，改一个家族不必通读另外五个：

| 文件                          | 管什么                                                                                          |
| ----------------------------- | ----------------------------------------------------------------------------------------------- |
| `ui/AnalysisChart.tsx`        | 按 family 分派；`AnalysisChartProps` 是对外的那一个                                             |
| `ui/charts/Cartesian.tsx`     | bar／line／area／combo，双数值轴、参考线与 `mark` 选标记                                        |
| `ui/charts/PieSlices.tsx`     | 饼图与环形图，「其他」那一片                                                                    |
| `ui/charts/ScatterPoints.tsx` | 散点，第三维走 `ZAxis`                                                                          |
| `ui/charts/Heatmap.tsx`       | 自绘网格                                                                                        |
| `ui/charts/Funnel.tsx`        | 自绘阶段条与转化率                                                                              |
| `ui/charts/MetricCard.tsx`    | 指标卡：值、比较、目标与迷你趋势                                                                |
| `ui/charts/palette.ts`        | `--chart-1..5` 取色与 `spec.colors` 的覆盖（值在进 `<style>` 前再校一次）                       |
| `ui/charts/axis.ts`           | 数值格式、轴域与刻度格式、左右轴归属                                                            |
| `ui/charts/family.ts`         | `FamilyProps`（每个家族收到的同一份 props）、值标签器 `useValueLabel` 与列标题 `useColumnTitle` |
| `ui/charts/TooltipValue.tsx`  | 提示里的那一个数值，按它所在列的读法                                                            |

- 家族组件都不导出到包外：`/ui` 的出口只有 `AnalysisChart`，换一种画法是换 `charts/` 下的文件，不是换一个公开 API。（见 test/analysisChart.test.tsx「AnalysisChart」）

## 图表怎么被读出来

- **画出来的部分是一张有名字的图，数字在它旁边。** recharts 默认打开 `accessibilityLayer`，给根 `<svg>` 挂上 `role="application"` 与 `tabIndex={0}`：读屏会因此退出浏览模式、把按键交给一个没有任何键盘处理的元素，而那个元素里只有坐标轴刻度、一个空 `<title>` 和一个空 `<desc>`——查询回答的数字一个都不在。`charts/asImage.ts` 把这一层关掉，换成 `role="img"` 加 `aria-label`；热力图与漏斗是自绘的 `div`，同样以 `role="img"` 加名字整块作为一张图。图里于是没有任何可聚焦的元素；
- **名字说的是"画的是什么"**：`{图型}：{度量}，按 {类目}`（`label.chart.figure`），度量与类目都按别名回 `AnalysisView.schema` 取列标题，取不到就只剩图型名。指标卡没有类目，用 `label.chart.figure.plain`；它的迷你趋势线另有一个 `label.chart.sparkline`；
- **可读替代来自同一份投影。** `charts/reading.ts` 从 `ChartData`——而不是旁边那份行投影——生成 `{name, header, rows}`，`ChartReadingTable` 用注册表的 `Table` 以 `sr-only` 渲染在图旁边，于是屏幕上画了什么、读屏就读到什么，两者不会各说各话：图上有、这里没有的值，只能意味着某个家族画了内核没整形过的东西。数值按该系列所在坐标轴的格式打印（`percent` 轴上的 0.25 读作 25%），空洞读作 `label.summary.unavailable`；
- **指标卡的值本来就是文字**，所以它整张卡不是图：值与带符号的比较照旧是可见文本，只有进度条背后的目标、比较的原值与趋势的每个点进 `sr-only` 表——那三样只有画出来的部分知道。（见 test/analysisChartA11y.test.tsx 与 test/accessibility.test.tsx「analysis, drawn as a chart」）
- **那根进度条就是注册表的 `Progress`**，不是一个靠内联 `width` 撑长度的 `div`：会填充的条本来就是 progressbar，角色、当前值与上下界只有真的那个才带得上。它以 `label.chart.target` 为名，`aria-valuetext` 用 `label.chart.target.reached` 把位置读成背后那两个数（`{value} of {target}`），而不是角色默认要念的那个百分比；目标为 0 时不做除法——没有可差的距离，要么到了要么没到。轨道高度在调用处抬到 2px（与导出进度同一个写法），`ui/components/**` 是上游的，不手改。（见 test/analysisChartA11y.test.tsx「the metric card still says its value out loud」）

## 追问：点一组弹三项

- **手势是"按下这一组"，而不是"按下某个按钮"。** 指针按在标记上——柱子（`charts/Cartesian.tsx`，挂在 recharts 的 `<Bar>` 上）、扇区（`PieSlices.tsx`）、热力图格子（`Heatmap.tsx`）、散点（`ScatterPoints.tsx`）——键盘按在表格的行上（`AnalysisTable.tsx`：行 `tabIndex=0`、`aria-haspopup="menu"`、`data-pickable`，Enter 或空格弹出同一个菜单）。四个家族与表格交出的都是同一个 `onPick(row, anchor)`（`charts/family.ts`），所以一个菜单服务所有布局；
- **键盘那条路是表格布局，不是图旁边那张 `sr-only` 表（F10）。** 画出来的部分整块是一张 `role="img"` 的图，里面一个可聚焦元素都没有（[图表怎么被读出来](#图表怎么被读出来)），而 `ChartReadingTable` 是读屏用的替代文本，本来就摆在指针与 Tab 都够不到的地方——把它做成可操作的，等于把"读得到"和"点得动"混成一件事。两种布局回答的是同一个问题，所以键盘的入口是切到表格：那里一行就是一组；
- **三项**（`ui/analysis/DrillMenu.tsx`，画的是 `useAnalysisResult().followUp(row)` 交出的动作列表，哪几项、什么顺序、按下去做什么都由控制器定）：**查看这些记录**（`workbench.drill(conditions)`，在同一个工作台里开出未保存的记录视图，带「来自」那一条，见 [react.md](../react.md) 的「持有的视图」；`canDrill` 为假时这一项不在——不是禁用，是不画）、**再按…拆一层**（一层子菜单，列出能分组、当前结果又还没按它分的字段；选中即 `splitBy` 后 `apply`）、**只看这一组**（`focusOn` 后 `apply`）。后两项改的是当前这个分析视图的配置，所以它们和手改编辑器一样会变脏、可撤、可保存；
- **菜单的标题就是这一组的条件**，由 `drillConditions` 交出、`describeFilter` 描述、`summaryText` 说出来——与「正在显示」那条用的是同一套词，所以"我点的是哪一组"和"现在筛的是什么"读起来是一句话的两半。标题写在菜单组**里面**：它标的就是组里这几项，读屏进到组里先听见条件；
- **贴着按下去的那个东西弹**：标记交出自己的元素，柱子、扇区与散点交出按下的那个点（`pointAnchor`），`ui/popups.tsx` 的 `DropdownMenuContent` 因此多一个 `anchor`。菜单**没有**自己的触发控件，但 Base UI 把菜单在浮动树里的节点挂在 Trigger 上，没有 Trigger 的根会把自己的子菜单当成兄弟菜单、一展开就把自己关掉——所以 `DrillMenu` 画一个谁也够不到的 Trigger 只为占住那个节点，焦点去哪儿由 `finalFocus` 说了算：关掉菜单，键盘回到按下的那一行；
- **展开了 elements 的分析不可按**（`pickable` 为假）：它的一行是最内层元素的一组，根文档上没有哪条条件选得出来，`drillConditions` 也交不出条件——于是标记与行根本不带这个手势，而不是弹一个三项都不灵的菜单。（见 test/drillMenu.test.tsx「the follow-up menu on one group」与 stories/view-engine 的 `FollowUpToRecords`／`FollowUpFocus`／`FollowUpSplit`）

## 刷新落在标题栏

- 刷新那个拆分按钮落在标题栏右组的视图级控件里（`WorkbenchShell` 的 `freshness` 槽）——它问的是「这一屏多久自己更新一次」，属于视图而不属于结果，所以它与结果工具栏上那几个「我怎么看这个结果」不在一处。规则与措辞与 Record 的那一个完全相同，见 [README.md#刷新是一个拆分按钮](README.md#刷新是一个拆分按钮)。`QueryStrip` 上的「重试」是失败后的出口，与它不是一回事：一个是出错了再来一次，一个是没出错也每隔一段时间来一次。（见 test/refreshControl.test.tsx「every workbench offers the interval」）

## 被截断的分组要说出来

- 分组没画完时，屏幕上的行只是真实分组的一个前缀，于是这一屏的每个占比、每个百分比、每个扇区都是拿"已显示的部分"当分母算出来的。饼图是最坏的一种：它的全部含义就是"各部分占整体多少"，而整体已经不在图里了。这件事读者自己查不出来——一张被截断的表和一张完整的表长得一模一样；
- **多要一行比猜一行强**（D20 Ⅷ）。聚合至多回答 `limit` 行，并不说它省略了多少，所以"恰好填满上限"曾经是唯一可用的信号，而它是二义的：刚好这么多组，和被截到这么多组，长得一样。现在引擎发查询时要 `limit + 1`（`analysisProbeLimit`）：那一行回来了就是"还有更多"，没回来就是"没有更多"，二义变成答案。**探针行只回答问题，不上屏**——`projectAnalysis` 把它丢掉，表格、图表与"其他"片都按读者要的那 N 组算，判据见 [../kernels.md#compileanalysis-与-projectanalysis](../kernels.md#compileanalysis-与-projectanalysis)；
- 于是句子从"可能"变成事实：`analysis.result.more-groups`，「只显示了前 {limit} 组，还有更多未列出」。**它的级别看画的是什么**：画成饼图时是 warning——扇区是「前 N 组」内部的份额，截断了就会被读成整体；其余（表格、柱、线……每一行的数都是真的，视图本就只要前 N 组）是 note，状态行用安静的 info 条说一句（`NoteStrip`），黄色留给真有问题的时候——一屏常常对自己被要求做的事发警告，读者就不再读警告了。它交给 `WorkbenchShell`，状态条在**表格与图表之上**，两种布局各画各的，这一行是共同的，切换布局不会把它丢掉；
- **唯一问不出来的那一种**：配置的上限已经顶到天花板（能力声明的 `maxLimit`，或 Wow 自己的 `AGGREGATION_LIMITS.MAX_LIMIT`）时，多要的那一行会让整个查询被拒，探不成。这一种保留旧读法与旧措辞——`analysis.result.at-limit`，「只显示了前 {limit} 组，可能还有更多未列出」——因为那时"恰好填满"确实是全部已知的东西；
- 合计行照旧来自自己的无分组查询，**所以不管截没截断它都覆盖范围内全部记录**——可见的几行加起来小于它们下面的合计，两个数都没错，正是这条 warning 要解释的事；表头把这句口径写在自己身上（下面「三条口径」）。（见 test/resultIssues.test.tsx「what the screen says about an analysis cut short」、test/analysisProject.test.ts「the probe row read back」与 stories/view-engine 的 `CutShort`／`CutShortTable`）

## 三条口径

D20 定下的三条数据口径，每一条都是"这个数看起来是甲，其实是乙"，所以都由屏幕自己说出来，而不是留给文档：

- **合计 = 范围内全部记录**（`label.analysis.totals-scope`）。合计行来自它自己那次无分组查询，所以它含着前 N 组之外的组、被「只保留」筛掉的组、以及没有维度值的记录。「合计」两个字单摆着会被读成"上面几行加起来"，而只要有东西被漏掉，两个数就对不上。这句口径落在写着「合计」的那一格上（`data-slot="totals-heading"`），`title` 给指针、`aria-describedby` 指着一段 `sr-only` 的话给读屏——**不用 `aria-description`**：那是一个只有 Chromium 实现的草案属性，用它等于只对一种浏览器说话。**没有用注册表的 `Tooltip`**：它要一个 trigger，而 trigger 是个既接 hover 又接 focus 的控件；合计格既不可聚焦也不可按，挂上去只有指针读得到，而为了让键盘读到就得给页脚每一行加一个 Tab 停靠点——在一张没有动作可做的表里放一个控件，比它解决的问题更贵。`title` 对同一个指针说同一句话，描述把它作为这一格的一部分说给读屏，不用"去打开"什么；
- **百分位是近似值**。表头前面一个「≈」（`columnTitle`，见下面「数字按它自己的列读」），`title` 与描述写「近似值」（`label.analysis.approximate`，表头带 `data-approximate`）。Wow 的百分位是近似计算的，而一个印到两位小数、挨着一个精确求和的 p95，读起来就是精确的；
- **「任一值」不保证每次一样**。汇总方式菜单里那一项写作「任一值（不保证每次一样）」（`label.summary.fn.ANY.item`），指标卡静止时在汇总下面还有一整句（`data-slot="metric-note"`，`label.analysis.any-note`）。两处两套措辞是有意的：表头经 `label.summary.of` 拼的是光秃秃的「任一值」——括号放在表头上，每读一行都要读一遍；而菜单是六选一、正在做决定的地方，警告就该在那里。（见 test/analysisTable.test.tsx「the three readings a result says out loud」与 test/analysisTray.test.tsx「the tray’s metric cards」）

## 数字按它自己的列读

- **坐标轴、提示、热力图格子、漏斗条与指标卡上的数，和表格里那一列是同一个读法**（`useValueLabel`）：一列在表里写作 ¥1,234.00、在提示里写作 1234，是同一个数的两种读法，而只有一种是那一列的。`AxisSpec.format` 仍然优先——那是关于这条轴的指令，`percent` 轴上的 0.25 就该读作 25%；
- **格式来自 `metricFormat(metric, field)` 而不是字段本身**（[kernels.md#指标的数怎么读](../kernels.md#指标的数怎么读)）：字段的 `numberFormat` 描述的是一个存下来的值，而一个整数字段的平均值不是整数，一个金额字段的去重计数不是钱；
- **语言也跟着界面走**：`valueText` 收 `DisplayContext.locale`，没有它的时候数字按运行这台机器的语言分组——那是唯一一种没人选过的语言，`zh-CN` 下写作 `CN¥` 而整页写的是 `¥`；
- **表头是两截拼出来的**（`columnTitle`）：内核交出字段显示名与汇总方式两个部件，中英各按自己的语序拼成「金额 的 平均」／`Average of Amount`（`label.summary.of`，与记录视图的列汇总同一套词）。同一字段的两个汇总方式因此是两个不同的表头，而别名（`amount_1`）从来不是谁起的名字；记录数自己一个词。（见 test/analysisTable.test.tsx「an analysis column header」「an analysis number」与 test/analysisChart.test.tsx「reads a numeric axis through the metric on it」）
- **卡片上写字段，提到它的地方写表头那一句**（`metricReference`）：指标卡的标题只写「金额」，因为汇总方式那个控件就在旁边；而在「只保留」、排序、派生指标的操作数，复制「…」并加条件那一项，以及移除、菜单、漏斗、改显示名这些读给屏幕阅读器听的名字里，旁边没有那个控件，于是它们说的是与结果列头同一句话——「金额 的 合计」与「金额 的 平均」是两个名字。汇总方式那个控件本身仍按卡片的名字命名：它的值就是另外半句。（见 test/analysisTray.test.tsx 与 test/havingRows.test.tsx）
- **百分位表头带「≈」**：`columnTitle` 在 `fn === 'PERCENTILE'` 时在前面加这一个字符，于是它跟着表头走到每一处——图表的坐标轴名、图例、提示里那一行都带着它，因为那些地方读的是同一个 `columnTitle`。一个符号胜过一句话：p95 印到两位小数、挨着一个精确的求和，不写它就读成精确值。符号背后的词在表头的 `title` 与描述上（见上面「三条口径」）；
- **日期的最早与最晚是日期，不是数**（[kernels.md#时间的最早与最晚](../kernels.md#时间的最早与最晚readsasitsfield-与-momentmetrics)）：`MAX(eventTime)` 这一列带着字段的 `cell`，于是表格、合计行、指标卡的主数、`sr-only` 读屏表都按字段读值（界面时区下的日期时间），表头、托盘的汇总方式、「只保留」与排序里提到它的地方都说「最晚」而不是「最大」（`columnTitle`／`metricReference`／指标卡的汇总方式都走 `summaryFunctionKey`，与记录视图的列汇总同一条）。日期字段的汇总方式单子上只有最早与最晚（任一值、百分位照能力声明），没有合计与平均；
- **分析表是自己的滚动口，表头与合计行吸在两端**（2026-09-23 用户：合计行参考记录视图，固定在底部）：与 `RecordTable` 同样把 vendored `Table` 的容器挪开（`overflow-visible`），`data-slot="analysis-table"` 自己 `overflow-auto`；表头 `stickyBand('top')`、合计行 `stickyBand('bottom')`（与记录视图的表头／汇总带同一档灰）；行少时 `useRoomBelowRows` 量出的空间画成不画东西的 `tbody[data-slot=row-room]`，合计行因此紧挨页脚。在工作台里这个口吃掉分析结果留给它的高度（`styles.css`：`analysis-table` `flex: 1 1 0`），分析结果自身不再滚动；别处（仪表盘面板、嵌入）它和行一样高、只横向滚。行不可按时口自己是一个 Tab 停靠点（`tabIndex=0`），否则键盘滚不动它。页脚那一句靠右，与记录视图的翻页同侧；
- **结果区有个底：最后一行是「正在显示 N 行，耗时 X 秒」**（`label.analysis.caption`，2026-09-23 用户：「底部就可以托住了，否则感觉报表会掉下来」）。它是结果块的 caption，与记录视图的分页同一份配方（`resultSlots('caption')`：上一条线、浅灰底、`px-4 py-2`），在填满容器的工作台里 `margin-top: auto` 贴底；图表与表格在它上面分剩下的高度。行数是屏幕上的分组行；耗时是运行时写在结果上的 `ViewResult.elapsedMs`——从提问到答案落地，引擎队列里的等待也算（人也在等），一秒内两位小数、一秒以上一位（浏览器故事「分析工作台/回归」的 `CaptionHoldsTheReport`；test/runtime.test.ts「says how long the answer took」）；
- **刻度字都在图里。** 数值轴与横向图的分类轴按刻度字自己定宽（recharts `width="auto"`），不再写死——分类轴从前固定 96px，把「OrderItemReservedTrackEventProcessor」从左边截成「kEventProcessor」；分类名超过 `CATEGORY_LABEL_MAX`（24 字）才以省略号截短，全名在 tooltip 里（`charts/axis.ts` 的 `categoryTick`）。刻度字以刻度为中心，最后一个会伸出绘图区半个字宽（真实服务上读成「2026年9月22E」「600,00(」），所以每张直角坐标图与散点图右侧留 `CHART_MARGIN.right`（40px，一个完整日期的一半）（浏览器故事「分析工作台/回归」的 `TicksInsideTheChart`；`TwoMetrics` 量两根纵轴并存）；
- **轴上全是整数，就没有小数刻度。** 记录数、计数之和这类指标：比例尺在 0 到 2 之间本会放 0.5、1.5，指标自己的格式把它们四舍五入成「1」「2」，轴就读成 2、2、1、1、0（真实补偿服务上发现，2026-09-23）。`charts/Cartesian.tsx` 的 `wholeOn` 按这根轴上的**值**判断（该轴上每个系列的每个点、以及落在该轴的参考线都是整数），而不是按指标种类——计数的平均不是整数，整数金额的合计是——整数轴给 `allowDecimals={false}`（浏览器故事「分析工作台/回归」的 `WholeTicks`：刻度全是整数且没有两个写成同一个字）；
- **时间点不画成图形，只写出来。** 柱子从零长、扇区占整体的一份、格子深浅按大小——一个时刻没有零点，也没有整体，把它的毫秒数画成柱长只会让每一根都一样高、从 1970 年长起。所以：可视化的数据页里，系列、饼与漏斗的值、热力格子、散点的两轴与大小这些「量的槽」只列数量指标（`OptionsShape.quantities`），时间点只能做指标卡的主数；以时间点为主数的指标卡不给「比较」、目标值与数值格式（`chart.metric.moment`）。指标全是时间点而又有维度时，每个图型都灰着写「要数量指标，时间画不成图」（`chart.fit.needs-quantity`），表格那张卡可选，结果区显示表格——**不去把时间轴做成日期刻度**：那样柱状与面积仍从轴的下界长起，最早的那根长度为零，读起来是"没有"，比不画更错；
- **提示里的那一行是自己画的。** 上游的 `ChartTooltipContent` 把数字写成 `toLocaleString()`，而它给出的唯一钩子 `formatter` 替换的是整行，所以色块、系列名与数值写在 `ui/charts/TooltipValue.tsx`——与 `ui/popups.tsx` 同一条缝，理由也一样：`ui/components/**` 是上游的，不手改。

## 空结果只有一句话

- 结果没有任何一组时，表格与图表说同一句 `label.analysis.empty`（`ui/analysis/EmptyResult.tsx`）。图表从前画一对空坐标轴——那读起来是"这张图坏了"，而不是"范围里没有符合条件的组"；
- 句子说的是**范围**，不是分析：从前的「没有可聚合的内容」读作"你这个分析算不出东西"，而指标好好的，只是没有组落进来。（见 test/analysisTable.test.tsx「says that no group matched」与 test/analysisUi.test.tsx「says that no group matched, chart layout included」）

## 可视化：结果工具栏呼出左侧栏，先选图型

D20 把可视化定为分析的**最后一步**：结果先是表格，确认完数据再谈怎么画。入口因此在结果工具栏右侧（`ui/analysis/AnalysisToolbar.tsx` 的 `data-slot="visualize"`，`aria-pressed` 说它开着没有），而不是在托盘里——托盘只装问题本身，图是结果的属性。

- **面板占左侧栏，不另开一栏**（`WorkbenchShell` 的 `panel` 槽，`data-slot="view-panel"`，[README.md#工作台骨架](README.md)）。视图列表是导航，配图的时候不需要导航；结果区因此一格不移，用户盯着的那张图不会因为开了个面板就跳一下。列表折起与否都画，**面板自带返回**（`label.chart.picker-back`），不靠列表把自己换回来。
- **第一层是图型网格**（`ui/analysis/ChartPicker.tsx`，`role="radiogroup"`），每格一张卡片（`data-slot="chart-tile"`，`data-chart-type`），三种状态各有各的凭据：
  - **在不在由能力决定**（D4）：定义没声明的图型根本不在网格里，灰着也不给——一个永远按不动的东西不是选项；
  - **灰不灰由形态决定，而且写明理由**：`fitCharts` 判（[kernels.md#哪些图型画得了这个形态fitchartsk3](../kernels.md)），画不出来的卡片带 `aria-disabled` 与一行 `data-slot="chart-reason"`，因为"为什么不能选"和"不能选"是两件事，只说后一件等于不说。**这句话要读得见**：卡片原来压一层 `opacity-60`，那行 11px 的灰字于是落到 2.20:1，唯一要说的话反倒最看不清；现在"画不出来"由虚线边与一层 `bg-muted/40` 说——边框与底色透不过文字——理由本身是 12px 的 `text-foreground/70`，与 `optionControls.tsx` 里 `OptionsSection` 那条判断同源：次要靠字重与框说，不靠淡到读不出；
  - **磁贴的名字是卡片上那个词，记号与理由是它的描述**（`aria-labelledby` 指着名字那一格，`aria-describedby` 指着 `chart-recommended` 与 `chart-reason`）。原来整块卡片挂一条 `aria-label`，它把卡片里的内容整个顶掉——于是「推荐」这枚为所有人画的记号，只有看得见的人拿到了。现在读屏听到的是「柱状图，推荐」「热力图，要两个维度」，也就是卡片本来写着的话；
  - **推荐带一个记号**（`data-recommended` 与 `data-slot="chart-recommended"`，文案 `label.chart.recommended`），至多一张卡片有。推荐是记号不是动作：手选之后不再自动换。
- **表格也是一张卡片**，排在最后。"回到表格"和"换成饼图"于是是同一个手势、同一处控件，而不是一个在工具栏的分段按钮、一个在面板里。
- **选完只重画，不发查询**：`layout` 与 `chart` 是呈现成员（`ANALYSIS_PRESENTATION_MEMBERS`，[model.md#viewconfigbase-的三个字段](../model.md)），`comparePending` 跳过它们，所以标题栏那颗「改过没应用」的点不为它们亮——按下去什么也不跑的点是在教人按没用的按钮。它们照旧随视图保存。
- **键盘按 radiogroup 的规矩走**：整组只有一个 Tab 停留点（选中的那张），方向键在**画得出来的**卡片之间同时移动选择与焦点，空格与回车就地选中。灰掉的那几张被方向键跳过——没有什么可选的——但它们用 `aria-disabled` 而不是 `disabled`：`disabled` 在有些读屏里连同那行理由一起从可访问树里拿走，而"为什么不能选"正是它唯一要说的话。

（见 test/chartPicker.test.tsx「the visualization panel」「a layout is a redraw, not a run」与 test/fitCharts.test.ts「fitCharts」；浏览器里的回归是 stories/view-engine 的 `VisualizePanel`）

## 可视化的第二层：选中图型的选项，三个页签

- **齿轮在磁贴旁边，不在磁贴里面。** 第一层的每块磁贴本身是一颗按钮（`role="radio"`），而一颗按钮里装不下另一颗按钮——嵌套的可交互元素读屏说不清、指针也分不出按的是哪一个。所以齿轮是磁贴的邻居而不是它的孩子：`IconButton`（`data-slot="chart-options-open"`，名字是「{图型}的选项」）浮在被选中那块磁贴的右上角，且只有被选中的那一块有它——没选中的图型谈不上"它的选项"。推荐标记因此让到磁贴左上角，两枚角标各占一头；
- **不论哪个家族，页都是同样那三页**：数据（哪个别名坐哪个槽）、显示（这张图怎么画）、坐标轴（数值轴的标题与范围）。`optionTabs(picked)` 说一个图型有哪几页：笛卡尔家族三页；散点只有"画哪两个指标"，一页；表格只有合计行，那是显示，一页；其余家族两页。**只有一页时不画页签条**——一条只有一项的页签条是个按不动的控件。结构按家族走而不是每个图型另起一套，是因为换图型换的是画法而不是这块面板：从柱状图换到饼图，"数据"仍然在第一页上；
- **数据页只有一条规则：位置槽列维度，度量槽列指标。** 横轴／拆分／类别／行／列／每个点是／阶段取自只列分组别名，系列／数值／横／纵／大小／对比只列指标别名，于是一个槽装不进不该装的东西，`validateChart` 的那几条别名规则在界面上根本无从触发。每个选项写的是**列标题**而不是别名（`useColumnTitle`）：「金额 的 合计」，而 `amount_1` 命名的是查询；漏斗的阶段写的是那个分组值自己的读法（`useValueLabel`）；
- **选另一个槽已经拿着的别名，两个槽对调**（`withSlot`）。按横轴拆分的图是 `chart.splitBy.same-as-x`，画不出来；而用户的动作分明是"把这个维度放到横轴上"。对调是唯一一种不丢东西的解释——两个槽仍各有人坐，没有谁需要重新选。热力图的行／列与散点的横／纵走同一条规则；
- **显示页上的设置是整张图一个选择。** 堆叠与平滑不是"某几个系列凑一堆"：`isStacked`／`withStacked` 与 `isSmooth`／`withSmooth` 要么全体加入要么全体退出，读回来时半数堆叠不算堆叠；只有一个系列又没有拆分时没有可堆的东西，那个框在那儿禁用着而不是不画——不画会读成"这张图不支持堆叠"；**禁用着就要说出为什么**（`label.chart.stacked-alone`，一行挂在框上的 `aria-describedby`），一个按不动又不出声的控件只会让人以为是坏了。**加入堆叠同时把所有系列收回同一根轴**：叠在一起的段是在相加，而两把尺子相加没有意义——跨两根轴的"堆叠"是每根轴各堆一摞，画在同一个位置、同样的宽度，于是高的那摞把矮的整个盖住，读起来是图坏了而不是一个和。取消堆叠不把轴还回去：哪个系列量在哪把尺子上是个选择，面板不替人猜一个旧的。图例与数值标签同理，一张图一份。图例的缺省是家族自己的答案（`charts/legend.ts` 的 `legendPlacement`）：饼图总有一个，笛卡尔图要到第二个系列才有，用户说了「无」就一个也没有。**颜色不在这里**（D20）：配色是主题的事；
- **坐标轴页只有笛卡尔家族有**，因为只有它有数值轴；类目轴没有可设的东西——它说的就是那个维度说的话。**右轴要等有系列坐上去才成为一节**：一条没有系列的轴不画，于是也没有它的标题与范围可填。四项（轴标题／最小／最大／数值格式）都空掉时 `yAxis.left` 整个消失，最后一侧消失时 `yAxis` 也消失——没人说过的事不该在配置里留下一个空对象；
- **表格那一项要跑查询，其余都是重画。** 合计行来自它自己那次无分组聚合（见[被截断的分组要说出来](#被截断的分组要说出来)），所以按下即 `setTotals` + `submit`；图表的每一处改动只改 `chart`，而 `chart` 与 `layout` 都是 `ANALYSIS_PRESENTATION_MEMBERS`，走 `updateChart` 重画屏幕上已有的行——不回后端，也不在标题栏「分析」那颗开关上点亮未应用的点；
- **系列的次序是拖出来的**（`ui/analysis/SeriesList.tsx`）。堆叠是从下往上读的，图例是从第一项读起的，所以哪个系列排在前面是个要由分析师定的设置——而它改的只有 `cartesian.series` 的次序，一个系列的别名、轴、画法一样也不动。手柄是记录视图排序编辑器与列设置用的那一枚（`ui/DragHandle.tsx`），行是那一份 `ui/RowItem.tsx`，「算不算一次放下」仍先过 `ui/dragDrop.ts` 的 `dropped()`，本列表在它之上只多问一句（`ui/analysis/drag.ts` 的 `seriesDrop`，和另外三处的 `drag.ts` 同一个家）：两个 id 是不是都指着这张图在画的指标——**一个系列拿它画的那个指标的别名当身份**，因为一张笛卡尔图一个指标一条系列，「添加系列」给的正是还没画的那些。方向键在手柄上一按移一位，落点的那一句「已移到第几位」由面板自己的播报区说（`useAnnouncer('series-announcement')`，只在面板开着时存在，与排序弹层里那一个同理）；乐观排序插件和那两处一样不装——它在指针还在动时就重排 DOM，而列表正是按下标渲染的。真指针那条链路只能在浏览器里跑（库靠量盒子做碰撞检测），所以它是故事 `SeriesOrder`；
- **按指标分阶段的漏斗，每个阶段可以自己起名**（`items[i].label`）。指标的列标题说的是「量了什么」——「金额 的 合计」——而漏斗的阶段说的是业务的一步——「下单」——这两句很少是同一句，且只有分析师知道。名字就写在它画出来的地方：阶段卡上一只没有标题的框（`optionControls.tsx` 的 `NameField`），缺省名是它的 placeholder，清空即把 `label` 整个删掉、退回列标题。**不用托盘卡片那种「菜单里改名」的形态**（`CardMenu.tsx` 的 `CardName`）：这块面板整页都是框和下拉——轴标题、参考线的说明——阶段名不过是其中一只；而托盘卡片的名字是它的**标题**，卡上已经排满控件，给每个阶段再添一枚只装一项菜单的图标按钮，等于每个阶段多收一个 Tab 停留点去说那只框站在那儿就已经说了的话。画图与读数表读的是同一句（`charts/family.ts` 的 `stageName`）：**没起名时退回指标的列标题，而不是别名**——投影层只有配置，写不出列标题，于是 `label ?? metric` 把 `orders` 画到了屏幕上，而别名命名的是查询，不是任何人认得的东西（见 test/analysisChart.test.tsx「names a metric stage by its column until the analyst names it」）；
- **漏斗的阶段顺序从结果行里起头**（`withStagesFrom`）。阶段的业务顺序内核不知道，`fitChartSlots` 把 `order` 留空，而没有阶段的漏斗什么也画不出来——刚选中就是一片空白，读起来是坏了。所以在第一层选中漏斗的那一刻，就按结果行来的顺序把各分组值填进去（每个文本值一次），之后用户在数据页上用上移／下移排它。（见 test/chartOptionsUi.test.tsx「the chart options」「the chart options’ display page」「the chart options’ axes page」「the chart options of the other families」「what the chart options change on screen」「what a drop on the series list means」「what a series drag says out loud」、test/chartOptions.test.ts「chartOptions」与 test/chartLegend.test.ts「legendPlacement」；浏览器里走一遍的是故事「可视化面板/回归」的 `ChartOptionsPages` 与 `SeriesOrder`）

## 只保留：一行一条比较

「只保留」（`ui/analysis/HavingRows.tsx`，`data-slot="analysis-having"`，D20 屏 B）是 Wow 的 `having`：**结果分组出来之后、排序与截断之前**的一道筛选。它落在指标槽底部、排序行下面，因为它筛的是「组」而不是「记录」——范围槽那棵条件树决定哪些记录参与计算，这里决定算完之后哪些组留下，两件事没有一个控件能同时说。

- **它是一组行，不是第二棵条件树。** 一行一条比较（`data-slot="having-row"`）：「只保留 · 哪个指标 · 比较 · 数值」，行与行之间是「都要成立」。**每一行自己是一个有名字的组**（`role="group"`，`label.analysis.having-row`，从 1 数起）：一行里的四只控件都按"自己是什么"起名——指标、比较、数值、移除——三行摆在一起就是十二只重名的控件，读屏走过去听不出站在第几条上。显示页的参考线同理（`label.chart.reference-row`）。条件树能表达 OR 与嵌套，但分析师对分组说的话从来只有一种形状——「金额合计大于一万」「记录数不少于 5」——把第二棵树摆在这里，等于为一个没人问的问题付一整套控件的钱，还要让读者分辨两棵长得一样的树筛的是不同的东西。所以没有组操作符、没有分组块、没有「简单／高级」；
- **在不在由能力与形态一起决定**（D4）：定义没声明 `having` 就整块不画，没有维度也不画——Wow 拒绝对无分组聚合做 having，而它本来就只有一行。两种情况都是不画而不是禁用：禁用的控件要让人读出「我做点什么它就能用」，而这里没有那件事可做；
- **没有值的那一行是编辑器的，不是配置的。** 「只保留…」按钮（`data-slot="add-having"`）加出来的行先没有数值，它留在组件自己的 state 里；写进草稿的永远只有填完的那几条，于是**磁盘上的配置永远是 Wow 收得下的那一份**。填满一条是一个 `CONDITION`，两条是一个 `AND`，全删光连键都不留（`without` 的口径：配置是普通 JSON，一个挂着 `undefined` 的成员是任何新建配置都不会长成的样子）；
- **那一行小字是口径而不是提示**（`data-slot="having-note"`，「没有值的组不会保留。」）。指标算不出数的组——没有可加的记录、除数为零——比较不成立，于是被筛掉。这是 Wow 的口径，不是我们的选择，但屏幕上少了两组而没人说过为什么，读者只会以为查询错了；
- **指标单子里没有「任一值」**：Wow 的 having 比的是数，而样本值不是数，它会直接拒掉这份配置。一个选了就跑不起来的选项不是选项；
- **存着的、行说不出的形状，照实说出来再给一条出路**（`label.analysis.having-unreadable` 加一颗「清空」）。Wow 的 having 还有区间、集合、空值判断与 OR 树，一份配置可以存着它们中的任何一个。把一个 `BETWEEN` 摊成两行，是编辑器替作者写了一份他没写过的配置——下一次保存就把原件覆盖掉了。所以行不装、名字照说、清空是唯一诚实的动作。

（见 test/havingRows.test.tsx「keeping only some of the groups」「exists only where the capability declares it and a dimension is there」「adds a row that writes nothing until it has a value」「reads several rows as one AND, and unwinds to no key at all」「offers every metric but the sample value」「writes the comparison the row is set to」「shows a stored shape the rows cannot say, and clears it」「sends the having with the query Apply runs」与 test/having.test.ts「having rows」；浏览器里真的少两组的是 stories/view-engine 的 `KeepOnly`）

## 公式与派生：写出来的指标

有两种指标是**写出来**的而不是从字段里挑出来的（`ui/analysis/FormulaCard.tsx`，D20 屏 B），它们在「添加指标」菜单的最后一组，且只在能力声明 `expressions` 时出现：

- **按公式**（Wow 的 `NUMERIC` 套一个 `BINARY`）：每条记录上算一个数再汇总，「金额 − 成本」逐单算毛利、再在组里合计。它**不等于**「金额合计 − 成本合计」——在合计上碰巧相等，在平均、百分位上根本不是一个数；
- **按已有指标计算**（Wow 的 `DERIVED`）：同一行上两个指标的算术，「金额合计 ÷ 客户数」。它在后端于分组之后算，所以它没有自己的记录，卡片上因此**没有漏斗**——「只算满足条件的记录」对一个不读记录的指标无话可说。

两者共用一副控件：**两个操作数，中间一个运算**。操作数从一张单子里挑（公式挑字段，派生挑指标），或者选「数字」当场敲一个进去——两种形态是一个控件的两种样子，不是两个控件。**不提供嵌套**：一个能套括号的公式编辑器是另一件产品（它要处理优先级、要有语法、要有错误位置），而托盘要装下的是分析师口里那一句话；配置里手写的嵌套式子照样跑得起来，卡片把它当成一句读出来、不去动它。

- **名字就是它说的那句话。** 卡片上写「金额 − 成本」，结果的列头写「金额 − 成本 的 合计」——公式是一个被汇总过的数，所以列头是那两部分合成的（`columnTitle`）；派生指标的列头只有它自己那一句，因为它既没有函数也没有字段，后面再缀一个词就是凭空多出来的；而那一句里的每个操作数都是对另一个指标的引用，所以说成那个指标自己的列头——「金额 的 合计 ÷ 记录数」，卡片标题、操作数下拉与列头三处同一句话（test/metricReference.test.ts「says a derived metric by its own words, with nothing appended」）。别名是查询的名字，任何时候都不上屏；
- **派生指标只读得到它前面的、非「任一值」的指标**——这是 Wow 的规矩（前向引用与环会被准入拒掉），所以两个操作数的单子里**就只列那些**：规矩由控件说出来，而不是由一条错误说出来；
- **套了一层的操作数写成一句话，旁边一行说它改不了**（`data-slot="operand-text"` 与 `label.analysis.expression-unreadable`）：卡片编的是一个运算两个操作数，那个本身还是一个运算的操作数没有控件可落，于是把它按 `expressionText`／`derivedText` 读出来摆在原处——原先那里什么也不画，读起来像一条只有一个操作数的公式，那是缺陷不是取舍；
- **改一格就是改整条指标的 `expression`**：左、右、运算三处任何一处变动都重写整棵 `BINARY`，因为它们本来就是一棵树的三个位置，打补丁会留下上一形态的残渣。公式的「汇总方式」是 `function`，它与表达式无关，单独写。

（见 test/formulaCard.test.tsx「a metric written as a formula」「is offered only where the capability declares expressions」「starts as two fields subtracted, and is named by what it says」「takes a number in place of a field, and writes it as a constant」「writes the operation and the summary the card is set to」「sends the expression and titles the column by what it says」「a metric derived from other metrics」「reads only the metrics before it, and holds no conditions of its own」「titles its column with its own words and nothing appended」与 test/having.test.ts「formulas」；浏览器里多出那一列的是 stories/view-engine 的 `Formula`）

## 排序：与记录视图同一个控件

指标槽底部那一行（`ui/analysis/SortRow.tsx`，`data-slot="analysis-sort"`）里的排序按钮，装的就是记录视图工具栏上的 `SortSettings`——同一个弹层、同一份优先级列表、同一套方向与拖拽。分析视图这边把**每个维度与每个指标的别名当成一个可排序的「字段」**递进去，标签用提到一个指标时的那句话（`metricReference`，见上面「卡片上写字段，提到它的地方写表头那一句」）。

- **一句话只该有一个控件。** 从前这里是一个只装得下一条排序的选择框，于是「先按记录数、再按金额」说不出来：两组记录数相同的时候，谁在前面全凭数据源。记录视图早就有一个能说这句话的控件，再写第二个只会让两处对「先按哪个」给出两种解释；
- **「前 N 组」紧挨着它**，因为「前 N」只有在「按什么排」旁边才读得懂。没有维度就整行不画——Wow 拒绝对无分组聚合排序，而它本来也只有一行；
- **上限是别名的总数**（`maxSortFields`）：每个维度与指标至多排一次，排完就没有可加的了，「排序字段」那颗按钮自己灰掉。

（见 test/formulaCard.test.tsx「ordering the groups」「orders by several aliases, in the priority the editor lists them」与 test/sortSettings.test.tsx「what the sort button says」；浏览器里换行序的是 stories/view-engine 的 `SortedByTwo`）

## 焦点：键盘不该被丢回页面开头

阶段二评审的三条（A1／A2／A9）说的是同一件事：**屏幕换掉了键盘正站着的那个元素，就得说出键盘接下来站哪儿**。焦点落到 `<body>` 上不是"没有焦点"，而是下一次 Tab 从标题栏重新走一遍——删三个维度就得从头走三趟。

- **可视化面板换层，焦点跟着层走**（A1，`ui/workbench/AnalysisParts.tsx`）。`panel` 这颗状态有三种值，每一次变化都会换掉侧栏里的整块内容：按「可视化」进第一层，焦点落到 `ChartPicker` 的 `h2`（`tabIndex={-1}`，它不进 Tab 路线，只接一次落点）；按齿轮进第二层，落到 `ChartOptions` 的 `h2`；「返回图型」回第一层，还是落到 `ChartPicker` 的 `h2`；「返回视图列表」把面板关掉，焦点**回到开面板的那颗按钮**（`AnalysisToolbar` 的 `data-slot="visualize"`，ref 由 `AnalysisParts` 持有）。工具栏本身已经不在了的时候（结果空了、宿主关了这项能力）退回结果块。这是一个**以层为依赖的 effect**，不是 `setTimeout`：标题要等 React 画完这一层才存在，而定时器只是在猜它什么时候画完；
- **移除之后键盘留在原地**（A2，`ui/analysis/listFocus.ts` 的 `useListFocus`）。维度卡、指标卡、展开链的一层、「只保留」的一行、漏斗的一个阶段、系列的一行、参考线的一条——七处移除共用一条规则：**焦点落到补上这个位置的那一项**（同下标），列表到头了就落到**上一项**，一项都不剩就落到这个槽自己的「添加」。移动同理：按下「上移」「下移」之后，焦点留在**这项落到新下标之后的那颗同向按钮**上，于是连按几次就能一路挪；挪到头那颗按钮禁用了，反向的那颗接住焦点——**不为此把边界上的按钮留着当空操作**，一个按下去什么也不做的按钮比一次横向移动的焦点更难读。规则写在 effect 里而不是写在按下的那一刻：按下的时候那一项还在页面上，是下一次渲染才把它拿走的；
- **结果的行是一个 Tab 停留点**（A9，`ui/AnalysisTable.tsx` + `ui/roving.ts`）。可按的行从前每行一个 `tabIndex={0}`，于是一百组就是一百个停留点，键盘想走出这张表得把已经读过的每一组再走一遍。行与行是同类的东西，这正是 roving tabindex 的场合（与记录视图表头同一套 `roving.ts`）：整组一个停留点，↑／↓ 在行之间走，Home／End 到两端，回车与空格照旧打开追问菜单。`tabindex` 写在节点上而不是渲染成属性——React 不知道它，也就不会把键盘挪过的那个停留点重新渲染回去。

（见 test/analysisFocus.test.tsx「the visualization panel hands the keyboard on」「a removal leaves the keyboard in the list」与 test/analysisTable.test.tsx「the result rows are one tab stop」）
