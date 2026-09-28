/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  isSystemInstanceId,
  isSystemScope,
  toSummary,
  ViewStoreError,
  type ViewConfig,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPreferences,
} from '../model/index.js';
import {
  emptyPreferences,
  type ViewPermissions,
  type ViewStore,
  type WriteContext,
} from './ViewStore.js';

export interface MemoryViewStoreOptions {
  instances?: ViewInstance[];
  preferences?: Record<string, ViewPreferences>;
  permissions?: (definitionId: string) => ViewPermissions;
  /** Lets an example keep its data across reloads; not a second store. */
  snapshot?: MemorySnapshot;
}

/**
 * Where a `MemoryViewStore` keeps its state between page loads: the whole
 * state, read and written as one document, synchronously.
 *
 * The store reads it when it is made, again before every write, and again
 * whenever `subscribe` says another writer changed it, so every write is
 * checked against what is stored now rather than what this store last saw
 * (see `MemoryViewStore`). `load` answers `undefined` for nothing readable,
 * and the store then keeps what it holds.
 *
 * `save` throws when the state could not be kept — storage full, blocked,
 * refused. The store then undoes the write in memory and rejects it with a
 * `ViewStoreError` (`UNAVAILABLE`), so nobody is told a view was saved that
 * the next page load will not have.
 */
export interface MemorySnapshot {
  load(): MemoryState | undefined;
  save(state: MemoryState): void;
  /** Tells the store that another writer changed the state; answers the unsubscribe. */
  subscribe?(listener: () => void): () => void;
}

export interface MemoryState {
  instances: ViewInstance[];
  preferences: Record<string, ViewPreferences>;
}

/**
 * The one implementation this package ships: a synchronous map behind the
 * port, for tests, examples and query-only use.
 *
 * It keeps the two consistency rules honest rather than convenient. A write
 * with a stale revision conflicts, and a replayed `requestId` returns the
 * first outcome instead of writing twice, so code written against it behaves
 * the same against a real backend.
 *
 * With a snapshot, several stores may share one stored state — two tabs over
 * one `localStorage`. The rule between them is the backend's: **re-read, then
 * compare revisions, one instance (or one definition's preferences) at a
 * time.** Before every write the store reloads the stored state, so a write
 * merges into what the other writer left — a board created in one tab
 * survives a reorder in the other, and ids never collide — and a write whose
 * `revision` the other writer has already moved past is refused as
 * `CONFLICT`, carrying what is stored, as any stale write is. Nothing is ever
 * overwritten because this store's copy was old. A write the snapshot could
 * not keep is undone and rejected as `UNAVAILABLE`: it did not land, so a
 * retry under the same `requestId` is safe.
 */
export class MemoryViewStore implements ViewStore {
  private readonly instances = new Map<string, ViewInstance>();
  private readonly preferences = new Map<string, ViewPreferences>();
  /** Outcome per logical instance write, so a retry is idempotent. */
  private readonly outcomes = new Map<string, ViewInstance | null>();
  /** The same rule for preferences, whose outcome is no instance. */
  private readonly preferenceOutcomes = new Map<string, ViewPreferences>();
  private readonly snapshot?: MemorySnapshot;
  permissions?: (definitionId: string) => ViewPermissions;
  private sequence = 0;

  constructor(options: MemoryViewStoreOptions = {}) {
    // Left undefined when the caller declared none, which the port reads as
    // "everything is allowed".
    if (options.permissions) this.permissions = options.permissions;
    this.snapshot = options.snapshot;

    const restored = options.snapshot?.load();
    this.restore({
      instances: restored?.instances ?? options.instances ?? [],
      preferences: restored?.preferences ?? options.preferences ?? {},
    });
    options.snapshot?.subscribe?.(() => this.reload());
  }

  list(definitionId: string): Promise<ViewInstanceSummary[]> {
    const summaries = [...this.instances.values()]
      .filter(instance => instance.definitionId === definitionId)
      .map(toSummary);
    return Promise.resolve(summaries);
  }

  get(id: string): Promise<ViewInstance> {
    const instance = this.instances.get(id);
    return instance
      ? Promise.resolve(copy(instance))
      : Promise.reject(new ViewStoreError('NOT_FOUND', `No such view: ${id}`));
  }

  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    context: WriteContext,
  ): Promise<ViewInstance> {
    const replayed = this.outcomes.get(context.requestId);
    if (replayed) return Promise.resolve(copy(replayed));
    if (isSystemScope(input.scope))
      return Promise.reject(
        new ViewStoreError('INVALID', 'System views are declared in code'),
      );
    this.reload();

    // Seeded instances occupy ids too, so the counter walks past anything
    // already taken rather than overwriting it.
    let id: string;
    do {
      this.sequence += 1;
      id = `${input.definitionId}-${this.sequence}`;
    } while (this.instances.has(id));
    if (isSystemInstanceId(id))
      return Promise.reject(
        new ViewStoreError('INVALID', `Reserved id namespace: ${id}`),
      );

    const instance: ViewInstance = { ...copy(input), id, revision: '1' };
    this.instances.set(id, instance);
    return this.commit(context, instance, () => this.instances.delete(id));
  }

  save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.update(id, revision, context, current => ({
      ...current,
      config: copy(config),
    }));
  }

  rename(
    id: string,
    title: string,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.update(id, revision, context, current => ({
      ...current,
      title,
    }));
  }

  delete(id: string, revision: string, context: WriteContext): Promise<void> {
    if (this.outcomes.has(context.requestId)) return Promise.resolve();
    this.reload();
    const current = this.instances.get(id);
    if (!current)
      return Promise.reject(
        new ViewStoreError('NOT_FOUND', `No such view: ${id}`),
      );
    // Deleting a system view is refused exactly as overwriting one is: it is
    // declared in code or by operations, and no user write reaches it.
    if (isSystemScope(current.scope))
      return Promise.reject(
        new ViewStoreError('FORBIDDEN', 'System views are read-only'),
      );
    const conflict = this.expect(current, revision);
    if (conflict) return Promise.reject(conflict);

    this.instances.delete(id);
    const refused = this.persist(() => this.instances.set(id, current));
    if (refused) return Promise.reject(refused);
    this.outcomes.set(context.requestId, null);
    return Promise.resolve();
  }

  getPreferences(definitionId: string): Promise<ViewPreferences> {
    return Promise.resolve(
      this.preferences.get(definitionId) ?? emptyPreferences(),
    );
  }

  setPreferences(
    definitionId: string,
    preferences: ViewPreferences,
    context: WriteContext,
  ): Promise<ViewPreferences> {
    // A replay answers with the outcome the first attempt produced, as every
    // other write does: a retry after a lost answer is the same logical
    // write, and reporting a conflict for it would be a lie.
    const replayed = this.preferenceOutcomes.get(context.requestId);
    if (replayed) return Promise.resolve(replayed);
    this.reload();

    const held = this.preferences.get(definitionId);
    const current = held ?? emptyPreferences();
    if (current.revision !== preferences.revision)
      return Promise.reject(
        new ViewStoreError(
          'CONFLICT',
          `Preferences for ${definitionId} changed`,
          { preferences: current },
        ),
      );

    const next: ViewPreferences = {
      ...preferences,
      revision: String(Number(current.revision) + 1),
    };
    this.preferences.set(definitionId, next);
    const refused = this.persist(() =>
      held
        ? this.preferences.set(definitionId, held)
        : this.preferences.delete(definitionId),
    );
    if (refused) return Promise.reject(refused);
    this.preferenceOutcomes.set(context.requestId, next);
    return Promise.resolve(next);
  }

  private update(
    id: string,
    revision: string,
    context: WriteContext,
    change: (current: ViewInstance) => ViewInstance,
  ): Promise<ViewInstance> {
    const replayed = this.outcomes.get(context.requestId);
    if (replayed) return Promise.resolve(copy(replayed));
    this.reload();

    const current = this.instances.get(id);
    if (!current)
      return Promise.reject(
        new ViewStoreError('NOT_FOUND', `No such view: ${id}`),
      );
    if (isSystemScope(current.scope))
      return Promise.reject(
        new ViewStoreError('FORBIDDEN', 'System views are read-only'),
      );
    const conflict = this.expect(current, revision);
    if (conflict) return Promise.reject(conflict);

    const next: ViewInstance = {
      ...change(current),
      revision: String(Number(current.revision) + 1),
    };
    this.instances.set(id, next);
    return this.commit(context, next, () => this.instances.set(id, current));
  }

  private expect(
    current: ViewInstance,
    revision: string,
  ): ViewStoreError | undefined {
    return current.revision === revision
      ? undefined
      : new ViewStoreError(
          'CONFLICT',
          `View ${current.id} moved to revision ${current.revision}`,
          { instance: current },
        );
  }

  private commit(
    context: WriteContext,
    instance: ViewInstance,
    undo: () => void,
  ): Promise<ViewInstance> {
    const refused = this.persist(undo);
    if (refused) return Promise.reject(refused);
    this.outcomes.set(context.requestId, instance);
    return Promise.resolve(copy(instance));
  }

  /**
   * Hands the state to the snapshot. When it could not be kept, the change
   * just made is undone — memory goes back to what is stored — and the
   * refusal is answered, so the write rejects rather than being reported as
   * saved. No outcome is remembered for it, so a retry writes again.
   */
  private persist(undo: () => void): ViewStoreError | undefined {
    if (!this.snapshot) return undefined;
    try {
      this.snapshot.save({
        instances: [...this.instances.values()].map(instance => copy(instance)),
        preferences: Object.fromEntries(this.preferences),
      });
      return undefined;
    } catch (error) {
      undo();
      return new ViewStoreError(
        'UNAVAILABLE',
        `The view store could not keep this write: ${reasonOf(error)}`,
      );
    }
  }

  /**
   * Takes the stored state as it is now, when there is one to read: before a
   * write, so the write is checked against it, and when another writer says
   * it changed. Nothing readable leaves what the store holds as it is.
   */
  private reload(): void {
    const stored = this.snapshot?.load();
    if (stored) this.restore(stored);
  }

  private restore(state: MemoryState): void {
    this.instances.clear();
    this.preferences.clear();
    for (const instance of state.instances)
      this.instances.set(instance.id, copy(instance));
    for (const [definitionId, preferences] of Object.entries(state.preferences))
      this.preferences.set(definitionId, preferences);
  }
}

/** What a refusal said — a `DOMException` is an `Error` only in some realms. */
function reasonOf(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const { name, message } = error as { name?: unknown; message?: unknown };
    if (typeof message === 'string')
      return typeof name === 'string' ? `${name}: ${message}` : message;
  }
  return String(error);
}

/**
 * A structural copy, so nothing a caller holds is the object the store keeps.
 * A config handed in or read back is a plain JSON tree, and a caller editing
 * one it still has a reference to must not change what is stored.
 */
function copy<T>(value: T): T {
  return structuredClone(value);
}
