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
import { OPS_DAILY } from './retail/boards.js';
import { RetailBoardScene } from './retail/RetailHost.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 运营日报 in the dashboard workbench: the board the home page embeds as a
 * report, here where the operations team reads, rearranges and saves it.
 */
function OpsDaily() {
  return <RetailBoardScene instanceId={OPS_DAILY} />;
}

const FIXTURE = '内存 ViewStore · 栖木生活 2 万张子订单（示例数据）';

const description = `**业务场景 · 运营日报**

运营每天早上看的那块板（docs/scenarios.md 4.1），在仪表盘工作台里打开：首页嵌的就是它，只是首页只读，这里能搭、能存。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-22 上午 10 点（Asia/Shanghai）。
- **筛选**：日期（默认「昨日」、必填，也可以选「前天」或日历上的任一天）、渠道、店铺，以及接到明细的「搜索订单」（订单号、买家昵称、商品名，编译成 Wow 的 \`SEARCH\`）。
- **第一行、第二行**：八张指标卡——GMV、实付金额、订单数、新客数、客单价、支付转化率、售后退款（越低越好）、发货及时率（目标 95%）。每张锚在所选的那一天（D39）：读 9 月 21 日较前一日，下面是以它为终点的近 30 天走势；客单价、转化率、及时率是两个和之比，每一天按那一天的和相除（D38）。
- **第三行**：今日与昨日的逐时 GMV（写在指标自己的条件里，不跟日期）；渠道分布——点一根柱，整块板筛到那个渠道（交叉筛选）。
- **第四行**：「付款超过 48 小时仍未发货」明细（此刻的队列，不跟日期；最早付款的在前，接搜索）；售后退款最多的 5 个商品（近 30 天）——点一个，打开销售复盘的「品类」页，带上这块板的渠道。
- **第五行**：值班手册。
- **观察**：华东（嘉兴）仓分拣线故障（A7）让 9 月 21 日的发货及时率掉到约 82%，明细里 11 张超时单都在华东仓；竹纤维浴巾的退款率远高于其他商品（A1）。`;

const meta = {
  title: 'View Engine/业务场景/运营日报',
  component: OpsDaily,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="ops-daily" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof OpsDaily>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The board as the operations team keeps it. */
export const DailyReport: Story = { name: '运营日报' };
