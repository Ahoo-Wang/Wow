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
  id: 'view-engine-备货与交付',
  title: 'View Engine/入门与业务流程/订单流程/备货与交付',
  component: OrderWorkbench,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '远航科技先发 6 台；知行工程拒收 1 台，回仓后重发。 数据与视图分开保存；公开故事保持初始状态。',
      },
    },
  },
  args: { stage: 'delivery', initialRole: 'delivery' },
} satisfies Meta<typeof OrderWorkbench>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '分批交付与拒收重发' };
