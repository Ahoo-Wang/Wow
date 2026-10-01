# From commands to actions

A command on a record is declared, not drawn. The host says **what**: which commands, when each is available, what a refusal says, whether to ask first and what to ask for. The engine does **where and how**: the row's button and menu, the selection bar, the record detail, the question and the form, a selection partly able to take it, a few at a time with progress and stop, the outcome, the refresh. Reference: README [Integrating a host: declared actions](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#integrating-a-host-declared-actions) and [host-integration.md §5](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/host-integration.md#5-声明式操作宿主声明做什么引擎负责怎样做).

## Decide, command by command

| Decision | How to decide | Declare |
| --- | --- | --- |
| Does it reach the screen? | A person issues it on a record in the course of the work the view supports. Commands a process or a saga issues, creation, and edits of many fields belong elsewhere (a form page of the host's own). | One entry in `actions([...])` |
| Which one is pressed most? | The next step of the queue (ship a paid order, retry a failure). At most one per resource. | `primary: true` |
| When is it available? | The aggregate's own rule, read from the record's state: the same condition the command handler checks. Do not invent a stricter one; the server keeps the last word. | `available: (row, { now, input }) => true \| reason` |
| What does a refusal say? | Why not, in the audience's words, and what to do instead where there is something (「只有已付款的订单能发货」). Never a code or a constant. | a `text(key)` reason |
| Does it read fields the view may not show? | The rule reads state fields; a page fetches only the columns shown. | the definition's `record.rowFields` (ask `wow-view-definition`) |
| Does availability change with time alone? | A hold that expires, a timeout passing. | `changesAt: (row, { now }) => ms \| null`; the engine asks again then, keep no timer |
| May this person do it at all? | A role the host knows. | `hidden: row => …` (absent), as against `available` (disabled with why) |
| Does it lose something? | Cancelling, refunding, deleting, forcing past a limit: what is lost does not come back. | `tone: 'danger'` and `confirm: { title, body }` saying the consequence; `ask` left out (`'always'`), so one record is asked too |
| Is it a routine step that cannot be undone? | Shipping a paid order: irreversible, but the next step of the work, not a loss. | `confirm: { title }` with `ask` left out (`'always'`): one record is asked too; no danger tone |
| Is it undone as easily as done, but worth counting for many? | Changing a warehouse, a priority. | `confirm: { title, ask: 'bulk' }`: one record runs at a press, a selection is counted first |
| May a selection take it? | Only if running it on many at once is something people do and recoverable. | `on: ['row', 'detail']` to keep it off the selection bar |
| What else does it need? | A value the command carries: a reason, a new value. One field of options becomes a choice in the menu (with a `confirm` asked once after the pick, unless `ask: 'bulk'`); anything else a form in the question. | `form: { name: { label, options \| input, required, initial } }`; `run(row, input)` |
| Which record does it go to? | The aggregate id, whatever the row key is. With a business `rowKey` (an order number), read the id off the row; the definition lists it in `record.rowFields`. | `run: row => commands.ship(idOf(row))` |
| When is it done? | Only once the read model shows it, so the refresh after shows the new state. | `run` sends with `waitStrategy({ stage: CommandStage.SNAPSHOT })` (the `Command-Wait-Stage` header; or the stage the projection needs) and throws on refusal |

`run` takes one record; the engine runs a selection a few at a time. There is no batch `runMany` until the service has a batch command. Slots (`slots: { row, bulk, global }`) are for what a declaration cannot say — a link out, a control of the host's own — and a slot that sends a command calls the context's `run` so it reports on the same line.

## An example

The order aggregate takes `ship_order` (only when paid), `cancel_order` (only before it ships, with a reason) and `change_warehouse`. The definition (`wow-view-definition`) keys rows by the order number and lists `aggregateId`, `state.status` and `state.warehouse` in `record.rowFields`.

<!-- typecheck: file=orderCommands.ts -->

```ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  CommandClient,
  CommandStage,
  waitStrategy,
} from '@ahoo-wang/wow-client';

/** The commands a person issues on one order, by aggregate id. Each resolves once the snapshot shows it. */
export interface OrderCommands {
  ship(id: string): Promise<void>;
  cancel(id: string, reason: string): Promise<void>;
  moveTo(id: string, warehouse: string): Promise<void>;
}

export function orderCommands(fetcher: Fetcher): OrderCommands {
  // A client generated by wow-generator types each route and body; this one
  // sends them by path.
  const client = new CommandClient({ fetcher, basePath: 'example/order' });
  // `Command-Wait-Stage: SNAPSHOT`: answer once the snapshot is written, for
  // the engine reads the view again right after `run` resolves.
  const headers = waitStrategy({ stage: CommandStage.SNAPSHOT });
  const send = async (command: string, id: string, body: object = {}) => {
    // The id is a path variable, encoded by the fetcher: never pasted into
    // the path. A refused command rejects; the engine reports the service's
    // reason on that order and leaves it selected.
    await client.send({
      path: `{id}/${command}`,
      method: 'POST',
      urlParams: { path: { id } },
      headers,
      body,
    });
  };
  return {
    ship: id => send('ship_order', id),
    cancel: (id, reason) => send('cancel_order', id, { reason }),
    moveTo: (id, warehouse) => send('change_warehouse', id, { warehouse }),
  };
}
```

<!-- typecheck: file=orderActions.ts -->

```ts
import { actions, text, type RecordRow } from '@ahoo-wang/wow-view-engine';
import type { OrderCommands } from './orderCommands';

interface OrderState {
  status?: string;
  warehouse?: string;
}

/** The fields below are the definition's `record.rowFields`. */
const stateOf = (row: RecordRow) => (row.data.state ?? {}) as OrderState;
/** The aggregate id: `row.key` is the order number people look up. */
const idOf = (row: RecordRow) => String(row.data.aggregateId);

export const orderActions = (commands: OrderCommands) =>
  actions([
    {
      id: 'ship',
      label: text('orders.ship'),
      primary: true,
      available: row =>
        stateOf(row).status === 'PAID' ? true : text('orders.shipNotPaid'),
      // Routine but not undone: asked for one order too (`ask` left at
      // 'always'), with no danger tone.
      confirm: { title: text('orders.shipTitle') },
      run: row => commands.ship(idOf(row)),
    },
    {
      id: 'cancel',
      label: text('orders.cancel'),
      // A loss: asked for one order too, and the consequence said.
      tone: 'danger',
      available: row =>
        ['PENDING_PAYMENT', 'PAID'].includes(stateOf(row).status ?? '')
          ? true
          : text('orders.cancelShipped'),
      confirm: {
        title: text('orders.cancelTitle'),
        body: text('orders.cancelBody'),
      },
      form: {
        reason: { label: text('orders.cancelReason'), input: 'text' },
      },
      // Not over a selection: each cancellation has its own reason.
      on: ['row', 'detail'],
      run: (row, { reason }) => commands.cancel(idOf(row), String(reason)),
    },
    {
      id: 'warehouse',
      label: text('orders.moveTo'),
      // One field of options: a choice in the menu, no form.
      form: {
        warehouse: {
          label: text('orders.warehouse'),
          options: [
            { value: 'SH', label: text('orders.shanghai') },
            { value: 'GZ', label: text('orders.guangzhou') },
          ],
        },
      },
      // The warehouse the order already ships from is not offered again.
      available: (row, { input }) =>
        input?.warehouse !== undefined &&
        input.warehouse === stateOf(row).warehouse
          ? text('orders.sameWarehouse')
          : true,
      // Undone as easily: one order moves at its pick; a selection is
      // counted first. Without `ask: 'bulk'` the pick would ask once.
      confirm: { title: text('orders.moveTitle'), ask: 'bulk' },
      run: (row, { warehouse }) =>
        commands.moveTo(idOf(row), String(warehouse)),
    },
  ]);
```

Bind them on the resource: `bind('orders', { route, actions: orderActions(orderCommands(appFetcher)) })`.

Every word is a key; their words go beside the definition's (`ORDER_WORDS`), and `ViewHost`'s `messages` takes both. A question is said with `{count}`, the records it goes to, and for a choice `{value}`, the picked option's own words (its `label` said in the reader's language). Where a language says one apart, add a `-one` key; Chinese does not, so its table has none:

<!-- typecheck: file=orderActionWords.ts -->

```ts
export const ORDER_ACTION_WORDS = {
  'zh-CN': {
    'orders.ship': '发货',
    'orders.shipNotPaid': '只有已付款的订单能发货',
    'orders.shipTitle': '发货 {count} 个订单？',
    'orders.cancel': '取消订单',
    'orders.cancelShipped': '已发货的订单不能取消，请走退货',
    'orders.cancelTitle': '取消这个订单？',
    'orders.cancelBody': '取消后买家的付款原路退回，订单不能恢复。',
    'orders.cancelReason': '取消原因',
    'orders.moveTo': '改发货仓',
    'orders.shanghai': '上海仓',
    'orders.guangzhou': '广州仓',
    'orders.sameWarehouse': '订单已在这个仓',
    'orders.moveTitle': '把 {count} 个订单改到{value}？',
  },
  en: {
    'orders.ship': 'Ship',
    'orders.shipNotPaid': 'Only a paid order can ship',
    'orders.shipTitle': 'Ship {count} orders?',
    'orders.shipTitle-one': 'Ship this order?',
    'orders.cancel': 'Cancel order',
    'orders.cancelShipped': 'A shipped order cannot be cancelled; start a return',
    'orders.cancelTitle': 'Cancel this order?',
    'orders.cancelBody':
      "The buyer's payment is refunded, and the order cannot be restored.",
    'orders.cancelReason': 'Reason',
    'orders.moveTo': 'Change warehouse',
    'orders.shanghai': 'Shanghai',
    'orders.guangzhou': 'Guangzhou',
    'orders.sameWarehouse': 'The order already ships from there',
    'orders.moveTitle': 'Move {count} orders to {value}?',
    'orders.moveTitle-one': 'Move this order to {value}?',
  },
} as const;
```

## The harness test

`actionHarness` reads the declarations by the engine's own rules, without a screen. Cover each rule on representative rows, what each place offers, what a press asks, and that `run` sends the right command to the right id. It runs in Node, like `admit` (the Node Vitest config and how to point Vitest at it are under "Where the tests run" below).

<!-- typecheck-context
import { orderActions } from './orderActions';
import type { OrderCommands } from './orderCommands';
declare function expect(value: unknown): { toEqual(expected: unknown): void; toBe(expected: unknown): void; toHaveBeenCalledWith(...args: unknown[]): void };
declare const vi: { fn<T extends (...args: never[]) => unknown>(impl: T): T };
-->

```ts
import { text, type RecordRow } from '@ahoo-wang/wow-view-engine';
import { actionHarness } from '@ahoo-wang/wow-view-engine/testing';

// Keyed by the order number, as the definition's `rowKey`; the id in data.
const row = (no: string, status: string): RecordRow => ({
  key: no,
  data: { aggregateId: `id-${no}`, state: { status, warehouse: 'SH' } },
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

// Where each is offered, and why a record does not take one.
expect(orders.at('bulk')).toEqual(['ship', 'warehouse']);
expect(orders.state('ship', 'O-2')).toEqual({
  hidden: false,
  available: false,
  reason: text('orders.shipNotPaid'),
});
// A selection splits into the able and the refused, grouped by reason.
expect(orders.bulk('ship').able).toEqual(['O-1']);
expect(orders.bulk('ship').reasons).toEqual([
  { reason: text('orders.shipNotPaid'), count: 1, keys: ['O-2'] },
]);
// What a press asks: `{ asks, confirm }`, the declared question or null.
expect(orders.asks('ship', 'row').asks).toBe(true);
// `ask: 'bulk'` on one record: not asked, though the question is declared.
expect(orders.asks('warehouse', 'row', { warehouse: 'GZ' })).toEqual({
  asks: false,
  confirm: { title: text('orders.moveTitle'), ask: 'bulk' },
});
expect(orders.asks('cancel', 'row')).toEqual({
  asks: true,
  confirm: {
    title: text('orders.cancelTitle'),
    body: text('orders.cancelBody'),
  },
});
// A choice's options, a form's blank required fields.
expect(orders.choice('warehouse')?.map(option => option.value)).toEqual([
  'SH',
  'GZ',
]);
expect(orders.state('warehouse', 'O-1', { warehouse: 'SH' }).reason).toBe(
  text('orders.sameWarehouse'),
);
expect(orders.missing('cancel', {})).toEqual(['reason']);
// No rule here flips with time alone.
expect(orders.changesAt()).toBe(null);
// `run` sends to the aggregate id, not the row key.
await orders.run('cancel', 'O-1', { reason: '买家要求' });
expect(commands.cancel).toHaveBeenCalledWith('id-O-1', '买家要求');
```

`run` on a record the action does not take rejects with `ActionRefused` and its reason, as the engine would refuse to send it.

### Every action word, in every language

`admit` reads definitions only; the actions' words are checked here. `withText` walks the declarations for every key written in them (labels, questions, forms, options) and reports each one without words; a refusal is returned by `available`, so read it off the harness and look its key up with `textKeyOf`:

<!-- typecheck-context
import type { ActionHarness } from '@ahoo-wang/wow-view-engine/testing';
import { orderActions } from './orderActions';
import { ORDER_ACTION_WORDS } from './orderActionWords';
import type { OrderCommands } from './orderCommands';
declare const commands: OrderCommands;
/** The harness above. */
declare const orders: ActionHarness;
declare const ORDER_WORDS: Record<'zh-CN' | 'en', Record<string, string>>;
declare function expect(value: unknown): { toEqual(expected: unknown): void };
declare const it: { each<T>(cases: readonly T[]): (name: string, body: (value: T) => void) => void };
-->

```ts
import { textKeyOf, withText } from '@ahoo-wang/wow-view-engine';

it.each(['zh-CN', 'en'] as const)('every action key has words in %s', locale => {
  const words: Readonly<Record<string, string>> = {
    ...ORDER_WORDS[locale],
    ...ORDER_ACTION_WORDS[locale],
  };
  const missing = new Set<string>();
  withText(orderActions(commands), key => words[key], key => missing.add(key));
  // One refusal of each rule, on the rows that meet it.
  const reasons = [
    orders.state('ship', 'O-2').reason,
    orders.state('cancel', 'O-2').reason,
    orders.state('warehouse', 'O-1', { warehouse: 'SH' }).reason,
  ];
  for (const reason of reasons) {
    const key = reason === null ? null : textKeyOf(reason);
    if (key !== null && words[key] === undefined) missing.add(key);
  }
  expect([...missing]).toEqual([]);
});
```

### The command client

The commands themselves are tested against a stubbed `fetch`: the path with the id encoded in it, the body, and the wait stage.

<!-- typecheck-context
import { orderCommands } from './orderCommands';
declare function expect(value: unknown): { toEqual(expected: unknown): void; toBe(expected: unknown): void };
declare const vi: { fn<T extends (...args: never[]) => unknown>(impl: T): T; stubGlobal(name: string, value: unknown): void };
-->

```ts
import { Fetcher } from '@ahoo-wang/fetcher';

const sent: { url: string; init?: RequestInit }[] = [];
vi.stubGlobal(
  'fetch',
  vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    sent.push({ url: String(url), init });
    return Response.json({});
  }),
);

await orderCommands(new Fetcher({ baseURL: 'http://localhost:8080' })).cancel(
  'a/1',
  '买家要求',
);
expect(sent[0].url).toBe('http://localhost:8080/example/order/a%2F1/cancel_order');
expect(new Headers(sent[0].init?.headers).get('Command-Wait-Stage')).toBe(
  'SNAPSHOT',
);
expect(JSON.parse(String(sent[0].init?.body))).toEqual({ reason: '买家要求' });
```

### Where the tests run

The harness, these checks and `admit` need no browser. Where the package's Vitest config is a browser project (Storybook's, a jsdom app's with setup files), give them a Node config of their own:

<!-- typecheck: skip — a Vitest config; vitest is not among the packages the samples compile against -->

```ts
// src/views/vitest.config.ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { name: 'views', environment: 'node', include: ['**/*.test.ts'] },
});
```

Run it as `vitest run --root src/views`: Vitest takes the `vitest.config.ts` of the root it is pointed at, and `include` is relative to that root. With the config elsewhere, name both: `vitest run --config vitest.views.config.ts`.

Type-check them with the host's own `tsconfig.json` where it covers `src/views`; otherwise a small one beside them:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "resolveJsonModule": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["**/*.ts", "**/*.tsx"]
}
```

`moduleResolution: "Bundler"` reads the package's subpath exports (`@ahoo-wang/wow-view-engine/testing`); `resolveJsonModule` lets a definition import its committed descriptor. Run `tsc -p src/views`.

## Report

- The resources registered, the store and why, the routes, and the theme path.
- Per resource: the commands that became actions and those left off (and why), each rule and the state fields it reads (`record.rowFields`), each refusal's words, which ones confirm and which run over a selection, and the wait stage `run` uses.
- Each check with its command and result: the harness tests, the route test, `admit`, type check and lint; anything not run, stated as missing evidence.
