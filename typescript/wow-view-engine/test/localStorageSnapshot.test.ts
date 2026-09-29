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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  emptyPreferences,
  isViewWriteError,
  localStorageSnapshot,
  MemoryViewStore,
  ViewEngine,
  type LocalStorageSnapshotOptions,
  type ViewErrorEvent,
  type ViewInstance,
} from '../src/index.js';
import {
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
  resourcesOf,
} from './fixtures.js';

const KEY = 'wow-compensation-dashboard:views';

function board(title = 'Board'): Omit<ViewInstance, 'id' | 'revision'> {
  return {
    definitionId: 'orders',
    title,
    scope: 'personal',
    config: recordConfig(),
  };
}

/** The stored document as it is, read back as the next page load would. */
function stored(): { instances: ViewInstance[]; preferences: object } {
  return JSON.parse(localStorage.getItem(KEY) ?? 'null') as {
    instances: ViewInstance[];
    preferences: object;
  };
}

/** One tab: its own store over the one `localStorage`, its own events. */
function tab(events: LocalStorageSnapshotOptions['events'] = null) {
  return new MemoryViewStore({
    snapshot: localStorageSnapshot(KEY, { events }),
  });
}

function quotaExceeded(): DOMException {
  return new DOMException('The quota has been exceeded.', 'QuotaExceededError');
}

beforeEach(() => localStorage.clear());
afterEach(() => localStorage.clear());

describe('localStorageSnapshot: a write storage refuses', () => {
  it('rejects a create as UNAVAILABLE, keeps nothing, and a retry writes it', async () => {
    const store = tab();
    const setItem = vi
      .spyOn(Storage.prototype, 'setItem')
      .mockImplementationOnce(() => {
        throw quotaExceeded();
      });

    const refused = await store
      .create(board(), { requestId: 'c-1' })
      .catch((error: unknown) => error);

    expect(refused).toMatchObject({
      name: 'ViewStoreError',
      code: 'UNAVAILABLE',
      storage: true,
      message: expect.stringContaining('QuotaExceededError'),
    });
    // Undone in memory as well: nothing claims a view the next load lacks.
    expect(await store.list('orders')).toEqual([]);
    expect(localStorage.getItem(KEY)).toBeNull();

    // The write did not land, so the same request writes it once there is room.
    const created = await store.create(board(), { requestId: 'c-1' });
    expect(setItem).toHaveBeenCalledTimes(2);
    expect(stored().instances.map(({ id }) => id)).toEqual([created.id]);
  });

  it('undoes a save, a rename, a delete and a preference write it could not keep', async () => {
    const store = tab();
    const kept = await store.create(board(), { requestId: 'c-1' });
    const before = localStorage.getItem(KEY);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });

    await expect(
      store.save(kept.id, recordConfig({ pageSize: 50 }), '1', {
        requestId: 's',
      }),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(
      store.rename(kept.id, 'Renamed', '1', { requestId: 'r' }),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(
      store.delete(kept.id, '1', { requestId: 'd' }),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(
      store.setPreferences(
        'orders',
        { order: [kept.id], defaultInstanceId: kept.id, revision: '0' },
        { requestId: 'p' },
      ),
    ).rejects.toMatchObject({ code: 'UNAVAILABLE' });

    expect(await store.get(kept.id)).toEqual(kept);
    expect(await store.getPreferences('orders')).toEqual(emptyPreferences());
    expect(localStorage.getItem(KEY)).toBe(before);
  });

  it('is a failed save on screen and a store failure to onError', async () => {
    const errors: ViewErrorEvent[] = [];
    const store = new MemoryViewStore({
      instances: [{ ...board('Mine'), id: 'orders-1', revision: '1' }],
      snapshot: localStorageSnapshot(KEY, {
        events: null,
        storage: () => ({
          getItem: () => null,
          setItem: () => {
            throw quotaExceeded();
          },
        }),
      }),
    });
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store,
      environment: {
        ...testEnvironment().environment,
        onError: event => void errors.push(event),
      },
    });
    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });

    const error = await engine.save(runtime).catch((caught: unknown) => caught);

    expect(isViewWriteError(error) && error.state.kind).toBe('unknown');
    // Said as the browser's storage refusing it, not as a lost answer.
    expect(isViewWriteError(error) && error.state).toMatchObject({
      issue: {
        code: 'view.write.storage',
        params: { reason: expect.stringContaining('QuotaExceededError') },
      },
    });
    expect(errors).toEqual([
      expect.objectContaining({
        kind: 'store',
        error: expect.objectContaining({ code: 'UNAVAILABLE' }),
      }),
    ]);
    expect(runtime.getSnapshot().dirty).toBe(true);
  });
});

describe('localStorageSnapshot: two tabs over one storage', () => {
  it("keeps tab A's board when tab B, holding an older state, reorders", async () => {
    const a = tab();
    const b = tab();
    const first = await a.create(board('First'), { requestId: 'a-1' });
    const b0 = await b.getPreferences('orders');

    // B made its copy before A's second board, and never heard of it.
    const second = await a.create(board('Second'), { requestId: 'a-2' });
    await b.setPreferences(
      'orders',
      { ...b0, order: [first.id] },
      { requestId: 'b-1' },
    );

    expect(stored().instances.map(({ title }) => title)).toEqual([
      'First',
      'Second',
    ]);
    expect(stored().preferences).toEqual({
      orders: { order: [first.id], defaultInstanceId: null, revision: '1' },
    });
    expect(await tab().get(second.id)).toMatchObject({ title: 'Second' });
  });

  it('gives tab B a new id rather than the one tab A just took', async () => {
    const a = tab();
    const b = tab();

    const fromA = await a.create(board('A'), { requestId: 'a-1' });
    const fromB = await b.create(board('B'), { requestId: 'b-1' });

    expect(fromB.id).not.toBe(fromA.id);
    expect(stored().instances.map(({ title }) => title)).toEqual(['A', 'B']);
  });

  it("refuses tab B's stale save as a conflict carrying tab A's version", async () => {
    const a = tab();
    const created = await a.create(board(), { requestId: 'a-1' });
    const b = tab();
    await a.save(created.id, recordConfig({ pageSize: 50 }), '1', {
      requestId: 'a-2',
    });

    const conflict = await b
      .save(created.id, recordConfig({ pageSize: 10 }), '1', {
        requestId: 'b-1',
      })
      .catch((error: unknown) => error);

    expect(conflict).toMatchObject({
      code: 'CONFLICT',
      instance: { revision: '2', config: { pageSize: 50 } },
    });
    expect(stored().instances[0]).toMatchObject({
      revision: '2',
      config: { pageSize: 50 },
    });
  });

  it("refuses tab B's stale reorder when tab A already moved the preferences", async () => {
    const a = tab();
    const b = tab();
    await a.setPreferences(
      'orders',
      { order: ['x'], defaultInstanceId: null, revision: '0' },
      { requestId: 'a-1' },
    );

    await expect(
      b.setPreferences(
        'orders',
        { order: ['y'], defaultInstanceId: null, revision: '0' },
        { requestId: 'b-1' },
      ),
    ).rejects.toMatchObject({
      code: 'CONFLICT',
      preferences: { order: ['x'], revision: '1' },
    });
  });

  it("reloads on another tab's storage event, before any write", async () => {
    const a = tab();
    const b = tab(window);
    const created = await a.create(board('From A'), { requestId: 'a-1' });
    expect(await b.list('orders')).toEqual([]);

    window.dispatchEvent(
      new StorageEvent('storage', {
        key: KEY,
        newValue: localStorage.getItem(KEY),
        storageArea: localStorage,
      }),
    );

    expect((await b.list('orders')).map(({ id }) => id)).toEqual([created.id]);
  });

  it('ignores a storage event for another key or another storage area', async () => {
    const a = tab();
    const b = tab(window);
    await a.create(board(), { requestId: 'a-1' });

    window.dispatchEvent(new StorageEvent('storage', { key: 'elsewhere' }));
    window.dispatchEvent(
      new StorageEvent('storage', { key: KEY, storageArea: sessionStorage }),
    );
    expect(await b.list('orders')).toEqual([]);

    // A `clear()` in another tab is announced with no key, and is ours.
    window.dispatchEvent(new StorageEvent('storage', { key: null }));
    expect(await b.list('orders')).toHaveLength(1);
  });

  it('listens on the window by default, and stops when unsubscribed', () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const listener = vi.fn();

    const unsubscribe = localStorageSnapshot(KEY).subscribe!(listener);
    window.dispatchEvent(new StorageEvent('storage', { key: KEY }));
    unsubscribe();
    window.dispatchEvent(new StorageEvent('storage', { key: KEY }));

    expect(add).toHaveBeenCalledWith('storage', expect.any(Function));
    expect(remove).toHaveBeenCalledWith('storage', add.mock.calls[0][1]);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(
      localStorageSnapshot(KEY, { events: null }).subscribe!(listener),
    ).toBeTypeOf('function');
  });
});

describe('localStorageSnapshot: what it reads', () => {
  it('reads corrupt or malformed JSON as nothing stored, and starts empty', async () => {
    const snapshot = localStorageSnapshot(KEY, { events: null });
    for (const corrupt of [
      '{not json',
      'null',
      '[]',
      '"text"',
      JSON.stringify({ instances: {} }),
      JSON.stringify({ instances: [], preferences: null }),
      JSON.stringify({ instances: [], preferences: [] }),
    ]) {
      localStorage.setItem(KEY, corrupt);
      expect(snapshot.load()).toBeUndefined();
    }

    localStorage.setItem(KEY, '{not json');
    const store = new MemoryViewStore({ snapshot });
    expect(await store.list('orders')).toEqual([]);
    // The next write replaces what could not be read.
    await store.create(board(), { requestId: 'c-1' });
    expect(stored().instances).toHaveLength(1);
  });

  it('reads a storage the browser refuses as nothing stored', () => {
    const snapshot = localStorageSnapshot(KEY, {
      events: null,
      storage: () => {
        throw new DOMException('denied', 'SecurityError');
      },
    });
    expect(snapshot.load()).toBeUndefined();
    expect(() => snapshot.save({ instances: [], preferences: {} })).toThrow(
      'denied',
    );
  });

  it("loads the console's stored format as it is, dropping only entries without an id", async () => {
    const saved: ViewInstance = {
      id: 'compensation.execution-failed-3',
      definitionId: 'compensation.execution-failed',
      title: 'My failures',
      scope: 'personal',
      revision: '4',
      config: recordConfig(),
    };
    const preferences = {
      'compensation.execution-failed': {
        order: [saved.id],
        defaultInstanceId: saved.id,
        revision: '2',
      },
    };
    localStorage.setItem(
      KEY,
      JSON.stringify({ instances: [saved, { title: 'no id' }], preferences }),
    );

    const store = tab();

    expect(await store.get(saved.id)).toEqual(saved);
    expect(await store.getPreferences('compensation.execution-failed')).toEqual(
      preferences['compensation.execution-failed'],
    );
    await store.rename(saved.id, 'Renamed', '4', { requestId: 'r-1' });
    expect(stored()).toEqual({
      instances: [{ ...saved, title: 'Renamed', revision: '5' }],
      preferences,
    });
  });

  it('keeps what the store holds when nothing is readable before a write', async () => {
    const store = tab();
    const created = await store.create(board(), { requestId: 'c-1' });
    localStorage.removeItem(KEY);

    await store.rename(created.id, 'Still here', '1', { requestId: 'r-1' });

    expect(stored().instances).toEqual([
      expect.objectContaining({ id: created.id, title: 'Still here' }),
    ]);
  });

  it('refuses a missing localStorage as a failed write, not a crash', () => {
    vi.stubGlobal('localStorage', undefined);
    try {
      const snapshot = localStorageSnapshot(KEY, { events: null });
      expect(snapshot.load()).toBeUndefined();
      expect(() => snapshot.save({ instances: [], preferences: {} })).toThrow(
        'localStorage is not available',
      );
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
