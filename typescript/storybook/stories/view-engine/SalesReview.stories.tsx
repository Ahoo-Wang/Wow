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
import type { DashboardFilters } from '@ahoo-wang/wow-view-engine';
import { AppShell } from '../shared/AppShell.js';
import { SALES_REVIEW } from './retail/boards.js';
import { RetailBoardScene } from './retail/RetailHost.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 销售复盘 in the dashboard workbench: the monthly review, on four tabs, and
 * the board that shows building — 「编辑」, adding a panel, rearranging,
 * undo, 「复制为共享视图并替换…」, save — since the home page only reads.
 */
function SalesReview({
  tab,
  filters,
}: {
  tab?: string;
  filters?: DashboardFilters;
}) {
  return (
    <RetailBoardScene
      instanceId={SALES_REVIEW}
      initialTab={tab}
      initialFilters={filters}
    />
  );
}

const FIXTURE = '内存 ViewStore · 栖木生活 2 万张子订单（示例数据）';

const description = `**业务场景 · 销售复盘（月度）**

每月初的经营复盘（docs/scenarios.md 4.1），四个标签页，只跑当前那一页。这块板也是「搭板子」的场景：运营组能在这里「编辑」——添加已存的视图或新建分析、拖动与缩放、移到别的标签页、撤销、保存；共享板上挂了个人视图时，「⋯ → 复制为共享视图并替换…」。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-22 上午 10 点。
- **筛选**：日期（可选，不设就是全部 25 个月）、渠道、会员等级；整板「按月｜按周｜按日」。
- **概览**：六张卡读最近一个过完的月（8 月）较 7 月（A-01）；按月的 GMV 与客单价（A-02）；8 月较 7 月按渠道拆的瀑布（A-03）。整板改成「按日」，卡片读昨日较前一日——与运营日报同一个口径。
- **品类**：品类构成的矩形树图（A-04）、价格带（A-16）、退款率最高的 10 个商品（A-07，点一个打开订单明细，带上这个商品）。竹纤维浴巾 70×140 排第一（A1）。
- **渠道与地域**：渠道份额按月（A-05，点一个渠道整板筛到它）、省份前 15、城市等级 × 渠道（「（未上报）」那一行是旧版小程序没报城市，A5）、各活动的 GMV 与客单价（A-11）。
- **客户**：GMV 前 20 的买家带合计（A-08）、新老客（A-15）、按首单月的复购率（A-18，会员数据，日期与渠道接不上它，面板头会说）。`;

const meta = {
  title: 'View Engine/业务场景/销售复盘',
  component: SalesReview,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  argTypes: {
    tab: { table: { disable: true } },
    filters: { table: { disable: true } },
  },
  decorators: [
    Story => (
      <AppShell current="sales-review" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof SalesReview>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 概览: the last month that ended, against the one before. */
export const Overview: Story = { name: '概览' };

/** 品类, where the bath towel's refund rate stands out (A1). */
export const Category: Story = { name: '品类', args: { tab: 'category' } };

/** 渠道与地域: live selling's share growing, and the cities never reported (A5). */
export const Channels: Story = {
  name: '渠道与地域',
  args: { tab: 'channel' },
};

/** 客户: who the big buyers are, and whether the new ones come back. */
export const Customers: Story = { name: '客户', args: { tab: 'customer' } };

/**
 * One day, as the daily report's press carries it over: the whole board is
 * narrowed to 2026-09-21, and the cards read that day — the same numbers
 * the daily report's cards show for it.
 */
export const OneDay: Story = {
  name: '某一天（9 月 21 日）',
  args: {
    filters: {
      values: {
        date: { type: 'absolute', from: '2026-09-21', to: '2026-09-21' },
      },
    },
  },
};
