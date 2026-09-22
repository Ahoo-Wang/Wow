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
import { fitChartSlots } from '@ahoo-wang/fetcher-view-engine';
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  HOST_LANGUAGE,
  analysisConfig,
  createStoryEngine,
  expandableOrdersDefinition,
  overviewDefinition,
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
  records = false,
  expandable = false,
  allColumns = false,
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
   * 这个工作台是不是也列记录视图。列了，追问菜单才有「查看这些记录」——
   * 下钻开出来的是一个记录视图，只在 record 也在 `kinds` 里时开得出来。
   */
  records?: boolean;
  /**
   * 这份定义声明不声明一条展开链（D20 屏 G）。声明了，托盘里才有「展开」
   * 那一槽；它换的是定义而不是配置，因为链是能力说了算的。
   */
  expandable?: boolean;
  /**
   * 表列不再逐条声明，而是「有什么别名画什么」。托盘里新加的指标因此
   * 当场多出一列——声明过列的视图只画声明过的那几列，那是作者的选择，
   * 但它也让「加一条指标」在屏幕上什么也不发生。
   */
  allColumns?: boolean;
  /**
   * A row limit the four warehouses can actually hit. Ordering the result
   * makes which rows survive the cut a decision rather than an accident.
   */
  limit?: number;
}) {
  const { groups, metrics } = analysisConfig();
  const fitted = fitChartSlots({ type: chart }, groups, metrics);
  const config = analysisConfig({
    layout,
    ...(limit === undefined
      ? {}
      : {
          limit,
          sort: [{ alias: 'amount', direction: SortDirection.DESC }],
        }),
    // The family comes from the same fitting a press of the chart-type
    // control goes through, and the knobs below only vary what they say they
    // vary. A story that wrote the sub-object by hand would be a story of a
    // config no user can reach — and it would have gone on passing while a
    // real switch left the chart without a family at all.
    chart: {
      ...fitted,
      ...(fitted.pie
        ? {
            // Money rather than the count, and four warehouses into three
            // slices: the smallest two merge into "other".
            pie: { ...fitted.pie, value: 'amount', maxSlices: 3 },
          }
        : {
            cartesian: {
              ...fitted.cartesian!,
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
      columns: allColumns
        ? []
        : [{ alias: 'warehouse' }, { alias: 'orders' }, { alias: 'amount' }],
      totals: true,
    },
  });

  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          instances: [{ ...savedViews[1], config }],
          ...(expandable
            ? {
                definitions: [expandableOrdersDefinition, overviewDefinition],
              }
            : {}),
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={savedViews[1].id}
          {...HOST_LANGUAGE}
          kinds={records ? ['record', 'analysis'] : ['analysis']}
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
  title: 'View Engine/分析视图/分析工作台',
  component: AnalysisWorkbenchDemo,
  args: {
    behaviour: 'data',
    layout: 'chart',
    chart: 'bar',
    series: 'amount',
    pinned: false,
    records: false,
    expandable: false,
    allColumns: false,
  },
  argTypes: {
    limit: { table: { disable: true } },
    allColumns: { control: 'boolean' },
    records: { control: 'boolean' },
    expandable: { control: 'boolean' },
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
 * 追问（D20 Ⅳ）：按下一根柱子——或表格布局里的一行——弹出三项，
 * 「查看这些记录」「再按…拆一层」「只看这一组」。这个工作台同时列着记录视图，
 * 所以第一项在：它在同一个工作台里开出一个未保存的记录视图，标题栏下多一条
 * 「返回／来自」。另外两项改的是当前这个分析视图。
 */
export const FollowUps: Story = {
  args: { layout: 'chart', chart: 'bar', records: true },
};

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
 * 四个仓库、上限两行：引擎多要一行（发出去的 `limit` 是 3），第三行回来了，
 * 于是"还有更多未列出"是问出来的答案而不是猜的——那一行只回答问题，不上屏。
 * 饼图是最坏的一种：每个扇区的占比都是拿"已显示的部分"当分母算出来的，所以
 * 上方多一条 warning。
 */
export const CutShort: Story = {
  args: { layout: 'chart', chart: 'pie', limit: 2 },
};

/**
 * The same cut, as rows: the table says it too, with the same one line — and
 * the totals row under it still covers every order, because it comes from its
 * own ungrouped query. Hover it to read the scope.
 */
export const CutShortTable: Story = { args: { layout: 'table', limit: 2 } };

/** An aggregation that matched nothing still has its editor. */
export const EmptyResult: Story = {
  args: { behaviour: 'empty', layout: 'table' },
};

/** A failed aggregation keeps the configuration on screen. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/**
 * 一份声明了展开链的定义：托盘里多出「展开」那一槽（D20 屏 G）。展开改的是
 * 「数的是什么」——展开到明细项，问题就是关于明细项的，仓库那个维度跟着离开。
 * 故事的数据源不求值 `elements`，所以这个故事到托盘为止，不按「应用」。
 */
export const Expandable: Story = {
  args: { layout: 'table', expandable: true },
};
