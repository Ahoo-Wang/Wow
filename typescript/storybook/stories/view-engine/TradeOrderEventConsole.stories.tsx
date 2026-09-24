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
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { DEFAULT_TRADING_HOST, tradingFetcher } from './tradeOrder.js';
import {
  TRADE_ORDER_EVENTS,
  createTradeOrderEventsEngine,
} from './tradeOrderEvents.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The trade order event stream analysis console — one workbench over the
 * events a real Wow trading service appended to its orders: the newest
 * events, one order's history in order, the reviews rejected, the orders
 * changed or cancelled, and how the events distribute over types and days.
 *
 * The event stream is the record of what happened, so it is read-only.
 * Nothing here is faked, so nothing here is repeatable — this story is for
 * using, not for CI (its regression twin runs over a recorded service).
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
    <StoryEngine create={() => createTradeOrderEventsEngine(fetcher)}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={TRADE_ORDER_EVENTS}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

/**
 * What the scene is, on the docs page rather than above the console: the
 * console is the product an analyst uses, so it has the screen to itself.
 */
const description = `**真实后端 · 交易订单**

Wow 交易服务里交易订单的事件流：真实的事件、真实的数据量；同一个工作台里读最近的事件、查一张订单的完整历史、找出评审驳回、改单与取消，看事件按类型与日期的分布。

- **数据源**：Wow 交易服务 · \`trade_order\` 事件流，地址是 \`host\` 参数，可在 Controls 面板切换；改动后按新地址重建引擎。默认是开发集群交易服务在本机的端口转发 \`http://localhost:8088\`，集群内可直接填 \`http://trading-service.dev.svc.cluster.local\`。
- **准备**：引擎与视图存储随故事新建；数据直连 \`host\` 指向的服务，只读。
- **操作**：打开「事件流分析台」场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：一条记录是一次命令追加的事件流，常常装着两个事件（下单并确认、驳回并转入待修改）；按事件筛选是对 \`body\` 的元素匹配，按事件分析会展开 \`body\`、以事件为计数单位。

> 事件流是已发生之事的记录，只读，没有命令。`;

const meta = {
  title: 'View Engine/真实后端/交易订单/事件流分析台',
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
        current="trade-order-event-streams"
        service={{ host: context.args.host }}
      >
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Console>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The newest events, one order's history, and the distribution of all. */
export const EventStreamConsole: Story = { name: '事件流分析台' };
