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
import userEvent from '@testing-library/user-event';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewCommandError,
  ViewEngine,
  defaultRuntimeEnvironment,
  issue,
  withFieldKinds,
  type DashboardRuntime,
  type FieldKind,
  type ViewInstance,
} from '../src/index.js';
import { DashboardWorkbench } from '../src/ui/index.js';
import {
  INSTANT,
  ZONE,
  analysisConfig,
  dashboardConfig,
  inZone,
  namedOrdersDefinition,
  ordersDefinition,
  overviewDefinition,
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

  it('opens a dashboard, shows its panels and its global filter', async () => {
    const user = userEvent.setup();
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

    // The global filter lives in the title bar's fold now, and a saved view
    // opens folded: the panels are what the dashboard is for, so nothing of
    // the editor is on the page until the handle beside the name asks for
    // it.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();

    await user.click(within(header).getByRole('button', { name: 'Filter' }));

    expect(screen.getByRole('button', { name: /Apply/ })).toBeTruthy();
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

    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    const manager = await screen.findByRole('dialog');
    const row = Array.from(
      manager.querySelectorAll('[data-slot="view-manager-row"]'),
    ).find(candidate =>
      candidate.textContent?.includes('Operations'),
    ) as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    const confirm = await screen.findByRole('alertdialog');
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
   * A dashboard has no result of its own — every panel runs its own query —
   * so the bar over the panels reads the applied config rather than a
   * result's, and shows once any panel has been asked. Reading a result that
   * is always null, it never showed at all.
   */
  it('says what the panels were asked under', async () => {
    const { engine } = setup({
      ...overview,
      config: dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        filter: {
          op: 'and',
          children: [{ field: 'region', operator: 'EQ', value: 'north' }],
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

    const bar = screen.getByRole('region', { name: 'Showing' });
    expect(bar.textContent).toContain('Region');
    expect(bar.textContent).toContain('north');
  });

  it('says so plainly when the panels were asked under nothing', async () => {
    const { engine } = setup();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());

    expect(
      screen.getByRole('region', { name: 'Showing' }).textContent,
    ).toContain('All records');
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

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Night shift' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
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
        await screen.findByRole('button', { name: 'More view actions' }),
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
        within(dialog).getByRole('button', { name: 'Create view' }),
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
        within(dialog).getByRole('button', { name: 'Create view' }),
      );

      expect(
        await within(dialog).findByText(
          'Fix what this view reports before saving it.',
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
        within(dialog).getByRole('button', { name: 'Create view' }),
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
          'label.view.unopenable': '打不开这个视图',
          'label.scope.group.personal': '仅自己',
        }}
      />,
    );

    expect(await screen.findByText('打不开这个视图')).toBeDefined();
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
        'This view no longer exists.',
      ),
    );
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
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        // A simple-mode config holding an OR tree: the advanced editor opens
        // and the kernel warns.
        filterMode: 'simple',
        filter: {
          op: 'or',
          children: [{ field: 'region', operator: 'EQ', value: 'CN' }],
        },
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
    expect(notice?.textContent).toContain('advanced editor');
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
        filter: {
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
