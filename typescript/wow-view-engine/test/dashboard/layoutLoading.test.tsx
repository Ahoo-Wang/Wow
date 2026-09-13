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
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewPageContent } from '../../src/view/ViewPageContent.js';
import { dashboardSetup } from './runtimeFixtures.js';
vi.hoisted(() =>
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  ),
);
afterEach(() => {
  cleanup();
  vi.doUnmock('../../src/dashboard/DashboardGrid.js');
});
it('keeps panels, navigation and drafts when the layout import fails and retries without querying', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.doMock('../../src/dashboard/DashboardGrid.js', () => {
    throw new Error('chunk unavailable');
  });
  const { engine, paged } = dashboardSetup();
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  runtime.edit(config => ({
    ...config,
    panels: config.panels.map(panel => ({
      ...panel,
      layout: { ...panel.layout, h: 20 },
    })),
  }));
  const config = runtime.getSnapshot().config;
  const position = runtime.getSnapshot().panels.a.position;
  render(<ViewPageContent engine={engine} />);
  expect(await screen.findByText(/已切换为简洁布局/)).toBeTruthy();
  expect(screen.getByRole('main', { name: '视图工作区' })).toBeTruthy();
  expect(screen.getAllByRole('cell', { name: '10', exact: true })).toHaveLength(
    2,
  );
  expect(
    (
      screen.getByRole('button', {
        name: '保存',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(false);
  expect(
    (screen.getByRole('button', { name: '编辑布局' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  screen.getByRole('button', { name: '重试布局' }).focus();
  fireEvent.click(screen.getByRole('button', { name: '重试布局' }));
  await screen.findByText(/已切换为简洁布局/);
  expect(screen.getAllByRole('cell', { name: '10', exact: true })).toHaveLength(
    2,
  );
  vi.doUnmock('../../src/dashboard/DashboardGrid.js');
  fireEvent.click(screen.getByRole('button', { name: '重试布局' }));
  await waitFor(() =>
    expect(document.querySelector('[data-dashboard-grid]')).not.toBeNull(),
  );
  expect(screen.queryByText(/已切换为简洁布局/)).toBeNull();
  expect(
    document.activeElement?.getAttribute('data-dashboard-layout-region'),
  ).toBe('');
  expect(runtime.getSnapshot().config).toBe(config);
  expect(runtime.getSnapshot().session.dirty).toBe(true);
  expect(runtime.getSnapshot().panels.a.position).toBe(position);
  expect(paged).toHaveBeenCalledTimes(2);
  cleanup();
  engine.dispose();
});

it('clears a failed-layout flag when panels disappear and a healthy layout mounts again', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.doMock('../../src/dashboard/DashboardGrid.js', () => {
    throw new Error('chunk unavailable');
  });
  const config = {
    schemaVersion: 1 as const,
    panels: [
      {
        kind: 'markdown' as const,
        id: 'note',
        title: 'Note',
        content: 'Saved note',
        layout: { x: 0, y: 0, w: 6, h: 8 },
      },
    ],
    filters: [],
  };
  const { engine } = dashboardSetup(config);
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  render(<ViewPageContent engine={engine} />);
  await screen.findByText(/已切换为简洁布局/);
  vi.doUnmock('../../src/dashboard/DashboardGrid.js');
  await act(async () => runtime.edit(value => ({ ...value, panels: [] })));
  await act(async () => runtime.edit(() => config));
  await waitFor(() =>
    expect(document.querySelector('[data-dashboard-grid]')).not.toBeNull(),
  );
  expect(
    (screen.getByRole('button', { name: '编辑布局' }) as HTMLButtonElement)
      .disabled,
  ).toBe(false);
  cleanup();
  engine.dispose();
});
