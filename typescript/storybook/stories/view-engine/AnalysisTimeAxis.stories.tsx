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
import type { CSSProperties } from 'react';
import {
  fitChartSlots,
  type AnalysisViewConfig,
  type ChartType,
  type DataViewDefinition,
  type FilterLeaf,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { tradeOrderDefinition } from './retail/definitions.js';
import { RETAIL_DATA_NOTE, retailShell } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 零售的交易订单，分析上限放到运行时的上限（一万组）：订单定义自己的上限
 * 是 1000（25 个月的日粒度约 760 组，够用），而逐时一年多是九千多组。
 */
const definition: DataViewDefinition = (() => {
  const orders = tradeOrderDefinition('retail-long-axis');
  return {
    ...orders,
    analysis: { ...orders.analysis!, limits: { maxLimit: 10_000 } },
  };
})();

const GMV = 'state.amounts.payableAmount';

/**
 * - `days`：25 个月的日 GMV，按店铺拆成三条线。
 * - `days-bars`：同一段画成按渠道堆叠的柱，约 750 根。
 * - `hours`：近 400 天的逐时 GMV，一条九千多个点的线。
 */
export type TimeAxisScene = 'days' | 'days-bars' | 'hours';

/** 截至昨日：今天才过了 10 个小时，放进来就是最后一个「最低点」。 */
const UNTIL_YESTERDAY: FilterLeaf = {
  field: 'firstEventTime',
  operator: 'LTE',
  value: { type: 'preset', preset: 'yesterday' },
};

/** 近 400 天：逐时约 9600 组，在一万组以内。 */
const LAST_400_DAYS: FilterLeaf = {
  field: 'firstEventTime',
  operator: 'BETWEEN',
  value: { type: 'relative', amount: 400, unit: 'day' },
};

function timeAxisConfig(
  scene: TimeAxisScene,
  type: ChartType = scene === 'days-bars' ? 'bar' : 'line',
): AnalysisViewConfig {
  const hourly = scene === 'hours';
  const split =
    scene === 'days'
      ? { field: 'state.shopId', alias: 'shop' }
      : scene === 'days-bars'
        ? { field: 'state.channel', alias: 'channel' }
        : undefined;
  const groups: AnalysisViewConfig['groups'] = [
    {
      type: 'DATE_HISTOGRAM',
      field: 'firstEventTime',
      alias: 'time',
      unit: hourly ? 'HOUR' : 'DAY',
      label: hourly ? '时段' : '日期',
      // 逐时里没有单的钟点是 0，不是断开的线。
      ...(split ? {} : { dense: true }),
    },
    ...(split ? [{ type: 'TERMS' as const, ...split }] : []),
  ];
  const metrics = [
    {
      alias: 'gmv',
      type: 'NUMERIC',
      function: 'SUM',
      label: 'GMV',
      expression: { type: 'FIELD', field: GMV },
    },
  ] satisfies AnalysisViewConfig['metrics'];
  const fitted = fitChartSlots({ type }, groups, metrics);
  return {
    kind: 'analysis',
    filter: {
      op: 'and',
      children: [hourly ? LAST_400_DAYS : UNTIL_YESTERDAY],
    },
    filterMode: 'simple',
    refresh: { interval: null },
    layout: 'chart',
    groups,
    metrics,
    sort: [{ alias: 'time', direction: 'ASC' }],
    limit: 10_000,
    table: { columns: [] },
    chart:
      scene === 'days-bars' && fitted.cartesian
        ? {
            ...fitted,
            cartesian: {
              ...fitted.cartesian,
              series: fitted.cartesian.series.map(series => ({
                ...series,
                stack: 'all',
              })),
            },
          }
        : fitted,
  };
}

const TITLES: Record<TimeAxisScene, string> = {
  days: '日 GMV（近 25 个月，按店铺）',
  'days-bars': '日 GMV（近 25 个月，按渠道）',
  hours: '逐时 GMV（近 400 天）',
};

function timeAxisView(scene: TimeAxisScene, chart?: ChartType): ViewInstance {
  return {
    id: 'retail-long-axis',
    definitionId: definition.id,
    title: TITLES[scene],
    scope: 'shared',
    revision: '1',
    config: timeAxisConfig(scene, chart),
  };
}

/**
 * 长时间轴上的一张图：栖木生活 25 个月的日 GMV，或近 400 天的逐时 GMV。
 * `patterns` 是宿主对花纹的钉法——写在工作台外面一层的
 * `--fve-chart-patterns` 上，`auto` 跟随系统的「提高对比度」（D33 Q57）。
 */
function TimeAxisDemo({
  scene = 'days',
  chart,
  patterns = 'auto',
}: {
  scene?: TimeAxisScene;
  /** The chart type; left out, the scene's own. */
  chart?: 'line' | 'bar';
  patterns?: 'auto' | 'on' | 'off';
}) {
  const view = timeAxisView(scene, chart);
  return (
    <div
      className="h-full"
      style={
        patterns === 'auto'
          ? undefined
          : ({ '--fve-chart-patterns': patterns } as CSSProperties)
      }
    >
      <StoryEngine
        key={`${scene}:${chart ?? ''}`}
        create={() => createRetailEngine([definition], [view])}
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId={definition.id}
            instanceId={view.id}
            {...HOST_LANGUAGE}
            kinds={['analysis']}
          />
        )}
      </StoryEngine>
    </div>
  );
}

const description = `**能力 · 长时间轴**（D33 批 A）

点多的图怎样读：栖木生活两年多的日 GMV，和一年多的逐时 GMV。

${RETAIL_DATA_NOTE}

- **缩放**：点多于 60 个的横轴在图下有一条滑条，拖两端缩放、拖中间平移；工作台里按住 Ctrl 滚动（触控板双指捏合同理）或触屏双指捏合也能缩放。**不按 Ctrl 的滚轮照常滚动页面**。缩放不随视图保存（Q51），换一个结果就回到全范围。缩到 2025 年 11 月上旬，读得出双 11 当天的尖峰；缩到 2026 年 2 月中旬，是春节的谷底。
- **图例**：多于一条系列时图例的每一项是一颗开关，点一下（或 Tab 到它按空格）藏起这条系列；刻度与读屏表跟着只量留下的系列。三家店里企业团购店量小，藏起另外两家才看得清它的起伏。
- **较上一期**：时间轴上悬停，提示框在每个数后写它较前一天的变化，不多发查询（Q59）。
- **大数据**：折线多于绘图区宽度的点按 LTTB 采样；多于一千根的柱一条路径画完、不写数。逐时那张是九千多个点（没有单的钟点补 0）；重画的预算与回归护栏由回归孪生在一万个点上量（本机约 186ms，CI 守 1500ms）。
- **花纹**：跟随系统「提高对比度」；宿主用 \`--fve-chart-patterns: on | off\` 钉开或钉关。

回归孪生（\`AnalysisTimeAxis.test.stories.tsx\`）不用这份数据：它自带一年与一万天的每日发货（\`dailyShipments.ts\`），每一天的数只由天数与仓库决定，按值断言。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [retailShell('time-axis')],
  title: 'View Engine/能力/长时间轴',
  component: TimeAxisDemo,
  args: { scene: 'days', patterns: 'auto' },
  argTypes: {
    scene: {
      control: 'inline-radio',
      options: ['days', 'days-bars', 'hours'],
    },
    chart: { control: 'inline-radio', options: [undefined, 'line', 'bar'] },
    patterns: { control: 'inline-radio', options: ['auto', 'on', 'off'] },
  },
} satisfies Meta<typeof TimeAxisDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 25 个月的日 GMV，三家店三条线：滑条、图例开关、提示框的「较上一期」。 */
export const DailyGmv: Story = { args: { scene: 'days' } };

/** 同一段按渠道画成堆叠的柱：约 750 根柱仍一根一个元素，缩到一周读得出每一天。 */
export const DailyGmvBars: Story = { args: { scene: 'days-bars' } };

/**
 * A story of thousands of rows is not judged by axe: its reading table holds
 * every one of them, and axe walking it outlasts the test. The same table at
 * a length of days is judged in the stories above.
 */
const LONG_RUN = { a11y: { test: 'off' } };

/** 近 400 天的逐时 GMV：一条九千多个点的线，按绘图区的宽度采样。 */
export const HourlyGmv: Story = {
  args: { scene: 'hours' },
  parameters: LONG_RUN,
};

/** 逐时画成柱：多于一千根，一条路径画完、不写数。 */
export const HourlyGmvBars: Story = {
  args: { scene: 'hours', chart: 'bar' },
  parameters: LONG_RUN,
};

/** 宿主钉开花纹：每条系列除了颜色还有自己的花纹。 */
export const PatternsPinnedOn: Story = {
  args: { scene: 'days-bars', patterns: 'on' },
};
