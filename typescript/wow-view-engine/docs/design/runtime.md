# 运行时

一个打开的视图对应一个 `ViewRuntime`，它是带 `subscribe / getSnapshot` 的小 store。

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
  setScopeFilter(tree: FilterTree | null): Issue[]; // 外层注入的附加条件，AND 到已应用筛选；不改 draft／saved
  readonly scopeFilter: FilterTree | null; // 当前生效的注入条件（最后一次被准入的那棵）；筛选摘要据此把"宿主的条件"与"视图自己的条件"分开呈现
  dispose(): void; // 最后一次通知订阅者后清空监听
}

/** 分页与选择只属于 Record；Engine 按 config.kind 返回对应的窄接口。 */
export interface RecordViewRuntime<
  P extends 'paged' | 'cursor' = 'paged' | 'cursor',
> extends ViewRuntime<RecordViewConfig> {
  page(target: RecordPageTarget<P>): void;
  select(keys: RecordKey[]): void;
}

/** 打开一个实例得到的判别联合；按 runtime.kind 收窄。 */
export type AnyViewRuntime =
  RecordViewRuntime | ViewRuntime<AnalysisViewConfig> | DashboardRuntime;

/** Dashboard 的公开面：快照多出 panels 与 resolving，并能等待引用加载、按面板取子 runtime。 */
export interface DashboardRuntime extends ViewRuntime<DashboardViewConfig> {
  getSnapshot(): DashboardRuntimeState; // ViewRuntimeState + panels: DashboardPanelState[] + resolving
  ready(): Promise<void>; // 每个面板引用都已加载或确认不可读
  panelRuntime(panelId: string): DataViewRuntime | null; // 宿主自行驱动某个面板时使用
}

/** 由配置类型推出的 runtime 类型，create 用它保留静态收窄。 */
export type RuntimeFor<C extends ViewConfig> = C extends RecordViewConfig
  ? RecordViewRuntime
  : ViewRuntime<C>;

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
- 新的 `apply / refresh / page` 替代同一 runtime 的在途请求，旧响应到达后丢弃。这由 `RequestRunner` 用 per-runtime key 实现，全局并发上限与队列来自 `RuntimeLimits`（`maxConcurrentQueries`、`maxQueuedQueries`、`maxPageSize`、`maxAnalysisRows`、`minRefreshInterval`、`maxRefreshInterval`、`maxFilterDepth`、`maxFilterNodes`、`maxDashboardPanels`）。
- 状态变更同步提交后再通知订阅者；相同状态返回相同对象，子对象引用稳定，以配合 `useSyncExternalStore`。`dispose` 是最后一次通知：订阅者据此读到 `disposed`，`useOpenView` 才能在实例被别处删除时自行重开，而不必等一次碰巧的渲染。
- runtime 不做持久化。保存是 Engine 的命令，成功后 Engine 调用 `runtime.markSaved(instance)` 推进基线。
- **`edit` 里值为 `undefined` 的成员是"删掉"，不是"置为 undefined"。** 配置是 JSON：没有这个成员与成员为 `undefined` 是同一份配置，却不是同一个对象，而 `dirty` 是与已保存配置的一次 `dequal`。把最后一条可选列表项删掉的编辑器因此会让视图就此一直"未保存"、离开守卫还会问一句用户早已撤销过的改动。`defaultRecordConfig` 同理：没有汇总时根本不写 `summaries` 这个键，而不是写一个 `undefined`。

- **注入的作用域条件同样要准入。** `setScopeFilter` 按本 runtime 的定义与 kinds 校验合并后的有效筛选（含深度与节点预算），返回 Issue 列表；含 error 时不改变已应用口径也不执行，因此自定义宿主与 Dashboard 走同一条准入路径。作用域条件从打开起就与配置一起准入：`issues` 始终是"draft AND 作用域"这份有效配置的校验结果，构造、`edit`、`adoptSaved` 与 `setScopeFilter` 都按这一条规则重算；`mergeFilters` 把作用域作为一个嵌套分组追加在 draft 自身条件之后（不拍平：分组内字段唯一，而宿主对同一字段再收窄是第二个问题而不是重复），因此指向 draft 树的 Issue 路径不因作用域而移位；作用域自身的子树多占一层深度，单条条件在线上仍编译为它本身。合并只丢弃结构合法且没有叶子的空树：含畸形条目的树不算空（`isEmptyFilter` 为 false），畸形条目保留在合并结果里由准入报出；根不是分组的 filter 不参与合并，原样交给准入报 `config.filter.invalid`。Dashboard 的 `fields` 访问器只交出结构合法的字段项，畸形项留给准入。
- **只执行准入过的配置。** `apply` 与 `setScopeFilter` 只提升通过准入的口径；打开时 `applied` 若未通过准入，`refresh` 与 `page` 同样是空操作，直到一份修正后的 draft 被 `apply`。否则"待修复"只挡住 `apply` 一个入口，刷新或翻页就会把被拒绝的配置发出去。
- **结果自身的问题记在结果上，不记在 `issues` 里。** `ProjectedView` 带一份 `issues: readonly Issue[]`，说的是"屏幕上这些数字"而不是"这份配置"。`state.issues` 是 draft 连同作用域的准入结果，每次 `edit` 都重算：把这类发现放进去，用户一敲键盘它就没了，而它描述的那些数字还在屏幕上。它随成功的执行一起推进，随下一次成功的结果一起被换掉，读它的是 `resultIssues(data)` 这一个函数——两个工作台把它并进 `WorkbenchShell` 的 `warnings`，`EmbeddedView` 并进它自己的 warning 条（[ui/README.md#两级-severity-与-statusstrip](ui/README.md#两级-severity-与-statusstrip)）。当下有两条，都是 warning，都不阻塞：
  - **`runtime.summary.page-only`**（路径 `['summaries']`）——汇总查询失败，汇总行退回本页口径。行本身留着，因为本页合计本身有用；`SummaryRow.scope` 说明它答的是 `page` 还是 `total`，这条 Issue 说明为什么退。配置没要汇总时两者都没有，汇总查询成功时是 `scope: 'total'` 且没有 Issue。默默顶替才是这里唯一的错误：读者看到「总计」，会当成全部命中记录的总计，二十行的 AVG 被读成四万行的 AVG。
  - **`analysis.result.at-limit`**（路径 `['limit']`，参数 `{ limit }`）——分析结果正好填满 `limit` 行。聚合只回答了行数，没说它省略了多少，"恰好等于上限"既可能是刚好这么多组，也可能是被截断的前缀，所以措辞是"**可能**被截断"而不是断言（判据见 [kernels.md#compileanalysis-与-projectanalysis](kernels.md#compileanalysis-与-projectanalysis)）。分析的合计行走自己的无分组查询，因此即使分组被截断它仍覆盖全部——两行加起来小于它们下面的合计，两个数都没错。
  - 分析的**合计**查询单独失败不报：分组行仍然完整地回答了它们自己的问题，屏幕上没有哪个数字的含义与它的说法不符，少一行合计而已。（见 test/resultIssues.test.tsx）

## 自动刷新

`applied.refresh.interval` 非空时由该 runtime 持有唯一计时器，到期调用 `refresh()`。四种情况暂停：

- `issues` 含 error；
- `editing` 为 true——`useFilterEditor` 与 `useAnalysisEditor` 提供 `focus`／`blur`，默认 `FilterPanel` 与 `AnalysisEditor` 在焦点进入或离开其根元素时调用，内部焦点移动不触发；控件的弹层经 Portal 渲染在根元素之外，焦点进入弹层时根元素内仍有带 `data-popup-open` 的触发器，算作未离开；查询进行中不冻结编辑器；
- 宿主报告页面不可见；
- 上一次请求仍在途。

间隔的入口是刷新按钮的 `▾`（`RefreshControl`，三种视图各有一处，见 [ui/README.md#刷新是一个拆分按钮](ui/README.md#刷新是一个拆分按钮)）：选中即 `edit({ refresh: { interval } })` 加 `apply`，因为这里读的是 `applied`。界面不添第四条之外的暂停理由。计时与可见性都来自注入的 `RuntimeEnvironment`（见下方[环境](#环境)），runtime 不触碰 DOM。计时器随 `dispose` 释放。多个 React 组件观察同一 runtime 不会产生多个计时器。（见 test/runtime.test.ts「DataViewRuntime auto refresh」）

## Dashboard

`DashboardRuntime` 持有 N 个子 `ViewRuntime` 加一个全局筛选草稿。`apply()` 校验全局筛选，为每个面板计算 `mergeGlobalFilter` 后经 `setScopeFilter` 注入再触发子 runtime 执行；宿主注入给 Dashboard 的作用域条件与数据视图同样从打开起就并入校验。

- `dashboard.panels.too-many` 这类路径为 `['panels']`、不属于任何一个面板的 Issue 按 Dashboard 整体的 error 处理，阻止全部面板执行。
- 子 runtime 拒绝注入的作用域时，该子 runtime 被释放而不是继续跑旧口径，拒绝理由以 `['panels', index, ...]` 为路径记在该面板的 `issues` 里，其余面板不受影响。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime child refusal」）
- 子 runtime 接受作用域后，它对自身已存配置的 warning 同样重定址到该路径记入面板的 `issues`，面板照常运行——从子 runtime 的快照读取，因为 `setScopeFilter` 对未变化的作用域返回空，而布局编辑会以同一作用域重新同步每个面板。
- `validateDashboard` 对映射后筛选的复验与子 runtime 对同一棵合并树的准入会让一个 kind 的 warning 出现两次，同 code 同 params 的只记面板校验的那一条。
- 宿主经 `panelRuntime` 驱动子 runtime（`edit`／`apply`）改变其 issues 时，面板的 `issues` 随子 runtime 的通知重建，不等下一次 Dashboard 同步。
- 作用域条件不进入子 runtime 的 `draft` 或 `saved`，面板因此不会变脏，也不会把 Dashboard 条件保存回被引用实例，执行的有效配置记录在 `result.config`。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime scope filter」）
- 每个面板独立 loading / error / result，Dashboard 不汇总成单一状态。
- 自动刷新由 DashboardRuntime 按自身 `refresh.interval` 统一计时并触发全部数据面板的 `refresh()`；被引用实例自身的 `refresh` 配置在 Dashboard 内忽略，避免两层计时器。（见 test/dashboardRuntime.test.ts「DashboardViewRuntime refreshing」）
- 布局编辑是普通 `edit({ panels })`。

## ViewEngine

`ViewEngine` 是注册表与命令入口（代码里是一个类，下面以接口形式列出其公开面），命令语义见 [management.md](management.md)：

```ts
export interface ViewEngine {
  readonly store: ViewStore;
  readonly environment: RuntimeEnvironment; // 时钟、计时器、可见性；由创建方注入
  definitions: ReadonlyMap<string, ViewDefinition>;
  resolveSource(key: string): ViewSource; // Pick<QueryApi, 'paged' | 'cursor' | 'aggregate'>
  resolveOptions(key: string): OptionSource; // FieldDefinition.remote 的候选来源

  open(
    instanceId: string,
    options?: { scopeFilter?: FilterTree | null },
  ): Promise<AnyViewRuntime>; // store.get → validate → runtime；按 runtime.kind 收窄；scopeFilter 从首次查询起生效并与配置一起准入
  create<C extends ViewConfig>(
    definitionId: string,
    input: { title: string; scope: 'personal' | 'shared'; config: C },
  ): RuntimeFor<C>; // 未保存的新视图；config 必填，由 default*Config / emptyDashboardConfig 生成
  save(runtime: ViewRuntime): Promise<ViewInstance>; // saved ? store.save : store.create
  saveAs(runtime, input: { title; scope }): Promise<ViewInstance>;
  rename(id: string, title: string): Promise<ViewInstance>;
  delete(id: string): Promise<void>;
  reorder(definitionId: string, order: string[]): Promise<ViewPreferences>;
  setDefault(
    definitionId: string,
    instanceId: string | null,
  ): Promise<ViewPreferences>;
  resolveDefault(summaries, preferences, explicit?): string | null; // management.md「列表、偏好与默认视图」的解析规则
  retryWrite(target: ViewRuntime | WriteHandle): Promise<ViewInstance | void>; // 复用原 requestId 与原正文重放；创建意图返回新实例
  abandonWrite(target: ViewRuntime | WriteHandle): void; // 清除写入状态，草稿保留
  resolveConflict(
    target: ViewRuntime | WriteHandle,
    choice: 'reload' | 'overwrite',
  ): Promise<ViewInstance | void>;
  pendingWrites(): ReadonlyMap<string, WriteState>; // 未结清的写入，按 WriteHandle 索引；含列表命令
  list(definitionId: string): Promise<ViewInstanceSummary[]>; // 代码声明的系统视图 + store.list()；摘要的 kind 由各自的 config 投影而来
  preferences(definitionId: string): Promise<ViewPreferences>;
  permissions(definitionId: string): ViewPermissions; // store 同步提供，缺省全允许
  definitionIssues(definitionId: string): Issue[]; // 构造时对定义准入的结果
  openRuntimes(): readonly ViewRuntime[]; // 已打开的 runtime，供工作台管理页签
  close(runtime: ViewRuntime): void; // dispose 并从注册表移除；只调 runtime.dispose() 会留下一个死条目
  dispose(): void; // 关闭全部 runtime 并取消在途请求
}
```

写入账本单独住在 `runtime/writeLedger.ts` 的 `WriteLedger` 里，`ViewEngine` 构造一个并转发：引擎这一侧只剩注册表与命令准入——定义是否可用、许可、标题、草稿有没有 error——准入过了就把一份 `WritePayload` 交给账本，公开面上的 `pendingWrites` / `retryWrite` / `abandonWrite` / `resolveConflict` 都只是转发，语义不变。账本掌管 `requestId` 的生成、按 `requestId` 索引的未结清结局、同一目标同时只放行一个在途写入，以及「上一个 unknown 没处理完之前不放行新意图」这条拦截；重试沿用原 `requestId` 与原正文重放，覆盖写则带上冲突报回的 revision 以新 `requestId` 重发，`reload` 只对 `save` 换掉草稿。账本够不到的东西——摘要缓存、偏好缓存、同一实例的其他已打开 runtime——由引擎通过 `WriteLedgerHost` 的几个回调借给它；确认之后落到 runtime 上的 `markSaved` / `moveBaseline` / `adoptSaved` / `setWrite` 仍由账本驱动。（见 test/writeLedger.test.ts）

`open` 与 `create` 对 Record 定义返回 `RecordViewRuntime`，其 `page` 只接受该定义声明的分页模式对应的目标；Analysis 与 Dashboard runtime 没有 `page`／`select`。`apply()` 与 `open()` 的首次查询按 `RecordCapability.paging` 选择目标：`paged` 用 `{ index: 1 }` 调 `source.paged`（Wow `Pagination.index` 从 1 开始），`cursor` 用 `{ cursor: null }` 调 `source.cursor`。`refresh()` 同样回到第一页。

`create`、`save`、`saveAs` 与 `open` 都先核对 `config.kind` 与所属定义的能力：`kind: 'data'` 只接受能力已声明的 `record`／`analysis`，`kind: 'dashboard'` 只接受 `dashboard`，不匹配即 error，不进入执行或保存。这与系统视图的定义期检查是同一条规则。

`open` 时的定义校验：`validate*(definition, instance.config)` 产生 `error` 级 Issue 则 runtime 进入"待修复"，`apply` 被拒绝直到用户修正；`warning` 不阻塞。这是定义演进的全部处理。

## 环境

```ts
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
