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

import { expect, it, vi } from 'vitest';
import { dashboardSetup } from './runtimeFixtures.js';
import {
  instance,
  deferred,
  setup,
  definition as recordDefinition,
} from '../engine/fixtures.js';
const config = {
  schemaVersion: 1 as const,
  filters: [],
  panels: [
    {
      kind: 'view' as const,
      id: 'a',
      instanceId: 'child',
      layout: { x: 0, y: 0, w: 12, h: 18 },
    },
  ],
};
it('reload survives synchronous replacement during reset publication', async () => {
  const gate = deferred<ReturnType<typeof instance>>();
  const load = vi.fn((id: string) =>
    id === 'other' ? gate.promise : Promise.resolve(instance(id)),
  );
  const { engine, paged } = dashboardSetup(config, {
    instance: { load, save: async v => v },
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
  let replaced = false;
  const off = runtime.subscribe(() => {
    if (!replaced && runtime.getSnapshot().panels.a.status === 'suspended') {
      replaced = true;
      runtime.edit(c => ({
        ...c,
        panels: c.panels.map(p => ({ ...p, instanceId: 'other' })),
      }));
    }
  });
  try {
    await runtime.reloadReference('a');
    expect(replaced).toBe(true);
    gate.resolve(instance('other'));
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().panels.a).toMatchObject({
        status: 'ready',
        instance: { id: 'other' },
      }),
    );
    expect(paged).toHaveBeenCalledTimes(2);
  } finally {
    off();
    engine.dispose();
  }
});

it('overlapping reloads preserve the newer request on the same owner', async () => {
  const { engine, paged, load } = dashboardSetup(config);
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
  const old = deferred<ReturnType<typeof instance>>(),
    fresh = deferred<ReturnType<typeof instance>>();
  load
    .mockImplementationOnce(() => old.promise)
    .mockImplementationOnce(() => fresh.promise);
  try {
    const first = runtime.reloadReference('a');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(2));
    const second = runtime.reloadReference('a');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(3));
    fresh.resolve(instance('child'));
    await Promise.all([first, second]);
    expect(runtime.getSnapshot().panels.a.status).toBe('ready');
  } finally {
    engine.dispose();
  }
});
it('reloading a panel does not cancel a sibling reload', async () => {
  const cfg = {
    ...config,
    panels: [...config.panels, { ...config.panels[0], id: 'b' }],
  };
  const { engine, paged, load } = dashboardSetup(cfg);
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const a = deferred<ReturnType<typeof instance>>(),
    b = deferred<ReturnType<typeof instance>>();
  load
    .mockImplementationOnce(() => a.promise)
    .mockImplementationOnce(() => b.promise);
  try {
    const first = runtime.reloadReference('a');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(3));
    const second = runtime.reloadReference('b');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(4));
    a.resolve(instance('child'));
    await first;
    b.resolve(instance('child'));
    await second;
    expect(runtime.getSnapshot().panels.b.status).toBe('ready');
  } finally {
    engine.dispose();
  }
});

it.each(['suspended', 'loading'] as const)(
  'settles an obsolete reload without joining a reload started by its %s observer',
  async phase => {
    const { engine, paged, load } = dashboardSetup(config);
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
    const gate = deferred<ReturnType<typeof instance>>();
    load.mockImplementationOnce(() => gate.promise);
    let replacement: Promise<void> | undefined;
    let started = false;
    const off = runtime.subscribe(() => {
      if (!started && runtime.getSnapshot().panels.a.status === phase) {
        started = true;
        replacement = runtime.reloadReference('a');
      }
    });
    try {
      let settled = false;
      const obsolete = runtime.reloadReference('a').then(() => {
        settled = true;
      });
      await vi.waitFor(() => expect(settled).toBe(true));
      expect(load).toHaveBeenCalledTimes(2);
      expect(runtime.getSnapshot().panels.a.status).toBe('loading');
      gate.resolve(instance('child'));
      await Promise.all([obsolete, replacement]);
      expect(paged).toHaveBeenCalledTimes(2);
      expect(runtime.getSnapshot().panels.a.status).toBe('ready');
    } finally {
      off();
      engine.dispose();
    }
  },
);

it('keeps a reload started by an abort observer deduplicated across configuration changes', async () => {
  const old = deferred<ReturnType<typeof instance>>();
  const fresh = deferred<ReturnType<typeof instance>>();
  const load = vi.fn<
    (id: string, signal?: AbortSignal) => Promise<ReturnType<typeof instance>>
  >(() => Promise.resolve(instance('child')));
  const { engine, paged } = dashboardSetup(config, {
    instance: { load, save: async value => value },
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
  let nested: Promise<void> | undefined;
  load
    .mockImplementationOnce((_id, signal) => {
      signal!.addEventListener(
        'abort',
        () => {
          nested = runtime.reloadReference('a');
        },
        { once: true },
      );
      return old.promise;
    })
    .mockImplementationOnce(() => fresh.promise);
  try {
    const first = runtime.reloadReference('a');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(2));
    const superseded = runtime.reloadReference('a');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(3));
    runtime.edit(current => ({
      ...current,
      panels: current.panels.map(panel => ({
        ...panel,
        layout: { ...panel.layout, w: 6 },
      })),
    }));
    const applied = runtime.apply();
    fresh.resolve(instance('child'));
    await Promise.all([first, superseded, nested, applied]);
    expect(runtime.getSnapshot().panels.a.status).toBe('ready');
    expect(load).toHaveBeenCalledTimes(3);
    expect(paged).toHaveBeenCalledTimes(2);
  } finally {
    engine.dispose();
  }
});

it('does not reacquire metadata after an abort observer disposes its embedding', async () => {
  const gate = deferred<ReturnType<typeof instance>>();
  let dispose = () => {};
  const load = vi.fn((_id: string, signal?: AbortSignal) => {
    signal!.addEventListener('abort', () => dispose(), { once: true });
    return gate.promise;
  });
  const { engine } = setup({
    host: {
      resolveSource: () => ({ paged: async () => ({ total: 0, list: [] }) }),
      instance: { load },
      definition: { load: async () => recordDefinition },
    },
    limits: {
      maxDashboardMetadataBytes:
        2 * new TextEncoder().encode(JSON.stringify(config)).byteLength,
    },
  });
  const saved = {
    id: 'copy',
    definitionId: 'root',
    kind: 'dashboard' as const,
    title: 'Copy',
    revision: 'r1',
    scope: { type: 'personal' as const },
    config,
  };
  const definition = {
    id: 'root',
    title: 'Root',
    dashboard: true as const,
    fields: [],
  };
  try {
    await engine.load();
    const position = engine.openPosition(saved, definition);
    dispose = () => position.dispose();
    const loading = position.runtime.resume();
    await vi.waitFor(() => expect(load).toHaveBeenCalledOnce());
    await position.runtime.reloadReference('a');
    await loading;
    expect(position.runtime.isDisposed).toBe(true);
    const reopened = engine.openPosition(saved, definition);
    reopened.dispose();
    expect(load).toHaveBeenCalledOnce();
  } finally {
    engine.dispose();
  }
});
