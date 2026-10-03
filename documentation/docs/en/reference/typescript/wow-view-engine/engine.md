---
title: 'Engine and Resources'
description: 'ViewEngine, its options and resources, ViewSource and RuntimeEnvironment — @ahoo-wang/wow-view-engine'
---

# Engine and Resources

`ViewEngine` is the one object a host builds once, at application start, and shares across the application: it registers the definitions (`resources`), holds the `store` that keeps saved views, manages the open runtimes, and is the only entry point for the commands that open, create, save, rename and delete. There is one entry point because every write takes one path — so the default UI and a hand-built one behave the same, and every outcome that is not a success lands in the same three recovery actions (`retryWrite`, `abandonWrite`, `resolveConflict`).

Construct it; do not extend it. It is `@sealed`, and its base class `EngineResources` is how the file is split, not an extension point.

Guides: [Getting Started with the View Engine](../../../guide/typescript/view-engine-getting-started.md) (step 6 builds the engine and its resources); [Fitting the View Engine into a Host](../../../guide/typescript/view-engine-host.md) (why one engine serves the whole application); [View Engine Core Concepts](../../../guide/typescript/view-engine-concepts.md) (the views and runtimes the engine manages).

## Building an engine

Each resource pairs a definition with the source of its data; every page over one source shares its queries, preferences and descriptors. `MemoryViewStore` forgets on reload, which is enough for development and tests; on a Wow service use [`WowViewStore`](../wow-view-store/) instead.

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
  // A failure is still shown where it happens; this copy is for your logs.
  environment: browserRuntimeEnvironment({
    onError: ({ kind, error }) => console.error(`[view-engine] ${kind}`, error),
  }),
});
```

## Options {#api-ViewEngineOptions}

| Option | Role |
|---|---|
| `resources` | Every resource the host registers: each definition, with the source of its data. A board's definition has no source — it queries nothing of its own |
| `store` | The port that keeps saved views and preferences ([persistence port](./store)) |
| `resolveOptions` | Where the remote candidates of `reference` fields come from, by the same key |
| `kinds` | The field kind registry; the built-in kinds when left out ([definitions and field kinds](./definitions#api-FieldKind)) |
| `text` | How the definitions' keys (`text(key)`) are said in the language the engine starts in, for a host with no Provider; under a `ViewHost` or `MessagesProvider` they are said in its language. Left out, a key reads as itself until a language is set |
| `limits` | The host's budgets over `DEFAULT_RUNTIME_LIMITS`; what is left out keeps its default. The source budgets (`maxPageSize`, `maxPageWindow`, `maxAnalysisRows`, `maxQueryFilterNodes`, `maxFilterValues`) come from a source's descriptor, and one given here only lowers it — leave them out unless the engine should ask for less than the server admits |
| `environment` | The only interface between a runtime and its host: clock, time zone, timers, page visibility, failure reports ([`RuntimeEnvironment`](#api-RuntimeEnvironment)) |
| `newId` | Makes idempotency keys; overridden in tests to keep them readable |
| `onIssue` | Findings with no caller to reject and nothing thrown behind them: a definition's admission, a list entry that was dropped, a change listener that threw. Failures — a query, a store call, an export, a render — go to `environment.onError` instead. Left out, a development build (`NODE_ENV` of `development`) writes them to the console, grouped by resource, each with how to fix it; any other build drops them |

Why two channels, `onIssue` and `onError`: the first is the host's declarations being wrong, fixed in code; the second is a failure met at run time, which the screen has already said in place — this copy is for monitoring.

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

One thing a host registers: a definition, and where its data comes from. A data definition's `source` is where its rows come from; a board has none.

```ts
export interface ViewResource {
  definition: ViewDefinition;
  source?: ViewSource;
}
```

## Data source {#api-ViewSource}

`ViewSource` is three of `QueryApi`'s methods, so wow-client's snapshot query client satisfies it as it is; another backend, or a test, writes one by hand (a test uses [`/testing`'s `memorySource`](./testing#api-memorySource)).

- Cancellation is an `AbortController` rather than the `AbortSignal` the other ports take, because that is what `QueryApi` accepts, and this port exists so that a wow-client query client plugs in unchanged. The controller is the engine's: it aborts it when a newer request takes this one's place or the runtime is disposed. A source written by hand reads `abortController?.signal` and never aborts it.
- `aggregate` answers `RecordData[]`: every value arrives as `unknown` and is read through a field's kind, rather than trusting a row type the caller asserts.
- `describe` is optional: the source's capability descriptor. The engine reads it once per source before the first view over it runs, narrows each definition to what it admits and takes its limits, and checks it again on its own schedule; asked with the version already held, an unchanged one answers `notModified`. wow-client's `describeSnapshot` or `describeEventStream` fits it as it is. Left out, a view runs on the definition and the default limits alone.

```ts
export interface ViewSource {
  aggregate(query: AggregationQuery, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<RecordData[]>;
  cursor(query: CursorQuery, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<CursorPage<RecordData>>;
  describe?(previous?: string, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<QueryDescriptorResult>;
  paged(query: FilterPagedQuery, attributes?: Record<string, unknown>, abortController?: AbortController): Promise<PagedList<RecordData>>;
}
```

A source over a Wow service is Storybook's integration walk-through's [`wowSource.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowSource.ts): each of the four methods handed to the snapshot query client and the descriptor client.

## Runtime environment {#api-RuntimeEnvironment}

The only interface between a runtime and its host. Relative dates, the refresh timer, the "is anyone looking" question and the failures a host wants to hear about all pass through it, so the layers from `model` to `store` stay free of the DOM and of the system clock, and a test drives time without faking globals. In a browser use `/react`'s `browserRuntimeEnvironment(overrides?)`; without a screen, the root entry's `defaultRuntimeEnvironment(overrides?)`.

- `timeZone`: the IANA zone relative and preset dates are resolved in.
- `onError`: told once of every failure the engine meets doing its work — a query, a store call, an export, a render, a chart, a declared action's command. The failure is still answered on screen; this is the host's copy. Whatever it throws is dropped, and left out, nothing is logged anywhere.

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

The members a host uses most; the full signature is in the [API report](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/api/root.api.md). The default UI already calls them for you; call them yourself when you build your own UI or write a test.

| Member | Role |
|---|---|
| `list(definitionId)` | The system views declared in code first, then what the store holds. An id in the reserved namespace is dropped and reported: only a definition declares a system view. When the store fails, `failed` says why, and the declared system views are still in `items` |
| `open(instanceId, options?)` | Opens a saved view, or a view declared in code without touching the store. `options.scopeFilter` is admitted with the config, in force from the first query |
| `create(definitionId, input)` | An unsaved view, carrying a complete config (from `defaultRecordConfig` and its kin), executed at once. No permission is asked here: the first `save` asks |
| `save(runtime)`, `saveAs(runtime, input)` | The first save creates, later saves overwrite; both need a draft without errors. `saveAs` with `scope: 'system'` makes a stored system view, which needs `editSystem` |
| `rename`, `delete`, `changeAudience` | Renaming carries no config, so a draft with errors does not block it. `changeAudience` needs the store's optional method of that name, or it is refused (`view.changeAudience.unsupported`) |
| `publishAsSystem(id)` | "Publish as system view": copies the saved view — as saved, not an open draft — into a stored system view; needs `editSystem` |
| `retryWrite`, `abandonWrite`, `resolveConflict` | The three recovery actions: replay under the original `requestId`; drop the outcome and keep the draft; `reload` the server's state, or `overwrite` against the revision the conflict reported (a new logical write, so a new `requestId`) |
| `close(runtime)` | Closes one open view: disposes it and drops it from the registry. Calling only `runtime.dispose()` leaves the engine holding a dead runtime |
| `subscribe(listener)` | Told whenever a write changes what a definition's list holds; answers the way to stop listening |
| `permissions(definitionId)` | The permissions the store gives ([`ViewPermissions`](./store#api-ViewPermissions)) |
| `setText(text, language?)` | Changes how the definitions' keys are said, and in which language; `ViewHost` calls it for you |

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

### What opening and creating take {#api-OpenOptions}

- `scopeFilter`: an outer condition in the view's own field names, admitted with the config — an order page scoped to one customer never lets an unscoped query leave, and never shows a row outside its scope.
- `definitionId`: the definition whose views the caller draws; an instance of another is refused before anything runs (`view.open.other-definition`).
- `tab`, `filters`, `held`: for a dashboard only — the tab it opens on, what its filters start at, the filters the host holds.

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

`CreateInput.scope` is an audience only (`personal` or `shared`): a user creates for an audience, and only a definition declares a system view.

### ViewListing {#api-ViewListing}

```ts
export interface ViewListing {
  failed: Issue | null;
  items: ViewInstanceSummary[];
}
```

## The full working version

- Storybook's [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs) wires an order object in the same steps; the bottom of the page is it running.
- Source files: [`ordersEngine.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/ordersEngine.ts), [`wowSource.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/wowSource.ts); the engine itself is [`src/runtime/viewEngine.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/runtime/viewEngine.ts).
