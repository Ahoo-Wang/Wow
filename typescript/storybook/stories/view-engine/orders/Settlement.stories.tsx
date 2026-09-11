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
  id: 'view-engine-对账与结算',
  title: 'View Engine/入门与业务流程/订单流程/对账与结算',
  component: OrderWorkbench,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '云杉传媒部分退货，退款与冲减各 1,200 元；两者完成后结算核对。 数据与视图分开保存；公开故事保持初始状态。',
      },
    },
  },
  args: { stage: 'settlement', initialRole: 'finance' },
} satisfies Meta<typeof OrderWorkbench>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '核对回款与票据' };
export const RefreshRecovery: Story = {
  name: '收款成功后刷新失败',
  args: { stage: 'release', failRefreshAfterWrite: true },
};
export const QueryFailure: Story = {
  name: '查询失败 · 保留条件重试',
  args: { failFirstQuery: true },
};
export const SummaryFailure: Story = {
  name: '汇总失败 · 独立恢复',
  args: { failFirstSummary: true },
};
