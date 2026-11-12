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
 * A save writes what ran (D94, audit F2). A draft with a part still waiting
 * for apply used to be written as it stood: the view was saved with a sort
 * or a condition its author had never seen the rows of, and opened on them
 * the next time. Now the save applies what waits, waits for the answer, and
 * writes nothing when the source refuses it.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  isViewCommandError,
  type RecordViewRuntime,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  deferred,
  nextTask,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  ROWS,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { SortDirection } from '@ahoo-wang/wow-client/dsl';

const SAVED: ViewInstance = {
  id: 'mine',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

const BY_AMOUNT = [{ field: 'amount', direction: SortDirection.DESC }];

async function opened(source: ViewSource) {
  const store = new MemoryViewStore({ instances: [SAVED] });
  const save = vi.spyOn(store, 'save');
  const create = vi.spyOn(store, 'create');
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => source),
    store,
    environment: testEnvironment().environment,
  });
  const runtime = (await engine.open('mine')) as RecordViewRuntime;
  await nextTask();
  return { engine, runtime, store, save, create };
}

/** How many times the source was asked for a page, by either way of paging. */
const asked = (source: ViewSource) =>
  vi.mocked(source.paged).mock.calls.length +
  vi.mocked(source.cursor).mock.calls.length;

describe('a save with part of the draft waiting for apply', () => {
  it('applies it first, and writes once the source has answered', async () => {
    const source = testSource();
    const { engine, runtime, store, save } = await opened(source);
    const before = asked(source);

    runtime.edit({ sort: BY_AMOUNT });
    expect(runtime.getSnapshot().applied.sort).not.toEqual(BY_AMOUNT);

    // The store is written only after the page the new sort asked for.
    save.mockImplementationOnce((...args) => {
      expect(asked(source)).toBe(before + 1);
      expect(runtime.getSnapshot().query.status).toBe('success');
      return MemoryViewStore.prototype.save.apply(store, args);
    });
    await engine.save(runtime);

    expect(save).toHaveBeenCalledTimes(1);
    expect(runtime.getSnapshot().applied.sort).toEqual(BY_AMOUNT);
    expect((await store.get('mine')).config).toMatchObject({ sort: BY_AMOUNT });
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('writes nothing when the source refuses what waited', async () => {
    const page = { total: 2, exact: true, list: [...ROWS] };
    const paged = vi
      .fn<ViewSource['paged']>()
      .mockResolvedValueOnce(page)
      .mockRejectedValue(new Error('The source refuses this sort.'));
    const cursor = vi
      .fn<ViewSource['cursor']>()
      .mockResolvedValueOnce({ nextCursor: 'cursor-2', list: [...ROWS] })
      .mockRejectedValue(new Error('The source refuses this sort.'));
    const { engine, runtime, store, save } = await opened(
      testSource({ paged, cursor }),
    );

    runtime.edit({ sort: BY_AMOUNT });
    const refused = await engine.save(runtime).catch((error: unknown) => error);

    expect(isViewCommandError(refused)).toBe(true);
    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.save.not-applied',
    );
    expect(save).not.toHaveBeenCalled();
    expect((await store.get('mine')).revision).toBe('1');
    // The draft is kept, and the failure is where a failed apply always is.
    expect(runtime.getSnapshot().draft.sort).toEqual(BY_AMOUNT);
    expect(runtime.getSnapshot().dirty).toBe(true);
    expect(runtime.getSnapshot().query.status).toBe('error');
  });

  it('asks again what failed, on the next press, and saves once it answers', async () => {
    const page = { total: 2, exact: true, list: [...ROWS] };
    const lost = new Error('The source is away.');
    // Open, then the apply the first save makes, then the second save's.
    const paged = vi
      .fn<ViewSource['paged']>()
      .mockResolvedValueOnce(page)
      .mockRejectedValueOnce(lost)
      .mockResolvedValue(page);
    const cursor = vi
      .fn<ViewSource['cursor']>()
      .mockResolvedValueOnce({ nextCursor: 'cursor-2', list: [...ROWS] })
      .mockRejectedValueOnce(lost)
      .mockResolvedValue({ nextCursor: 'cursor-2', list: [...ROWS] });
    const source = testSource({ paged, cursor });
    const { engine, runtime, store, save } = await opened(source);

    runtime.edit({ sort: BY_AMOUNT });
    await engine.save(runtime).catch(() => undefined);
    expect(save).not.toHaveBeenCalled();
    // Nothing waits now: the failed apply took the draft. The next save
    // must not write it unanswered.
    const before = asked(source);
    await engine.save(runtime);

    expect(asked(source)).toBe(before + 1);
    expect(save).toHaveBeenCalledTimes(1);
    expect((await store.get('mine')).config).toMatchObject({ sort: BY_AMOUNT });
    expect(runtime.getSnapshot().query.status).toBe('success');
  });

  it('copies what ran, too', async () => {
    const source = testSource();
    const { engine, runtime, create } = await opened(source);
    const before = asked(source);

    runtime.edit({ sort: BY_AMOUNT });
    const copy = await engine.saveAs(runtime, {
      title: 'By amount',
      scope: 'personal',
    });

    expect(asked(source)).toBe(before + 1);
    expect(create).toHaveBeenCalledTimes(1);
    expect(copy.config).toMatchObject({ sort: BY_AMOUNT });
    expect(runtime.getSnapshot().applied.sort).toEqual(BY_AMOUNT);
  });
});

describe('a draft that changes while its save waits', () => {
  const PAGE = { total: 2, exact: true, list: [...ROWS] };

  /** A source whose pages after the first are answered when the test says. */
  function heldSource() {
    const held: ReturnType<typeof deferred<typeof PAGE>>[] = [];
    let opened = false;
    const paged = vi.fn<ViewSource['paged']>(() => {
      if (!opened) {
        opened = true;
        return Promise.resolve(PAGE);
      }
      const next = deferred<typeof PAGE>();
      held.push(next);
      return next.promise;
    });
    return { source: testSource({ paged }), held };
  }

  it('runs what was edited during the wait, and writes the draft that was answered', async () => {
    const { source, held } = heldSource();
    const { engine, runtime, store, save } = await opened(source);

    runtime.edit({ sort: BY_AMOUNT });
    const saving = engine.save(runtime);
    await nextTask();
    expect(held).toHaveLength(1);
    // Typed while the first query is out: it waits again.
    runtime.edit({ pageSize: 30 });
    held[0].resolve(PAGE);
    await nextTask();

    // Not written yet: the page size has not run.
    expect(save).not.toHaveBeenCalled();
    expect(held).toHaveLength(2);
    held[1].resolve(PAGE);
    await saving;

    expect(save).toHaveBeenCalledTimes(1);
    expect(runtime.getSnapshot().applied).toMatchObject({
      sort: BY_AMOUNT,
      pageSize: 30,
    });
    expect((await store.get('mine')).config).toMatchObject({
      sort: BY_AMOUNT,
      pageSize: 30,
    });
  });

  it('is not saved when it is still changing after three runs', async () => {
    const { source, held } = heldSource();
    const { engine, runtime, save } = await opened(source);

    runtime.edit({ sort: BY_AMOUNT });
    const saving = engine.save(runtime).catch((error: unknown) => error);
    for (let run = 0; run < 3; run += 1) {
      await nextTask();
      expect(held).toHaveLength(run + 1);
      runtime.edit({ pageSize: 30 + run });
      held[run].resolve(PAGE);
    }
    const refused = await saving;

    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.save.not-applied',
    );
    expect(save).not.toHaveBeenCalled();
    // Three runs, and no fourth.
    await nextTask();
    expect(held).toHaveLength(3);
  });
});

describe('a view closed while its save waits', () => {
  it('ends the save, refused, and writes nothing', async () => {
    const never = new Promise<never>(() => undefined);
    const paged = vi
      .fn<ViewSource['paged']>()
      .mockResolvedValueOnce({ total: 2, exact: true, list: [...ROWS] })
      .mockReturnValue(never);
    const { engine, runtime, save } = await opened(testSource({ paged }));

    runtime.edit({ sort: BY_AMOUNT });
    const saving = engine.save(runtime).catch((error: unknown) => error);
    await nextTask();
    expect(runtime.getSnapshot().query.status).toBe('loading');
    runtime.dispose();
    const refused = await saving;

    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.runtime.not-owned',
    );
    expect(save).not.toHaveBeenCalled();
  });
});

describe('a save with nothing waiting', () => {
  it('asks the source nothing more', async () => {
    const source = testSource();
    const { engine, runtime, save } = await opened(source);

    runtime.edit({ sort: BY_AMOUNT });
    runtime.apply();
    await nextTask();
    const before = asked(source);
    await engine.save(runtime);

    expect(asked(source)).toBe(before);
    expect(save).toHaveBeenCalledTimes(1);
  });

  it('leaves a draft with an error to the save’s own refusal', async () => {
    const source = testSource();
    const { engine, runtime, save } = await opened(source);
    const before = asked(source);

    runtime.edit({
      sort: [{ field: 'no-such-field', direction: SortDirection.ASC }],
    });
    const refused = await engine.save(runtime).catch((error: unknown) => error);

    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.config.invalid',
    );
    expect(asked(source)).toBe(before);
    expect(save).not.toHaveBeenCalled();
  });
});
