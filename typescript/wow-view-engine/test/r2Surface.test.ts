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
 * Round two's review of the public surface before the beta freezes it: each
 * test is a promise the package makes (its READMEs, the documentation, a
 * TSDoc, the service the fakes stand in for) that the code does not keep.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createElement } from 'react';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FilterOperator } from '@ahoo-wang/wow-client/dsl';
import {
  MemoryViewStore,
  ViewEngine,
  actions,
  isViewStoreError,
  type DashboardViewConfig,
  type RecordAction,
  type ViewInstance,
} from '../src/index.js';
import { memorySource } from '../src/testing/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { setup } from './fixtures/ui.js';
import {
  dashboardConfig,
  overviewDefinition,
  recordConfig,
  testEnvironment,
} from './fixtures.js';

afterEach(cleanup);

const ctx = (requestId: string) => ({ requestId });

function personal(overrides: Partial<ViewInstance> = {}): ViewInstance {
  return {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'Mine',
    scope: 'personal',
    revision: '1',
    config: recordConfig(),
    ...overrides,
  };
}

async function refusedAs(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (error) {
    return isViewStoreError(error) ? error.code : `threw ${String(error)}`;
  }
  return 'resolved';
}

describe('README', () => {
  const readme = (name: string) =>
    readFileSync(join(import.meta.dirname, '..', name), 'utf8');

  it('promises no migration on read, which the engine does not do (AGENTS.md「Stored views are read as they are」)', async () => {
    // A board saved in an older form: the 12-column grid.
    const board = {
      ...dashboardConfig(),
      columns: 12,
    } as unknown as DashboardViewConfig;
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'board-1',
          definitionId: 'overview',
          title: 'Old board',
          scope: 'personal',
          revision: '1',
          config: board,
        },
      ],
    });
    const engine = new ViewEngine({
      store,
      resources: [{ definition: overviewDefinition() }],
      environment: testEnvironment().environment,
    });
    const runtime = await engine.open('board-1');
    // Refused, not migrated: the board does not keep opening.
    expect(runtime.getSnapshot().issues.map(found => found.code)).toContain(
      'dashboard.grid.unsupported',
    );
    expect(runtime.getSnapshot().draft).toMatchObject({ columns: 12 });

    // So the READMEs must not tell a host the opposite.
    expect(readme('README.md')).not.toMatch(/migrates stored configs on read/);
    expect(readme('README.zh-CN.md')).not.toMatch(/读取时迁移存储的配置/);
  });
});

describe('MemoryViewStore against the view store service', () => {
  // platform/view-store/view-store-domain/.../view/View.kt onRename, and
  // ViewSpec.kt「RenameView("Closed orders") { expectUnchanged() }」.
  it('answers a rename to the title the view has with the view as it is, revision unmoved', async () => {
    const store = new MemoryViewStore({ instances: [personal()] });
    const renamed = await store.rename('orders-1', 'Mine', '1', ctx('r-1'));
    expect(renamed.revision).toBe('1');
    // A second writer holding revision 1 still writes: nothing moved.
    await expect(
      store.save('orders-1', recordConfig({ pageSize: 50 }), '1', ctx('r-2')),
    ).resolves.toMatchObject({ revision: '2' });
  });

  // .../preferences/ViewPreferences.kt onSet, and ViewPreferencesSpec.kt
  // 「whenCommand(first) { expectUnchanged() }」.
  it('answers preferences set to what is stored with the revision unmoved', async () => {
    const store = new MemoryViewStore();
    const first = await store.setPreferences(
      'orders',
      { order: ['orders-1'], defaultInstanceId: null, revision: '0' },
      ctx('p-1'),
    );
    const again = await store.setPreferences(
      'orders',
      { ...first },
      ctx('p-2'),
    );
    expect(again.revision).toBe(first.revision);
  });

  // .../view/ViewConfigs.kt problemOf (kind) and definitionProblem: the
  // server refuses both as view-store.invalid, which WowViewStore reads as
  // INVALID. storage.md: "Code that is right against it behaves the same
  // against a real backend."
  it('refuses a config whose kind is no view kind as INVALID', async () => {
    const store = new MemoryViewStore();
    const bogus = {
      ...recordConfig(),
      kind: 'chart',
    } as unknown as ViewInstance['config'];
    expect(
      await refusedAs(
        store.create(
          {
            definitionId: 'orders',
            title: 'Bogus',
            scope: 'personal',
            config: bogus,
          },
          ctx('c-1'),
        ),
      ),
    ).toBe('INVALID');
  });

  it('refuses a blank definition id as INVALID', async () => {
    const store = new MemoryViewStore();
    expect(
      await refusedAs(
        store.create(
          {
            definitionId: '  ',
            title: 'Nowhere',
            scope: 'personal',
            config: recordConfig(),
          },
          ctx('c-2'),
        ),
      ),
    ).toBe('INVALID');
  });
});

describe('memorySource against a Wow query service', () => {
  const documents = [1, 2, 3, 4, 5].map(n => ({
    aggregateId: `a-${n}`,
    state: { n },
  }));
  const all = { op: FilterOperator.MATCH_ALL } as const;

  // query/query/.../backend/BackendQueries.kt: "A token that does not decode
  // for this model and effective sort is rejected as `Invalid cursor.`
  // before any I/O." host.md: "so a test sees what production gives".
  it('refuses a cursor it never issued, as the service does', async () => {
    const source = memorySource(documents);
    await expect(
      source.cursor({ filter: all, cursor: 'not-a-cursor', size: 2 }),
    ).rejects.toThrow();
    await expect(
      source.cursor({ filter: all, cursor: '-2', size: 2 }),
    ).rejects.toThrow();
  });

  // wow-client PagedList: "`total` is exact when `exact`, a lower bound
  // otherwise, and null for total `none`."
  it('answers a paged query asking total `none` with no total', async () => {
    const source = memorySource(documents);
    const page = await source.paged({
      filter: all,
      pagination: { index: 1, size: 2 },
      total: 'none',
    });
    expect(page.total).toBeNull();
  });
});

describe('README entries of the packages it sends a host to', () => {
  it('names no entry of @ahoo-wang/wow-view-store that the package does not export', () => {
    const exported = Object.keys(
      (
        JSON.parse(
          readFileSync(
            join(import.meta.dirname, '../../wow-view-store/package.json'),
            'utf8',
          ),
        ) as { exports: Record<string, unknown> }
      ).exports,
    );
    for (const name of ['README.md', 'README.zh-CN.md']) {
      const text = readFileSync(join(import.meta.dirname, '..', name), 'utf8');
      // "add `@ahoo-wang/wow-view-store` (its `/v10` entry on a Wow V10
      // service …)": D265 promoted `/v10` to the package root.
      const named = [
        ...text.matchAll(
          /wow-view-store[^)]*?\)[^`]*`(\/[a-z0-9-]+)` (?:entry|入口)/g,
        ),
      ]
        .map(match => `.${match[1]}`)
        .concat(
          [
            ...text.matchAll(
              /用它的 `(\/[a-z0-9-]+)` 入口|its `(\/[a-z0-9-]+)` entry/g,
            ),
          ].map(match => `.${match[1] ?? match[2]}`),
        );
      for (const entry of named)
        expect({
          readme: name,
          entry,
          exported: exported.includes(entry),
        }).toEqual({
          readme: name,
          entry,
          exported: true,
        });
    }
  });
});

describe('an action form the source refused, thrown by a second copy of the package', () => {
  /**
   * What `new ActionInputError(...)` is when the host's actions module was
   * bundled with another copy of the package: the same name and members,
   * another class. Every other error the engine and a host exchange is
   * recognised by name for exactly this case (src/model/storeError.ts
   * `isViewStoreError`: "Every guard of the engine's errors reads them so").
   */
  class ActionInputError extends Error {
    readonly reason: string;
    readonly fields: Readonly<Record<string, string>>;
    constructor(reason: string, fields: Readonly<Record<string, string>> = {}) {
      super(reason);
      this.name = 'ActionInputError';
      this.reason = reason;
      this.fields = { ...fields };
    }
  }

  it('comes back with the refused field marked, as one from this copy does (returnedForm.test.tsx)', async () => {
    const run = vi.fn<RecordAction['run']>().mockRejectedValueOnce(
      new ActionInputError('Not a duration: 1m 1h.', {
        backoff: 'Not a duration: 1m 1h.',
      }),
    );
    const harness = setup();
    render(
      createElement(DataWorkbench, {
        engine: harness.engine,
        definitionId: 'orders',
        instanceId: 'orders-1',
        record: {
          actions: actions([
            {
              id: 'spec',
              label: 'Apply spec',
              form: {
                count: { label: 'Count', required: false },
                backoff: { label: 'Backoff', required: false },
              },
              run,
            },
          ]),
        },
      }),
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const row = screen
      .getAllByRole('row')
      .find(found => within(found).queryByLabelText('Select o-1'))!;
    await userEvent.click(
      within(row).getByRole('button', { name: 'Actions for o-1' }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Apply spec' }),
    );
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Backoff'), {
      target: { value: '1m 1h' },
    });
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Apply spec' }),
    );
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));

    // The form comes back over the record, the field marked; any other
    // failure closes it.
    const returned = await screen.findByRole('dialog', {
      name: 'Apply spec: o-1',
    });
    expect(
      within(returned).getByLabelText('Backoff').getAttribute('aria-invalid'),
    ).toBe('true');
  });
});
