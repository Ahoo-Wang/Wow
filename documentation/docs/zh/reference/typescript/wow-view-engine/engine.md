---
title: '引擎与资源'
description: 'ViewEngine、它的选项与 resources、ViewSource 与 RuntimeEnvironment——@ahoo-wang/wow-view-engine'
---

# 引擎与资源

`ViewEngine` 是宿主在应用启动时建一次、整个应用共用的对象：它登记定义（`resources`），握住保存视图的 `store`，管理打开着的运行时，并且是打开、新建、保存、改名、删除这些命令的唯一入口。之所以只有一个入口，是因为每次写入都走同一条路——默认界面和宿主自己搭的界面因此表现一致，任何不成功的结局都落进同样三种恢复动作（`retryWrite`、`abandonWrite`、`resolveConflict`）。

直接构造它，不要继承：它标了 `@sealed`，基类 `EngineResources` 只是文件的拆法，不是扩展点。

相关指南：[视图引擎入门](../../../guide/typescript/view-engine-getting-started.md)（第 6 步建出引擎与资源）、[把视图引擎接进宿主](../../../guide/typescript/view-engine-host.md)（为什么整个应用只有一个引擎）、[视图引擎的核心概念](../../../guide/typescript/view-engine-concepts.md)（引擎管理的视图与运行时）。

## 建一个引擎

每个资源把一份定义和它的数据来源配成一对；同一个来源上的所有页面共用查询、偏好与查询描述。`MemoryViewStore` 刷新就忘，开发与测试够用；在 Wow 服务上换成 [`WowViewStore`](../wow-view-store/)。

<!-- typecheck-context
declare const ordersDefinition: import('@ahoo-wang/wow-view-engine').DataViewDefinition
declare const source: import('@ahoo-wang/wow-view-engine').ViewSource
-->

```ts
import { MemoryViewStore, ViewEngine } from '@ahoo-wang/wow-view-engine';
import { browserRuntimeEnvironment } from '@ahoo-wang/wow-view-engine/react';

export const engine = new ViewEngine({
  resources: [{ definition: ordersDefinition, source }],
  store: new MemoryViewStore(),
  // 失败照常在发生处显示；这里是给日志的一份。
  environment: browserRuntimeEnvironment({
    onError: ({ kind, error }) => console.error(`[view-engine] ${kind}`, error),
  }),
});
```

## 选项 {#api-ViewEngineOptions}

| 选项 | 作用 |
|---|---|
| `resources` | 宿主登记的全部资源：每份定义，以及它的数据来源。看板的定义没有来源——它自己不查询 |
| `store` | 保存视图与偏好的端口（[持久化端口](./store)） |
| `resolveOptions` | `reference` 字段远端候选值的来源，按同一个键查 |
| `kinds` | 字段类型登记表；缺省是内置类型（[定义与字段类型](./definitions#api-FieldKind)） |
| `text` | 定义里的键（`text(key)`）在引擎起始语言下怎样说，给没有 Provider 的宿主；有 `ViewHost` 或 `MessagesProvider` 时由它们按自己的语言说。缺省时，在设定语言前键就读作键本身 |
| `limits` | 宿主在 `DEFAULT_RUNTIME_LIMITS` 上的预算，没写的保持缺省。来源预算（`maxPageSize`、`maxPageWindow`、`maxAnalysisRows`、`maxQueryFilterNodes`、`maxFilterValues`）由来源的查询描述给出，这里给的只能往下压——除非要引擎比服务端允许的少要一些，否则别写 |
| `environment` | 运行时与宿主之间唯一的接口：时钟、时区、计时器、页面是否可见、失败通知（[`RuntimeEnvironment`](#api-RuntimeEnvironment)） |
| `newId` | 生成幂等键；测试里覆盖它，让键好读 |
| `onIssue` | 没有调用方可拒绝、背后也没有抛出的发现：定义的准入、被丢掉的列表项、抛了错的变更监听。查询、存储、导出、渲染这些失败走 `environment.onError`。缺省时开发构建（`NODE_ENV` 为 `development`）把它们按资源分组写到控制台，附修正办法；其他构建丢弃 |

为什么分成 `onIssue` 和 `onError` 两路：前者是宿主写的声明有问题，修代码就好；后者是运行中遇到的失败，界面已经在原地说了，这一份是给监控的。

```ts
export interface ViewEngineOptions {
  environment?: RuntimeEnvironment;
  kinds?: FieldKindRegistry;
  limits?: Partial<RuntimeLimits>;
  newId?(): string;
  onIssue?(issue: Issue): void;
  resolveOptions?(key: string): OptionSource;
  resources: readonly ViewResource[];
  store: ViewStore;
  text?(key: string): string | undefined;
}
```

### ViewResource {#api-ViewResource}

宿主登记的一项：一份定义，以及它的数据从哪里来。数据定义的 `source` 是行的来源；看板没有。

```ts
export interface ViewResource {
  definition: ViewDefinition;
  source?: ViewSource;
}
```

## 数据来源 {#api-ViewSource}

`ViewSource` 是 `QueryApi` 的三个方法，所以 wow-client 的快照查询客户端原样就满足它；别的后端或测试可以手写一个（测试用 [`/testing` 的 `memorySource`](./testing#api-memorySource)）。

- 取消用的是 `AbortController`，而不是其他端口用的 `AbortSignal`，因为 `QueryApi` 收的就是它，这个端口要让 wow-client 的查询客户端不经改写插进来。控制器归引擎：有更新的请求取代这一个、或运行时被释放时，引擎去 abort；手写的来源只读 `abortController?.signal`，自己不 abort。
- `aggregate` 返回 `RecordData[]`，每个值都以 `unknown` 到达，经字段类型读取，而不是信任调用方断言的行类型。
- `describe` 可选：来源的能力描述。引擎在第一个视图运行前读一次，按它收窄每份定义、取它的限额，之后按自己的节奏复查；带着已有版本问时，没变就答 `notModified`。wow-client 的 `describeSnapshot` 或 `describeEventStream` 原样就合用。不提供时，视图只凭定义和缺省限额运行。

```ts
export interface ViewSource {
  aggregate(query: AggregationQuery, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<RecordData[]>;
  cursor(query: CursorQuery, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<CursorPage<RecordData>>;
  describe?(previous?: string, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<QueryDescriptorResult>;
  paged(query: FilterPagedQuery, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<PagedList<RecordData>>;
}
```

接 Wow 服务的来源，见 Storybook 接入导览的 [`wowSource.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowSource.ts)：四个方法各转给快照查询客户端和查询描述客户端。

## 运行环境 {#api-RuntimeEnvironment}

运行时与宿主之间唯一的接口。相对日期、刷新计时、「有没有人在看」以及宿主想听的失败都经过它，所以 `model` 到 `store` 各层不碰 DOM 和系统时钟，测试也不必伪造全局对象就能驱动时间。浏览器里用 `/react` 的 `browserRuntimeEnvironment(overrides?)`；无界面时用根入口的 `defaultRuntimeEnvironment(overrides?)`。

- `timeZone`：解析相对日期与预设日期用的 IANA 时区。
- `onError`：引擎干活时遇到的每个失败——查询、存储调用、导出、渲染、图表、声明的操作所发的命令——各告诉一次。界面照常在原地答复，这是给宿主的副本；它抛出的东西被丢弃，不提供时哪里都不记。

```ts
export interface RuntimeEnvironment {
  clearTimeout(handle: unknown): void;
  now(): Date;
  onError?(event: ViewErrorEvent): void;
  setTimeout(callback: () => void, ms: number): unknown;
  timeZone: string;
  visibility: VisibilitySource;
}
```

## ViewEngine {#api-ViewEngine}

下面是宿主最常用的成员；完整签名见 [API 报告](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/api/root.api.md)。默认界面已经替你调用了它们，自己搭界面或写测试时才直接用。

| 成员 | 作用 |
|---|---|
| `list(definitionId)` | 代码里声明的系统视图在前，然后是存储里的。保留命名空间里的 id 被丢弃并报告：只有定义能声明系统视图。存储失败时 `failed` 说明原因，声明的系统视图仍在 `items` 里 |
| `open(instanceId, options?)` | 打开一个保存的视图，或不碰存储地打开代码声明的视图。`options.scopeFilter` 与配置一起准入，从第一次查询起生效 |
| `create(definitionId, input)` | 一个未保存的视图，带着完整配置（`defaultRecordConfig` 等生成）立刻执行。这里不问权限：首次 `save` 时才问 |
| `save(runtime)`、`saveAs(runtime, input)` | 首次保存即创建，之后覆盖，都要求草稿没有错误。`saveAs` 的 `scope: 'system'` 是存储型系统视图，需要 `editSystem` |
| `rename`、`delete`、`changeAudience` | 改名不带配置，所以草稿有错也不挡它。`changeAudience` 需要存储实现可选的同名方法，否则被拒（`view.changeAudience.unsupported`） |
| `publishAsSystem(id)` | 「发布为系统视图」：把保存的视图（按已保存的样子，不是打开着的草稿）复制成存储型系统视图；需要 `editSystem` |
| `retryWrite`、`abandonWrite`、`resolveConflict` | 三种恢复动作：按原 `requestId` 重放；放弃结局保留草稿；`reload` 取服务端状态、`overwrite` 以冲突报告的修订号重放（新的逻辑写，所以换新 `requestId`） |
| `close(runtime)` | 关闭一个打开的视图：释放并移出登记。只调 `runtime.dispose()` 会让引擎握着一个死运行时 |
| `subscribe(listener)` | 任一写入改变了某定义的列表时通知，返回取消订阅的函数 |
| `permissions(definitionId)` | 存储给出的权限（[`ViewPermissions`](./store#api-ViewPermissions)） |
| `setText(text, language?)` | 换定义的键在哪种语言下怎样说；`ViewHost` 会替你调用 |

```ts
export declare class ViewEngine extends EngineResources {
  constructor(options: ViewEngineOptions);
  abandonWrite(target: WriteTarget): void;
  changeAudience(id: string, audience: ViewAudience): Promise<ViewInstance>;
  close(runtime: ViewRuntime): void;
  create<C extends ViewConfig>(definitionId: string, input: CreateInput<C>): RuntimeFor<C>;
  definitionIssues(definitionId: string): Issue[];
  delete(id: string): Promise<void>;
  dispose(): void;
  readonly environment: RuntimeEnvironment;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;
  list(definitionId: string): Promise<ViewListing>;
  open(instanceId: string, options?: OpenOptions): Promise<AnyViewRuntime>;
  openRuntimes(): readonly ViewRuntime[];
  pendingWrites(): ReadonlyMap<string, WriteState>;
  permissions(definitionId: string): ViewPermissions;
  preferences(definitionId: string): Promise<ViewPreferences>;
  publishAsSystem(id: string): Promise<ViewInstance>;
  rename(id: string, title: string): Promise<ViewInstance>;
  reorder(definitionId: string, order: string[]): Promise<ViewPreferences>;
  resolveConflict(target: WriteTarget, choice: ConflictChoice): Promise<ViewInstance | ViewPreferences | void>;
  retryWrite(target: WriteTarget): Promise<ViewInstance | ViewPreferences | void>;
  save(runtime: ViewRuntime): Promise<ViewInstance>;
  saveAs(runtime: ViewRuntime, input: { title: string; scope: ViewScope }): Promise<ViewInstance>;
  setAutoRun(definitionId: string, autoRun: boolean): Promise<ViewPreferences>;
  setDefault(definitionId: string, instanceId: string | null): Promise<ViewPreferences>;
  readonly store: ViewStore;
  subscribe(listener: ViewChangeListener): () => void;
}
```

### 打开与新建的输入 {#api-OpenOptions}

- `scopeFilter`：一个外层条件，用视图自己的字段名写，与配置一起准入——按客户限定的订单页不会让一个未限定的查询发出去，也不会显示范围外的行。
- `definitionId`：调用方画的是哪份定义的视图；别的定义的实例在运行前就被拒（`view.open.other-definition`）。
- `tab`、`filters`、`held`：只对看板有意义——打开在哪个页签、筛选初值、宿主固定住的筛选。

```ts
export interface OpenOptions {
  definitionId?: string;
  filters?: DashboardFilters | null;
  held?: HeldFilters | null;
  scopeFilter?: FilterTree | null;
  tab?: string | null;
}
```

```ts
export interface CreateInput<C extends ViewConfig> {
  config: C;
  scope: ViewAudience;
  scopeFilter?: FilterTree | null;
  title: string;
}
```

`CreateInput.scope` 只能是受众（`personal` 或 `shared`）：用户为受众新建，只有定义能声明系统视图。

### ViewListing {#api-ViewListing}

```ts
export interface ViewListing {
  failed: Issue | null;
  items: ViewInstanceSummary[];
}
```

## 完整可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)用同样几步接一个订单对象，页底就是它在运行。
- 源文件：[`ordersEngine.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersEngine.ts)、[`wowSource.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowSource.ts)；引擎本身在 [`src/runtime/viewEngine.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/runtime/viewEngine.ts)。
