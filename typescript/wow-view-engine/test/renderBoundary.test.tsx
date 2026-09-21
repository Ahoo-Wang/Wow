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

import { createElement } from 'react';
import {
  act,
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { DashboardRuntime, DashboardViewConfig } from '../src/index.js';
import { useDashboard } from '../src/react/index.js';
import type { RecordActionSlots } from '../src/react/index.js';
import {
  DashboardGrid,
  MessagesProvider,
  RecordWorkbench,
  RenderBoundary,
  ViewSurface,
  zhCN,
} from '../src/ui/index.js';
import type { RenderFailure } from '../src/ui/index.js';
import type * as Panels from '../src/ui/DashboardPanels.js';
import {
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { setup } from './fixtures/ui.js';

/**
 * One content panel made to throw on demand, so a dashboard can be shown a
 * body that fails to draw. Everything else in the module is the real thing.
 */
vi.mock('../src/ui/DashboardPanels.js', async importOriginal => {
  const original = await importOriginal<typeof Panels>();
  return {
    ...original,
    ContentPanel(props: Parameters<typeof original.ContentPanel>[0]) {
      if (props.panel.kind === 'markdown' && props.panel.content === 'BOOM')
        throw new Error('markdown exploded');
      return createElement(original.ContentPanel, props);
    },
  };
});

afterEach(cleanup);

// React reports every caught error to the console as well; the assertions
// below are about what the user sees, so that noise stays out of the run.
beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

/** A child that throws while a switch is on, and draws once it is off. */
function Fuse({ blown, label = 'drawn' }: { blown: boolean; label?: string }) {
  if (blown) throw new Error('blown');
  return <p>{label}</p>;
}

describe('RenderBoundary', () => {
  it('replaces the part that threw with a recoverable state, and reports it', async () => {
    const failures: RenderFailure[] = [];
    let blown = true;
    const { rerender } = render(
      <ViewSurface>
        <RenderBoundary name="result" onFailure={f => failures.push(f)}>
          <Fuse blown={blown} />
        </RenderBoundary>
      </ViewSurface>,
    );

    const alert = screen.getByRole('alert');
    expect(alert.getAttribute('data-boundary')).toBe('result');
    expect(
      within(alert).getByText('This part could not be drawn'),
    ).toBeTruthy();
    // The error's own words, so the host can tell which of its parts it was.
    expect(within(alert).getByText('blown')).toBeTruthy();
    expect(failures).toHaveLength(1);
    expect(failures[0].boundary).toBe('result');
    expect((failures[0].error as Error).message).toBe('blown');

    // Retry draws the children again; once the cause is gone, they stand.
    blown = false;
    rerender(
      <ViewSurface>
        <RenderBoundary name="result" onFailure={f => failures.push(f)}>
          <Fuse blown={blown} />
        </RenderBoundary>
      </ViewSurface>,
    );
    await userEvent.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('drawn')).toBeTruthy();
  });

  it('draws the failure again when retried while the cause is still there', async () => {
    const failures: RenderFailure[] = [];
    render(
      <ViewSurface>
        <RenderBoundary name="editor" onFailure={f => failures.push(f)}>
          <Fuse blown />
        </RenderBoundary>
      </ViewSurface>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(screen.getByRole('alert').getAttribute('data-boundary')).toBe(
      'editor',
    );
    expect(failures).toHaveLength(2);
  });

  it('lets go of a failure when its reset key changes', () => {
    const { rerender } = render(
      <ViewSurface>
        <RenderBoundary name="result" resetKeys={['view-a']}>
          <Fuse blown />
        </RenderBoundary>
      </ViewSurface>,
    );
    expect(screen.getByRole('alert')).toBeTruthy();
    rerender(
      <ViewSurface>
        <RenderBoundary name="result" resetKeys={['view-b']}>
          <Fuse blown={false} label="another view" />
        </RenderBoundary>
      </ViewSurface>,
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('another view')).toBeTruthy();
  });

  it('keeps to one line where it stands in a row of controls', () => {
    render(
      <ViewSurface>
        <RenderBoundary name="actions" compact>
          <Fuse blown />
        </RenderBoundary>
      </ViewSurface>,
    );
    const alert = screen.getByRole('alert');
    expect(alert.tagName).toBe('SPAN');
    expect(alert.getAttribute('data-boundary')).toBe('actions');
    expect(alert.getAttribute('title')).toBe('blown');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeTruthy();
  });

  it('speaks the host catalogue', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <RenderBoundary name="result">
          <Fuse blown />
        </RenderBoundary>
      </MessagesProvider>,
    );
    expect(screen.getByText('这一块没能画出来')).toBeTruthy();
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy();
  });
});

describe('the workbench boundaries', () => {
  /**
   * The host's row slot runs inside the result block. When it throws, the
   * rows go and the rest of the workbench stays: the title bar, the editor
   * and whatever the user had typed into it.
   */
  it('holds a throwing row action to the result block', async () => {
    const { engine } = setup();
    const failures: RenderFailure[] = [];
    let broken = true;
    const actions: RecordActionSlots = {
      row: ({ row }) => {
        if (broken && row.key === 'o-1') throw new Error('row action failed');
        return <button type="button">act {String(row.key)}</button>;
      },
    };
    const { rerender } = render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        actions={actions}
        onRenderFailure={f => failures.push(f)}
      />,
    );

    const alert = await screen.findByRole('alert');
    expect(alert.getAttribute('data-boundary')).toBe('result');
    expect(alert.closest('[data-slot="result-block"]')).not.toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
    // Beyond the boundary, the view is still the view: its title bar, its
    // save commands and its editor are all still on screen.
    expect(
      document.querySelector('[data-slot="view-header-block"]'),
    ).not.toBeNull();
    expect(screen.getByRole('button', { name: /Save/ })).toBeTruthy();
    // A saved view opens with its editor folded, so what is asserted is the
    // fold's toggle rather than the panel inside it.
    expect(
      document.querySelector('[data-slot="editor-toggle"]'),
    ).not.toBeNull();
    expect(failures.map(f => f.boundary)).toEqual(['result']);
    expect((failures[0].error as Error).message).toBe('row action failed');

    broken = false;
    rerender(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        actions={actions}
        onRenderFailure={f => failures.push(f)}
      />,
    );
    await userEvent.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(screen.getByRole('button', { name: 'act o-1' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('holds a throwing global action to its slot in the title bar', async () => {
    const { engine } = setup();
    const failures: RenderFailure[] = [];
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        actions={{
          global: () => {
            throw new Error('global action failed');
          },
        }}
        onRenderFailure={f => failures.push(f)}
      />,
    );

    const alert = await screen.findByRole('alert');
    expect(alert.getAttribute('data-boundary')).toBe('actions');
    expect(alert.closest('[data-slot="view-header-block"]')).not.toBeNull();
    // The rows are drawn regardless: the failure is the slot's alone.
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(screen.getByRole('button', { name: /Save/ })).toBeTruthy();
    expect(failures.map(f => f.boundary)).toEqual(['actions']);
  });
});

describe('the dashboard panel boundaries', () => {
  async function openDashboard(config: DashboardViewConfig) {
    const store = new MemoryViewStore({ instances: [] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
      environment: testEnvironment().environment,
    });
    const instance = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config,
      },
      { requestId: 'r' },
    );
    const runtime = (await engine.open(instance.id)) as DashboardRuntime;
    const view = renderHook(() => useDashboard(runtime));
    await act(async () => {
      await Promise.resolve();
    });
    return () => view.result.current;
  }

  it('keeps a panel that fails to draw from taking the others with it', async () => {
    const controller = await openDashboard(
      dashboardConfig({
        panels: [
          {
            id: 'boom',
            kind: 'markdown',
            content: 'BOOM',
            layout: { x: 0, y: 0, w: 6, h: 4 },
          },
          {
            id: 'fine',
            kind: 'markdown',
            content: '# Weekly review',
            layout: { x: 6, y: 0, w: 6, h: 4 },
          },
        ],
      }),
    );
    const failures: RenderFailure[] = [];
    render(
      <ViewSurface>
        <DashboardGrid
          dashboard={controller()}
          onRenderFailure={f => failures.push(f)}
        />
      </ViewSurface>,
    );

    // The other panel is on screen; the failed one says so in its own body.
    expect(screen.getByRole('heading', { name: 'Weekly review' })).toBeTruthy();
    const alert = screen.getByRole('alert');
    expect(alert.getAttribute('data-boundary')).toBe('panel');
    expect(alert.closest('[data-slot="dashboard-panel"]')).not.toBeNull();
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(failures).toEqual([
      expect.objectContaining({ boundary: 'panel', panelId: 'boom' }),
    ]);
  });
});
