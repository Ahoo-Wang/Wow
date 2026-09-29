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
import {
  actions,
  type RecordRow,
  type ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { RETAIL_DATA_NOTE, retailShell } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import {
  ORDER_WORKBENCH_VIEWS,
  RETAIL_ORDERS,
  retailOrdersDefinition,
} from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import sceneSource from './RetailOrders.stories.tsx?raw';
import { hostSource } from './hostSource.js';

/** The statuses a reminder to the warehouse still makes sense for. */
const AWAITING_SHIPMENT = new Set(['PAID', 'PARTIALLY_SHIPPED']);

function awaitingShipment(row: RecordRow): boolean {
  const state = (row.data.state ?? {}) as Record<string, unknown>;
  return AWAITING_SHIPMENT.has(String(state.status));
}

/**
 * The host's one command, declared (host-integration.md 5): 「催发货」, a
 * reminder pushed to the warehouse's work queue. It changes nothing on the
 * order itself — which is why it is the command here and 「标记加急」 is
 * not: a tag written by a story that cannot write the data would claim a
 * change the list never shows. The host says what and when; the engine puts
 * it in each row and over a selection, asks how many, sends it a few at a
 * time and reports. An order that has left the warehouse is refused with
 * the reason before anything is sent, and stays selected.
 */
const ORDER_ACTIONS = actions([
  {
    id: 'remind',
    label: '催发货',
    primary: true,
    available: row => (awaitingShipment(row) ? true : '这张单已经发出，不用催'),
    run: () => new Promise(resolve => setTimeout(resolve, 120)),
  },
]);

function OrderWorkbench({ engine }: { engine: ViewEngine }) {
  return (
    <DataWorkbench
      engine={engine}
      definitionId={RETAIL_ORDERS}
      {...HOST_LANGUAGE}
      record={{
        actions: ORDER_ACTIONS,
        emptyTitle: '没有符合条件的订单',
        emptyDescription: '换一个时间范围，或清掉几个条件再看。',
      }}
    />
  );
}

function OrderScene() {
  return (
    <StoryEngine
      create={() =>
        createRetailEngine([retailOrdersDefinition], ORDER_WORKBENCH_VIEWS)
      }
    >
      {engine => <OrderWorkbench engine={engine} />}
    </StoryEngine>
  );
}

const description = `**业务场景 · 订单工作台**

客服与运营每天打开的那个工作台：按单号、买家、留言找单，盯发货超时的队列，成批催发货。

${RETAIL_DATA_NOTE}

- **系统视图**（跟着定义发布）：**发货超时**（值班队列，每 30 秒刷新，打开就是它）、全部订单、昨日订单、礼品加急（标记「全都包含」礼品与加急）、全额退款关闭。
- **共享与个人视图**：客服组的「留言提到改地址」（全文搜索框）；品控组的「浴巾退款单（近 3 个月）」（商品行上的元素匹配：同一行既是竹纤维浴巾 70×140 · 米白、又退过款，A1）；个人的「我跟的大客户」（按买家筛，买家是远程搜索的引用字段，卡片布局）。
- **看什么**：打开「发货超时」——付款 48 小时仍未发出、不是预售的单，最早付款的在前。这一刻有 11 张，**发货仓一列全是华东（嘉兴）**：那是 9 月 20 日起分拣线故障卡住的包裹（埋下的异常 A7）。勾几张按「催发货」，结果条逐条说成了几单；已经发出的单会被拒并说明原因。
- **还能做**：点任一行看分组后的全部字段；列设置、冻结、多重排序；本页与全部两行汇总（实付合计、最早下单时间）；在筛选里加「买家」搜「林」，从 6700 个会员里挑；表格与卡片切换；导出 CSV；另存为自己的视图。`;

/** What 「Show code」 shows on this page (`hostSource.ts`). */
const HOST_CODE = hostSource(['RetailOrders.stories.tsx', sceneSource]);

const meta = {
  title: 'View Engine/业务场景/订单工作台',
  component: OrderScene,
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: { component: description },
      // 「Show code」: the host's side of this scene, read from the file.
      source: { code: HOST_CODE, language: 'tsx' },
    },
  },
  decorators: [retailShell('retail-orders')],
} satisfies Meta<typeof OrderScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const OrderWorkbenchScene: Story = { name: '订单工作台' };
