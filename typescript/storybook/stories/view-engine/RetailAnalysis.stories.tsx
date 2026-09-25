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
import type {
  DataViewDefinition,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { RETAIL_DATA_NOTE, retailShell } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import {
  ANALYSIS_VIEWS,
  MEMBER_ANALYSIS_VIEWS,
  retailMembersDefinition,
  retailOrderAnalysisDefinition,
} from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/** Which dataset the analyst works on: the orders, or the members. */
type Dataset = 'orders' | 'members';

const DATASETS: Record<
  Dataset,
  { definition: DataViewDefinition; views: ViewInstance[] }
> = {
  orders: { definition: retailOrderAnalysisDefinition, views: ANALYSIS_VIEWS },
  members: {
    definition: retailMembersDefinition,
    views: MEMBER_ANALYSIS_VIEWS,
  },
};

/**
 * The analysts' workbench: the team's saved analyses in the list, the
 * records behind any group one follow-up away (the definition declares its
 * records, so 「查看这些记录」 opens them here).
 */
function AnalysisScene({ dataset }: { dataset: Dataset }) {
  const { definition, views } = DATASETS[dataset];
  return (
    <StoryEngine
      key={dataset}
      create={() => createRetailEngine([definition], views)}
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={definition.id}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const description = `**业务场景 · 分析工作台**

运营分析组的工作台：第 4 节分析师的 18 个问题（A-01～A-18）各是一张共享的已存分析，打开就是答案；按任何一组「查看这些记录」追到订单——按商品行展开的分析也一样，打开的是有一行是这一组的单。

${RETAIL_DATA_NOTE}

- **订单与商品**（交易订单）：本月 GMV 较上月同期（指标卡）、本月经营概况、客单价（按日，较前一日的走势卡）、日 GMV 走势（25 个月，7 日移动平均、日均线、峰谷；滚轮或底部滑条缩放到双 11 那一周）、月 GMV 与客单价（双轴）、本月 GMV 较上月同期（分渠道）、本月净销售额的渠道构成（瀑布）、品类构成（矩形树图）、渠道结构（百分比堆叠）、省份 GMV 前 15、城市等级 × 渠道（热力，空值单独一组）、退款率最高的商品、大客户 Top 20（合计行）、下单到完成的漏斗、下单时段热力、双 11 零点各支付方式的超时率、2025 双 11 前后的日 GMV、各活动的客单价与优惠力度、每周发货超时率（5% 红线）、各仓付款到发货 P50/P90、直播间优惠占比 × 退款率（散点）、新客与老客的 GMV、成交单价分布、支付方式构成。
- **会员**：购买次数分布（帕累托：按人数排、上面一条累计占比）、按首单月的复购率（越早的首单月观察期越长，复购率自然越高，大促月要与相邻月份比）。
- **埋下的异常在哪里看得见**：
  - A1 质量问题的浴巾——「退款率最高的商品（近 3 个月）」第一名，竹纤维浴巾 70×140 · 米白约 26%，其余都在 13% 以下。点它「查看这些记录」就是卖过它的单；只看退过款的在订单工作台的「浴巾退款单（近 3 个月）」，理由在售后工作台。
  - A3 3 月 8 日直播间叠加券——「直播间：优惠占比 × 退款率」里最右边那一点（优惠约 43%，其余不到 27%）。
  - A4 双 11 零点云闪付故障——「双 11 零点：各支付方式的超时率」里云闪付 5 单全部超时。
  - A5 旧版小程序没报城市——「城市等级 × 渠道」的「（空）」一行，只落在微信小程序那一列（88 单）；按下那一格「查看这些记录」就是这 88 单。
  - A6 春节停运——「每周发货超时率」2026-02-16 那一周约 62%，远在 5% 红线之上；两次双 11 那一周约 22%。
- **计数单位**：按商品分析（品类、退款率、单价）展开商品行，以订单行为单位；其余以子订单为单位。退款率、超时率这类比率读作百分比，客单价读作金额（派生指标的读法）；「本月至今」「上月同期（至今）」是命名时段，不会过期。`;

const meta = {
  title: 'View Engine/业务场景/分析工作台',
  component: AnalysisScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [retailShell('retail-analysis')],
} satisfies Meta<typeof AnalysisScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const OrderAnalysis: Story = {
  name: '订单与商品',
  args: { dataset: 'orders' },
};

export const MemberAnalysis: Story = {
  name: '会员',
  args: { dataset: 'members' },
};
