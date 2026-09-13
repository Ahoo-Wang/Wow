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

import { StrictMode } from 'react';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { EmbeddedView } from '../src/view/EmbeddedView.js';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import type { ViewInstance, ViewSource } from '../src/contracts/viewModel.js';
import { definition, instance } from './engine/fixtures.js';
import { globalFilter } from './dashboard/runtimeFixtures.js';
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
afterEach(cleanup);
function setup(limits?: { maxConcurrentQueries: number }) {
  const record = instance('records');
  record.config.filters = globalFilter().filters;
  const analysis: ViewInstance = {
    ...instance('analysis'),
    kind: 'analysis',
    config: {
      filters: globalFilter().filters,
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
      presentation: { layout: 'table', columns: [] },
    },
  };
  const dashboard: ViewInstance = {
    ...instance('dashboard'),
    kind: 'dashboard',
    config: {
      schemaVersion: 1,
      panels: [
        {
          id: 'a',
          kind: 'view',
          instanceId: 'records',
          layout: { x: 0, y: 0, w: 12, h: 8 },
        },
      ],
      filters: [],
    },
  };
  const root = {
    ...definition,
    dashboard: true,
    analysis: { count: true, fields: [] },
  };
  const paged = vi.fn<NonNullable<ViewSource['paged']>>().mockResolvedValue({
    total: 25,
    list: [{ state: { id: 'SO-1', amount: 10 } }],
  });
  const aggregate = vi
    .fn<NonNullable<ViewSource['aggregate']>>()
    .mockResolvedValue([{ n: 1 }]);
  const save = vi.fn();
  const engine = new ViewEngine({
    limits,
    definitionId: root.id,
    definition: root,
    instances: {
      instances: [record, analysis, dashboard],
      defaultInstanceId: null,
    },
    host: {
      resolveSource: () => ({ paged, aggregate }),
      definition: { load: async () => root },
      instance: { load: async () => record, save },
      permission: {
        getInstance: () => ({
          save: true,
          saveAsPersonal: true,
          saveAsShared: true,
        }),
        getDefinition: () => ({ createPersonal: true, createShared: true }),
      },
    },
  });
  return { engine, paged, aggregate, save };
}
it.each(['records', 'analysis', 'dashboard'])(
  'embeds %s from its saved baseline without managing the engine',
  async instanceId => {
    const { engine, paged, aggregate, save } = setup();
    await engine.load();
    const baseline = engine.getSnapshot().sessions[instanceId];
    const open = vi.fn();
    const view = render(
      <EmbeddedView
        engine={engine}
        instanceId={instanceId}
        onOpenView={open}
      />,
    );
    await waitFor(() =>
      expect(
        instanceId === 'analysis' ? aggregate : paged,
      ).toHaveBeenCalledTimes(1),
    );
    await screen.findByRole('region', { name: `嵌入视图：${instanceId}` });
    for (const name of ['保存', '新建仪表盘', '管理视图', '编辑布局', '添加'])
      expect(screen.queryByRole('button', { name, exact: true })).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    fireEvent.click(
      screen.getByRole('button', { name: `打开完整视图：${instanceId}` }),
    );
    expect(open).toHaveBeenCalledWith({ instanceId, definitionId: 'orders' });
    expect(engine.getSnapshot().selectedInstanceId).toBeNull();
    expect(engine.getSnapshot().sessions[instanceId]).toBe(baseline);
    expect(
      Object.values(engine.getSnapshot().sessions).every(
        session => !session.dirty,
      ),
    ).toBe(true);
    expect(save).not.toHaveBeenCalled();
    view.unmount();
    expect(Object.keys(engine.getSnapshot().sessions)).toHaveLength(3);
    expect(engine.getSnapshot().status).toBe('ready');
    engine.dispose();
  },
);
it('keeps two embeds independent through paging and unmount', async () => {
  const { engine, paged } = setup();
  await engine.load();
  const view = render(
    <>
      <EmbeddedView
        key="a"
        engine={engine}
        instanceId="records"
        title="订单 A"
      />
      <EmbeddedView
        key="b"
        engine={engine}
        instanceId="records"
        title="订单 B"
      />
    </>,
  );
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const a = screen.getByRole('region', { name: '嵌入视图：订单 A' });
  const b = screen.getByRole('region', { name: '嵌入视图：订单 B' });
  expect(
    within(a).getByRole('navigation', { name: '订单 A记录分页' }),
  ).toBeTruthy();
  expect(
    within(b).getByRole('navigation', { name: '订单 B记录分页' }),
  ).toBeTruthy();
  fireEvent.click(within(a).getByRole('button', { name: '下一页' }));
  await within(a).findByText('第 2 / 3 页');
  expect(within(b).getByText('第 1 / 3 页')).toBeTruthy();
  view.rerender(
    <>
      <EmbeddedView
        key="b"
        engine={engine}
        instanceId="records"
        title="订单 B"
      />
    </>,
  );
  expect(
    Object.keys(engine.getSnapshot().sessions).filter(id =>
      id.startsWith('position:'),
    ),
  ).toHaveLength(1);
  expect(paged).toHaveBeenCalledTimes(3);
  view.unmount();
  engine.dispose();
});
it('cleans StrictMode positions and clears old content when its target changes', async () => {
  const { engine, paged, aggregate } = setup();
  await engine.load();
  const view = render(
    <StrictMode>
      <EmbeddedView engine={engine} instanceId="records" />
    </StrictMode>,
  );
  await waitFor(() => expect(paged).toHaveBeenCalled());
  expect(
    Object.keys(engine.getSnapshot().sessions).filter(id =>
      id.startsWith('position:'),
    ),
  ).toHaveLength(1);
  view.rerender(
    <StrictMode>
      <EmbeddedView engine={engine} instanceId="analysis" />
    </StrictMode>,
  );
  expect(
    screen.queryByRole('region', { name: '嵌入视图：records' }),
  ).toBeNull();
  await waitFor(() => expect(aggregate).toHaveBeenCalled());
  expect(
    Object.keys(engine.getSnapshot().sessions).filter(id =>
      id.startsWith('position:'),
    ),
  ).toHaveLength(1);
  view.unmount();
  expect(Object.keys(engine.getSnapshot().sessions)).toHaveLength(3);
  engine.dispose();
});
it.each(['records', 'analysis'])(
  'keeps %s filter drafts local until query and never writes the saved session',
  async instanceId => {
    const { engine, paged, aggregate, save } = setup();
    await engine.load();
    const source = instanceId === 'analysis' ? aggregate : paged;
    render(<EmbeddedView engine={engine} instanceId={instanceId} />);
    await waitFor(() => expect(source).toHaveBeenCalledTimes(1));
    const baseline = engine.getSnapshot().sessions[instanceId];
    fireEvent.click(screen.getByRole('button', { name: '筛选', exact: true }));
    const input = screen.getByRole('textbox', { name: 'Amount值' });
    fireEvent.change(input, { target: { value: '20' } });
    fireEvent.click(screen.getByRole('button', { name: '刷新', exact: true }));
    await waitFor(() => expect(source).toHaveBeenCalledTimes(2));
    expect(
      JSON.stringify((source.mock.calls[1][0] as { filter: unknown }).filter),
    ).toContain('"value":10');
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
    await waitFor(() => expect(source).toHaveBeenCalledTimes(3));
    expect(
      JSON.stringify((source.mock.calls[2][0] as { filter: unknown }).filter),
    ).toContain('"value":20');
    expect(engine.getSnapshot().sessions[instanceId]).toBe(baseline);
    expect(
      Object.values(engine.getSnapshot().sessions).every(
        session => !session.dirty,
      ),
    ).toBe(true);
    expect(save).not.toHaveBeenCalled();
    cleanup();
    engine.dispose();
  },
);
it('shows unavailable targets and asynchronous full-view navigation errors locally', async () => {
  const { engine, paged } = setup();
  await engine.load();
  const view = render(<EmbeddedView engine={engine} instanceId="missing" />);
  expect(screen.getByRole('alert').textContent).toContain('不可访问');
  view.rerender(
    <EmbeddedView
      engine={engine}
      instanceId="records"
      onOpenView={async () => {
        throw new Error('navigation failed');
      }}
    />,
  );
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
  fireEvent.click(
    screen.getByRole('button', { name: '打开完整视图：records' }),
  );
  await screen.findByText('navigation failed');
  cleanup();
  engine.dispose();
});

it('recovers position allocation failures and renders missing bindings without allocating', async () => {
  const { engine, paged } = setup();
  await engine.load();
  const view = render(<EmbeddedView engine={null} instanceId="records" />);
  expect(screen.getByRole('status').textContent).toContain('正在加载');
  view.rerender(
    <EmbeddedView engine={null} instanceId="records" error="binding failed" />,
  );
  expect(screen.getByRole('alert').textContent).toBe('binding failed');
  vi.spyOn(engine, 'openPosition').mockImplementationOnce(() => {
    throw new Error('position budget');
  });
  view.rerender(
    <EmbeddedView
      engine={engine}
      instanceId="records"
      onOpenView={() => {
        throw new Error('route failed');
      }}
    />,
  );
  await screen.findByText('position budget');
  fireEvent.click(screen.getByRole('button', { name: '重试嵌入' }));
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
  fireEvent.click(
    screen.getByRole('button', { name: '打开完整视图：records' }),
  );
  await screen.findByText('route failed');
  view.unmount();
  engine.dispose();
});
it('cancels a pending read on target replacement and ignores its late rows', async () => {
  const { engine, paged, aggregate } = setup();
  await engine.load();
  let complete: (result: {
    total: number;
    list: { state: { id: string; amount: number } }[];
  }) => void = () => {};
  paged.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        complete = resolve;
      }),
  );
  const view = render(<EmbeddedView engine={engine} instanceId="records" />);
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
  const signal = paged.mock.calls[0][2]!.signal;
  view.rerender(<EmbeddedView engine={engine} instanceId="analysis" />);
  await waitFor(() => expect(aggregate).toHaveBeenCalledTimes(1));
  expect(signal.aborted).toBe(true);
  complete({ total: 1, list: [{ state: { id: 'late record', amount: 99 } }] });
  await waitFor(() =>
    expect(
      screen.getByRole('region', { name: '嵌入视图：analysis' }),
    ).toBeTruthy(),
  );
  expect(screen.queryByText('late record')).toBeNull();
  view.unmount();
  engine.dispose();
});
it('retries a failed engine load through the existing engine lifecycle', async () => {
  const load = vi
    .fn()
    .mockRejectedValueOnce(new Error('definition offline'))
    .mockResolvedValue(definition);
  const engine = new ViewEngine({
    definitionId: 'orders',
    instances: { instances: [instance('records')], defaultInstanceId: null },
    host: {
      definition: { load },
      resolveSource: () => ({ paged: async () => ({ total: 0, list: [] }) }),
    },
  });
  await expect(engine.load()).rejects.toThrow('definition offline');
  render(<EmbeddedView engine={engine} instanceId="records" />);
  expect(screen.getByRole('alert').textContent).toContain('definition offline');
  fireEvent.click(screen.getByRole('button', { name: '重试加载' }));
  await screen.findByRole('region', { name: '嵌入视图：records' });
  cleanup();
  engine.dispose();
});

it('queues concurrent embeddings and starts the next read when capacity is released', async () => {
  const { engine, aggregate } = setup({ maxConcurrentQueries: 1 });
  await engine.load();
  let complete: (rows: { n: number }[]) => void = () => {};
  aggregate.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        complete = resolve;
      }),
  );
  const view = render(
    <>
      <EmbeddedView engine={engine} instanceId="analysis" />
      <EmbeddedView engine={engine} instanceId="analysis" />
    </>,
  );
  await waitFor(() =>
    expect(
      Object.entries(engine.getSnapshot().sessions).some(
        ([id, s]) =>
          id.startsWith('position:') &&
          s.kind === 'analysis' &&
          s.queryStatus === 'waiting',
      ),
    ).toBe(true),
  );
  expect(aggregate).toHaveBeenCalledTimes(1);
  complete([{ n: 1 }]);
  await waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
  await waitFor(() =>
    expect(
      Object.entries(engine.getSnapshot().sessions)
        .filter(([id]) => id.startsWith('position:'))
        .every(([, s]) => s.kind === 'analysis' && s.queryStatus === 'success'),
    ).toBe(true),
  );
  view.unmount();
  engine.dispose();
});
it('retries an initial failed analysis from the visible refresh action', async () => {
  const { engine, aggregate } = setup();
  await engine.load();
  aggregate.mockRejectedValueOnce(new Error('temporary source failure'));
  render(<EmbeddedView engine={engine} instanceId="analysis" />);
  await screen.findByText('分析查询失败，请重试');
  fireEvent.click(screen.getByRole('button', { name: '刷新', exact: true }));
  await waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
  await waitFor(() =>
    expect(screen.queryByText('分析查询失败，请重试')).toBeNull(),
  );
  cleanup();
  engine.dispose();
});
