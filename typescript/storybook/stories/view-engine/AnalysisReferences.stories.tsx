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
import { SortDirection } from '@ahoo-wang/wow-client';
import type {
  AnalysisViewConfig,
  CartesianSpec,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  YEAR_OF_SHIPMENTS,
  shipmentsDefinition,
  shipmentsView,
} from './dailyShipments.js';
import {
  HOST_LANGUAGE,
  analysisConfig,
  createStoryEngine,
} from './fixtures.js';
import { rowSource } from './rowSource.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 参考与算出的系列（D33 批 B）：华东仓最近 90 天的每日发货金额，一条线或一排
 * 柱，上面画平均线、目标区间、最高与最低点、趋势、移动平均、累计。
 */
type ReferenceScene =
  | 'average-and-target'
  | 'trend-and-moving-average'
  | 'running-total'
  | 'cut-short';

/** The last 90 days of one warehouse: whole, every day measured. */
const NINETY_DAYS = YEAR_OF_SHIPMENTS.filter(
  row => row.warehouse === 'CN-EAST',
).slice(-90);

const SCENES: Record<
  ReferenceScene,
  { type: 'line' | 'bar'; spec: Partial<CartesianSpec>; limit?: number }
> = {
  'average-and-target': {
    type: 'line',
    spec: {
      referenceLines: [
        { axis: 'left', statistic: 'average', metric: 'amount' },
      ],
      referenceBands: [
        { axis: 'left', from: 1000, to: 1400, label: '目标区间' },
      ],
      extremes: true,
    },
  },
  'trend-and-moving-average': {
    type: 'line',
    spec: {
      derived: [
        { kind: 'trend', metric: 'amount' },
        { kind: 'moving-average', metric: 'amount' },
      ],
    },
  },
  'running-total': {
    type: 'bar',
    spec: {
      derived: [{ kind: 'cumulative', metric: 'amount' }],
      extremes: true,
    },
  },
  // Newest first and cut at 30: the result is the last 30 days of 90, so a
  // running total over them would start in the middle of the range.
  'cut-short': {
    type: 'line',
    spec: { derived: [{ kind: 'cumulative', metric: 'amount' }] },
    limit: 30,
  },
};

/** The analysis a scene draws: amount by day, the scene's marks over it. */
function referencesConfig(scene: ReferenceScene): AnalysisViewConfig {
  const { type, spec, limit } = SCENES[scene];
  return analysisConfig({
    layout: 'chart',
    groups: [
      { type: 'DATE_HISTOGRAM', field: 'createdAt', alias: 'day', unit: 'DAY' },
    ],
    metrics: [
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    sort: [
      {
        alias: 'day',
        direction: limit ? SortDirection.DESC : SortDirection.ASC,
      },
    ],
    limit: limit ?? 1_000,
    table: { columns: [] },
    chart: {
      type,
      cartesian: { x: 'day', series: [{ metric: 'amount' }], ...spec },
    },
  });
}

function ReferencesDemo({
  scene = 'average-and-target',
}: {
  scene?: ReferenceScene;
}) {
  const view = shipmentsView(referencesConfig(scene));
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          definitions: [shipmentsDefinition],
          source: rowSource(NINETY_DAYS),
          instances: [view],
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={shipmentsDefinition.id}
          instanceId={view.id}
          {...HOST_LANGUAGE}
          kinds={['analysis']}
        />
      )}
    </StoryEngine>
  );
}

const FIXTURE = '内存 ViewStore · 华东仓最近 90 天的每日发货';

const description = `**分析视图 · 参考与算出的系列**（D33 批 B）

- **参考线**：在一个数值上，或在一个指标的平均值、中位数上——内核只按量到的值算，补出的 0 不算。
- **目标区间**：一根轴上从一个数到另一个数，淡色铺在标记后面。
- **最高点与最低点**：每条系列量到的最高与最低各一个点，旁边写「最高」「最低」和数；堆叠的一段不标。
- **算出的线**：趋势、N 期移动平均、累计，内核沿整条时间轴算，画成前景色虚线、不进堆叠、不占色位；提示框、图例与读屏表都写「（算出的）」。
- **行不完整时不画**（Q53）：结果只显示了前 N 组、「只保留」筛过、时间轴上有不确知的空缺时，这些线不画，图上方写一句原因，显示页里的选项置灰并写同一句。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="references" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/能力/参考与算出的系列',
  component: ReferencesDemo,
  args: { scene: 'average-and-target' },
  argTypes: {
    scene: {
      control: 'inline-radio',
      options: [
        'average-and-target',
        'trend-and-moving-average',
        'running-total',
        'cut-short',
      ],
    },
  },
} satisfies Meta<typeof ReferencesDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 平均线、目标区间、最高与最低点。 */
export const AverageAndTarget: Story = {
  args: { scene: 'average-and-target' },
};

/** 趋势线与 7 期移动平均（按日缺省一周）。 */
export const TrendAndMovingAverage: Story = {
  args: { scene: 'trend-and-moving-average' },
};

/** 柱上的累计线：它坐右轴、自己一把尺子，柱子照样读得出每一天。 */
export const RunningTotal: Story = { args: { scene: 'running-total' } };

/** 只拿回最近 30 天：累计不画，图上写原因（Q53）。 */
export const CutShort: Story = { args: { scene: 'cut-short' } };
