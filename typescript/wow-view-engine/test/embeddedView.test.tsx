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
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
} from '../src/index.js';
import type {
  FilterTree,
  RecordData,
  ViewInstance,
  ViewNavigation,
} from '../src/index.js';
import { EmbeddedView } from '../src/ui/index.js';
import {
  INSTANT,
  ROWS,
  ZONE,
  analysisConfig,
  dashboardConfig,
  inZone,
  namedOrdersDefinition,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { mine, mixed, setup } from './fixtures/ui.js';

afterEach(cleanup);

describe('EmbeddedView', () => {
  function embed(config: ViewInstance['config']) {
    return new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({ instances: [{ ...mine, config }] }),
      resolveSource: () =>
        testSource({
          paged: () =>
            Promise.resolve({
              total: 1,
              list: [{ id: 'o-1', createdAt: INSTANT }],
            }),
        }),
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });
  }

  /**
   * The embed's first moment, before the view is read, is said as well as
   * drawn (U-13): the skeleton for the eye, a status and a busy mark for a
   * reader, who otherwise heard nothing until the rows came.
   */
  it('says it is opening while the view is read', async () => {
    let release: () => void = () => {};
    const held = new Promise<void>(resolve => (release = resolve));
    class Slow extends MemoryViewStore {
      override async get(id: string) {
        await held;
        return super.get(id);
      }
    }
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new Slow({ instances: [{ ...mine, config: recordConfig() }] }),
      resolveSource: () => testSource(),
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    const opening = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="embed-opening"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(opening.getAttribute('aria-busy')).toBe('true');
    expect(within(opening).getByRole('status').textContent).toBe(
      'Opening the view',
    );
    expect(
      opening
        .querySelector('[data-slot="skeleton"]')
        ?.getAttribute('aria-hidden'),
    ).toBe('true');

    await act(async () => release());
    await waitFor(() =>
      expect(document.querySelector('[data-slot="embed-opening"]')).toBeNull(),
    );
  });

  it('pins the mode and the preset it is given on its surface (5B)', async () => {
    render(
      <EmbeddedView
        engine={embed(recordConfig())}
        instanceId="orders-1"
        theme="dark"
        preset="neutral"
      />,
    );

    const surface = await waitFor(() => {
      const found = document.querySelector('[data-slot="view-surface"]');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(surface.getAttribute('data-theme')).toBe('dark');
    expect(surface.getAttribute('data-fve-preset')).toBe('neutral');
  });

  it("shows times on the clock of the engine's zone, in the language given", async () => {
    const engine = embed(
      recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'createdAt' }] },
      }),
    );

    render(
      <EmbeddedView engine={engine} instanceId="orders-1" locale="en-GB" />,
    );

    expect(await screen.findByText(inZone(INSTANT))).toBeDefined();
  });

  it("takes the host's wording, for its own alerts and everything inside", async () => {
    render(
      <EmbeddedView
        engine={setup().engine}
        instanceId="missing"
        messages={{ 'label.view.unopenable': '打不开这个视图' }}
      />,
    );
    expect(await screen.findByText('打不开这个视图')).toBeDefined();
    cleanup();

    render(
      <EmbeddedView
        engine={setup().engine}
        instanceId="orders-1"
        interaction="interactive"
        withExport
        messages={{ 'label.record.select-all': '全选' }}
      />,
    );
    expect(await screen.findByRole('checkbox', { name: '全选' })).toBeDefined();
  });

  it('names chart categories as their field names its values', async () => {
    // The table's list puts the count first; the chart still groups by
    // warehouse, and names its bars through the schema, in the question's
    // order, rather than through the table's columns.
    const engine = embed(
      analysisConfig({
        layout: 'chart',
        table: { columns: [{ alias: 'orders' }] },
      }),
    );

    // The chart is what is asked about, so queries look inside it.
    const { container } = render(
      <EmbeddedView engine={engine} instanceId="orders-1" />,
    );

    expect(
      await within(container).findByText('China', {
        // The `sr-only` reading beside the chart names the category too.
        ignore: 'script, style, [data-slot="chart-reading"] *',
      }),
    ).toBeDefined();
  });

  it('shows the result and none of the workbench chrome', async () => {
    const { engine } = setup();

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // What a business page embeds is the answer, not a second application:
    // no view list, no condition editor, no save commands.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Save/ })).toBeNull();
  });

  it('runs the host condition without touching the saved config', async () => {
    const { engine, source } = setup();

    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        }}
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    await waitFor(() =>
      expect(
        vi
          .mocked(source.paged)
          .mock.calls.some(([query]) =>
            JSON.stringify(query.filter).includes('CN'),
          ),
      ).toBe(true),
    );

    const runtime = engine.openRuntimes()[0];
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('shows an analysis view as its saved layout', async () => {
    const analysis: ViewInstance = {
      ...mine,
      config: analysisConfig({ layout: 'table' }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [analysis] }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    // No tray and no handle for one: an embedded view is its author's
    // question, read as it was saved.
    expect(document.querySelector('[data-slot="analysis-tray"]')).toBeNull();
    expect(document.querySelector('[data-slot="editor-toggle"]')).toBeNull();
  });

  it('names a dashboard as a view it cannot show: that is EmbeddedDashboard', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'overview-1',
            definitionId: 'overview',
            title: 'Overview',
            scope: 'personal',
            revision: '1',
            config: dashboardConfig(),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="overview-1" />);

    expect(
      await screen.findByText(
        'This view is of another kind (dashboard), so this page cannot show it.',
      ),
    ).toBeDefined();
    expect(document.querySelector('[data-slot="dashboard-grid"]')).toBeNull();
  });

  it('reports a failed query inside the embed', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () =>
        testSource({ paged: () => Promise.reject(new Error('down')) }),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'Could not load the data: down',
      ),
    );
  });

  /**
   * A refused narrowing leaves the previous one running. The hook dropped the
   * issues `setScopeFilter` returns, so the embed went on showing a result
   * for a condition the page had already replaced.
   */
  it('says so when a narrowing it is given later is refused', async () => {
    const { engine, source } = setup();
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    };
    const { rerender } = render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={scope}
      />,
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(0),
    );

    rerender(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
        }}
      />,
    );

    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();
  });

  /**
   * The same refusal on the first open (D17-5). It used to go into the first
   * admission with the config, so the screen named the *view* as the thing to
   * fix — a view that was fine — and showed nothing at all, while the very
   * same condition refused a moment later said it was the page's and left the
   * result up.
   */
  it('says the same when the narrowing it opens with is refused', async () => {
    const { engine, source } = setup();

    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
        }}
      />,
    );

    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();
    // The view is not what needs fixing, and a host cannot fix somebody
    // else's saved config anyway.
    expect(screen.queryByText(/needs fixing/i)).toBeNull();
    // And the wider result is on screen, as it is for a narrowing refused
    // later: the page not getting the range it asked for is no reason to
    // withhold what the view does say — that is what the alert is for.
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    for (const [query] of vi.mocked(source.paged).mock.calls)
      expect(JSON.stringify(query.filter)).not.toContain('nope');
  });

  /**
   * Refused, accepted, refused again. The middle one is the one that has to
   * stay in force: a refusal takes nothing away, so the narrowing the view
   * did accept goes on running under the alert.
   */
  it('keeps the narrowing it accepted when a later one is refused', async () => {
    const { engine, source } = setup();
    const refused: FilterTree = {
      op: 'and',
      children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
    };
    const accepted: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    };
    const embed = (scope: FilterTree) => (
      <EmbeddedView engine={engine} instanceId="orders-1" scopeFilter={scope} />
    );

    const { rerender } = render(embed(refused));
    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();

    rerender(embed(accepted));
    await waitFor(() =>
      expect(screen.queryByText(/could not narrow/i)).toBeNull(),
    );
    await waitFor(() =>
      expect(
        JSON.stringify(vi.mocked(source.paged).mock.lastCall?.[0].filter),
      ).toContain('CN'),
    );

    rerender(
      embed({
        op: 'and',
        children: [{ field: 'gone', operator: 'EQ', value: 'y' }],
      }),
    );
    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();
    expect(engine.openRuntimes()[0].scopeFilter).toEqual(accepted);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  });

  /**
   * Both at once, each said where it belongs: the config's own error takes
   * the result's place, and the refusal is still the page's to answer for.
   * Refusing the host's condition is not a way to fix a view.
   */
  it('tells a view that must be fixed apart from a narrowing that was refused', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      // Blocks on its own: a page size must be positive.
      store: new MemoryViewStore({
        instances: [{ ...mine, config: recordConfig({ pageSize: 0 }) }],
      }),
      resolveSource: () => testSource(),
    });

    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
        }}
      />,
    );

    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();
    // The view's one error, said outright rather than headed (F-14).
    expect(
      screen.getByText('The page size must be a positive number.'),
    ).toBeTruthy();
    expect(screen.queryByRole('row')).toBeNull();
  });

  /**
   * A host may narrow again before the first rows are back. The answer to a
   * question nobody is asking any more must not land on the screen, and the
   * refusal must not outlive the condition that earned it.
   */
  it('keeps up with a host that narrows while the first query is out', async () => {
    const pending: ((page: { total: number; list: RecordData[] }) => void)[] =
      [];
    const source = testSource({
      paged: vi.fn(
        () =>
          new Promise<{ total: number; list: RecordData[] }>(resolve => {
            pending.push(resolve);
          }),
      ),
    });
    const { engine } = setup(source);
    const embed = (scope: FilterTree) => (
      <EmbeddedView engine={engine} instanceId="orders-1" scopeFilter={scope} />
    );

    const { rerender } = render(
      embed({
        op: 'and',
        children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
      }),
    );
    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();
    await waitFor(() => expect(pending).toHaveLength(1));

    rerender(
      embed({
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      }),
    );
    await waitFor(() => expect(pending).toHaveLength(2));
    await waitFor(() =>
      expect(screen.queryByText(/could not narrow/i)).toBeNull(),
    );

    // The un-narrowed answer comes back last, and answers nothing anyone
    // asked for now: the narrowed rows are what stays on screen.
    await act(async () => {
      pending[1]({ total: 1, list: [{ id: 'narrow', amount: 1 }] });
      pending[0]({ total: 2, list: [...ROWS] });
    });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2));
  });

  it('reports a view it cannot open', async () => {
    const { engine } = setup();

    render(<EmbeddedView engine={engine} instanceId="gone" />);

    await waitFor(() =>
      expect(screen.getByText(/could not be opened/i)).toBeDefined(),
    );
  });

  /**
   * An embed hides the editor, so this notice is the one way a reader learns
   * the view is not quite what its author saved. Unlike an error it does not
   * take the result's place: the rows are real, and they still show.
   */
  it('shows a warning above the result rather than instead of it', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mixed] }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const notice = screen.getByRole('status');
    expect(notice.getAttribute('data-tone')).toBe('warning');
    expect(notice.textContent).toContain('advanced editor');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  /**
   * A config can carry both. The error branch returned before the warning
   * was rendered, so an embed said one level less than the workbench did.
   */
  it('keeps saying what is worth noting when an error takes the result place', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mixed,
            config: recordConfig({
              filterMode: 'simple',
              filter: {
                op: 'or',
                children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
              },
              // Blocks: the page size must be positive.
              pageSize: 0,
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The page size must be a positive number.',
      ),
    );
    expect(screen.getByRole('status').textContent).toContain('advanced editor');
    expect(screen.queryByRole('row')).toBeNull();
  });

  /**
   * The page narrowed the view, and the bar above the rows is the only place
   * that says so. It is named as the page's rather than mixed in with the
   * view's own conditions, and it carries no remove: no path of the editor
   * addresses it, so the only thing a ✕ could do is fail.
   */
  it("names the host scope among the conditions, as nobody's to remove", async () => {
    const { engine } = setup();

    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        }}
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const scoped = await waitFor(() => {
      const badge = document.querySelector('[data-scoped]');
      if (!badge) throw new Error('no scoped badge yet');
      return badge;
    });
    expect(scoped.textContent).toContain('CN');
    expect(scoped.textContent).toContain('Set by the page');
  });

  /**
   * An embed shows what somebody already decided. The ✕ on a saved condition
   * would let a reader widen the view — on a page that embedded "this
   * customer's shipments", that is the page listing everyone's.
   */
  it('offers no way to drop a saved condition from the summary', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({
              filter: {
                op: 'and',
                children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
              },
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(
      document.querySelector('[data-slot="applied-bar"]')?.textContent,
    ).toContain('CN');
    expect(screen.queryByRole('button', { name: /^Unset/ })).toBeNull();
  });

  /**
   * The strip says "showing the last successful result", so the last
   * successful result has to still be there. Replacing the table with the
   * failure made that line describe an empty frame.
   */
  it('keeps the rows under the line that reports a failed refresh', async () => {
    let attempt = 0;
    const source = testSource({
      paged: () => {
        attempt += 1;
        return attempt === 1
          ? Promise.resolve({ total: 2, list: [{ id: 'o-1' }, { id: 'o-2' }] })
          : Promise.reject(new Error('down'));
      },
    });
    const { engine } = setup(source);

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    act(() => engine.openRuntimes()[0].refresh());

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'Could not load the data: down',
      ),
    );
    // Both at once, the way the workbenches do it: the rows that did come
    // back are still the real ones, and the failure's own line says so —
    // not a fold the reader has to open to learn the rows are old.
    expect(screen.getByRole('alert').textContent).toContain(
      'Could not load the data: down · Showing the last successful result',
    );
    expect(screen.queryByRole('button', { name: '1 more' })).toBeNull();
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });
});

/**
 * The tier and the switches (D22): a record or analysis view on a business
 * page is read-only unless the host says interactive, and shows what else
 * the host switched on — its title, its search, its export, 在工作台中打开 —
 * and nothing it did not.
 */
describe('EmbeddedView tiers and switches', () => {
  function engineOf(config: ViewInstance['config'], fields = true) {
    const base = ordersDefinition();
    return new ViewEngine({
      definitions: [
        fields
          ? ordersDefinition({
              fields: [
                ...base.fields,
                { name: 'q', label: 'Search orders', kind: 'search' },
              ],
            })
          : base,
      ],
      store: new MemoryViewStore({ instances: [{ ...mine, config }] }),
      resolveSource: () => testSource(),
    });
  }

  it('reads, and does nothing else, in the read-only tier', async () => {
    render(
      <EmbeddedView engine={engineOf(recordConfig())} instanceId="orders-1" />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // No header sorts, no width handle, no pages, no picking.
    for (const head of screen.getAllByRole('columnheader'))
      expect(within(head).queryByRole('button')).toBeNull();
    expect(document.querySelector('[data-slot="column-resizer"]')).toBeNull();
    expect(
      document.querySelector('[data-slot="record-pagination"]'),
    ).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    // Nothing the host did not switch on, and no first row to hold it.
    expect(document.querySelector('[data-slot="embed-head"]')).toBeNull();
    expect(screen.queryByRole('searchbox')).toBeNull();
    expect(
      document.querySelector('[data-embed-size="content"]'),
    ).not.toBeNull();
  });

  it('sorts by a header and pages in the interactive tier', async () => {
    const engine = engineOf(recordConfig());
    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        interaction="interactive"
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    await userEvent.click(
      screen.getByRole('button', { name: /Sort by Amount, ascending/ }),
    );
    await waitFor(() =>
      expect(engine.openRuntimes()[0].getSnapshot().applied).toMatchObject({
        sort: [{ field: 'amount', direction: 'ASC' }],
      }),
    );
    expect(
      document.querySelector('[data-slot="record-pagination"]'),
    ).not.toBeNull();
  });

  it('titles itself at the level the host outline calls for, when asked', async () => {
    render(
      <EmbeddedView
        engine={engineOf(recordConfig())}
        instanceId="orders-1"
        withTitle
        headingLevel={3}
      />,
    );

    expect(
      await screen.findByRole('heading', { level: 3, name: mine.title }),
    ).toBeDefined();
  });

  it('offers the search box and the export where the host switched them on', async () => {
    const { rerender } = render(
      <EmbeddedView
        engine={engineOf(recordConfig())}
        instanceId="orders-1"
        withSearch
        withExport
      />,
    );

    expect(
      await screen.findByRole('searchbox', { name: 'Search orders' }),
    ).toBeDefined();
    expect(await screen.findByRole('button', { name: /Export/ })).toBeDefined();
    // The read-only tier keeps no row checks even with the export on; the
    // export takes the whole result (D26 Q36, test/embeddedExport.test.tsx).
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);

    // A definition without a search field has no box to show.
    rerender(
      <EmbeddedView
        engine={engineOf(recordConfig(), false)}
        instanceId="orders-1"
        withSearch
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(screen.queryByRole('searchbox')).toBeNull();
  });

  it('opens the view in the workbench through the host route, under the page narrowing — interactive only', async () => {
    const onNavigate = vi.fn();
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    };
    const engine = engineOf(recordConfig());
    const { rerender } = render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={scope}
        onNavigate={onNavigate}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(
      screen.queryByRole('button', { name: 'Open in the workbench' }),
    ).toBeNull();

    rerender(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={scope}
        onNavigate={onNavigate}
        interaction="interactive"
      />,
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Open in the workbench' }),
    );
    // The page's narrowing stays the page's there: its scope, locked, and
    // nothing of the reader's to carry (D26 Q30).
    expect(onNavigate).toHaveBeenCalledWith({
      kind: 'view',
      definitionId: 'orders',
      instanceId: 'orders-1',
      scopeFilter: scope,
      filter: null,
    });

    // Switched off, it is not there, route or not.
    rerender(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        onNavigate={onNavigate}
        interaction="interactive"
        openInWorkbench={false}
      />,
    );
    expect(
      screen.queryByRole('button', { name: 'Open in the workbench' }),
    ).toBeNull();
  });

  it('switches an analysis between table and chart in the interactive tier, and not in the read-only one', async () => {
    const engine = engineOf(analysisConfig({ layout: 'table' }));
    const { rerender } = render(
      <EmbeddedView engine={engine} instanceId="orders-1" />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(screen.queryByRole('group', { name: 'Show result as' })).toBeNull();
    // Nothing on it opens a menu: the follow-ups are the interactive tier's.
    const group = await screen.findByRole('row', { name: /CN/ });
    expect(group.getAttribute('aria-haspopup')).toBeNull();

    rerender(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        interaction="interactive"
      />,
    );
    const layout = await screen.findByRole('group', { name: 'Show result as' });
    await userEvent.click(
      within(layout).getByRole('button', { name: 'Chart' }),
    );
    await waitFor(() =>
      expect(document.querySelector('[data-slot="chart"]')).not.toBeNull(),
    );
    // Nothing of it is saved.
    expect(engine.openRuntimes()[0].getSnapshot().saved?.config).toMatchObject({
      layout: 'table',
    });
  });

  it('opens the follow-up menu on a group, through the host route, in the interactive tier — the page’s narrowing its locked scope', async () => {
    const onNavigate = vi.fn();
    const engine = engineOf(analysisConfig({ layout: 'table' }));
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
    };
    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={scope}
        interaction="interactive"
        onNavigate={onNavigate}
      />,
    );
    const table = await screen.findByRole('table');
    const row = await within(table).findByRole('row', { name: /CN/ });
    expect(row.getAttribute('aria-haspopup')).toBe('menu');
    await userEvent.click(row);
    await userEvent.click(
      await screen.findByRole('menuitem', { name: /See these records/ }),
    );
    expect(onNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'unsaved',
        definitionId: 'orders',
        scopeFilter: scope,
      }),
    );
    // Not among the view's own conditions, where the reader could take it
    // off (D26 Q30, H4).
    const [to] = onNavigate.mock.calls[0] as [ViewNavigation];
    expect(
      JSON.stringify(to.kind === 'unsaved' && to.config.filter),
    ).not.toContain('PENDING');
  });

  it('fills its container when asked, and never refreshes itself when told not to', async () => {
    const clock = testEnvironment();
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          { ...mine, config: recordConfig({ refresh: { interval: 30 } }) },
        ],
      }),
      resolveSource: () => testSource(),
      environment: clock.environment,
    });
    const { rerender } = render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        size="fill"
        autoRefresh={false}
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(document.querySelector('[data-embed-size="fill"]')).not.toBeNull();
    await waitFor(() => expect(clock.timers).toBe(0));

    rerender(
      <EmbeddedView engine={engine} instanceId="orders-1" size="fill" />,
    );
    await waitFor(() => expect(clock.timers).toBe(1));
  });
});
