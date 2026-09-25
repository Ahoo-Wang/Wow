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
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  AggregationFunction,
  AggregationGroupType,
  SortDirection,
} from '@ahoo-wang/wow-client';
import type {
  AnalysisViewConfig,
  ChartSpec,
  DataViewDefinition,
  RecordData,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  analysisConfig,
  createStoryEngine,
} from './fixtures.js';
import { rowSource } from './rowSource.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 显示收口（D33 批 E）：十二个城市、四个渠道的销售，城市之间差着几个数量级。
 * 对数刻度、散点的轴与十字准星、超过八条的拆分（Q56）、不可加指标的饼图置灰，
 * 以及把图导出为 PNG 与 SVG（Q58）。
 */
export type DisplayScene =
  | 'log-scale'
  | 'log-refused'
  | 'scatter'
  | 'split-other'
  | 'split-crowded'
  | 'pie-average';

/** Twelve cities, from a county town to a metropolis: three orders apart. */
const CITIES = [
  ['SH', '上海', 520_000],
  ['BJ', '北京', 410_000],
  ['SZ', '深圳', 260_000],
  ['GZ', '广州', 190_000],
  ['HZ', '杭州', 88_000],
  ['CD', '成都', 61_000],
  ['WH', '武汉', 35_000],
  ['XA', '西安', 18_000],
  ['NJ', '南京', 9_600],
  ['CS', '长沙', 4_200],
  ['KM', '昆明', 1_900],
  ['LS', '拉萨', 640],
] as const;

const CHANNELS = [
  ['online', '线上'],
  ['store', '门店'],
  ['wholesale', '批发'],
  ['partner', '合作方'],
] as const;

/**
 * Each city's sales split over the channels, an order for about every ¥5,000
 * — a larger city places more of them, so orders and sales rise together.
 */
function sales(): RecordData[] {
  return CITIES.flatMap(([city, , total]) =>
    CHANNELS.flatMap(([channel], channelIndex) => {
      const share = [0.45, 0.3, 0.15, 0.1][channelIndex]!;
      const orders = Math.max(1, Math.round((total * share) / 5_000));
      return Array.from({ length: orders }, (_, n) => ({
        id: `${city}-${channel}-${n}`,
        city,
        channel,
        amount: Math.round((total * share) / orders),
      }));
    }),
  );
}

const CITY_SALES = sales();

/** The same, a returns desk in Lhasa taking one order's worth back: a sum of 0. */
const WITH_A_ZERO: RecordData[] = [
  ...CITY_SALES.filter(row => row.city !== 'LS'),
  { id: 'LS-returns-1', city: 'LS', channel: 'store', amount: 320 },
  { id: 'LS-returns-2', city: 'LS', channel: 'store', amount: -320 },
];

const citySalesDefinition: DataViewDefinition = {
  kind: 'data',
  id: 'city-sales',
  title: '城市销售',
  source: 'city-sales',
  fields: [
    { name: 'id', label: '单号', kind: 'string', sortable: true },
    {
      name: 'city',
      label: '城市',
      kind: 'enum',
      options: CITIES.map(([value, label]) => ({ value, label })),
    },
    {
      name: 'channel',
      label: '渠道',
      kind: 'enum',
      options: CHANNELS.map(([value, label]) => ({ value, label })),
    },
    {
      name: 'amount',
      label: '金额',
      kind: 'number',
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      { field: 'city', groups: [AggregationGroupType.TERMS], functions: [] },
      {
        field: 'channel',
        groups: [AggregationGroupType.TERMS],
        functions: [],
      },
      {
        field: 'amount',
        groups: [],
        functions: [AggregationFunction.SUM, AggregationFunction.AVG],
      },
    ],
  },
};

const CITY = { type: 'TERMS', field: 'city', alias: 'city' } as const;
const CHANNEL = { type: 'TERMS', field: 'channel', alias: 'channel' } as const;
const SUM = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
} as const;
const AVG = { ...SUM, alias: 'average', function: 'AVG' } as const;
const COUNT = { alias: 'orders', type: 'COUNT' } as const;

/** The analysis a scene opens on. */
function displayConfig(scene: DisplayScene): AnalysisViewConfig {
  const byCity = (chart: ChartSpec, metrics: AnalysisViewConfig['metrics']) =>
    analysisConfig({
      layout: 'chart',
      groups: [CITY],
      metrics,
      sort: [{ alias: metrics[0].alias, direction: SortDirection.DESC }],
      limit: 100,
      table: { columns: [] },
      chart,
    });
  const split = (metric: typeof SUM | typeof AVG) =>
    analysisConfig({
      layout: 'chart',
      groups: [CHANNEL, CITY],
      metrics: [metric],
      sort: [{ alias: 'channel', direction: SortDirection.ASC }],
      limit: 1_000,
      table: { columns: [] },
      chart: {
        type: 'bar',
        cartesian: {
          x: 'channel',
          splitBy: 'city',
          // Sums stack into each channel's whole; averages stand side by side.
          series: [
            metric === SUM
              ? { metric: metric.alias, stack: 'all' }
              : { metric: metric.alias },
          ],
        },
      },
    });
  switch (scene) {
    case 'log-scale':
    case 'log-refused':
      return byCity(
        {
          type: 'bar',
          cartesian: {
            x: 'city',
            series: [{ metric: 'amount' }],
            yAxis: { left: { scale: 'log' } },
          },
        },
        [SUM],
      );
    case 'scatter':
      return byCity(
        {
          type: 'scatter',
          scatter: {
            category: 'city',
            x: 'orders',
            y: 'amount',
            xAxis: { scale: 'log' },
            yAxis: { label: '销售额（对数）', scale: 'log' },
          },
        },
        [SUM, COUNT],
      );
    case 'split-other':
      return split(SUM);
    case 'split-crowded':
      return split(AVG);
    case 'pie-average':
      return byCity(
        {
          type: 'bar',
          cartesian: { x: 'city', series: [{ metric: 'average' }] },
        },
        [AVG],
      );
  }
}

function displayView(scene: DisplayScene): ViewInstance {
  return {
    id: 'city-sales-analysis',
    definitionId: citySalesDefinition.id,
    title: '各城市销售额',
    scope: 'shared',
    revision: '1',
    config: displayConfig(scene),
  };
}

function DisplayDemo({ scene = 'log-scale' }: { scene?: DisplayScene }) {
  const view = displayView(scene);
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          definitions: [citySalesDefinition],
          source: rowSource(scene === 'log-refused' ? WITH_A_ZERO : CITY_SALES),
          instances: [view],
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={citySalesDefinition.id}
          instanceId={view.id}
          {...HOST_LANGUAGE}
          kinds={['analysis']}
        />
      )}
    </StoryEngine>
  );
}

const FIXTURE = '内存 ViewStore · 十二个城市、四个渠道的销售';

const description = `**分析视图 · 显示收口**（D33 批 E）

- **对数刻度**：数值轴与散点的两根轴都能按 10 的幂排刻度，城市之间差几个数量级时每一根都读得出；轴上有 0 或负数时这一项置灰并写原因，已存的对数刻度按线性画、图上方写一句。
- **散点**：两根轴各有标题、范围、数值格式与刻度；指针所在处画十字准星，两根轴上各写一个数。
- **超过八条的拆分**（Q56，结清 Q9）：可加的指标画最大的七条，其余并成一条灰色「其他」——另发一次只按横轴分组的查询、减去这七条；不可加的指标照画，显示页写「超过 8 条，颜色会重复，建议改用热力图或表格」；不可加指标的饼图置灰，写「占比只对可加的指标成立」。
- **读屏摘要**：图名之后一句话：共几组、最高、最低；时间轴说首尾与走向。
- **导出图片**（Q58）：「导出」菜单里与「导出数据…」并列的 PNG 与 SVG，图上带标题、图例与一行条件。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="display" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/能力/显示收口',
  component: DisplayDemo,
  args: { scene: 'log-scale' },
  argTypes: {
    scene: {
      control: 'inline-radio',
      options: [
        'log-scale',
        'log-refused',
        'scatter',
        'split-other',
        'split-crowded',
        'pie-average',
      ],
    },
  },
} satisfies Meta<typeof DisplayDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 十二个城市的销售额，左轴按 10 的幂排：拉萨与上海在一张图上都读得出。 */
export const LogScale: Story = { args: { scene: 'log-scale' } };

/** 拉萨一退一进合计为 0：对数刻度放不下，按线性画并在图上方写原因。 */
export const LogRefused: Story = { args: { scene: 'log-refused' } };

/** 每个城市一个点：单数对销售额，两根轴都按对数；指针处画十字准星。 */
export const ScatterAxes: Story = { args: { scene: 'scatter' } };

/** 按渠道拆成十二个城市：画最大的七个与一条灰色「其他」（Q56）。 */
export const SplitOther: Story = { args: { scene: 'split-other' } };

/** 平均额拆成十二条：不可加，照画，显示页里说颜色会重复（Q56）。 */
export const SplitCrowded: Story = { args: { scene: 'split-crowded' } };

/** 只有平均额：饼图置灰，写「占比只对可加的指标成立」（Q9）。 */
export const PieOfAnAverage: Story = { args: { scene: 'pie-average' } };
