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
import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import {
  DashboardPanelBoundary,
  DashboardPanelContent,
} from '../../src/dashboard/DashboardPanelContent.js';
import { dashboardSetup } from './runtimeFixtures.js';
import type { ComponentProps } from 'react';
import type { AnalysisResult } from '../../src/analysis/AnalysisResultView.js';
import { definition, instance } from '../engine/fixtures.js';
vi.mock('../../src/analysis/AnalysisResultView.js', () => ({
  AnalysisResult: (props: ComponentProps<typeof AnalysisResult>) => (
    <div>
      <span>{props.mode}</span>
      {(props.localError || props.session.queryError) && (
        <p role="alert">{props.localError ?? props.session.queryError}</p>
      )}
      <button onClick={props.onRun}>Run analysis</button>
      <button onClick={() => props.onSortChange([])}>Sort analysis</button>
      <button onClick={() => props.onModeChange('analysis')}>Chart mode</button>
    </div>
  ),
}));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it('contains render failure and retries without affecting a sibling', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  let fail = true;
  function Content() {
    if (fail) throw new Error('render');
    return <p>Recovered</p>;
  }
  render(
    <>
      <DashboardPanelBoundary panelId="bad">
        <Content />
      </DashboardPanelBoundary>
      <button>Sibling</button>
    </>,
  );
  expect(screen.getByRole('button', { name: 'Sibling' })).toBeTruthy();
  fail = false;
  fireEvent.click(screen.getByRole('button', { name: '重试显示面板' }));
  expect(screen.getByText('Recovered')).toBeTruthy();
});
it('surfaces async refresh/reload failures and synchronous source navigation errors locally', async () => {
  const { engine, paged } = dashboardSetup();
  await engine.load();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const panel = engine.dashboard('dashboard').getSnapshot().panels.a;
  const refresh = vi.fn().mockRejectedValue(new Error('refresh offline'));
  const reload = vi.fn().mockRejectedValue('unavailable');
  const original = () => {
    throw new Error('navigation offline');
  };
  render(
    <DashboardPanelContent
      panel={panel}
      compilers={{}}
      onRefresh={refresh}
      onReload={reload}
      onOpenOriginal={original}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '刷新child' }));
  expect(await screen.findByText('refresh offline')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '重新加载引用' }));
  expect(await screen.findByText('操作失败')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'child面板选项' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '编辑原视图' }));
  expect(await screen.findByText('navigation offline')).toBeTruthy();
  engine.dispose();
});
it('connects analysis inspection, explicit run and failed sort to existing position commands', async () => {
  const { engine } = dashboardSetup();
  await engine.load();
  const aggregate = vi.fn().mockResolvedValue([{ n: 1 }]);
  const saved = {
    ...instance('count'),
    kind: 'analysis' as const,
    config: {
      filters: instance().config.filters,
      dimensions: [],
      metrics: [
        {
          id: 'n',
          alias: 'n',
          title: 'Count',
          component: { name: 'count' },
          props: {},
        },
      ],
      sort: [],
      limit: 100,
      presentation: { layout: 'table' as const, columns: [] },
    },
  };
  const target = { ...definition, analysis: { count: true, fields: [] } };
  const position = engine.openPosition(saved, target, {
    source: { aggregate },
  });
  if (position.kind !== 'analysis') throw new Error('analysis');
  await position.commands.run();
  const refresh = vi.fn().mockResolvedValue(undefined);
  render(
    <DashboardPanelContent
      panel={{
        panelId: 'count',
        status: 'ready',
        loading: false,
        blocked: false,
        error: null,
        instance: saved,
        definition: target,
        position,
        scopeVersion: 1,
        referenceVersion: 1,
      }}
      compilers={{}}
      onRefresh={refresh}
      onReload={() => {}}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: /面板选项$/ }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '数据详情' }));
  expect(await screen.findByRole('region', { name: '执行口径' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '关闭' }));
  fireEvent.click(screen.getByRole('button', { name: 'Chart mode' }));
  expect(screen.getByText('analysis')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Run analysis' }));
  await waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
  aggregate.mockRejectedValueOnce(new Error('sort offline'));
  fireEvent.click(screen.getByRole('button', { name: 'Sort analysis' }));
  expect(await screen.findByText('sort offline')).toBeTruthy();
  engine.dispose();
});
