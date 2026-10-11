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

import { redUp } from './changeColors.js';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { CHART_VIEWS, CHART_VIEW_IDS } from './retail/chartViews.js';
import { RETAIL_DATA_NOTE, retailShell } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import { retailOrderAnalysisDefinition } from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';

type ChartView = keyof typeof CHART_VIEW_IDS;

/**
 * The analysis workbench opened on one saved analysis of the chart
 * gallery: the chart is the answer, the view list holds the rest of the
 * gallery, and the visualization panel says what else the result can be.
 */
function ChartScene({ view }: { view: ChartView }) {
  return (
    <StoryEngine
      key={view}
      create={() =>
        createRetailEngine([retailOrderAnalysisDefinition], CHART_VIEWS)
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={retailOrderAnalysisDefinition.id}
          instanceId={CHART_VIEW_IDS[view]}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const description = `**业务场景 · 图型陈列**

零售订单上每种统计与结构图型各一个真问题（view-engine D41）：一张已存分析一种图，打开就是答案。可视化面板的「适合这个结果」「其他图型」说明这个结果还能画成什么、为什么别的不行。

${RETAIL_DATA_NOTE}

- **两个时刻之差**（N3）：付款到发货的小时数不再读读模型预先算好的字段，而是查询时现算（DATE_DIFF，付款时间 → 发货时间，以小时计）。箱线图的五个数、直方分组都读它；托盘里「添加指标」→「两个时刻之差」能自己搭一个，卡片菜单「按这个时长分区间」把它变成维度。
- **条件里的两个时刻之差**：「发货时间 距 付款时间 > 48 小时」写在范围里（EXPRESSION，N3），按仓库数付款后超过 48 小时才发货的单；在条件编辑器里给任一个时间字段选「距另一时刻」就能自己写。
- **直方分组**：付款到发货落在哪一档，每 4 小时一档；48 小时以后的几档就是超时发货。
- **箱线图**：各仓付款到发货的小时数，最短、P25、中位数、P75、最长；四分位与中位数是 Wow 的近似百分位，图上写明。托盘里任一个「付款到发货」指标卡的菜单都有「补齐箱线图的五个数」。
- **K 线图**：近 12 周里一件商品（长绒棉毛巾 单只装，每周都有成交）的成交单价，每周一根：开与收是那一周最早、最晚那一行的单价（期初值、期末值，FIRST／LAST），高低是那一周单价的最高、最低。是单价而不是每单实付——一单买几件、凑什么单都不影响它，大促那几周直降加深，K 线就往下走。场景按国内习惯红涨绿跌（\`data-fve-change-colors="red-up"\`）；读屏表与提示框另用文字写收高、收低。托盘里任一张「成交单价」期初值、期末值、最大、最小的指标卡，菜单都有「补齐 K 线的四个数」。
- **刻度盘**：本月 GMV 在月目标上的位置，目标一根刻线。和指标卡的区别写在图型磁贴上：指标卡说「数字与变化」，刻度盘说「在刻度上的位置」。
- **雷达图**：各渠道近 3 个月的退款率、直降占比、老客占比与取消率——四根比率轴，比的是轮廓，不是规模。
- **平行坐标图**：各省份一条线跨三根轴；多于 8 条时同一种颜色、半透明，悬停读出是哪个省。
- **旭日图**：近 12 个月实付的品类构成，里圈一级类目、外圈二级类目；按下外圈一段「查看这些记录」。
- **树图**：同一个问题画成从左到右的分解，每个子类写着自己的数。
- **桑基图**：近 3 个月的 GMV 从渠道流向支付方式，带的宽度就是那一对的 GMV；按下一条带「查看这些记录」。
- **日历热力图**：2025 年起每天的 GMV，一年一块（2025 年整年、2026 年到 9 月）；双 11 与年货节最深，没有订单的日子没有格子。色阶是线性的，双 11 那一格是全年最深，其余日子相对浅——日历图要的正是让那一天一眼可见。
- **河流图**：近 12 个月各渠道每周的 GMV 叠成一条河，直播间从细流涨成主干。`;

const meta = {
  title: 'View Engine/业务场景/图型陈列',
  component: ChartScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  // Rising in red, as the Chinese market the data is set in reads a
  // candle (Storybook review P2), and as 「图型全景」 already draws it.
  beforeEach: () => redUp(),
  decorators: [retailShell('retail-analysis')],
} satisfies Meta<typeof ChartScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Boxplot: Story = {
  name: '箱线图：各仓付款到发货',
  args: { view: 'boxplot' },
};

export const DurationBands: Story = {
  name: '直方分组：付款到发货的时长',
  args: { view: 'durationBands' },
};

export const LateShipments: Story = {
  name: '条件：付款后超过 48 小时才发货',
  args: { view: 'lateShipments' },
};

export const Candlestick: Story = {
  name: 'K 线图：一件商品每周的成交单价',
  args: { view: 'candlestick' },
};

export const Gauge: Story = {
  name: '刻度盘：本月 GMV 达成',
  args: { view: 'gauge' },
};

export const Radar: Story = {
  name: '雷达图：各渠道的经营轮廓',
  args: { view: 'radar' },
};

export const Parallel: Story = {
  name: '平行坐标图：各省份',
  args: { view: 'parallel' },
};

export const Sunburst: Story = {
  name: '旭日图：品类 → 子类',
  args: { view: 'sunburst' },
};

export const Tree: Story = {
  name: '树图：品类 → 子类',
  args: { view: 'tree' },
};

export const Sankey: Story = {
  name: '桑基图：渠道 → 支付方式',
  args: { view: 'sankey' },
};

export const Calendar: Story = {
  name: '日历热力图：每日 GMV',
  args: { view: 'calendar' },
};

export const ThemeRiver: Story = {
  name: '河流图：各渠道每周 GMV',
  args: { view: 'themeRiver' },
};
