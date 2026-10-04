---
title: '测试工具'
description: '/testing 入口：memorySource、matches、admit、resolveNavigation、actionHarness，以及 ViewStore 端口一致性测试——@ahoo-wang/wow-view-engine/testing'
---

# 测试工具

`/testing` 给宿主自己的测试用：不起浏览器、不连服务端，就能检查宿主写的声明——定义准入吗、措辞齐吗、操作的业务规则对吗、路由解析到哪里。它跑的是引擎自己的那几段逻辑，而不是另写一套近似。

它不依赖 React 或 DOM。`memorySource` 用可选的 peer `mingo` 实现 Wow 的查询语义，用到它的项目要自己装 `mingo`。

<!-- typecheck-context
declare const ordersDefinition: import('@ahoo-wang/wow-view-engine').DataViewDefinition
declare const ordersDescriptor: import('@ahoo-wang/wow-client').QueryModelDescriptor
declare const ORDERS_WORDS: Readonly<Record<string, string>>
declare const orderActions: import('@ahoo-wang/wow-view-engine').RecordActions
declare const orders: import('@ahoo-wang/wow-view-engine').RecordData[]
-->

```ts
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { text } from '@ahoo-wang/wow-view-engine';
import { actionHarness, admit } from '@ahoo-wang/wow-view-engine/testing';

test('定义被准入，每个键都有措辞', () => {
  assert.deepEqual(
    admit([ordersDefinition], { order: ordersDescriptor }, { text: key => ORDERS_WORDS[key] }),
    [],
  );
});

test('只有已付款的订单能发货，并说明原因', () => {
  const rows = orders.map(data => ({ key: String(data.aggregateId), data }));
  const harness = actionHarness(orderActions, rows);
  assert.equal(harness.state('ship', 'SO-0001').reason, null);
  assert.equal(harness.state('ship', 'SO-0003').reason, text('orders.notPaid'));
  // 单条直接发；选了多条先问。
  assert.equal(harness.asks('ship', 'row').asks, false);
});
```

相关指南：[把视图引擎接进宿主](../../../guide/typescript/view-engine-host.md#testing)（用 `/testing` 测宿主的接线）、[声明式操作](../../../guide/typescript/view-engine-actions.md#harness)（用 `actionHarness` 测操作的规则）、[写好一份定义](../../../guide/typescript/view-engine-definitions.md)（用 `admit` 自检一份定义）、[视图存在哪里](../../../guide/typescript/view-engine-storage.md)（对自己的 store 跑一致性测试）。

## admit {#api-admit}

引擎对 `definitions` 在 `descriptors`（快照，按 `DataViewDefinition.source` 索引）之上会有的每条发现，各带它所属的定义；宿主的声明都成立时答 `[]`。

每份定义都像在引擎里登记时那样被准入——它的键用 `text` 说出、它自己的规则、它的看板对照其他每份定义，判的都是声明时的原样（键仍是键）——每份数据定义都像来源会做的那样被收窄到它的描述，收窄拿掉的东西也说出来。没给描述的来源上的定义也会被指出，因为它的能力没被检查。它接受资源列表原样传入（`{ definition, source }`）。传入的资源里至少有一份带着 `source` 时，作为资源传入的数据定义还要过引擎启动时的那道检查：传入的某份资源在它的 `source` 键下登记了数据源，否则报 `definition.source.unregistered`。只有 `{ definition }` 或只传定义的列表没说数据从哪来，不查这一条。

```ts
export declare function admit(definitions: readonly Admissible[], descriptors: Readonly<Record<string, QueryModelDescriptor>>, options?: AdmitOptions): AdmitFinding[];

export interface AdmitOptions {
  kinds?: FieldKindRegistry;
  limits?: Partial<RuntimeLimits>;
  text?(key: string): string | undefined;
}

export type Admissible = ViewDefinition | { definition: ViewDefinition; source?: ViewSource };

export type AdmitFinding = Issue & { definition: string };
```

## actionHarness {#api-actionHarness}

宿主声明的操作在一些记录之上，不要屏幕：引擎自己对它们的读法——哪个位置提供哪个、一条记录现在能不能接受、为什么不能、一组选择怎样分开、确认与表单问什么、规则什么时候自己变——让宿主的单元测试一行钉住一条业务规则。

| 成员 | 作用 |
|---|---|
| `ids`、`at(place)` | 每个操作的 id（按声明顺序）；某个位置提供的 id |
| `state(id, key, input?)` | 一条记录的状态：`available`、`hidden`、`reason`（宿主写的原因，键仍是键；可用时为 `null`） |
| `bulk(id, keys?, input?)` | 一组选择怎样分开：能接受的、被拒的、按原因归并（最常见的在前），即对话框列出的 |
| `asks(id, place, input?)` | 在该位置按下是否先问、问什么：声明的问题，或引擎用自己的话问时为 `null`（没有 `confirm` 的选择） |
| `form(id)`、`choice(id)`、`missing(id, input)` | 表单字段；是否是一个选择；`input` 留空的必填字段（表单的 `initial` 垫在 `input` 下） |
| `changesAt(key?)` | `now` 之后某条记录的可用性最早自己翻转的时刻 |
| `run(id, key, input?)` | 像引擎那样给一条记录发命令：不接受时以原因拒绝（`ActionRefusedError`），否则就是宿主自己的 `run` |

`options.now` 是规则被问的时刻，缺省 `Date.now()`。

```ts
export declare function actionHarness(list: RecordActions, rows: readonly ActionRow[], input?: ActionHarnessOptions): ActionHarness;

export interface ActionHarnessOptions {
  now?: number;
}

export interface ActionHarness {
  asks(id: string, place: ActionPlace, input?: ActionInput): { asks: boolean; confirm: ActionConfirm | null };
  at(place: ActionPlace): string[];
  bulk(id: string, keys?: readonly RecordKey[], input?: ActionInput): HarnessBulk;
  changesAt(key?: RecordKey): number | null;
  choice(id: string): readonly FieldOption[] | null;
  form(id: string): HarnessField[] | null;
  readonly ids: readonly string[];
  missing(id: string, input: ActionInput): string[];
  run(id: string, key: RecordKey, input?: ActionInput): Promise<unknown>;
  state(id: string, key: RecordKey, input?: ActionInput): HarnessState;
}

export interface HarnessState {
  available: boolean;
  hidden: boolean;
  reason: string | null;
}

export interface HarnessBulk {
  able: RecordKey[];
  reasons: { reason: string; count: number; keys: RecordKey[] }[];
  refused: ActionRefusal[];
}

export interface HarnessField {
  input: 'select' | 'text' | 'number' | 'boolean';
  label: string;
  name: string;
  options: readonly FieldOption[] | null;
  required: boolean;
}

export declare class ActionRefusedError extends Error {
  constructor(reason: string);
  readonly reason: string;
}
```

## memorySource {#api-memorySource}

一个放在内存里的 `ViewSource`，像 MongoDB 上的 Wow 服务那样回答每个查询——筛选、排序、分页、投影与聚合——所以宿主的测试看到的是引擎真实的查询被它所依据的语义回答，而不是预设的答案。

- 文档是服务端返回的快照（或事件流）：信封上是 `aggregateId`、`ownerId` 与 `deleted`，状态在 `state` 下，时间是 epoch 毫秒。`deleted: true` 的文档被略过，除非筛选带 `DELETION` 条件。游标是偏移量，和 Wow 的一样对调用方不透明。
- 它答不了的——没有读法的运算符或形状，比如 `ID`、`TENANT_ID`、`SPACE_ID` 或引擎从不发的日历筛选（`TODAY` 等）——以错误拒绝，所以测试开始发的查询会失败，而不是拿到一个似是而非的错答案。文档只读不写，测试可以在两次查询之间改它们。

| 选项 | 作用 |
|---|---|
| `now` | 服务端的时钟（epoch 毫秒），`BEFORE_NOW` 与 `AFTER_NOW` 拿它比较；测试把它钉在数据围绕的那一刻，让「晚于现在」每次运行都答一样 |
| `timeField` | 每份文档都以 epoch 毫秒持有的时间列，像 Wow 快照的 `firstEventTime`。文档按它排序，筛选顶层 AND 里对它的范围先用二分查找截出来——给大数据集 |
| `remember` | 按查询记住重复的聚合答案，每个调用方拿自己的副本。只用于从不变化的文档；缺省关，所以改了文档的测试在下一次回答里就看到改动 |

`matches(document, filter, options?)` 问一份文档是否匹配一个筛选，读法与 `memorySource` 相同——测试拿自己的条件（比如旧页面用的那个）和视图匹配的结果比较。与来源不同，它对删除不作假设：没有 `DELETION` 条件时，已删除的文档也是候选。

```ts
export declare function memorySource(documents: readonly RecordData[], options?: MemorySourceOptions): ViewSource;

export interface MemorySourceOptions {
  now?: () => number;
  remember?: boolean;
  timeField?: string;
}

export declare function matches(document: RecordData, filter: FilterExpression, options?: { now?: () => number }): boolean;
```

## resolveNavigation {#api-resolveNavigation}

`to` 经它通往的定义的路由解析（`bind` 的 `route`，由 `bindingOf` 查找）：宿主的 `navigate` 拿到的就是它。纯函数，也在 `/testing` 里，让宿主对自己绑定的测试跑引擎自己的解析。

```ts
export declare function resolveNavigation(to: ViewNavigation, bindingOf: (definitionId: string) => { route?: ViewRouteOf } | undefined): ViewDestination;
```

## ViewStore 端口一致性测试 {#conformance}

自己实现 [`ViewStore`](./store#api-ViewStore) 的后端，用端口一致性测试检查它：每个实现都要通过的同一组用例——列表与读取、修订号冲突、`requestId` 重放、受众变更、系统视图——`MemoryViewStore` 在引擎里跑它，`WowViewStore` 在 `typescript/integration-test` 里对着视图存储服务跑它。

它是**仓库里的测试文件，不随包发布**：放进 `/testing` 会把测试框架带进一个发布的入口。所以它按工作区路径导入，只依赖 `vitest`，以及引擎源码里不带运行时导入的模块；实现在仓库外的后端，把这个文件复制过去用，或参照它写自己的用例。它对后端的假设只有这些：每个用例在一个新的定义 id 里工作，所以共享服务端不用重置；修订号只比较变没变，从不排序或解析——只有未动过的偏好答 `'0'`。主体没声明的能力，相应用例按名字跳过。

<!-- typecheck: skip — 按路径导入仓库里的测试文件，它不随包发布 -->

```ts
import { describeViewStoreConformance } from '../../wow-view-engine/test/conformance/viewStoreConformance.js';

describeViewStoreConformance({
  name: 'WowViewStore',
  capabilities: {
    owners: true,
    personalViews: true,
    changeAudience: true,
    idempotentCreate: true,
    systemViews: { definitionId: 'conformance-system' },
  },
  // 整个文件一个服务端就够：每个用例都用新的 id。
  connect: () => ({ owner }) => new WowViewStore({ fetcher: actingAs(owner) }),
});
```

| 能力 | 含义 |
|---|---|
| `owners` | 为两个 owner 打开的存储是两个用户：各自看到对方的共享视图、看不到对方的个人视图，偏好各自独立。单用户存储为 `false`，关于两个用户的用例跳过 |
| `personalViews` | 能否创建个人视图（没人登录的宿主没有） |
| `changeAudience` | 存储实现了可选的 `changeAudience` |
| `idempotentCreate` | 以同一个 `requestId` 重放的 `create` 答第一次的结局而不是再建一个。Wow 服务端不对 `create` 去重（由它生成 id），`WowViewStore` 在同一个存储内靠先问重放路由来守住它；连这都守不住的存储声明 `false`，该用例跳过，其他写入的重放仍是必需 |
| `systemViews` | 后端为之提供至少一个只读系统视图的定义；没有就省略，「写系统视图被拒」的用例跳过 |
| `storedSystemViews` | 存储保存自己的系统视图：`create` 带 `scope: 'system'` 创建一个，以 `stored: true` 回答和列出 |

## 完整可运行版本

- Storybook 接入导览的 [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts) 用 `admit` 和 `actionHarness` 钉住导览里的声明。
- 一致性测试：[`test/conformance/viewStoreConformance.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/conformance/viewStoreConformance.ts)，以及 `WowViewStore` 的 [`wowViewStore.conformance.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/integration-test/test/view-store/wowViewStore.conformance.test.ts)。
- 源文件：[`src/testing/`](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/src/testing)。
