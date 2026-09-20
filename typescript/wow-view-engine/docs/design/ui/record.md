# UI 层：Record 视图

Record 工作台的结果区组件。三种视图共用的骨架、状态条、值显示与 `FilterPanel` 见 [README.md](README.md)；控制器见 [react.md#userecordtable](../react.md#userecordtable)。

## RecordTable 与 RecordCards

- `RecordTable` 的 `selectable` 默认为 true，工作台与 `EmbeddedView` 不受影响；
- 关掉时汇总行的口径标签（`total`／`page`）没有多出来的格子可占，于是标在首列之上，而不是顶掉首列自己的汇总。

## 汇总行的口径是数字的一部分

- 口径标签由**结果**决定，不由配置决定：`SummaryRow.scope` 是内核投影出来的，`total` 来自它自己那次覆盖全部命中记录的聚合，`page` 来自屏幕上这几行的加总。汇总查询失败时行不消失——本页合计本身有用——但标签改说「本页」，`tfoot` 上的 `data-scope` 同步改成 `page`，宿主要据此上色也拿得到；
- 同时状态条上多一条 warning（`runtime.summary.page-only`），说明**为什么**只剩本页。两处缺一不可：只改标签，读者未必注意到；只报 warning，行上那个词仍然在撒谎。这条 warning 随结果走而不是随草稿走，因此在编辑器里改点什么不会把它从屏幕上抹掉——见 [../runtime.md#规则](../runtime.md#规则)；
- 二十行的 AVG 被当成四万行的 AVG，是这一行唯一能犯的错，而沉默正是犯它的方式。（见 test/resultIssues.test.tsx「what the screen says about a downgraded total」与 stories/view-engine 的 `TotalCoversThisPageOnly`）

- `RecordTable` 暂不接 TanStack：控制器已经是表格模型，列语义、排序、选择与分页都从它来，再叠一层只是把同一份状态写两遍。等列宽拖拽与列序拖拽真的要做时再引入，那时它提供的才是新能力。（见 test/ui.test.tsx「RecordTable on its own」「RecordCards on its own」「the summary row」）

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
