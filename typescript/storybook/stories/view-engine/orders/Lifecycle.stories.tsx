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
  id: 'view-engine-全链路体验',
  title: 'View Engine/入门与业务流程/全链路体验',
  component: OrderWorkbench,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '从创建开始，按岗位接力完成审核、收款、交付、票据与关闭。 数据与视图分开保存；公开故事保持初始状态。\n\n### 按你的目标阅读\n| 目标 | 入口 |\n| --- | --- |\n| 从接单开始理解业务 | [接单与审核](./?path=/docs/view-engine-接单与审核--docs) → [收款与放行](./?path=/docs/view-engine-收款与放行--docs) → [备货与交付](./?path=/docs/view-engine-备货与交付--docs) → [对账与结算](./?path=/docs/view-engine-对账与结算--docs) → [售后与关闭](./?path=/docs/view-engine-售后与关闭--docs) |\n| 在应用中接入引擎 | [最小接入](./?path=/docs/view-engine-扩展接入-最小接入--docs) → [我的工作视图](./?path=/docs/view-engine-我的工作视图--docs) → [业务扩展](./?path=/docs/view-engine-扩展接入--docs) |\n| 检查某一能力的边界 | 按引擎与宿主、数据视图、分析视图、查询筛选和扩展组件分类；例如[空集与汇总](./?path=/docs/view-engine-专项场景-record-view-表格与汇总--docs) |\n\n[真实补偿数据](./?path=/docs/view-engine-补偿-api-数据--docs)与[真实补偿分析](./?path=/docs/view-engine-补偿-api-分析--docs)可验证远程查询接入。\n\n先在下方完成一笔订单，再按需进入章节；各章节独立初始化，跨章节不共享业务修改。',
      },
    },
  },
  args: { stage: 'all', initialRole: 'sales' },
} satisfies Meta<typeof OrderWorkbench>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '完成一笔销售订单' };
export const NarrowDark: Story = {
  name: '窄屏深色 · 全链路',
  args: { appearance: 'dark' },
  render: args => (
    <div data-testid="library-delivery-container" style={{ maxWidth: 392 }}>
      <OrderWorkbench {...args} />
    </div>
  ),
};
