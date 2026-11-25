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
 * The view store service's checks `MemoryViewStore` answers as the service
 * does (#7067): those the port's conformance suite leaves to the service's
 * own limits, each as an `INVALID` refusal in the service's words
 * (`ViewConfigs`, `ViewPreferences.problemOf` of `platform/view-store`).
 */

import { describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  type ViewConfig,
  type ViewInstance,
  type ViewPreferences,
} from '../src/index.js';
import { dashboardConfig, recordConfig } from './fixtures.js';

const LONG_ID = 'x'.repeat(257);
let requests = 0;
const write = () => ({ requestId: `rule-${(requests += 1)}` });

/** What `pending` rejected with, as `code: message`; `resolved` when it did not. */
async function refusalOf(pending: Promise<unknown>): Promise<string> {
  return pending.then(
    () => 'resolved',
    (error: { code?: string; message?: string }) =>
      `${error.code}: ${error.message}`,
  );
}

function create(
  store: MemoryViewStore,
  definitionId: string,
  config: ViewConfig,
) {
  return store.create(
    { definitionId, title: 'A view', scope: 'personal', config },
    write(),
  );
}

describe("MemoryViewStore answers the view store service's checks", () => {
  it('refuses a definition id longer than the service indexes', async () => {
    const store = new MemoryViewStore();
    expect(await refusalOf(create(store, LONG_ID, recordConfig()))).toBe(
      "INVALID: A view's definitionId is longer than 256 characters.",
    );
    expect(
      await refusalOf(create(store, 'x'.repeat(256), recordConfig())),
    ).toBe('resolved');
  });

  it('refuses the panels the service cannot index, on a create and a save', async () => {
    const store = new MemoryViewStore();
    const board = (panels: unknown) =>
      dashboardConfig({ panels: panels as never });
    const cases: [unknown, string][] = [
      ['x', "A view's config panels must be an array."],
      [
        Array.from({ length: 1001 }, () => ({})),
        "A view's config holds 1001 panels; the limit is 1000.",
      ],
      [[1], "Each of a view's config panels must be an object."],
      [[[]], "Each of a view's config panels must be an object."],
      [
        [{ instanceId: 5 }],
        "A panel's instanceId must be a string of at most 256 characters.",
      ],
      [
        [{ opens: LONG_ID }],
        "A panel's opens must be a string of at most 256 characters.",
      ],
      [[{ click: 'x' }], "A panel's click must be an object."],
      [
        [{ click: { instanceId: LONG_ID } }],
        "A panel's click.instanceId must be a string of at most 256 characters.",
      ],
    ];
    for (const [panels, message] of cases)
      expect(await refusalOf(create(store, 'boards', board(panels)))).toBe(
        `INVALID: ${message}`,
      );
    // What it indexes: absent members, null ones, and references it can hold.
    for (const panels of [
      null,
      [],
      [{ instanceId: null, click: null }],
      [{ instanceId: 'a', opens: 'b', click: { instanceId: 'c' } }],
      [{ click: {} }],
    ])
      expect(
        await refusalOf(create(store, 'boards', board(panels))),
        JSON.stringify(panels),
      ).toBe('resolved');
    const made = await create(store, 'boards', board([]));
    expect(
      await refusalOf(store.save(made.id, board([1]), made.revision, write())),
    ).toBe("INVALID: Each of a view's config panels must be an object.");
    expect(
      await refusalOf(
        store.save(
          made.id,
          { kind: 'chart' } as unknown as ViewConfig,
          made.revision,
          write(),
        ),
      ),
    ).toBe(
      "INVALID: A view's config kind must be one of [record, analysis, dashboard].",
    );
  });

  it('refuses preferences of a blank definition, or holding an id longer than the service indexes', async () => {
    const store = new MemoryViewStore();
    const preferences = (
      overrides: Partial<ViewPreferences> = {},
    ): ViewPreferences => ({
      order: [],
      defaultInstanceId: null,
      revision: '0',
      ...overrides,
    });
    expect(
      await refusalOf(store.setPreferences(' ', preferences(), write())),
    ).toBe("INVALID: The preferences' definitionId must not be blank.");
    expect(
      await refusalOf(store.setPreferences(LONG_ID, preferences(), write())),
    ).toBe(
      "INVALID: The preferences' definitionId holds an id longer than 256 characters.",
    );
    expect(
      await refusalOf(
        store.setPreferences(
          'orders',
          preferences({ order: ['a', LONG_ID] }),
          write(),
        ),
      ),
    ).toBe(
      "INVALID: The preferences' order holds an id longer than 256 characters.",
    );
    expect(
      await refusalOf(
        store.setPreferences(
          'orders',
          preferences({ defaultInstanceId: LONG_ID }),
          write(),
        ),
      ),
    ).toBe(
      "INVALID: The preferences' defaultInstanceId holds an id longer than 256 characters.",
    );
  });

  it('spends a revision on preferences only when something set changes', async () => {
    const store = new MemoryViewStore();
    let held = await store.setPreferences(
      'orders',
      {
        order: ['a'],
        defaultInstanceId: null,
        autoRun: true,
        lastTabs: { board: 'one' },
        revision: '0',
      },
      write(),
    );
    const changes: [string, Partial<ViewPreferences>, boolean][] = [
      ['the same', {}, false],
      ['the same tabs, another object', { lastTabs: { board: 'one' } }, false],
      ['another tab', { lastTabs: { board: 'two' } }, true],
      ['one more tab', { lastTabs: { board: 'two', other: 'x' } }, true],
      ['no tabs', { lastTabs: undefined }, true],
      ['tabs again', { lastTabs: { board: 'two' } }, true],
      ['auto-run off', { autoRun: false }, true],
      ['another order', { order: ['b'] }, true],
      ['a longer order', { order: ['b', 'c'] }, true],
      ['a default', { defaultInstanceId: 'b' }, true],
    ];
    for (const [what, change, spends] of changes) {
      const next = await store.setPreferences(
        'orders',
        { ...held, ...change },
        write(),
      );
      expect(next.revision !== held.revision, what).toBe(spends);
      held = next;
    }
  });

  it('refuses a list cursor of an audience it does not have', async () => {
    const store = new MemoryViewStore();
    for (const cursor of ['3:1', '0:0', '1:', 'x'])
      expect(await refusalOf(store.list('orders', { cursor })), cursor).toMatch(
        /^INVALID: Not a list cursor of this store/,
      );
    await expect(store.list('orders', { cursor: null })).resolves.toEqual({
      items: [],
      nextCursor: null,
    });
  });

  // QueriedBoardReferences (D486 ④): a claim is checked against the shared
  // boards and the stored system ones, which every reader sees. A configured
  // system board is the deployment's, which the service never reads, and no
  // board holds itself.
  it('keeps a view shared for a stored system board that shows it, not for a configured one or for itself', async () => {
    const showing = (instanceId: string): ViewConfig =>
      dashboardConfig({
        panels: [
          {
            id: 'p1',
            kind: 'view',
            title: 'Shown',
            instanceId,
            layout: { x: 0, y: 0, w: 12, h: 6 },
            bindings: [],
          },
        ],
      });
    const shown: ViewInstance = {
      id: 'shown',
      definitionId: 'orders',
      title: 'Shown',
      scope: 'shared',
      revision: '1',
      config: recordConfig(),
    };
    const configured: ViewInstance = {
      id: 'configured-board',
      definitionId: 'overview',
      title: 'Configured board',
      scope: 'system',
      revision: '1',
      config: showing('shown'),
    };
    const itself: ViewInstance = {
      ...configured,
      id: 'itself',
      title: 'Itself',
      scope: 'shared',
      config: showing('itself'),
    };

    const store = new MemoryViewStore({
      instances: [shown, configured, itself],
    });
    expect(
      (await store.changeAudience('shown', 'personal', '1', write())).scope,
    ).toBe('personal');
    expect(
      (await store.changeAudience('itself', 'personal', '1', write())).scope,
    ).toBe('personal');

    const stored = new MemoryViewStore({
      instances: [
        shown,
        { ...configured, title: 'Stored board', stored: true },
      ],
    });
    await expect(
      stored.changeAudience('shown', 'personal', '1', write()),
    ).rejects.toMatchObject({ code: 'INVALID', boards: ['Stored board'] });
  });
});
