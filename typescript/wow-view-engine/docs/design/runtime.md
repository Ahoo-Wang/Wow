# 运行时

一个打开的视图对应一个 `ViewRuntime`，它是带 `subscribe / getSnapshot` 的小 store。**store 那一半只有一份实现**（`runtime/runtimeStore.ts` 的 `RuntimeStore`）：它持有 `draft`／`applied`／`result` 这三态所在的那份快照、订阅者、自动刷新计时器的账本，以及「draft 与已保存的那份是否还一样」这一条 `dirty` 判据——数据视图与仪表盘各持一只，两个 runtime 只留各自「是哪种视图」的部分（一次查询，还是 N 块面板）。两处真正不同的地方做成 store 的显式钩子而不是拷贝：`admit`（这个类把配置交给哪一套准入）、`apply`（提升在这里意味着什么）、`holding`（暂停理由里要问 runtime 的那几条：在途的是谁的请求、有没有选中的行）、`release`（dispose 时还要放掉什么）、`restored`（`revert` 之后仪表盘还要 `load` 一次引用）与可选的 `blocking`（哪些 issue 挡住 apply 与计时器：数据视图不给，任何 error 都挡；仪表盘只算整板的 error，见「Dashboard」）。订阅与通知那一半再往下由 `runtime/listeners.ts` 的 `listenerSet` 出：`RuntimeStore`、列表变化通知与 React 那边的倒数读数共用一份，两条规则（提交状态之后再通知、遍历监听者集合的副本，好让监听者在自己那一下里退订）因此只写一处。（store 自身的那几条见 test/runtimeStore.test.ts，两个 runtime 各自的规则仍在 test/runtime.test.ts 与 test/dashboardRuntime.test.ts）

## 状态与命令

```ts
export interface ViewRuntime<C extends ViewConfig = ViewConfig> {
  readonly id: string; // runtimeId，与 instanceId 分离
  readonly kind: C['kind']; // 判别字段，供调用方收窄
  readonly definition: ViewDefinition;
  readonly kinds: FieldKindRegistry; // 准入所用的注册表，筛选编辑器据此编辑
  readonly limits: RuntimeLimits; // 本次准入所用的预算，编辑器据此只提供内核会接受的选项（如分页条的每页条数上限 maxPageSize）
  readonly fields: readonly FieldDefinition[]; // 筛选编辑器编辑的字段：数据视图取定义，Dashboard 取 draft 声明的全局字段（随 draft 变化，每次渲染读取）
  readonly disposed: boolean; // dispose 之后为 true，所有命令成为空操作
  getSnapshot(): ViewRuntimeState<C>;
  subscribe(listener: () => void): () => void;

  edit(patch: Partial<C>): void; // 只改 draft，同步；成员给 undefined 即删除该成员
  apply(): void; // validate(draft) 无 error → applied = draft，执行
  revert(): void; // draft 回到 saved.config，重算 issues／dirty；与 applied 不同且无 error 时再 apply；未保存过为空操作
  refresh(): void; // 重跑 applied
  setEditing(active: boolean): void; // 编辑器获得／失去输入焦点时调用，暂停自动刷新
  setAutoRefresh(on: boolean): void; // 宿主不要它自己刷新时关掉（嵌入的 autoRefresh）：计时器一直停着，间隔原样、不进草稿，手动刷新照跑
  setAutoApply(on: boolean): void; // 改了就跑：问题变了就自己 apply，见下；哪些成员是「问题」由模型按种类声明（`autoRunMembers`），记录与仪表盘没声明任何一个，所以那里只记偏好、不上弦
  setScopeFilter(tree: FilterTree | null): Issue[]; // 外层注入的附加条件，AND 到已应用筛选；不改 draft／saved；返回被拒的那几条，生效时为空
  readonly refusedScope: Issue[]; // 当前要求的那个注入条件因何被拒；生效时为空。被拒不阻塞视图，见下「被拒的是条件，不是视图」
  readonly scopeFilter: FilterTree | null; // 当前生效的注入条件（最后一次被准入的那棵）；筛选摘要据此把"宿主的条件"与"视图自己的条件"分开呈现
  readonly environment: RuntimeEnvironment; // 这个视图跑在哪个宿主上；nextRefreshAt 是这只钟上的读数，倒计时必须问同一只钟
  valueCandidates(field: string): ValueCandidateSource | null; // 一个文本条件可以从中挑的值，按数据计数、在注入作用域之内；不给时为 null（Dashboard 一律 null）。见「条件的值取自数据」
  dispose(): void; // 最后一次通知订阅者后清空监听
}

/** 分页与选择只属于 Record；Engine 按 config.kind 返回对应的窄接口。 */
export interface RecordViewRuntime<
  P extends 'paged' | 'cursor' = 'paged' | 'cursor',
> extends ViewRuntime<RecordViewConfig> {
  page(target: RecordPageTarget<P>): void;
  select(keys: RecordKey[]): void;
  exportRows(options?: ExportRowsOptions): Promise<ExportedRows>; // 已应用口径的全量行，供导出；见「导出」
}

/** 打开一个实例得到的判别联合；按 runtime.kind 收窄。 */
export type AnyViewRuntime =
  RecordViewRuntime | ViewRuntime<AnalysisViewConfig> | DashboardRuntime;

/** Dashboard 的公开面：快照多出 panels 与 resolving，并能等待引用加载、按面板取子 runtime。 */
export interface DashboardRuntime
  extends ViewRuntime<DashboardViewConfig>, DashboardEditing {
  getSnapshot(): DashboardRuntimeState; // ViewRuntimeState + panels: DashboardPanelState[] + resolving + tab（屏幕上的标签页）+ filters + history（撤销／重做各自那一步）
  ready(): Promise<void>; // 每个面板引用都已加载或确认不可读
  panelRuntime(panelId: string): DataViewRuntime | null; // 宿主自行驱动某个面板时使用
  place(panelId: string, layout: PanelLayout): void; // 摆一个面板并只应用这一处摆放；被盖住的面板让开、所在标签页上浮压紧（placePanel），其余未应用的编辑照旧待应用
  refreshPanel(panelId: string): void; // 只重跑这一个面板：失败面板的「重试」
  showTab(tabId: string | null): void; // 换屏幕上的标签页：只有它的面板跑（批 B3）；板子没有它就是第一页
}

/** 搭板子（D22 A～E，批 B1）：每条都同时写进 draft 与屏幕上的 applied，面板按草稿实时重跑；保存才写回，revert 放弃。 */
export interface DashboardEditing {
  addPanel(panel: NewPanel, placement?: NewPanelPlacement): string | null; // 已保存视图／板内分析／内容；放进所在标签页 fromRow 起的第一个空位；满了为 null
  removePanel(panelId: string): void; // 所在标签页随之上浮压紧
  duplicatePanel(panelId: string): string | null; // 旁边有位放旁边，否则放下面；板内分析一并复制
  renamePanel(panelId: string, title: string): void; // 空白去掉标题，回到按内容命名
  replacePanelView(panelId: string, instanceId: string): void; // 换一个已保存视图；展示覆盖作废，标题与接线保留
  editPanelContent(panelId: string, patch: Partial<NewContentPanel>): void; // 内容面板改内容，不改种类
  movePanelToTab(panelId: string, tabId: string): void; // 放进目标标签页的第一个空位，原标签页压紧
  setPresentation(
    panelId: string,
    presentation: PanelPresentation | null,
  ): void; // null：恢复为视图的样子
  referToSaved(panelId: string, instance: ViewInstance): void; // 面板改为引用「就是它显示的那个视图」的已保存实例，展示覆盖、点击、接线都留着：板内分析已另存为视图（ViewEngine.saveOwnedView），或个人视图已复制成共享的（ViewEngine.copyPanelView）
  addTab(title: string, firstTitle: string): string | null; // 无标签页的板子第一次加：现有面板归入 firstTitle 那一页
  renameTab(tabId: string, title: string): void;
  moveTab(tabId: string, index: number): void;
  removeTab(tabId: string): void; // 连同面板；最后一个标签页不删
  reorderPanel(panelId: string, step: 'up' | 'down'): void; // 窄屏一列里前后挪一位，写回宽布局（reorderPanel）；两端什么也不做
  undo(): EditStep | null; // 撤销上一步搭板子的命令（它改过的那几个成员，草稿与屏幕一起），答撤掉的那一步；没有为 null
  redo(): EditStep | null; // 重做刚撤销的那一步，直到下一次编辑
}

/** 由配置类型推出的 runtime 类型，create 用它保留静态收窄；种类只有值知道（联合）时就是 open 交回的 AnyViewRuntime，调用处不必再强转。 */
export type RuntimeFor<C extends ViewConfig> = [C] extends [RecordViewConfig]
  ? RecordViewRuntime
  : [C] extends [AnalysisViewConfig]
    ? ViewRuntime<AnalysisViewConfig>
    : [C] extends [DashboardViewConfig]
      ? DashboardRuntime
      : AnyViewRuntime;

/** 分页目标由 RecordCapability.paging 决定；游标模式的第一页是 cursor: null。 */
export type RecordPageTarget<P extends 'paged' | 'cursor'> = P extends 'paged'
  ? { index: number }
  : { cursor: string | null };

export interface ViewRuntimeState<C> {
  saved: ViewInstance | null; // 保存基线；null 表示未保存的新视图
  title: string; // 未保存时来自 create 的输入，已保存时等于 saved.title
  scope: ViewInstance['scope']; // 同上；决定首次保存使用的创建许可
  draft: C;
  applied: C;
  issues: Issue[]; // validate(draft 与作用域条件合并后的有效配置)
  dirty: boolean; // !sameJson(draft, saved?.config)
  query: {
    status: 'idle' | 'loading' | 'success' | 'error';
    error?: Issue;
    requestId?: string;
  };
  result: { config: C; own: C; data: ProjectedView; receivedAt: number } | null; // 只随成功推进；config 是真正执行的有效配置（含作用域），own 是产生它的自有配置（合并前被提升的 applied；Dashboard 不自跑查询，两者相同）
  selection: RecordKey[]; // 只覆盖当前结果；范围变化即清空，见下
  write: WriteState | null; // 最近一次写入的待处理结局，见 management.md「冲突与未知结果」
  editing: boolean; // 由 setEditing 维护，用于暂停自动刷新
  autoApply: boolean; // 改了就跑：问题一变就自己再跑一次，见「改了就跑」；缺省关，由工作台按用户偏好打开
  nextRefreshAt: number | null; // 下一次自动刷新的到期时刻（environment.now() 的毫秒）；没有武装计时器时为 null，见「自动刷新」
}

/** 写入的非成功结局；成功直接推进 saved 并清空该字段。 */
export type WriteState = { requestId: string; payload: WritePayload } & (
  | { kind: 'conflict'; remote: ViewInstance | ViewPreferences }
  | { kind: 'rejected'; issue: Issue }
  | { kind: 'unknown' }
);

/** 原样保留的写入正文，覆盖与重试都用它，不从当前草稿重新推导。
 *  正文自带 `id` 与期望 `revision`：重试是同一次逻辑写入，因此沿用同一期望；
 *  只有"覆盖"把期望推进到冲突报告的 `revision`，并生成新的 requestId。 */
export type WritePayload =
  | { action: 'preferences'; definitionId: string; next: ViewPreferences }
  | {
      action: 'create';
      input: Omit<ViewInstance, 'id' | 'revision'>;
      /** 'first-save' 把源 runtime 绑定到新实例，'save-as' 保持源 runtime 不变。 */
      intent: 'first-save' | 'save-as';
    }
  | { action: 'save'; id: string; revision: string; config: ViewConfig }
  | { action: 'rename'; id: string; revision: string; title: string }
  | { action: 'delete'; id: string; revision: string };

export type WriteAction = WritePayload['action'];

/** 未打开实例的写入的可寻址身份；结局存放在 engine.pendingWrites()。 */
export interface WriteHandle {
  readonly id: string;
}

/** 所有写入方法的唯一拒绝类型：非成功结局连同其句柄一并交给调用方。 */
export class ViewWriteError extends Error {
  readonly handle: WriteHandle;
  readonly state: WriteState;
}
```

## 规则

- `result.config` 是产生该结果的配置，不随 draft 变化；UI 用它标注"结果对应的条件"。`result.own` 是同一次执行中**合并作用域之前**的那份自有配置，随 `config` 一起记录：`mergeFilters` 把作用域作为尾随分组追加，`or`／`nor` 的 draft 还会被整棵包成第一个子节点，因此 `config.filter` 上的路径既不指向 draft，也不指向编辑器能删的东西——摘要要给出可点删除的条件，就只能读 `own.filter`，而宿主注入的那部分由 `runtime.scopeFilter` 单独交出、单独呈现且不可删除。
- 选择绑定当前结果：`page`、`apply` 与解释环境变化清空 `selection`；`refresh` 后按新结果的行键求交集，消失的行自动移出。本轮不支持跨页选择，批量动作只作用于当前结果中仍存在的行。
- 新的 `apply / refresh / page` 替代同一 runtime 的在途请求，旧响应到达后丢弃。这由 `RequestRunner` 用 per-runtime key 实现，全局并发上限与队列来自 `RuntimeLimits`（`maxConcurrentQueries`、`maxQueuedQueries`、`maxPageSize`、`maxAnalysisRows`、`minRefreshInterval`、`maxRefreshInterval`、`maxFilterDepth`、`maxFilterNodes`、`maxDashboardPanels`、`exportMax`）。**呈现口径也在 `RuntimeLimits` 里**（2026-09-21，P-17）：每页条数梯子 `pageSizes` 与卡片的 `cardPageSizes`（缺省 12／24／48／96：12 在一行 1～4 张、以及窄屏退下来的列数下都是整行——用户 2026-09-23：卡片每页条数应是一行张数的倍数）、自动刷新梯子 `refreshIntervals`、新视图的 `defaultPageSize`／`defaultColumns`／`defaultCardFields`——它们不是预算，是产品的选项与缺省，从前是模块常量、改一档就是一次发版；放在预算旁边是因为预算会裁它们（高于 `maxPageSize` 的档不提供），也因为交给引擎的是同一个对象。控件读 `runtime.limits`，视图保存时的那一档照旧折进梯子。
- 状态变更同步提交后再通知订阅者；相同状态返回相同对象，子对象引用稳定，以配合 `useSyncExternalStore`。`dispose` 是最后一次通知：订阅者据此读到 `disposed`，`useOpenView` 才能在实例被别处删除时自行重开，而不必等一次碰巧的渲染。
- runtime 不做持久化。保存是 Engine 的命令，成功后 Engine 调用 `runtime.markSaved(instance)` 推进基线。
- **`edit` 里值为 `undefined` 的成员是"删掉"，不是"置为 undefined"。** 配置是 JSON：没有这个成员与成员为 `undefined` 是同一份配置，却不是同一个对象，而 `dirty` 是与已保存配置的一次 `dequal`。把最后一条可选列表项删掉的编辑器因此会让视图就此一直"未保存"、离开守卫还会问一句用户早已撤销过的改动。`defaultRecordConfig` 同理：没有汇总时根本不写 `summaries` 这个键，而不是写一个 `undefined`。

- **注入的作用域条件同样要准入。** `setScopeFilter` 按本 runtime 的定义与 kinds 校验合并后的有效筛选（含深度与节点预算），返回**被拒的那几条**（生效时为空）；被拒时不改变已应用口径也不执行，因此自定义宿主与 Dashboard 走同一条准入路径。
- **被拒的是条件，不是视图（D17-5）。** 判断"被拒"看的是**差**：合并后的 error 里，减去配置自己单独校验也会报的那些，剩下的才是这个条件带来的。所以一份本来就要先修正的配置不会把宿主的条件一起拖下水，宿主也不会收到一句它无能为力的"这个视图要先修正"。这条规则在**构造时同样适用**：一开就带着的收窄若被拒，它压根不进 `injectedScope`，`issues` 是配置自己的那一份，视图照常执行——没收窄的那份结果留在屏幕上——被拒的理由记在 `refusedScope` 上（`ViewRuntime.refusedScope`，构造与每次 `setScopeFilter` 写它，内容变了才通知；同一份答案保持同一个数组，宿主每次渲染新建条件对象也不会把屏幕带进死循环）。Dashboard 的引用晚到一步才知道面板能不能扛得住某个全局字段，所以 `settled` 里按同一条规则再判一次；面板的子 runtime 反过来：仪表盘的条件正是这块面板存在的理由，扛不住就不画、并在面板上说明（`PanelChildren.sync` 读子 runtime 的 `refusedScope`）。
- 作用域条件从打开起就与配置一起准入：`issues` 始终是"draft AND 作用域"这份有效配置的校验结果，构造、`edit`、`adoptSaved` 与 `setScopeFilter` 都按这一条规则重算；`mergeFilters` 把作用域作为一个嵌套分组追加在 draft 自身条件之后（不拍平：分组内字段唯一，而宿主对同一字段再收窄是第二个问题而不是重复），因此指向 draft 树的 Issue 路径不因作用域而移位；作用域自身的子树多占一层深度，单条条件在线上仍编译为它本身。合并只丢弃结构合法且没有叶子的空树：含畸形条目的树不算空（`isEmptyFilter` 为 false），畸形条目保留在合并结果里由准入报出；根不是分组的 filter 不参与合并，原样交给准入报 `config.filter.invalid`。Dashboard 的 `fields` 访问器只交出结构合法的字段项，畸形项留给准入。
- **只执行准入过的配置。** `apply` 与 `setScopeFilter` 只提升通过准入的口径；打开时 `applied` 若未通过准入，`refresh` 与 `page` 同样是空操作，直到一份修正后的 draft 被 `apply`。否则"待修复"只挡住 `apply` 一个入口，刷新或翻页就会把被拒绝的配置发出去。
- **失败说的是数据源的原因，不是 HTTP 客户端的。** 查询失败（`runtime.query.failed`）、导出失败与批量命令失败的 `reason` 都经 `sourceReason(error)` 读出：带着 exchange 的拒绝（fetcher 的 `ExchangeError`，按形状读，引擎不依赖 HTTP 客户端）先读它的响应体——Wow 在 4xx 的体里写 `{ errorCode, errorMsg }`，于是用户读到的是「HTTP page window[12000] must not exceed 10000.」而不是「Request failed with status code 400 for http://…」；体里什么也没说时只说 `HTTP 400` 这样的状态，**从不把请求地址摆给用户**；不是 exchange 的拒绝照旧读它自己的 message。读体可能要一拍，所以查询在读完之前仍算在途，期间开始的新请求胜出。引擎自己的命令与存储失败不经这里，照旧按各自的错误码说（`toIssue`）。（见 test/sourceReason.test.ts「sourceReason」、test/runtime.test.ts「reports what the service said when it refused the query」、test/bulkCommand.test.tsx「keeps the refused records selected, each with the source’s reason」）
- **结果自身的问题记在结果上，不记在 `issues` 里。** `ProjectedView` 带一份 `issues: readonly Issue[]`，说的是"屏幕上这些数字"而不是"这份配置"。`state.issues` 是 draft 连同作用域的准入结果，每次 `edit` 都重算：把这类发现放进去，用户一敲键盘它就没了，而它描述的那些数字还在屏幕上。它随成功的执行一起推进，随下一次成功的结果一起被换掉，读它的是 `resultIssues(data)` 这一个函数——两个工作台把它并进 `WorkbenchShell` 的 `warnings`，`EmbeddedView` 并进它自己的 warning 条（[ui/README.md#两级-severity-与-statusstrip](ui/README.md#两级-severity-与-statusstrip)）。当下有两条，都是 warning，都不阻塞：
  - **`runtime.summary.page-only`**（路径 `['summaries']`）——汇总查询失败，汇总行退回本页口径。行本身留着，因为本页合计本身有用；`SummaryRow.scope` 说明它答的是 `page` 还是 `total`，这条 Issue 说明为什么退。配置没要汇总时两者都没有，汇总查询成功时是 `scope: 'total'` 且没有 Issue。默默顶替才是这里唯一的错误：读者看到「总计」，会当成全部命中记录的总计，二十行的 AVG 被读成四万行的 AVG。
  - **`analysis.result.more-groups`**（路径 `['limit']`，参数 `{ limit }` 是读者设的那个上限，不是查询带的那个）——分析结果之下还有没列出的组；画成饼图时是 `warning`（份额只在已列出的组里算），其余是 `note`（每一行都是完整的数，视图本就只要前 N 组）。这是问出来的，不是猜的：分组查询要的是 `limit + 1`，多回来的那一行就是答案，随后被丢掉不上屏（判据见 [kernels.md#compileanalysis-与-projectanalysis](kernels.md#compileanalysis-与-projectanalysis)）。分析的合计行走自己的无分组查询，因此即使分组被截断它仍覆盖全部——两行加起来小于它们下面的合计，两个数都没错。
  - **`analysis.result.at-limit`**（同样的路径与参数）——探不成时的那一种：配置上限已经顶到天花板，多要一行会让查询被拒，于是只剩"恰好填满上限"这个二义信号，措辞相应是"**可能**还有更多"而不是断言。两条不会同时出现。
  - 分析的**合计**查询单独失败不报：分组行仍然完整地回答了它们自己的问题，屏幕上没有哪个数字的含义与它的说法不符，少一行合计而已。（见 test/resultIssues.test.tsx）

## 自动刷新

`applied.refresh.interval` 非空时由该 runtime 持有唯一计时器，到期调用 `refresh()`。五种情况暂停：

- `issues` 含 error；
- `editing` 为 true——`useFilterEditor` 与 `useAnalysisEditor` 提供 `focus`／`blur`，默认 `FilterPanel` 与分析托盘 `Tray` 在焦点进入或离开其根元素时调用，内部焦点移动不触发；控件的弹层经 Portal 渲染在根元素之外，焦点进入弹层时根元素内仍有带 `data-popup-open` 的触发器，算作未离开；查询进行中不冻结编辑器；
- 宿主报告页面不可见；
- 上一次请求仍在途；
- **有选中的行**（记录视图）：选中的行是有人正要动手的行，一次刷新可能把它们挪到别页或移出结果；放开选择，钟再走。（见 test/runtime.test.ts「holds the timer while rows are selected」）

间隔的入口是刷新按钮的 `▾`（`RefreshControl`，三种视图各有一处，见 [ui/README.md#刷新是一个拆分按钮](ui/README.md#刷新是一个拆分按钮)）：选中即 `edit({ refresh: { interval } })` 加 `apply`，因为这里读的是 `applied`；仪表盘走 `setRefreshInterval`，被读时是读者自己的、不进草稿（见下文 [Dashboard](#dashboard)）。界面不另添暂停理由。计时与可见性都来自注入的 `RuntimeEnvironment`（见下方[环境](#环境)），runtime 不触碰 DOM。计时器随 `dispose` 释放。多个 React 组件观察同一 runtime 不会产生多个计时器。（见 test/runtime.test.ts「DataViewRuntime auto refresh」）

**计时器只有一份实现**（`runtime/refreshTimer.ts` 的 `RefreshTimer`）：数据视图与仪表盘各持一只，武装、复用同一延迟、停表都在它里面；暂停理由由 `RuntimeStore` 读（`refreshDelayOf(interval, held)`），其中三条（draft 有 error、编辑器有焦点、页面不可见）它自己就能读出来，其余的（上一次请求仍在途、有选中的行）要问持有它的 runtime——在途的是谁的请求只有 runtime 知道：数据视图是自己那一个，仪表盘是任意一块面板的，而仪表盘里的那份数据视图干脆一直按住（整块板只有一只钟）。到期时刻由 store 公布——从前两处逐字相同的拷贝就是靠这两步收掉的。**到期时刻随计时器一起公布**：`state.nextRefreshAt` 在武装计时器的同一处写入（`environment.now() + delay`），停表的同一处清空，因此它不是关于计时器的第二种说法，而就是计时器自己的那个数。任意一条暂停理由成立时它是 `null`——「正在倒数」与「表停了」在屏幕上必须分得开，冻在某个秒数上的倒计时是在说谎。可见性变化与（仪表盘上）面板查询的起落都不是 runtime 自身的状态变更，本来不通知订阅者；只有在它们**移动了到期时刻**时才补一次通知（每轮至多两次，与面板数量无关），否则屏幕上的倒计时会继续数向一个已经不存在的计时器。手动 `refresh()` 不需要特别处理就会重排：请求在途时计时器停，落地后按新的 `now()` 重新武装——刚拿到的数据不该在三秒后又被刷一次。倒数由 `useRefreshCountdown` 在控件里每秒重画（见 [react.md](react.md)），读的始终是这个数与 `environment.now()`，界面不另起时钟。（见 test/runtime.test.ts「publishes when the next refresh is due」与 test/dashboardRuntime.test.ts「publishes when the whole board is next due」）

**期末再问一次**（2026-09-23 审查：指标卡的「最后一期」按提问时刻判断，页面开着过零点一直说昨天）：store 另有一只一次性的钟（`runtime/refreshTimer.ts` 的 `MomentTimer`，按时刻而不是按间隔武装，时刻不变就不重武装），时刻由持有者说（`RuntimeStoreHost.expiresAt`）：数据视图读结果的行与草稿的图——指标卡带走势、读最后一期、提问时还有一期没结束——交给内核的 `periodRollover`，时刻是提问时刻加它剩下的时长（`DataViewRuntime.rolloverAt`）；仪表盘取当前标签页上各面板里最早的那个（`PanelChildren.rolloverAt`），面板里的数据视图自己不武装，与自动刷新同一个道理。时刻过一秒（`ROLLOVER_GRACE_MS`，给数据源收齐刚结束的那个桶）调 `host.refresh()`，重跑的是同一个问题，新结果带新的提问时刻，于是卡片换到新的一期。它不看刷新间隔，也不公布到期时刻（不是读者选的节奏，倒计时不该为它出现）；按住它的是 store 停了、草稿被拒、页面不可见、请求在途——页面藏着过了期末，露出来那一刻就问。（见 test/periodRollover.test.ts「a card asked again when its period ends」「a dashboard’s cards asked again when their period ends」）

## 改了就跑

自动刷新答的是「这份数据过时了」，「改了就跑」（D20；todo 批 7）答的是另一件事——**问题变了**。分析师在托盘里加一个维度、换一种汇总、改一下前 N 组，屏幕上那张表立刻就与它上面那句话对不上了；让他再把手伸到底下按一次应用，是让他为自己刚说过的话付一次手续费。所以问题一变，一小会儿之后它自己跑（`runtime/autoApply.ts` 的 `autoApplyDue(state)` 与 `AUTO_APPLY_DELAY_MS = 300`）。

**三条按住它的理由**，缺一它就该跑：

- **开关关着**（`state.autoApply` 为 false）。这是用户的偏好而不是视图的配置，工作台从 `ViewPreferences.autoRun` 推给 runtime（见 [management.md#列表偏好与默认视图](management.md#列表偏好与默认视图)）；runtime 自己缺省是关的，没人推它就不会有谁的屏幕莫名其妙动起来。关着时应用是唯一的跑法；
- **草稿被准入拒绝**（`issues` 含 error）。一份跑不起来的问题不该被自动拿去跑——那只会把一条错误在屏幕上循环播放一遍；
- **改的不只是问题**（`autoApplyDue`：改过的成员里有一个不在 `autoRunMembers(kind)` 里）。哪些成员算「问题」——展开、维度、指标、只保留、排序、前 N 组、合计行——由模型在 `ANALYSIS_AUTO_RUN_MEMBERS` 里声明在类型旁边（[model.md#配置](model.md)），runtime 不认得任何一种视图的规则；记录视图与仪表盘一个都没声明，开关对它们只是被记住的偏好。范围不在其中：条件照旧等应用（D20），**而且在它等着的时候别的也不跑**：应用跑的是整份草稿，条件与问题一起提升，半份草稿跑出来的结果会同时说两件事——行是新问题的，口径是旧条件的。所以「维度改了 + 条件也改了」这一份草稿整个等着那一下按键。

**一个 runtime 一只计时器**（`runtime/refreshTimer.ts` 的 `RefreshTimer`，与自动刷新那只各是各的）：每次 `edit` 都停表再武装，所以那 300 毫秒是从**最后一次**编辑数起——加一个时间维度、紧接着改它的粒度，是一次查询而不是两次。`apply`、`revert`、关掉开关与 `dispose` 各自停表。300 毫秒是「手停下来了」与「屏幕没反应」之间的那一档：更短会把一串连着的编辑各发一次查询，更长就读成卡了。

跑之前那一下，屏幕上的行答的是上一个问题：它们**淡着留在那儿**，不清空——下一个答案只有几百毫秒远，中间闪一次白读起来是出了错。界面这一半是 `AnalysisParts` 的 `data-slot="analysis-result"` 加 `data-stale`，读的是 `useAnalysisEditor.stale`（见 [react.md#useanalysiseditor-与-usedashboard](react.md#useanalysiseditor-与-usedashboard) 与 [ui/analysis.md#托盘范围--维度--指标--结果一个应用](ui/analysis.md#托盘范围--维度--指标--结果一个应用)）。（见 test/autoApply.test.ts「改了就跑: the analysis runs again on its own」「the auto-run preference」与 test/autoRun.test.tsx「改了就跑: an analysis runs as it is edited」「改了就跑: the tray’s switch」）

## 一条记录读全

`RecordViewRuntime.fetchRecord(key, signal?)`（`runtime/fetchRecord.ts`）按行键单独取一条完整记录，供详情用：条件只有「行键等于 key」与宿主注入的作用域（一个租户的视图打不开别的租户的记录），**不带**页上的条件、排序与投影——详情是关于这条记录的，一条被命令改过的记录可能已不满足页上的条件；按定义的分页方式问第一条，没有就是 `null`。它和导出一样走在请求线之外，不占调度槽位、不动屏幕上的行。（见 test/recordDetail.test.tsx「a record read whole」）

## 条件的值取自数据

`ViewRuntime.valueCandidates(field)`（`runtime/valueCandidates.ts` 的 `ValueCandidateSources`）交出字段的 `ValueCandidateSource`——`search(query, signal?)` 答 `{ values: { value, count }[], complete }`——或 `null`：字段不是内核说能列的那种（`valueCandidateField`，[kernels.md#条件的值取自数据candidatests](kernels.md#条件的值取自数据candidatests)），或者这是一个 Dashboard：它的全局字段不是哪一份数据的字段。**每个字段一个源、同一个对象**，编辑器可以拿它当副作用的依赖。

- **问法是内核的**：`valueCandidatesConfig` 造的分析配置经 `withScopeFilter` 合上**宿主注入的作用域**（不合视图自己的条件——那样编辑一个已有条件时只看得到它已经选中的那个值），过一遍 `validateAnalysis`（定义收不下这个问题就在这儿拒，而不是交给服务），再 `compileAnalysis`，走 `ViewSource.aggregate`，与导出、读一条记录一样**在请求线之外**：不占调度槽位、不动屏幕上的结果；
- **答案留到下一次刷新**：按「字段 + 打的字」记下，同一个条件再打开、同一字段上的第二个条件都不再问；**手里能缩的不去问**——不带字的那份回来时已经齐了（`complete`），或者字段根本没有子串／前缀条件（源缩不了），打的字就在手里的那份上缩（`narrowValueCandidates`）；
- **数据重读或作用域换了就全忘**：`refresh()` 是把数据再读一遍，留着之前的答案就会列出行里已经没有的值、报着已经不对的条数；宿主把视图收窄到另一个租户，问的就是另一批记录。`refresh`、`setScopeFilter` 与 `dispose` 都清空答案，换之前发出去、之后才回来的那一份也不记；此外每问一次都先读此刻的作用域，与上一次答案所问的作用域不同（按值比）就先全忘——所以谁挪了作用域都不必记得去说一声（仪表盘上板子的固定范围、宿主持有的值就是这样挪的）；
- **取消用 `AbortSignal`**，跟 `fetchRecord` 一样由 `abortWith`（`runtime/abort.ts`，内部，不导出）转成源收的 controller；被取消的请求以信号的原因拒绝、什么也不记；源抛的错原样往上交，界面用 `sourceReason` 说成源自己的话。（见 test/valueCandidates.test.ts「a view runtime offers value candidates」）

## 导出

`RecordViewRuntime.exportRows(options)` 按**已应用**口径（`applied` 合并作用域之后的那一份，也就是产生屏幕上这些行的那份配置）在后台把结果分页拉完，交还行本身；序列化与下载在别处（[kernels.md](kernels.md) 的 `serializeCsv`、[ui/record.md#导出](ui/record.md#导出)）。

- **它走在运行时自己的请求线之外**：不占 `RequestRunner` 的槽位、不写 `state.result`、不发通知，既不会被 `apply` 顶掉，也顶不掉屏幕上的查询。导出是"在看这个视图的同时再要一份"，一个因为导出而清空自己的视图是更坏的答案；
- **每页按 `limits.maxPageSize` 要，而不是按视图的 `pageSize`**：屏幕上一页几行与文件无关，来回次数越少越好，而那个上限正是这个源被准入时的那一个；
- **`ctx.now` 只读一次**：二十页之间"今天"不能翻篇，否则同一个文件的首尾答的是两个问题；
- **停在 `options.max ?? limits.exportMax`，声明了分页窗口（`RecordCapability.maxWindow`）时再停在窗口里的最后一整页**（`exportPlan`：每页条数不超过窗口，上限取 ⌊窗口 / 每页⌋ × 每页），并在结果里以 `capped` 说明文件是截断的——越过窗口的那一页源直接拒绝，接着要下去就是拉完前面所有行之后整次失败；导出窗口事先说的上限也是这个数；空页当作结果的结束，哪怕源还报着下一页——这也是"源一直回空页"时唯一的出口；
- **取消用 `AbortSignal`**，每一页各自建一个 `AbortController` 跟着它（`ViewSource` 收的是 controller，组件握的是 signal），取消时 Promise 以 `ExportCancelled` 拒绝，由 `isExportCancelled` 认出来——它是用户的答复，不是要报出来的失败；
- **进度是 `(fetched, total?)`**：分页源有总数就报，游标源没有总数，那就不报一个没人算得出的数。（见 test/exportRows.test.ts）

## Dashboard

实现拆在 `src/runtime/dashboard/`：`references.ts`（`PanelReferences`——面板引用的加载：未问／在加载／已加载（读不到为 `null`）三态，外加「到了却用不上」的失败原因；每一次落定回调一次，由 runtime 重新判草稿）、`children.ts`（`PanelChildren`——每个数据面板的子 runtime 与其生命周期：随每次 sync 对齐面板与作用域、面板没了或指向别处就释放、随仪表盘一起 dispose；子 runtime 一通知就回调 runtime 重排计时器并重建该面板的 issues）、`panels.ts`（无状态的读法与寻址：`panelOf`、`blocksBoard`、`boardFindings`、`stopsSave`、`panelIssues`、`atPanel`、`samePanels`；读不可信配置的 `panelsOf`、`tabsOf` 在内核 `src/dashboard/panels.ts`，各层都从那里读）、`panelRun.ts`（一块面板跑什么、交出什么，以及整板每块面板的状态 `boardPanels`）、`commands.ts`（`BoardRules`——跨部件的规则各有名字、放在一处：搭建放行什么、停不停表、谁的刷新间隔生效、默认值即此刻的值、宿主持有筛选后重读点击、板子打开在哪；`BoardCommands`——其余每条命令只转给一个部件，不做判断）、`presentation.ts`（`presentedConfig`：面板的展示覆盖叠到视图配置上，不合身就丢掉并注明）。`DashboardViewRuntime` 只剩组装：准入、状态与计时器的接线，以及把引用、子 runtime 与筛选值对齐的 `sync`。

`DashboardRuntime` 持有 N 个子 `ViewRuntime`、板子配置的草稿与板子筛选此刻的值（见下文「筛选此刻的值是读者的」）。板子没有自己的条件（[D27](decisions.md#d27-仪表盘不画正在显示条2026-09-24)）：整板只有固定范围 `fixed` 一条读者改不了的条件（`boardCondition`）。`apply()` 校验配置，为每个面板把固定范围映射到面板字段、再 AND 上接上它的筛选，经 `setScopeFilter` 注入再触发子 runtime 执行。**板子自己不收外来的条件**（[D26](decisions.md#d26-阶段-34-联合审查的十一条拍板2026-09-24) Q32）：`setScopeFilter` 对仪表盘只答一条 `dashboard.scope.unsupported`、什么也不变，`scopeFilter` 恒为 `null`；`open` 带来的那一棵同样被拒、记在 `refusedScope` 上，板子照自己的配置跑。宿主要收窄一块板，是逐个锁定或隐藏它的筛选（下文「宿主持有的筛选」）。（见 test/dashboardHandOver.test.ts「refuses one and runs as it was: a board is narrowed filter by filter」「refuses one asked for as it opens, and runs without it」）

- `dashboard.panels.too-many` 这类路径为 `['panels']`、不属于任何一个面板的 Issue 按 Dashboard 整体的 error 处理，阻止全部面板执行。
- **板子说自己的，只有一种读法**（R3：Q-01／A-07）：`boardFindings(state)`（`runtime/dashboard/panels.ts`）是草稿上**没有哪块面板带着**的发现——板子自己的（「面板太多」在 `['panels']` 上，不属于任何一块）与草稿提出、还没有面板带上的面板发现（整板条件映射到面板字段后发出的 warning，应用之前只在草稿里，保存会把它看不见地写进去）；面板自己的留在面板上说。控制器的 `issues` 就是它，工作台、可编辑档嵌入与自己画板子的宿主读同一份——从前是三种分法，控制器那份漏掉「面板太多」，可编辑档嵌入丢掉草稿里的面板 warning。（见 test/boardFindings.test.tsx「what a board says about itself (boardFindings)」）
- **只有这种整板的 error 挡住整板**（`blocksBoard`，`runtime/dashboard/panels.ts`）：`apply()`、`revert()` 之后的重新应用、整板的刷新计时器都只看它。面板自己的 error——引用用不了、绑定不成立、链接的协议被拒——只让这一块不跑、在面板上说明，全局筛选照样应用到其余面板，拖拽照样落地，计时器照样刷新其余面板。从前 `apply` 见任何 error 就拒绝：一块坏面板让拖拽弹回、全局筛选点了没反应（R1）。`RuntimeStore` 为此多了一个可选的 `blocking` 钩子，数据视图不给、仍是「任何 error」。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime a panel in error」）
- 子 runtime 拒绝注入的作用域时，该子 runtime 被释放而不是继续跑旧口径，拒绝理由以 `['panels', index, ...]` 为路径记在该面板的 `issues` 里，其余面板不受影响。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime child refusal」）
- 子 runtime 接受作用域后，它对自身已存配置的 warning **与 note** 同样重定址到该路径记入面板的 `issues`，面板照常运行——note 是那张画不了这个结果、退回表格的图（`chart.as-table`），从前只收 warning，面板里的图一声不吭就成了表格，工作台与嵌入视图却都会说（批 B2，见 test/dashboardBuilding.test.tsx「says why a chart shows as its table, in the words of the picker」）；**子 runtime 上一次结果自身的 warning**（`resultIssues`：汇总退回本页 `runtime.summary.page-only`、分析还有更多组 `analysis.result.more-groups` 或探不成时的 `analysis.result.at-limit`）也并入，同样重定址——两个工作台与 `EmbeddedView` 都会说这两条，同一张被截断的饼图放进仪表盘不能就不说了；子 runtime 一通知（结果落地就是一次）面板的 `issues` 就重建，不等下一次 Dashboard 同步（见 test/dashboardRuntime.test.ts「carries a child result warning on the panel」）——从子 runtime 的快照读取，因为 `setScopeFilter` 对未变化的作用域返回空，而布局编辑会以同一作用域重新同步每个面板。
- `validateDashboard` 对映射后筛选的复验与子 runtime 对同一棵合并树的准入会让一个 kind 的 warning 出现两次，同 code 同 params 的只记面板校验的那一条。
- 宿主经 `panelRuntime` 驱动子 runtime（`edit`／`apply`）改变其 issues 时，面板的 `issues` 随子 runtime 的通知重建，不等下一次 Dashboard 同步。
- 作用域条件不进入子 runtime 的 `draft` 或 `saved`，面板因此不会变脏，也不会把 Dashboard 条件保存回被引用实例，执行的有效配置记录在 `result.config`。（见 test/dashboardRuntime.test.ts「never makes the referenced view dirty or changes its saved config」）
- **点一组**（D22 H、I，批 D，`runtime/dashboard/press.ts` 的 `PanelPresses`）：面板状态多一个 `click`——面板的点击，准入对它说过话就是 `null`（`clickInForce`：点一组回到追问菜单）。**交叉筛选** `crossFilter(panelId, row)`：`row` 是图或表交回来的那一组（按别名），经分析内核 `drillGroups` 读成这一组的条件，取出面板接这个筛选的那个字段上的那一维，按筛选类型写成它的值（日期是桶的时间窗、是否是那个值、ID 是一项、文本与数字是一项的列表；没有值的那一组答 `no-value`，什么也不变），再经 `FilterValues.press` 设进去并记下来处（`DashboardFilters.from`）；**同一组再点一次就清掉**（必填的回到默认值）。答 `set`／`cleared`／`no-value`／`none`，界面据此说一句。**被点的面板不筛自己**：`panelRun` 跑一个面板时跳过来处是它自己的那些筛选（其余接线的面板照常 300 毫秒后按新值重跑），于是它保留所有组，`pressed(panelId, row)` 回答哪一组是点中的那一组、界面据此标出它。从筛选条改这个值（`setFilterValue`）、「清空」都不是谁的点击，来处随之消失，那块面板从此也按这个值筛。**自定义目的地** `destination(panelId, row)`：地址填上这一组（`{ kind: 'url' }`，填出来不安全就拒绝并说为什么）；视图先经 `PanelReferences.fetch` 读进来（只在点的那一刻读，打开板子不为目的地多一次加载），读不到或是仪表盘就拒绝，否则 `{ kind: 'view', instanceId, filter }`——这一组的条件里，目的视图的数据也有同名同类型字段的那几条（与自动接线同一条规则）。**另一块仪表盘**（D23 Q17）：同样经 `PanelReferences.fetch` 在点的那一刻读，用内核 `validateBoardClick` 按读到的板子判映射；有一条不成立（板子没了、筛选没了、维度没了或收不了）就答 `{ fallback: Issue }`——界面说一句并在点中的那一组上打开追问菜单；读进引用的板子同时触发重判，面板从此带着 warning、`clickInForce` 为 `null`。成立就答 `{ kind: 'dashboard', definitionId, instanceId, filters }`：`filters` 从那块板（迁移后）的 `defaultFilters` 起，每条映射的筛选换成它的来源给的值——维度是这一组在那一维上的值（与交叉筛选同一个 `filterValueOf`），这块板的筛选是它此刻在 `DashboardFilters.values` 里的值；没有值（那一组是「（空）」、那个筛选空着）或目的筛选拒收（`filterValueIssues`，如几个值给只收一个的）就不换。`destinationBoard(instanceId)` 是「点击时…」读那块板的同一条路（答迁到 24 列的实例，读不到或不是板子答 `null`），读一次也就让这块板按它重判。打开这块板时这些都不读：`PanelReferences.load` 只收面板引用的视图。（见 test/dashboardPress.test.ts「a press that opens another board (D23 Q17)」）
- 每个面板独立 loading / error / result，Dashboard 不汇总成单一状态。
- 自动刷新由 DashboardRuntime 按自身 `refresh.interval` 统一计时并触发全部数据面板的 `refresh()`；被引用实例自身的 `refresh` 配置在 Dashboard 内忽略，避免两层计时器。`nextRefreshAt` 同样是这一只计时器的到期时刻（「在途」问的是面板），标题栏那处倒计时因此数的就是整块板子的下一次刷新。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime refreshing」）
- **读者改间隔不算「已修改」**（D26 Q35）：间隔的入口走 `setRefreshInterval(interval)`。板子被读时它是这位读者这次打开的（`state.readerRefresh`），与筛选的值一样不进草稿，板子不变脏，计时器按它而不是按 `applied.refresh`（`RuntimeStoreHost.interval`）；限额收不下的间隔忽略。搭板子时它是板子自己的，`edit` 加 `apply` 写进草稿、随「保存」写出。开始搭板子放掉读者的那一个。（见 test/dashboardRefresh.test.ts「a reader’s refresh interval (D26 Q35)」）
- **搭板子期间整板不自动刷新**（D26 Q39）：`setBuilding(active)` 与 `state.building` 是这次打开的「正在搭」（「编辑」按下到「保存」或「取消」），两种界面都读它而不是各记一份。为真时整板的计时器停、`nextRefreshAt` 为 `null`，作者手下的面板不被重跑；放开即重新武装。（见 test/dashboardRefresh.test.ts「building a board holds its auto refresh (D26 Q39)」）
- 单个面板的重跑是 `refreshPanel(panelId)`：整板刷新收窄到一个子 runtime，面板失败时的「重试」用它；没有子 runtime 的面板（不可用、被拒）没什么可重跑，它什么也不做。刷新失败时子 runtime 的 `result` 不动（「只随成功推进」），面板因此留着上一次的结果，由界面注明（[ui/dashboard.md](ui/dashboard.md#面板失败保留上次的结果可以重试)）。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime refreshing one panel」）
- **用手摆放走 `place(panelId, layout)`**（`edit` 碰不到 `panels`，见下文搭板子的历史）：它把这一处摆放同时写进 draft 与 applied，然后按 applied 同步，**不提升 draft 的其余部分**——从前摆放走 `edit` + `apply`，正在编辑、还没应用的全局筛选随手一挪就跑了半份（R2）。被摆的面板占它要的格子，盖住的面板让开，然后**它所在的标签页整页上浮压紧**（批 A 走查，照 Metabase）：不留洞，拖走的面板原位由下面的补上，放到空处的面板浮到有东西托住为止（`placePanel`，`src/dashboard/layout.ts`，见 [ui/dashboard.md](ui/dashboard.md#dashboardgrid-与几何写回)）。只有用手动才压紧：打开保存的仪表盘什么也不动，库自己算的布局不写回。只动这一个标签页——另一个标签页是另一张栅格。越出栅格、不是面板的摆放被忽略。不改引用与作用域，所以没有面板重跑。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime placing」、test/dashboardLayout.test.ts）
- **旧形式的板子在一个读取边界读成新形式**（D22 E、D23 Q16、D26 Q31／A-06；AGENTS.md 的存量数据例外）：`ViewEngine` 持有的 `store` 是宿主存储经 `readingStore`（`runtime/storedViews.ts`）包过的一份——`get`、`create`／`save`／`rename` 的回执，以及写入冲突时带回的服务器那一份，都先过 `readStored`，仪表盘就是 `migrateDashboardConfig`（内核，`src/dashboard/migrate.ts`：12 列栅格读成 24 列，批 C 之前的整板条件读成筛选默认值与固定范围 `fixed`，D27 之前板子自己的 `filter` 与 `filterMode` 拿掉、有条件的并进 `fixed`）。过了这里的一切——runtime 的配置与基线、`adoptSaved`／`moveBaseline`、面板引用、点一下打开的另一块板——只见新形式、自己不迁；所以打开旧板子不会变脏，第一次保存写出新形式。写在代码里的系统仪表盘是代码，不经过它。（见 test/dashboardEditing.test.ts「a board stored in the 12-column grid」「a pre-C board read once, through save and reopen」）
- **搭板子的命令（`DashboardEditing`，D22 A～E）走同一条路**：`addPanel`／`removePanel`／`duplicatePanel`／`renamePanel`／`replacePanelView`／`editPanelContent`／`movePanelToTab`／`setPresentation`／`referToSaved` 与四个标签页命令，每条都是内核里的一个纯函数（`src/dashboard/edit.ts`、`tabs.ts`），像 `place` 一样同时写进 draft 与 applied、不提升草稿的其余部分——**编辑中面板按草稿实时重跑，「完成」（保存）才写回，「取消」是 `revert`**（D22 A，用户拍板）。加面板时新 id 与位置按 draft 算一次，applied 拿同一块面板，不各算各的。新引用照常加载。命令指向不存在的面板或标签页时什么也不做。能不能编辑是界面按权限给不给入口；**编辑只属于搭建**（R3：Q-02）：`state.building` 为假时（「编辑」之前，「完成」或「取消」之后）每条命令都被拒——什么也不变、不记一步，有返回值的答被拒时那一个（`null`，没有接上的面板）。一个比搭建活得久的手势——「添加」选好视图、视图还在读时按了「取消」——从前仍把面板加进了刚还原的板子，让它又变脏。`place` 暂时还不受这条管：栅格的两份界面测试在没开始搭的板子上摆放、再撤销，挪进来要等它们（todo R3）。撤销与重做本身不看搭建：历史就是搭建的——搭建结束时忘掉，所以搭建之外能撤的只有那种摆放。（见 test/dashboardEditing.test.ts「editing a board」、test/dashboardBuildingGate.test.ts「an edit is the building’s (Q-02)」）
- **搭板子的历史：一条命令一步，撤销与重做**（批 B2，`runtime/dashboard/history.ts` 的 `EditHistory`，由 `boardEditing` 持有）：`DashboardEditing` 与 `DashboardFilterEditing` 的每一条命令——加、删、复制、摆放、调顺序、改名、换视图、改内容、换标签页、展示、另存后改指、点击、四个标签页命令、筛选的全部设置、接线、时间粒度——是**一步**（`EditStep`：命令名与它说的是哪个面板、标签页或筛选），记下它**改过的那几个成员**（`panels`、`tabs`、`fields`、`timeGrouping`……）在草稿与屏幕上改之前与改之后的样子；什么也没改（包括重新做出一样的东西，比如原样提交的表单）的不算一步。「另存为视图」与「复制为共享视图并替换」之后的改指（`referToSaved`）也是一步：撤销只把面板改回原来的视图，已写下的视图留着（撤销管草稿，删一个已保存的视图是另一次写）。`undo()` 把那几个成员放回改之前，`redo()` 放回改之后，走的是编辑同一条路（草稿与屏幕一起、面板随之重跑、被删又回来的面板重新读引用、脏与否照常判），**别的成员不动**——中间拼的整板条件、改的刷新间隔都留着，撤销也不是一次应用。**同一件东西上连着的同一种改名或设定是一步**（改面板、标签页或筛选的名字，筛选的默认值与自己列的一组，展示，时间粒度）：逐字打的名字、弹层里试过的几种图型，撤销一下回到开始之前；连着改回原样的就不是一步。一次新的编辑丢掉可重做的那一步；最多记 `EDIT_HISTORY_DEPTH`（100）步。撤销或重做了设默认值，筛选此刻的值跟着它（与设默认值同一条规则）。**普通的 `edit(patch)` 碰不到历史管的成员**（R3：A-10；`runtime/dashboard/history.ts` 的 `outsideHistory`）：`panels`、`tabs`、`fields`、`timeGrouping` 只由搭板子的命令写、每条一步；历史按整个成员记，一次绕过它改了面板 b 的 `edit({ panels })`，会在撤销面板 a 的改名时被一起撤掉、谁也不说。其余成员——整板条件、刷新间隔——照常进草稿，给成 `undefined` 的删键（`model/json.ts` 的 `overlaid`，与数据视图同一条），撤销不碰它们。（见 test/dashboardBuildingGate.test.ts「a plain edit of a board and its history (A-10)」）**历史在 `revert`、`markSaved`（保存）、`adoptSaved`（读回别处的写）与搭建结束（`setBuilding(false)`）时重来**：取消与完成之后没有东西可撤销。`state.history` 说此刻撤销与重做各是哪一步（没有为 `null`），界面据此给按钮起名。内容面板的表单改内容与标题是一次 `editPanelContent`（`title` 按 `renamePanel` 读，空白就去掉），所以是一步。（见 test/dashboardHistory.test.ts「the history of building a board」「EditHistory」）
- **窄屏的调顺序**（D22 J）：`reorderPanel(panelId, 'up' | 'down')` 在面板所在标签页的阅读顺序里前后挪一位，由内核 `reorderPanelIn` 写成宽布局——草稿与屏幕一起，一步（规则见 [ui/dashboard.md](ui/dashboard.md) 的「窄屏」）。（见 test/dashboardHistory.test.ts「reordering the one-column reading, in the runtime」、test/dashboardLayout.test.ts「reordering the one-column reading」）
- **加面板之前先把视图读进来**：`preload(instanceId)` 读一个还没有面板指着的已保存视图、落定（读到或读不到）后兑现，从不拒绝；`addPanel` 按视图显示的东西定大小（`defaultPanelSize`）只在它已读进来时做得到，所以界面的「添加」先等它（批 B2）。读进来的引用就放在 `PanelReferences` 里，面板的子 runtime 直接用，不再读一次。（见 test/dashboardBuilding.test.tsx「adds a saved view from the picker, sized by what it shows」）
- **板内分析视图**（D22 C）：`owned` 面板没有引用可加载，定义是代码，`DashboardRuntimeOptions.definitions`（工厂从定义注册表查，查不到或准入失败为 `null`）同步给出；准入把它当成已保存分析一样判（绑定、合并后的全局筛选；定义不在报 `dashboard.panel.definition-unknown`），子 runtime 从它自己的配置开、`saved` 为 `null`（它随板保存，永远不单独保存），所以它的问题由子 runtime 自己的准入判——与已保存分析一字不差；判不过的面板不跑、在面板上说为什么。板内分析改了问题，子 runtime 不重建：同一个子 runtime `edit` 新配置再 `apply`，屏幕上的结果留到新结果到为止。「另存为视图」是 `ViewEngine.saveOwnedView`：按分析视图的规则校验、要标题与创建许可，写出实例后 `referToSaved` 把面板改为引用它（引用直接播种进 `PanelReferences`，不再读一次），展示覆盖保留；板子本身随后照常保存。（见 test/dashboardEditing.test.ts「a view the board owns」）**「复制为共享视图并替换」是 `ViewEngine.copyPanelView`**（D22 B）：复制的是面板上子 runtime 的 `saved`——视图**保存时**的配置，不带面板的展示覆盖与板子的筛选（那两样仍是面板的）——按同样的规则校验、要标题与在目标受众下创建的许可，写出副本后同一个 `referToSaved` 把面板改为引用它，展示覆盖、点击、接线都留着（显示的还是同一个视图）；个人视图原样留着。面板没有这位读者打开着的已保存视图（板内分析、打不开的引用、没有的面板）时拒绝，`dashboard.panel.not-referenced`。（见 test/dashboardEditing.test.ts「refuses to copy a panel that shows no saved view this reader has open」、test/dashboardBuild.test.ts「point a saved panel at its copy, keeping its look and its click」）
- **展示覆盖**（D22 D）：子 runtime 拿到的配置是「视图自己的配置 + 面板的 `presentation`」（`runtime/dashboard/presentation.ts` 的 `presentedConfig`）：记录视图只认 `layout`，分析认 `layout`／`chart`／`table`。覆盖不再合身——成员这种视图没有，或者分析内核判这张图画不了这个结果——就整份丢掉、面板照视图原样显示，带一条 warning `dashboard.panel.presentation-dropped`，不是 error；视图本身已判不过时说的是视图的问题。覆盖只改怎么看，所以同一个子 runtime，不重建。**只改了画法的那一种只重画不重跑**（D20：展示从不问数据源；批 B3 收口）：新旧配置只在 `presentationMembers(kind)`——分析的 `layout`／`chart`、记录的 `layout`——上不同时，`PanelChildren.sync` 只把它 `edit` 进子 runtime 的草稿、不 `apply`，面板由草稿在手上的行上重画（`ui/dashboard/PanelBodies.tsx` 的 `AnalysisPanel` 走 `useAnalysisResult`，与工作台同一条路）；表格合计行是一次自己的查询，改它照旧 `edit` + `apply`，旧结果留到新结果到。覆盖永远不写回被引用的视图。（见 test/dashboardEditing.test.ts「a panel's override of how it looks」的「changes in the same child, redrawn and never asked again」「runs again for a totals row, which is a query of its own」）
- **只跑屏幕上的那一页**（D22 E，批 B3 收口）：`state.tab` 是屏幕上的标签页——`showTab(tabId)` 要的那一页、板子有它时；否则第一页；没有标签页为 `null`。它是读者的，不进配置，切换不让板子变脏。`sync` 只把这一页的面板与子 runtime 对齐：别的页上**已有**子 runtime 的面板原样留着（`PanelChildren.hold`：行、作用域都不动，只更新问题的地址），**从没看过**的页上的数据面板没有子 runtime、标 `waiting`（没问过、也没坏）。切过去时 `sync` 对齐那一页：新面板建子 runtime 并跑；看过的面板只有问的东西变了（全局筛选、视图、覆盖）才重跑，否则直接画留着的行。整板刷新（计时器与按钮）只刷新屏幕上那一页，别的页上的子 runtime 记下 `missed`，切回去时补刷一次（这一趟本来就要因作用域变了而重跑的，就不另刷）。`removeTab` 删掉屏幕上那一页时落到第一页。打开时先定页：`ViewEngine.open` 在第一次 `sync` 之前 `showTab`（宿主给的 `OpenOptions.tab`，板子有它时；否则读者的 `ViewPreferences.lastTabs`），于是记住的那一页直接开始跑，而不是先跑第一页再切过去。（见 test/dashboardTabs.test.ts「only the tab on screen runs」「where a board opens」）
- **筛选此刻的值是读者的**（D22 F，批 C1；`runtime/dashboard/filterValues.ts`）：`state.filters: DashboardFilters`——每个筛选的值与时间粒度的单位——和 `state.tab` 一样不进配置，设它从不让板子变脏，保存也不写它。它从默认值开始（`defaultFilters`），或从宿主地址里读回来的那一份开始（`OpenOptions.filters`，`ViewEngine.open` 在第一次 `sync` 之前交给 runtime，于是第一次查询就带着它，不先按默认值跑一遍）。进来的每一份都先经 `admitFilters` 按**屏幕上的板子**准入：板子没有的筛选、它的种类读不了的值、单值筛选给了多个、时间粒度不提供的单位——都不收并如实答出（`setFilterValue`／`setFilters` 的返回值），收下的才生效。**收多少看是谁说的**：读者的一次改动（`setFilterValue`、一次交叉筛选的点击、`setGroupingUnit`）只改一个名字，全有或全无——被拒就原样不动；宿主地址（`setFilters`、打开时的 `OpenOptions.filters`）一次说全部，**部分接收**——收下能收的，被拒的逐条答出，于是地址里一个筛选改了名或删了、一个值读不了，只丢它自己，不让读者收藏的整个地址失效、板子悄悄回到默认值。打开时没有返回值可答，被拒的记在 `DashboardRuntime.refusedFilters` 上（连同 `OpenOptions.held` 被拒的；打开时写一次，之后不变；全收下时是 `[]`），宿主或界面可以据此说一句。（见 test/dashboardFilterRuntime.test.ts「opens on what it takes of an address gone partly stale, and says what it left out」「takes what it can of every filter put at once, answers the rest, and runs on it」「refuses a value its filter cannot take, and changes nothing」、test/embeddedDashboard.test.tsx「opens on what it takes of an address gone partly stale: one stale name or bad value costs only itself」、test/dashboardWorkbench.test.tsx「opens under what it takes of a stale address, the rest left out and said (D22 F)」）**必填筛选永远有值**：清空（`setFilterValue(名, null)`）、「清空」（`clearFilters`）、宿主地址里没写，都回到它的默认值，所以板子从不在必填筛选为空时跑。改了板子（删筛选、换类型、改默认值）之后再按新板子重读一遍：删掉的不再有值，换了类型的旧值作废；**设默认值时它此刻的值也跟过去**（`setFilterDefault`：作者设了默认值就看到板子按它跑）。（见 test/dashboardFilterRuntime.test.ts「what the filters hold」）
- **宿主持有的筛选**（D22 嵌入一半；`holdFilters(held: HeldFilters | null)`）：嵌入页面锁定与隐藏的筛选是宿主的。`HeldFilters` 点名每个持有的筛选与它的值（`null` 是它的默认值），带 `unit` 时连时间粒度一起持有；值随即按屏幕上的板子准入、生效，被拒的不收并如实答出——每问一次答一次，同样的问题同样的答案；能收的照收。持有之后读者的命令都碰不到它们：`setFilterValue` 拒绝（`dashboard.filter.held`），`clearFilters` 与 `setGroupingUnit` 绕过它们，设它的交叉筛选点击被放到一边（`clickInForce` 读作没有点击，于是追问菜单；`PanelPresses` 也不设它）。每次调用取代上一次，放手的筛选留着值、重归读者。别的文本筛选的候选值在持有的值下计数。`OpenOptions.held` 让它们从第一次查询起就在。`setAutoRefresh(false)` 让整板那一个计时器一直停着，与数据视图同一个开关。（见 test/embedRuntime.test.ts「is in force from the first query when the board opens under it」「follows the page, puts a default back for null, and lets go」「answers what the board refuses of the page, every time it is asked, and takes the rest」「sets aside a click that sets a filter the page holds: a press does what a panel without one does」「holds a board’s one timer too」）
- **离开板子时交出什么**（D26 Q30、Q33；`handOver(panelId)`，`runtime/dashboard/panelRun.ts` 的 `panelHandOver`）：一块数据面板的视图离开板子——「在工作台中打开」、追问、点击去另一个视图、板内分析——带走的是**同样的两部分**，都已映射成那个视图自己的字段名（`HandOver`，`runtime/navigation.ts`）：`scopeFilter` 是不归读者的那部分——板子的固定范围（`fixed`，D26 Q31）与页面持有的（锁定与隐藏的筛选，`holdFilters`）——到了那边是它的作用域，谁也拿不掉（照 D22 H4）；`filter` 是读者设的筛选值，到了那边是视图**自己的**条件，读者可以逐条拿掉。被点的面板不筛自己的那一维照样不带（`DashboardFilters.from`）。`from` 是回板子的路（`BoardOrigin`）：板子的标题，与 `back`——`{ kind: 'dashboard', definitionId, instanceId, filters, tab }`，即这块板此刻的筛选与标签页；从没保存过的板子没有 id 可回，不带 `from`。不是数据面板、面板不在、runtime 已释放时答 `null`。四个出口各自拼出路由目标（`ViewNavigation`，`runtime/navigation.ts`）：已保存的视图是 `{ kind: 'view', definitionId, instanceId, scopeFilter, filter, from? }`；没保存的是 `{ kind: 'unsaved', definitionId, title, config, scopeFilter, named?, from? }`，`filter` 已经并进 `config` 自己的条件（`withHandedFilter`：一层 AND 时平铺，否则嵌套并换成 advanced）。点击去另一个视图时，两部分与这一组的条件都只留目的视图的数据也有**同名同类型**字段的那几条（与自动接线同一条规则），这一组的条件归读者、可删。（见 test/dashboardHandOver.test.ts「DashboardViewRuntime hands a panel’s view over (D26 Q30, Q33)」、test/dashboardHandOver.test.ts「hands the board’s fixed scope with what the page holds: neither is the reader’s (D26 Q31)」、test/dashboardPress.test.ts「takes what the panel takes off the board (D26 Q30): the page’s hold as the scope, the reader’s value among its own」、test/boardHandOver.test.tsx「leaving a board through the host’s route (D26 Q30)」）
- **改了就跑**（与分析视图的「自动运行」同一个意思，D22 F）：筛选条上没有「应用」。一个值变了，屏幕上的筛选立刻是新的，面板在最后一次改动之后 `AUTO_APPLY_DELAY_MS`（300 毫秒）自己重跑——连打几个字是一次查询，不是每个字一次（`RefreshTimer`，与自动刷新那只各是各的）；第一次 `sync` 之前只记下值。一个面板跑的条件是「板子的固定范围经它的接线映射」AND「接上它的每个有值的筛选」（`panelFilterTree`，`runtime/dashboard/panelRun.ts` 的 `panelRun`），**没接上的筛选到不了这个面板**。（见 test/dashboardFilterRuntime.test.ts「runs a change a moment later on its own, only where the filter is wired, and never makes the board dirty」）
- **时间粒度**（D22 F）：每个分析面板的日期直方图维度在子 runtime 拿到配置之前换成板子此刻的单位（`regrouped`，`runtime/dashboard/grouping.ts`）——**只在视图的定义允许那个字段按那个粒度分组时**（问的是分析内核的 `analysisScope`，不是另一份清单）；允许不了的面板保留自己的粒度，并在面板上带一条 note `dashboard.grouping.kept`（它说的是这个面板仍按它自己的粒度看，界面随头部标记说出来）。换粒度是一次问题的改变，子 runtime 照同一条路 `edit` + `apply`，不重建。每个面板的状态带 `grouping`：`taken`／`kept`／`null`（没有时间维度或板子没有时间粒度）。（见 test/dashboardFilterRuntime.test.ts「the time grouping」）
- **每个面板带着它受哪些筛选影响**：`DashboardPanelState.reach`（`filterReach`：接上了经哪个字段、是否自动；没接上是没有可接的字段还是没人接），读进来的视图才知道字段，没读进来的只答接上的；内容面板是空的。界面据此在面板头上说「不受『〈筛选〉』影响」，并用 `filtersOnTab` 把在当前标签页上什么也没影响的筛选淡一档。（见 test/dashboardFilterRuntime.test.ts「what reaches a panel」）
- **设置筛选走同一条路**（`DashboardFilterEditing`，`runtime/dashboard/editing.ts`）：加、改名、换类型、删、默认值、必填、可多选、自己列一组、调顺序、时间粒度，以及接线 `bindPanel(筛选, 面板, 字段)`（返回自动接上的面板）与 `unbindPanels`——每条都是内核里的一个纯函数，像其余搭板子的命令一样同时写进草稿与屏幕、「完成」才保存。**加进来的数据面板自带接线**：`addPanel` 在放下之前按 `autoBindings` 把它接到它有同名同类型字段的每个筛选上（视图要先读进来，`preload`，界面本来就先等它）。这些命令与其余只转一手的命令都在 `BoardCommands`（`runtime/dashboard/commands.ts`）里；跨部件的那几条规则——设默认值同时设此刻的值、撤销设默认值时值跟着、非搭建态拒绝编辑——在同一个文件的 `BoardRules` 里，各有名字（R3：A-04）。（见 test/dashboardFilterRuntime.test.ts「setting the filters up」）
- **文本筛选的候选值**（D22 G「值从哪来：接上的字段」）：`valueCandidates(筛选名)` 给一个 `ValueCandidateSource`——它接上的每个字段在数据里真有的值，带记录数，与文本条件的候选值同一条路（`ValueCandidateSources`，#1768；工厂经 `DashboardRuntimeOptions.candidateSources` 给出）；同一份数据的同一个字段只问一次，不同数据里的同一个值合成一条、记录数相加；**数在面板跑的那个条件之下**——「一块面板在什么条件下」只有一处定义（`panelScope`，`runtime/dashboard/panelRun.ts`）：板子的固定范围（`fixed`）、接上它的筛选的值，经面板的绑定映射到它的字段名；面板跑时带上此刻所有的值，数候选值时只带宿主持有的那几个（读者自己选的不带，否则列表只剩已选的那几个），于是固定在「区域＝华东」的板子只数华东的值。这个条件每问一次现读（板子与面板都读此刻的），与上一次答案所问的不同就忘掉答过的——宿主持有的筛选换了一个值、作者改了固定范围，都重新数（`FilterCandidates`，`runtime/dashboard/filterCandidates.ts`；见 test/dashboardFilterRuntime.test.ts「counts under the condition the panel runs under: the board’s fixed scope, never the reader’s values」「counts again once what the panel runs under moves, a held value included」）。接上的字段自己声明了一组（`enum`）时不数：`wiredOptions(筛选名)` 给出那一组，界面从中选（见 test/dashboardFilterRuntime.test.ts「is the list the wired fields declare where they declare one, never counted」）。日期、数字、是否、自己列了一组的、谁也没接的，或接上的字段没有候选值的，答 `null`——界面就是一个普通的输入框。（见 test/dashboardFilterRuntime.test.ts「what a text filter offers」）
- **保存只被整板的 error 挡**（D22 B）：`stopsSave(kind, issues)`（`runtime/dashboard/panels.ts`）对记录与分析视图是「任何 error」，对仪表盘就是 `blocksBoard`——面板自己的问题（引用读者看不到、绑定不成立、视图保存的设置已不可用）在面板上说，随板保存；作者可能正是要保存去修另一块面板，一块坏面板让整板存不了，板子就没人维护得了。`ViewEngine.save`／`saveAs` 与 `useSaveCommands` 的 `hasErrors`／`blocked` 用同一个函数。共享板引用个人视图因此是 warning（`dashboard.panel.scope-too-narrow`）：面板对看得到的人照常跑，头部标记说明不是每位读者都看得到。（见 test/dashboardEditing.test.ts「what stops a save」、test/dashboardRuntime.test.ts「shares a dashboard that stands on a personal view」）

## ViewEngine

`ViewEngine` 是注册表与命令入口（代码里是一个类，下面以接口形式列出其公开面），命令语义见 [management.md](management.md)：

```ts
export interface ViewEngine {
  readonly store: ViewStore;
  readonly environment: RuntimeEnvironment; // 时钟、计时器、可见性；由创建方注入
  definitions: ReadonlyMap<string, ViewDefinition>;
  resolveSource(key: string): ViewSource; // 数据来源：QueryApi 的 paged / cursor / aggregate 三个方法，见「环境」
  resolveOptions(key: string): OptionSource; // FieldDefinition.remote 的候选来源；运行时以 `optionSource(remote)` 转交给筛选编辑器（宿主没接就是 null，编辑器退回打字）

  open(
    instanceId: string,
    options?: {
      scopeFilter?: FilterTree | null;
      tab?: string | null;
      filters?: DashboardFilters | null;
      held?: HeldFilters | null;
    },
  ): Promise<AnyViewRuntime>; // store.get → validate → runtime；按 runtime.kind 收窄；scopeFilter 从首次查询起生效并与配置一起准入，被拒则不生效、记在 refusedScope 上；仪表盘不收 scopeFilter（`dashboard.scope.unsupported`，记在 refusedScope 上）；tab 是仪表盘从哪一页开（板子没有它就读 lastTabs）；filters 是它的筛选从哪开始（部分接收），held 是宿主持有的那几个（holdFilters），都在第一次 sync 之前交给 runtime，两者被拒的记在 refusedFilters 上
  create<C extends ViewConfig>(
    definitionId: string,
    input: {
      title: string;
      scope: 'personal' | 'shared';
      config: C;
      scopeFilter?: FilterTree | null;
    },
  ): RuntimeFor<C>; // 未保存的新视图；config 必填，由 default*Config / emptyDashboardConfig 生成；不问许可——什么都还没写，第一次 save 才问（H1）；scopeFilter 同 open，下钻出的视图借此继承来源的作用域（H4）
  save(runtime: ViewRuntime): Promise<ViewInstance>; // saved ? store.save : store.create；被 stopsSave 挡的 draft 拒绝
  saveAs(runtime, input: { title; scope }): Promise<ViewInstance>;
  saveOwnedView(
    dashboard: ViewRuntime,
    panelId: string,
    input: { title; scope },
  ): Promise<ViewInstance>; // 板内分析「另存为视图」：建实例，面板改为引用它（写进板子的 draft，随板保存）
  copyPanelView(
    dashboard: ViewRuntime,
    panelId: string,
    input: { title; scope },
  ): Promise<ViewInstance>; // 「复制为共享视图并替换」（D22 B）：面板显示的已保存视图按保存时的配置复制到 scope，面板改为引用副本（同上，随板保存）；原视图不动
  rename(id: string, title: string): Promise<ViewInstance>;
  delete(id: string): Promise<void>;
  reorder(definitionId: string, order: string[]): Promise<ViewPreferences>;
  setDefault(
    definitionId: string,
    instanceId: string | null,
  ): Promise<ViewPreferences>;
  setAutoRun(definitionId: string, autoRun: boolean): Promise<ViewPreferences>; // 改了就跑，与排序、默认视图同住一份偏好；不问许可
  rememberTab(
    definitionId: string,
    instanceId: string,
    tabId: string,
  ): Promise<void>; // 读者上次看的标签页（lastTabs）；一串切换只写最后一个，从不拒绝、失败即放手
  resolveDefault(summaries, preferences, explicit?): string | null; // management.md「列表、偏好与默认视图」的解析规则
  retryWrite(target: ViewRuntime | WriteHandle): Promise<ViewInstance | void>; // 复用原 requestId 与原正文重放；创建意图返回新实例
  abandonWrite(target: ViewRuntime | WriteHandle): void; // 清除写入状态，草稿保留
  resolveConflict(
    target: ViewRuntime | WriteHandle,
    choice: 'reload' | 'overwrite',
  ): Promise<ViewInstance | void>;
  pendingWrites(): ReadonlyMap<string, WriteState>; // 未结清的写入，按 WriteHandle 索引；含列表命令
  list(definitionId: string): Promise<ViewListing>; // { items, failed }：代码声明的系统视图 + store.list()；store 失败时 items 只剩声明的、failed 说原因；摘要的 kind 由各自的 config 投影而来
  subscribe(listener: (change: ViewChange) => void): () => void; // 列表变化的订阅面，形同 runtime.subscribe；返回退订
  preferences(definitionId: string): Promise<ViewPreferences>;
  permissions(definitionId: string): ViewPermissions; // store 同步提供，缺省全允许
  definitionIssues(definitionId: string): Issue[]; // 构造时对定义准入的结果
  openRuntimes(): readonly ViewRuntime[]; // 已打开的 runtime，供工作台管理页签
  close(runtime: ViewRuntime): void; // dispose 并从注册表移除；只调 runtime.dispose() 会留下一个死条目
  dispose(): void; // 关闭全部 runtime 并取消在途请求
}
```

`viewEngine.ts` 里只剩上面这张命令面：每条命令先准入、再交出去一份 `WritePayload`。命令脚下的东西各住一个文件，一个文件一件事——`runtime/definitions.ts` 的 `DefinitionRegistry`（定义注册表：构造时判一次定义、用的时候按 id 取，取不到或不可用即拒；代码声明的系统实例也由它造），`runtime/permissions.ts` 的 `PermissionGuard`（许可：store 同步作答，不答则全允许；系统视图一律只读——这句判断只写在 `instanceAbilities` 一处，管理器的按钮存不存在读的是同一个函数，D4 要的正是「按钮存在＝命令会被放行」，A2），`runtime/preferences.ts` 的 `PreferenceCache`（偏好缓存，外加 `orderSummaries` 与默认视图的解析规则），`runtime/summaries.ts` 的 `SummaryCache`（摘要缓存：列过的与写入确认的记下、删掉的丢掉，`locate` 在问 store 之前先问它，A8），`runtime/openRuntimes.ts` 的 `OpenRuntimes`（已打开的 runtime：登记、按实例找持有者、关闭与清理），`runtime/runtimeFactory.ts` 的 `RuntimeFactory`（装配一个 runtime：能力核对、注入时钟与调度、以及 dashboard 的两个回调）。拆分不改任何行为，`ViewEngine` 的公开面与上表一字不差。

写入账本单独住在 `runtime/writeLedger.ts` 的 `WriteLedger` 里，`ViewEngine` 构造一个并转发：引擎这一侧只剩注册表与命令准入——定义是否可用、许可、标题、草稿有没有 error——准入过了就把一份 `WritePayload` 交给账本，公开面上的 `pendingWrites` / `retryWrite` / `abandonWrite` / `resolveConflict` 都只是转发，语义不变。账本掌管 `requestId` 的生成、按 `requestId` 索引的未结清结局、同一目标同时只放行一个在途写入，以及「上一个 unknown 没处理完之前不放行新意图」这条拦截；重试沿用原 `requestId` 与原正文重放，覆盖写则带上冲突报回的 revision 以新 `requestId` 重发，`reload` 只对 `save` 换掉草稿。账本够不到的东西——摘要缓存、偏好缓存、同一实例的其他已打开 runtime——由引擎通过 `WriteLedgerHost` 的几个回调借给它；确认之后落到 runtime 上的 `markSaved` / `moveBaseline` / `adoptSaved` / `setWrite` 仍由账本驱动。（见 test/writeLedger.test.ts）

**列表变化由引擎通知（D15）。** `subscribe(listener)` 与 runtime 的 `subscribe` 同形：登记一个监听者，返回退订。任何一次落地的写入只要改变了某个定义下的列表，就通知一条 `ViewChange = { definitionId, kind, id }`——`kind` 是**那次写入**（`create` | `save` | `rename` | `delete`），不是视图的种类；账本里的重试与覆盖走的是同一处 `applyEffect`，因此照样通知。偏好写入（排序、默认视图）不在其列：它改的是顺序与默认，而发起它的那一方本来就握着结果。删除的正文里带上 `definitionId`（`WritePayload`），因为除此之外没有一处说得出它属于哪张列表，而重放要说得出同一句话。通知在效果落定之后发出，一个监听者抛出不会波及其余监听者，也不会把一次已经落地的写入记成待重试的结局——它被就地拦下，作为 `view.change.notify-failed` 交给 `onIssue`。`dispose()` 清空监听者。宿主因此不必在每个调用点后面记得刷新列表；`useViewList` 订阅的就是这一面（[react.md#useviewlist](react.md#useviewlist)）。（见 test/engine.test.ts「ViewEngine change notifications」）

**记录视图的运行时是数据视图运行时的子类**（`runtime/recordRuntime.ts` 的 `RecordDataViewRuntime`，由 `dataViewRuntime` 按配置种类选）：页、选择、导出、读一条整条只属于记录视图，都在子类里；共享的「提问—执行—落定」在 `DataViewRuntime`（`runtime/viewRuntime.ts`），记录视图经四个钩子接进去——新问题从哪开始（`startOver`：回第一页、放开选择）、这次查询要哪一页（`pageNow`）、答案落定还要改什么或是否要重问（`settle`：结果缩了退到末页、刷新保留幸存的选择）、自己的暂停计时器理由（`holds`：有选择）。分析视图的运行时因此没有一个只能拒绝的 `exportRows`，仪表盘面板用 `isRecordRuntime` 区分而不是强转。契约类型在 `runtime/viewRuntimeTypes.ts`。（见 test/exportRows.test.ts「gives a view with no rows of its own no export at all」）

`open` 与 `create` 对 Record 定义返回 `RecordViewRuntime`，其 `page` 只接受该定义声明的分页模式对应的目标；Analysis 与 Dashboard runtime 没有 `page`／`select`。`apply()` 与 `open()` 的首次查询按 `RecordCapability.paging` 选择目标：`paged` 用 `{ index: 1 }` 调 `source.paged`（Wow `Pagination.index` 从 1 开始），`cursor` 用 `{ cursor: null }` 调 `source.cursor`。`refresh()` 读**当前这一页**（刷新按钮、计时器、命令写完之后的那一次都是它）：从前它回到第一页，逐页处理一份列表的人每刷新一次就丢一次位置。结果在脚下缩了、当前页落空时（例如命令把最后一页的行移出了结果），落到现在够得到的最后一页（`record/paging.ts` 的 `pageAfterShrink`），而不是停在一张前面还有行的空页上。（见 test/runtime.test.ts「reads the page it is on again on refresh」「steps back to the last page there is when the result shrank under it」）

`create`、`save`、`saveAs` 与 `open` 都先核对 `config.kind` 与所属定义的能力：`kind: 'data'` 只接受能力已声明的 `record`／`analysis`，`kind: 'dashboard'` 只接受 `dashboard`，不匹配即 error，不进入执行或保存。这与系统视图的定义期检查是同一条规则。

`open` 时的定义校验：`validate*(definition, instance.config)` 产生 `error` 级 Issue 则 runtime 进入"待修复"，`apply` 被拒绝直到用户修正；`warning` 不阻塞。这是定义演进的全部处理。

## 环境

```ts
/** 一个定义的数据从哪里来：QueryApi 的三个方法，Wow 的快照客户端原样就是一个 ViewSource。 */
export interface ViewSource {
  paged(
    query: FilterPagedQuery,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<PagedList<RecordData>>;
  cursor(
    query: CursorQuery,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<CursorPage<RecordData>>;
  aggregate(
    query: AggregationQuery,
    attributes?: Record<string, unknown>,
    abortController?: AbortController,
  ): Promise<RecordData[]>;
}

/** reference 字段的远程候选：搜索分页与按 id 回填，供内置 reference 编辑器使用。 */
export interface OptionSource {
  search(
    input: { query: string; cursor?: string },
    signal?: AbortSignal,
  ): Promise<{ items: FieldOption[]; nextCursor: string | null }>;
  resolve(
    ids: (string | number)[],
    signal?: AbortSignal,
  ): Promise<FieldOption[]>;
}

/** runtime 与宿主环境之间的唯一接口；Node 缺省实现始终可见，`/react` 的 `useViewEngine` 注入基于 `document.visibilityState` 的实现。 */
export interface RuntimeEnvironment {
  now(): Date;
  timeZone: string; // 相对日期的解析、DATE_HISTOGRAM 的缺省切桶与界面显示共用
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  visibility: {
    isVisible(): boolean;
    subscribe(listener: () => void): () => void;
  };
}
```

**`ViewSource` 是写出来的，不是从 `QueryApi` `Pick` 出来的**，理由有三条，都指向同一件事——它是每一个数据来源都要实现的那个口子，所以它得**恰好**说出本包要的东西：`QueryApi.paged` 收的是 `PagedQueryRequest`，即 `FilterPagedQuery | PagedQuery`，而 `PagedQuery` 是弃用 API——`Pick` 等于把架构测试在别处一概禁掉的东西写进这个口子；`QueryApi.aggregate` 答的是 `DynamicDocument`（`Record<string, any>`），从 `any` 里读出来的行没有任何人检查，这里答 `RecordData`，每个值都是 `unknown`、都要经字段的 kind 读一遍；`Pick` 还会把 `QueryApi` 的两个类型参数与各方法自带的泛型摊给每一个实现（包括测试里的桩），而这三个方法只在一种实例化下被用。

`Pick` 本来能买到的那件事——上游改了签名这边就编译不过——改由一条编译期可赋值断言买：`test/architecture.test.ts` 的「Wow protocol」里把一个 `QueryApi<RecordData>` 赋给 `ViewSource`，`tsconfig.test.json` 会类型检查这个文件，所以 Wow 的签名一漂移就断在这里，而不是断在某个试图把查询客户端交过来的宿主身上。
