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
import { fn } from 'storybook/test';
import { OrderExample } from '../../packages/view-engine/examples/react/OrderExample.js';
import { FilterPersistenceExample } from '../../packages/view-engine/examples/react/FilterPersistenceExample.js';
import type { OrderEvent } from '../../packages/view-engine/examples/react/orderService.js';

import exampleSource from '../../packages/view-engine/examples/react/OrderExtensions.tsx?raw';

import persistenceSource from '../../packages/view-engine/examples/react/FilterPersistenceExample.tsx?raw';

const meta = {
  title: 'View Engine/扩展接入/公共包',
  component: OrderExample,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: exampleSource, language: 'tsx' },
      description: {
        component:
          '五类扩展由 `ViewExtensions` 显式注册：globalActions、toolbarActions、rowActions、filters、cells。JSON 中只保存 `{ name, options }` 引用，组件与服务留在运行时。\n\n### 验证重点\n- 全局、批处理、行操作由业务代码执行写入；写入成功后调用绑定实例的 `refresh()`。\n- 写入成功但刷新失败，只重试刷新，防止重复写入。\n- 筛选注册同时提供 component、compile、modes 和可选 clear；保存的是 props，未设置控件也能恢复。\n- 单元格只接收只读展示上下文，不在 render 中写入。\n\n代码面板展示 OrderExtensions 或 FilterPersistenceExample 的真实实现；同目录 OrderOperations、OrderCells、OrderFilters 提供完整业务示例。',
      },
    },
  },
} satisfies Meta<typeof OrderExample>;

export default meta;

type Story = StoryObj<typeof meta>;

export const FilterPersistence: Story = {
  parameters: {
    docs: { source: { code: persistenceSource, language: 'tsx' } },
  },
  name: '公共包 · 组件配置 JSON 保存与重新打开',
  render: args => <FilterPersistenceExample appearance={args.appearance} />,
};

export const FailureAndScopedRefresh: Story = {
  name: '公共包 · 失败恢复与异步作用域',
  args: {
    failFirstRead: true,
    failFirstWrite: true,
    onEvent: fn<(event: OrderEvent) => void>(),
  },
};

export const RefreshRecovery: Story = {
  name: '公共包 · 刷新重试不重复写入',
  args: {
    failRefreshAfterWrite: true,
    onEvent: fn<(event: OrderEvent) => void>(),
  },
};
