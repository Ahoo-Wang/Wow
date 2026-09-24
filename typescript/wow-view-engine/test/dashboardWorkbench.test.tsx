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
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { FilterOperator } from '@ahoo-wang/wow-client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewCommandError,
  ViewEngine,
  ViewStoreError,
  defaultRuntimeEnvironment,
  issue,
  withFieldKinds,
  type DashboardRuntime,
  type FieldKind,
  type ViewInstance,
} from '../src/index.js';
import { DashboardWorkbench, zhCN } from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  analysisConfig,
  dashboardConfig,
  inZone,
  namedOrdersDefinition,
  ordersDefinition,
  overviewDefinition,
  preCDashboardConfig,
  recordConfig,
  testSource,
} from './fixtures.js';
import { landed, tracked } from './fixtures/writes.js';
import { panel, pending } from './fixtures/dashboard.js';

afterEach(cleanup);

describe('DashboardWorkbench', () => {
  const overview: ViewInstance = {
    id: 'overview-1',
    definitionId: 'overview',
    title: 'Operations',
    scope: 'personal',
    revision: '1',
    config: dashboardConfig({
      fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      panels: [
        panel({
          title: 'Pending',
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        }),
      ],
    }),
  };

  function setup(instance: ViewInstance = overview) {
    const source = testSource();
    const store = tracked(
      new MemoryViewStore({ instances: [pending, instance] }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => source,
    });
    return { engine, source, store };
  }

  it('opens a dashboard, shows its panels and its filter bar', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByText('Pending')).toBeTruthy());
    // Refresh moved into the title bar with the save commands; the row of
    // buttons between the editor and the panels is gone.
    const header = document.querySelector(
      '[data-slot="view-header"]',
    ) as HTMLElement;
    expect(
      within(header).getByRole('button', { name: 'Refresh' }),
    ).toBeTruthy();
    expect(header.textContent).toContain('Operations');
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    // The board's filters are a bar over the panels (D22 F), not a fold in
    // the title bar with an Apply: there is nothing to apply.
    const bar = screen.getByRole('region', { name: 'Filters' });
    expect(within(bar).getByRole('group', { name: 'Region' })).toBeTruthy();
    expect(within(header).queryByRole('button', { name: 'Filter' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
  });

  it('pins the mode and the preset it is given on its surface (5B)', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        theme="light"
        preset="neutral"
      />,
    );

    await waitFor(() => expect(screen.getByText('Pending')).toBeTruthy());
    const surface = document.querySelector('[data-slot="view-surface"]');
    expect(surface?.getAttribute('data-theme')).toBe('light');
    expect(surface?.getAttribute('data-fve-preset')).toBe('neutral');
  });

  it('opens under the filters a host keeps in its address, and tells it what they hold (D22 F)', async () => {
    const onFiltersChange = vi.fn();
    const { engine, source } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [
          {
            name: 'region',
            label: 'Region',
            kind: 'string',
            required: true,
            default: ['CN'],
          },
        ],
        panels:
          overview.config.kind === 'dashboard' ? overview.config.panels : [],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        initialFilters={{ values: { region: ['EU'] } }}
        onFiltersChange={onFiltersChange}
      />,
    );

    await waitFor(() =>
      expect(onFiltersChange).toHaveBeenCalledWith({
        values: { region: ['EU'] },
      }),
    );
    // The first page asked already carries it: no run under the default.
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    expect(JSON.stringify(vi.mocked(source.paged).mock.calls[0])).toContain(
      '"EU"',
    );
    const runtime = engine
      .openRuntimes()
      .find(opened => opened.kind === 'dashboard') as DashboardRuntime;
    act(() => {
      runtime.setFilterValue('region', null);
    });
    // Required: cleared, it holds its default again.
    await waitFor(() =>
      expect(onFiltersChange).toHaveBeenLastCalledWith({
        values: { region: ['CN'] },
      }),
    );
  });

  it('opens under what it takes of a stale address, the rest left out and said (D22 F)', async () => {
    const onFiltersChange = vi.fn();
    const { engine, source } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [
          { name: 'region', label: 'Region', kind: 'string' },
          { name: 'created', label: 'Created', kind: 'date' },
        ],
        panels:
          overview.config.kind === 'dashboard' ? overview.config.panels : [],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        // A filter taken off the board since, and a date it cannot read.
        initialFilters={{
          values: { region: ['EU'], gone: ['x'], created: 'yesterday' },
        }}
        onFiltersChange={onFiltersChange}
      />,
    );

    await waitFor(() =>
      expect(onFiltersChange).toHaveBeenCalledWith({
        values: { region: ['EU'] },
      }),
    );
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    expect(JSON.stringify(vi.mocked(source.paged).mock.calls[0])).toContain(
      '"EU"',
    );
    const runtime = engine
      .openRuntimes()
      .find(opened => opened.kind === 'dashboard') as DashboardRuntime;
    expect(runtime.refusedFilters.map(found => found.path)).toEqual([
      ['filters', 'gone'],
      ['filters', 'created'],
    ]);

    // Said once, over the bar, in the catalogue's words; put away by its ✕,
    // which hands the keyboard on to the bar it was about.
    const notice = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="dashboard-filters-refused"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(notice.getAttribute('role')).toBe('alert');
    expect(notice.textContent).toContain(
      'Some of the filters in the link could not be used',
    );
    expect(notice.textContent).toContain('gone');
    fireEvent.click(within(notice).getByRole('button', { name: 'Dismiss' }));
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="dashboard-filters-refused"]'),
      ).toBeNull(),
    );
    await waitFor(() =>
      expect(
        screen
          .getByRole('region', { name: 'Filters' })
          .contains(document.activeElement),
      ).toBe(true),
    );
  });

  it('says nothing over the bar when the address was taken whole', async () => {
    const { engine, source } = setup(overview);
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    expect(
      document.querySelector('[data-slot="dashboard-filters-refused"]'),
    ).toBeNull();
  });

  /**
   * Nothing reloads a pin: once the manager deletes the pinned view, the
   * engine disposes the runtime and reopening the id answers "no such view"
   * for as long as the page is open, so the workbench has to let go itself.
   */
  it('moves on to the view that is still there once the open one is deleted', async () => {
    const other: ViewInstance = {
      ...overview,
      id: 'overview-2',
      title: 'Night shift',
    };
    const store = tracked(
      new MemoryViewStore({
        instances: [pending, overview, other],
      }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Operations' }).ariaCurrent,
      ).toBe('true'),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Manage dashboards' }));
    const manager = await screen.findByRole('dialog');
    const row = Array.from(
      manager.querySelectorAll('[data-slot="view-manager-row"]'),
    ).find(candidate =>
      candidate.textContent?.includes('Operations'),
    ) as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    const confirm = await screen.findByRole('alertdialog');
    // What deleting a board costs, said as a board's (D26 Q34): the
    // analyses made inside it go with it.
    expect(confirm.textContent).toContain(
      'Only the dashboard is removed: its records and the saved views its panels show stay, and the analyses made inside it go with it.',
    );
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });

    await waitFor(
      () =>
        expect(screen.queryByRole('button', { name: 'Operations' })).toBeNull(),
      { timeout: 3000 },
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Night shift' }).ariaCurrent,
      ).toBe('true'),
    );
  });

  /**
   * D27: a board draws no 「正在显示」 band. Its filters run as they change,
   * so the filter bar is what the panels show; the board's fixed scope
   * reaches the panels all the same.
   */
  it('draws no applied band: the filter bar is what the panels show (D27)', async () => {
    const { engine, source } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        fixed: {
          op: 'and',
          children: [{ field: 'region', operator: 'NE', value: 'north' }],
        },
        panels: [
          panel({
            title: 'Pending',
            bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          }),
        ],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    expect(screen.getByRole('region', { name: 'Filters' })).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Showing' })).toBeNull();
    expect(JSON.stringify(vi.mocked(source.paged).mock.lastCall)).toContain(
      '"north"',
    );
  });

  /**
   * D26 Q31: a board stored before batch C opens with what no filter could
   * hold as its fixed scope. The filter bar's row says it (D27), as the
   * board's 「Fixed scope」 and with no ✕ — no reader takes it out — while
   * the leaf a filter could hold is that filter's default, the reader's to
   * change as any filter's value.
   */
  it('shows a pre-C board’s fixed scope read-only, beside the reader’s own filters', async () => {
    const { engine, source } = setup({
      ...overview,
      config: preCDashboardConfig(
        {
          op: 'and',
          children: [
            { field: 'region', operator: 'IN', value: ['CN'] },
            { field: 'region', operator: 'NE', value: 'north' },
          ],
        },
        {
          fields: [{ name: 'region', label: 'Region', kind: 'string' }],
          panels:
            overview.config.kind === 'dashboard' ? overview.config.panels : [],
        },
      ),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    const bar = screen.getByRole('region', { name: 'Filters' });
    const fixed = within(bar).getByRole('group', { name: 'Fixed scope' });
    expect(
      fixed.querySelector('[data-slot="filter-reading"]')?.textContent,
    ).toBe('Region is not north');
    // Its one button says why it stays, and takes nothing out.
    expect(
      within(fixed)
        .getAllByRole('button')
        .map(button => button.getAttribute('aria-label')),
    ).toEqual([
      'The dashboard itself holds every panel to this; it cannot be changed here.',
    ]);
    expect(screen.queryByRole('region', { name: 'Showing' })).toBeNull();
    // Both halves reach the panel: the fixed scope, and the filter's value.
    const asked = JSON.stringify(vi.mocked(source.paged).mock.lastCall);
    expect(asked).toContain('"north"');
    expect(asked).toContain('"CN"');

    // The reader's own filter is theirs: changing it leaves the fixed scope
    // in force and on the band, and the board unmodified.
    const runtime = engine
      .openRuntimes()
      .find(opened => opened.kind === 'dashboard') as DashboardRuntime;
    vi.mocked(source.paged).mockClear();
    act(() => runtime.setFilterValue('region', ['EU']));
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    const again = JSON.stringify(vi.mocked(source.paged).mock.lastCall);
    expect(again).toContain('"EU"');
    expect(again).toContain('"north"');
    expect(again).not.toContain('"CN"');
    expect(
      within(screen.getByRole('region', { name: 'Filters' })).getByRole(
        'group',
        { name: 'Fixed scope' },
      ).textContent,
    ).toContain('north');
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('draws no applied band over a board its bar says everything about', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    expect(screen.queryByRole('region', { name: 'Showing' })).toBeNull();
  });

  /**
   * The title bar is the one place a dashboard is saved from now, and what
   * lands there has to reach the sidebar and the open view alike.
   */
  it('saves a copy from the title bar and opens it', async () => {
    const { engine, store } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    fireEvent.click(
      screen.getByRole('button', { name: 'More dashboard actions' }),
    );
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Night shift' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create dashboard' }),
    );

    await landed(store);
    expect((await store.list('overview')).map(item => item.title)).toContain(
      'Night shift',
    );
    await waitFor(() =>
      expect(
        within(screen.getByRole('navigation')).getByRole('button', {
          name: 'Night shift',
        }).ariaCurrent,
      ).toBe('true'),
    );
  });

  /**
   * A copy is judged for the audience it is headed for, not the one the open
   * view sits in: `engine.saveAs` validates at the target scope. Gating the
   * dialog on the open draft's own validity therefore answered the wrong
   * question in both directions — it let through a copy the engine would
   * refuse, and it barred a copy that is the way out of an invalid view.
   */
  describe('copying a dashboard to another audience', () => {
    /** A personal record view: a shared dashboard may not show it. */
    const personalPanel: ViewInstance = {
      ...pending,
      id: 'mine',
      title: 'My orders',
      scope: 'personal',
    };

    function board(scope: ViewInstance['scope']): ViewInstance {
      return {
        ...overview,
        scope,
        config: dashboardConfig({
          panels: [panel({ title: 'Mine', instanceId: 'mine' })],
        }),
      };
    }

    function workbench(instance: ViewInstance) {
      const store = tracked(
        new MemoryViewStore({
          instances: [personalPanel, instance],
        }),
      );
      const engine = new ViewEngine({
        definitions: [ordersDefinition(), overviewDefinition()],
        store,
        resolveSource: () => testSource(),
      });
      render(
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId="overview-1"
        />,
      );
      return { store, engine };
    }

    /** The save-as dialog, opened from the title bar's menu. */
    async function openCopy() {
      fireEvent.click(
        await screen.findByRole('button', { name: 'More dashboard actions' }),
      );
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
      return screen.findByRole('dialog');
    }

    /**
     * D22 B: a shared board may stand on a personal view — the panel is
     * blank for whoever cannot read it, and says so — so copying a personal
     * board to everyone is a save like any other.
     */
    it('copies a board on a personal view to everyone', async () => {
      const { store } = workbench(board('personal'));
      await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

      const dialog = await openCopy();
      fireEvent.change(within(dialog).getByLabelText('Title'), {
        target: { value: 'For everyone' },
      });
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Everyone' }));
      fireEvent.click(
        within(dialog).getByRole('button', { name: 'Create dashboard' }),
      );

      await landed(store);
      expect((await store.list('overview')).map(item => item.title)).toContain(
        'For everyone',
      );
    });

    it('shows a refusal inside the dialog and stays open', async () => {
      const { store, engine } = workbench(board('personal'));
      await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
      // Only the engine knows, at the scope the copy is headed for.
      vi.spyOn(engine, 'saveAs').mockRejectedValue(
        new ViewCommandError(issue('view.config.invalid', [])),
      );

      const dialog = await openCopy();
      fireEvent.change(within(dialog).getByLabelText('Title'), {
        target: { value: 'For everyone' },
      });
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Everyone' }));
      fireEvent.click(
        within(dialog).getByRole('button', { name: 'Create dashboard' }),
      );

      // The refusal is about the board being copied, and says so (Q34).
      expect(
        await within(dialog).findByText(
          'Fix what this dashboard reports before saving it.',
        ),
      ).toBeDefined();
      // Said where the user is still looking, with the answer still theirs.
      expect(screen.getByRole('dialog')).toBe(dialog);
      expect(
        (await store.list('overview')).map(item => item.title),
      ).not.toContain('For everyone');
    });

    it('takes a personal copy of a shared board on a personal view', async () => {
      // Saved shared over a personal panel: the panel runs for its author
      // and says, in its header, that not every reader of the board sees it.
      const { store } = workbench(board('shared'));
      await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
      expect(
        screen.getByLabelText(
          'The view this panel shows is not open to everyone who reads this dashboard.',
        ),
      ).toBeTruthy();

      const dialog = await openCopy();
      fireEvent.change(within(dialog).getByLabelText('Title'), {
        target: { value: 'Mine after all' },
      });
      fireEvent.click(within(dialog).getByRole('radio', { name: 'Only me' }));
      fireEvent.click(
        within(dialog).getByRole('button', { name: 'Create dashboard' }),
      );

      await landed(store);
      expect((await store.list('overview')).map(item => item.title)).toContain(
        'Mine after all',
      );
    });
  });

  // Its own alerts render above the provider of the surface it draws, yet
  // must read the wording it was handed, as everything inside that surface does.
  it("takes the host's wording, for its own alerts and everything inside", async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="missing"
        messages={{
          'label.dashboard.unopenable': '打不开这个仪表盘',
          'label.dashboard.group.personal': '仅自己',
        }}
      />,
    );

    expect(await screen.findByText('打不开这个仪表盘')).toBeDefined();
    // The sidebar is named by the definition rather than by the catalogue;
    // what it says about itself still comes from the wording handed in.
    expect(screen.getByRole('navigation', { name: 'Overview' })).toBeDefined();
    expect(screen.getByText('仅自己')).toBeDefined();
  });

  /** A dashboard whose one panel shows `config` over the named orders. */
  function named(config: ViewInstance['config']) {
    return new ViewEngine({
      definitions: [namedOrdersDefinition(), overviewDefinition()],
      store: tracked(
        new MemoryViewStore({
          instances: [
            { ...pending, config },
            {
              ...overview,
              config: dashboardConfig({
                panels: [panel({ title: 'Pending' })],
              }),
            },
          ],
        }),
      ),
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

  it("shows a panel's times on the clock of the engine's zone, in the language given", async () => {
    const engine = named(
      recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'createdAt' }] },
      }),
    );

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        locale="en-GB"
      />,
    );

    expect(await screen.findByText(inZone(INSTANT))).toBeTruthy();
  });

  it("names a chart panel's categories as their field names its values", async () => {
    // The table shows only the count; the chart still groups by warehouse,
    // and names its bars through the schema rather than the table's columns.
    const engine = named(
      analysisConfig({
        layout: 'chart',
        table: { columns: [{ alias: 'orders' }] },
      }),
    );

    // Queries look inside the workbench rendered here.
    const { container } = render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    // The drawing alone: the `sr-only` reading beside every chart says the
    // same category again, and what it says is pinned in
    // `test/analysisChartA11y.test.tsx`.
    expect(
      await within(container).findByText('China', {
        ignore: 'script, style, [data-slot="chart-reading"] *',
      }),
    ).toBeTruthy();
  });

  it('runs the panels again on demand', async () => {
    const { engine, source } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(source.paged).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(source.paged).toHaveBeenCalledTimes(2));
  });

  it('leaves the filter out when the dashboard declares no global fields', async () => {
    const { engine } = setup({
      ...overview,
      config: dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByText('Pending')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
  });

  it('says what needs fixing before the dashboard can run', async () => {
    const { engine, source } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [{ name: 'not a field', label: 'Broken', kind: 'string' }],
        panels: [panel({ title: 'Pending' })],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    // The strip is a line, and one finding is that line: said outright
    // rather than headed and folded away behind a count of one (F-14).
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'not a field is not a usable field name.',
      ),
    );
    expect(screen.queryByRole('button', { name: '1 more' })).toBeNull();
    // Nothing ran: an error blocks the apply that would have created panels.
    expect(source.paged).not.toHaveBeenCalled();
  });

  /**
   * D26 Q34: the chrome around a board names a board — the sidebar's fold,
   * its list, the switcher and the save button's menu among them — never
   * 「视图」, which on this page is what the panels show.
   */
  it('names the board in its chrome: the list, its fold, the switcher, the save menu (Q34)', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        messages={zhCN}
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    expect(screen.getByRole('button', { name: '收起仪表盘列表' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '更多仪表盘操作' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: '我的仪表盘' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '管理仪表盘' })).toBeTruthy();
    // Folded, the list gives way to the switcher and the way back.
    fireEvent.click(screen.getByRole('button', { name: '收起仪表盘列表' }));
    expect(
      await screen.findByRole('button', { name: '展开仪表盘列表' }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: '切换仪表盘' })).toBeTruthy();
    // Nothing on the chrome calls the board a view.
    const named = [...document.querySelectorAll('[aria-label]')]
      .map(element => element.getAttribute('aria-label') ?? '')
      .filter(name => name.includes('视图'));
    expect(named).toEqual([]);
  });

  /**
   * X-03: the kernel says a filter by its key, and a filter added on the bar
   * is keyed `filter-1` — a word no reader typed. The status line says it by
   * its name on the bar.
   */
  it('says a filter by its name on the bar, never its key (X-03)', async () => {
    const { engine, source } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [
          {
            name: 'filter-1',
            label: 'Shipped on',
            kind: 'datetime',
            required: true,
          },
        ],
        panels: [panel({ title: 'Pending' })],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The filter Shipped on always has a value, so it needs a default to start at.',
      ),
    );
    expect(screen.getByRole('alert').textContent).not.toContain('filter-1');
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('reports a dashboard it cannot open', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="missing"
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'This dashboard no longer exists.',
      ),
    );
  });

  /**
   * D26 Q34: what the engine reports about the board — a write refused, the
   * list that would not load, the board gone — is said of a board. The
   * engine raises one code for every kind; the surface picks the word.
   */
  it('says what the engine reports about the board as a board’s (Q34)', async () => {
    const { engine, store } = setup();
    vi.spyOn(store, 'list').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'gateway down'),
    );
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
        messages={zhCN}
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    expect(
      (await screen.findByText('仪表盘列表加载失败：无法连接服务端。'))
        .textContent,
    ).not.toContain('视图');
    const runtime = engine
      .openRuntimes()
      .find(opened => opened.kind === 'dashboard') as DashboardRuntime;
    act(() => runtime.edit({ refresh: { interval: 300 } }));
    fireEvent.click(await screen.findByRole('button', { name: '保存' }));

    // The store's refusal, said of the board, with its own words after.
    const refused = await screen.findByText(/^你不能写这个仪表盘。/);
    expect(refused.textContent).toContain('not yours');
    // Nothing the status lines say calls the board a view.
    for (const line of screen.queryAllByRole('alert'))
      expect(line.textContent).not.toContain('视图');
  });

  /**
   * A warning about the dashboard itself is the workbench's to say; a
   * warning about one panel is that panel's, in its own frame, and saying it
   * twice would only make the notice longer than the caveat. Neither blocks:
   * the healthy panel still runs, and nothing asks for a fix.
   */
  it('notes what is worth noting about the dashboard, and leaves panel findings to the panels', async () => {
    const { engine } = setup({
      ...overview,
      config: dashboardConfig({
        // A filter that picks from a list of its own, and the list is empty:
        // the kernel warns about the board.
        fields: [
          { name: 'region', label: 'Region', kind: 'string', options: [] },
        ],
        panels: [
          panel({
            title: 'Pending',
            bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          }),
          panel({
            id: 'gone',
            title: 'Gone',
            instanceId: 'deleted',
            layout: { x: 6, y: 0, w: 6, h: 4 },
          }),
        ],
      }),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const notice = document.querySelector(
      '[data-slot="status-strip"][data-tone="warning"]',
    );
    expect(notice?.textContent).toContain('the list is empty');
    expect(notice?.textContent).not.toContain('deleted');
    expect(
      screen.getByText(
        'The view this panel shows was deleted, or you do not have access to it',
      ),
    ).toBeTruthy();
    expect(screen.queryByText(/needs fixing/)).toBeNull();
  });

  /**
   * A global condition mapped onto a panel field that warns is, before
   * Apply, a finding under `['panels', …]` that the applied panels do not
   * carry and the dashboard's own list leaves out, so nothing showed it —
   * and Save would have persisted it unseen. Until Apply hands it to the
   * panel, the notice says it; once the panel wears it, the notice lets go.
   */
  it('says a draft panel warning no panel carries yet, until apply hands it over', async () => {
    // Warns on the panel's field only, so the finding exists at panel level
    // and nowhere else: the dashboard's own validation of the global
    // condition has nothing to say about it.
    const rounded: FieldKind = {
      id: 'rounded',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => null,
      validate: ({ value, field, path }) =>
        field.name === 'mass' &&
        typeof value === 'number' &&
        !Number.isInteger(value)
          ? [{ code: 'filter.value.rounded', severity: 'warning', path }]
          : [],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: Math.round(leaf.value as number),
      }),
      editor: () => ({ input: 'number' }),
      describe: ({ leaf, field }) => ({
        text: `${field.label} = ${String(leaf.value)}`,
        value: { kind: 'text', value: String(leaf.value) },
      }),
    };
    const orders = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...orders,
          fields: [
            ...orders.fields,
            { name: 'mass', label: 'Mass', kind: 'rounded' },
          ],
        },
        overviewDefinition(),
      ],
      store: tracked(
        new MemoryViewStore({
          instances: [
            pending,
            {
              ...overview,
              config: dashboardConfig({
                fields: [{ name: 'weight', label: 'Weight', kind: 'rounded' }],
                panels: [
                  panel({
                    title: 'Pending',
                    bindings: [{ globalField: 'weight', panelField: 'mass' }],
                  }),
                ],
              }),
            },
          ],
        }),
      ),
      resolveSource: () => testSource(),
      kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    });

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const notice = () =>
      document.querySelector('[data-slot="status-strip"][data-tone="warning"]');
    const marker = () => document.querySelector('[data-slot="panel-warning"]');
    expect(notice()).toBeNull();
    const runtime = engine
      .openRuntimes()
      .find(opened => opened.kind === 'dashboard') as DashboardRuntime;

    act(() =>
      runtime.edit({
        fixed: {
          op: 'and',
          children: [{ field: 'weight', operator: 'EQ', value: 2.5 }],
        },
      }),
    );

    // Above the grid, a panel's finding names its panel: 「这个面板」 would
    // point at nothing up here.
    await waitFor(() =>
      expect(notice()?.textContent).toContain('Pending: filter.value.rounded'),
    );
    expect(marker()).toBeNull();

    act(() => runtime.apply());

    await waitFor(() => expect(marker()).toBeTruthy());
    expect(notice()).toBeNull();
  });
});
