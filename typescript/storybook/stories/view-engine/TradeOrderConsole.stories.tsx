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
import { useState } from 'react';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import {
  DEFAULT_TRADING_HOST,
  TRADE_ORDER,
  createTradeOrderEngine,
  tradingFetcher,
} from './tradeOrder.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The trade order console — one workbench over the orders a real Wow trading
 * service holds: the queue waiting on an operator, the orders waiting on
 * payment, and how the orders distribute over status, customers, products
 * and days. The record views and the analysis views sit in one list.
 *
 * It only reads: the trading service's commands belong to its own
 * application. Nothing here is faked, so nothing here is repeatable — this
 * story is for using, not for CI (its regression twin runs over a recorded
 * service).
 */

/**
 * One engine per host, keyed by the host so pointing the Controls panel
 * elsewhere starts over rather than mixing two services' views.
 */
function Console({ host }: { host: string }) {
  return <HostConsole key={host} host={host} />;
}

function HostConsole({ host }: { host: string }) {
  const [fetcher] = useState(() => tradingFetcher(host));
  return (
    <StoryEngine create={() => createTradeOrderEngine(fetcher)}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={TRADE_ORDER}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

/**
 * What the scene is, on the docs page rather than above the console: the
 * console is the product an operator uses, so it has the screen to itself.
 */
const description = `**真实后端 · 交易订单**

Wow 交易服务里的交易订单：真实的数据、真实的数据量；同一个工作台里处理待评审与待修改的订单、盯住待付款的订单，看订单按状态、客户、商品与日期的分布。

- **数据源**：Wow 交易服务 · \`trade_order\` 快照，地址是 \`host\` 参数，可在 Controls 面板切换；改动后按新地址重建引擎。默认是开发集群交易服务在本机的端口转发 \`http://localhost:8088\`，集群内可直接填 \`http://trading-service.dev.svc.cluster.local\`。
- **准备**：引擎与视图存储随故事新建；数据直连 \`host\` 指向的服务，只读。
- **操作**：打开「快照控制台」场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像操作员使用时一样。
- **观察**：筛选、排序、分页、汇总与聚合都由服务端执行；「商品」一列按商品名读订单行，按商品筛选是对 \`state.items\` 的元素匹配，「商品排行」展开订单行、以行为计数单位。

> 只读：交易订单的命令属于交易服务自己的应用，这里不发送任何命令。`;

const meta = {
  title: 'View Engine/真实后端/交易订单/快照控制台',
  component: Console,
  // A live service answers differently every time, so this is never a
  // regression test, and its docs page does not mount it: opening the
  // catalog must not call the service.
  tags: ['!test'],
  parameters: {
    // The console is the product: it fills the canvas inside the host's
    // own bar and navigation (`AppShell`), as it would a screen.
    layout: 'fullscreen',
    docs: { autoMount: false, description: { component: description } },
  },
  args: { host: DEFAULT_TRADING_HOST },
  argTypes: {
    host: {
      control: 'text',
      description: 'Wow 交易服务地址，改动后按新地址重建引擎。',
    },
  },
  decorators: [
    (Story, context) => (
      <AppShell
        current="trade-order-snapshots"
        service={{ host: context.args.host }}
      >
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Console>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The order queue, the unpaid orders, and the distribution of all. */
export const SnapshotConsole: Story = { name: '快照控制台' };
