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
import { RETAIL_DATA_NOTE, retailShell } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import {
  RETAIL_ORDER_EVENTS,
  retailOrderEventsDefinition,
} from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';

function OrderEventScene() {
  return (
    <StoryEngine
      create={() => createRetailEngine([retailOrderEventsDefinition])}
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={RETAIL_ORDER_EVENTS}
          {...HOST_LANGUAGE}
          record={{ selectable: false, emptyTitle: '没有符合条件的事件' }}
        />
      )}
    </StoryEngine>
  );
}

const description = `**业务场景 · 订单事件流**

交易订单的事件流分析台：一张单这一生发生过什么、每天发生了多少、哪些单折腾得最多。只读。

${RETAIL_DATA_NOTE}

- **数据**：最近 90 天下单的 2628 张子订单的事件流，共 13239 条；一条事件流是一次命令追加的事件（版本从 1 起连续），事件在数组 \`body\` 里。按事件筛选是对 \`body\` 的元素匹配，按事件分析展开 \`body\`、以事件为计数单位。游标分页，一页接一页往后读。
- **系统视图**：最近的事件、订单历史（模板：填上订单号，按版本读）、支付超时、售后与退款、事件类型分布、每日事件量、变动最多的订单。
- **看什么**：打开「订单历史」，在订单号里填 \`TO2026091900005\`——9 月 19 日付款之后再没有下文：它是华东（嘉兴）仓分拣线故障卡住的单之一（埋下的异常 A7，订单工作台「发货超时」里的那 11 张）。对照一张正常的单，付款成功之后是包裹发出、包裹签收、交易完成。
- **还能做**：「事件类型分布」按事件类型展开计数；「每日事件量」补齐没有事件的日子；点「变动最多的订单」里任一单「查看这些记录」。`;

const meta = {
  title: 'View Engine/业务场景/订单事件流',
  component: OrderEventScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [retailShell('retail-order-events')],
} satisfies Meta<typeof OrderEventScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const OrderEventStream: Story = { name: '订单事件流' };
