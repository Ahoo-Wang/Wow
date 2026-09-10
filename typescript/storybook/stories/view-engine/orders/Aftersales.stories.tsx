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
import { OrderWorkbench } from '../../../packages/view-engine/examples/react/sales-order/OrderWorkbench.js';
import source from '../../../packages/view-engine/examples/react/sales-order/OrderWorkbench.tsx?raw';
const meta = {
  id: 'view-engine-售后与关闭',
  title: 'View Engine/订单业务流程/售后与关闭',
  component: OrderWorkbench,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '从云杉传媒 SO-202609-1005 开始：登记退款 1,200 元 → 登记冲减 1,200 元 → 结算核对 → 在详情中交给销售主管 → 关闭订单。订单在关闭前始终保留于当前队列。下方“从签收订单申请退货”演示申请、审核和入库的前半段。',
      },
    },
  },
  args: { stage: 'aftersales', initialRole: 'finance' },
} satisfies Meta<typeof OrderWorkbench>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '退货退款及订单关闭' };

export const ReturnFromReceipt: Story = {
  name: '从签收订单申请退货',
  args: { stage: 'all', initialRole: 'support' },
  parameters: {
    docs: {
      description: {
        story:
          '打开 SO-202609-1017，申请退回 1 台显示器。按详情中的下一步完成审核、入库、退款、冲减、核对与关闭。',
      },
    },
  },
};
