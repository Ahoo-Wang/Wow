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
import source from '../../../packages/view-engine/examples/react/sales-order/OrderExtensions.tsx?raw';
import definitionSource from '../../../packages/view-engine/examples/react/sales-order/views.ts?raw';
import hostSource from '../../../packages/view-engine/examples/react/sales-order/host.ts?raw';
import workbenchSource from '../../../packages/view-engine/examples/react/sales-order/OrderWorkbench.tsx?raw';
const meta = {
  id: 'view-engine-扩展接入',
  title: 'View Engine/开发接入/业务扩展',
  component: OrderWorkbench,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '接入顺序：先体验业务动作，再对照每个故事的真实源码。\n\n| 业务问题 | 定义引用 | 运行时接入 | 如何验证 |\n| --- | --- | --- | --- |\n| 找超期订单 | editor.name=delivery-risk | filters 的 props/compile/clear | 选择已超期未发完，查询只剩 SO-202609-1004 |\n| 客户 ID 显示名称 | remote-select 的 source=customers | optionSources.customers | 搜索客户，保存后恢复名称 |\n| 展示交付进度 | cellRenderer.name=delivery-progress | cells | 对照明细里的待发和已签收数量 |\n| 创建/批量/单笔操作 | recordActions 引用 | globalActions/toolbarActions/rowActions | 在详情交接岗位，写入后刷新对应视图 |\n\nJSON 只保存 name/options 与筛选 props，组件和服务注册在运行时。源码面板展示当前步骤的实现，不需要从完整工作台中寻找扩展代码。',
      },
    },
  },
  args: { stage: 'delivery', initialRole: 'delivery' },
} satisfies Meta<typeof OrderWorkbench>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '1 · 业务筛选、单元格与操作注册' };
export const Definition: Story = {
  name: '2 · 定义中的 JSON 引用',
  parameters: {
    docs: {
      source: { code: definitionSource, language: 'tsx' },
      description: {
        story:
          '对照 editor、cellRenderer 和 recordActions 的 name，与步骤 1 的注册表逐一对应；这些引用可以随视图定义传输。',
      },
    },
  },
};
export const Local: Story = {
  name: '3 · 本地定义与可替换宿主',
  parameters: {
    docs: {
      source: { code: hostSource, language: 'tsx' },
      description: {
        story:
          '本地定义可以直接提供；异步定义、保存和权限经 ViewHost 加载。这里注入内存存储，替换宿主即可接入服务；真实 HTTP 传输在开发验证专区。',
      },
    },
  },
  args: { localDefinition: true },
};

export const Regions: Story = {
  name: '4 · 卡片与工具栏保留默认内容',
  parameters: {
    docs: {
      source: { code: workbenchSource, language: 'tsx' },
      description: {
        story:
          'renderCard 和 renderToolbar 接收 defaultContent；本例追加交付提示，保留选择、分页和业务操作。',
      },
    },
  },
  args: { customRegions: true, layout: 'card' },
};
