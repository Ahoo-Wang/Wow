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
import {
  fitChartSlots,
  type AnalysisViewConfig,
} from '@ahoo-wang/fetcher-view-engine';
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  analysisConfig,
  createStoryEngine,
  datedOrdersDefinition,
  expandableOrdersDefinition,
  failedEventsDefinition,
  failedEventsSource,
  failedEventsView,
  overviewDefinition,
  savedViews,
  waybillAnalysisDefinition,
  waybillAnalysisView,
  waybillSource,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
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
  visualization = true,
  latest = false,
  limit,
  kept,
  waybills,
  failures,
  savedFunnel,
  labels = false,
  heatmap = false,
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
   * 宿主让不让配图（`WorkbenchFeatures.visualization`）。关掉之后结果工具栏上
   * 的「可视化」按钮与左侧栏那块面板一起不在——关掉的功能是不存在，而不是置灰。
   */
  visualization?: boolean;
  /**
   * 每个仓库最晚的一单：指标是创建时间的最大值，也就是一个时刻。它读作日期
   * 时间、表头说「最晚」，按它排序是「最近的在前」；图形不量它——柱子画的是
   * 订单数，它留在表里。
   */
  latest?: boolean;
  /**
   * A row limit the four warehouses can actually hit. Ordering the result
   * makes which rows survive the cut a decision rather than an accident.
   */
  limit?: number;
  /**
   * 存成「只保留 金额的合计 大于 kept」的视图（Wow `having`）。打开时托盘收着，
   * 于是被筛掉的组只能由结果第一行的读法说出来。
   */
  kept?: number;
  /**
   * 换成 50 行运单问的那几个问题（`waybillScene`）：按日倒序的单数画成柱或
   * 指标卡的迷你趋势，或十个目的城市的运费画成饼。订单只有四个仓库、七单，
   * 「时间朝哪边走」与「第九种颜色」都问不出来。
   */
  waybills?: WaybillScene;
  /**
   * 换成十个失败事件问的问题（`failuresScene`）：按处理器数失败次数——名字有
   * 长有短，前两组降序是两个长名字、升序是两个短的——或按聚合 ID 分组，一串
   * 要一个字一个字抄走的码。
   */
  failures?: FailuresScene;
  /**
   * 存成一个漏斗的视图：按仓库分阶段，量这些阶段的是 `value`，阶段是 `order`。
   * 一个阶段的漏斗、量平均数的漏斗都是早先存得下、如今画不出的样子——打开它
   * 什么也不跑，从状态行进图型网格修。
   */
  savedFunnel?: { value: 'orders' | 'amount'; order: string[] };
  /** Whether the chart writes each value over its mark (`ChartSpec.labels`). */
  labels?: boolean;
  /**
   * 仓库 × 状态的热力图：两个维度、一个金额合计，格子深浅按金额，底下一条色标
   * （D21 第四批）。
   */
  heatmap?: boolean;
}) {
  const { groups, metrics } = analysisConfig();
  const fitted = fitChartSlots({ type: chart }, groups, metrics);
  const saved = analysisConfig({
    layout,
    ...(limit === undefined
      ? {}
      : {
          limit,
          sort: [{ alias: 'amount', direction: SortDirection.DESC }],
        }),
    ...(kept === undefined
      ? {}
      : {
          having: {
            type: 'CONDITION' as const,
            metric: 'amount',
            operator: 'GT' as const,
            value: kept,
          },
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
      ...(labels ? { labels: true } : {}),
    },
    table: {
      // The list orders the columns and nothing more: a dimension or metric
      // added in the tray is appended after these (audit P0-1).
      columns: [
        { alias: 'warehouse' },
        { alias: 'orders' },
        { alias: 'amount' },
      ],
      totals: true,
    },
  });
  const config = heatmap
    ? heatmapConfig(layout, labels)
    : latest
      ? latestConfig(layout)
      : savedFunnel
        ? {
            ...saved,
            chart: {
              type: 'funnel' as const,
              funnel: {
                stages: {
                  from: 'group' as const,
                  category: 'warehouse',
                  ...savedFunnel,
                },
              },
            },
          }
        : saved;

  if (failures) {
    const view = failedEventsView(failuresScene(failures));
    return (
      <StoryEngine
        create={() =>
          createStoryEngine({
            behaviour,
            definitions: [failedEventsDefinition, overviewDefinition],
            source: failedEventsSource(behaviour),
            instances: [view],
          })
        }
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId={failedEventsDefinition.id}
            instanceId={view.id}
            {...HOST_LANGUAGE}
            kinds={['analysis']}
            features={{ visualization }}
          />
        )}
      </StoryEngine>
    );
  }

  if (waybills) {
    const scene = waybillScene(waybills, layout);
    const view = waybillAnalysisView(
      labels ? { ...scene, chart: { ...scene.chart, labels: true } } : scene,
    );
    return (
      <StoryEngine
        create={() =>
          createStoryEngine({
            behaviour,
            definitions: [waybillAnalysisDefinition, overviewDefinition],
            source: waybillSource(behaviour),
            instances: [view],
          })
        }
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId={waybillAnalysisDefinition.id}
            instanceId={view.id}
            {...HOST_LANGUAGE}
            kinds={['analysis']}
            features={{ visualization }}
          />
        )}
      </StoryEngine>
    );
  }

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
            : latest
              ? { definitions: [datedOrdersDefinition, overviewDefinition] }
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
          features={{ visualization }}
        />
      )}
    </StoryEngine>
  );
}

/** Orders by warehouse and status, as a heatmap. */
function heatmapConfig(layout: 'table' | 'chart', labels: boolean) {
  const groups = [
    { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
    { alias: 'status', field: 'status', type: 'TERMS' },
  ] satisfies AnalysisViewConfig['groups'];
  // The amounts differ cell to cell where the counts are all one.
  const metrics = [
    {
      alias: 'amount',
      type: 'NUMERIC',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'amount' },
    },
  ] satisfies AnalysisViewConfig['metrics'];
  return analysisConfig({
    layout,
    groups,
    metrics,
    table: { columns: [] },
    chart: {
      ...fitChartSlots({ type: 'heatmap' }, groups, metrics),
      ...(labels ? { labels: true } : {}),
    },
  });
}

/** The order count and the latest order per warehouse, the latest first. */
function latestConfig(layout: 'table' | 'chart') {
  const { groups } = analysisConfig();
  const metrics = [
    { alias: 'orders', type: 'COUNT' },
    {
      alias: 'latest',
      type: 'NUMERIC',
      function: 'MAX',
      expression: { type: 'FIELD', field: 'createdAt' },
    },
  ] satisfies AnalysisViewConfig['metrics'];
  return analysisConfig({
    layout,
    metrics,
    sort: [{ alias: 'latest', direction: SortDirection.DESC }],
    table: { columns: [] },
    // The latest is a moment, which no mark measures: the bars count orders.
    chart: fitChartSlots({ type: 'bar' }, groups, metrics, new Set(['latest'])),
  });
}

type FailuresScene = 'processor' | 'aggregate';

/**
 * 失败事件上的两个问题，都画成表。
 *
 * - `processor`：每个处理器失败几次、重试了几次，**失败最多的前两组**。按表头
 *   把次数改成升序，留下的就是失败最少的两组——名字从三十几个字母变成五六个，
 *   列一格也不该挪。
 * - `aggregate`：每个聚合失败几次。聚合 ID 在记录视图里读作可复制的值，在这里
 *   用同一个等宽字。
 */
function failuresScene(scene: FailuresScene): AnalysisViewConfig {
  const byProcessor = scene === 'processor';
  const groups = [
    byProcessor
      ? { type: 'TERMS', field: 'processor', alias: 'processor' }
      : { type: 'TERMS', field: 'aggregateId', alias: 'aggregate' },
  ] satisfies AnalysisViewConfig['groups'];
  const failures = { alias: 'failures', type: 'COUNT' } as const;
  const retries = {
    alias: 'retries',
    type: 'NUMERIC',
    function: 'SUM',
    expression: { type: 'FIELD', field: 'retries' },
  } as const;
  const metrics: AnalysisViewConfig['metrics'] = byProcessor
    ? [failures, retries]
    : [failures];
  return analysisConfig({
    layout: 'table',
    groups,
    metrics,
    sort: [{ alias: 'failures', direction: SortDirection.DESC }],
    limit: byProcessor ? 2 : 100,
    table: { columns: [] },
    chart: fitChartSlots({ type: 'bar' }, groups, metrics),
  });
}

type WaybillScene = 'daily' | 'daily-card' | 'cities' | 'bands';

/**
 * 运单上的四个问题。
 *
 * - `daily`／`daily-card`：每天几单，**按日倒序**——表格要今天在最上面，这是
 *   这类视图最常见的存法（补偿服务的「每日新增失败」就是这样存的）。同一批行
 *   画成柱或迷你趋势，时间轴仍从左往右走：图按时间排，表按视图排。
 * - `cities`：十个目的城市的运费合计。色板八色，第九片会与第一片同色，所以
 *   饼图在第八片把尾巴并进灰色的「其他」。
 * - `bands`：运费按 500 一档分组，每档几单。一个桶的键是那一档的下界，每一
 *   行、每根柱读成「¥0～500」这样的一段，而不是「¥0.00」。
 */
function waybillScene(
  scene: WaybillScene,
  layout: 'table' | 'chart',
): AnalysisViewConfig {
  if (scene === 'bands') {
    const groups = [
      { type: 'HISTOGRAM', field: 'amount', alias: 'band', interval: 500 },
    ] satisfies AnalysisViewConfig['groups'];
    const metrics = [
      { alias: 'waybills', type: 'COUNT' },
    ] satisfies AnalysisViewConfig['metrics'];
    return analysisConfig({
      layout,
      groups,
      metrics,
      sort: [{ alias: 'band', direction: SortDirection.ASC }],
      table: { columns: [] },
      chart: fitChartSlots({ type: 'bar' }, groups, metrics),
    });
  }
  if (scene === 'cities') {
    const groups = [
      { type: 'TERMS', field: 'destination', alias: 'city' },
    ] satisfies AnalysisViewConfig['groups'];
    const metrics = [
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ] satisfies AnalysisViewConfig['metrics'];
    return analysisConfig({
      layout,
      groups,
      metrics,
      sort: [{ alias: 'amount', direction: SortDirection.DESC }],
      table: { columns: [] },
      chart: fitChartSlots({ type: 'pie' }, groups, metrics),
    });
  }
  const groups = [
    { type: 'DATE_HISTOGRAM', field: 'createdAt', alias: 'day', unit: 'DAY' },
  ] satisfies AnalysisViewConfig['groups'];
  const metrics = [
    { alias: 'waybills', type: 'COUNT' },
  ] satisfies AnalysisViewConfig['metrics'];
  return analysisConfig({
    layout,
    groups,
    metrics,
    sort: [{ alias: 'day', direction: SortDirection.DESC }],
    limit: 30,
    table: { columns: [] },
    chart: fitChartSlots(
      { type: scene === 'daily' ? 'bar' : 'metric' },
      groups,
      metrics,
    ),
  });
}

/** What the scenes answer from, said in the host's service line and below. */
const FIXTURE = '内存 ViewStore · 四个仓库的聚合结果';

/**
 * What the scene is, on the docs page rather than above the workbench: the
 * workbench sits in the host application (`AppShell`), as it would in a
 * product, and has the page area to itself.
 */
const description = `**分析视图 · 分析工作台**

分组与指标进去，图表或表格出来。

- **数据源**：${FIXTURE}。
- **准备**：每次挂载都新建引擎与存储；分组、指标与图型来自保存的配置。
- **操作**：打开任一场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：切换 Table／Chart 会重新执行，因为图表整形发生在投影层。`;

const meta = {
  parameters: {
    // The workbench fills the host's page area, as it would a screen.
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="analysis" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
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
    visualization: true,
    latest: false,
    labels: false,
    heatmap: false,
  },
  argTypes: {
    heatmap: { control: 'boolean' },
    labels: { control: 'boolean' },
    latest: { control: 'boolean' },
    limit: { table: { disable: true } },
    kept: { table: { disable: true } },
    savedFunnel: { table: { disable: true } },
    waybills: {
      control: 'inline-radio',
      options: [undefined, 'daily', 'daily-card', 'cities', 'bands'],
    },
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
    visualization: { control: 'boolean' },
  },
} satisfies Meta<typeof AnalysisWorkbenchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The default: a bar chart of one metric across four warehouses. */
export const BarChart: Story = { args: { layout: 'chart', chart: 'bar' } };

/**
 * 追问（D20 Ⅳ）：按下一根柱子——或表格布局里的一行——弹出三项，
 * 「查看这些记录」「按其他维度细分…」「只看这一组」。这个工作台同时列着记录视图，
 * 所以第一项在：它在同一个工作台里开出一个未保存的记录视图，叫「订单 · 这一组」，
 * 标题栏下一颗「返回」。另外两项同样开在旁边、同样能返回，原来那个视图不变脏。
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

/**
 * 宿主关掉了可视化（D18 Ⅺ）：结果照它保存的样子画，工具栏上没有「可视化」
 * 按钮，左侧栏也没有那块面板——关掉的功能不存在，而不是置灰。
 */
export const NoVisualization: Story = {
  args: { layout: 'chart', chart: 'bar', visualization: false },
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

/**
 * 每个仓库最晚的一单（生产审查）：创建时间的最大值是一个时刻，读作界面
 * 语言与时区下的日期时间，而不是十三位毫秒；表头说「创建时间的最晚」。
 * 切到图表，柱子量的是订单数——时刻没有零点可以让柱子从那里长。
 */
/**
 * 失败最多的两个处理器（2026-09-23 审查 P1）：名字很长，表一打开就合身；按表头
 * 把次数改成升序，留下失败最少的两个，名字只有几个字母——列宽是第一次画时量
 * 的，之后钉住，列一格不挪。
 */
export const FailingProcessors: Story = { args: { failures: 'processor' } };

/**
 * 每个聚合失败几次：聚合 ID 在记录视图里读作可复制的值，这里用与它同一个
 * 等宽字，0 和 O、l 和 1 分得开，一列码上下对齐。
 */
export const FailingAggregates: Story = { args: { failures: 'aggregate' } };

export const LatestPerWarehouse: Story = {
  args: { layout: 'table', latest: true },
};

/**
 * 第一次的答案还在路上（数据源慢 1.5 秒）：结果区先画出答案的形状——表格是
 * 几行灰条，图表是一块绘图区——工具栏、条件带与页脚已经在各自的位置上，行落地
 * 时换的是框里的内容，不是任何东西的位置。
 */
export const Loading: Story = { args: { behaviour: 'slow', layout: 'table' } };

/** 同上，保存的是图表：骨架是一块绘图区。 */
export const LoadingChart: Story = {
  args: { behaviour: 'slow', layout: 'chart' },
};

/**
 * 每天几单，按日倒序存着——表格今天在最上面。画成柱，时间仍从左往右：
 * 投影层按时间排时间轴，表格留着视图自己的排序（2026-09-23 审查）。
 */
export const DailyNewestFirst: Story = {
  args: { layout: 'chart', waybills: 'daily' },
};

/**
 * 三十天的柱，每根柱上写着它的数：写得下的都写，会压到别的数上的那一个不写
 * ——而不是叠在一起（ECharts 的 `labelLayout.hideOverlap`，D21）。数写得短，
 * 与刻度同一个读法。
 */
export const ValueLabels: Story = {
  args: { layout: 'chart', waybills: 'daily', labels: true },
};

/**
 * 仓库 × 状态的热力图：格子铺满绘图区、第一行在上，深浅按金额，底下一条色标
 * 读得回数；格子上写着金额（从前是挤在一角的灰格子，没有色标也没有数）。
 */
export const HeatmapChart: Story = {
  args: { layout: 'chart', heatmap: true, labels: true },
};

/**
 * 两个指标画成折线：每个点一颗圆点，线的两端各离绘图区的边半格，金额在左轴、
 * 订单数在右轴，两根轴各有标题（D21 第二批）。
 */
export const LineChart: Story = {
  args: { layout: 'chart', chart: 'line', series: 'both' },
};

/**
 * 只剩一组时柱子也只有它该有的宽：从前一组就是一整块铺满绘图区的色板
 * （定价「按状态分布」，真实后端 2026-09-23）。
 */
export const OneBar: Story = { args: { layout: 'chart', limit: 1 } };

/** 同一个按日倒序的问题画成指标卡：迷你趋势同样从最早的一天画起。 */
export const DailyTrendCard: Story = {
  args: { layout: 'chart', waybills: 'daily-card' },
};

/**
 * 十个目的城市的运费：色板八色，饼图画出七个城市加一片灰色的「其他」，
 * 八片八种颜色——从前色板只有五色，第六片起与前面的同色。
 */
export const TenCities: Story = {
  args: { layout: 'chart', waybills: 'cities' },
};

/**
 * 运费区间：按 500 一档，每档几单。一档的键只是它的下界，从前读成「¥0.00」
 * 「¥500.00」，说不出是哪一段（2026-09-23 真实后端走查）；现在横轴、提示、
 * 读屏表、表格与追问菜单的标题都读成「¥0～500」，按界面语言写短（万、亿）。
 */
export const FreightBands: Story = {
  args: { layout: 'chart', waybills: 'bands' },
};

/**
 * An aggregation that matched no group keeps its toolbar. With no condition
 * in force the range is already every record, so there is nothing to change
 * in the tray: the empty result says why, and offers no button.
 */
export const EmptyResult: Story = {
  args: { behaviour: 'empty', layout: 'table' },
};

/**
 * A failed aggregation keeps the toolbar and the conditions on screen, and
 * says the failure under the toolbar with 「重试」.
 */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/**
 * 一份声明了展开链的定义：托盘里多出「展开」那一槽（D20 屏 G）。展开改的是
 * 「数的是什么」——展开到明细项，问题就是关于明细项的，仓库那个维度跟着离开。
 * 故事的数据源不求值 `elements`，所以这个故事到托盘为止，不按「应用」。
 */
export const Expandable: Story = {
  args: { layout: 'table', expandable: true },
};
