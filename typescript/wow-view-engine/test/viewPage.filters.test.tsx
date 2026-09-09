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

import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  compileBuiltinFilter,
  createFilterConfiguration,
  newFilterNode,
} from '../src/filter/filterCore.js';
import type { FilterEditorProps } from '../src/filter/filterReactTypes.js';
import { ViewEngine } from '../src/record/ViewEngine.js';
import { ViewPage, ViewPageContent } from '../src/record/ViewPage.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

afterEach(cleanup);

it('edits record datetime filters in the definition timezone', async () => {
  const { host, paged } = setup();
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      timeZone: 'America/New_York',
      fields: [
        ...definition.fields,
        { field: 'created', label: '创建时间', type: 'datetime' },
      ],
    },
    instances: {
      instances: [
        {
          ...instance,
          config: {
            ...instance.config,
            filters: createFilterConfiguration({
              ...newFilterNode(FilterOperator.GTE, 'created'),
              props: { value: Date.UTC(2026, 0, 15, 15, 30) },
              component: { name: 'builtin', options: { showTime: true } },
            }),
          },
        },
      ],
      defaultInstanceId: instance.id,
    },
    host,
  });
  try {
    await engine.load();
    render(<ViewPageContent engine={engine} />);
    const time = screen.getByRole('textbox', { name: '创建时间时间' });
    expect(time).toHaveProperty('value', '10:30:00');
    fireEvent.change(time, { target: { value: '11:30' } });
    expect(paged).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
    await waitFor(() =>
      expect(paged.mock.lastCall?.[0].filter).toEqual(
        filter.gte('created', Date.UTC(2026, 0, 15, 16, 30)),
      ),
    );
  } finally {
    engine.dispose();
  }
});

it('preserves results until Query and avoids cell rerenders in compiled builds', async () => {
  const { host, paged } = setup();
  const Cell = vi.fn(({ value }: { value: unknown }) => (
    <span>{String(value)}</span>
  ));
  const extendedDefinition = {
    ...definition,
    fields: definition.fields.map(field => ({
      ...field,
      cellRenderer: { name: 'amount' },
    })),
  };
  render(
    <ViewPage
      scopeKey="user-one"
      definitionId="orders"
      host={host}
      definition={extendedDefinition}
      extensions={{ cells: { amount: Cell } }}
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  const calls = Cell.mock.calls.length;
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '500' },
  });
  await screen.findByText('筛选未生效');
  expect(screen.getByRole('cell', { name: '42' })).toBeTruthy();
  // Functional behavior is shared; automatic memoization belongs to the compiled build.
  if (import.meta.env.MODE === 'compiled')
    expect(Cell.mock.calls).toHaveLength(calls);
  expect(paged).toHaveBeenCalledTimes(1);
  paged.mockResolvedValue({ list: [{ id: 0, amount: 500 }], total: 1 });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await screen.findByRole('cell', { name: '500' });
});
it('keeps the applied summary below the editor until a new query is applied', async () => {
  const { host, paged } = setup();
  render(<ViewPage scopeKey="test-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  const summary = screen.getByRole('region', { name: '已应用筛选' });
  expect(summary.textContent).toContain('金额 大于等于 10');
  expect(
    screen
      .getByRole('region', { name: '筛选器' })
      .compareDocumentPosition(summary) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(
    summary.compareDocumentPosition(
      screen.getByRole('group', { name: '表格工具栏' }),
    ) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '20' },
  });
  const toggle = screen.getByRole('button', { name: '收起筛选' });
  expect(summary.textContent).toContain('金额 大于等于 10');
  expect(summary.textContent).not.toContain('金额 大于等于 20');
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(toggle);
  expect(
    screen.getByRole('button', { name: '展开筛选' }).textContent,
  ).toContain('待查询');
  expect(screen.getByRole('region', { name: '已应用筛选' })).toBe(summary);
  fireEvent.click(screen.getByRole('button', { name: '展开筛选' }));
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  await waitFor(() =>
    expect(summary.textContent).toContain('金额 大于等于 20'),
  );
  expect(paged.mock.lastCall?.[0].filter).toEqual(filter.gte('amount', 20));
  fireEvent.click(screen.getByRole('button', { name: '清空条件' }));
  expect(summary.textContent).toContain('金额 大于等于 20');
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  await waitFor(() => expect(summary.textContent).toContain('全部记录'));
});
it('collapses filters without unmounting editors, applying drafts or clearing selection', async () => {
  const { host, paged } = setup();
  render(
    <ViewPage
      scopeKey="test-user"
      definitionId="orders"
      host={host}
      selectable
    />,
  );
  await screen.findByRole('cell', { name: '42' });
  const amount = screen.getByRole('textbox', {
    name: '金额值',
  }) as HTMLInputElement;
  fireEvent.change(amount, { target: { value: '99' } });
  expect(screen.getByText('筛选未生效')).toBeTruthy();
  expect(screen.queryByText('筛选待查询')).toBeNull();
  fireEvent.click(screen.getByRole('checkbox', { name: '选择记录 0' }));
  fireEvent.click(screen.getByRole('button', { name: '收起筛选' }));
  const toggle = screen.getByRole('button', { name: '展开筛选' });
  expect(toggle.getAttribute('aria-expanded')).toBe('false');
  expect(toggle.textContent).toContain('待查询');
  expect(screen.queryByText('筛选未生效')).toBeNull();
  expect(screen.queryByRole('textbox', { name: '金额值' })).toBeNull();
  expect(amount.isConnected).toBe(true);
  expect(amount.value).toBe('99');
  expect(
    screen
      .getByRole('checkbox', { name: '选择记录 0' })
      .getAttribute('aria-checked'),
  ).toBe('true');
  expect(
    (screen.getByRole('button', { name: '保存' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '取消选择' }));
  expect(
    screen
      .getByRole('checkbox', { name: '选择记录 0' })
      .getAttribute('aria-checked'),
  ).toBe('false');
  expect(document.activeElement).toBe(
    screen.getByRole('group', { name: '表格工具栏' }),
  );
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(toggle);
  expect(screen.getByRole('textbox', { name: '金额值' })).toBe(amount);
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  expect(paged.mock.lastCall?.[0].filter).toEqual(filter.gte('amount', 99));
});
it('rejects applying an invalid custom buffer without enabling Save or querying', async () => {
  const { host, paged } = setup();
  function Custom({ onValidityChange }: FilterEditorProps) {
    return (
      <>
        <button onClick={() => onValidityChange(false, '金额尚未完成')}>
          输入不完整金额
        </button>
        <button onClick={() => onValidityChange(true)}>修正金额</button>
      </>
    );
  }
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition: {
      ...definition,
      fields: [{ ...definition.fields[0], editor: { name: 'custom' } }],
    },
    instances: {
      instances: [
        {
          ...instance,
          config: {
            ...instance.config,
            filters: {
              ...instance.config.filters,
              root: {
                ...instance.config.filters.root,
                component: { name: 'custom' },
              },
            },
          },
        },
      ],
      defaultInstanceId: instance.id,
    },
    filterCompilers: { custom: { compile: compileBuiltinFilter } },
    host,
  });
  await engine.load();
  render(
    <ViewPageContent
      engine={engine}
      extensions={{
        filters: {
          custom: {
            component: Custom,
            compile: compileBuiltinFilter,
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  await act(() => engine.setTitle('Updated title'));
  fireEvent.click(screen.getByRole('button', { name: '输入不完整金额' }));
  await act(async () => {
    await expect(engine.applyFilter()).rejects.toThrow(/筛选/);
    await expect(engine.applyFilter(instance.id)).rejects.toThrow(/筛选/);
    await expect(engine.save()).rejects.toThrow(/先查询/);
  });
  expect(engine.getSnapshot().sessions.mine).toMatchObject({
    filterValid: false,
    filterPending: true,
  });
  expect(screen.getByText('金额尚未完成')).toBeTruthy();
  expect(
    (
      screen.getByRole('button', {
        name: '查询',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(
    (
      screen.getByRole('button', {
        name: '保存',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(paged).toHaveBeenCalledTimes(1);
  expect(host.instance!.save).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '修正金额' }));
  await act(() => engine.applyFilter());
  await act(() => engine.save());
  expect(engine.getSnapshot().sessions.mine).toMatchObject({
    filterValid: true,
    filterPending: false,
  });
  expect(paged).toHaveBeenCalledTimes(2);
  expect(host.instance!.save).toHaveBeenCalledTimes(1);
  engine.dispose();
});

it('changes filter mode from the global toolbar and reopens the same pending draft', async () => {
  const { host, paged } = setup();
  render(<ViewPage scopeKey="mode-user" definitionId="orders" host={host} />);
  await screen.findByRole('cell', { name: '42' });
  fireEvent.change(screen.getByRole('textbox', { name: '金额值' }), {
    target: { value: '99' },
  });
  fireEvent.click(screen.getByRole('button', { name: '收起筛选' }));
  fireEvent.click(screen.getByRole('button', { name: '筛选模式' }));
  fireEvent.click(
    await screen.findByRole('menuitemradio', { name: '高级', exact: true }),
  );
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '收起筛选' }).textContent,
    ).toContain('高级'),
  );
  expect(
    (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement).value,
  ).toBe('99');
  expect(paged).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '筛选模式' }));
  fireEvent.click(
    await screen.findByRole('menuitemradio', { name: '简单', exact: true }),
  );
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '收起筛选' }).textContent,
    ).toContain('简单'),
  );
  expect(paged).toHaveBeenCalledTimes(1);
  expect(
    (screen.getByRole('textbox', { name: '金额值' }) as HTMLInputElement).value,
  ).toBe('99');
});
