---
title: '宿主接线'
description: 'ViewHost、bind、路由端口、声明的操作 actions() 与措辞 MessagesProvider——@ahoo-wang/wow-view-engine'
---

# 宿主接线

宿主在页面外面写一次 `ViewHost`，把几个端口填上：数据（`engine`）、路由（`router`）、主题、语言、每个资源在这个宿主里的行为（`bindings`）。其下的每个界面——工作台、嵌入、看板——只取自己所在处不同的东西：`<DataWorkbench definitionId="orders" />`。路由、国际化与主题仍然归宿主，端口只是通向它们的桥。

## ViewHost {#api-ViewHost}

`ViewHost` 可以嵌套，内层盖在外层之上——一个页面自己的绑定、第二个引擎——而界面自己的 `engine`、`messages`、`locale`、`onNavigate` 或 `record` 仍然优先。只有最外层的宿主去画 `<html>`：明暗模式、预设、品牌色。

<!-- typecheck-context
declare const orderActions: import('@ahoo-wang/wow-view-engine').RecordActions
declare const ORDERS_WORDS: Readonly<Record<string, string>>
-->

```tsx
import { useMemo, type ReactNode } from 'react';
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { bind, ViewHost, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import { useReactRouter } from '@ahoo-wang/wow-view-engine/react-router';
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes.css';

const MESSAGES = { ...zhCN, ...ORDERS_WORDS };

// 放在 React Router 的 RouterProvider 里面。
export function AppHost({ engine, children }: { engine: ViewEngine; children: ReactNode }) {
  const router = useReactRouter();
  const bindings = useMemo(
    () => [
      bind('orders', {
        // 去订单的每条路——链接、看板的追问、导航——都走这条路由。
        route: view => (view === null ? '/orders' : `/orders?${new URLSearchParams({ view })}`),
        actions: orderActions,
      }),
    ],
    [],
  );
  return (
    <ViewHost
      engine={engine}
      router={router}
      locale="zh-CN"
      messages={MESSAGES}
      bindings={bindings}
      preset="porcelain"
      rememberColorMode="orders-app.color-mode"
    >
      {children}
    </ViewHost>
  );
}
```

| 属性 | 作用 |
|---|---|
| `engine` | 应用的引擎，建一次。没有它的内层宿主用外层的 |
| `bindings` | 每个资源在这个宿主里的行为（`bind`）——路由、记录的读法、记录上的命令；内层宿主按 id 覆盖 |
| `router` | 路由端口：`/react-router` 的 `useReactRouter()`，或在别的路由上写两个成员。有了它引擎管地址：离开看板或视图的每条路都走目标资源的路由；工作台打开地址里的 `?view=` 并写回；绑定资源的记录详情跟随 `?id=`；交接的视图、看板的筛选与页签作为历史条目的 state 旅行 |
| `navigate` | 每条离开的路交给宿主自己处理，而不是交给路由器：经目标资源的路由解析后交出（`ViewRoute`）；URL 与没有路由的资源原样交出 |
| `locale`、`messages` | 语言端口：值显示用的语言，以及合并在当前生效措辞之上的措辞——引擎自己的和定义的键（`text(key)`）一样。换了它，每个打开的视图重画，不重开、不重查、不变脏 |
| `theme`、`preset`、`brand` | 主题的两条路：`theme="host"` 跟随宿主的 shadcn 主题（宿主导入 `shadcn-bridge.css`）；或 `preset` 命名一套预设（导入它的样式表），宿主自己的界面经 `fve-tokens` 穿上它。`brand` 是盖在预设上的品牌色 |
| `colorMode`、`rememberColorMode` | 明暗：缺省 `system`，引擎在 `<html>` 上画 `.dark` 与 `color-scheme` 并实时跟随系统；`light`／`dark` 起始固定；`host` 表示宿主自己画（如 next-themes），引擎只跟随 `.dark`。读者用 `useColorMode` 另选；`rememberColorMode` 是在本机记住这个选择的 `localStorage` 键 |

主题的细节见[视图引擎主题](../../../guide/typescript/view-engine-theming.md)。

```ts
export interface ViewHostProps {
  bindings?: readonly ViewBinding[];
  brand?: string;
  children: import('react').ReactNode;
  colorMode?: HostColorMode;
  engine?: ViewEngine;
  locale?: string;
  messages?: ViewMessages;
  navigate?(to: ViewDestination): void;
  preset?: ViewPreset;
  rememberColorMode?: string;
  router?: ViewRouter;
  theme?: 'host';
}
export declare function ViewHost(input: ViewHostProps): import('react').JSX.Element;
```

## bind {#api-bind}

把一份定义在宿主里的行为绑上：`bind(definitionId, options)`。界面自己的同名属性优先。

| 选项 | 作用 |
|---|---|
| `route` | 这份定义的视图或看板在宿主地址里的位置：打开 `instanceId` 的页面路径——`null` 表示没人保存过的视图（分组上的追问、看板自己的分析），它在页面的缺省视图上打开并整体交接。引擎把路径连同页面打开所需的东西（`ViewRoute.state`）交给路由器或 `navigate`；没有路由的定义原样交出。`useViewNavigation` 问链接时不带 `target` |
| `actions` | 它的记录能接受的命令，用 [`actions()`](#api-actions) 声明：引擎把它们放到记录出现的每个地方——工作台的行、选择、详情，以及建在它上面的看板记录面板——先问、几条几条地执行、再报告结果 |
| `slots` | 宿主自己的标记，画在声明的操作之后——视图上方一个、选择上一个、每行一个：声明说不了的东西的出口，比如一个外链 |
| `reading` | 一条记录怎样读：详情自己的读法、标题、分节、谁握着打开哪条记录——即 `DataWorkbench` 的 `record.detail`，也是 `EmbeddedView` 的 `detail` 打开时用的 |

```ts
export declare function bind(definitionId: string, options?: ViewBindingOptions): ViewBinding;

export interface ViewBindingOptions {
  actions?: RecordActions;
  reading?: RecordDetailOptions;
  route?(instanceId: string | null, target?: RoutedTarget): string;
  slots?: RecordActionSlots;
}

export interface ViewBinding extends ViewBindingOptions {
  readonly definitionId: string;
}
```

`useViewNavigation()` 列出宿主导航里的位置：每个绑了 `route` 的已登记资源，按登记顺序，带它的系统视图，标题按当前措辞说出；`current` 读路由器的地址，没有路由器时没有当前项。

## 路由端口 {#api-ViewRouter}

`ViewRouter` 是宿主的路由器在引擎眼里的样子，不管用的是哪个库。它的合同在对象的身份上：**`location` 变了就是一个新的 `ViewRouter` 对象，没变就是同一个。** `ViewHost` 经 context 把它往下传，读地址的一切——打开的视图、打开的记录、看板的筛选与页签——在且仅在对象换新时重读。原地修改的路由器永远看不到移动；每次渲染都新建的会每次都重读地址。用 `useMemo` 按 location 的各部分建它，`useReactRouter` 就是这样做的。`go` 在调用那一刻读取，所以每次都可以是新函数。

`useReactRouter()`（`/react-router` 入口）把 React Router 交给 `ViewHost` 的 `router`，要放在它的 `RouterProvider`（或任何路由组件）里面：路径相对于它的 `basename`，和它自己的链接一样。React Router 是可选的 peer，只有这个入口加载它。

```ts
export interface ViewRouter {
  go(path: string, options?: { state?: unknown; replace?: boolean }): void;
  readonly location: ViewLocation;
}
export declare function useReactRouter(): ViewRouter;
```

## 声明的操作 {#api-actions}

操作是代码，从不被保存：宿主说做什么、什么时候能做、用什么话说；引擎负责摆放——行里、选择上、记录详情里——先问、几条几条地执行、报告结果。`actions()` 检查列表：每个操作有唯一的 id、标签和 `run`。这里写错是宿主的代码错了，所以立刻抛出。

<!-- typecheck-context
declare const commands: { ship(orderId: string): Promise<void> }
-->

```ts
import { actions, text, type RecordRow } from '@ahoo-wang/wow-view-engine';

const paid = (row: RecordRow) =>
  (row.data.state as { status?: string } | undefined)?.status === 'PAID';

export const orderActions = actions([
  {
    id: 'ship',
    label: text('orders.ship'),
    // 行里的按钮；其余的进行末「⋯」菜单。
    primary: true,
    // true，或者为什么不能：原因显示在禁用的按钮上，选择按它分成能与不能两组。
    available: row => (paid(row) ? true : text('orders.notPaid')),
    // 单条直接发；选了多条先数清楚再问。
    confirm: { title: text('orders.shipTitle'), ask: 'bulk' },
    run: row => commands.ship(String(row.key)),
  },
]);
```

`available` 读的字段要在行里：一页记录只取视图显示的字段，所以判断读的字段不在列里时，定义要写 `record.rowFields`（见[行里取回哪些字段](./definitions#api-RecordCapability)），否则操作一直禁用。

| 成员 | 作用 |
|---|---|
| `id`、`label` | 在同一个绑定的操作里唯一的 id；标签可以是 `text(key)` |
| `run(row, input)` | 给一条记录发命令。要在读模型反映它之后才 resolve——Wow 命令等 `CommandStage.SNAPSHOT`（或宿主投影需要的阶段）——因为引擎紧接着会重读视图。命令被拒时抛出，抛出的东西按来源自己的原因读。命令是写入，所以要幂等：超时或断网后结局无人知道，读者被告知去核对，可能再按一次 |
| `available(row, context)` | 记录现在能接受命令时为 `true`，否则说明原因。已知输入时带着输入问，所以一个选择可以拒绝某个选项、提供其余的 |
| `hidden(row, context)` | 这条记录根本不显示这个操作——读者无权做——区别于 `available` 显示为禁用并说明原因 |
| `changesAt(row, context)` | `available` 下一次自己变化的时刻（毫秒）；引擎到时重问，宿主不用自己的计时器 |
| `confirm` | 先问；可以是输入的函数。措辞里 `{count}` 是记录数（有 `-one` 形式给区分单数的语言），`{value}` 是选择的选项，`{record}` 是单条时的记录键。`ask: 'always'`（缺省）单条也问；`'bulk'` 只有选择才问。选择总是问 |
| `form` | 命令除记录外还要的东西。只有一个带选项字段的表单就是一个选择：选项直接列在菜单里，选中的就是输入 |
| `on` | 在哪里提供：`row`、`bulk`、`detail`；缺省处处都有 |
| `primary` | 读者最常按的那个：行里的按钮，不在菜单里 |
| `tone` | `default` 或 `danger` |
| `timeout` | 一条记录的 `run` 等多久（毫秒）。过了就不再等，把结局报告为未知——它可能已经接受了命令——而不是让界面一直忙着。缺省不设期限 |

```ts
export declare function actions(list: readonly RecordAction[]): RecordActions;

export interface RecordAction {
  available?(row: RecordRow, context: ActionContext): Availability;
  changesAt?(row: RecordRow, context: ActionContext): number | null | undefined;
  readonly confirm?: ActionConfirm | ((input: ActionInput) => ActionConfirm);
  readonly form?: ActionForm;
  hidden?(row: RecordRow, context: ActionContext): boolean;
  readonly id: string;
  readonly label: string;
  readonly on?: readonly ActionPlace[];
  readonly primary?: boolean;
  run(row: RecordRow, input: ActionInput): Promise<unknown>;
  readonly timeout?: number;
  readonly tone?: ActionTone;
}

export interface ActionConfirm {
  action?: string;
  ask?: 'always' | 'bulk';
  body?: string;
  title: string;
  tone?: ActionTone;
}

export interface ActionFormField {
  initial?: string | number | boolean;
  input?: 'text' | 'number' | 'boolean';
  label: string;
  options?: readonly FieldOption[];
  required?: boolean;
}
```

在测试里不用屏幕就能钉住这些规则：[`actionHarness`](./testing#api-actionHarness)。

## 措辞 {#api-MessagesProvider}

模型只带 `code` 和 `params`，不带任何文案，这让应用能翻译或改写其中任何一句。措辞目录是另一半：一张扁平的表里有两个命名空间——issue 的 `code`，以及组件自己写的文字用的 `label.*` 键。缺了的键回落成键本身，所以缺口显示为它一直显示的那个 code，而不是什么都没有。

- `en` 与 `zhCN` 是引擎自带的两份目录，逐键对应。宿主改几句时铺开一份、再覆盖要改的键，如下面的例子。
- 改写引擎自己的键时写 `satisfies MessageOverrides`：引擎改名或删掉的键在宿主那边成为编译错误，而不是悄悄回落成引擎的句子。宿主自己的键（定义的 `text(key)`）另放在开放的 `ViewMessages` 里。消息键与 issue code 都是公开面，列在 `test/surface/messages.txt` 与 `issues.txt`。
- `ViewSurface`（每个工作台与嵌入都用它）会渲染 `MessagesProvider`，所以把 `messages` 交给 `ViewHost` 或界面就够了；`MessagesProvider` 单独存在，是给用组件拼自己界面的宿主。每个 Provider 合并在上一层之上，而不是合并在缺省目录之上。

```ts
import { zhCN, type MessageOverrides, type ViewMessages } from '@ahoo-wang/wow-view-engine/ui';

const engineWords = { 'label.filter.apply': '确定' } satisfies MessageOverrides;

export const messages: ViewMessages = {
  ...zhCN,
  ...engineWords,
  'orders.title': '订单',
};
```

```ts
export interface MessagesProviderProps {
  children: import('react').ReactNode;
  locale?: string;
  messages?: ViewMessages;
}
export declare function MessagesProvider(input: MessagesProviderProps): import('react').JSX.Element;
export type ViewMessages = Readonly<Record<string, string>>;
export type MessageOverrides = Partial<Record<MessageKey, string>>;
```

## 完整可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)：宿主、绑定、两个声明的操作、两种语言与一个内存路由器。
- 源文件：[`OrdersHost.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/OrdersHost.tsx)、[`orderActions.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/orderActions.ts)、[`memoryRouter.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/memoryRouter.ts)。
- 一个真实宿主：补偿控制台的 [`src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation/dashboard/src/views)。
