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
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  hasAsked,
  type AnalysisViewConfig,
  type FilterTree,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import { wayOutOf } from '../src/ui/record/emptyWayOut.js';
import {
  analysisConfig,
  deferred,
  ordersDefinition,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const CN: FilterTree = {
  op: 'and',
  children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
};

function open(
  source: ViewSource,
  config: Partial<AnalysisViewConfig> = {},
): { source: ViewSource } {
  const view: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config: analysisConfig(config),
  };
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [view] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  return { source };
}

const slot = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);

/**
 * The result block's toolbar, once the view is open — not the opening
 * skeleton's stand-in for it, which wears the same slot and is hidden.
 */
async function toolbar(): Promise<HTMLElement> {
  return await waitFor(() => {
    const bar = document.querySelector<HTMLElement>(
      '[data-slot="result-toolbar"]:not([aria-hidden])',
    );
    expect(bar).not.toBeNull();
    return bar!;
  });
}

function appliedBar(): HTMLElement | null {
  return slot('applied-bar');
}

/**
 * The first answer on its way (2026-09-23 audit, P1): the result block was
 * blank until the rows arrived, and then the toolbar, the applied bar and
 * the caption all appeared at once, the result under them moving down. The
 * frame stands from the moment the question is sent, and the rows landing
 * change what is in it, not where anything is.
 */
describe('the analysis result while its first answer is on its way', () => {
  it('draws the toolbar, the conditions and the caption around a skeleton of the table', async () => {
    const waiting = deferred<Record<string, unknown>[]>();
    open(testSource({ aggregate: () => waiting.promise }), { filter: CN });

    const bar = await toolbar();
    // The reading is the question's, before any row has said it.
    expect(slot('analysis-reading')?.textContent).toMatch(/^By Warehouse · /);
    expect(
      within(bar).getByRole('button', { name: 'Table', pressed: true }),
    ).toBeDefined();
    expect(
      within(bar).getByRole('button', {
        name: defaultMessages['label.analysis.visualize'],
      }),
    ).toBeDefined();
    // What the question was sent under, which is what the rows will be.
    expect(appliedBar()?.textContent).toContain('CN');

    const skeleton = slot('analysis-table-skeleton');
    expect(skeleton?.getAttribute('aria-hidden')).toBe('true');
    // Three rows, a bar per column the table will draw.
    const rows = skeleton!.querySelectorAll('tr');
    expect(rows).toHaveLength(3);
    expect(rows[0].querySelectorAll('[data-slot="skeleton"]')).toHaveLength(2);
    const caption = slot('analysis-caption');
    expect(caption?.hasAttribute('data-loading')).toBe(true);
    expect(caption?.getAttribute('aria-hidden')).toBe('true');

    waiting.resolve([{ warehouse: 'CN', orders: 2 }]);
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    // The same toolbar and the same bar — not drawn anew once rows arrived.
    expect(await toolbar()).toBe(bar);
    expect(slot('analysis-table-skeleton')).toBeNull();
    expect(slot('analysis-caption')?.hasAttribute('data-loading')).toBe(false);
    expect(slot('analysis-caption')?.textContent).toMatch(/Showing 1/);
  });

  it('draws a chart area when the view is a chart', async () => {
    const waiting = deferred<Record<string, unknown>[]>();
    open(testSource({ aggregate: () => waiting.promise }), {
      layout: 'chart',
    });

    await toolbar();
    expect(slot('analysis-chart-skeleton')).not.toBeNull();
    expect(slot('analysis-table-skeleton')).toBeNull();
    // Switching the layout redraws the skeleton too; it asks nothing.
    fireEvent.click(screen.getByRole('button', { name: 'Table' }));
    await waitFor(() => expect(slot('analysis-table-skeleton')).not.toBeNull());

    waiting.resolve([{ warehouse: 'CN', orders: 2 }]);
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  });

  it('keeps the rows on screen through a refresh, with no skeleton over them', async () => {
    const later = deferred<Record<string, unknown>[]>();
    const aggregate = vi
      .fn()
      .mockResolvedValueOnce([{ warehouse: 'CN', orders: 2 }])
      .mockReturnValueOnce(later.promise);
    open(testSource({ aggregate }));
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));

    expect(screen.getByRole('table')).toBeDefined();
    expect(slot('analysis-table-skeleton')).toBeNull();
    expect(slot('analysis-caption')?.hasAttribute('data-loading')).toBe(false);
    later.resolve([{ warehouse: 'CN', orders: 3 }]);
    await waitFor(() => expect(screen.getByText('3')).toBeDefined());
  });
});

/**
 * A first query that failed left the failure alone on the page — no
 * toolbar, no conditions, nothing to press but the retry. The chrome stays,
 * the failure is the query strip the record view says it in, and it is
 * worded for the reader rather than transcribed from the source.
 */
describe('the analysis result when its query fails', () => {
  it('keeps the toolbar and the conditions, and says the failure under the toolbar with a retry', async () => {
    const aggregate = vi
      .fn()
      .mockRejectedValueOnce(new Error('gateway down'))
      .mockResolvedValue([{ warehouse: 'CN', orders: 2 }]);
    open(testSource({ aggregate }), { filter: CN });

    const strip = await waitFor(() => {
      const found = slot('status-strip');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(strip.getAttribute('role')).toBe('alert');
    expect(strip.textContent).toContain(
      'Could not load the data: gateway down',
    );
    const bar = await toolbar();
    // The strip is inside the result block, under the toolbar.
    expect(
      bar.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(appliedBar()?.textContent).toContain('CN');
    // Nothing is counted, and nothing pretends to be loading.
    expect(slot('analysis-caption')).toBeNull();
    expect(slot('analysis-table-skeleton')).toBeNull();

    // The switches still work: they redraw and never run.
    fireEvent.click(within(bar).getByRole('button', { name: 'Chart' }));
    await waitFor(() =>
      expect(
        within(bar).getByRole('button', { name: 'Chart', pressed: true }),
      ).toBeDefined(),
    );
    expect(aggregate).toHaveBeenCalledTimes(1);

    fireEvent.click(within(strip).getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(slot('status-strip')).toBeNull());
    expect(aggregate).toHaveBeenCalledTimes(2);
    expect(await toolbar()).toBe(bar);
  });

  it('opens the chart types over the question that failed', async () => {
    open(
      testSource({ aggregate: vi.fn().mockRejectedValue(new Error('down')) }),
    );
    const bar = await toolbar();
    await waitFor(() => expect(slot('status-strip')).not.toBeNull());

    fireEvent.click(
      within(bar).getByRole('button', {
        name: defaultMessages['label.analysis.visualize'],
      }),
    );
    // Fitted to the question's shape — one dimension, one count — so a bar
    // chart is on offer rather than everything greyed for want of rows.
    const tile = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-tile"][data-chart-type="bar"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(tile.hasAttribute('aria-disabled')).toBe(false);
    fireEvent.click(tile);
    await waitFor(() =>
      expect(
        within(bar).getByRole('button', { name: 'Chart', pressed: true }),
      ).toBeDefined(),
    );
  });
});

/**
 * A range that matched no group took the toolbar away and offered nothing
 * to do. The toolbar stays, and the empty result offers the one next step
 * the record view's does, chosen by the same rule.
 */
describe('the analysis result when no group matched', () => {
  const nothing = () => testSource({ aggregate: vi.fn(async () => []) });

  /**
   * With no condition in force the range is every record, and nothing the
   * tray can do produces a group: the reason, and no button (the user's
   * ruling on #1800).
   */
  it('keeps the toolbar and, with no condition in force, says why and offers nothing', async () => {
    open(nothing());
    const bar = await toolbar();
    const empty = await waitFor(() => {
      const found = slot('analysis-empty');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(await toolbar()).toBe(bar);
    expect(empty.getAttribute('data-way-out')).toBe('add');
    expect(empty.textContent).toContain(
      defaultMessages['label.analysis.empty-none'],
    );
    expect(within(empty).queryByRole('button')).toBeNull();
    expect(slot('analysis-caption')?.textContent).toMatch(/Showing 0 groups/);
  });

  it('asks a saved view under its own conditions to change the range', async () => {
    open(nothing(), { filter: CN });
    const empty = await waitFor(() => {
      const found = slot('analysis-empty');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(empty.getAttribute('data-way-out')).toBe('edit');
    expect(empty.textContent).toContain(
      defaultMessages['label.analysis.empty-view'],
    );
    fireEvent.click(
      within(empty).getByRole('button', {
        name: defaultMessages['label.analysis.empty-edit'],
      }),
    );
    await waitFor(() => expect(slot('analysis-tray')).not.toBeNull());
  });

  it('takes a view whose conditions moved back to the saved ones', async () => {
    const aggregate = vi.fn(async () => []);
    open(testSource({ aggregate }), { filter: CN });
    await waitFor(() => expect(slot('analysis-empty')).not.toBeNull());

    // Take the saved condition out of force from the applied bar.
    fireEvent.click(
      within(appliedBar()!).getByRole('button', { name: /^Unset/ }),
    );
    await waitFor(() =>
      expect(slot('analysis-empty')?.getAttribute('data-way-out')).toBe(
        'restore',
      ),
    );
    expect(slot('analysis-empty')?.textContent).toContain(
      defaultMessages['label.analysis.empty-hint'],
    );

    fireEvent.click(
      screen.getByRole('button', {
        name: defaultMessages['label.analysis.empty-restore'],
      }),
    );
    await waitFor(() =>
      expect(slot('analysis-empty')?.getAttribute('data-way-out')).toBe('edit'),
    );
    // Asked again, under the saved condition.
    const calls = aggregate.mock.calls as unknown[][];
    expect(JSON.stringify(calls[calls.length - 1][0])).toContain('CN');
  });
});

describe('wayOutOf', () => {
  it('clears the conditions of a view never saved, and asks again', () => {
    const filter = { clear: vi.fn(), submit: vi.fn() };
    const openEditor = vi.fn();
    const { wayOut, take } = wayOutOf({
      state: { applied: analysisConfig({ filter: CN }), saved: null },
      hasConditions: true,
      filter,
      runtime: null,
      openEditor,
    });
    expect(wayOut).toBe('clear');
    take();
    expect(filter.clear).toHaveBeenCalled();
    expect(filter.submit).toHaveBeenCalled();
    expect(openEditor).not.toHaveBeenCalled();
  });
});

describe('hasAsked', () => {
  it('is a result, a query on its way, or one that failed', () => {
    const at = (status: 'idle' | 'loading' | 'success' | 'error') => ({
      result: null,
      query: { status },
    });
    expect(hasAsked(null)).toBe(false);
    expect(hasAsked(at('idle'))).toBe(false);
    expect(hasAsked(at('loading'))).toBe(true);
    expect(hasAsked(at('error'))).toBe(true);
    expect(hasAsked({ result: {}, query: { status: 'idle' } })).toBe(true);
  });
});
