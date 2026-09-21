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
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import {
  AnalysisWorkbench,
  DashboardWorkbench,
  RecordWorkbench,
  ViewSurface,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  ROWS,
  testSource,
} from './fixtures.js';

/**
 * D12: a function is an icon button, and an icon button says its name on
 * hover. `src/ui/IconButton.tsx` is the one place that pairs the two, so
 * this suite asks the only question a call site can get wrong — did this
 * button go through it? — rather than listing the buttons that did. A
 * control added next month is held to the rule without anyone remembering
 * to add it here.
 *
 * The question is put to the rendered DOM rather than to the source: a
 * button with no text of its own is a button whose name lives in
 * `aria-label` alone, where a pointer never reaches it.
 */
afterEach(cleanup);

/**
 * What a tooltip's trigger leaves on the element it renders.
 *
 * Two marks, because neither survives on its own. `data-slot` is the
 * vendored component's, and a call site that stamps its own — `refresh-now`,
 * `view-expand`, the controls a test looks for — replaces it, since a
 * `render` element's props win over the component's. `data-base-ui-*` is the
 * primitive's own and cannot be overridden, but it is dropped while the
 * tooltip is `disabled`, which is how a drag handle stays quiet mid-drag. A
 * control that went through the wrapper always carries one of the two.
 */
function speaks(element: Element): boolean {
  return (
    element.hasAttribute('data-base-ui-tooltip-trigger') ||
    element.getAttribute('data-slot') === 'tooltip-trigger'
  );
}

/**
 * The buttons another PR owns for now, each named with the one that owns it.
 * An entry is a promise rather than an exemption: it goes when that PR
 * lands.
 *
 * The list is empty. The export dialog's trigger goes through the wrapper
 * like everything else, and the buttons in `src/ui/RecordTable.tsx` and
 * `src/ui/record/SortableHeader.tsx` — which the column-resize PR is
 * rewriting — all carry text, so nothing on screen is silent and nothing is
 * excused. If that PR lands a silent icon button, this is where it is
 * written down and dated rather than where the rule is loosened.
 */
const ELSEWHERE: readonly {
  readonly selector: string;
  readonly owner: string;
}[] = [];

/** Whether another PR owns this control for now. */
function inFlight(element: Element): boolean {
  return ELSEWHERE.some(({ selector }) => element.matches(selector));
}

/**
 * Every button on screen whose only content is an icon.
 *
 * `hidden` elements are out: the way back out of an expanded surface
 * (`ViewExpandExit`) is one, and it is not on the page until the surface it
 * belongs to fills the screen — where it carries its name as text anyway.
 */
function iconOnly(): Element[] {
  return Array.from(
    document.querySelectorAll('button, [role="button"]'),
  ).filter(
    element =>
      element.textContent?.trim() === '' && !(element as HTMLElement).hidden,
  );
}

/**
 * The ones that say nothing to a pointer, as the buttons themselves rather
 * than as a count — a failure names what to fix.
 */
function silent(): string[] {
  return iconOnly()
    .filter(element => !inFlight(element) && !speaks(element))
    .map(
      element =>
        `${element.tagName.toLowerCase()} "${
          element.getAttribute('aria-label') ?? '(unnamed)'
        }" — ${element.getAttribute('data-slot') ?? 'no data-slot'}`,
    );
}

const orders: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Orders',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    // One sorted column, so a sort entry has a handle, a direction and a ✕;
    // two conditions, so the tray draws a pill's ✕ and the value list's own
    // add and remove.
    sort: [{ field: 'amount', direction: 'DESC' }],
    filter: {
      op: 'and',
      children: [
        { field: 'warehouse', operator: 'EQ', value: 'CN' },
        { field: 'amount', operator: 'IN', value: [100, 200] },
      ],
    },
  }),
};

/** A second view, so the manager has a list worth reordering. */
const archived: ViewInstance = { ...orders, id: 'orders-2', title: 'Archived' };

const totals: ViewInstance = {
  id: 'totals-1',
  definitionId: 'orders',
  title: 'Totals',
  scope: 'personal',
  revision: '1',
  config: analysisConfig({ layout: 'table' }),
};

const overview: ViewInstance = {
  id: 'overview-1',
  definitionId: 'overview',
  title: 'Overview',
  scope: 'personal',
  revision: '1',
  config: dashboardConfig({
    fields: [{ name: 'region', label: 'Region', kind: 'string' }],
    panels: [
      {
        id: 'rows',
        kind: 'view',
        title: 'Rows',
        instanceId: 'orders-1',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 0, y: 0, w: 6, h: 4 },
      },
    ],
  }),
};

/** More rows than one page holds, so the pagination draws its arrows. */
function pagedSource() {
  return testSource({
    paged: () => Promise.resolve({ total: 42, list: [...ROWS] }),
  });
}

function engineWith(
  instances: ViewInstance[],
  source = testSource(),
): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store: new MemoryViewStore({ instances }),
    resolveSource: () => source,
  });
}

/**
 * A popup, by the control it is rather than by the name on it: the names on
 * this bar carry the state of what they open ("Sort by Amount"), and a
 * suite about names should not be the one that has to spell them.
 */
async function open(control: string): Promise<void> {
  fireEvent.click(document.querySelector(`[data-control="${control}"]`)!);
  await screen.findByRole('dialog');
}

/** The open popup, dismissed, so the next one is unambiguous. */
async function close(): Promise<void> {
  fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
}

describe('every icon-only button says its name on hover', () => {
  it('holds across the record workbench and the popups it opens', async () => {
    render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([orders, archived], pagedSource())}
          definitionId="orders"
          instanceId="orders-1"
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(document.querySelector('table')).not.toBeNull());

    // The title bar, the applied conditions, the toolbar and the pagination
    // are all drawn before anything is opened — and there are enough of them
    // that an empty report below means the rule held rather than that the
    // scan found nothing.
    expect(iconOnly().length).toBeGreaterThan(8);
    expect(silent()).toEqual([]);

    // The filter tray, holding both conditions.
    fireEvent.click(screen.getByRole('button', { name: /^Filter/ }));
    await screen.findByRole('group', { name: /Warehouse/ });
    expect(silent()).toEqual([]);

    for (const control of ['columns', 'sort']) {
      await open(control);
      expect(silent()).toEqual([]);
      await close();
    }

    // The manager, which is where a view is carried, renamed and deleted —
    // and a row mid-rename, which is the only place ✓ and ✕ are drawn.
    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    const manager = await screen.findByRole('dialog', { name: 'Manage views' });
    expect(silent()).toEqual([]);
    fireEvent.click(
      within(manager).getAllByRole('button', { name: 'Rename' })[0]!,
    );
    await within(manager).findByRole('button', { name: 'Save the title' });
    expect(silent()).toEqual([]);
    // Out of the rename first: the field answers Escape itself and stops it
    // there, which is what keeps one keystroke from closing the manager too.
    fireEvent.click(
      within(manager).getByRole('button', { name: 'Keep the title' }),
    );
    await close();

    // The sidebar folded away, which is the only state its expand button has.
    fireEvent.click(screen.getByRole('button', { name: 'Hide the view list' }));
    await screen.findByRole('button', { name: 'Show the view list' });
    expect(silent()).toEqual([]);
  });

  it('holds across the analysis workbench and its editor', async () => {
    render(
      <ViewSurface>
        <AnalysisWorkbench
          engine={engineWith([totals])}
          definitionId="orders"
          instanceId="totals-1"
        />
      </ViewSurface>,
    );
    // The editor is the fold this workbench opens with, so its rows — a
    // grouping and a metric, each with a ✕ — are on screen with the result.
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(screen.getByRole('region', { name: 'Analysis' })).toBeDefined();
    expect(silent()).toEqual([]);
  });

  it('holds across the dashboard workbench', async () => {
    render(
      <ViewSurface>
        <DashboardWorkbench
          engine={engineWith([overview, orders])}
          definitionId="overview"
          instanceId="overview-1"
        />
      </ViewSurface>,
    );
    await screen.findByRole('heading', { name: 'Overview' });
    await waitFor(() => expect(silent()).toEqual([]));
  });
});
