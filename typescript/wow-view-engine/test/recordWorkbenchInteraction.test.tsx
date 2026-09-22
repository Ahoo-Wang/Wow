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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedList } from '@ahoo-wang/fetcher-wow';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance, ViewSource, RecordData } from '../src/index.js';
import { defaultMessages, DataWorkbench } from '../src/ui/index.js';
import type { DataWorkbenchProps } from '../src/ui/index.js';
import {
  deferred,
  ROWS,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { addConditions, editorToggle } from './fixtures/workbench.js';
import { dataColumnHeaders, mine, setup } from './fixtures/ui.js';

afterEach(cleanup);

describe('DataWorkbench interaction', () => {
  async function open(source: ViewSource = testSource()) {
    const harness = setup(source);
    render(
      <DataWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // Every saved view opens with its conditions folded away; the tests
    // below are about what is inside the fold.
    fireEvent.click(editorToggle());
    await screen.findByRole('button', { name: /Apply/ });
    return harness;
  }

  it('adds a condition, edits it and applies it', async () => {
    const { source } = await open();

    await addConditions(['Warehouse']);

    const value = await screen.findByLabelText('Warehouse value');
    fireEvent.change(value, { target: { value: 'CN' } });
    fireEvent.click(screen.getByRole('button', { name: /Apply/ }));

    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].filter).toMatchObject({
        field: 'warehouse',
        value: 'CN',
      });
    });
  });

  it('removes a condition and clears the tree', async () => {
    await open();

    await addConditions(['Warehouse']);
    await screen.findByLabelText('Warehouse value');

    fireEvent.click(screen.getByRole('button', { name: 'Remove Warehouse' }));
    expect(screen.queryByLabelText('Warehouse value')).toBeNull();

    await addConditions(['Warehouse']);
    await screen.findByLabelText('Warehouse value');
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));

    expect(screen.queryByLabelText('Warehouse value')).toBeNull();
  });

  it('switches to cards and back', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));

    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Table' }));
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  });

  it('selects rows and reports the count', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('Select all rows'));

    await waitFor(() => expect(screen.getByText('2 selected')).toBeDefined());

    fireEvent.click(screen.getByLabelText('Select o-1'));
    await waitFor(() => expect(screen.getByText('1 selected')).toBeDefined());
  });

  it('sorts by a column and pages forward', async () => {
    // A total larger than one page: the toolbar disables Next at the end of
    // the result, so paging forward needs somewhere to go.
    const { source } = await open(
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 50, list: [...ROWS] })),
      }),
    );

    fireEvent.click(screen.getByRole('button', { name: /Amount/ }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].sort).toEqual([
        { field: 'amount', direction: 'ASC' },
      ]);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].pagination).toMatchObject({ index: 2 });
    });

    fireEvent.click(screen.getByRole('button', { name: 'Previous page' }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].pagination).toMatchObject({ index: 1 });
    });
  });

  /**
   * A view whose query matched nothing, which is what the empty state and
   * its one way out are drawn over. `open` waits for rows, so this waits
   * for the sentence that stands in their place.
   */
  async function openEmpty() {
    const source = testSource({
      paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
    });
    const harness = setup(source);
    render(
      <DataWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    // The empty title, as it is drawn: the live region says the same
    // sentence out loud, which is two nodes holding this text on purpose.
    await screen.findByText(defaultMessages['label.record.empty'], {
      selector: '[data-slot="empty-title"]',
    });
    return source;
  }

  /**
   * The empty result's way out, wired to the conditions that emptied it:
   * clearing the draft alone would leave the rows on screen fetched under
   * the conditions the button had just taken away, so it applies as well.
   */
  it('clears the applied conditions from the empty result', async () => {
    const source = await openEmpty();

    fireEvent.click(editorToggle());
    await screen.findByRole('button', { name: /Apply/ });
    await addConditions(['Warehouse']);
    const value = await screen.findByLabelText('Warehouse value');
    fireEvent.change(value, { target: { value: 'CN' } });
    fireEvent.click(screen.getByRole('button', { name: /Apply/ }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].filter).toMatchObject({
        field: 'warehouse',
      });
    });

    fireEvent.click(
      await screen.findByRole('button', {
        name: defaultMessages['label.record.empty-clear'],
      }),
    );

    // Cleared *and* asked again: the rows the button is standing on were
    // fetched under the conditions it just removed.
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].filter).not.toMatchObject({
        field: 'warehouse',
      });
    });
  });

  /**
   * And with nothing applied there is nothing to clear, so the way out is
   * the other one: the question to change is behind a fold that may not
   * even be on screen, and the button opens it.
   */
  it('opens the conditions from an empty result that had none', async () => {
    await openEmpty();
    // A saved view opens folded, which is the whole reason this way out
    // exists: the question to change is not on screen.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();

    fireEvent.click(
      screen.getByRole('button', {
        name: defaultMessages['label.record.empty-add'],
      }),
    );

    await screen.findByRole('button', { name: /Apply/ });
  });

  /**
   * Next used to be live on every paged result, so the page after the last
   * one was an ordinary click away — and what came back was an empty table
   * with no way to tell it from a filter that matched nothing. It was then
   * drawn dead; on a result that fits in one page it is not drawn at all
   * (D12), because the count and "Page 1 of 1" have already said so.
   */
  it('draws no arrows on a result that fits in one page', async () => {
    await open();

    expect(screen.queryByRole('button', { name: 'Next page' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous page' })).toBeNull();
  });

  /** And stops there on a result that does not. */
  it('stops Next at the last page', async () => {
    // Forty records, twenty to a page: two pages, so the arrows are drawn
    // and there is somewhere for Next to go exactly once.
    const { source } = await open(
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 40, list: [...ROWS] })),
      }),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Next page' })).toHaveProperty(
        'disabled',
        true,
      ),
    );

    const before = vi.mocked(source.paged).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(vi.mocked(source.paged).mock.calls).toHaveLength(before);
  });

  it('hides a column from the picker', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Amount' }),
    );

    // The data columns lose Amount; the action column (every row opens its
    // detail from there) stays.
    await waitFor(() =>
      expect(screen.queryByRole('columnheader', { name: /Amount/ })).toBeNull(),
    );
    expect(dataColumnHeaders()).toHaveLength(2);
  });

  /**
   * The mode is a way of editing rather than part of the filter, so it is a
   * menu on the editor's own toggle rather than a row inside the panel.
   */
  it('switches the filter editor to advanced mode', async () => {
    const user = userEvent.setup();
    await open();

    await user.click(screen.getByRole('button', { name: 'Editor options' }));
    await user.click(
      await screen.findByRole('menuitemradio', { name: 'Advanced' }),
    );

    // The toggle's own name is the mode in force, said without the menu
    // being opened a second time.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Filter · Advanced' }),
      ).toBeDefined(),
    );
    // And the panel below is the advanced editor: a group with an operator.
    expect(
      screen.getByRole('combobox', { name: 'Group operator' }),
    ).toBeDefined();
  });

  it('keeps the filter editable while a query is still running', async () => {
    const pending = deferred<PagedList<RecordData>>();
    const { engine } = setup(testSource({ paged: () => pending.promise }));
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    fireEvent.click(await screen.findByRole('button', { name: /^Filter/ }));
    const apply = await screen.findByRole('button', { name: /Apply/ });

    // The rows are still coming. Typing never re-queries and the next apply
    // supersedes the request in flight, so nothing here has to wait for it.
    expect(apply.hasAttribute('disabled')).toBe(false);
    await addConditions(['Warehouse']);
    const value = await screen.findByLabelText('Warehouse value');
    expect((value as HTMLInputElement).disabled).toBe(false);
    fireEvent.change(value, { target: { value: 'CN' } });
    expect((value as HTMLInputElement).value).toBe('CN');

    pending.resolve({ total: 2, list: [...ROWS] });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  });

  it('refreshes on demand', async () => {
    const { source } = await open();
    const before = vi.mocked(source.paged).mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));

    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBe(before + 1),
    );
  });

  it('refreshes the list once a save-as lands in the store', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'My copy' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    // The copy joins the sidebar rather than waiting for a remount.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'My copy' })).toBeDefined(),
    );
  });
});

/**
 * The result is the point of a record view, so everything above it earns its
 * height: the editor folds away, findings are lines rather than banners, and
 * the one thing that always shows is what the rows on screen were asked for.
 */
describe('the record workbench layout', () => {
  /** A view whose saved condition the applied bar has something to say about. */
  const filtered: ViewInstance = {
    ...mine,
    config: recordConfig({
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    }),
  };

  function workbench(
    instances: ViewInstance[],
    props: Partial<DataWorkbenchProps> = {},
    source: ViewSource = testSource(),
  ) {
    const store = new MemoryViewStore({ instances });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => source,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={instances[0]?.id ?? null}
        {...props}
      />,
    );
    return { engine, store, source };
  }

  it('opens a saved view with its conditions folded away', async () => {
    workbench([mine]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    expect(editorToggle().getAttribute('aria-expanded')).toBe('false');
    // Folded is unmounted, not hidden: a fold that kept the draft's inputs
    // on the page would keep them in the tab order too.
    expect(document.querySelector('[data-slot="editor-band"]')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add' })).toBeNull();
  });

  /**
   * A view that was never saved has nothing to show yet, so the editor is
   * the point of the screen rather than something in the way of it.
   */
  it('opens a view that was never saved with its conditions out', async () => {
    const { engine } = workbench([]);
    // Nothing in the store is unsaved — the store is what saving means — so
    // the engine is asked for a fresh runtime the way a "new view" would.
    const fresh = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    cleanup();
    vi.spyOn(engine, 'open').mockResolvedValue(fresh);
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    const band = await screen.findByRole('button', { name: /^Filter/ });
    expect(band.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('button', { name: 'Add' })).toBeDefined();
    // And it says so beside the title, where the save button would otherwise
    // have to be read to find out.
    expect(screen.getByText('Not saved yet')).toBeDefined();
  });

  it('counts what is waiting to be applied on the folded band', async () => {
    workbench([mine]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(editorToggle());
    await addConditions(['Warehouse']);
    fireEvent.change(await screen.findByLabelText('Warehouse value'), {
      target: { value: 'CN' },
    });

    // Folded again, the count is the one thing left saying the editor holds
    // something the rows below were not fetched under.
    fireEvent.click(editorToggle());
    await waitFor(() =>
      expect(editorToggle().textContent).toContain('1 not applied'),
    );
  });

  it('says what the rows were fetched under, and takes one out of force', async () => {
    const { source } = workbench([filtered]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    const bar = screen.getByRole('region', { name: 'Showing' });
    expect(bar.textContent).toContain('Warehouse');
    expect(bar.textContent).toContain('CN');

    fireEvent.click(within(bar).getByRole('button', { name: /^Unset/ }));

    // Unsetting applies at once: the condition leaves the query, and the bar
    // that describes the new result says there is nothing narrowing it.
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      // Nothing left to narrow by: the query asks for everything.
      expect(calls[calls.length - 1][0].filter).toEqual({ op: 'MATCH_ALL' });
    });
    await waitFor(() =>
      expect(
        screen.getByRole('region', { name: 'Showing' }).textContent,
      ).toContain('All records'),
    );
  });

  it('keeps the rows a failed refresh could not replace, and retries', async () => {
    let fail = false;
    const source = testSource({
      paged: vi.fn(() =>
        fail
          ? Promise.reject(new Error('gateway down'))
          : Promise.resolve({ total: 2, list: [...ROWS] }),
      ),
    });
    workbench([mine], {}, source);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fail = true;
    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));

    const strip = await screen.findByRole('alert');
    expect(strip.textContent).toContain('The source answered: gateway down');
    // The rows are the last ones that came back, and the failure's own
    // line says so rather than the table emptying itself over a dropped
    // connection — or a fold hiding that the rows are old.
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(strip.textContent).toContain(
      'gateway down · Showing the last successful result',
    );
    expect(within(strip).queryByRole('button', { name: '1 more' })).toBeNull();

    fail = false;
    fireEvent.click(within(strip).getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it("offers the host's bulk action only while rows are picked", async () => {
    workbench([mine], {
      record: {
        actions: {
          bulk: ({ rows }) => (
            <button type="button">Export {rows.length} selected</button>
          ),
        },
      },
    });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    // The host's own button, named by what it would act on — the toolbar's
    // own export is a different control and is there all along.
    expect(screen.queryByRole('button', { name: /\d+ selected/ })).toBeNull();

    fireEvent.click(screen.getByLabelText('Select all rows'));

    expect(
      await screen.findByRole('button', { name: 'Export 2 selected' }),
    ).toBeDefined();
  });

  it("puts the host's row action in the pinned column, and in the card", async () => {
    workbench([mine], {
      record: {
        actions: {
          global: () => <button type="button">New order</button>,
          row: ({ row }) => (
            <button type="button">Open {String(row.key)}</button>
          ),
        },
      },
    });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    // The global action sits in the title bar, beside the save commands.
    const header = document.querySelector(
      '[data-slot="view-header"]',
    ) as HTMLElement;
    expect(
      within(header).getByRole('button', { name: 'New order' }),
    ).toBeDefined();

    // The row action is a column of its own, pinned so a wide table cannot
    // scroll it out of reach.
    const head = screen.getByRole('columnheader', { name: 'Actions' });
    expect(head.dataset.pin).toBe('right');
    const cell = screen
      .getByRole('button', { name: 'Open o-1' })
      .closest('[data-slot="row-actions"]');
    expect(cell).not.toBeNull();
    // Wrapped once: the workbench binds the slot to the view and the table
    // adds the wrapper, and a second one inside the first was two boxes
    // with one job. One per row, none inside another.
    expect(document.querySelectorAll('[data-slot="row-actions"]')).toHaveLength(
      2,
    );
    expect(
      cell?.parentElement?.closest('[data-slot="row-actions"]'),
    ).toBeNull();
    // The whole column and not the header alone: a header that stays while
    // its cells slide away is worse than no pinning at all.
    expect(cell?.closest('td')?.getAttribute('data-pin')).toBe('right');

    // The same buttons follow the rows into the card layout, in a footer.
    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(
      screen
        .getByRole('button', { name: 'Open o-1' })
        .closest('[data-slot="card-footer"]'),
    ).not.toBeNull();
    expect(document.querySelectorAll('[data-slot="row-actions"]')).toHaveLength(
      2,
    );
  });

  it('asks before a switch that would lose an unsaved draft', async () => {
    const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };
    workbench([mine, other]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    // An edit the toolbar can make, so there is a draft worth keeping.
    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    await waitFor(() => expect(dataColumnHeaders()).toHaveLength(4));
    fireEvent.keyDown(document.body, { key: 'Escape' });

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    const asked = await screen.findByRole('alertdialog');
    expect(asked.textContent).toContain('Unsaved changes will be lost.');

    fireEvent.click(within(asked).getByRole('button', { name: 'Stay' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Leave',
      }),
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Other' }).ariaCurrent).toBe(
        'true',
      ),
    );
  });

  /** Nothing to lose, nothing to ask: a guard that always fires is ignored. */
  it('switches straight over when there is nothing to lose', async () => {
    const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };
    workbench([mine, other]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Other' }).ariaCurrent).toBe(
        'true',
      ),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
});
