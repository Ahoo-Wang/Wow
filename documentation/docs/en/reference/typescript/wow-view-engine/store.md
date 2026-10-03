---
title: 'Persistence Port'
description: 'The ViewStore port, the six ViewStoreError codes, ViewPermissions with editSystem, MemoryViewStore and localStorageSnapshot — @ahoo-wang/wow-view-engine'
---

# Persistence Port

The view engine persists two things only: saved views (`ViewInstance`) and personal preferences. `ViewStore` is the only port a backend must satisfy — on a Wow service use [`WowViewStore`](../wow-view-store/); only a backend that is not Wow implements it itself. The one implementation the package ships is `MemoryViewStore`, for tests, examples and query-only use.

## ViewStore {#api-ViewStore}

Eight required methods and two optional ones (`changeAudience`, `permissions`); two consistency rules:

| Rule | Behaviour |
|---|---|
| Optimistic revision | Every write carries the `revision` it expects; a mismatch throws `ViewStoreError` with code `CONFLICT`, carrying the instance or preferences the server holds, and the UI offers reload, overwrite or save as |
| Idempotent `requestId` | Each logical write has one `requestId` in `WriteContext`; a retry after a timeout reuses it with the same payload, which is how the server deduplicates it |
| A fixed list order | `list` answers summaries in a fixed order: the backend's system views first (as it declares them), then the shared views, then the user's personal ones, each audience oldest first. With no preferences, the first of the list is the view that opens by default, so every store answers the same one |
| Permissions drive buttons only | `permissions` only decides which buttons a UI enables, and is synchronous because the application fetched it before creating the engine. Authorization, visibility filtering and deduplication are the server's |

| Method | What to know |
|---|---|
| `create` | Creates a view for the scope `input` names. `scope: 'system'` creates a **stored system view** ("Publish as system view", and Save as when the host grants `editSystem`) and answers it with `stored: true`; a store that keeps no system views refuses it (`FORBIDDEN` or `INVALID`). Publishing is a copy: the view it was made from is untouched. The engine never sends `stored` itself; the store sets it |
| `rename` | Stores the title trimmed. A title blank once trimmed, or longer than `MAX_VIEW_TITLE_LENGTH`, is refused as `INVALID` — so is one passed to `create`, and a config of more than `MAX_VIEW_CONFIG_BYTES` passed to `create` or `save`. The engine refuses both before sending; the store keeps them for every other caller |
| `changeAudience` | Optional. Moves a view to the other audience in place, id kept, so a board that shows it keeps showing it. A store without it has no such entry in the manager, and the engine refuses the command before anything is sent (`view.changeAudience.unsupported`). It keeps every other instance write's rules, and two of its own: **no change is no write** — asked for the audience the view already has, it answers the view as it is, revision unmoved (after the revision check, so a stale one still conflicts); **a view a shared board shows stays shared** — made personal it would be blank on that board for every other reader, so the store refuses it as `INVALID` with **`boards`, those boards' titles**. Deleting such a view stays allowed |

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

When you implement the port yourself, hold it to the repository's port conformance suite (see [testing helpers](./testing#conformance)).

## ViewStoreError {#api-ViewStoreError}

The single failure type of the port. A backend adapter maps its transport errors, HTTP status codes included, onto these six codes, and keeps what it mapped from: the error it caught as `cause`, the backend's own error code as `detail`. It lives in `model` rather than next to the port because both sides of the port speak it: a store raises it, and the runtime classifies a write outcome by it without depending on any store implementation.

| Code | Meaning | Carries |
|---|---|---|
| `CONFLICT` | The expected revision no longer matches | `instance` or `preferences`: what the server holds |
| `NOT_FOUND` | The view does not exist | |
| `FORBIDDEN` | Refused — a write to a configured system view, say, or moving a system view to another audience | |
| `INVALID` | Reached but refused: the payload is wrong | `boards`: from a refused `changeAudience`, the titles of the boards that keep the view shared |
| `UNAVAILABLE` | Not reached, or reached with an unknown outcome. A retry under the same `requestId` is safe | `reachable`: the server answered with an error of its own (a 5xx, a timeout, a refusal it reported), not the network; `storage`: the browser's own storage would not keep the write (full or turned off), so it never left the device |
| `UNSUPPORTED` | The backend has no view store at all (a server released before it), so no view can be read or written there, and none is "gone" | |

`detail.code` is the backend's own word for the failure — a Wow server's `errorCode` (`ViewAppRequired`, `ViewInvalid`, …) — so a host can tell apart what the port's code folds together. Diagnostics only: the engine decides by `code`. The engine says a store failure as an issue code such as `view.open.failed.not_found` (see [issue codes](./issues)).

`isViewStoreError` is structural: an `Error` named `ViewStoreError` with one of the port's codes, so an error raised by a second copy of this package, or by a store that builds the shape itself, is still recognised; the name keeps a stray `{ code: 'NOT_FOUND' }` from an HTTP library out.

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

What the current user may do with one definition's views. It drives buttons only; the real authorization is the server's (on Wow, the CoSec gateway's), and a host answers it by the same role the gateway checks.

- Bar `editSystem`, **silence allows**: `InstancePermissions.changeAudience` reads as allowed when absent too, and moving a view also asks the create permission of the audience it goes to (`createShared` to share it, `createPersonal` to take it back).
- **`editSystem` absent is false**: a system view reaches every user, so only a host that says so opens it. When true, the `instance(id)` answer applies to a stored system view as well (save, rename, delete), and new ones may be created (`create({ scope: 'system' })`, "Publish as system view"); a system view never moves audience, and one declared in code or in the backend's configuration stays read-only.

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

A synchronous map behind the port. It keeps the two consistency rules honest rather than convenient: a write with a stale revision conflicts, and a replayed `requestId` returns the first outcome instead of writing twice, so code written against it behaves the same against a real backend — as do the port's list order and the server's limits on a title and a config, which it refuses as `INVALID`.

System views come two ways. One seeded with `scope: 'system'` and no `stored` is a configured one, as the backend's configuration serves it: every write to it is refused (`FORBIDDEN`). One created with `scope: 'system'`, or seeded with `stored: true`, is a stored one: saved, renamed and deleted like a shared view, never moved to another audience. Like the server, the store asks no permission of its own: `editSystem` is the host's to grant and the engine's to ask.

With a snapshot, several stores may share one stored state — two tabs over one `localStorage`. The rule between them is the backend's: **re-read, then compare revisions, one instance (or one definition's preferences) at a time.** Before every write the store reloads the stored state, so a write merges into what the other writer left, and a write whose revision the other writer has moved past is refused as `CONFLICT`. A write the snapshot could not keep is undone and rejected as `UNAVAILABLE`: it did not land, so a retry under the same `requestId` is safe.

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

A `MemoryViewStore` snapshot kept in `localStorage` under `key`: the whole store as one JSON document, `{ instances, preferences }`. For development and single-user hosts until a real backend holds the views; it is one browser's, not a second store.

- **A write storage refuses is a failed write**: with a full quota or blocked storage, the store undoes the write and rejects it as `UNAVAILABLE` (marked `storage`), which the engine reports to `onError` and the UI says as the browser's storage being full or turned off rather than as a result that never came back. It never pretends a view was kept.
- **Tabs do not overwrite each other**: the document is re-read and the revision checked before every write, and another tab's `storage` event for `key` reloads the store, so what that tab saved is listed without waiting for a write.
- A missing, unreadable or malformed document reads as nothing stored — the store starts empty rather than the page breaking — and the next write replaces it.

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

## The full working version

- Storybook's [integration walk-through](/storybook/?path=/docs/view-engine-接入导览--docs) keeps its views in a `MemoryViewStore`; the compensation console keeps them on the view store server through [`WowViewStore`](../wow-view-store/).
- Source files: [`store/ViewStore.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/store/ViewStore.ts), [`store/MemoryViewStore.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/store/MemoryViewStore.ts), [`store/localStorageSnapshot.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/store/localStorageSnapshot.ts), [`model/storeError.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-view-engine/src/model/storeError.ts).
