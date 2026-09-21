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

/**
 * Save, save-as, revert, rename and delete: what each command writes, what
 * it reports when the write comes back a conflict or a refusal, and the one
 * queue that keeps a view to a single write in flight.
 */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ViewCommandError,
  ViewStoreError,
  issue,
  type ViewInstance,
} from '../src/index.js';
import { useOpenView, useSaveCommands } from '../src/react/index.js';
import { deferred, mine, recordConfig } from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';

afterEach(cleanup);

describe('useSaveCommands', () => {
  async function openMine() {
    const { engine, store } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, commands: useSaveCommands(engine, opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    return { engine, store, result };
  }

  it('answers with no abilities when nothing is open', () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useSaveCommands(engine, null));

    expect(result.current.can).toEqual({
      save: false,
      saveAs: false,
      rename: false,
      delete: false,
      revert: false,
      createPersonal: false,
      createShared: false,
    });
    expect(result.current.state).toEqual({
      pending: false,
      error: null,
      write: null,
      dirty: false,
      blocked: false,
      hasErrors: false,
      lastSavedAt: null,
    });
  });

  it('does nothing when a command has no target', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useSaveCommands(engine, null));

    await expect(result.current.save()).resolves.toBeNull();
    await expect(
      result.current.saveAs({ title: 'x', scope: 'personal' }),
    ).resolves.toBeNull();
    await expect(result.current.rename('x')).resolves.toBeNull();
    await expect(result.current.delete()).resolves.toBe(false);
    await expect(result.current.retry()).resolves.toEqual({
      landed: false,
      written: false,
      instance: null,
    });
    await expect(result.current.resolveConflict('reload')).resolves.toEqual({
      landed: false,
      written: false,
      instance: null,
    });
    expect(() => result.current.abandon()).not.toThrow();
  });

  it('saves, renames and deletes an open view', async () => {
    const { store, result } = await openMine();

    act(() => result.current.opened.runtime?.edit({ pageSize: 44 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.dirty).toBe(false);

    await act(async () => {
      await result.current.commands.rename('Renamed');
    });
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Renamed',
    });

    await act(async () => {
      expect(await result.current.commands.delete()).toBe(true);
    });
    await expect(store.list('orders')).resolves.toEqual([]);
  });

  it('turns a refusal into state instead of a rejection', async () => {
    const { engine, result } = await openMine();
    vi.spyOn(engine, 'save').mockRejectedValueOnce(
      new ViewCommandError(issue('view.config.invalid', [])),
    );

    await act(async () => {
      expect(await result.current.commands.save()).toBeNull();
    });

    expect(result.current.commands.state.error?.code).toBe(
      'view.config.invalid',
    );
    expect(result.current.commands.state.pending).toBe(false);
  });

  it("keeps the current view pending while another view's command settles", async () => {
    const { engine } = engineWith();
    const a = await engine.open('orders-1');
    const b = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });

    const aFailed = deferred<void>();
    vi.spyOn(engine, 'save').mockImplementation(async runtime => {
      if (runtime === a) {
        await aFailed.promise;
        throw new ViewCommandError(issue('view.config.invalid', []));
      }
      // B's write never settles within the test.
      return new Promise<ViewInstance>(() => {});
    });

    // A workbench reuses this hook across views: A's save is in flight when
    // the user switches to B and saves there.
    const { result, rerender } = renderHook(
      ({ runtime }: { runtime: typeof a }) => useSaveCommands(engine, runtime),
      { initialProps: { runtime: a } },
    );

    act(() => {
      void result.current.save();
    });
    rerender({ runtime: b });
    act(() => {
      void result.current.save();
    });
    expect(result.current.state.pending).toBe(true);

    await act(async () => {
      aFailed.reject(new Error('A failed'));
      await Promise.resolve();
    });

    // B's write is still in flight, so its buttons stay disabled.
    expect(result.current.state.pending).toBe(true);
  });

  /**
   * A workbench can go back. The hook keeps one queue, so A's second write
   * must still chain behind A's first however many other views were written
   * to in between — a second write against one runtime is what the engine
   * refuses with `view.write.in-flight`, and the header would report that as
   * the failure of the click that was only second.
   */
  it("waits for this view's own write when the workbench returns", async () => {
    const { engine } = engineWith();
    const a = await engine.open('orders-1');
    const b = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });

    const held = deferred<ViewInstance>();
    const started: string[] = [];
    vi.spyOn(engine, 'save').mockImplementation(runtime => {
      if (runtime === a) {
        started.push(started.includes('a') ? 'a2' : 'a');
        return held.promise;
      }
      started.push('b');
      return Promise.resolve({ ...mine, id: 'orders-2', revision: '1' });
    });

    const { result, rerender } = renderHook(
      ({ runtime }: { runtime: typeof a }) => useSaveCommands(engine, runtime),
      { initialProps: { runtime: a } },
    );

    act(() => {
      void result.current.save();
    });
    rerender({ runtime: b });
    await act(async () => {
      await result.current.save();
    });
    rerender({ runtime: a });

    // A's first write is still hanging, so its second one has not left.
    let second: Promise<ViewInstance | null> = Promise.resolve(null);
    act(() => {
      second = result.current.save();
    });
    expect(started).toEqual(['a', 'b']);

    await act(async () => {
      held.resolve({ ...mine, revision: '2' });
      await second;
    });
    expect(started).toEqual(['a', 'b', 'a2']);
    // It ran as its own write, not as a refusal of one already in flight.
    expect(result.current.state.error).toBeNull();
  });

  /**
   * Two clicks before the first answer is in. The second is queued behind the
   * first, and by the time it reaches the front the view is `unknown` — the
   * one outcome that refuses a new intent, because the write it stands for
   * may already have landed. Sending anyway earns `view.write.unknown-pending`
   * and puts that refusal on screen over an outcome the user has yet to retry
   * or abandon, so the queued command is dropped instead.
   */
  it('drops a queued write once the one ahead came back unknown', async () => {
    const { store, result } = await openMine();
    const held = deferred<ViewInstance>();
    const saves = vi.spyOn(store, 'save').mockReturnValueOnce(held.promise);

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    let first: Promise<ViewInstance | null> = Promise.resolve(null);
    let second: Promise<ViewInstance | null> = Promise.resolve(null);
    act(() => {
      first = result.current.commands.save();
      second = result.current.commands.save();
    });

    await act(async () => {
      held.reject(new ViewStoreError('UNAVAILABLE', 'timeout'));
      await Promise.all([first, second]);
    });

    expect(result.current.commands.state.write?.kind).toBe('unknown');
    // The second never left, so the store saw one attempt and the second
    // click resolved as nothing happened rather than as a failure.
    expect(saves).toHaveBeenCalledTimes(1);
    await expect(second).resolves.toBeNull();
    // What is on screen is the first write's own outcome, not a refusal of
    // the second one stacked over it.
    expect(result.current.commands.state.error?.code).toBe(
      'view.save.failed.unknown',
    );
    expect(result.current.commands.state.pending).toBe(false);
  });

  it("lets the current view's abandon take the progress slot", async () => {
    const { engine } = engineWith();
    const a = await engine.open('orders-1');
    const b = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });

    // A leaves a failure in the shared slot; the workbench switches to B.
    vi.spyOn(engine, 'save').mockRejectedValueOnce(
      new ViewCommandError(issue('view.config.invalid', [])),
    );
    const { result, rerender } = renderHook(
      ({ runtime }: { runtime: typeof a }) => useSaveCommands(engine, runtime),
      { initialProps: { runtime: a } },
    );
    await act(async () => {
      await result.current.save();
    });
    rerender({ runtime: b });

    // Abandoning is the user acting now, not an old callback arriving late:
    // even a refusal belongs to B, over A's leftover failure.
    vi.spyOn(engine, 'abandonWrite').mockImplementationOnce(() => {
      throw new ViewCommandError(issue('view.write.not-pending', []));
    });
    act(() => {
      result.current.abandon();
    });

    // The refusal shows as itself: toIssue keeps a command error's own code.
    expect(result.current.state.error?.code).toBe('view.write.not-pending');
  });

  it('exposes an unresolved write and its recovery actions', async () => {
    const { engine, store, result } = await openMine();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('unknown');

    await act(async () => {
      await result.current.commands.retry();
    });
    expect(result.current.commands.state.write).toBeNull();
    // A replay that landed is a write that landed, badge and all.
    expect(result.current.commands.state.lastSavedAt).toEqual(
      expect.any(Number),
    );
    await expect(store.get('orders-1')).resolves.toMatchObject({
      config: { pageSize: 21 },
    });

    vi.spyOn(engine, 'abandonWrite').mockImplementationOnce(() => {
      throw new ViewCommandError(issue('view.write.not-pending', []));
    });
    act(() => result.current.commands.abandon());
    expect(result.current.commands.state.error?.code).toBe(
      'view.write.not-pending',
    );
  });

  it('abandons an outcome and clears the error', async () => {
    const { store, result } = await openMine();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await act(async () => {
      await result.current.commands.save();
    });

    act(() => result.current.commands.abandon());

    expect(result.current.commands.state.write).toBeNull();
    expect(result.current.commands.state.error).toBeNull();
  });

  it('resolves a conflict by reloading', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('conflict');

    await act(async () => {
      await result.current.commands.resolveConflict('reload');
    });
    expect(result.current.opened.runtime?.getSnapshot().draft).toMatchObject({
      pageSize: 77,
    });
  });

  it('leaves a failure behind when another view opens', async () => {
    const { engine, store } = engineWith();
    await store.create(
      {
        definitionId: 'orders',
        title: 'Second',
        scope: 'personal',
        config: recordConfig(),
      },
      { requestId: 'seed' },
    );
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'nope'),
    );

    const { result, rerender } = renderHook(
      ({ id }: { id: string }) => {
        const opened = useOpenView(engine, id);
        return { opened, commands: useSaveCommands(engine, opened.runtime) };
      },
      { initialProps: { id: 'orders-1' } },
    );
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.error).not.toBeNull();

    rerender({ id: 'orders-2' });
    await waitFor(() =>
      expect(result.current.opened.runtime?.getSnapshot().saved?.id).toBe(
        'orders-2',
      ),
    );

    expect(result.current.commands.state.error).toBeNull();
    expect(result.current.commands.state.pending).toBe(false);
  });

  it('offers revert only with edits and a baseline to drop them for', async () => {
    const { engine, result } = await openMine();
    expect(result.current.commands.can.revert).toBe(false);

    act(() => result.current.opened.runtime?.edit({ pageSize: 44 }));
    expect(result.current.commands.can.revert).toBe(true);

    act(() => result.current.commands.revert());
    expect(result.current.commands.can.revert).toBe(false);
    expect(result.current.opened.runtime?.getSnapshot().draft).toMatchObject({
      pageSize: 20,
    });

    // A view that was never saved is dirty from the start and has nothing to
    // go back to, so reverting is never offered and does nothing.
    const fresh = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });
    const { result: unsaved } = renderHook(() =>
      useSaveCommands(engine, fresh),
    );
    expect(unsaved.current.can.revert).toBe(false);
    act(() => unsaved.current.revert());
    expect(fresh.getSnapshot().dirty).toBe(true);
  });

  it('blocks writing on a refused draft or an outcome nobody can read', async () => {
    const { store, result } = await openMine();
    expect(result.current.commands.state.blocked).toBe(false);

    // Every button that writes disables on all three reasons at once, so the
    // hook answers them as one flag rather than making each UI re-derive it.
    act(() => result.current.opened.runtime?.edit({ pageSize: 5000 }));
    expect(result.current.commands.state.blocked).toBe(true);
    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    expect(result.current.commands.state.blocked).toBe(false);

    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await act(async () => {
      await result.current.commands.save();
    });
    // Nobody knows whether that write landed, so the next one might be the
    // same write a second time.
    expect(result.current.commands.state.write?.kind).toBe('unknown');
    expect(result.current.commands.state.blocked).toBe(true);
  });

  it('leaves a conflict open to the new intent that resolves it', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('conflict');

    // A conflict is a definite answer, and "Save my copy" is one of the ways
    // out of it — blocking on it would disable the button that resolves it.
    // A UI that must refuse a *blind* Save here reads `write` itself.
    expect(result.current.commands.state.blocked).toBe(false);
    await act(async () => {
      await expect(
        result.current.commands.saveAs({
          title: 'My copy',
          scope: 'personal',
        }),
      ).resolves.not.toBeNull();
    });
  });

  it('times the write that landed and clears it when the next starts', async () => {
    const { engine, result } = await openMine();
    expect(result.current.commands.state.lastSavedAt).toBeNull();

    act(() => result.current.opened.runtime?.edit({ pageSize: 44 }));
    await act(async () => {
      await result.current.commands.save();
    });
    const landed = result.current.commands.state.lastSavedAt;
    expect(typeof landed).toBe('number');

    // The moment belongs to one write. A UI showing "Saved" for a couple of
    // seconds must not carry the last one's badge over the next attempt.
    const slow = deferred<ViewInstance>();
    vi.spyOn(engine, 'save').mockReturnValueOnce(slow.promise);
    act(() => {
      void result.current.commands.save();
    });
    expect(result.current.commands.state.lastSavedAt).toBeNull();
    expect(result.current.commands.state.blocked).toBe(true);

    await act(async () => {
      slow.resolve({ ...mine, revision: '3' });
      await Promise.resolve();
    });
    expect(result.current.commands.state.lastSavedAt).toEqual(
      expect.any(Number),
    );
  });

  it('leaves the last write unmarked when a command fails', async () => {
    const { engine, result } = await openMine();
    vi.spyOn(engine, 'save').mockRejectedValueOnce(
      new ViewCommandError(issue('view.config.invalid', [])),
    );

    await act(async () => {
      await result.current.commands.save();
    });

    expect(result.current.commands.state.lastSavedAt).toBeNull();
    // A rename that landed is not a save, so it marks no moment either.
    await act(async () => {
      await result.current.commands.rename('Renamed');
    });
    expect(result.current.commands.state.lastSavedAt).toBeNull();
  });

  it('marks no moment for a conflict the user resolved by reloading', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('conflict');

    await act(async () => {
      await expect(
        result.current.commands.resolveConflict('reload'),
      ).resolves.toMatchObject({ landed: true, written: false });
    });

    // Reloading settles the conflict by taking the stored state and dropping
    // the draft. Nothing of the user's was written, so a "View saved" badge
    // over the edits they just gave up is the one thing it must not show.
    expect(result.current.commands.state.lastSavedAt).toBeNull();
  });

  it('marks no moment for a replayed write that is not a save', async () => {
    const { store, result } = await openMine();
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    await act(async () => {
      await result.current.commands.rename('Renamed');
    });
    expect(result.current.commands.state.write?.kind).toBe('unknown');

    await act(async () => {
      await expect(result.current.commands.retry()).resolves.toMatchObject({
        landed: true,
        written: false,
      });
    });

    // The replay did reach the store — the title is the new one — but what
    // it wrote is not the config on screen. Saying "View saved" here would
    // tell the user their unsaved edits are safe when nothing of them went.
    expect(result.current.commands.state.lastSavedAt).toBeNull();
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Renamed',
    });
  });

  it('times a conflict the user resolved by overwriting', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });

    await act(async () => {
      await expect(
        result.current.commands.resolveConflict('overwrite'),
      ).resolves.toMatchObject({ landed: true, written: true });
    });

    expect(result.current.commands.state.lastSavedAt).toEqual(
      expect.any(Number),
    );
  });

  it('offers only a copy of a system view', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'system:orders:all');
      return useSaveCommands(engine, opened.runtime);
    });

    await waitFor(() => expect(result.current.can.saveAs).toBe(true));
    expect(result.current.can).toMatchObject({
      save: false,
      rename: false,
      delete: false,
    });
  });
});
