---
title: 声明式操作
description: 记录上的命令怎样声明：可用规则与理由、放在哪、确认与表单、多选、结局（包括结果未知与幂等）、record.rowFields，以及用 actionHarness 测。
---

# 声明式操作

本页回答：**记录上的命令——发货、取消、改仓——怎样交给视图引擎，而不必在每个页面上自己画按钮、对话框和进度？**

记录上的命令是声明，不是画出来的。宿主说**做什么**：有哪些命令、各在什么时候可用、不可用时为什么、要不要先问、要读者填什么。引擎负责**放在哪、怎样做**：

| 宿主写（业务） | 引擎做（机制） |
|---|---|
| 有哪些操作，各调哪条命令（`run`） | 放在哪：行里的按钮、行的「⋯」菜单、多选条、记录详情、看板的记录面板 |
| 何时可用、不可用时怎么说（`available` 返回 `true` 或理由），何时自己翻转（`changesAt`） | 停用并写明理由；多选里只有一部分能做时，列出不能做的与理由，一键只选能做的；到点重算，宿主不开定时器 |
| 措辞、危险程度、要不要先问（`confirm`）、要填什么（`form`） | 确认框与表单、焦点、键盘、读屏播报 |
| 命令何时算完成：`run` 在读模型反映了命令之后才 resolve | 几条一起跑：并发、进度、停止、逐条的结局，跑完重读视图 |

这样分是因为「这张订单现在能不能发货」是业务规则，只有宿主知道；而「停用的按钮怎样让键盘用户也看到理由」「多选时有两条不能发怎么办」「请求超时了算成功还是失败」是机制，每个宿主都写一遍只会写出不一样的错。

## 声明一个操作 {#declare}

下面假设一个订单聚合接受三条命令：`ship_order`（只有已付款的能发）、`cancel_order`（发货前才能取消，要写原因）与 `change_warehouse`。先把命令写成一个客户端，按聚合 id 发送：

<!-- typecheck: file=orderCommands.ts -->

```ts
// src/views/orderCommands.ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  CommandClient,
  CommandStage,
  commandHeaders,
  waitStrategy,
} from '@ahoo-wang/wow-client';

/** 一个人在一张订单上发的命令。每条都在快照反映了它之后才 resolve。 */
export interface OrderCommands {
  ship(id: string, version: number): Promise<void>;
  cancel(id: string, version: number, reason: string): Promise<void>;
  moveTo(id: string, warehouse: string): Promise<void>;
}

export function orderCommands(fetcher: Fetcher): OrderCommands {
  // 用 wow-generator 生成的命令客户端时，路由与请求体都有类型；这里按路径发。
  const client = new CommandClient({ fetcher, basePath: 'example/order' });
  // `Command-Wait-Stage: SNAPSHOT`：快照写好才答，因为 `run` 一 resolve 引擎就重读视图。
  const wait = waitStrategy({ stage: CommandStage.SNAPSHOT });
  const send = async (
    command: string,
    id: string,
    body: object,
    version?: number,
  ) => {
    // 被服务端拒绝时 reject：引擎把服务端的原因报在那张订单上。
    await client.send({
      path: `{id}/${command}`,
      method: 'POST',
      // id 是路径变量，由 fetcher 编码，不拼进路径。
      urlParams: { path: { id } },
      // 带上这一行显示时的版本：已经生效过的命令再发一次，按版本冲突被拒，不会做两遍。
      headers:
        version === undefined
          ? wait
          : { ...wait, ...commandHeaders({ aggregateVersion: version }) },
      body,
    });
  };
  return {
    ship: (id, version) => send('ship_order', id, {}, version),
    cancel: (id, version, reason) =>
      send('cancel_order', id, { reason }, version),
    moveTo: (id, warehouse) => send('change_warehouse', id, { warehouse }),
  };
}
```

然后声明操作。`actions([...])` 检查每个 id 唯一、每个操作都有 `run`，写错是宿主代码的错，所以当场抛出：

<!-- typecheck: file=orderActions.ts -->

```ts
// src/views/orderActions.ts
import { actions, text, type RecordRow } from '@ahoo-wang/wow-view-engine';
import type { OrderCommands } from './orderCommands';

interface OrderState {
  status?: string;
  warehouse?: string;
}

// 下面读的字段都列在定义的 `record.rowFields` 里（见下一节）。
const stateOf = (row: RecordRow) => (row.data.state ?? {}) as OrderState;
const versionOf = (row: RecordRow) => Number(row.data.version);
// 行键就是聚合 id（定义没有另写 `rowKey`）。
const idOf = (row: RecordRow) => String(row.key);

export const orderActions = (commands: OrderCommands) =>
  actions([
    {
      id: 'ship',
      label: text('orders.ship'),
      // 行里的按钮；其余的收在行的「⋯」菜单里。
      primary: true,
      // `true`，或者为什么不行：一个键，按读者的语言说出来。
      available: row =>
        stateOf(row).status === 'PAID' ? true : text('orders.shipNotPaid'),
      // 例行、但发出去收不回：一张也先问，不用危险色。
      confirm: { title: text('orders.shipTitle') },
      run: row => commands.ship(idOf(row), versionOf(row)),
    },
    {
      id: 'cancel',
      label: text('orders.cancel'),
      // 会失去东西：危险色，一张也先问，并说出后果。
      tone: 'danger',
      available: row =>
        ['CREATED', 'PAID'].includes(stateOf(row).status ?? '')
          ? true
          : text('orders.cancelShipped'),
      confirm: {
        title: text('orders.cancelTitle'),
        body: text('orders.cancelBody'),
      },
      form: {
        reason: { label: text('orders.cancelReason'), input: 'text' },
      },
      // 不放在多选条上：每次取消都有自己的原因。
      on: ['row', 'detail'],
      // 30 秒没有回音就不再等这一张，结局记为「结果未知」。
      timeout: 30_000,
      run: (row, { reason }) =>
        commands.cancel(idOf(row), versionOf(row), String(reason)),
    },
    {
      id: 'warehouse',
      label: text('orders.moveTo'),
      // 只有一个带选项的字段：菜单里直接列出选项，不弹表单。
      form: {
        warehouse: {
          label: text('orders.warehouse'),
          options: [
            { value: 'SH', label: text('orders.shanghai') },
            { value: 'GZ', label: text('orders.guangzhou') },
          ],
        },
      },
      // 带着选项问：订单已在的那个仓不再提供。
      available: (row, { input }) =>
        input?.warehouse !== undefined &&
        input.warehouse === stateOf(row).warehouse
          ? text('orders.sameWarehouse')
          : true,
      // 改回去和改过来一样容易：一张选了就改，多选才先数清楚。
      confirm: { title: text('orders.moveTitle'), ask: 'bulk' },
      run: (row, { warehouse }) =>
        commands.moveTo(idOf(row), String(warehouse)),
    },
  ]);
```

在资源上绑定它们：[`bind('orders', { route, actions: orderActions(orderCommands(fetcher)) })`](../../reference/typescript/wow-view-engine/host.md#api-bind)（[把引擎接进宿主](./view-engine-host.md#bind)）。资源在哪里出现，操作就跟到哪里。

| 成员 | 是什么 |
|---|---|
| `id`、`label` | 在同一个绑定的操作里唯一的 id；名字是一个键或一段文字 |
| `primary` | 读者按得最多的那个：行里的按钮。一个资源最多一个 |
| `tone` | `default` 或 `danger` |
| `on` | 在哪里提供：`row`、`bulk`、`detail`，缺省三处都有 |
| `hidden(row, ctx)` | 这条记录上根本不出现：这个人不能做 |
| `available(row, ctx)` | `true`，或为什么现在不行：出现但停用，并写明理由 |
| `changesAt(row, ctx)` | `available` 下一次自己翻转的时刻（毫秒），只有新的状态才会改变它时不写 |
| `confirm` | 先问什么；也可以是输入的函数 |
| `form` | 命令除记录之外还要什么 |
| `run(row, input)` | 为一条记录发命令 |
| `timeout` | 一条记录的 `run` 等多久（毫秒）；缺省不设期限 |

规则都带第二个参数 `{ now, input? }`。时间由引擎给（测试里由测试给），规则不读时钟；`input` 在已知时给——菜单里选的那一项、填好的表单——所以「已经是这个值」可以只拒绝那一项。一条规则抛错是宿主的 bug：引擎拒绝这条记录，而不是提供一个没人检查过的命令。

**可用性只随时间翻转时**，写 `changesAt`：面上取所有看得见的记录里最早的那个时刻，只开一个定时器，到点重算。

<!-- typecheck-context
import type { RecordRow } from '@ahoo-wang/wow-view-engine';
declare function holdUntil(row: RecordRow): number;
declare function release(id: string): Promise<void>;
-->

```ts
import { actions, text } from '@ahoo-wang/wow-view-engine';

export const holdActions = actions([
  {
    id: 'release',
    label: text('orders.release'),
    // 扣留期内不能放行……
    available: (row, { now }) =>
      holdUntil(row) > now ? text('orders.onHold') : true,
    // ……扣留期一过就能：到这个时刻引擎再问一次。
    changesAt: (row, { now }) =>
      holdUntil(row) > now ? holdUntil(row) + 1 : null,
    run: row => release(String(row.key)),
  },
]);
```

**权限**写在这里：这个人不能做的，用 `hidden` 让它不出现；这个人能做、但这条记录现在不行的，用 `available` 停用并说明。理由用读者的话说出为什么、能做什么替代（「已发货的订单不能取消，请走退货」），不写代码或常量。服务端仍然有最后一句话：规则与命令处理器检查的条件一致即可，不要另造一条更严的。

## 行要读的字段：`record.rowFields` {#row-fields}

这是接操作时最常踩的坑。**引擎只查询视图显示的东西**：一页的查询只投影行键、可见的列、卡片的字段（定义允许卡片布局时）与排序字段，不是整个文档。交给操作的 `row.data` 也就只有这些。

所以，「待发货」视图没有显示状态列时，`stateOf(row).status` 是 `undefined`，「发货」的规则答「只有已付款的订单能发货」，每一行的按钮都停用——而切到显示状态列的「全部订单」，同一张订单又能发了。

规则要读的字段，在定义里写进 [`record.rowFields`](../../reference/typescript/wow-view-engine/definitions.md#api-RecordCapability)，不管视图显示不显示，每一行都带着它们：

<!-- typecheck-context
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
declare const descriptor: QueryModelDescriptor;
-->

```ts
import { defineView, text } from '@ahoo-wang/wow-view-engine';

export const ordersDefinition = defineView(descriptor, {
  id: 'orders',
  source: 'order',
  title: text('orders.title'),
  fields: {
    aggregateId: text('orders.id'),
    'state.status': text('orders.status'),
    'state.warehouse': text('orders.warehouse'),
    'state.amount': text('orders.amount'),
    version: text('orders.version'),
  },
  // 操作读这三个字段，视图显示不显示它们都一样。
  record: { rowFields: ['state.status', 'state.warehouse', 'version'] },
});
```

每个都必须是定义声明了的、一行里真有的字段，准入会检查（`definition.record.row-field-unknown`）。没有「有操作时就取整个文档」的开关：整个文档正是让补偿控制台一页失败执行重达 808 KB 的原因。

## 放在哪 {#placement}

| 位置 | 画什么 |
|---|---|
| 行（与卡片） | `primary` 的是按钮，其余在「{record} 的操作」菜单（「⋯」）里 |
| 多选条 | `primary` 的写「{action} {count} 条」，其余按名字；不是全都能做时写「{action} {able}/{count} 条」，一条都不能做时停用，以最常见的理由为提示 |
| 记录详情 | 抽屉头部画同一套（`on` 含 `detail` 的） |
| 看板的记录面板 | 照工作台画：记录的操作两档都有，多选的只在交互档；命令后整块看板重读 |

- **停用的按钮仍可获焦。** 它不用原生的 `disabled`，而是 `aria-disabled`：在 Tab 序里，获焦就打开写着理由的提示，理由也是按钮的无障碍描述；菜单顶端去重后列出各条理由。
- 命令运行时「⋯」照样能开，菜单里的项停用；问题框关上时焦点回到按下的那个按钮。
- [`EmbeddedView`](../../reference/typescript/wow-view-engine/components.md#api-EmbeddedView) 不画声明的操作：它的行命令只有宿主自己的 `rowActions`。

## 确认与表单 {#confirm}

什么时候先问，是引擎的规矩，宿主只声明意图：

| 情形 | 问不问 |
|---|---|
| 多选 | **总是问**：多少条、哪些不会发、为什么 |
| 一条，没有 `confirm` | 直接做 |
| 一条，`confirm`（`ask` 缺省 `'always'`） | 先问 |
| 一条，`confirm: { …, ask: 'bulk' }` | 直接做；只有多选才问 |
| 有表单 | 总是在对话框里填 |
| 一个带选项的字段（选择） | 菜单里列出选项，选中的就是输入；有 `confirm` 时选完再问一次，`ask: 'bulk'` 时不问 |

怎样选 `ask`：会失去东西的（取消、退款、删除）——危险色，`confirm` 说出后果，一条也问；例行但收不回的（发货）——一条也问，不用危险色；改回去和改过来一样容易的（换仓、改优先级）——`ask: 'bulk'`，多选时先数清楚。

- `confirm` 的字：`title`、`body`；`action` 是确认按钮上的字，也是结局那一行里这次运行的名字，缺省是操作的 `label`；`tone` 缺省是操作的。
- 字里可以写 `{count}`（多少条）、`{value}`（选择所选那一项，按读者的语言说出它的 `label`）、`{record}`（一条时的记录键）。某种语言单数另有说法时，另写一个 `-one` 结尾的键（`'orders.shipTitle-one'`）；中文不分，就不写。
- `confirm` 可以是输入的函数：标成「不可恢复」时换一句后果、换成危险色。它抛错时引擎按操作名问，不跳过确认。
- 宿主没写 `confirm` 的多选，引擎问「对 {count} 条记录执行「{action}」？」；一条时点名不计数。
- 只有**危险且没有表单**的问题是 `alertdialog`（读屏当紧急念），带表单的与例行的问题是 `dialog`。框外点击不关，焦点困在框内。
- 表单字段：`input` 是 `text`、`number` 或 `boolean`（有 `options` 时是选项），缺省必填（`required: false` 改为选填），`initial` 是打开时的值；`run` 按名字拿到它们。提交键始终可按，缺项时按下才标出那一项并把焦点送过去。

## 多选 {#bulk}

`run` 只写一条记录，多选由引擎调度：

- **部分可用**：问题框里说「{count} 条里 {able} 条能{action}」，按理由分组列出不能做的（理由、条数、前几个键），「只选能做的 N 条」一键把选择收窄到能做的。照样确认时，不能做的不发送，记为**未执行**而不是失败，并留在选择里。
- **几条一起跑**：一次最多 4 条并发，状态条显示进度（「正在执行 2/5」）与「停止」。第一次「停止」只是不再开始新的，按钮随即变成「不再等待」；再按一次，还在飞的记为结果未知，这一趟当场落定。
- **跑完重读视图**，失败的与未执行的留在选择里，以便处理后重试。
- 命令没有批量版本，所以没有 `runMany`；服务端有了批量命令再说。

## 结局 {#outcomes}

`run` 落定之后，每条记录有一个结局，状态条画在行的下面，按结局点名（「发货 · SO-1002 已完成」「SO-1002 失败：理由」）：

| 结局 | 什么时候 | 之后 |
|---|---|---|
| 已完成 | `run` resolve | 视图重读 |
| 失败 | `run` reject：服务端拒绝了命令 | 原因取自服务端的错误，记录**留在选择里** |
| 未执行 | 轮到它时操作已经不接这条记录（状态在选择之后变了） | 记录留在选择里，带着理由 |
| 结果未知 | 发出之后没有回音：超时（`TimeoutError`、`FetchTimeoutError`、HTTP 504）、中止、断网、过了操作的 `timeout`，或者读者按了「不再等待」 | 「N 项结果未知，先刷新核对」，这些记录**取消选择** |

**`run` 要等读模型反映了命令再 resolve。** 引擎紧接着就重读视图；跑在命令前面的刷新读到的是旧状态，读者会以为命令没生效。Wow 的命令用 `waitStrategy({ stage: CommandStage.SNAPSHOT })` 作请求头（`Command-Wait-Stage`），或者宿主的投影需要的那个阶段。

**结果未知的记录取消选择**，不留着诱导一次可能把退款做两遍的盲目重跑。可读者核对之后仍可能再按一次，所以**命令要幂等**：

- 发送这一行显示时的聚合版本——`commandHeaders({ aggregateVersion })`，即 `Command-Aggregate-Version`，定义把 `version` 列进 `record.rowFields`——第一次已经生效的命令再发一次，按版本冲突被拒，而不是再做一遍；
- 再带一个服务端据以去重的请求 id（`commandHeaders({ requestId })`，即 `Command-Request-Id`），同一次发送的重试只算一次。

失败与结果未知的原错误还经引擎环境的 `onError` 交给宿主的监控（`kind: 'action'`，带操作 id、记录键、定义与视图），中止不报。

## 插槽：逃生口 {#slots}

声明说不出的东西——一个外链、一个宿主自己的控件——画在插槽里：`bind` 上的 `slots: { row, bulk, global }`，排在声明的操作之后。插槽里仍要发命令时，调用上下文的 `run`，它与声明的操作走同一个执行器，进度与结局报在同一行：`row: ({ row, run, busy }) => …`。插槽不是主路；能声明的都声明。

## 用 `actionHarness` 测 {#harness}

`/testing` 的 [`actionHarness(actions, rows, { now })`](../../reference/typescript/wow-view-engine/testing.md#api-actionHarness) 按引擎自己的规则读声明，不画界面——读的是界面用的同一份规则。在有代表性的行上覆盖每条规则、每个位置提供什么、按下问什么，以及 `run` 把哪条命令发给哪个 id：

<!-- typecheck-context
import { orderActions } from './orderActions';
import type { OrderCommands } from './orderCommands';
declare function expect(value: unknown): { toEqual(expected: unknown): void; toBe(expected: unknown): void; toHaveBeenCalledWith(...args: unknown[]): void };
declare const vi: { fn<T extends (...args: never[]) => unknown>(impl: T): T };
-->

```ts
import { text, type RecordRow } from '@ahoo-wang/wow-view-engine';
import { actionHarness } from '@ahoo-wang/wow-view-engine/testing';

const row = (id: string, status: string): RecordRow => ({
  key: id,
  data: { version: 3, state: { status, warehouse: 'SH' } },
});
const commands: OrderCommands = {
  ship: vi.fn(() => Promise.resolve()),
  cancel: vi.fn(() => Promise.resolve()),
  moveTo: vi.fn(() => Promise.resolve()),
};
const orders = actionHarness(
  orderActions(commands),
  [row('O-1', 'PAID'), row('O-2', 'SHIPPED')],
  { now: Date.parse('2026-09-30T00:00:00Z') },
);

// 每个位置提供哪些；一条记录为什么不接。
expect(orders.at('bulk')).toEqual(['ship', 'warehouse']);
expect(orders.state('ship', 'O-2')).toEqual({
  hidden: false,
  available: false,
  reason: text('orders.shipNotPaid'),
});
// 多选按能不能做分开，不能做的按理由分组。
expect(orders.bulk('ship').able).toEqual(['O-1']);
// 按下问什么：`{ asks, confirm }`。
expect(orders.asks('ship', 'row').asks).toBe(true);
expect(orders.asks('warehouse', 'row', { warehouse: 'GZ' }).asks).toBe(false);
// 选择的选项，表单里空着的必填项。
expect(orders.state('warehouse', 'O-1', { warehouse: 'SH' }).reason).toBe(
  text('orders.sameWarehouse'),
);
expect(orders.missing('cancel', {})).toEqual(['reason']);
// `run` 发给聚合 id，带着这一行的版本。
await orders.run('cancel', 'O-1', { reason: '买家要求' });
expect(commands.cancel).toHaveBeenCalledWith('O-1', 3, '买家要求');
```

`run` 遇到操作不接的记录时以 `ActionRefusedError` 和理由拒绝，正如引擎不会把它发出去。操作里的每个键是否都有措辞，用根入口的 `withText` 遍历声明、`textKeyOf` 查 `available` 返回的理由；写法在 skill `wow-view-host` 的 [actions.md](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-view-host/references/actions.md)，那里还有命令客户端本身的测试与在 Node 里跑这些测试的配置。

## 完整的可运行版本

- Storybook 的[接入导览](/storybook/?path=/docs/view-engine-接入导览--docs)第 4 步：订单上的「发货」与「取消订单」，页面下方可以按。源文件：[`orderActions.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/orderActions.ts)、[`wowCommands.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowCommands.ts)、[`ordersDefinition.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersDefinition.ts)，以及用 `actionHarness` 核对它们的 [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts)。
- 每种放法与流程一个交互测试——行的主操作与溢出菜单、部分可用的多选、表单、危险确认、到点翻转：[`DeclaredActions.test.stories.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/DeclaredActions.test.stories.tsx)。
- 一个真实的宿主：补偿控制台的 [`executionActions.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/compensation/dashboard/src/views/executionActions.ts)（带 `changesAt`、输入的函数作 `confirm` 与一个选择）与它的 [`executionActions.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/compensation/dashboard/src/views/executionActions.test.ts)。

## 下一步

| 接下来 | 阅读 |
|---|---|
| [`ViewHost`](../../reference/typescript/wow-view-engine/host.md#api-ViewHost)、`bind`、路由与嵌入 | [把引擎接进宿主](./view-engine-host.md) |
| 键盘与读屏怎样走一遍声明式操作 | [视图引擎的可访问性](./view-engine-accessibility.md) |
| 从零接一个业务对象，带一个操作 | [视图引擎入门](./view-engine-getting-started.md) |
