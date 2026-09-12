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

import { afterEach, expect, it, vi } from 'vitest';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import {
  deferred,
  instance,
  managementPermissions,
  selected,
  setup,
} from './fixtures.js';

const engines: ViewEngine[] = [];
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));

it.each(['abort', 'notification'] as const)(
  'keeps every published selection within current rows during %s reentry',
  async trigger => {
    const { engine, paged } = setup();
    engines.push(engine);
    await engine.load();
    const stalled = deferred<{ total: number; list: never[] }>();
    paged.mockReturnValueOnce(stalled.promise);
    const background = engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .refresh({ background: true });
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    paged.mockResolvedValue({
      total: 1,
      list: [{ state: { id: 'b', amount: 20 } }],
    });
    let newer: Promise<void> | undefined;
    const violations: unknown[] = [];
    let started = false;
    const refresh = () => {
      if (!started) {
        started = true;
        newer = engine
          .record(engine.getSnapshot().selectedInstanceId!)
          .refresh();
      }
    };
    const unsubscribe = engine.subscribe(() => {
      const session = selected(engine);
      const keys = new Set(session.rows.map(row => row.state.id));
      if (session.selectedRowKeys.some(key => !keys.has(key)))
        violations.push(session.selectedRowKeys);
      if (trigger === 'notification' && !session.refreshing && !started)
        refresh();
    });
    if (trigger === 'abort')
      paged.mock.calls[1][2].signal.addEventListener('abort', refresh, {
        once: true,
      });
    try {
      engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .setSelection(['a']);
      await newer;
      expect(selected(engine).rows).toEqual([
        { state: { id: 'b', amount: 20 } },
      ]);
      expect(selected(engine).selectedRowKeys).toEqual([]);
      expect(violations).toEqual([]);
    } finally {
      unsubscribe();
      stalled.resolve({ total: 0, list: [] });
      await background;
    }
  },
);

it.each(['save', 'rename', 'reload'] as const)(
  'permits a write synchronously from the %s completion notification',
  async operation => {
    const save = vi.fn(async value => ({ ...value, revision: 'r3' }));
    const rename = vi.fn(async (_id: string, title: string) => ({
      ...instance(),
      title,
      revision: 'r2',
    }));
    const { engine } = setup({
      host: {
        instance: {
          save,
          rename,
          load: async () => ({ ...instance(), title: 'First', revision: 'r2' }),
        },
        permission: { getInstance: managementPermissions },
      },
    });
    engines.push(engine);
    await engine.load();
    if (operation === 'save') engine.setTitle('First');
    let triggered = false;
    let next: Promise<void> | undefined;
    const unsubscribe = engine.subscribe(() => {
      const session = selected(engine);
      if (
        triggered ||
        session.writeStatus !== 'idle' ||
        session.baseline.title !== 'First'
      )
        return;
      triggered = true;
      engine.setTitle('Second');
      next = engine.save();
      void next.catch(() => {});
    });
    try {
      if (operation === 'save') await engine.save();
      else if (operation === 'rename') await engine.renameInstance('First');
      else await engine.reloadInstance();
      expect(triggered).toBe(true);
      await expect(next).resolves.toBeUndefined();
      expect(selected(engine).baseline.title).toBe('Second');
      expect(selected(engine).writeStatus).toBe('idle');
    } finally {
      unsubscribe();
    }
  },
);

it('releases a definitively rejected write before its error notification permits a retry', async () => {
  const save = vi
    .fn()
    .mockRejectedValueOnce(
      new ViewServiceError('INVALID_ARGUMENT', 'Try again'),
    )
    .mockImplementation(async value => ({ ...value, revision: 'r2' }));
  const { engine } = setup({ host: { instance: { save } } });
  engines.push(engine);
  await engine.load();
  engine.setTitle('First');
  let retried = false;
  let retry: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (
      !retried &&
      selected(engine).writeStatus === 'idle' &&
      selected(engine).writeError === 'Try again'
    ) {
      retried = true;
      retry = engine.save();
      void retry.catch(() => {});
    }
  });
  try {
    await expect(engine.save()).rejects.toThrow('Try again');
    expect(retried).toBe(true);
    await expect(retry).resolves.toBeUndefined();
    expect(save).toHaveBeenCalledTimes(2);
    expect(selected(engine).baseline.title).toBe('First');
  } finally {
    unsubscribe();
  }
});

it('makes the source writable in the save-as completion notification', async () => {
  const { engine, host } = setup();
  engines.push(engine);
  await engine.load();
  let creating = false,
    triggered = false;
  let saved: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    const source = engine.getSnapshot().sessions.mine;
    if (source.writeStatus === 'creating') creating = true;
    if (creating && !triggered && source.writeStatus === 'idle') {
      triggered = true;
      saved = engine.save('mine');
      void saved.catch(() => {});
    }
  });
  try {
    await engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
    expect(triggered).toBe(true);
    await expect(saved).resolves.toBeUndefined();
    expect(host.instance!.save).toHaveBeenCalledOnce();
  } finally {
    unsubscribe();
  }
});

it('allows a full load from the completed preference notification', async () => {
  const { engine } = setup({
    host: { preference: { saveOrder: async () => {} } },
  });
  engines.push(engine);
  await engine.load();
  let triggered = false;
  let reloaded: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (!triggered && engine.getSnapshot().instanceIds[0] === 'shared') {
      triggered = true;
      reloaded = engine.load();
      void reloaded.catch(() => {});
    }
  });
  try {
    await engine.reorderInstances(['shared', 'mine']);
    expect(triggered).toBe(true);
    await expect(reloaded).resolves.toBeUndefined();
    expect(engine.getSnapshot().status).toBe('ready');
  } finally {
    unsubscribe();
  }
});

it('releases the original creation before notifying that full-load recovery is cleared', async () => {
  const creation = deferred<ReturnType<typeof instance>>();
  const { engine } = setup({
    host: { instance: { create: () => creation.promise } },
  });
  engines.push(engine);
  await engine.load();
  const creating = engine.saveAs({
    title: 'Copy',
    scope: { type: 'personal' },
  });
  await engine.load();
  expect(selected(engine).requiresReload).toBe(true);
  let resumed = false;
  let saved: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (!resumed && !selected(engine).requiresReload) {
      resumed = true;
      saved = engine.save();
      void saved.catch(() => {});
    }
  });
  try {
    creation.reject(new ViewServiceError('INVALID_ARGUMENT', 'Rejected'));
    await creating;
    expect(resumed).toBe(true);
    await expect(saved).resolves.toBeUndefined();
  } finally {
    unsubscribe();
  }
});

it('keeps cursor navigation issued synchronously after reload ahead of its automatic refresh', async () => {
  const { engine, cursor } = setup({
    instances: {
      instances: [instance('mine', 'cursor')],
      defaultInstanceId: 'mine',
    },
    host: {
      instance: {
        load: async () => ({ ...instance('mine', 'cursor'), revision: 'r2' }),
      },
    },
  });
  engines.push(engine);
  cursor.mockImplementation(async query => ({
    list: [{ state: { id: query.cursor === null ? 'first' : 'second' } }],
    nextCursor: query.cursor === null ? 'second' : null,
  }));
  await engine.load();
  engine.setTitle('Keep local edit');
  let moved = false;
  let navigation: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (!moved && selected(engine).baseline.revision === 'r2') {
      moved = true;
      navigation = engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .nextPage();
      void navigation.catch(() => {});
    }
  });
  try {
    await engine.reloadInstance();
    await navigation;
    expect(moved).toBe(true);
    expect(selected(engine)).toMatchObject({
      page: 2,
      cursor: 'second',
      rows: [{ state: { id: 'second' } }],
    });
    expect(cursor.mock.calls.map(call => call[0].cursor)).toEqual([
      null,
      'second',
    ]);
  } finally {
    unsubscribe();
  }
});

it.each(['saveAs', 'delete'] as const)(
  'does not replace a page query started by %s completion',
  async operation => {
    const { engine, paged, host } = setup({
      host: {
        instance: { delete: async () => ({ defaultInstance: null }) },
        permission: { getInstance: managementPermissions },
      },
    });
    engines.push(engine);
    paged.mockImplementation(async query => ({
      total: 100,
      list: [{ state: { id: `row-${query.pagination.index}` } }],
    }));
    await engine.load();
    let moved = false;
    let navigation: Promise<void> | undefined;
    const unsubscribe = engine.subscribe(() => {
      if (!moved && engine.getSnapshot().selectedInstanceId !== 'mine') {
        moved = true;
        navigation = engine
          .record(engine.getSnapshot().selectedInstanceId!)
          .setPage(2);
        void navigation.catch(() => {});
      }
    });
    try {
      if (operation === 'saveAs')
        await engine.saveAs({ title: 'Copy', scope: { type: 'personal' } });
      else await engine.deleteInstance();
      await navigation;
      expect(moved).toBe(true);
      expect(host.resolveSource).toHaveBeenCalledTimes(2);
      expect(selected(engine)).toMatchObject({
        page: 2,
        rows: [{ state: { id: 'row-2' } }],
      });
      expect(paged.mock.calls.map(call => call[0].pagination.index)).toEqual([
        1, 2,
      ]);
    } finally {
      unsubscribe();
    }
  },
);
