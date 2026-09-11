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
import '@ahoo-wang/fetcher-view-engine/themes/neutral.css';
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  FilterOperator,
  type AggregationQuery,
} from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewHost,
  compileAnalysis,
  createFilterConfiguration,
  type AnalysisComponentConfig,
  type AnalysisRow,
  type AnalysisViewConfig,
  type RecordData,
  type ViewDefinition,
} from '@ahoo-wang/fetcher-view-engine';
import {
  useViewEngine,
  ViewPage,
  ViewTheme,
  Button,
} from '@ahoo-wang/fetcher-view-engine/react';

const definition: ViewDefinition = {
  id: 'chart-examples',
  title: '销售分析图表',
  sourceId: 'offline-chart-fixtures',
  timeZone: 'UTC',
  fields: [
    { field: 'region', label: '地区', type: 'string' },
    { field: 'channel', label: '销售渠道', type: 'string' },
    { field: 'product', label: '商品', type: 'string' },
    { field: 'productId', label: '商品 ID', type: 'string' },
    { field: 'productName', label: '商品名称', type: 'string' },
    { field: 'createdAt', label: '成交时间', type: 'datetime' },
    { field: 'revenue', label: '销售额', type: 'number' },
  ],
  analysis: {
    count: true,
    fields: [
      ...['region', 'channel', 'product', 'productId'].map(field => ({
        field,
        groups: [AggregationGroupType.TERMS],
        functions: [],
        any: true,
      })),
      { field: 'productName', groups: [], functions: [], any: true },
      {
        field: 'createdAt',
        groups: [AggregationGroupType.DATE_HISTOGRAM],
        dateUnits: [AggregationDateUnit.DAY],
        functions: [],
      },
      {
        field: 'revenue',
        groups: [],
        functions: [AggregationFunction.SUM, AggregationFunction.AVG],
        unit: 'CNY',
      },
    ],
  },
};
const dimension = (field: string, title: string): AnalysisComponentConfig => ({
  id: field,
  component: { name: 'terms' },
  field,
  alias: field,
  title,
  props: {},
});
const revenue: AnalysisComponentConfig = {
  id: 'revenue',
  component: { name: 'numeric' },
  field: 'revenue',
  alias: 'revenue',
  title: '销售额',
  props: { function: AggregationFunction.SUM },
};
const orders: AnalysisComponentConfig = {
  id: 'orders',
  component: { name: 'count' },
  alias: 'orders',
  title: '订单数',
  props: {},
};
const base: AnalysisViewConfig = {
  filters: createFilterConfiguration({
    id: 'all',
    component: { name: 'builtin' },
    operator: FilterOperator.MATCH_ALL,
    props: {},
  }),
  dimensions: [dimension('region', '地区'), dimension('channel', '销售渠道')],
  metrics: [revenue],
  sort: [],
  limit: 600,
  presentation: {
    layout: 'bar',
    columns: [],
    x: 'region',
    series: 'channel',
    metrics: ['revenue'],
  },
};
const regionalRows: AnalysisRow[] = [
  { region: '华东', channel: '线上', revenue: 180000 },
  { region: '华东', channel: '门店', revenue: 120000 },
  { region: '华南', channel: '线上', revenue: 90000 },
  { region: '华南', channel: '门店', revenue: 70000 },
  { region: '华北', channel: '线上', revenue: 60000 },
  { region: '华北', channel: '门店', revenue: 110000 },
];
const day = Date.UTC(2026, 8, 1);
const scenarios = {
  representative: {
    title: '按商品 ID 统计销售额，展示商品名称',
    config: {
      ...base,
      dimensions: [
        {
          ...dimension('productId', '商品'),
          label: {
            field: 'productName',
            alias: 'productName',
            title: '商品名称',
          },
        },
      ],
      metrics: [revenue],
      presentation: {
        layout: 'bar',
        columns: [
          { alias: 'productId' },
          { alias: 'productName' },
          { alias: 'revenue' },
        ],
        x: 'productId',
        metrics: ['revenue'],
      },
    },
    rows: [
      { productId: 'P-1001', productName: '机械键盘', revenue: 300000 },
      { productId: 'P-1002', productName: '无线鼠标', revenue: 160000 },
      { productId: 'P-1003', productName: '机械键盘', revenue: 170000 },
    ],
  },
  multiMetric: {
    title: '销售合计与客单价',
    config: {
      ...base,
      dimensions: [dimension('region', '地区')],
      metrics: [
        revenue,
        {
          ...revenue,
          id: 'average',
          alias: 'average',
          title: '客单价',
          props: { function: AggregationFunction.AVG },
        },
      ],
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'region',
        metrics: ['revenue', 'average'],
      },
    },
    rows: [
      { region: '华东', revenue: 1000, average: 500 },
      { region: '华南', revenue: 900, average: 450 },
    ],
  },
  signed: {
    title: '收入与退款正负堆叠',
    config: {
      ...base,
      dimensions: [
        {
          id: 'day',
          component: { name: 'date-histogram' },
          field: 'createdAt',
          alias: 'day',
          title: '日期',
          props: { unit: AggregationDateUnit.DAY },
        },
        dimension('channel', '业务类型'),
      ],
      presentation: { ...base.presentation, x: 'day', stacked: true },
    },
    rows: [
      { day, channel: '收入', revenue: 100 },
      { day, channel: '退款', revenue: -30 },
      { day: day + 86400000, channel: '收入', revenue: 80 },
      { day: day + 86400000, channel: '退款', revenue: -20 },
    ],
  },
  counts: {
    title: '少量记录计数',
    config: {
      ...base,
      dimensions: [dimension('region', '地区')],
      metrics: [
        {
          id: 'count',
          component: { name: 'count' },
          alias: 'records',
          title: '记录数',
          props: {},
        },
      ],
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'region',
        metrics: ['records'],
      },
    },
    rows: [
      { region: '华东', records: 1 },
      { region: '华南', records: 2 },
    ],
  },
  implicitPie: {
    title: '默认指标冲突与定位修复',
    config: {
      ...base,
      dimensions: [dimension('region', '地区')],
      metrics: [
        revenue,
        {
          ...revenue,
          id: 'average',
          alias: 'average',
          title: '客单价',
          props: { function: AggregationFunction.AVG },
        },
      ],
      presentation: { layout: 'pie', columns: [], x: 'region' },
    },
    rows: [
      { region: '华东', revenue: 1000, average: 100 },
      { region: '华南', revenue: 500, average: 50 },
    ],
  },
  laterMetric: {
    title: '净销售额含负数，按订单数展示占比',
    config: {
      ...base,
      dimensions: [dimension('region', '地区')],
      metrics: [revenue, orders],
      presentation: { layout: 'table', columns: [] },
    },
    rows: [
      { region: '华东', revenue: -300, orders: 5 },
      { region: '华南', revenue: 200, orders: 3 },
    ],
  },
  multi: { title: '地区与渠道销售额', config: base, rows: regionalRows },
  trend: {
    title: '连续时间趋势与空值',
    config: {
      ...base,
      dimensions: [
        {
          id: 'day',
          component: { name: 'date-histogram' },
          field: 'createdAt',
          alias: 'day',
          title: '成交日期',
          props: { unit: AggregationDateUnit.DAY },
        },
        dimension('channel', '销售渠道'),
      ],
      presentation: {
        layout: 'line',
        columns: [],
        x: 'day',
        series: 'channel',
        metrics: ['revenue'],
      },
    },
    rows: [
      { day, channel: '线上', revenue: 12000 },
      { day, channel: '门店', revenue: 9000 },
      { day: day + 86400000, channel: '线上', revenue: null },
      { day: day + 86400000, channel: '门店', revenue: 0 },
      { day: day + 3 * 86400000, channel: '线上', revenue: 16000 },
      { day: day + 3 * 86400000, channel: '门店', revenue: 11000 },
    ],
  },
  donut: {
    title: '已返回地区销售额分布',
    config: {
      ...base,
      dimensions: [dimension('region', '地区')],
      presentation: {
        layout: 'pie',
        columns: [],
        x: 'region',
        metrics: ['revenue'],
        donut: true,
      },
    },
    rows: [
      { region: '华东', revenue: 300000 },
      { region: '华南', revenue: 160000 },
      { region: '华北', revenue: 170000 },
    ],
  },
  metric: {
    title: '无分组指标',
    config: {
      ...base,
      dimensions: [],
      metrics: [
        orders,
        revenue,
        {
          ...revenue,
          id: 'average',
          alias: 'average',
          title: '平均订单金额',
          props: { function: AggregationFunction.AVG },
        },
      ],
      presentation: { layout: 'metric', columns: [] },
    },
    rows: [{ orders: 1200, revenue: 630000, average: 525 }],
  },
  empty: { title: '空结果', config: base, rows: [] },
  dimensions: {
    title: '额外维度需要数据表',
    config: {
      ...base,
      dimensions: [...base.dimensions, dimension('product', '商品')],
    },
    rows: [
      { region: '华东', channel: '线上', product: '订阅', revenue: 12000 },
      { region: '华东', channel: '线上', product: '服务', revenue: 8000 },
    ],
  },
  units: {
    title: '不同单位保留数据表',
    config: {
      ...base,
      dimensions: [dimension('region', '地区')],
      metrics: [orders, revenue],
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'region',
        metrics: ['orders', 'revenue'],
      },
    },
    rows: [
      { region: '华东', orders: 500, revenue: 300000 },
      { region: '华南', orders: 300, revenue: 160000 },
    ],
  },
  limit: {
    title: '超图表上限的分页表格',
    config: {
      ...base,
      dimensions: [dimension('product', '商品')],
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'product',
        metrics: ['revenue'],
      },
    },
    rows: Array.from({ length: 501 }, (_, i) => ({
      product: `SKU-${String(i + 1).padStart(3, '0')}`,
      revenue: (i + 1) * 100,
    })),
  },
} satisfies Record<
  string,
  { title: string; config: AnalysisViewConfig; rows: AnalysisRow[] }
>;

function OfflineChartPage({ host }: { host: MemoryViewHost }) {
  const binding = useViewEngine({
    scopeKey: 'demo',
    definitionId: definition.id,
    host,
  });
  return <ViewPage {...binding} />;
}
function OfflineCharts({
  scenario = 'multi',
  appearance = 'light',
  reloadable = false,
  startWithTable = false,
  title,
}: {
  scenario?: keyof typeof scenarios;
  appearance?: 'light' | 'dark';
  reloadable?: boolean;
  startWithTable?: boolean;
  title?: string;
}) {
  const [generation, setGeneration] = useState(0);
  const [requests, setRequests] = useState(0);
  const [host] = useState(() => {
    const source = scenarios[scenario];
    const fixture = startWithTable
      ? {
          ...source,
          config: {
            ...source.config,
            presentation: { layout: 'table' as const, columns: [] },
          },
        }
      : source;
    const compiled = compileAnalysis(fixture.config, {
      fields: definition.fields,
      capability: definition.analysis!,
      timeZone: definition.timeZone,
    });
    if (!compiled.plan)
      throw new Error(compiled.errors.map(e => e.message).join('；'));
    const expected = JSON.stringify(compiled.plan.query);
    return new MemoryViewHost({
      serviceKey: `chart-fixture-${scenario}`,
      scopeKey: 'demo',
      definition,
      instances: {
        defaultInstanceId: 'example',
        instances: [
          {
            id: 'example',
            definitionId: definition.id,
            kind: 'analysis',
            title: title ?? fixture.title,
            revision: '1',
            scope: { type: 'personal' },
            config: fixture.config,
          },
        ],
      },
      resolveSource: () => ({
        async aggregate<Row extends RecordData = RecordData>(
          query: AggregationQuery,
          _attributes?: Record<string, unknown>,
          controller?: AbortController,
        ): Promise<Row[]> {
          controller?.signal.throwIfAborted();
          setRequests(value => value + 1);
          // Exact compiled requests only: this fixture never pretends to evaluate an edited aggregation.
          if (JSON.stringify(query) !== expected)
            throw new Error(
              '离线示例只提供预设查询结果；请恢复配置或切换示例。图表设置可自由切换。',
            );
          return structuredClone(fixture.rows) as unknown as Row[];
        },
      }),
    });
  });
  return (
    <ViewTheme
      theme="neutral"
      appearance={appearance}
      data-testid="chart-theme"
    >
      <div className="fve-root fve:px-4 fve:py-2 fve:text-sm fve:text-muted-foreground">
        离线预设数据 ·{' '}
        <span data-testid="chart-requests">查询次数：{requests}</span> ·
        调整图表不会重新查询；修改查询条件后运行会显示可恢复错误。
      </div>
      {scenario === 'representative' && (
        <p className="fve-root fve:m-0 fve:px-4 fve:py-2 fve:text-sm fve:text-muted-foreground">
          按 productId 分组、SUM 汇总销售额，ANY(productName) 补充商品名称。不同
          ID 的同名商品仍分别统计。示例假设同一 ID 对应同一名称；ANY
          不用于选择最新名称。
        </p>
      )}
      {reloadable && (
        <Button
          variant="outline"
          className="fve:mx-4 fve:mb-2"
          onClick={() => setGeneration(value => value + 1)}
        >
          重载已保存视图
        </Button>
      )}
      <OfflineChartPage key={generation} host={host} />
    </ViewTheme>
  );
}
const meta = {
  id: 'view-engine-分析图表',
  title: 'View Engine/分析视图/图表与结果',
  component: OfflineCharts,
  parameters: { layout: 'fullscreen' },
  render: args => (
    <OfflineCharts key={`${args.scenario}:${args.startWithTable}`} {...args} />
  ),
} satisfies Meta<typeof OfflineCharts>;
export default meta;
type Story = StoryObj<typeof meta>;
export const MultiSeries: Story = {
  name: '多系列柱状图',
  args: { scenario: 'multi' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('application')).toBeVisible();
    const count = canvas.getByTestId('chart-requests').textContent!;
    const visual = canvas.getByRole('button', {
      name: '可视化配置',
      exact: true,
    });
    await expect(visual).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(visual);
    const settings = canvas.getByRole('region', {
      name: '可视化配置区',
    });
    const result = canvas.getByLabelText('分析结果区');
    if (result.getBoundingClientRect().width > 450)
      await expect(settings.getBoundingClientRect().right).toBeLessThanOrEqual(
        result.getBoundingClientRect().left,
      );
    if (!canvas.getByText('样式设置', { exact: true }).closest('details')?.open)
      await userEvent.click(canvas.getByText('样式设置', { exact: true }));
    await userEvent.click(canvas.getByRole('combobox', { name: '柱状图方向' }));
    await userEvent.click(await page.findByRole('option', { name: '横向' }));
    await expect(
      canvas.getByRole('radio', { name: '折线图', exact: true }),
    ).toBeDisabled();
    await userEvent.click(
      canvas.getByRole('radio', { name: '柱状图', exact: true }),
    );
    await expect(
      canvas.getByRole('combobox', { name: '系列维度' }),
    ).toHaveTextContent('销售渠道');
    await expect(
      canvas.getByRole('combobox', { name: '柱状图方向' }),
    ).toHaveTextContent('横向');
    await userEvent.click(
      canvas.getByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(canvas.getByRole('table')).toBeVisible();
    const tabs = canvas
      .getByRole('tablist', { name: '分析结果展示方式' })
      .getBoundingClientRect();
    const bounds = result.getBoundingClientRect();
    await expect(
      Math.abs((tabs.left + tabs.right - bounds.left - bounds.right) / 2),
    ).toBeLessThan(2);
    await userEvent.keyboard('{ArrowLeft}');
    await expect(
      canvas.getByRole('tab', { name: '分析', exact: true }),
    ).toHaveAttribute('aria-selected', 'true');
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '配置查询', exact: true }),
    );
    const sheet = await page.findByRole('dialog', { name: '配置查询' });
    await expect(sheet).toHaveAttribute('data-slot', 'sheet-content');
    await expect(
      Math.abs(
        sheet.getBoundingClientRect().right -
          canvasElement.ownerDocument.documentElement.clientWidth,
      ),
    ).toBeLessThan(2);
    const query = within(sheet);
    const summary = query.getByLabelText('编辑维度 1');
    await userEvent.click(summary);
    const title = page.getByRole('textbox', { name: '维度 1 名称' });
    await userEvent.clear(title);
    await userEvent.type(title, '销售地区');
    await userEvent.click(
      page.getByRole('button', { name: '完成编辑', exact: true }),
    );
    await userEvent.click(
      query.getByRole('button', { name: '查看结果', exact: true }),
    );
    await expect(
      canvas.getByRole('button', { name: '展开查询配置' }),
    ).toHaveTextContent('销售地区');
    await userEvent.click(canvas.getByRole('button', { name: '展开查询配置' }));
    await userEvent.click(query.getByLabelText('编辑维度 1'));
    await expect(
      page.getByRole('textbox', { name: '维度 1 名称' }),
    ).toHaveValue('销售地区');
    await userEvent.clear(title);
    await userEvent.type(title, '地区');
    await userEvent.click(
      page.getByRole('button', { name: '完成编辑', exact: true }),
    );
    await userEvent.click(
      query.getByRole('button', { name: '查看结果', exact: true }),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '折叠可视化配置' }),
    );
    await expect(visual).toHaveAttribute('aria-expanded', 'false');
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(count);
  },
};
export const TimeTrend: Story = {
  name: '时间趋势、零值与缺口',
  args: { scenario: 'trend' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(canvas.getByRole('cell', { name: /^无值$/ })).toBeVisible();
    await expect(canvas.getByRole('cell', { name: /^0$/ })).toBeVisible();
    await expect(canvas.getAllByRole('row')).toHaveLength(7);
    await userEvent.click(
      canvas.getByRole('tab', { name: '分析', exact: true }),
    );
  },
};
export const Donut: Story = {
  name: '地区销售额环形图',
  args: { scenario: 'donut' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    await userEvent.click(canvas.getByText('样式设置', { exact: true }));
    const legend = within(canvas.getByRole('list', { name: '分组数值与占比' }));
    await expect(legend.getByText('300,000', { exact: true })).toBeVisible();
    await expect(legend.getByText('47.6%', { exact: true })).toBeVisible();
    await expect(legend.getByText('25.4%', { exact: true })).toBeVisible();
    await expect(legend.getByText('27%', { exact: true })).toBeVisible();
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '环形', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('checkbox', { name: '环形', exact: true }),
      ).not.toBeChecked(),
    );
    await waitFor(() =>
      expect(
        within(canvas.getByRole('list', { name: '分组数值与占比' })).getByText(
          '47.6%',
          { exact: true },
        ),
      ).toBeVisible(),
    );
    await userEvent.click(
      canvas.getByRole('checkbox', { name: '环形', exact: true }),
    );
    await waitFor(() =>
      expect(
        canvas.getByRole('checkbox', { name: '环形', exact: true }),
      ).toBeChecked(),
    );
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent('1');
  },
};
export const MetricCards: Story = {
  name: '无分组指标卡',
  args: { scenario: 'metric' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText('525', { exact: true })).toBeVisible();
  },
};
export const Empty: Story = { name: '空结果', args: { scenario: 'empty' } };
export const ExtraDimensions: Story = {
  name: '多余维度回退',
  args: { scenario: 'dimensions' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(
      await canvas.findByRole('cell', { name: '订阅' }),
    ).toBeVisible();
    await expect(canvas.getByRole('table')).toBeVisible();
  },
};
export const MixedUnits: Story = {
  name: '不同单位回退',
  args: { scenario: 'units' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(
      await canvas.findByRole('cell', { name: '500' }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('tab', { name: '分析', exact: true }),
    );
    await expect(canvas.getAllByText(/指标单位不兼容/).length).toBeGreaterThan(
      0,
    );
  },
};
export const BoundedTable: Story = {
  name: '超限结果分页回退',
  args: { scenario: 'limit' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(
      await canvas.findByRole('cell', { name: 'SKU-001' }),
    ).toBeVisible();
    await expect(canvas.getAllByRole('row')).toHaveLength(101);
    await userEvent.click(canvas.getByRole('button', { name: '下一页' }));
    await expect(
      canvas.getByRole('cell', { name: 'SKU-101', hidden: true }),
    ).toHaveTextContent('SKU-101');
    await userEvent.click(
      canvas.getByRole('button', { name: '配置查询', exact: true }),
    );
    const query = within(
      await within(canvasElement.ownerDocument.body).findByRole('dialog', {
        name: '配置查询',
      }),
    );
    await userEvent.click(query.getByText('高级设置', { exact: true }));
    const limit = query.getByRole('textbox', { name: '最多结果行数' });
    await userEvent.clear(limit);
    await userEvent.type(limit, '601');
    await expect(
      canvas.getByRole('cell', { name: 'SKU-101', hidden: true }),
    ).toHaveTextContent('SKU-101');
    await userEvent.clear(limit);
    await userEvent.type(limit, '600');
    await expect(
      canvas.getByRole('cell', { name: 'SKU-101', hidden: true }),
    ).toHaveTextContent('SKU-101');

    await userEvent.click(
      query.getByRole('button', { name: '查看结果', exact: true }),
    );
    await expect(canvas.getByRole('cell', { name: 'SKU-101' })).toBeVisible();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
  },
};

export const DarkTheme: Story = {
  name: '深色主题与作用域图表颜色',
  args: { scenario: 'multi', appearance: 'dark' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const chart = await canvas.findByRole('application');
    await expect(chart).toBeVisible();
    const scope = canvas.getByTestId('chart-theme');
    await expect(scope).toHaveAttribute('data-theme', 'dark');
    await expect(getComputedStyle(chart).colorScheme).toBe('dark');
    const mark = chart.querySelector('.recharts-bar-rectangle path');
    await expect(mark).not.toBeNull();
    const probe = document.createElement('span');
    probe.style.color = 'var(--fve-chart-1)';
    scope.appendChild(probe);
    const expectedColor = getComputedStyle(probe).color;
    await expect(getComputedStyle(mark!).fill).toBe(expectedColor);
    probe.style.color = 'var(--fve-muted-foreground)';
    const tick = chart.querySelector('.recharts-cartesian-axis-tick-value');
    await expect(tick).not.toBeNull();
    await expect(getComputedStyle(tick!).fill).toBe(
      getComputedStyle(probe).color,
    );
    probe.remove();
  },
};
export const SavedLayout: Story = {
  name: '保存后重建引擎恢复图表',
  args: { scenario: 'trend', reloadable: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
    await userEvent.click(
      canvas.getByRole('radio', { name: '面积图', exact: true }),
    );
    await expect(
      canvas.getByRole('radio', { name: '面积图', exact: true }),
    ).toBeChecked();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
    await userEvent.click(canvas.getByRole('button', { name: '保存' }));
    await expect(
      await canvas.findByRole('status', { name: '保存状态' }),
    ).toHaveTextContent('视图已保存');
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '重载已保存视图' }),
    );
    await waitFor(() =>
      expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
        '查询次数：2',
      ),
    );
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    await expect(
      canvas.getByRole('radio', { name: '面积图', exact: true }),
    ).toBeChecked();
    await expect(
      canvas.getByRole('combobox', { name: '横轴维度' }),
    ).toHaveTextContent('成交日期');
    await expect(
      canvas.getByRole('combobox', { name: '系列维度' }),
    ).toHaveTextContent('销售渠道');
    await expect(canvas.getByRole('button', { name: '保存' })).toBeDisabled();
    await expect(canvasElement.querySelector('.recharts-area')).not.toBeNull();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：2',
    );
  },
};

export const ZoomedLongTitle: Story = {
  name: '200% 缩放与中英文长标题',
  args: {
    scenario: 'multi',
    title:
      '跨区域线上与门店销售分析 / Regional online and retail sales comparison for the current financial year',
  },
  decorators: [
    Story => (
      <div style={{ zoom: 2 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
      window.innerWidth + 1,
    );
    for (const name of ['配置查询', '运行分析']) {
      const button = canvas.getByRole('button', { name, exact: true });
      await expect(button).toBeVisible();
      const box = button.getBoundingClientRect();
      await expect(box.left).toBeGreaterThanOrEqual(0);
      await expect(box.right).toBeLessThanOrEqual(window.innerWidth + 1);
    }
    await userEvent.click(
      canvas.getByRole('button', { name: '运行分析', exact: true }),
    );
    await waitFor(() =>
      expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
        '查询次数：2',
      ),
    );
  },
};

export const SmallCounts: Story = {
  name: '小计数使用整数刻度',
  args: { scenario: 'counts' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    const verify = (axis: string) => {
      const labels = [
        ...canvasElement.querySelectorAll(
          `.recharts-${axis}-tick-labels .recharts-cartesian-axis-tick-value`,
        ),
      ].map(node => node.textContent);
      expect(labels.length).toBeGreaterThan(1);
      expect(new Set(labels).size).toBe(labels.length);
      expect(labels.every(value => /^\d+$/.test(value ?? ''))).toBe(true);
    };
    verify('yAxis');
    if (!canvas.getByText('样式设置', { exact: true }).closest('details')?.open)
      await userEvent.click(canvas.getByText('样式设置', { exact: true }));
    await userEvent.click(canvas.getByRole('combobox', { name: '柱状图方向' }));
    await userEvent.click(await page.findByRole('option', { name: '横向' }));
    await waitFor(() => verify('xAxis'));
  },
};

export const SignedStacks: Story = {
  name: '收入与退款分居零轴两侧',
  args: { scenario: 'signed' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    const negativeTicks = (axis: string) =>
      [
        ...canvasElement.querySelectorAll(`.recharts-${axis}-tick-labels text`),
      ].some(t => Number(t.textContent!.replaceAll(',', '')) < 0);
    await waitFor(() => expect(negativeTicks('yAxis')).toBe(true));
    if (!canvas.getByText('样式设置', { exact: true }).closest('details')?.open)
      await userEvent.click(canvas.getByText('样式设置', { exact: true }));
    await userEvent.click(canvas.getByRole('combobox', { name: '柱状图方向' }));
    await userEvent.click(await page.findByRole('option', { name: '横向' }));
    await waitFor(() => expect(negativeTicks('xAxis')).toBe(true));
    await userEvent.click(
      canvas.getByRole('radio', { name: '面积图', exact: true }),
    );
    await waitFor(() => expect(negativeTicks('yAxis')).toBe(true));
    await expect(canvasElement.querySelector('.recharts-area')).not.toBeNull();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
  },
};

export const MetricRoundTrip: Story = {
  name: '切换饼图不丢失多指标',
  args: { scenario: 'multiMetric' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    for (const layout of ['饼图', '柱状图']) {
      await userEvent.click(
        canvas.getByRole('radio', { name: layout, exact: true }),
      );
      if (layout === '饼图')
        await expect(
          within(
            canvas.getByRole('region', { name: '可视化配置区' }),
          ).getByText(/饼图只支持一个指标，当前选择了 2 个/),
        ).toBeVisible();
      await expect(
        canvas.getByRole('checkbox', { name: '销售额', exact: true }),
      ).toBeChecked();
      await expect(
        canvas.getByRole('checkbox', { name: '客单价', exact: true }),
      ).toBeChecked();
    }
    await expect(canvas.getByRole('application')).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: '保存', exact: true }),
    ).toBeDisabled();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
  },
};

export const EmbeddedConfiguration: Story = {
  name: '窄容器配置与键盘操作',
  args: { scenario: 'multi' },
  render: args => (
    <div
      data-testid="embedded-analysis-container"
      style={{ width: 600, maxWidth: '100%' }}
    >
      <OfflineCharts {...args} />
    </div>
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('application')).toBeVisible();
    const trigger = canvas.getByRole('button', {
      name: '配置查询',
      exact: true,
    });
    await userEvent.click(trigger);
    const dialog = within(
      await page.findByRole('dialog', { name: '配置查询' }),
    );
    const summary = dialog.getByLabelText('编辑维度 1');
    await userEvent.click(summary);
    await expect(
      page.getByRole('textbox', { name: '维度 1 名称' }),
    ).toBeVisible();
    await userEvent.click(dialog.getByLabelText('编辑维度 2'));
    await expect(
      page.getByRole('textbox', { name: '维度 2 名称' }),
    ).toBeVisible();
    await expect(summary).toHaveAttribute('aria-expanded', 'false');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(dialog.getByLabelText('编辑维度 2')).toHaveFocus(),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(trigger).toHaveFocus());
    const container = canvas.getByTestId('embedded-analysis-container');
    container.style.width = '100%';
    await waitFor(() =>
      expect(page.getByLabelText('分析配置面板')).toBeInTheDocument(),
    );
    await userEvent.click(trigger);
    await expect(page.getByLabelText('分析配置面板')).toBeVisible();
    await userEvent.click(page.getByLabelText('编辑指标 1'));
    await waitFor(() =>
      expect(
        page
          .getByRole('dialog', { name: '指标设置' })
          .contains(canvasElement.ownerDocument.activeElement),
      ).toBe(true),
    );
    await userEvent.click(page.getByRole('combobox', { name: '指标 1 类型' }));
    await expect(await page.findByRole('listbox')).toBeVisible();
    // Resize without a pointer event: outside-click dismissal cannot hide an orphaned menu for us.
    container.style.width = '600px';
    await expect(page.getByRole('listbox')).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(page.queryByRole('dialog', { name: '指标设置' })).toBeNull(),
    );
    await userEvent.click(
      page.getByRole('button', { name: '查看结果', exact: true }),
    );
    await userEvent.click(trigger);
    await userEvent.click(page.getByLabelText('编辑指标 1'));
    await expect(page.queryByRole('listbox')).toBeNull();
    await userEvent.keyboard('{Escape}');
    await userEvent.keyboard('{Escape}');
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent('1');
  },
};

export const AnyRepresentative: Story = {
  name: '商品销售：按 ID 分组，以名称展示',
  args: { scenario: 'representative' },
};

export const TableFirst: Story = {
  name: '默认数据表 → 选择展示方式',
  args: { scenario: 'multi', startWithTable: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(await canvas.findByRole('table')).toBeVisible();
    await expect(
      canvas.getByRole('tab', { name: '数据表', exact: true }),
    ).toHaveAttribute('aria-selected', 'true');
    await expect(
      canvas.queryByRole('combobox', { name: '图表类型' }),
    ).toBeNull();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    await expect(
      canvas.queryByRole('combobox', { name: '横轴维度' }),
    ).toBeNull();
    await expect(canvas.getAllByRole('radio')).toHaveLength(5);
    await expect(
      canvas.queryByRole('radio', { name: '数据表', exact: true }),
    ).toBeNull();
    await userEvent.click(
      canvas.getByRole('radio', { name: '柱状图', exact: true }),
    );
    await userEvent.click(canvas.getByRole('combobox', { name: '横轴维度' }));
    await userEvent.click(
      await page.findByRole('option', { name: '地区', exact: true }),
    );
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(
      canvas.getByRole('combobox', { name: '横轴维度' }),
    ).toBeVisible();
    await userEvent.click(
      canvas.getByRole('tab', { name: '数据表', exact: true }),
    );
    await expect(canvas.getByRole('table')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('tab', { name: '分析', exact: true }),
    );
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
  },
};

export const CapabilitySelection: Story = {
  name: '展示方式卡片：后续合法指标与键盘选择',
  args: { scenario: 'laterMetric' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByRole('table')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '可视化配置', exact: true }),
    );
    await expect(
      canvas.queryByRole('combobox', { name: '图表类型' }),
    ).toBeNull();
    await expect(canvas.getAllByRole('radio')).toHaveLength(5);
    await expect(canvas.getByRole('radio', { name: '指标卡' })).toBeDisabled();
    await expect(canvas.getByText(/不可用：仅支持无分组结果/)).toBeVisible();
    const pie = canvas.getByRole('radio', { name: '饼图' });
    await expect(pie).toBeEnabled();
    await userEvent.click(pie);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(
      canvas.getByRole('checkbox', { name: '订单数', exact: true }),
    ).toBeChecked();
    await expect(
      canvas.getByText('样式设置').closest('details'),
    ).not.toHaveAttribute('open');
    await userEvent.click(canvas.getByRole('radio', { name: '柱状图' }));
    await userEvent.keyboard('{ArrowRight}');
    await expect(pie).toBeChecked();
    await expect(pie).toHaveFocus();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
  },
};

export const ImplicitMetricRepair: Story = {
  name: '隐式指标冲突：定位并修复，保留结果',
  args: { scenario: 'implicitPie' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText('当前映射无法绘图')).toBeVisible();
    await userEvent.click(
      canvas.getByRole('button', { name: '修复配置', exact: true }),
    );
    const average = canvas.getByRole('checkbox', {
      name: '客单价',
      exact: true,
    });
    await expect(average).toBeChecked();
    await expect(average).toHaveFocus();
    await expect(
      canvas.getByRole('checkbox', { name: '销售额', exact: true }),
    ).toBeChecked();
    await expect(
      within(canvas.getByRole('region', { name: '可视化配置区' })).getByText(
        /当前选择了 2 个/,
      ),
    ).toBeVisible();
    await expect(canvas.getByText(/客单价（AVG）不能用于占比/)).toBeVisible();
    await userEvent.click(average);
    await expect(await canvas.findByRole('application')).toBeVisible();
    await expect(
      canvas.getByRole('radio', { name: '饼图', exact: true }),
    ).toBeChecked();
    await expect(canvas.getByTestId('chart-requests')).toHaveTextContent(
      '查询次数：1',
    );
  },
};
