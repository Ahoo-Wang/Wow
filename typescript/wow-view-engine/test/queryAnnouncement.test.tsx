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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { PagedList } from '@ahoo-wang/wow-client';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { RecordData } from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { defaultMessages } from '../src/ui/messages.js';
import { querySentence } from '../src/ui/record/queryAnnouncement.js';
import {
  deferred,
  mine,
  ordersDefinition,
  ROWS,
  testSource,
} from './fixtures.js';
import { formattersFor } from './fixtures/columns.js';
import { recordTableController } from './fixtures/ui.js';
import { cursorPaging } from '../src/record/index.js';

afterEach(cleanup);

/** What the result block's live region is holding right now. */
function announced(): string {
  const region = document.querySelector(
    '[data-slot="record-announcement"]',
  ) as HTMLElement | null;
  if (!region) throw new Error('no live region');
  return region.textContent ?? '';
}

function engineWith(paged: () => Promise<PagedList<RecordData>>): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () => testSource({ paged }),
  });
}

/**
 * A query is the one thing a record view does that nobody is told about: the
 * rows change under a reader who is not looking at them, and the toolbar,
 * the pagination and the empty state all say so only on screen.
 */
describe('what a record query says out loud', () => {
  it('is one region, and it belongs to the result', async () => {
    render(
      <DataWorkbench
        engine={engineWith(() =>
          Promise.resolve({ total: 2, list: [...ROWS] }),
        )}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(announced()).toBe('2 records in all'));

    // Polite, and the only announcer on this surface: two of them are two
    // voices answering the same key (`ui/Announcer.tsx`). The transient
    // `role="status"` a landed save inserts is not one — it has no
    // `aria-live` and is read because it appears, not because it changed.
    const regions = document.querySelectorAll(
      '[role="status"][aria-live="polite"]',
    );
    expect(regions).toHaveLength(1);
    expect(regions[0]?.getAttribute('data-slot')).toBe('record-announcement');
  });

  it('says a query is running, then what came back', async () => {
    const first = deferred<PagedList<RecordData>>();
    render(
      <DataWorkbench
        engine={engineWith(() => first.promise)}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(announced()).toBe('Running the query'));

    first.resolve({ total: 2, list: [...ROWS] });
    await waitFor(() => expect(announced()).toBe('2 records in all'));
  });

  it('says a result that matched nothing, in the words on the screen', async () => {
    render(
      <DataWorkbench
        engine={engineWith(() => Promise.resolve({ total: 0, list: [] }))}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(announced()).toBe('Nothing to show'));
  });

  /**
   * A cursor source is a position in one order and never answers with a
   * total, so the announcement says the one number it holds — the same rule
   * the pagination bar reads by, so the two can never disagree. Driven
   * through the controller rather than a workbench: what a source's paging
   * mode *is* belongs to `recordPagination.test.tsx`.
   */
  it("says the empty result in the host's own words, where it has some", async () => {
    render(
      <DataWorkbench
        engine={engineWith(() => Promise.resolve({ total: 0, list: [] }))}
        definitionId="orders"
        instanceId="orders-1"
        record={{ emptyTitle: 'No orders are waiting' }}
      />,
    );

    // The title on screen and the sentence said are one sentence: a host
    // that put the case in its own words did not put it in one of them.
    await waitFor(() => expect(announced()).toBe('No orders are waiting'));
  });

  it('counts what arrived when the source gives no total', () => {
    const messages = formattersFor(defaultMessages);

    expect(
      querySentence(
        recordTableController({
          paging: cursorPaging('c-2'),
        }),
        messages,
      ),
    ).toBe('2 on this page');
  });

  it('leaves a failure to the alert that already interrupts', async () => {
    render(
      <DataWorkbench
        engine={engineWith(() => Promise.reject(new Error('down')))}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    // The failure is a `role="alert"` strip of its own, which interrupts on
    // its own account; the polite region says none of it, and a result that
    // never arrived is not announced as an empty one either.
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'Could not load the data: down',
      ),
    );
    expect(announced()).not.toContain('down');
    expect(announced()).not.toContain('Nothing to show');
  });

  it('reads each query back, however alike two results are', async () => {
    render(
      <DataWorkbench
        engine={engineWith(() =>
          Promise.resolve({ total: 2, list: [...ROWS] }),
        )}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(announced()).toBe('2 records in all'));

    // A refresh answers with the very same rows. The region empties to the
    // running sentence in between, so the reader hears the second query
    // rather than a region that never changed.
    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));
    await waitFor(() => expect(announced()).toBe('Running the query'));
    await waitFor(() => expect(announced()).toBe('2 records in all'));
  });

  it('says it in the catalogue in force', async () => {
    render(
      <DataWorkbench
        engine={engineWith(() =>
          Promise.resolve({ total: 2, list: [...ROWS] }),
        )}
        definitionId="orders"
        instanceId="orders-1"
        messages={{ 'label.pagination.total': '共 {total} 条记录' }}
      />,
    );

    await waitFor(() => expect(announced()).toBe('共 2 条记录'));
  });
});
