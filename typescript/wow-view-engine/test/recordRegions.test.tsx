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
import { useState, type ReactNode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { RecordView } from '../src/record/RecordView.js';
import { ViewEngine } from '../src/record/ViewEngine.js';
import { ViewPage, ViewPageContent } from '../src/record/ViewPage.js';
import type {
  RecordPaginationRenderContext,
  RecordTableToolbarRenderContext,
} from '../src/record/recordReactTypes.js';
import type { ViewInstance } from '../src/record/recordModel.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

const engines: ViewEngine[] = [];

afterEach(() => {
  cleanup();
  engines.splice(0).forEach(engine => engine.dispose());
  vi.restoreAllMocks();
});

function StatefulRegion({ children }: { children: ReactNode }) {
  const [count, setCount] = useState(0);
  return (
    <div>
      <button onClick={() => setCount(value => value + 1)}>
        区域状态 {count}
      </button>
      {children}
    </div>
  );
}

it('forwards record regions through ViewPage and composes each default region once', async () => {
  const { host } = setup();
  const tableContexts: RecordTableToolbarRenderContext[] = [];
  const paginationContexts: RecordPaginationRenderContext[] = [];
  const page = render(
    <ViewPage
      scopeKey="regions"
      definitionId="orders"
      host={host}
      renderTableToolbar={context => {
        tableContexts.push(context);
        return (
          <div aria-label="自定义表格工具栏">{context.defaultContent}</div>
        );
      }}
      renderPagination={context => {
        paginationContexts.push(context);
        return <StatefulRegion>{context.defaultContent}</StatefulRegion>;
      }}
    />,
  );

  expect(await screen.findByRole('cell', { name: '42' })).toBeTruthy();
  expect(screen.getByLabelText('自定义表格工具栏')).toBeTruthy();
  expect(screen.getAllByRole('group', { name: '表格工具栏' })).toHaveLength(1);
  expect(screen.getAllByRole('navigation', { name: '记录分页' })).toHaveLength(
    1,
  );
  expect(Object.isFrozen(tableContexts.at(-1)!.definition)).toBe(true);
  expect(Object.isFrozen(tableContexts.at(-1)!.session)).toBe(true);
  expect(Object.isFrozen(paginationContexts.at(-1)!.session)).toBe(true);

  fireEvent.click(screen.getByRole('button', { name: '区域状态 0' }));
  page.rerender(
    <ViewPage
      scopeKey="regions"
      definitionId="orders"
      host={{ ...host }}
      renderTableToolbar={context => (
        <div aria-label="自定义表格工具栏">{context.defaultContent}</div>
      )}
      renderPagination={context => (
        <StatefulRegion>{context.defaultContent}</StatefulRegion>
      )}
    />,
  );
  expect(screen.getByRole('button', { name: '区域状态 1' })).toBeTruthy();
});

it('binds ViewPageContent region operations to their rendered instance', async () => {
  const { host } = setup();
  const other: ViewInstance = {
    ...structuredClone(instance),
    id: 'other',
    title: '其他订单',
  };
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance, other], defaultInstanceId: instance.id },
    host,
  });
  engines.push(engine);
  await engine.load();
  let table!: RecordTableToolbarRenderContext;
  let pagination!: RecordPaginationRenderContext;
  render(
    <ViewPageContent
      engine={engine}
      selectable
      renderTableToolbar={context => {
        if (context.session.instance.id === instance.id) table = context;
        return context.defaultContent;
      }}
      renderPagination={context => {
        if (context.session.instance.id === instance.id) pagination = context;
        return context.defaultContent;
      }}
    />,
  );
  expect(await screen.findByRole('cell', { name: '42' })).toBeTruthy();
  fireEvent.click(screen.getByRole('checkbox', { name: '选择记录 0' }));
  const firstColumn =
    table.session.instance.config.presentation.table.columns[0];

  await act(() => engine.selectInstance(other.id));
  await table.refresh();
  table.clearSelection();
  table.setColumns([{ ...firstColumn, title: '已绑定列' }]);
  await pagination.setPageSize(20);

  expect(engine.getSnapshot().selectedInstanceId).toBe(other.id);
  expect(
    engine.getSnapshot().sessions[other.id].instance.config.pagination.size,
  ).toBe(10);
  expect(engine.getSnapshot().sessions[instance.id].selectedRowKeys).toEqual(
    [],
  );
  expect(
    engine.getSnapshot().sessions[instance.id].instance.config.presentation
      .table.columns[0].title,
  ).toBe('已绑定列');
  expect(
    engine.getSnapshot().sessions[instance.id].instance.config.pagination.size,
  ).toBe(20);
});

it('rechecks current paged state before running stale pagination operations', async () => {
  const { host, paged } = setup();
  paged.mockResolvedValue({ list: [{ id: 0, amount: 42 }], total: 20 });
  let finish!: (value: {
    list: { id: number; amount: number }[];
    total: number;
  }) => void;
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host,
  });
  engines.push(engine);
  await engine.load();
  let current!: RecordPaginationRenderContext;
  render(
    <RecordView
      engine={engine}
      renderPagination={context => {
        current = context;
        return (
          <div>
            <span>{`${context.canPrevious}/${context.canNext}/${context.canChangePageSize}`}</span>
            <button onClick={() => void context.nextPage()}>
              自定义下一页
            </button>
          </div>
        );
      }}
    />,
  );
  expect(current.canNext).toBe(true);
  const first = current;
  paged.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  const next = first.nextPage();
  await waitFor(() => expect(current.canNext).toBe(false));
  expect(current.canChangePageSize).toBe(false);
  await first.nextPage();
  await first.setPageSize(50);
  expect(paged).toHaveBeenCalledTimes(2);
  await act(async () => finish({ list: [{ id: 1, amount: 43 }], total: 20 }));
  await next;
  expect(current.page).toBe(2);
  expect(current.canPrevious).toBe(true);
  expect(current.canNext).toBe(false);
  await first.nextPage();
  expect(paged).toHaveBeenCalledTimes(2);
});

it('shares cursor and failure guards while preserving operation rejection', async () => {
  const cursorInstance: ViewInstance = {
    ...structuredClone(instance),
    config: {
      ...structuredClone(instance.config),
      pagination: { mode: 'cursor', size: 10 },
    },
  };
  const { host } = setup();
  const cursor = vi
    .fn()
    .mockResolvedValueOnce({
      list: [{ id: 0, amount: 42 }],
      nextCursor: 'next',
    })
    .mockRejectedValueOnce(new Error('cursor unavailable'));
  host.resolveSource = () => ({ cursor });
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [cursorInstance], defaultInstanceId: instance.id },
    host,
  });
  engines.push(engine);
  await engine.load();
  let current!: RecordPaginationRenderContext;
  render(
    <RecordView
      engine={engine}
      renderPagination={context => {
        current = context;
        return <div>{context.defaultContent}</div>;
      }}
    />,
  );
  expect(current.mode).toBe('cursor');
  expect(current.canPrevious).toBe(false);
  await current.previousPage();
  await current.setPage(2);
  expect(cursor).toHaveBeenCalledOnce();

  await expect(current.nextPage()).rejects.toThrow('cursor unavailable');
  await waitFor(() => expect(current.canNext).toBe(false));
  expect(current.canChangePageSize).toBe(false);
  expect(screen.queryByRole('navigation', { name: '记录分页' })).toBeNull();
  await current.nextPage();
  await current.setPageSize(20);
  expect(cursor).toHaveBeenCalledTimes(2);
});

it('isolates region rendering failures and preserves stateful sibling regions', async () => {
  const { host } = setup();
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    host,
  });
  engines.push(engine);
  await engine.load();
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  function BrokenChild(): ReactNode {
    throw new Error('broken region');
  }
  const view = render(
    <RecordView
      engine={engine}
      renderTableToolbar={() => <BrokenChild />}
      renderPagination={context => (
        <StatefulRegion>{context.defaultContent}</StatefulRegion>
      )}
    />,
  );
  expect(screen.getByText('表格工具栏渲染失败')).toBeTruthy();
  expect(screen.getByRole('cell', { name: '42' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '区域状态 0' }));
  view.rerender(
    <RecordView
      engine={engine}
      renderTableToolbar={context => context.defaultContent}
      renderPagination={context => (
        <StatefulRegion>{context.defaultContent}</StatefulRegion>
      )}
    />,
  );
  expect(screen.getByRole('group', { name: '表格工具栏' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '区域状态 1' })).toBeTruthy();
  expect(consoleError).toHaveBeenCalled();
});

it('exposes stable slots for record semantic regions', async () => {
  const { host } = setup();
  render(<ViewPage scopeKey="slots" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  const root = screen.getByLabelText('数据视图');
  expect(root.getAttribute('data-slot')).toBe('record-view');
  for (const slot of [
    'record-global-toolbar',
    'record-table-toolbar',
    'record-applied-filters',
    'record-pagination',
  ])
    expect(root.querySelectorAll(`[data-slot="${slot}"]`)).toHaveLength(1);
});

it.each(['renderTableToolbar', 'renderPagination'] as const)(
  'isolates %s local state per instance in standalone RecordView',
  async region => {
    const { host } = setup();
    const other = { ...structuredClone(instance), id: 'other' };
    const engine = new ViewEngine({
      host,
      definitionId: definition.id,
      definition,
      instances: {
        instances: [instance, other],
        defaultInstanceId: instance.id,
      },
    });
    engines.push(engine);
    await engine.load();
    render(
      <RecordView
        engine={engine}
        {...{
          [region]: () => <StatefulRegion>{null}</StatefulRegion>,
        }}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '区域状态 0' }));
    await act(() => engine.refresh());
    expect(screen.getByRole('button', { name: '区域状态 1' })).toBeTruthy();
    await act(() => engine.selectInstance('other'));
    expect(screen.getByRole('button', { name: '区域状态 0' })).toBeTruthy();
  },
);
