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
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  HOST_LANGUAGE,
  analysisConfig,
  createStoryEngine,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * Colours a spec pins by name: a category value for a pie's slice, a series
 * alias for a cartesian mark. The theme's palette fills every other slot.
 */
const PINNED_COLORS = { 'CN-SOUTH': '#7c3aed', amount: '#0f766e' };

/**
 * The Analysis workbench. Table and chart are two layouts of one saved
 * config, and switching between them is a new execution rather than a redraw,
 * because the kernel shapes a chart only when the config that ran asked for one.
 */
function AnalysisWorkbenchDemo({
  behaviour = 'data',
  layout = 'chart',
  chart = 'bar',
  series = 'amount',
  pinned = false,
  limit,
}: {
  behaviour?: SourceBehaviour;
  layout?: 'table' | 'chart';
  chart?: 'bar' | 'line' | 'pie';
  /** Which metrics a cartesian chart draws; two of them earn a legend. */
  series?: 'amount' | 'both';
  /** Whether the spec pins 华南 and the amount series to colours of their own. */
  pinned?: boolean;
  /**
   * A row limit the four warehouses can actually hit. Ordering the result
   * makes which rows survive the cut a decision rather than an accident.
   */
  limit?: number;
}) {
  const config = analysisConfig({
    layout,
    ...(limit === undefined
      ? {}
      : {
          limit,
          sort: [{ alias: 'amount', direction: SortDirection.DESC }],
        }),
    chart: {
      ...(chart === 'pie'
        ? {
            type: 'pie' as const,
            // Four warehouses, three slices: the smallest two merge into "other".
            pie: { category: 'warehouse', value: 'amount', maxSlices: 3 },
          }
        : {
            type: chart,
            cartesian: {
              x: 'warehouse',
              // Order counts are single digits beside amounts in the
              // thousands, so the second metric is measured on its own axis.
              series:
                series === 'both'
                  ? [
                      { metric: 'amount' },
                      { metric: 'orders', axis: 'right' as const },
                    ]
                  : [{ metric: 'amount' }],
            },
          }),
      ...(pinned ? { colors: PINNED_COLORS } : {}),
    },
    table: {
      columns: [
        { alias: 'warehouse' },
        { alias: 'orders' },
        { alias: 'amount' },
      ],
      totals: true,
    },
  });

  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          instances: [{ ...savedViews[1], config }],
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={savedViews[1].id}
          {...HOST_LANGUAGE}
          kinds={['analysis']}
        />
      )}
    </StoryEngine>
  );
}

const scene = {
  ...viewEngineScene,
  domain: '分析视图',
  summary: '分组与指标进去，图表或表格出来。',
  fixture: '内存 ViewStore · 四个仓库的聚合结果',
  setup: '每次挂载都新建引擎与存储；分组、指标与图型来自保存的配置。',
  observe: '切换 Table／Chart 会重新执行，因为图表整形发生在投影层。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/分析视图/Analysis 工作台',
  component: AnalysisWorkbenchDemo,
  args: {
    behaviour: 'data',
    layout: 'chart',
    chart: 'bar',
    series: 'amount',
    pinned: false,
  },
  argTypes: {
    limit: { table: { disable: true } },
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing'],
    },
    layout: { control: 'inline-radio', options: ['table', 'chart'] },
    chart: { control: 'inline-radio', options: ['bar', 'line', 'pie'] },
    series: { control: 'inline-radio', options: ['amount', 'both'] },
    pinned: { control: 'boolean' },
  },
} satisfies Meta<typeof AnalysisWorkbenchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The default: a bar chart of one metric across four warehouses. */
export const BarChart: Story = { args: { layout: 'chart', chart: 'bar' } };

/**
 * Two metrics on one chart: a series each, the count on a right-hand axis,
 * and the legend a cartesian chart shows only once it has more than one.
 */
export const TwoMetrics: Story = {
  args: { layout: 'chart', chart: 'bar', series: 'both' },
};

/** The same result as rows, with the totals row from its own ungrouped query. */
export const TableWithTotals: Story = { args: { layout: 'table' } };

/** A pie needs a category and one value, and the kernel merges the tail. */
export const PieChart: Story = { args: { layout: 'chart', chart: 'pie' } };

/**
 * `chart.colors` names a category — 华南 — and the slice takes that colour
 * while the other slices keep their palette slots. Flip to a bar to see the
 * same map colour a series by its alias instead.
 */
export const PinnedCategoryColor: Story = {
  args: { layout: 'chart', chart: 'pie', pinned: true },
};

/**
 * 四个仓库、上限两行：结果正好填满上限，分组可能还没画完。饼图是最坏的一种
 * ——每个扇区的占比都是拿"已显示的部分"当分母算出来的——所以上方多一条
 * warning，把"可能被截断"说成"可能"，因为聚合只回答了行数，没说它省略了多少。
 */
export const CutShort: Story = {
  args: { layout: 'chart', chart: 'pie', limit: 2 },
};

/** The same cut, as rows: the table says it too, with the same one line. */
export const CutShortTable: Story = { args: { layout: 'table', limit: 2 } };

/** An aggregation that matched nothing still has its editor. */
export const EmptyResult: Story = {
  args: { behaviour: 'empty', layout: 'table' },
};

/** A failed aggregation keeps the configuration on screen. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };
