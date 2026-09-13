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
import { afterEach, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { DashboardLayout } from '../../src/dashboard/DashboardGrid.js';
import { DashboardView } from '../../src/dashboard/DashboardView.js';
import type { DashboardPanel } from '../../src/dashboard/dashboardModel.js';
import type { DeepReadonly } from '../../src/lib/types.js';
import { dashboardSetup } from './runtimeFixtures.js';
vi.hoisted(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it('keyboard geometry preserve the mounted body and stable visual reading order', () => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    width: 1200,
    height: 500,
    top: 0,
    left: 0,
    right: 1200,
    bottom: 500,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });
  function Demo() {
    const [panels, setPanels] = useState<
      readonly DeepReadonly<DashboardPanel>[]
    >(
      ['a', 'b'].map((id, index) => ({
        id,
        kind: 'view' as const,
        instanceId: id,
        layout: { x: index * 6, y: 0, w: 6, h: 5 },
      })),
    );
    return (
      <DashboardLayout
        panels={panels}
        enabled
        title={id => id}
        onCommit={setPanels}
      >
        {panel => <input aria-label={`${panel.id}备注`} defaultValue="" />}
      </DashboardLayout>
    );
  }
  render(<Demo />);
  const input = screen.getByRole('textbox', { name: 'a备注' });
  fireEvent.change(input, { target: { value: 'keep' } });
  for (let step = 0; step < 6; step++)
    fireEvent.keyDown(screen.getByRole('button', { name: '移动b' }), {
      key: 'ArrowLeft',
    });
  expect(
    screen.getAllByRole('textbox').map(node => node.getAttribute('aria-label')),
  ).toEqual(['b备注', 'a备注']);
  expect(screen.getByRole('textbox', { name: 'a备注' })).toBe(input);
  fireEvent.keyDown(screen.getByRole('button', { name: '调整a尺寸' }), {
    key: 'ArrowDown',
  });
  expect(screen.queryByRole('spinbutton')).toBeNull();
  expect((input as HTMLInputElement).value).toBe('keep');
  expect(screen.getByRole('textbox', { name: 'a备注' })).toBe(input);
});
it('undo, redo and cancel layout edits preserve query positions and issue no requests', async () => {
  const { engine, paged } = dashboardSetup();
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  render(<DashboardView runtime={runtime} />);
  await waitFor(() =>
    expect(screen.getAllByRole('button', { name: '刷新child' })).toHaveLength(
      2,
    ),
  );
  const initial = runtime.getSnapshot().config.panels;
  const position = runtime.getSnapshot().panels[initial[0].id].position;
  paged.mockClear();
  fireEvent.click(screen.getByRole('button', { name: '编辑布局' }));
  fireEvent.keyDown(
    screen.getAllByRole('button', { name: '调整child尺寸' })[0],
    { key: 'ArrowDown' },
  );
  expect(runtime.getSnapshot().config.panels[0].layout.h).toBe(
    initial[0].layout.h + 1,
  );
  fireEvent.click(screen.getByRole('button', { name: '撤销布局' }));
  expect(runtime.getSnapshot().config.panels[0].layout).toEqual(
    initial[0].layout,
  );
  fireEvent.click(screen.getByRole('button', { name: '重做布局' }));
  expect(runtime.getSnapshot().config.panels[0].layout.h).toBe(
    initial[0].layout.h + 1,
  );
  fireEvent.click(screen.getByRole('button', { name: '取消布局编辑' }));
  expect(runtime.getSnapshot().config.panels[0].layout).toEqual(
    initial[0].layout,
  );
  expect(runtime.getSnapshot().panels[initial[0].id].position).toBe(position);
  expect(paged).not.toHaveBeenCalled();
  cleanup();
  engine.dispose();
});

it.each(['drag', 'resize'])(
  'cancels a real library %s when its source is removed without leaving a placeholder or remounting survivors',
  async kind => {
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1200);
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 1200,
      height: 500,
      top: 0,
      left: 0,
      right: 1200,
      bottom: 500,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });
    vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(
      () => document.body,
    );
    const commit = vi.fn();
    const renderBody = vi.fn((panel: DeepReadonly<DashboardPanel>) => (
      <input aria-label={`${panel.id}备注`} defaultValue="keep" />
    ));
    function Demo() {
      const [panels, setPanels] = useState<
        readonly DeepReadonly<DashboardPanel>[]
      >(
        ['a', 'b'].map((id, index) => ({
          id,
          kind: 'view' as const,
          instanceId: id,
          layout: { x: index * 6, y: 0, w: 6, h: 5 },
        })),
      );
      return (
        <>
          <button
            onClick={() =>
              setPanels(items => items.filter(panel => panel.id !== 'a'))
            }
          >
            Remove active source
          </button>
          <DashboardLayout
            panels={panels}
            enabled
            title={id => {
              if (!panels.some(panel => panel.id === id))
                throw new Error('Removed title accessed');
              return id;
            }}
            onCommit={next => {
              commit();
              setPanels(next);
            }}
          >
            {renderBody}
          </DashboardLayout>
        </>
      );
    }
    const view = render(<Demo />);
    const survivor = screen.getByRole('textbox', { name: 'b备注' });
    fireEvent.mouseDown(
      kind === 'drag'
        ? screen.getByRole('button', { name: '移动a' })
        : view.container.querySelector(
            '[data-dashboard-panel="a"] .react-resizable-handle',
          )!,
      {
        button: 0,
        clientX: 20,
        clientY: 20,
      },
    );
    fireEvent.mouseMove(document, { buttons: 1, clientX: 120, clientY: 100 });
    fireEvent.mouseMove(document, { buttons: 1, clientX: 220, clientY: 150 });
    expect(
      view.container.querySelector('.react-grid-placeholder'),
    ).not.toBeNull();
    renderBody.mockClear();
    fireEvent.click(
      screen.getByRole('button', { name: 'Remove active source' }),
    );
    expect(screen.queryByRole('textbox', { name: 'a备注' })).toBeNull();
    expect(renderBody.mock.calls.every(([panel]) => panel.id !== 'a')).toBe(
      true,
    );
    await waitFor(() =>
      expect(
        view.container.querySelector('.react-grid-placeholder'),
      ).toBeNull(),
    );
    expect(
      view.container.querySelector('[data-dashboard-cancel-shell]'),
    ).toBeNull();
    expect(screen.getByRole('textbox', { name: 'b备注' })).toBe(survivor);
    expect(commit).not.toHaveBeenCalled();
    fireEvent.keyDown(screen.getByRole('button', { name: '调整b尺寸' }), {
      key: 'ArrowDown',
    });
    await waitFor(() =>
      expect(
        view.container.querySelector<HTMLElement>('[data-dashboard-panel="b"]')!
          .style.height,
      ).toBe('272px'),
    );
    expect(commit).toHaveBeenCalledOnce();
    expect(screen.getByRole('textbox', { name: 'b备注' })).toBe(survivor);
  },
);
