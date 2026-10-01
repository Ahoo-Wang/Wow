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
 * A view saved by a newer engine, opened by this one (model.md「新引擎写的配置
 * 在旧引擎里」): Wow minors run mixed, and two applications — or a stale tab —
 * share one view store. A member this engine does not know is carried
 * through open → edit → save untouched; a value of a closed set it does not
 * know is an admission error that keeps the view as stored, so nothing the
 * newer engine wrote is ever dropped by the older one.
 */

import { describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  isViewCommandError,
  type RecordViewRuntime,
  type ViewConfig,
  type ViewInstance,
} from '../src/index.js';
import {
  analysisConfig,
  nextTask,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';

function engineOver(store: MemoryViewStore): ViewEngine {
  return new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => testSource()),
    store,
    environment: testEnvironment().environment,
  });
}

function stored(config: unknown): ViewInstance {
  return {
    id: 'newer',
    definitionId: 'orders',
    title: 'Saved by a newer engine',
    scope: 'personal',
    revision: '1',
    config: config as ViewConfig,
  };
}

describe('a view a newer engine saved', () => {
  it('keeps the members this engine does not know through open, edit and save', async () => {
    const base = recordConfig();
    const newer = {
      ...base,
      // A member of the config, and one on a part of it, that a later
      // minor added and this engine has never heard of.
      grouping: { field: 'status', collapsed: true },
      table: {
        ...base.table,
        columns: [{ field: 'id', wrap: 'never' }, { field: 'amount' }],
      },
    };
    const store = new MemoryViewStore({ instances: [stored(newer)] });
    const engine = engineOver(store);

    const runtime = (await engine.open('newer')) as RecordViewRuntime;
    await nextTask();
    expect(runtime.getSnapshot().issues).toEqual([]);
    expect(runtime.getSnapshot().dirty).toBe(false);

    runtime.edit({ sort: [{ field: 'amount', direction: 'DESC' }] });
    runtime.apply();
    await engine.save(runtime);

    const written = (await store.get('newer')).config as unknown as Record<
      string,
      unknown
    >;
    expect(written.sort).toEqual([{ field: 'amount', direction: 'DESC' }]);
    expect(written.grouping).toEqual({ field: 'status', collapsed: true });
    expect(written.table).toEqual({
      columns: [{ field: 'id', wrap: 'never' }, { field: 'amount' }],
    });

    // Read back by a fresh engine, the view is the one it saved, clean.
    const again = await engineOver(store).open('newer');
    await nextTask();
    expect(again.getSnapshot().dirty).toBe(false);
    expect(again.getSnapshot().draft).toMatchObject({
      grouping: { field: 'status', collapsed: true },
    });
  });

  it('is refused, not rewritten, when it names a value this engine does not know', async () => {
    const newer = {
      ...analysisConfig(),
      layout: 'chart',
      // A chart family a later minor added.
      chart: {
        type: 'pareto',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
    };
    const store = new MemoryViewStore({ instances: [stored(newer)] });
    const engine = engineOver(store);

    const runtime = await engine.open('newer');
    await nextTask();
    expect(runtime.getSnapshot().issues).toEqual([
      expect.objectContaining({
        code: 'chart.type.unknown',
        severity: 'error',
        path: ['chart', 'type'],
      }),
    ]);

    runtime.edit({ limit: 50 });
    const refused = await engine.save(runtime).catch((error: unknown) => error);
    expect(isViewCommandError(refused)).toBe(true);
    const kept = await store.get('newer');
    expect(kept.revision).toBe('1');
    expect(kept.config).toEqual(newer);
  });
});
