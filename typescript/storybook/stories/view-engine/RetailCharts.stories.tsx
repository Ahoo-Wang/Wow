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

- **箱线图**：各仓付款到发货的小时数，最短、P25、中位数、P75、最长；四分位与中位数是 Wow 的近似百分位，图上写明。托盘里任一个「付款到发货」指标卡的菜单都有「补齐箱线图的五个数」。
- **K 线图**：近 12 周每周成交单价的开、高、低、收：开与收是那一周最早、最晚那一单的实付（期初值、期末值，FIRST／LAST），高低是最大、最小值。收高的一周用「涨」色、收低的用「跌」色，跟宿主的涨跌配色（\`data-fve-change-colors\`，红涨绿跌时颜色对调）；读屏表与提示框另用文字写收高、收低。托盘里「实付」的期初值、期末值、最大、最小任一张指标卡的菜单都有「补齐 K 线的四个数」。
- **刻度盘**：本月 GMV 在月目标上的位置，目标一根刻线。和指标卡的区别写在图型磁贴上：指标卡说「数字与变化」，刻度盘说「在刻度上的位置」。
- **雷达图**：各渠道近 3 个月的单量、GMV、退款额，每根轴各自的刻度。
- **平行坐标图**：各省份一条线跨三根轴；多于 8 条时同一种颜色、半透明，悬停读出是哪个省。
- **旭日图**：近 12 个月实付的品类构成，里圈一级类目、外圈二级类目；按下外圈一段「查看这些记录」。
- **树图**：同一个问题画成从左到右的分解，每个子类写着自己的数。
- **桑基图**：近 3 个月的 GMV 从渠道流向支付方式，带的宽度就是那一对的 GMV；按下一条带「查看这些记录」。
- **日历热力图**：25 个月里每天的 GMV，一年一块；双 11 与年货节最深，没有订单的日子没有格子。
- **河流图**：近 12 个月各渠道每周的 GMV 叠成一条河，直播间从细流涨成主干。`;

const meta = {
  title: 'View Engine/业务场景/图型陈列',
  component: ChartScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [retailShell('retail-analysis')],
} satisfies Meta<typeof ChartScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Boxplot: Story = {
  name: '箱线图：各仓付款到发货',
  args: { view: 'boxplot' },
};

export const Candlestick: Story = {
  name: 'K 线图：每周成交单价',
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
