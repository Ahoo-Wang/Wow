# Wiring

The reference for every member below is the engine's README: [Create an engine](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#2-create-an-engine), [ViewHost](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#3-put-the-engine-above-your-pages-viewhost) and [Which view is open, and your route](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#which-view-is-open-and-your-route); the reasons are in [host-integration.md §4](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/docs/design/host-integration.md#4-资源注册数据在核心行为在-react). Two complete hosts to read: the compensation console (`compensation/dashboard/src/views/engine.ts`, `routes.ts`, `executionActions.ts`, and `src/features/App/ConsoleHost.tsx`) and the Storybook walkthrough (`typescript/storybook/stories/view-engine/integration/`). Confirm signatures in the installed typings; the package is pre-release.

## Sources: where each resource's rows come from

A source is the Wow query client of the model the definition reads, plus its descriptor. The engine sends only Wow queries, so the client's methods are the source as they are; `describe` lets it narrow every definition to the store it runs on before the first query.

<!-- typecheck: file=source.ts -->

```ts
import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  QueryClientFactory,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/wow-client';
import type { ViewSource } from '@ahoo-wang/wow-view-engine';

export function orderSource(fetcher: Fetcher): ViewSource {
  const factory = new QueryClientFactory({
    contextAlias: 'example',
    aggregateName: 'order',
    resourceAttribution: ResourceAttributionPathSpec.NONE,
    fetcher,
  });
  const snapshots = factory.createSnapshotQueryClient();
  // The schema route has no tenant or owner segment: its own client.
  const descriptors = factory.createQueryDescriptorClient();
  return {
    paged: (query, attributes, abort) =>
      snapshots.paged(query, attributes, abort),
    cursor: (query, attributes, abort) =>
      snapshots.cursor(query, attributes, abort),
    aggregate: (query, attributes, abort) =>
      snapshots.aggregate(query, attributes, abort),
    describe: (previous, attributes, abort) =>
      descriptors.describeSnapshot(previous, attributes, abort),
  };
}
```

An event-stream definition takes `factory.createEventStreamQueryClient()` and `describeEventStream`. Resource attribution (tenant, owner) is the fetcher's: with CoSec's interceptors the paths are filled from the token (see $wow-client).

## The engine: once, at the application's start

<!-- typecheck: file=engine.ts -->
<!-- typecheck-context
import type { DataViewDefinition, DashboardDefinition, ViewStore } from '@ahoo-wang/wow-view-engine';
declare const orders: DataViewDefinition;
declare const overview: DashboardDefinition;
declare const appFetcher: import('@ahoo-wang/fetcher').Fetcher;
declare const store: ViewStore;
declare function sendToMonitoring(record: Record<string, unknown>): void;
import { orderSource } from './source';
-->

```ts
import { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { browserRuntimeEnvironment } from '@ahoo-wang/wow-view-engine/react';

export const engine = new ViewEngine({
  store,
  resources: [
    { definition: orders, source: orderSource(appFetcher) },
    // A board queries nothing of its own.
    { definition: overview },
  ],
  environment: browserRuntimeEnvironment({
    onError: ({ kind, error, context }) =>
      sendToMonitoring({ kind, error, ...context }),
  }),
});
```

- **One engine for the application**, never one per page or per language: pages share its queries, preferences and descriptors, and a language change only redraws.
- Two definitions over one model share a source key (`definition.source`) and one source.
- Leave `limits` alone: the queue makes room for a board's panels by itself, and the descriptor supplies the server's budgets. Pass a limit only to go lower.
- Without `onIssue`, a development build prints admission findings per resource with how to fix them; they belong in the definition's `admit` test, not in a handler that hides them.

## ViewHost: the one thing around the pages

<!-- typecheck: file=Host.tsx -->
<!-- typecheck-context
import type { Fetcher } from '@ahoo-wang/fetcher';
import type { RecordActions } from '@ahoo-wang/wow-view-engine';
import { engine } from './engine';
declare const appFetcher: Fetcher;
declare const ORDER_WORDS: Record<'zh-CN' | 'en', Record<string, string>>;
declare const ORDER_ACTION_WORDS: Record<'zh-CN' | 'en', Record<string, string>>;
declare function orderActions(commands: OrderCommands): RecordActions;
declare function orderCommands(fetcher: Fetcher): OrderCommands;
interface OrderCommands {}
-->

```tsx
import type { ReactNode } from 'react';
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes/porcelain.css';
import { useReactRouter } from '@ahoo-wang/wow-view-engine/react-router';
import { bind, ViewHost, zhCN } from '@ahoo-wang/wow-view-engine/ui';

/** A workbench page on `view`, or on its default (`null`). */
export function withView(path: string, view: string | null): string {
  return view === null ? path : `${path}?${new URLSearchParams({ view })}`;
}

export const BINDINGS = [
  bind('orders', {
    route: view => withView('/orders', view),
    // The row key is the order number (the definition's `rowKey`).
    reading: { title: row => `#${String(row.key)}` },
    // actions.md: the commands, and the declarations over them.
    actions: orderActions(orderCommands(appFetcher)),
  }),
  bind('overview', { route: board => withView('/boards', board) }),
];

const MESSAGES = {
  'zh-CN': {
    ...zhCN,
    ...ORDER_WORDS['zh-CN'],
    ...ORDER_ACTION_WORDS['zh-CN'],
  },
  en: { ...ORDER_WORDS.en, ...ORDER_ACTION_WORDS.en },
};

export function Host({
  locale,
  children,
}: {
  locale: 'zh-CN' | 'en';
  children: ReactNode;
}) {
  return (
    <ViewHost
      engine={engine}
      router={useReactRouter()}
      locale={locale}
      messages={MESSAGES[locale]}
      bindings={BINDINGS}
      preset="porcelain"
      rememberColorMode="my-app.color-mode"
    >
      {children}
    </ViewHost>
  );
}
```

- `messages` is merged over the engine's own English words: hand it `zhCN` (spread, then rewords) for Chinese, and the definitions' words for each language. Switching `locale` and `messages` redraws what is open; nothing is reopened or queried again.
- Keep `bindings` stable (module scope, or `useMemo` over what they close over): a binding built in render is a new binding every render.
- Under a router the engine writes the open view to `?view=` and the open record to `?id=`, and keeps a hand-over and a board's filters and tab in the history entry. Do not sync them yourself; pass `instanceId` / `onInstanceChange` or `record.detail.open` only when the host keeps them elsewhere.
- Another router is the two-member `ViewRouter` (`location`, `go`); make it a new object when the location changes and the same one while it does not (`useMemo` over the location's parts).
- Nest a `ViewHost` only for a second engine or another binding of the same id; only the outermost one paints `<html>`.

## Pages

A page names what it shows and nothing else:

<!-- typecheck-context
declare function Header(props: { children: React.ReactNode }): React.ReactNode;
-->

```tsx
import { DataWorkbench, useViewNavigation } from '@ahoo-wang/wow-view-engine/ui';

export function OrdersPage() {
  return <DataWorkbench definitionId="orders" />;
}

/** The shell's places, from the bindings: each resource with a route. */
export function Places() {
  const places = useViewNavigation();
  return (
    <Header>
      <nav>
        {places.map(place => (
          <a
            key={place.id}
            href={place.path}
            aria-current={place.current ? 'page' : undefined}
          >
            {place.title}
          </a>
        ))}
      </nav>
    </Header>
  );
}
```

Use the host's own link and sidebar components in place of `<a>`; the engine has no page shell. A page that already has a `<main>` passes `landmark="region"` to the workbench.

Give the workbench a definite height — `height`, not `min-height`. It fills its container and keeps its pager at the bottom; in a container that only grows with its content it falls back to a 36rem floor (`--fve-workbench-min-height`) and the document scrolls on top of the table. Make the shell the viewport's height, the top bar fixed, and the content area the rest:

```css
.app { display: flex; flex-direction: column; height: 100svh; overflow: hidden; } /* bar: flex: none; content: flex: 1; min-height: 0 */
```

A page taller than the screen (a dashboard, a form) scrolls inside its own container (`overflow-y: auto`), never the document.

To show a decided view inside a business page, embed it (`EmbeddedView`, `EmbeddedDashboard`; README [Embedding](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#embedding-a-view-or-a-dashboard)); an embed never writes, and a page's lock is not a security boundary.

## The record reading

`reading` is how one record of the resource reads in its detail, wherever it opens (workbench, board panel, embed): `title(row)` names it in the header, `sections(context)` adds the host's own regions (a related view embedded, a form), `render(context)` replaces the body for a record better told in a layout of its own. See README [The record detail](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#the-record-detail-your-sections-and-the-record-in-your-address).

## Routes, tested

The engine resolves a way off (a board's panel, a follow-up, the way back) through the target's `route`. Test the bindings with the same function:

<!-- typecheck-context
import { BINDINGS } from './Host';
declare function expect(value: unknown): { toMatchObject(expected: unknown): void };
-->

```ts
import { resolveNavigation } from '@ahoo-wang/wow-view-engine/testing';

const bindingOf = (id: string) =>
  BINDINGS.find(binding => binding.definitionId === id);

const toQueue = {
  kind: 'view',
  definitionId: 'orders',
  instanceId: 'system:orders:to-ship',
  scopeFilter: null,
  filter: null,
} as const;
expect(resolveNavigation(toQueue, bindingOf)).toMatchObject({
  kind: 'route',
  path: '/orders?view=system%3Aorders%3Ato-ship',
  state: { handOver: toQueue },
});
```

## Theme and Content Security Policy

Pointers only; do not hand-write either:

- **Theme:** one choice on `ViewHost` — `theme="host"` with `@ahoo-wang/wow-view-engine/shadcn-bridge.css` for a host with a shadcn (Tailwind v4) theme, or `preset="…"` with that preset's stylesheet (and `brand`) for a host that wears the engine's. `colorMode="host"` where the app already paints dark mode (next-themes or its own toggle). The host's own chrome takes `className="fve-tokens"` on its shell, not on `<body>`. Details: README [Theme](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#theme-pick-one-of-two-paths) and the guide [Theming the View Engine](https://wow.ahoo.me/guide/typescript/view-engine-theming).
- **CSP:** serve the stylesheet as a file, publish the response's nonce in `<meta property="csp-nonce">`, and allow `blob:` in `img-src` for the chart PNG export. Details: README [Content Security Policy](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/README.md#content-security-policy).
