---
title: 'Testing Helpers'
description: 'The /testing entry: memorySource, matches, admit, resolveNavigation, actionHarness, and the ViewStore port conformance suite — @ahoo-wang/wow-view-engine/testing'
---

# Testing Helpers

`/testing` is for a host's own tests: without a browser or a server, check what the host declared — whether its definitions are admitted, whether every key has words, whether its actions' business rules hold, where a route resolves. It runs the engine's own logic for each, not an approximation written beside it.

It needs no React or DOM. `memorySource` answers with Wow's query semantics through the optional peer `mingo`, which a project that uses it installs itself.

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

test('the definition is admitted, every key worded', () => {
  assert.deepEqual(
    admit([ordersDefinition], { order: ordersDescriptor }, { text: key => ORDERS_WORDS[key] }),
    [],
  );
});

test('only a paid order ships, and says why not', () => {
  const rows = orders.map(data => ({ key: String(data.aggregateId), data }));
  const harness = actionHarness(orderActions, rows);
  assert.equal(harness.state('ship', 'SO-0001').reason, null);
  assert.equal(harness.state('ship', 'SO-0003').reason, text('orders.notPaid'));
  // One order ships at a press; a selection is asked first.
  assert.equal(harness.asks('ship', 'row').asks, false);
});
```

Guides: [Fitting the View Engine into a Host](../../../guide/typescript/view-engine-host.md#testing) (testing a host's wiring with `/testing`); [Declared Actions](../../../guide/typescript/view-engine-actions.md#harness) (testing actions' rules with `actionHarness`); [Writing a Definition](../../../guide/typescript/view-engine-definitions.md) (self-checking a definition with `admit`); [Where Views Live](../../../guide/typescript/view-engine-storage.md) (running the conformance suite on your own store).

## admit {#api-admit}

Every finding the engine would have about `definitions` over `descriptors` (the snapshots, by `DataViewDefinition.source`), each with the definition it is about; `[]` for a host's declarations that hold.

Each definition is admitted as the engine admits it when registered — its keys said in `text`, its own rules, its boards against every other definition, each judged as declared, keys and all — and each data definition narrowed to its descriptor as a source would narrow it, what that takes away said too. A definition over a source with no descriptor given is said to be, since its capabilities go unchecked. A host's list of resources (`{ definition, source }`) is passed as it is. When at least one resource passed carries its `source`, a data definition passed as a resource is held to the engine's start-up check too: some resource passed registers a source under its `source` key, or it is `definition.source.unregistered`. A list of `{ definition }` alone, or of bare definitions, says nothing of sources and is not checked for them.

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

A host's declared actions over some records, without a screen: the engine's own reading of them — which place offers which, whether a record takes one now and why not, how a selection splits, what the confirmation and the form ask, when the rules change on their own — so a host's unit test pins one business rule in a line.

| Member | Role |
|---|---|
| `ids`, `at(place)` | Every action's id, in the order declared; the ids offered at a place |
| `state(id, key, input?)` | One record's state: `available`, `hidden`, `reason` (as the host wrote it, a key staying a key; `null` when able) |
| `bulk(id, keys?, input?)` | How a selection splits: the able, the refused, and the refused by reason (the commonest first) — what the dialog lists |
| `asks(id, place, input?)` | Whether pressing it at a place asks first, and what: the declared question, or `null` when the engine asks in its own words (a selection with no `confirm`) |
| `form(id)`, `choice(id)`, `missing(id, input)` | The form's fields; whether it is a choice; the required fields `input` leaves blank (the form's `initial` under `input`) |
| `changesAt(key?)` | The soonest time after `now` a record's availability flips on its own |
| `run(id, key, input?)` | Sends one record the command as the engine would: refused with the reason (`ActionRefusedError`) where it does not take it, else the host's `run` itself |

`options.now` is the time the rules are asked at; `Date.now()` when left out.

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

A `ViewSource` over documents held in memory that answers each query the way a Wow service over MongoDB does — filter, sort, page, projection and aggregation — so a host's test sees the engine's real queries answered by the semantics the engine is built on, not a canned answer.

- The documents are the snapshots (or event streams) as the service returns them: `aggregateId`, `ownerId` and `deleted` on the envelope, the state under `state`, times as epoch milliseconds. A document with `deleted: true` is left out unless the filter carries a `DELETION` condition. A cursor is an offset, as opaque to the caller as Wow's.
- What it cannot answer — an operator or shape it has no reading of, such as `ID`, `TENANT_ID`, `SPACE_ID` or the calendar filters (`TODAY`, …) the engine never sends — is refused with an error, so a query a test starts to send fails rather than getting a plausible wrong answer. The documents are read, never written; a test may change them between queries.

| Option | Role |
|---|---|
| `now` | The service's clock, in epoch milliseconds, which `BEFORE_NOW` and `AFTER_NOW` compare against; a test pins it to the moment its data is built around, so "after now" answers the same on every run |
| `timeField` | A time column every document holds as epoch milliseconds, the way a Wow snapshot keeps `firstEventTime`. The documents are kept in its order, and a range on it in the filter's top-level AND is cut out by binary search first — for large sets |
| `remember` | Answers a repeated aggregation from memory, keyed by the query, each caller getting its own copy. Only for documents that never change; off by default, so a test that edits a document sees the edit in the next answer |

`matches(document, filter, options?)` asks whether one document matches a filter as `memorySource` reads it — a test compares a condition of its own (the one an old screen used, say) with what a view matched. Unlike a source, it assumes nothing about deletion: without a `DELETION` condition a deleted document is a candidate too.

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

`to` through the route of the definition it leads to, where it has one (`bind`'s `route`, looked up by `bindingOf`): what the host's `navigate` is handed. Pure, and in `/testing` too, so a host's tests of its bindings run the engine's own resolution.

```ts
export declare function resolveNavigation(to: ViewNavigation, bindingOf: (definitionId: string) => { route?: ViewRouteOf } | undefined): ViewDestination;
```

## The ViewStore port conformance suite {#conformance}

A backend that implements [`ViewStore`](./store#api-ViewStore) itself is held to the port's conformance suite: one set of cases every implementation passes — listing and reading, revision conflicts, `requestId` replays, audience changes, system views. `MemoryViewStore` runs it in the engine, `WowViewStore` against the view store server in `typescript/integration-test`.

It is **a test file of the repository, not part of the published package**: in `/testing` it would carry a test framework into a published entry. So it is imported by workspace path and depends only on `vitest` and on engine modules with no runtime imports; a backend outside the repository copies the file, or writes its own cases after it. What it assumes of the backend, and nothing more: every case works in a fresh definition id, so a shared server needs no reset; revisions are compared for change and no change only, never ordered or parsed — except that untouched preferences answer `'0'`. A capability the subject does not declare skips its cases, by name.

<!-- typecheck: skip — imports a repository test file by path, which is not published -->

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
  // One server for the whole file is fine: every test uses fresh ids.
  connect: () => ({ owner }) => new WowViewStore({ fetcher: actingAs(owner) }),
});
```

| Capability | Meaning |
|---|---|
| `owners` | Stores opened for two owners are two users: each sees the other's shared views and not their personal ones, and keeps preferences of their own. `false` for a single-user store, and the cases about two users are skipped |
| `personalViews` | A personal view can be created at all (a host nobody signs in to has none) |
| `changeAudience` | The store implements the optional `changeAudience` |
| `idempotentCreate` | A `create` replayed under its `requestId` answers the first outcome rather than making a second view. The Wow server does not deduplicate `create` (it generates the id), and `WowViewStore` keeps it within one store by asking the replay route first; a store that keeps it not even so declares `false`, and that case is skipped — every other write's replay stays mandatory |
| `systemViews` | A definition the backend serves at least one read-only system view for; left out when it serves none, and the case that every write to one is refused is skipped |
| `storedSystemViews` | The store keeps system views of its own: `create` with `scope: 'system'` makes one, answered and listed with `stored: true` |

## The full working version

- Storybook's integration walk-through's [`integration.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/integration/integration.test.ts) holds the walk-through's declarations to `admit` and `actionHarness`.
- The conformance suite: [`test/conformance/viewStoreConformance.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/test/conformance/viewStoreConformance.ts), and `WowViewStore`'s [`wowViewStore.conformance.test.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/integration-test/test/view-store/wowViewStore.conformance.test.ts).
- Source files: [`src/testing/`](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-view-engine/src/testing).
