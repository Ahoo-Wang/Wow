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
import { expect, waitFor, within } from 'storybook/test';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
} from '@ahoo-wang/fetcher-view-engine';
import type { Story } from './demoTypes.js';
import { Scenario } from './Scenario.js';
import { recordViewMeta } from './meta.js';

const meta = {
  ...recordViewMeta,
  parameters: {
    ...recordViewMeta.parameters,
    docs: {
      ...recordViewMeta.parameters.docs,
      description: {
        component:
          '筛选输入只修改草稿，Enter/查询才更新记录。已应用区域的清空按钮将控件值恢复为未设置并立即查询，不删除控件。\n\n普通分页返回 list/total；游标模式返回 list/nextCursor，只向后翻页。每条记录采用 Wow MaterializedSnapshot<OrderState>：aggregateId 为行标识，业务字段使用 state.customer、state.totalAmount、state.paidAmount 等路径，state.items 保留商品、单价、数量和小计。下单时间使用快照 firstEventTime，支付时间使用 state.paidAt，两者均为毫秒时间戳；未支付时 paidAt 为 null。金额单位为人民币元；商品明细通过自定义渲染器组合内置 TagsCell 展示。\n\nfirstOperator（创建人）与 operator（最后操作人）保存 userId，字段 options 使用 { value: userId, label: 姓名 } 映射。内置 text 单元格显示姓名，multi-select 按姓名选择但提交 ID；没有映射的 ID 保留原值。新建记录使用当前用户作为创建人，批量处理仅更新最后操作人。\n\n排序、页大小和筛选改变后由引擎重新协调查询。失败时保留可恢复状态，空结果保留工具栏与创建入口。请实际修改条件、切页并重试失败场景，观察记录而不只观察开发输出。所有 source 方法沿用 Wow QueryApi，取消信号继续传给业务 I/O。',
      },
    },
  },
  title: 'View Engine/Record View/查询与分页',
};

export default meta;

export const BusinessRecords: Story = {
  name: '订单工作台 · 查询与保存',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText('ORD-202609-1001')).toBeVisible();
    const row = canvas.getByRole('row', { name: /ORD-202609-1001/ });
    await expect(within(row).getByText('¥680.00')).toBeVisible();
    await expect(row.querySelector('[data-tone="warning"]')).toHaveTextContent(
      '待处理',
    );
    await expect(row).toHaveTextContent(/2026.*9.*6.*09:00/);
    for (const operation of ['save', 'create', 'rename', 'delete']) {
      await expect(
        canvas.getByTestId(`record-${operation}-count`),
      ).toHaveTextContent('0');
    }
    await expect(canvas.getByTestId('record-query-count')).toHaveTextContent(
      '1',
    );
  },
  render: args => <Scenario {...args} />,
};

export const CursorRecords: Story = {
  name: '游标查询 · 只向后翻页',
  render: args => <Scenario {...args} mode="cursor" summaries />,
};

export const ItemFilters: Story = {
  name: '商品明细 · 同一商品满足条件',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByText('共 2 条记录');
    for (const id of ['ORD-202609-1001', 'ORD-202609-1007'])
      await expect(
        canvas.getByRole('row', { name: new RegExp(id) }),
      ).toBeVisible();
    await expect(
      canvas.getByRole('row', { name: '本页汇总' }),
    ).toHaveTextContent('¥2,360.00');
    await waitFor(() =>
      expect(canvas.getByRole('row', { name: '所有汇总' })).toHaveTextContent(
        '¥1,680.00',
      ),
    );
  },
  parameters: {
    docs: {
      description: {
        story:
          '高级筛选 → 添加筛选 → 商品明细。支持商品编码、名称、单价、数量和小计，多个条件约束同一条明细；例如“名称包含键盘且数量 ≥ 2”。返回整笔订单，汇总仍统计订单总额与实付金额。保存保留 ELEMENT_MATCH 分支与子字段组件配置。',
      },
    },
  },
  render: args => (
    <Scenario
      {...args}
      summaries
      initialFilter={createFilterConfiguration({
        ...newFilterNode(FilterOperator.ELEMENT_MATCH, 'state.items'),
        predicate: {
          ...newFilterNode(FilterOperator.AND),
          operands: [
            {
              ...newFilterNode(FilterOperator.CONTAINS, 'productName'),
              props: { value: '键盘' },
            },
            {
              ...newFilterNode(FilterOperator.GTE, 'quantity'),
              props: { value: 2 },
            },
          ],
        },
      })}
    />
  ),
};

export const EmptyRecords: Story = {
  name: '空列表 · 保留创建入口',
  render: args => <Scenario {...args} empty />,
};

export const QueryFailure: Story = {
  name: '查询失败 · 保留草稿重试',
  render: args => <Scenario {...args} failFirstQuery />,
};
