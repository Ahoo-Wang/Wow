/// <reference types="vite/client" />
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
import { OrderExample } from '../../../packages/view-engine/examples/react/OrderExample.js';

import exampleSource from '../../../packages/view-engine/examples/react/OrderExample.tsx?raw';

const meta = {
  title: '开发验证/本地视图恢复',
  id: 'development-local-storage',
  component: OrderExample,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: exampleSource, language: 'tsx' },
      description: {
        component:
          '开发验证：视图配置写入浏览器 localStorage，订单记录仍由隔离的内存服务提供。刷新或重新打开验证恢复；重置仅清理当前示例配置。',
      },
    },
  },
} satisfies Meta<typeof OrderExample>;

export default meta;

type Story = StoryObj<typeof meta>;

export const LocalStorageViews: Story = {
  name: '开发验证 · 浏览器本地视图',
  args: {
    persistViews: true,
    scopeKey: 'storybook:local-view-host',
    initialSidebarCollapsed: false,
  },
  parameters: {
    docs: {
      description: {
        story:
          '保存、另存、改名、删除及排序写入 localStorage。可以刷新浏览器或重新打开视图验证恢复；重置仅清除此示例的视图配置。',
      },
    },
  },
};
