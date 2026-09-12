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

import { filter } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { ViewPage } from './fixtures/OwnedViewPage.js';
import { ViewPageContent } from '../src/view/ViewPage.js';
import type { GlobalActionsRendererProps } from '../src/record/recordReactTypes.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('keeps unresolved component scopes null in global and table actions without querying all records', async () => {
  const { host, paged } = setup();
  const global = vi.fn((props: GlobalActionsRendererProps) => (
    <button>{props.filter === null ? '全局等待条件' : '全局可查询'}</button>
  ));
  const table = vi.fn((props: GlobalActionsRendererProps) => (
    <button>{props.filter === null ? '表格等待条件' : '表格可查询'}</button>
  ));
  render(
    <ViewPage
      scopeKey="unknown-component"
      definitionId="orders"
      host={host}
      definition={{
        ...definition,
        record: {
          ...definition.record,
          recordActions: {
            global: { name: 'global' },
            toolbar: { name: 'table' },
          },
        },
      }}
      instances={{
        instances: [
          {
            ...instance,
            config: {
              ...instance.config,
              filters: {
                ...instance.config.filters,
                root: {
                  ...instance.config.filters.root,
                  component: { name: 'missing' },
                },
              },
            },
          },
        ],
        defaultInstanceId: instance.id,
      }}
      extensions={{ globalActions: { global }, toolbarActions: { table } }}
    />,
  );
  await screen.findByRole('button', { name: '全局等待条件' });
  expect(screen.getByRole('button', { name: '表格等待条件' })).toBeTruthy();
  expect(
    screen.getByRole('region', { name: '已应用筛选' }).textContent,
  ).toContain('筛选尚未生效');
  expect(screen.getAllByText(/未注册.*missing/).length).toBeGreaterThan(0);
  expect(paged).not.toHaveBeenCalled();
});

it('business refresh stays bound to its instance after navigation', async () => {
  const { host, paged } = setup();
  let context: GlobalActionsRendererProps | undefined;
  function Actions(props: GlobalActionsRendererProps) {
    context = props;
    return <button>业务操作</button>;
  }
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      record: {
        ...definition.record,
        recordActions: { global: { name: 'actions' } },
      },
    },
    host,
  });
  await engine.load();
  render(
    <ViewPageContent
      engine={engine}
      extensions={{ globalActions: { actions: Actions } }}
    />,
  );
  const previous = context!;
  await act(() => engine.selectInstance('system'));
  await act(() => previous.refresh());
  expect(engine.getSnapshot().selectedInstanceId).toBe('system');
  expect(paged).toHaveBeenCalledTimes(3);
  engine.dispose();
});
it('recovers batch actions on selection changes without retrying on unrelated draft edits', async () => {
  const { host, paged } = setup();
  paged.mockResolvedValue({
    list: [
      { id: 0, amount: 42 },
      { id: 1, amount: 84 },
    ],
    total: 2,
  });
  vi.spyOn(console, 'error').mockImplementation(() => {});
  let attempts = 0;
  function Batch({ selectedRowKeys }: GlobalActionsRendererProps) {
    attempts++;
    if (selectedRowKeys.includes(0))
      throw new Error('record 0 cannot be rendered');
    return <button>{`可处理 ${selectedRowKeys.join(',')}`}</button>;
  }
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      record: {
        ...definition.record,
        recordActions: { toolbar: { name: 'batch' } },
      },
    },
    host,
  });
  await engine.load();
  render(
    <ViewPageContent
      engine={engine}
      record={{ selectable: true }}
      extensions={{ toolbarActions: { batch: Batch } }}
    />,
  );
  await act(() =>
    engine.record(engine.getSnapshot().selectedInstanceId!).setSelection([0]),
  );
  expect(screen.getByText('工具栏操作渲染失败')).toBeTruthy();
  const failedAttempts = attempts;
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '99' },
  });
  expect(attempts).toBe(failedAttempts);
  await act(() =>
    engine.record(engine.getSnapshot().selectedInstanceId!).setSelection([1]),
  );
  expect(screen.getByRole('button', { name: '可处理 1' })).toBeTruthy();
  expect(screen.queryByText('工具栏操作渲染失败')).toBeNull();
  engine.dispose();
});
it('recovers global actions when a pending query finishes', async () => {
  const { host, paged } = setup();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  function Actions({ querying }: GlobalActionsRendererProps) {
    if (querying) throw new Error('query input is not ready');
    return <button>可用操作</button>;
  }
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      record: {
        ...definition.record,
        recordActions: { global: { name: 'actions' } },
      },
    },
    host,
  });
  await engine.load();
  render(
    <ViewPageContent
      engine={engine}
      extensions={{ globalActions: { actions: Actions } }}
    />,
  );
  let finish!: (result: {
    list: { id: number; amount: number }[];
    total: number;
  }) => void;
  paged.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  let request!: Promise<void>;
  act(() => {
    request = engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  });
  await screen.findByText('全局操作渲染失败');
  await act(async () => {
    finish({ list: [{ id: 0, amount: 42 }], total: 1 });
    await request;
  });
  expect(screen.getByRole('button', { name: '可用操作' })).toBeTruthy();
  expect(screen.queryByText('全局操作渲染失败')).toBeNull();
  engine.dispose();
});
it('separates global and table actions while sharing the applied query context', async () => {
  const { host, paged } = setup();
  let tableContext: GlobalActionsRendererProps | undefined;
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      record: {
        ...definition.record,
        recordActions: {
          global: { name: 'create' },
          toolbar: { name: 'batch' },
        },
      },
    },
    host,
  });
  await engine.load();
  render(
    <ViewPageContent
      engine={engine}
      record={{ selectable: true }}
      extensions={{
        globalActions: { create: () => <button>新建记录</button> },
        toolbarActions: {
          batch: props => {
            tableContext = props;
            return <button>批量处理</button>;
          },
        },
      }}
    />,
  );
  const global = within(screen.getByRole('group', { name: '全局工具栏' }));
  const table = within(screen.getByRole('group', { name: '记录工具栏' }));
  expect(global.getByRole('heading', { name: '订单管理' })).toBeTruthy();
  expect(global.getByRole('button', { name: '新建记录' })).toBeTruthy();
  expect(global.queryByRole('button', { name: '批量处理' })).toBeNull();
  expect(table.getByRole('button', { name: '批量处理' })).toBeTruthy();
  expect(table.getByRole('button', { name: '列设置' })).toBeTruthy();
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '99' },
  });
  fireEvent.click(screen.getByRole('checkbox', { name: '选择记录 0' }));
  expect(tableContext?.selectedRowKeys).toEqual([0]);
  expect(tableContext?.filter).toEqual(filter.gte('amount', 10));
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});
