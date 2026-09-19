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

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  isSystemInstanceId,
  MemoryViewStore,
  parseSystemInstanceId,
  ViewEngine,
  ViewStoreError,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPermissions,
} from '../src/index.js';
import {
  PREFERENCES_KEY,
  useViewList,
  useViewManager,
  type ViewListState,
  type ViewManagerController,
} from '../src/react/index.js';
import {
  deferred,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** Two personal views, alongside the system view the definition declares. */
function instances(): ViewInstance[] {
  return [
    {
      id: 'orders-1',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
    {
      id: 'orders-2',
      definitionId: 'orders',
      title: 'Yours',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
  ];
}

function permitting(
  overrides: Partial<ViewPermissions> = {},
): (definitionId: string) => ViewPermissions {
  return () => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  });
}

function engineWith(
  options: { permissions?: (definitionId: string) => ViewPermissions } = {},
): { engine: ViewEngine; store: MemoryViewStore } {
  const store = new MemoryViewStore({
    instances: instances(),
    permissions: options.permissions,
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

interface Managed {
  list: ViewListState;
  manager: ViewManagerController;
}

/** The manager over a settled list, which is the only state it is used in. */
async function managed(engine: ViewEngine) {
  const rendered = renderHook<Managed, unknown>(() => {
    const list = useViewList(engine, 'orders');
    return { list, manager: useViewManager(engine, 'orders', list) };
  });
  await waitFor(() => expect(rendered.result.current.list.loading).toBe(false));
  return rendered;
}

describe('useViewManager', () => {
  it('renames a view and reloads the list', async () => {
    const { engine } = engineWith();
    const { result } = await managed(engine);

    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', 'Renamed'),
      ).resolves.toBe(true);
    });

    await waitFor(() =>
      expect(
        result.current.list.items.find(item => item.id === 'orders-1')?.title,
      ).toBe('Renamed'),
    );
    expect(result.current.manager.outcomes.size).toBe(0);
    expect(result.current.manager.pending).toBeNull();
  });

  it('deletes a view and reloads the list', async () => {
    const { engine } = engineWith();
    const { result } = await managed(engine);

    await act(async () => {
      await expect(result.current.manager.delete('orders-2')).resolves.toBe(
        true,
      );
    });

    await waitFor(() =>
      expect(result.current.list.items.map(item => item.id)).toEqual([
        'system:orders:all',
        'orders-1',
      ]),
    );
  });

  it('stops naming a deleted default while the reload is in flight', async () => {
    const { engine, store } = engineWith();
    await store.setPreferences(
      'orders',
      { order: [], defaultInstanceId: 'orders-2', revision: '0' },
      { requestId: 'seed' },
    );
    const { result } = await managed(engine);
    expect(result.current.list.defaultInstanceId).toBe('orders-2');

    // The reload a landing triggers is held open, which is the whole window
    // the list keeps its settled items through.
    const read = engine.list.bind(engine);
    const listed = deferred<ViewInstanceSummary[]>();
    vi.spyOn(engine, 'list').mockImplementationOnce(() => listed.promise);

    await act(async () => {
      await expect(result.current.manager.delete('orders-2')).resolves.toBe(
        true,
      );
    });

    // The row is gone from the store, and a workbench riding on the default
    // would otherwise reopen it here — the engine has just disposed that
    // runtime, so the open answers with a transient not-found.
    expect(result.current.list.loading).toBe(true);
    expect(result.current.list.items.map(item => item.id)).not.toContain(
      'orders-2',
    );
    expect(result.current.list.preferences?.defaultInstanceId).toBeNull();
    expect(result.current.list.defaultInstanceId).not.toBe('orders-2');

    await act(async () => {
      listed.resolve(await read('orders'));
    });
    await waitFor(() => expect(result.current.list.loading).toBe(false));
    expect(result.current.list.defaultInstanceId).not.toBe('orders-2');
    expect(result.current.list.items.map(item => item.id)).not.toContain(
      'orders-2',
    );
  });

  it('names the write in flight while it is in flight', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    const slow = deferred<ViewInstance>();
    vi.spyOn(store, 'rename').mockReturnValueOnce(slow.promise);

    let landed!: Promise<boolean>;
    act(() => {
      landed = result.current.manager.rename('orders-1', 'Later');
    });
    expect(result.current.manager.pending).toBe('orders-1');

    await act(async () => {
      slow.resolve({ ...instances()[0], title: 'Later', revision: '2' });
      await landed;
    });
    expect(result.current.manager.pending).toBeNull();
  });

  it('runs two commands one after the other, naming each in turn', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    const first = deferred<ViewInstance>();
    const second = deferred<void>();
    const rename = vi.spyOn(store, 'rename').mockReturnValueOnce(first.promise);
    const remove = vi.spyOn(store, 'delete').mockReturnValueOnce(
      // The second command must not even reach the store until the first is
      // done: one slot cannot report two writes, and the second one's
      // `finally` would clear it while the first is still going.
      second.promise,
    );

    let both!: Promise<boolean[]>;
    act(() => {
      both = Promise.all([
        result.current.manager.rename('orders-1', 'Later'),
        result.current.manager.delete('orders-2'),
      ]);
    });

    expect(result.current.manager.pending).toBe('orders-1');
    await waitFor(() => expect(rename).toHaveBeenCalledTimes(1));
    // The first write has reached the store and the second has not moved.
    expect(remove).not.toHaveBeenCalled();
    expect(result.current.manager.pending).toBe('orders-1');

    await act(async () => {
      first.resolve({ ...instances()[0], title: 'Later', revision: '2' });
      await first.promise;
    });
    await waitFor(() =>
      expect(result.current.manager.pending).toBe('orders-2'),
    );
    expect(remove).toHaveBeenCalledTimes(1);

    await act(async () => {
      second.resolve();
      await both;
    });
    expect(result.current.manager.pending).toBeNull();
    expect(result.current.manager.outcomes.size).toBe(0);
  });

  it('drops a completion from the definition it has moved on from', async () => {
    const { engine, store } = engineWith();
    const slow = deferred<ViewInstance>();
    vi.spyOn(store, 'rename').mockReturnValueOnce(slow.promise);

    const rendered = renderHook<Managed, { definitionId: string }>(
      ({ definitionId }) => {
        const list = useViewList(engine, definitionId);
        return { list, manager: useViewManager(engine, definitionId, list) };
      },
      { initialProps: { definitionId: 'orders' } },
    );
    await waitFor(() =>
      expect(rendered.result.current.list.loading).toBe(false),
    );

    let landed!: Promise<boolean>;
    act(() => {
      landed = rendered.result.current.manager.rename('orders-1', 'Later');
    });
    expect(rendered.result.current.manager.pending).toBe('orders-1');

    rendered.rerender({ definitionId: 'other' });
    // Outcomes and progress answer for the rows of the definition they were
    // raised under. This list does not hold them.
    expect(rendered.result.current.manager.pending).toBeNull();
    expect(rendered.result.current.manager.outcomes.size).toBe(0);

    await act(async () => {
      slow.reject(new ViewStoreError('UNAVAILABLE', 'timeout'));
      await landed;
    });

    // The old command finished into a slot nothing reads, rather than
    // putting an unknown outcome on a row of the definition now on screen.
    expect(rendered.result.current.manager.pending).toBeNull();
    expect(rendered.result.current.manager.outcomes.size).toBe(0);
  });

  it('keeps a rename conflict and clears it once the baseline moves', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    // Somebody else renamed it after this list was read, so the revision the
    // command carries is a revision behind.
    await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', 'Mine'),
      ).resolves.toBe(false);
    });
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'conflict',
    );

    await act(async () => {
      await expect(
        result.current.manager.resolveConflict('orders-1', 'reload'),
      ).resolves.toBe(true);
    });

    // Reloading a rename conflict only advances the baseline (§7.4), so the
    // row is done with it and the list shows what is stored.
    expect(result.current.manager.outcomes.size).toBe(0);
    await waitFor(() =>
      expect(
        result.current.list.items.find(item => item.id === 'orders-1')?.title,
      ).toBe('Theirs'),
    );
  });

  it('overwrites a rename conflict with the original intent', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

    await act(async () => {
      await result.current.manager.rename('orders-1', 'Mine');
    });
    await act(async () => {
      await expect(
        result.current.manager.resolveConflict('orders-1', 'overwrite'),
      ).resolves.toBe(true);
    });

    expect(result.current.manager.outcomes.size).toBe(0);
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Mine',
    });
  });

  it('retries an unknown outcome under its own request id', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    vi.spyOn(store, 'delete').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    await act(async () => {
      await expect(result.current.manager.delete('orders-2')).resolves.toBe(
        false,
      );
    });
    expect(result.current.manager.outcomes.get('orders-2')?.kind).toBe(
      'unknown',
    );

    await act(async () => {
      await expect(result.current.manager.retry('orders-2')).resolves.toBe(
        true,
      );
    });
    expect(result.current.manager.outcomes.size).toBe(0);
    await expect(store.get('orders-2')).rejects.toThrow();
  });

  it('abandons an unknown outcome', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    await act(async () => {
      await result.current.manager.rename('orders-1', 'Later');
    });
    act(() => result.current.manager.abandon('orders-1'));

    expect(result.current.manager.outcomes.size).toBe(0);
    expect(engine.pendingWrites().size).toBe(0);
  });

  it('leaves nothing behind when abandoning a key it never recorded', async () => {
    const { engine } = engineWith();
    const { result } = await managed(engine);
    const abandonWrite = vi.spyOn(engine, 'abandonWrite');

    act(() => result.current.manager.abandon('orders-1'));

    expect(abandonWrite).not.toHaveBeenCalled();
    expect(result.current.manager.outcomes.size).toBe(0);
  });

  it('records a refusal the engine made before sending anything', async () => {
    const { engine } = engineWith();
    const { result } = await managed(engine);

    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', '  '),
      ).resolves.toBe(false);
    });

    const outcome = result.current.manager.outcomes.get('orders-1');
    expect(outcome?.kind).toBe('rejected');
    expect(outcome?.kind === 'rejected' && outcome.issue.code).toBe(
      'view.title.empty',
    );
    // Nothing left, so there is nothing to replay or to resolve.
    await act(async () => {
      await expect(result.current.manager.retry('orders-1')).resolves.toBe(
        false,
      );
      await expect(
        result.current.manager.resolveConflict('orders-1', 'reload'),
      ).resolves.toBe(false);
    });
    act(() => result.current.manager.abandon('orders-1'));
    expect(result.current.manager.outcomes.size).toBe(0);
  });

  it('records a refused permission against the row that asked', async () => {
    const { engine } = engineWith({
      permissions: permitting({
        instance: () => ({ save: true, rename: true, delete: false }),
      }),
    });
    const { result } = await managed(engine);

    await act(async () => {
      await expect(result.current.manager.delete('orders-1')).resolves.toBe(
        false,
      );
    });

    const outcome = result.current.manager.outcomes.get('orders-1');
    expect(outcome?.kind === 'rejected' && outcome.issue.code).toBe(
      'view.delete.forbidden',
    );
  });

  it('moves a view one step and submits the whole visible order', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    const before = result.current.list.items.map(item => item.id);

    await act(async () => {
      await expect(result.current.manager.move(before[1], 'up')).resolves.toBe(
        true,
      );
    });

    await expect(store.getPreferences('orders')).resolves.toMatchObject({
      order: [before[1], before[0], before[2]],
    });
    await waitFor(() =>
      expect(result.current.list.items.map(item => item.id)).toEqual([
        before[1],
        before[0],
        before[2],
      ]),
    );
  });

  it('writes nothing for a move off either end, or for a row it does not hold', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    const setPreferences = vi.spyOn(store, 'setPreferences');
    const ids = result.current.list.items.map(item => item.id);

    await act(async () => {
      await expect(result.current.manager.move(ids[0], 'up')).resolves.toBe(
        false,
      );
      await expect(
        result.current.manager.move(ids[ids.length - 1], 'down'),
      ).resolves.toBe(false);
      await expect(result.current.manager.move('gone', 'up')).resolves.toBe(
        false,
      );
    });

    expect(setPreferences).not.toHaveBeenCalled();
    expect(result.current.manager.outcomes.size).toBe(0);
  });

  it('refuses a reorder nobody is permitted, with no preferences to quote', async () => {
    const { engine, store } = engineWith({
      permissions: permitting({ reorder: false }),
    });
    // The preferences never loaded either, so the refusal describes an intent
    // built from nothing rather than from a revision it never read.
    vi.spyOn(store, 'getPreferences').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );
    const { result } = await managed(engine);
    expect(result.current.list.preferences).toBeNull();

    await act(async () => {
      await expect(
        result.current.manager.move(result.current.list.items[1].id, 'up'),
      ).resolves.toBe(false);
    });

    const outcome = result.current.manager.outcomes.get(PREFERENCES_KEY);
    expect(outcome?.kind === 'rejected' && outcome.issue.code).toBe(
      'view.preferences.reorder-forbidden',
    );
    expect(outcome?.payload.action).toBe('preferences');
  });

  it('sets and clears the default view', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);

    await act(async () => {
      await expect(result.current.manager.setDefault('orders-2')).resolves.toBe(
        true,
      );
    });
    await waitFor(() =>
      expect(result.current.list.defaultInstanceId).toBe('orders-2'),
    );

    await act(async () => {
      await expect(result.current.manager.setDefault(null)).resolves.toBe(true);
    });
    await expect(store.getPreferences('orders')).resolves.toMatchObject({
      defaultInstanceId: null,
    });
  });

  it('keeps a preference conflict after reloading, to be confirmed again', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    // The stored preferences moved on under the revision this list read.
    await store.setPreferences(
      'orders',
      { order: ['orders-2'], defaultInstanceId: 'orders-2', revision: '0' },
      { requestId: 'other' },
    );

    await act(async () => {
      await expect(result.current.manager.setDefault('orders-1')).resolves.toBe(
        false,
      );
    });
    expect(result.current.manager.outcomes.get(PREFERENCES_KEY)?.kind).toBe(
      'conflict',
    );

    await act(async () => {
      await expect(
        result.current.manager.resolveConflict(PREFERENCES_KEY, 'reload'),
      ).resolves.toBe(true);
    });

    // §7.3: the stored preferences are read again and the user's own intent
    // is put to them once more rather than replayed at the new revision.
    expect(result.current.manager.outcomes.get(PREFERENCES_KEY)?.kind).toBe(
      'conflict',
    );
    await waitFor(() =>
      expect(result.current.list.defaultInstanceId).toBe('orders-2'),
    );

    // The engine settled it, so the kept outcome answers for nothing: asking
    // again is a new write, which this time lands.
    await act(async () => {
      await expect(
        result.current.manager.resolveConflict(PREFERENCES_KEY, 'reload'),
      ).resolves.toBe(false);
      await expect(result.current.manager.setDefault('orders-1')).resolves.toBe(
        true,
      );
    });
    expect(result.current.manager.outcomes.size).toBe(0);
  });

  it('keys preferences in the namespace a store may not issue', () => {
    // Both halves of the key space are instance ids as far as `outcomes` is
    // concerned, so the preferences slot is taken from the one prefix
    // `isSystemInstanceId` reserves rather than from a bare word a store
    // could hand out as an id of its own.
    expect(PREFERENCES_KEY).toBe('system:preferences');
    expect(isSystemInstanceId(PREFERENCES_KEY)).toBe(true);
    // Not a composed system view id either, so nothing reads it as one.
    expect(parseSystemInstanceId(PREFERENCES_KEY)).toBeNull();
  });

  it('keeps a new command off the queue while a key is unknown', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    const rename = vi
      .spyOn(store, 'rename')
      .mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'timeout'));
    const remove = vi.spyOn(store, 'delete');

    await act(async () => {
      await result.current.manager.rename('orders-1', 'Later');
    });
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'unknown',
    );
    expect(rename).toHaveBeenCalledTimes(1);

    // The write may have landed, so the engine refuses a second one against
    // the same target — and a refusal carries no handle, so recording it
    // would take away the only two things that can still answer for this
    // row. Turning the command away here means it never gets the chance.
    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', 'Again'),
      ).resolves.toBe(false);
      await expect(result.current.manager.delete('orders-1')).resolves.toBe(
        false,
      );
    });
    expect(rename).toHaveBeenCalledTimes(1);
    expect(remove).not.toHaveBeenCalled();
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'unknown',
    );

    // Retry and abandon are what the key does accept, and they still work.
    await act(async () => {
      await expect(result.current.manager.retry('orders-1')).resolves.toBe(
        true,
      );
    });
    expect(result.current.manager.outcomes.size).toBe(0);
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Later',
    });
  });

  it('keeps a new command off the queue while a key holds a conflict', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', 'Mine'),
      ).resolves.toBe(false);
    });
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'conflict',
    );
    expect(engine.pendingWrites().size).toBe(1);

    // A conflict holds a handle just as an `unknown` does, and the engine
    // would happily dispatch a second rename over it. The row has one slot:
    // whatever that command recorded would take the conflict's place, and
    // the write it addresses would be left in `pendingWrites()` with nothing
    // on screen able to overwrite or abandon it.
    const rename = vi.spyOn(store, 'rename');
    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', 'Later'),
      ).resolves.toBe(false);
    });
    expect(rename).not.toHaveBeenCalled();
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'conflict',
    );
    expect(engine.pendingWrites().size).toBe(1);

    // The conflict's own handle still answers, which is the point of it.
    await act(async () => {
      await expect(
        result.current.manager.resolveConflict('orders-1', 'overwrite'),
      ).resolves.toBe(true);
    });
    expect(result.current.manager.outcomes.size).toBe(0);
    expect(engine.pendingWrites().size).toBe(0);
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Mine',
    });
  });

  it('leaves a recoverable outcome alone when the next command is refused', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

    await act(async () => {
      await result.current.manager.rename('orders-1', 'Mine');
    });
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'conflict',
    );

    // A refusal never left, so it has nothing to replay. Taking the slot
    // would drop the handle the conflict still needs.
    await act(async () => {
      await expect(
        result.current.manager.rename('orders-1', '  '),
      ).resolves.toBe(false);
    });
    expect(result.current.manager.outcomes.get('orders-1')?.kind).toBe(
      'conflict',
    );

    await act(async () => {
      await expect(
        result.current.manager.resolveConflict('orders-1', 'overwrite'),
      ).resolves.toBe(true);
    });
    expect(result.current.manager.outcomes.size).toBe(0);
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Mine',
    });
  });

  it('starts a command for other inputs on a queue of its own', async () => {
    const store = new MemoryViewStore({
      instances: [
        ...instances(),
        {
          id: 'returns-1',
          definitionId: 'returns',
          title: 'Theirs',
          scope: 'personal',
          revision: '1',
          config: recordConfig(),
        },
      ],
    });
    const engine = new ViewEngine({
      definitions: [
        ordersDefinition(),
        ordersDefinition({ id: 'returns', title: 'Returns', views: [] }),
      ],
      store,
      resolveSource: () => testSource(),
    });
    const hangs = deferred<ViewInstance>();
    const rename = vi.spyOn(store, 'rename').mockReturnValueOnce(hangs.promise);

    const rendered = renderHook<Managed, { definitionId: string }>(
      ({ definitionId }) => {
        const list = useViewList(engine, definitionId);
        return { list, manager: useViewManager(engine, definitionId, list) };
      },
      { initialProps: { definitionId: 'orders' } },
    );
    await waitFor(() =>
      expect(rendered.result.current.list.loading).toBe(false),
    );

    let stuck!: Promise<boolean>;
    act(() => {
      stuck = rendered.result.current.manager.rename('orders-1', 'Later');
    });
    expect(rendered.result.current.manager.pending).toBe('orders-1');

    rendered.rerender({ definitionId: 'returns' });
    await waitFor(() =>
      expect(rendered.result.current.list.loading).toBe(false),
    );
    expect(rendered.result.current.manager.pending).toBeNull();

    // The queue belongs to the inputs whose commands are on it. Chaining
    // this one behind a write raised under a definition nobody is looking at
    // any more would leave the row the user just clicked showing nothing
    // until that write answers — which it may never do.
    let second!: Promise<boolean>;
    act(() => {
      second = rendered.result.current.manager.rename('returns-1', 'Renamed');
    });
    expect(rendered.result.current.manager.pending).toBe('returns-1');
    await waitFor(() => expect(rename).toHaveBeenCalledTimes(2));

    await act(async () => {
      await expect(second).resolves.toBe(true);
    });
    expect(rendered.result.current.manager.pending).toBeNull();
    await waitFor(() =>
      expect(
        rendered.result.current.list.items.find(item => item.id === 'returns-1')
          ?.title,
      ).toBe('Renamed'),
    );

    // The old queue settles alone, and its answer lands on nothing.
    await act(async () => {
      hangs.resolve({ ...instances()[0], title: 'Later', revision: '2' });
      await stuck;
    });
    expect(rendered.result.current.manager.outcomes.size).toBe(0);
  });

  it('computes a second quick move from the order the first submitted', async () => {
    const { engine, store } = engineWith();
    const { result } = await managed(engine);
    const [first, second, third] = result.current.list.items.map(
      item => item.id,
    );
    const gate = deferred<void>();
    const write = store.setPreferences.bind(store);
    let calls = 0;
    const setPreferences = vi
      .spyOn(store, 'setPreferences')
      .mockImplementation(async (...args) => {
        calls += 1;
        if (calls === 1) await gate.promise;
        return write(...args);
      });

    // Both clicks land before the list has reloaded, so both read the same
    // rendered order. Computing from it twice would submit the same order
    // twice and leave the row one step from where the user put it.
    let moves!: Promise<boolean[]>;
    act(() => {
      moves = Promise.all([
        result.current.manager.move(third, 'up'),
        result.current.manager.move(third, 'up'),
      ]);
    });

    await waitFor(() => expect(setPreferences).toHaveBeenCalledTimes(1));
    expect(setPreferences.mock.calls[0][1].order).toEqual([
      first,
      third,
      second,
    ]);

    await act(async () => {
      gate.resolve();
      await expect(moves).resolves.toEqual([true, true]);
    });

    expect(setPreferences).toHaveBeenCalledTimes(2);
    expect(setPreferences.mock.calls[1][1].order).toEqual([
      third,
      first,
      second,
    ]);
    await waitFor(() =>
      expect(result.current.list.items.map(item => item.id)).toEqual([
        third,
        first,
        second,
      ]),
    );
  });

  it('reads the abilities of a row off the permissions', async () => {
    const { engine } = engineWith({
      permissions: permitting({
        reorder: false,
        setDefault: true,
        instance: id => ({
          save: true,
          rename: id === 'orders-1',
          delete: false,
        }),
      }),
    });
    const { result } = await managed(engine);

    expect(result.current.manager.can.reorder).toBe(false);
    expect(result.current.manager.can.setDefault).toBe(true);
    expect(result.current.manager.can.instance('orders-1')).toEqual({
      rename: true,
      delete: false,
    });
    expect(result.current.manager.can.instance('orders-2')).toEqual({
      rename: false,
      delete: false,
    });
  });

  it('offers neither rename nor delete on a system view', async () => {
    const { engine } = engineWith();
    const { result } = await managed(engine);

    // The permissions say yes to every instance; the scope says no.
    expect(result.current.manager.can.instance('orders-1')).toEqual({
      rename: true,
      delete: true,
    });
    expect(result.current.manager.can.instance('system:orders:all')).toEqual({
      rename: false,
      delete: false,
    });

    await act(async () => {
      await expect(
        result.current.manager.rename('system:orders:all', 'New'),
      ).resolves.toBe(false);
    });
    const outcome = result.current.manager.outcomes.get('system:orders:all');
    expect(outcome?.kind === 'rejected' && outcome.issue.code).toBe(
      'view.system.read-only',
    );
  });
});
