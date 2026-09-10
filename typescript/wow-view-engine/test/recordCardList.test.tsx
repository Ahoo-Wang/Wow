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

import { afterEach, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import type { CellRendererProps } from '../src/record/recordReactTypes.js';
import { RecordTable } from '../src/record/RecordTable.js';
import { RecordCardList } from '../src/record/RecordCardList.js';
import { props } from './fixtures/recordTable.js';
afterEach(cleanup);
it('renders missing titles as keys and preserves false and zero', () => {
  const base = props();
  const onSelectionChange = vi.fn();
  render(
    <RecordCardList
      {...base}
      instance={{
        ...base.instance,
        config: {
          ...base.instance.config,
          presentation: {
            layout: 'card',
            card: { title: { id: 'title', field: 'name' }, fields: [] },
          },
        },
      }}
      rows={[
        { meta: { id: 'a' }, name: null },
        { meta: { id: 'b' }, name: false },
        { meta: { id: 'c' }, name: 0 },
      ]}
      selectable
      onSelectionChange={onSelectionChange}
    />,
  );
  expect(screen.getByRole('heading', { name: 'a' })).toBeTruthy();
  expect(screen.getByRole('heading', { name: '否' })).toBeTruthy();
  expect(screen.getByRole('heading', { name: '0' })).toBeTruthy();
  fireEvent.click(screen.getByRole('checkbox', { name: '选择本页全部记录' }));
  expect(onSelectionChange).toHaveBeenCalledWith(['a', 'b', 'c']);
});

it('replaces invalid and failed covers without hiding row content', () => {
  const base = props();
  const instance = {
    ...base.instance,
    config: {
      ...base.instance.config,
      presentation: {
        layout: 'card' as const,
        card: {
          title: { id: 'title', field: 'name' },
          cover: { field: 'detail' },
          fields: [],
        },
      },
    },
  };
  const { rerender } = render(
    <RecordCardList
      {...base}
      instance={instance}
      rows={[
        { meta: { id: 'a' }, name: 'Order', detail: 'javascript:alert(1)' },
      ]}
    />,
  );
  expect(screen.getByRole('img', { name: 'Order：暂无封面' })).toBeTruthy();
  rerender(
    <RecordCardList
      {...base}
      instance={instance}
      rows={[{ meta: { id: 'a' }, name: 'Order', detail: '/cover.png' }]}
    />,
  );
  fireEvent.error(screen.getByRole('img', { name: 'Order' }));
  expect(screen.getByRole('img', { name: 'Order：暂无封面' })).toBeTruthy();
  expect(screen.getByRole('heading', { name: 'Order' })).toBeTruthy();
});

it('honors a title renderer for the intrinsic row key without changing field definitions', () => {
  const base = props();
  const definition = { ...base.definition, fields: [] };
  const renderer = vi.fn(({ value }: CellRendererProps) => (
    <strong>Custom {String(value)}</strong>
  ));
  render(
    <RecordCardList
      {...base}
      definition={definition}
      instance={{
        ...base.instance,
        config: {
          ...base.instance.config,
          presentation: {
            layout: 'card',
            card: {
              title: {
                id: 'title',
                field: definition.rowKey,
                renderer: { name: 'custom' },
              },
              fields: [],
            },
          },
        },
      }}
      rows={[{ meta: { id: 'a' } }]}
      extensions={{ cells: { custom: renderer } }}
    />,
  );
  expect(screen.getByRole('heading', { name: 'Custom a' })).toBeTruthy();
  expect(renderer.mock.calls[0][0].field.field).toBe(definition.rowKey);
  expect(definition.fields).toEqual([]);
});

it('lets a custom card own its content while selection stays managed by the list', () => {
  const base = props();
  const onSelectionChange = vi.fn();
  const renderCard = vi.fn(context => (
    <article>自定义 {String(context.record.name)}</article>
  ));
  render(
    <RecordCardList
      {...base}
      instance={{
        ...base.instance,
        config: {
          ...base.instance.config,
          presentation: {
            layout: 'card',
            card: { title: { id: 'title', field: 'name' }, fields: [] },
          },
        },
      }}
      rows={[{ meta: { id: 'a' }, name: '订单A' }]}
      selectable
      onSelectionChange={onSelectionChange}
      renderCard={renderCard}
    />,
  );
  expect(screen.getByText('自定义 订单A')).toBeTruthy();
  expect(screen.queryByRole('heading')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '选择记录 a' }));
  expect(onSelectionChange).toHaveBeenCalledWith(['a']);
  expect(renderCard.mock.calls[0][0].rowKey).toBe('a');
  expect(renderCard.mock.calls[0][0].refresh).toBe(base.refresh);
});

it('isolates custom card failures without removing sibling cards or selection', () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  const base = props();
  try {
    render(
      <RecordCardList
        {...base}
        instance={{
          ...base.instance,
          config: {
            ...base.instance.config,
            presentation: {
              layout: 'card',
              card: { title: { id: 'title', field: 'name' }, fields: [] },
            },
          },
        }}
        rows={[
          { meta: { id: 'a' }, name: 'A' },
          { meta: { id: 'b' }, name: 'B' },
        ]}
        selectable
        renderCard={({ rowKey, defaultContent }) => {
          if (rowKey === 'a') throw new Error('broken');
          return defaultContent;
        }}
      />,
    );
    expect(screen.getByRole('alert').textContent).toBe('卡片内容渲染失败');
    expect(screen.getByRole('heading', { name: 'B' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '选择记录 a' })).toBeTruthy();
  } finally {
    log.mockRestore();
  }
});

it('renders configured summary fields and removes only the toggled selection', () => {
  const base = props();
  const selection = vi.fn();
  render(
    <RecordCardList
      {...base}
      selectable
      selectedRowKeys={['a', 'b']}
      onSelectionChange={selection}
      instance={{
        ...base.instance,
        config: {
          ...base.instance.config,
          presentation: {
            layout: 'card',
            card: {
              title: { id: 'title', field: 'name' },
              fields: [
                { id: 'amount', field: 'amount' },
                { id: 'detail', field: 'detail', title: '说明' },
              ],
            },
          },
        },
      }}
      rows={[
        { meta: { id: 'a' }, name: '商品 A', amount: 42, detail: '现货' },
        { meta: { id: 'b' }, name: '商品 B', amount: 0, detail: '预售' },
      ]}
    />,
  );
  expect(screen.getAllByText('金额')).toHaveLength(2);
  expect(screen.getAllByText('说明')).toHaveLength(2);
  expect(screen.getByText('现货')).toBeTruthy();
  expect(screen.getByText('预售')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '选择记录 a' }));
  expect(selection).toHaveBeenLastCalledWith(['b']);
  fireEvent.click(screen.getByRole('checkbox', { name: '选择本页全部记录' }));
  expect(selection).toHaveBeenLastCalledWith([]);
});
it('renders loading, stale-result failure and retry independently from empty results', () => {
  const base = props();
  const card = {
    ...base.instance,
    config: {
      ...base.instance.config,
      presentation: {
        layout: 'card' as const,
        card: { title: { id: 'title', field: 'name' }, fields: [] },
      },
    },
  };
  const retry = vi.fn();
  const view = render(
    <RecordCardList {...base} instance={card} rows={[]} querying />,
  );
  expect(screen.getByText('正在查询').getAttribute('role')).toBe('status');
  expect(screen.queryByText('暂无数据')).toBeNull();
  view.rerender(
    <RecordCardList
      {...base}
      instance={card}
      rows={[{ meta: { id: 'a' }, name: '缓存结果' }]}
      queryError="网络中断"
      onQueryRetry={retry}
    />,
  );
  expect(screen.getByRole('alert').textContent).toContain('显示上次查询结果');
  expect(screen.getByRole('heading', { name: '缓存结果' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '重试查询' }));
  expect(retry).toHaveBeenCalledOnce();
  view.rerender(<RecordCardList {...base} instance={card} rows={[]} />);
  expect(screen.getByRole('status').textContent).toBe('暂无数据');
});
it('rejects mismatched layouts at both standalone renderer boundaries', () => {
  const base = props();
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    expect(() => render(<RecordCardList {...base} />)).toThrow(
      '需要 card 布局',
    );
    expect(() =>
      render(
        <RecordTable
          {...base}
          instance={{
            ...base.instance,
            config: {
              ...base.instance.config,
              presentation: {
                layout: 'card',
                card: { title: { id: 'title', field: 'name' }, fields: [] },
              },
            },
          }}
        />,
      ),
    ).toThrow('需要 table 布局');
  } finally {
    log.mockRestore();
  }
});

it.each(['title', 'field', 'actions'] as const)(
  'retains a failed %s renderer across unrelated selection updates',
  kind => {
    const base = props();
    const record = { meta: { id: 'a' }, name: 'A', amount: 42 };
    const broken = vi.fn(() => {
      throw new Error('broken renderer');
    });
    const extensions = { cells: { broken }, rowActions: { broken } };
    const card = {
      title: {
        id: 'title',
        field: 'name',
        ...(kind === 'title' ? { renderer: { name: 'broken' } } : {}),
      },
      fields:
        kind === 'field'
          ? [{ id: 'amount', field: 'amount', renderer: { name: 'broken' } }]
          : [],
      ...(kind === 'actions'
        ? { actions: { renderer: { name: 'broken' } } }
        : {}),
    };
    const instance = {
      ...base.instance,
      config: {
        ...base.instance.config,
        presentation: { layout: 'card' as const, card },
      },
    };
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const view = render(
        <RecordCardList
          {...base}
          instance={instance}
          rows={[record]}
          extensions={extensions}
          selectable
        />,
      );
      expect(screen.getByRole('alert').textContent).toContain('渲染失败');
      const calls = broken.mock.calls.length;
      view.rerender(
        <RecordCardList
          {...base}
          instance={instance}
          rows={[record]}
          extensions={extensions}
          selectable
          selectedRowKeys={['a']}
        />,
      );
      expect(broken).toHaveBeenCalledTimes(calls);
      view.rerender(
        <RecordCardList
          {...base}
          instance={instance}
          rows={[{ ...record, name: 'updated' }]}
          extensions={extensions}
          selectable
        />,
      );
      expect(broken.mock.calls.length).toBeGreaterThan(calls);
    } finally {
      log.mockRestore();
    }
  },
);
