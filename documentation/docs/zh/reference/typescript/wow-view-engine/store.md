---
title: '持久化端口'
description: 'ViewStore 端口、ViewStoreError 的六个代码、ViewPermissions 与 editSystem、MemoryViewStore 与 localStorageSnapshot——@ahoo-wang/wow-view-engine'
---

# 持久化端口

视图引擎只持久化两样东西：保存的视图（`ViewInstance`）与个人偏好。`ViewStore` 是后端唯一要满足的端口——在 Wow 服务上用 [`WowViewStore`](../wow-view-store/)，只有不是 Wow 的后端才自己实现它。包里自带的唯一实现是 `MemoryViewStore`，给测试、示例与只查询的场景。

相关指南：[视图存在哪里](../../../guide/typescript/view-engine-storage.md)（选哪一个 store、接上 `WowViewStore`、自己实现端口并跑一致性测试）、[视图引擎的核心概念](../../../guide/typescript/view-engine-concepts.md)（保存的视图、revision 与冲突）、[视图存储](../../../guide/extensions/view-store.md)（`WowViewStore` 背后的 Kotlin 服务端）。

## ViewStore {#api-ViewStore}

八个必需方法，外加两个可选的（`changeAudience`、`permissions`）；两条一致性规则：

| 规则 | 行为 |
|---|---|
| 乐观修订号 | 每次写入带着它期望的 `revision`；不匹配就抛 `ViewStoreError`，代码 `CONFLICT`，并带上服务端持有的实例或偏好，界面提供重新加载、覆盖或另存为 |
| 幂等的 `requestId` | 每次逻辑写入在 `WriteContext` 里有一个 `requestId`；超时后的重试复用它和同样的载荷，服务端据此去重 |
| 固定的列表顺序 | `list` 按固定顺序答摘要：后端的系统视图在前（按它声明的顺序），然后共享视图，然后用户的个人视图，每个受众内最早创建的在前。没有偏好时列表第一项就是缺省打开的视图，所以每个存储答出同一个 |
| 权限只管按钮 | `permissions` 只决定界面启用哪些按钮，是同步的，因为应用在建引擎前就取好了它。授权、可见性过滤与去重都是服务端的责任 |

| 方法 | 要点 |
|---|---|
| `create` | 为 `input` 指定的范围创建。`scope: 'system'` 创建**存储型系统视图**（「发布为系统视图」与授了 `editSystem` 时的另存为）并以 `stored: true` 回答；不保存系统视图的存储拒绝它（`FORBIDDEN` 或 `INVALID`）。发布是复制，原视图不动。引擎自己从不发 `stored`，由存储设置 |
| `rename` | 存修剪后的标题。修剪后为空、或长于 `MAX_VIEW_TITLE_LENGTH` 的标题以 `INVALID` 拒绝——`create` 也一样；`create` 或 `save` 的配置超过 `MAX_VIEW_CONFIG_BYTES` 也是。引擎发送前就拒绝两者，存储为其他调用方保留这些检查 |
| `changeAudience` | 可选。把视图就地移到另一个受众，id 不变，所以显示它的看板继续显示它。没有它的存储在管理器里就没有这一项，引擎在发送前拒绝该命令（`view.changeAudience.unsupported`）。它守其他实例写入的全部规则，外加两条：**不变就不写**——请求它已有的受众，原样回答，修订号不动（在修订号检查之后，所以过期的仍然冲突）；**共享看板显示的视图保持共享**——设为个人会让其他读者看到空面板，存储以 `INVALID` 拒绝，并在 **`boards` 里给出那些看板的标题**。删除这样的视图仍然允许 |

```ts
export interface ViewStore {
  changeAudience?(id: string, audience: ViewAudience, revision: string, context: WriteContext): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteContext): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteContext): Promise<void>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  getPreferences(definitionId: string, signal?: AbortSignal): Promise<ViewPreferences>;
  list(definitionId: string, signal?: AbortSignal): Promise<ViewInstanceSummary[]>;
  permissions?(definitionId: string): ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteContext): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteContext): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteContext): Promise<ViewPreferences>;
}

export interface WriteContext {
  requestId: string;
  signal?: AbortSignal;
}
```

自己实现这个端口时，用仓库里的端口一致性测试检查它（见[测试工具](./testing#conformance)）。

## ViewStoreError {#api-ViewStoreError}

端口唯一的失败类型。后端适配器把传输错误（HTTP 状态码在内）映射到这六个代码上，并保留映射前的东西：捕获的错误作为 `cause`，后端自己的错误码作为 `detail`。它放在 `model` 里而不是端口旁边，因为端口两侧都说它：存储抛出它，运行时按它给写入结局分类，而不依赖任何存储实现。

| 代码 | 含义 | 附带 |
|---|---|---|
| `CONFLICT` | 期望的修订号不再匹配 | `instance` 或 `preferences`：服务端持有的那份 |
| `NOT_FOUND` | 视图不存在 | |
| `FORBIDDEN` | 被拒绝——比如写一个配置声明的系统视图，或把系统视图换受众 | |
| `INVALID` | 到达了但被拒：载荷不对 | `boards`：`changeAudience` 被拒时，让视图保持共享的那些看板的标题 |
| `UNAVAILABLE` | 没到达，或到达了但结局未知。用同一个 `requestId` 重试是安全的 | `reachable`：服务端答了自己的错误（5xx、超时或它报告的拒绝），不是网络；`storage`：浏览器自己的存储不肯保存（满了或被关），写入从未离开设备 |
| `UNSUPPORTED` | 后端根本没有视图存储（早于它发布的服务端），所以那里读写不了任何视图，也没有哪个是「不见了」 | |

`detail.code` 是后端自己对失败的说法——Wow 服务端的 `errorCode`（`ViewAppRequired`、`ViewInvalid`……）——让宿主分辨端口代码合在一起的情况。只作诊断：引擎按 `code` 决定。引擎把存储失败说成 issue code，如 `view.open.failed.not_found`（见 [Issue code](./issues)）。

`isViewStoreError` 是结构判断：一个名为 `ViewStoreError`、带端口代码之一的 `Error` 就是，所以第二份本包、或自己构造这个形状的存储抛出的错误也认得出；名字把 HTTP 库里随手一个 `{ code: 'NOT_FOUND' }` 挡在外面。

```ts
export declare class ViewStoreError extends Error {
  constructor(code: ViewStoreErrorCode, message: string, held?: ConflictingState & {
    storage?: true;
    boards?: readonly string[];
    reachable?: true;
    detail?: { readonly code: string };
    cause?: unknown;
  });
  readonly boards?: readonly string[];
  readonly code: ViewStoreErrorCode;
  readonly detail?: { readonly code: string };
  readonly instance?: ViewInstance;
  readonly preferences?: ViewPreferences;
  readonly reachable?: true;
  readonly storage?: true;
}
export type ViewStoreErrorCode = 'CONFLICT' | 'NOT_FOUND' | 'FORBIDDEN' | 'INVALID' | 'UNAVAILABLE' | 'UNSUPPORTED';
export declare const VIEW_STORE_ERROR_CODES: readonly ViewStoreErrorCode[];
export declare function isViewStoreError(error: unknown): error is ViewStoreError;
```

## ViewPermissions {#api-ViewPermissions}

当前用户对一份定义的视图能做什么。它只决定按钮；真正的授权在服务端（在 Wow 上是 CoSec 网关），宿主按网关检查的同一个角色回答它。

- 除 `editSystem` 外，**不说就是允许**：`InstancePermissions.changeAudience` 缺省也是允许，换受众还要问目标受众的创建权限（共享要 `createShared`，收回个人要 `createPersonal`）。
- **`editSystem` 不说就是不允许**：系统视图到达每个用户，所以只有宿主明说时才开放。为真时，`instance(id)` 的回答也适用于存储型系统视图（保存、改名、删除），并允许新建（`create({ scope: 'system' })`、「发布为系统视图」）；系统视图从不换受众，代码或后端配置声明的系统视图仍然只读。

```ts
export interface ViewPermissions {
  createPersonal: boolean;
  createShared: boolean;
  editSystem?: boolean;
  instance(id: string): InstancePermissions;
  reorder: boolean;
  setDefault: boolean;
}

export interface InstancePermissions {
  changeAudience?: boolean;
  delete: boolean;
  rename: boolean;
  save: boolean;
}
```

<!-- typecheck-context
declare const isAdmin: boolean
declare const mayWriteShared: boolean
-->

```ts
import type { ViewPermissions } from '@ahoo-wang/wow-view-engine';

export const permissions = (): ViewPermissions => ({
  createPersonal: true,
  createShared: mayWriteShared,
  editSystem: isAdmin,
  reorder: true,
  setDefault: true,
  instance: () => ({ save: true, rename: true, delete: true, changeAudience: mayWriteShared }),
});
```

## MemoryViewStore {#api-MemoryViewStore}

一个同步的 Map 挡在端口后面。它把两条一致性规则守得诚实而不是方便：修订号过期的写入冲突，重放的 `requestId` 返回第一次的结局而不写两次，所以针对它写的代码在真实后端上表现相同——端口的列表顺序、服务端对标题与配置的上限（以 `INVALID` 拒绝）也一样。

系统视图有两种来法。以 `scope: 'system'` 播种且不带 `stored` 的是配置型的，像后端配置提供的那样：对它的每次写入都被拒（`FORBIDDEN`）。以 `scope: 'system'` 创建的、或带 `stored: true` 播种的是存储型的：像共享视图一样保存、改名、删除，从不换受众。和服务端一样，存储自己不问权限：`editSystem` 由宿主授予、由引擎去问。

有了快照，几个存储可以共享一份存储状态——同一个 `localStorage` 上的两个标签页。它们之间的规则就是后端的：**先重读，再比修订号，一次一个实例（或一份定义的偏好）**。存储在每次写入前重新加载存储状态，所以写入合并进另一个写入者留下的东西，修订号已被对方推过的写入以 `CONFLICT` 拒绝。快照保存不了的写入被撤销，并以 `UNAVAILABLE` 拒绝：它没有落地，所以用同一个 `requestId` 重试是安全的。

```ts
export declare class MemoryViewStore implements ViewStore {
  constructor(options?: MemoryViewStoreOptions);
  changeAudience(id: string, audience: ViewAudience, revision: string, context: WriteContext): Promise<ViewInstance>;
  create(input: Omit<ViewInstance, 'id' | 'revision'>, context: WriteContext): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteContext): Promise<void>;
  get(id: string): Promise<ViewInstance>;
  getPreferences(definitionId: string): Promise<ViewPreferences>;
  list(definitionId: string): Promise<ViewInstanceSummary[]>;
  readonly permissions?: (definitionId: string) => ViewPermissions;
  rename(id: string, title: string, revision: string, context: WriteContext): Promise<ViewInstance>;
  save(id: string, config: ViewConfig, revision: string, context: WriteContext): Promise<ViewInstance>;
  setPreferences(definitionId: string, preferences: ViewPreferences, context: WriteContext): Promise<ViewPreferences>;
}

export interface MemoryViewStoreOptions {
  instances?: ViewInstance[];
  permissions?: (definitionId: string) => ViewPermissions;
  preferences?: Record<string, ViewPreferences>;
  snapshot?: MemorySnapshot;
}
```

### localStorageSnapshot {#api-localStorageSnapshot}

把 `MemoryViewStore` 的快照放在 `localStorage` 的 `key` 下：整个存储一份 JSON 文档 `{ instances, preferences }`。给开发与单用户宿主，直到真正的后端接手视图；它是一个浏览器的，不是第二个存储。

- **存储拒绝的写入就是失败的写入**：配额满或存储被禁时，存储撤销这次写入并以 `UNAVAILABLE`（标 `storage`）拒绝，引擎报给 `onError`，界面说浏览器存储满了或被关了，而不是结果没回来。它从不假装视图已保存。
- **标签页之间不互相覆盖**：每次写入前重读文档并检查修订号；另一个标签页对 `key` 的 `storage` 事件会让存储重新加载，所以对方保存的不用等写入就会列出。
- 缺失、读不了或格式不对的文档读作什么都没存——存储从空开始而不是页面崩溃——下一次写入替换它。

<!-- typecheck-context
declare const ordersDefinition: import('@ahoo-wang/wow-view-engine').DataViewDefinition
declare const source: import('@ahoo-wang/wow-view-engine').ViewSource
-->

```ts
import { MemoryViewStore, ViewEngine, localStorageSnapshot } from '@ahoo-wang/wow-view-engine';

export const engine = new ViewEngine({
  resources: [{ definition: ordersDefinition, source }],
  store: new MemoryViewStore({ snapshot: localStorageSnapshot('orders-app:views') }),
});
```

```ts
export declare function localStorageSnapshot(key: string, options?: LocalStorageSnapshotOptions): MemorySnapshot;

export interface MemorySnapshot {
  load(): MemoryState | undefined;
  save(state: MemoryState): void;
  subscribe?(listener: () => void): () => void;
}
```

## 完整可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)用 `MemoryViewStore` 保存视图；补偿控制台用 [`WowViewStore`](../wow-view-store/) 把视图存在视图存储服务里。
- 源文件：[`store/ViewStore.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/store/ViewStore.ts)、[`store/MemoryViewStore.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/store/MemoryViewStore.ts)、[`store/localStorageSnapshot.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/store/localStorageSnapshot.ts)、[`model/storeError.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/model/storeError.ts)。
