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
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
} from '../src/index.js';
import type { ViewInstance, FilterTree } from '../src/index.js';
import { EmbeddedView } from '../src/ui/index.js';
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
        messages={{ 'label.record.select-all': '全选' }}
      />,
    );
    expect(await screen.findByRole('checkbox', { name: '全选' })).toBeDefined();
  });

  it('names chart categories as their field names its values', async () => {
    // The table shows only the count; the chart still groups by warehouse,
    // and names its bars through the schema rather than the table's columns.
    const engine = embed(
      analysisConfig({
        layout: 'chart',
        table: { columns: [{ alias: 'orders' }] },
      }),
    );

    // Recharts measures text in a span of its own on the body; the chart is
    // what is asked about.
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
    expect(screen.queryByRole('button', { name: /Run/ })).toBeNull();
  });

  it('shows a dashboard as its grid of panels', async () => {
    const panelled: ViewInstance = {
      id: 'overview-1',
      definitionId: 'overview',
      title: 'Overview',
      scope: 'personal',
      revision: '1',
      config: dashboardConfig({
        panels: [
          {
            id: 'rows',
            kind: 'markdown',
            title: 'Note',
            content: '# Weekly review',
            layout: { x: 0, y: 0, w: 6, h: 2 },
          },
        ],
      }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: new MemoryViewStore({ instances: [panelled] }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="overview-1" />);

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Weekly review' }),
      ).toBeTruthy(),
    );
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
        'The source answered: down',
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
      expect(screen.getByRole('alert').textContent).toContain('needs fixing'),
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
        'The source answered: down',
      ),
    );
    // Both at once, the way the workbenches do it: the rows that did come
    // back are still the real ones, and the strip says so behind its
    // disclosure rather than over an empty frame.
    fireEvent.click(screen.getByRole('button', { name: '1 more' }));
    expect(screen.getByRole('alert').textContent).toContain(
      'Showing the last successful result',
    );
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });
});
