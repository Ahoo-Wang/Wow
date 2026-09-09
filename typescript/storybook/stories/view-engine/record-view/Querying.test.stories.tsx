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
import { playBusinessRecords } from './persistence.play.js';
import {
  playCursorRecords,
  playEmptyRecords,
  playQueryFailure,
} from './querying.play.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import displayMeta, {
  BusinessRecords as DisplayBusinessRecords,
  CursorRecords as DisplayCursorRecords,
  EmptyRecords as DisplayEmptyRecords,
  QueryFailure as DisplayQueryFailure,
} from './Querying.stories.js';
import type { Story } from './demoTypes.js';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  aggregation,
  filter,
  SortDirection,
  StringComparison,
} from '@ahoo-wang/fetcher-wow';
import { definition, orders, type OrderSnapshot } from './fixtures.js';
import { createOrderSource } from './querySource.js';
import { chooseInstance } from './playHelpers.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/Record View/查询与分页/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

export const BusinessRecords: Story = {
  ...DisplayBusinessRecords,
  tags: ['!dev', '!autodocs', 'test'],
  play: playBusinessRecords,
};

export const CursorRecords: Story = {
  ...DisplayCursorRecords,
  tags: ['!dev', '!autodocs', 'test'],
  play: playCursorRecords,
};

export const EmptyRecords: Story = {
  ...DisplayEmptyRecords,
  tags: ['!dev', '!autodocs', 'test'],
  play: playEmptyRecords,
};

export const QueryFailure: Story = {
  ...DisplayQueryFailure,
  tags: ['!dev', '!autodocs', 'test'],
  play: playQueryFailure,
};

export const SnapshotContract: Story = {
  ...DisplayBusinessRecords,
  name: 'Wow 快照 · 订单数据契约',
  play: async ({ canvasElement }) => {
    await expect(
      definition.fields.find(field => field.label === '下单时间')?.field,
    ).toBe('firstEventTime');
    await expect(
      definition.fields.find(field => field.label === '支付时间')?.field,
    ).toBe('state.paidAt');
    for (const order of orders) {
      await expect(order).toMatchObject({
        aggregateId: expect.stringMatching(/^ORD-/),
        contextName: 'commerce',
        aggregateName: 'order',
        tenantId: expect.any(String),
        ownerId: expect.any(String),
        spaceId: expect.any(String),
        version: expect.any(Number),
        eventId: expect.any(String),
        firstOperator: expect.any(String),
        operator: expect.any(String),
        firstEventTime: expect.any(Number),
        eventTime: expect.any(Number),
        snapshotTime: expect.any(Number),
        tags: expect.any(Object),
        deleted: false,
        state: {
          totalAmount: expect.any(Number),
          paidAmount: expect.any(Number),
        },
      });
      await expect(order).not.toHaveProperty('amount');
      await expect(order).not.toHaveProperty('id');
      await expect(order.state).not.toHaveProperty('createdAt');
      await expect(Number.isSafeInteger(order.firstEventTime)).toBe(true);
      if (order.state.paidAmount === 0)
        await expect(order.state.paidAt).toBeNull();
      else {
        await expect(Number.isSafeInteger(order.state.paidAt)).toBe(true);
        await expect(order.state.paidAt).toBeGreaterThan(order.firstEventTime);
        await expect(order.eventTime).toBeGreaterThanOrEqual(
          order.state.paidAt!,
        );
      }
      await expect(order.state.items.length).toBeGreaterThan(0);
      for (const item of order.state.items)
        await expect(Math.round(item.totalPrice * 100)).toBe(
          Math.round(item.price * 100) * item.quantity,
        );
      await expect(Math.round(order.state.totalAmount * 100)).toBe(
        order.state.items.reduce(
          (sum, item) => sum + Math.round(item.totalPrice * 100),
          0,
        ),
      );
      await expect(order.state.paidAmount).toBeGreaterThanOrEqual(0);
      await expect(order.state.paidAmount).toBeLessThanOrEqual(
        order.state.totalAmount,
      );
    }
    const runtime = createOrderSource(
      {},
      () => {},
      () => {},
    );
    const request = {
      filter: filter.gte('state.totalAmount', 1000),
      sort: [{ field: 'state.totalAmount', direction: SortDirection.ASC }],
    };
    const paged = await runtime.source.paged({
      ...request,
      pagination: { index: 1, size: 2 },
    });
    const cursor = await runtime.source.cursor({ ...request, size: 2 });
    await expect(paged.total).toBe(12);
    await expect(paged.list.map(row => row.aggregateId)).toEqual([
      'ORD-202609-1009',
      'ORD-202609-1002',
    ]);
    await expect(cursor.list).toEqual(paged.list);
    await expect(
      await runtime.source.aggregate({
        filter: filter.eq('aggregateId', 'ORD-202609-1002'),
        metrics: [
          aggregation.sum(
            aggregation.field('state.totalAmount'),
            'totalAmount',
          ),
          aggregation.sum(aggregation.field('state.paidAmount'), 'paidAmount'),
        ],
      }),
    ).toEqual([
      {
        totalAmount: orders[1].state.totalAmount,
        paidAmount: orders[1].state.paidAmount,
      },
    ]);
    const created = runtime.createOrder();
    await expect(created.firstOperator).toBe('storybook-user');
    await expect(created.operator).toBe('storybook-user');
    await expect(created.state.totalAmount).toBe(3200);
    await expect(created.state.paidAmount).toBe(0);
    await expect(created.state.paidAt).toBeNull();
    await expect(created.firstEventTime).toBe(
      Date.parse('2026-09-06T12:30:00+08:00'),
    );
    const nextDayPayments = await runtime.source.paged({
      filter: filter.between(
        'state.paidAt',
        Date.parse('2026-09-07T00:00:00+08:00'),
        Date.parse('2026-09-07T23:59:59.999+08:00'),
      ),
    });
    await expect(nextDayPayments.list.map(row => row.aggregateId)).toEqual([
      'ORD-202609-1003',
      'ORD-202609-1007',
      'ORD-202609-1011',
      'ORD-202609-1015',
    ]);
    for (const [direction, firstId] of [
      [SortDirection.ASC, 'ORD-202609-1002'],
      [SortDirection.DESC, 'ORD-202609-1015'],
    ] as const) {
      const sorted = await runtime.source.paged<OrderSnapshot>({
        filter: filter.matchAll(),
        sort: [{ field: 'state.paidAt', direction }],
        pagination: { index: 1, size: 30 },
      });
      await expect(sorted.list[0].aggregateId).toBe(firstId);
      await expect(
        sorted.list.slice(-10).every(row => row.state.paidAt === null),
      ).toBe(true);
    }
    runtime.processOrders([created.aggregateId]);
    const updated = await runtime.source.paged({
      filter: filter.eq('aggregateId', created.aggregateId),
    });
    await expect(updated.list[0]).toMatchObject({
      aggregateId: created.aggregateId,
      version: created.version + 1,
      firstEventTime: created.firstEventTime,
      state: { ...created.state, status: 'processing' },
    });
    await expect(updated.list[0].eventId).not.toBe(created.eventId);
    await expect(
      runtime.processOrders([
        created.aggregateId,
        orders[2].aggregateId,
        orders[3].aggregateId,
      ]),
    ).toBe(0);
    const unchanged = await runtime.source.paged({
      filter: filter.isIn('aggregateId', [
        orders[2].aggregateId,
        orders[3].aggregateId,
      ]),
    });
    await expect(unchanged.list).toEqual([orders[2], orders[3]]);
    const canvas = within(canvasElement);
    const row = await canvas.findByRole('row', { name: /ORD-202609-1001/ });
    await expect(within(row).getByText('¥0.00')).toBeVisible();
    await expect(row).toHaveTextContent('机械键盘 × 2');
  },
};

export const Operators: Story = {
  ...DisplayBusinessRecords,
  name: '操作人 · 名称展示与 ID 查询',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await canvas.findByRole('row', { name: /ORD-202609-1001/ });
    const row = () => canvas.getByRole('row', { name: /ORD-202609-1001/ });
    const person = (label: string) => {
      const headers = canvas.getAllByRole('columnheader');
      const index = headers.findIndex(header => header.textContent === label);
      expect(index).toBeGreaterThan(0);
      return within(row()).getAllByRole('cell')[index];
    };
    await expect(person('创建人')).toHaveTextContent('林晨');
    await expect(person('最后操作人')).toHaveTextContent('林晨');
    await userEvent.click(within(row()).getByRole('checkbox'));
    await userEvent.click(canvas.getByRole('button', { name: '批量处理' }));
    await waitFor(() =>
      expect(
        canvas.getByRole('status', { name: '订单操作结果' }),
      ).toHaveTextContent('已处理 1 笔订单'),
    );
    await expect(person('创建人')).toHaveTextContent('林晨');
    await expect(person('最后操作人')).toHaveTextContent('周宁');

    await userEvent.click(canvas.getByRole('button', { name: '添加筛选' }));
    const picker = within(
      await page.findByRole('dialog', { name: '选择筛选字段' }),
    );
    for (const label of ['创建人', '最后操作人'])
      await userEvent.click(picker.getByRole('checkbox', { name: label }));
    await userEvent.click(picker.getByRole('button', { name: '完成' }));
    for (const [label, name] of [
      ['创建人', '林晨'],
      ['最后操作人', '周宁'],
    ]) {
      await userEvent.click(canvas.getByRole('combobox', { name: label }));
      await userEvent.click(await page.findByRole('option', { name }));
      await userEvent.keyboard('{Escape}');
    }
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await canvas.findByText('共 1 条记录');
    await expect(row()).toBeInTheDocument();
    await expect(
      JSON.parse(canvas.getByTestId('record-query').textContent!),
    ).toMatchObject({
      filter: {
        operands: expect.arrayContaining([
          { op: 'IN', field: 'firstOperator', values: ['sales-1'] },
          { op: 'IN', field: 'operator', values: ['storybook-user'] },
        ]),
      },
    });
    await userEvent.click(canvas.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(canvas.getByTestId('record-save-count')).toHaveTextContent(/^1$/),
    );
    await expect(
      JSON.parse(canvas.getByTestId('record-write').textContent!),
    ).toMatchObject({
      config: {
        filters: {
          root: {
            operands: expect.arrayContaining([
              expect.objectContaining({
                field: 'firstOperator',
                props: expect.objectContaining({ values: ['sales-1'] }),
              }),
              expect.objectContaining({
                field: 'operator',
                props: expect.objectContaining({ values: ['storybook-user'] }),
              }),
            ]),
          },
        },
      },
    });
    await chooseInstance(canvasElement, '全部订单 系统');
    await canvas.findByText('共 18 条记录');
    await chooseInstance(canvasElement, '我的订单');
    await canvas.findByText('共 1 条记录');
    await expect(
      canvas.getByRole('combobox', { name: '创建人' }),
    ).toHaveTextContent('林晨');
    await expect(
      canvas.getByRole('combobox', { name: '最后操作人' }),
    ).toHaveTextContent('周宁');
  },
};

export const ItemFilters: Story = {
  ...DisplayBusinessRecords,
  name: '商品明细筛选 · 查询与恢复',
  play: async ({ canvasElement }) => {
    const runtime = createOrderSource(
      {},
      () => {},
      () => {},
    );
    // The monitor has quantity 1; quantity 2 belongs to the cable in the same order.
    const crossItem = filter.elementMatch(
      'state.items',
      filter.and([filter.eq('productId', 'MON-01'), filter.gte('quantity', 2)]),
    );
    await expect(
      (await runtime.source.paged({ filter: crossItem })).total,
    ).toBe(0);
    await expect(
      (await runtime.source.cursor({ filter: crossItem })).list,
    ).toEqual([]);
    await expect(
      await runtime.source.aggregate({
        filter: crossItem,
        metrics: [
          aggregation.sum(aggregation.field('state.totalAmount'), 'total'),
        ],
      }),
    ).toEqual([{ total: null }]);
    // A matching line costs 100 yuan; aggregation still uses the whole 1,280-yuan order.
    const itemPrice = filter.elementMatch(
      'state.items',
      filter.and([
        filter.between('price', 45, 55),
        filter.eq('totalPrice', 100),
      ]),
    );
    await expect(
      (await runtime.source.cursor({ filter: itemPrice })).list.map(
        row => row.aggregateId,
      ),
    ).toEqual(['ORD-202609-1002']);
    await expect(
      await runtime.source.aggregate({
        filter: itemPrice,
        metrics: [
          aggregation.sum(aggregation.field('state.totalAmount'), 'total'),
        ],
      }),
    ).toEqual([{ total: 1280 }]);
    for (const [comparison, count] of [
      [StringComparison.CASE_SENSITIVE, 0],
      [StringComparison.CASE_INSENSITIVE, 1],
    ] as const)
      await expect(
        (
          await runtime.source.paged({
            filter: filter.elementMatch(
              'state.items',
              filter.contains('productName', 'hdmi', comparison),
            ),
          })
        ).total,
      ).toBe(count);

    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await canvas.findByRole('row', { name: /ORD-202609-1001/ });
    await userEvent.click(canvas.getByRole('button', { name: '筛选模式' }));
    await userEvent.click(
      await page.findByRole('menuitemradio', { name: '高级' }),
    );
    await userEvent.click(canvas.getByRole('button', { name: '添加筛选' }));
    let picker = within(
      await page.findByRole('dialog', { name: '选择筛选字段' }),
    );
    await userEvent.click(picker.getByRole('checkbox', { name: '商品明细' }));
    await userEvent.click(picker.getByRole('button', { name: '完成' }));
    const items = within(
      canvas.getByRole('group', { name: '商品明细元素条件' }),
    );
    await userEvent.click(
      items.getByRole('button', { name: '商品明细元素内添加筛选' }),
    );
    picker = within(await page.findByRole('dialog', { name: '选择筛选字段' }));
    await expect(
      picker
        .getAllByRole('checkbox')
        .map(field => field.closest('label')!.textContent),
    ).toEqual(['商品编码', '商品名称', '单价', '数量', '小计']);
    for (const name of ['商品编码', '数量'])
      await userEvent.click(picker.getByRole('checkbox', { name }));
    await userEvent.click(picker.getByRole('button', { name: '完成' }));
    await userEvent.type(
      items.getByRole('textbox', { name: '商品编码' }),
      'MON-01{Enter}',
    );
    await userEvent.type(items.getByRole('textbox', { name: '数量值' }), '2');
    await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
      /^1$/,
    );
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await canvas.findByText('共 0 条记录');
    await userEvent.clear(items.getByRole('textbox', { name: '数量值' }));
    await userEvent.type(
      items.getByRole('textbox', { name: '数量值' }),
      '1{Enter}',
    );
    await canvas.findByText('共 1 条记录');
    await expect(
      canvas.getByRole('row', { name: /ORD-202609-1002/ }),
    ).toHaveTextContent('¥1,280.00');
    await expect(
      JSON.parse(canvas.getByTestId('record-query').textContent!),
    ).toMatchObject({
      filter: {
        operands: expect.arrayContaining([
          {
            op: 'ELEMENT_MATCH',
            field: 'state.items',
            predicate: {
              op: 'AND',
              operands: [
                { op: 'IN', field: 'productId', values: ['MON-01'] },
                { op: 'EQ', field: 'quantity', value: 1 },
              ],
            },
          },
        ]),
      },
    });
    await userEvent.click(canvas.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(canvas.getByTestId('record-save-count')).toHaveTextContent(/^1$/),
    );
    await expect(canvas.getByTestId('record-write')).toHaveTextContent(
      '"field": "state.items"',
    );
    await expect(canvas.getByTestId('record-write')).toHaveTextContent(
      '"name": "text-values"',
    );
    await chooseInstance(canvasElement, '全部订单 系统');
    await canvas.findByRole('row', { name: /ORD-202609-1001/ });
    await chooseInstance(canvasElement, '我的订单');
    await canvas.findByRole('row', { name: /ORD-202609-1002/ });
    await expect(
      canvas.getByRole('button', { name: '移除MON-01' }),
    ).toBeVisible();
    await expect(canvas.getByRole('textbox', { name: '数量值' })).toHaveValue(
      '1',
    );
  },
};

export const BuiltinFilters: Story = {
  ...DisplayBusinessRecords,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await canvas.findByRole('row', { name: /ORD-202609-1001/ });
    await userEvent.click(canvas.getByRole('button', { name: '添加筛选' }));
    const picker = within(
      await page.findByRole('dialog', { name: '选择筛选字段' }),
    );
    for (const name of ['订单编号', '客户', '订单状态', '下单时间', '支付时间'])
      await userEvent.click(picker.getByRole('checkbox', { name }));
    await userEvent.click(picker.getByRole('button', { name: '完成' }));

    await userEvent.click(canvas.getByRole('textbox', { name: '订单编号' }));
    await userEvent.paste('ORD-202609-1002,ORD-202609-1014');
    await userEvent.click(canvas.getByRole('combobox', { name: '客户' }));
    await userEvent.click(
      await page.findByRole('option', { name: '晨星零售' }),
    );
    await userEvent.click(canvas.getByRole('combobox', { name: '订单状态' }));
    await userEvent.click(await page.findByRole('option', { name: '处理中' }));
    await userEvent.keyboard('{Escape}');
    for (const label of ['下单时间', '支付时间']) {
      await userEvent.click(
        canvas.getByRole('button', { name: new RegExp(`${label}日期范围`) }),
      );
      const rangePicker = within(
        await page.findByRole('dialog', { name: `${label}日期范围` }),
      );
      await expect(rangePicker.getAllByRole('grid')).toHaveLength(2);
      const today = new Date();
      const monthOffset =
        (2026 - today.getFullYear()) * 12 + 8 - today.getMonth();
      for (let month = 0; month < Math.abs(monthOffset); month++)
        await userEvent.click(
          rangePicker.getByRole('button', {
            name: monthOffset < 0 ? '前往上个月' : '前往下个月',
          }),
        );
      await userEvent.click(
        rangePicker.getByRole('button', { name: /^2026年9月6日 星期日/ }),
      );
      await userEvent.click(
        rangePicker.getByRole('button', { name: /^2026年9月6日 星期日/ }),
      );
      await expect(
        canvas.queryAllByRole('textbox', {
          name: new RegExp(`${label}.*时间`),
        }),
      ).toHaveLength(0);
    }
    await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
      /^1$/,
    );
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await canvas.findByText('共 2 条记录');
    await expect(
      canvas.getByRole('row', { name: /ORD-202609-1002/ }),
    ).toHaveTextContent('¥1,280.00');
    await expect(
      canvas.getByRole('row', { name: /ORD-202609-1014/ }),
    ).toHaveTextContent('¥5,600.00');
    await expect(
      JSON.parse(canvas.getByTestId('record-query').textContent!),
    ).toMatchObject({
      filter: {
        op: 'AND',
        operands: expect.arrayContaining(
          ['firstEventTime', 'state.paidAt'].map(field => ({
            op: 'BETWEEN',
            field,
            lowerBound: Date.parse('2026-09-05T16:00:00Z'),
            upperBound: Date.parse('2026-09-06T15:59:59.999Z'),
          })),
        ),
      },
    });
    await expect(
      canvas.getByRole('region', { name: '已应用筛选' }),
    ).toHaveTextContent('下单时间 介于 2026-09-06 至 2026-09-06');
    await userEvent.click(canvas.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(canvas.getByTestId('record-save-count')).toHaveTextContent(/^1$/),
    );
    for (const name of [
      'text-values',
      'remote-select',
      'multi-select',
      'datetime-range',
    ])
      await expect(canvas.getByTestId('record-write')).toHaveTextContent(
        `"name": "${name}"`,
      );
    await expect(
      JSON.parse(canvas.getByTestId('record-write').textContent!),
    ).toMatchObject({
      config: {
        filters: {
          root: {
            operands: expect.arrayContaining(
              ['firstEventTime', 'state.paidAt'].map(field =>
                expect.objectContaining({
                  field,
                  component: { name: 'datetime-range' },
                  props: {
                    lowerBound: { date: '2026-09-06' },
                    upperBound: { date: '2026-09-06' },
                  },
                }),
              ),
            ),
          },
        },
      },
    });
  },
};
