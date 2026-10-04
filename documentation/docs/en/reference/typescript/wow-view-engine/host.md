---
title: 'Host Wiring'
description: 'ViewHost, bind, the router port, declared actions with actions(), and wording with MessagesProvider — @ahoo-wang/wow-view-engine'
---

# Host Wiring

A host writes `ViewHost` once around its pages and fills a few ports: data (`engine`), router (`router`), theme, language, and what each resource does in this host (`bindings`). Every surface under it — a workbench, an embed, a board — takes only what differs where it stands: `<DataWorkbench definitionId="orders" />`. The router, the i18n and the theme stay the host's; the ports are bridges to them.

Guides: [Fitting the View Engine into a Host](../../../guide/typescript/view-engine-host.md) (`ViewHost`, [`bind`](../../../guide/typescript/view-engine-host.md#bind), [the router port](../../../guide/typescript/view-engine-host.md#router-port), [messages and locale](../../../guide/typescript/view-engine-host.md#messages)); [Declared Actions](../../../guide/typescript/view-engine-actions.md) (how to [declare an action](../../../guide/typescript/view-engine-actions.md#declare), placement, confirmation and outcomes).

## ViewHost {#api-ViewHost}

Hosts nest, the inner one over the outer one — a page's own bindings, a second engine — and a surface's own `engine`, `messages`, `locale`, `onNavigate` or `record` still wins. Only the outermost host paints `<html>`: the mode, the preset, the brand.

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

// Inside React Router's RouterProvider.
export function AppHost({ engine, children }: { engine: ViewEngine; children: ReactNode }) {
  const router = useReactRouter();
  const bindings = useMemo(
    () => [
      bind('orders', {
        // Every way to the orders — a link, a board's follow-up, the navigation — goes through this route.
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

| Prop | Role |
|---|---|
| `engine` | The application's engine, built once. An inner host without one uses the outer one's |
| `bindings` | What each resource does in this host (`bind`) — its route, how its records are read, the commands on its records; an inner host's wins by id |
| `router` | The router port: `useReactRouter()` from `/react-router`, or two members over another router. With it the engine keeps the address: every way off a board or a view goes through the route of the resource it leads to; a workbench opens the address's `?view=` and writes it back; a bound resource's record detail follows `?id=`; a view handed over, and a board's filters and tab, travel as the history entry's state |
| `navigate` | Every way off in the host's own hands instead of the router's: handed resolved through the route of the resource it leads to (`ViewRoute`); a URL, and a resource with no route, as it came |
| `locale`, `messages` | The language port: the language values show in, and words merged over those in force — the engine's own and the definitions' keys (`text(key)`) alike. Changing them redraws every open view without reopening, re-querying or dirtying anything |
| `theme`, `preset`, `brand` | The theme's two paths: `theme="host"` follows the host's shadcn theme (the host imports `shadcn-bridge.css`); or `preset` names a preset (its stylesheet imported), which the host's own chrome wears through `fve-tokens`. `brand` is the brand colour over the preset |
| `colorMode`, `rememberColorMode` | Light or dark: `system` by default — the engine paints `<html>`'s `.dark` and `color-scheme` and follows the system live; `light` / `dark` start pinned; `host` where the host paints its own mode (as next-themes does) and the engine only follows `.dark`. The reader picks another through `useColorMode`; `rememberColorMode` is the `localStorage` key that keeps the pick on this machine |

Theming in detail: [Theming the View Engine](../../../guide/typescript/view-engine-theming.md).

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

Binds a definition's behaviour in the host: `bind(definitionId, options)`. A surface's own prop of the same name wins. The id must be one a resource registers: `ViewHost` hands each binding it is given to the engine (`checkBinding`), and one naming no registered definition — a misspelt id, whose route, reading and actions would otherwise be lost without a word — is reported through `onIssue` as `binding.definition.unknown`, once per id. It is a warning, not an error: bindings pass down to inner hosts, so a binding on an outer `ViewHost` meant for the definitions of an engine nested inside it is reported once against the outer engine, and so is a binding kept for a resource a feature flag left out. Neither breaks anything; put such a binding on the inner host, or leave it and ignore the warning.

| Option | Role |
|---|---|
| `route` | Where a view or a board of this definition lives in the host's address: the path of the page that opens `instanceId` — `null` for a view nobody saved (a follow-up on a group, a board's own analysis), which opens on the page's default and is handed over whole. The engine hands the router, or `navigate`, the path with what the page opens with (`ViewRoute.state`); a definition with no route is handed over raw. `target` is absent where `useViewNavigation` asks for a link |
| `actions` | The commands its records take, declared with [`actions()`](#api-actions): the engine places them wherever its records are drawn — the workbench's rows, selection and detail, and every record panel of a board over it — asks first, runs them a few at a time and says how it went |
| `slots` | The host's own markup, drawn after the declared actions — one over the view, one over a selection, one per row: the escape hatch for what a declaration cannot say, a link out, say |
| `reading` | How one of its records is read: the detail's own reading, its title, its sections, who holds which record is open — what `DataWorkbench` takes as `record.detail`, and what an `EmbeddedView`'s `detail` opens with |

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

`useViewNavigation()` lists the places of the host's navigation: every registered resource the host bound a `route` to, in the order registered, with its system views, titles said in the words in force; `current` reads the router's address, and with no router nothing is current.

## Router port {#api-ViewRouter}

`ViewRouter` is the host's router as the engine reads and moves it, whichever library it is. Its contract is the object's identity: **a new `ViewRouter` object whenever `location` changes, and the same object while it does not.** `ViewHost` hands it down through context, and everything that reads the address — the open view, the open record, a board's filters and tab — re-reads it when, and only when, the object is new. A router mutated in place is never seen to move; one rebuilt on every render reads the address anew on every render. Build it with `useMemo` over the location's parts, as `useReactRouter` does. `go` is read at the moment it is called, so it may be a new function each time.

`useReactRouter()` (the `/react-router` entry) hands React Router to `ViewHost`'s `router`, inside its `RouterProvider` (or any router component): paths relative to its `basename`, as its own links are. React Router is an optional peer that only this entry loads.

```ts
export interface ViewRouter {
  go(path: string, options?: { state?: unknown; replace?: boolean }): void;
  readonly location: ViewLocation;
}
export declare function useReactRouter(): ViewRouter;
```

## Declared actions {#api-actions}

Actions are code and are never saved: the host says what, when and in what words; the engine places them — in the row, over a selection, in the record's detail — asks, runs them a few at a time and reports. `actions()` checks the list: every action has an id no other has, a label and a `run`. A mistake there is the host's code, so it throws at once.

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
    // A button in the row; the rest go behind the row's "⋯" menu.
    primary: true,
    // `true`, or why not: the reason shows on the disabled button, and a selection splits by it.
    available: row => (paid(row) ? true : text('orders.notPaid')),
    // One order ships at a press; a selection is counted and asked first.
    confirm: { title: text('orders.shipTitle'), ask: 'bulk' },
    run: row => commands.ship(String(row.key)),
  },
]);
```

The fields `available` reads must be in the row: a page of records fetches only what the view shows, so where a rule reads a field that is not a column, the definition lists it in `record.rowFields` (see [which fields a row carries](./definitions#api-RecordCapability)), or the action stays disabled.

| Member | Role |
|---|---|
| `id`, `label` | An id unique among the actions of one binding; the label may be `text(key)` |
| `run(row, input)` | Sends the command for one record. It resolves once the read model reflects it — a Wow command waits for `CommandStage.SNAPSHOT` (or the stage the host's projection needs) — because the engine reads the view again right after. It throws when the command was refused; what it throws is read for the source's own reason. A command is a write, so make it idempotent: one that times out or loses the network has an outcome nobody knows, and the reader, told to check, may press again |
| `available(row, context)` | `true` when the record takes the command now, else why not. Asked with the input where one is known, so a choice can refuse one option and offer the rest |
| `hidden(row, context)` | The record does not show the action at all — the reader may not do it — as against `available`, which shows it disabled with why |
| `changesAt(row, context)` | When `available` next changes on its own, in milliseconds; the engine asks again then, so the host keeps no timer |
| `confirm` | Ask first; may be a function of the input. Its words are said with `{count}` (how many records, with a `-one` form where a language says one apart), `{value}` (a choice's option) and `{record}` (the record's key when it is one record). `ask: 'always'` (the default) asks for one record too; `'bulk'` asks for a selection only. A selection always asks |
| `form` | What the command needs besides the record. A form of one field with options is a choice: its options are offered in the menu, and the one picked is the input |
| `on` | Where it is offered: `row`, `bulk`, `detail`; every place by default |
| `primary` | The one a reader presses most: a button in the row, not in the menu |
| `tone` | `default` or `danger` |
| `timeout` | How long one record's `run` is waited for, in milliseconds. Past it the engine stops waiting and reports the outcome as unknown — it may have taken the command — rather than holding the surface busy. No deadline by default |

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

Pin these rules in a test without a screen: [`actionHarness`](./testing#api-actionHarness).

## Wording {#api-MessagesProvider}

The model carries `code` and `params` and no copy at all, which is what lets an application translate or reword any of it. The catalogue is the other half: one flat map with two namespaces — an issue's `code`, and a `label.*` key for the text a component writes itself. A missing key falls back to the key, so a gap shows as the code it always showed rather than as nothing.

- `en` and `zhCN` are the engine's two catalogues, key for key. A host that changes a few spreads one and overrides what it changes, as the example below does.
- Write a rewording of the engine's own keys with `satisfies MessageOverrides`: a key the engine renames or drops is then a compile error on the host's side rather than wording that silently falls back to the engine's sentence. The host's own keys (a definition's `text(key)`) go beside it in an open `ViewMessages`. Message keys and issue codes are public surface, listed in `test/surface/messages.txt` and `issues.txt`.
- `ViewSurface` (which every workbench and embed draws on) renders `MessagesProvider`, so handing `messages` to `ViewHost` or a surface is enough; `MessagesProvider` exists on its own for a host that builds its own surface out of the components. Each provider merges over the one above it, not over the defaults.

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

## The full working version

- Storybook's [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs): the host, its bindings, two declared actions, words in two languages and an in-memory router.
- Source files: [`OrdersHost.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/OrdersHost.tsx), [`orderActions.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/orderActions.ts), [`memoryRouter.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/memoryRouter.ts).
- A real host: the compensation console's [`src/views/`](https://github.com/Ahoo-Wang/Wow/tree/main/compensation/dashboard/src/views).
