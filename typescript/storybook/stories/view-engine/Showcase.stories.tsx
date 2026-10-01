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
import { AppShell } from '../shared/AppShell.js';
import { redUp } from './changeColors.js';
import { registerChinaMap } from './chinaProvinces.js';
import { RetailBoardScene } from './retail/RetailHost.js';
import { SHOWCASE, type Tab } from './retail/showcase.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import sceneSource from './Showcase.stories.tsx?raw';
import hostShell from './retail/RetailHost.tsx?raw';
import boardSource from './retail/showcase.ts?raw';
import { hostSource } from './hostSource.js';
import { BOARD_FIXTURE } from './retail/scene.js';
import { GUIDE_COUNTS } from './retail/guide.js';

/**
 * 图型全景 in the dashboard workbench: every one of the engine's chart
 * types once, each titled with the question it answers, on four tabs.
 */
function Showcase({ tab }: { tab?: Tab }) {
  return <RetailBoardScene instanceId={SHOWCASE} initialTab={tab} />;
}

const FIXTURE = BOARD_FIXTURE;

const description = `**业务场景 · 图型全景**

一块看板，引擎的 ${GUIDE_COUNTS.chartTypes} 种图型各用一次，每张图的标题就是它回答的分析问题（\`retail/showcase.ts\`）。第二轮审查里「能回答」的图引用分析工作台与图型陈列里已存的分析；「部分／不能」的换成了它真正答得了的问题，建成板上自有的面板：只取完整月、完整周，雷达的四根轴都是比率，平行坐标只画 5 个有名字的渠道，K 线看近一年卖得最多的一件商品每周的成交单价。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-22 上午 10 点。
- **筛选**：下单时间（可选，不设就各图读各自问题的范围）、渠道、省份、支付方式。
- **联动**：点地图上的一个省或「哪些省份买得最多」的一根柱，整板按省份筛选；点支付方式的一块饼，整板按支付方式筛选。
- **走势**：本月至今较上月同期（指标卡）、上月 GMV 达成（刻度盘，目标写在标题里）、完整月的 GMV 与客单价（组合）、25 个月的日 GMV 与日目标区间（折线）、新客 GMV 占比（百分比面积）、各渠道每周 GMV（河流）、近 12 个完整月的每日 GMV（日历）、一件商品的周 K 线（红涨绿跌，页面挂 \`data-fve-change-colors="red-up"\`）。
- **构成**：支付方式（饼）、上月净销售额的渠道累加（瀑布）、品类（矩形树图）、品类 → 子类（旭日、树图）、渠道 → 支付方式（桑基）。
- **分布与关系**：省份前 15（横向柱）、各仓付款到发货（箱线）、直播间优惠 × 退款（散点）、星期 × 时段（热力）、渠道的比率轮廓（雷达）、5 个渠道的四项对照（平行坐标）。
- **地域与转化**：各省 GMV（省级地图，从 DataV 下载边界，所以这个故事要连外网）、下单到完成（漏斗）。`;

/** What 「Show code」 shows on this page (`hostSource.ts`). */
const HOST_CODE = hostSource(
  ['Showcase.stories.tsx', sceneSource],
  ['retail/showcase.ts', boardSource],
  ['retail/RetailHost.tsx', hostShell],
);

const meta = {
  title: 'View Engine/业务场景/图型全景',
  component: Showcase,
  // The map's borders come from DataV over the network: the display stories
  // are for reading, the regression twin draws a map of its own.
  tags: ['!test'],
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: { component: description },
      // 「Show code」: the host's side of this scene, read from the file.
      source: { code: HOST_CODE, language: 'tsx' },
    },
  },
  argTypes: { tab: { table: { disable: true } } },
  beforeEach: () => {
    const takeBack = redUp();
    const unregister = registerChinaMap();
    return () => {
      unregister();
      takeBack();
    };
  },
  decorators: [
    Story => (
      <AppShell current="showcase" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Showcase>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 走势：生意怎么样。 */
export const Trend: Story = { name: '走势' };

/** 构成：钱从哪里来。 */
export const Mix: Story = { name: '构成', args: { tab: 'mix' } };

/** 分布与关系：差异在哪。 */
export const Spread: Story = { name: '分布与关系', args: { tab: 'spread' } };

/** 地域与转化。 */
export const Region: Story = { name: '地域与转化', args: { tab: 'region' } };
