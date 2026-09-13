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
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  AggregationFunction,
  AggregationGroupType,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewHost,
  createFilterConfiguration,
  type ViewDefinition,
  type ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import {
  EmbeddedView,
  useViewEngine,
} from '@ahoo-wang/fetcher-view-engine/react';
import { createOrderSource } from '../../packages/view-engine/examples/react/sales-order/querySource.js';

const definition: ViewDefinition = {
  id: 'home-orders',
  title: '经营数据',
  sourceId: 'orders',
  fields: [
    { field: 'id', label: '订单编号', type: 'string' },
    { field: 'region', label: '区域', type: 'string' },
    { field: 'amount', label: '销售额', type: 'number' },
  ],
  record: { rowKey: 'id', allowedLayouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      { field: 'region', groups: [AggregationGroupType.TERMS], functions: [] },
      { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
    ],
  },
  dashboard: true,
};
const filters = createFilterConfiguration({
  id: 'all',
  component: { name: 'builtin' },
  operator: FilterOperator.MATCH_ALL,
  props: {},
});
const instances: ViewInstance[] = [
  {
    id: 'orders',
    definitionId: definition.id,
    title: '近期订单',
    kind: 'record',
    revision: '1',
    scope: { type: 'personal' },
    config: {
      filters,
      sort: [],
      pagination: { mode: 'paged', size: 5 },
      presentation: {
        layout: 'table',
        table: {
          columns: definition.fields.map(({ field }) => ({
            id: field,
            kind: 'field',
            field,
          })),
        },
      },
    },
  },
  {
    id: 'regional-sales',
    definitionId: definition.id,
    title: '区域销售',
    kind: 'analysis',
    revision: '1',
    scope: { type: 'personal' },
    config: {
      filters,
      dimensions: [
        {
          id: 'region',
          component: { name: 'terms' },
          field: 'region',
          alias: 'region',
          title: '区域',
          props: {},
        },
      ],
      metrics: [
        {
          id: 'total',
          component: { name: 'numeric' },
          field: 'amount',
          alias: 'total',
          title: '销售额合计',
          props: { function: AggregationFunction.SUM },
        },
      ],
      sort: [],
      limit: 100,
      presentation: { layout: 'table', columns: [] },
    },
  },
  {
    id: 'overview',
    definitionId: definition.id,
    title: '经营概览',
    kind: 'dashboard',
    revision: '1',
    scope: { type: 'personal' },
    config: {
      schemaVersion: 1,
      panels: [
        {
          id: 'recent-orders',
          kind: 'view',
          instanceId: 'orders',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
        {
          id: 'sales-total',
          kind: 'view',
          instanceId: 'regional-sales',
          layout: { x: 6, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [],
    },
  },
];

function EmbeddedExample({
  instanceId = 'overview',
  repeated = false,
}: {
  instanceId?: string;
  repeated?: boolean;
}) {
  const [opened, setOpened] = useState('');
  const [host] = useState(
    () =>
      new MemoryViewHost({
        definition,
        instances: { instances, defaultInstanceId: null },
        serviceKey: 'embedded-story',
        scopeKey: 'manager',
        supportedFormats: { record: true, analysis: true, dashboard: 1 },
        resolveSource: () =>
          createOrderSource(() =>
            Array.from({ length: 12 }, (_, index) => ({
              id: `SO-${index + 1}`,
              region: index % 2 ? '华南' : '华东',
              amount: (index + 1) * 120,
            })),
          ),
        definitionPermissions: () => ({
          createPersonal: true,
          createShared: true,
        }),
        instancePermissions: () => ({
          save: true,
          saveAsPersonal: true,
          saveAsShared: true,
          rename: true,
          delete: true,
        }),
      }),
  );
  const binding = useViewEngine({
    scopeKey: 'embedded-story:manager',
    definitionId: definition.id,
    host,
  });
  return (
    <main className="fve-root fve:flex fve:min-h-screen fve:flex-col fve:gap-4 fve:bg-muted/30 fve:p-4">
      <h1 className="fve:text-xl fve:font-semibold">业务仪表盘</h1>
      <p className="fve:text-sm fve:text-muted-foreground">
        浏览经营数据，进入完整视图后管理配置。
      </p>
      {Array.from({ length: repeated ? 2 : 1 }, (_, index) => (
        <section key={index} aria-label={`嵌入区域 ${index + 1}`}>
          <EmbeddedView
            {...binding}
            instanceId={instanceId}
            title={
              repeated
                ? `${instances.find(instance => instance.id === instanceId)?.title} ${index + 1}`
                : undefined
            }
            onOpenView={identity =>
              setOpened(`${identity.definitionId} / ${identity.instanceId}`)
            }
          />
        </section>
      ))}
      {opened && <p role="status">已打开完整视图：{opened}</p>}
    </main>
  );
}

const meta = {
  id: 'view-engine-embedded-view',
  title: 'View Engine/引擎与宿主/嵌入视图',
  component: EmbeddedExample,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof EmbeddedExample>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Dashboard: Story = {
  name: '业务仪表盘',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(
      await canvas.findByRole('cell', { name: 'SO-1', exact: true }),
    ).toBeVisible();
    await expect(
      canvas.getAllByRole('cell', { name: '华东', exact: true }).length,
    ).toBeGreaterThan(0);
    await expect(
      canvas.queryByRole('button', {
        name: /^(保存|另存为|编辑布局|全局筛选设置|选择列|列设置|新建仪表盘)$/,
      }),
    ).not.toBeInTheDocument();
    await userEvent.click(
      canvas.getByRole('button', { name: '打开完整视图：经营概览' }),
    );
    await expect(
      await canvas.findByText('已打开完整视图：home-orders / overview'),
    ).toBeVisible();
  },
};
export const Record: Story = {
  name: '记录视图',
  args: { instanceId: 'orders' },
};
export const Analysis: Story = {
  name: '分析视图',
  args: { instanceId: 'regional-sales' },
};
export const Independent: Story = {
  name: '同一视图独立浏览',
  args: { instanceId: 'orders', repeated: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const first = within(canvas.getByRole('region', { name: '嵌入区域 1' }));
    const second = within(canvas.getByRole('region', { name: '嵌入区域 2' }));
    await first.findByRole('cell', { name: 'SO-1', exact: true });
    await second.findByRole('cell', { name: 'SO-1', exact: true });
    await userEvent.click(
      first.getByRole('button', { name: '筛选', exact: true }),
    );
    await userEvent.click(
      second.getByRole('button', { name: '筛选', exact: true }),
    );
    await userEvent.click(first.getByRole('button', { name: '下一页' }));
    await waitFor(() =>
      expect(
        first.getByRole('cell', { name: 'SO-6', exact: true }),
      ).toBeVisible(),
    );
    await expect(
      second.getByRole('cell', { name: 'SO-1', exact: true }),
    ).toBeVisible();
    await expect(
      second.queryByRole('cell', { name: 'SO-6', exact: true }),
    ).toBeNull();
  },
};

export const IndependentDashboards: Story = {
  name: '同一仪表盘独立嵌入',
  args: { instanceId: 'overview', repeated: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        canvas.getAllByRole('cell', { name: 'SO-1', exact: true }),
      ).toHaveLength(2),
    );
  },
};
export const IndependentAnalyses: Story = {
  name: '同一分析视图独立嵌入',
  args: { instanceId: 'regional-sales', repeated: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        canvas.getAllByRole('cell', { name: '华东', exact: true }),
      ).toHaveLength(2),
    );
  },
};
