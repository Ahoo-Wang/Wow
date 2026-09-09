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
import type { OrderEvent } from '../../packages/view-engine/examples/react/orderService.js';

import exampleSource from '../../packages/view-engine/examples/react/OrderExample.tsx?raw';
import { RecordViewExample } from '../docs/RecordViewExample.js';
import minimalSource from '../docs/RecordViewExample.tsx?raw';

const meta = {
  title: 'View Engine/快速开始',
  component: OrderExample,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: exampleSource, language: 'tsx' },
      description: {
        component:
          '从“第一个数据视图”开始：查看三笔订单 → 修改金额但保留原结果 → Enter 查询 → 清空条件值 → 分页与排序。代码面板展示与文档站共用的完整源码。\n\n### 接入顺序\n1. 构建公开包：`pnpm --filter @ahoo-wang/fetcher-view-engine... build`。\n2. 提供 `ViewDefinition`、`ViewInstanceList` 和 `ViewHost.resolveSource`，由 `ViewPage` 管理引擎生命周期。\n3. 用 `scopeKey` 隔离用户/租户，按需接入保存服务和五类扩展。\n\n当前包尚未公开发布，独立应用先使用验证后的本地归档。下面的五类扩展示例可复制 `packages/view-engine/examples/react` 目录运行；所有展示故事保留初始状态，自动化断言在独立回归故事中。文档站的 View Engine 专区提供接入、使用与 API 参考。',
      },
    },
  },
} satisfies Meta<typeof OrderExample>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Minimal: Story = {
  name: '第一个数据视图 · 查询、排序与分页',
  render: args => <RecordViewExample appearance={args.appearance} />,
  parameters: {
    docs: {
      source: { code: minimalSource, language: 'tsx' },
      description: {
        story:
          '金额改为 200，按 Enter 查询后只显示 ORDER-002；清空已应用条件值后恢复全部记录。此示例的宿主只提供查询，保存等能力在接入对应服务后启用。',
      },
    },
  },
};

export const FiveExtensions: Story = {
  name: '公共包 · 五类扩展与手动查询',
  args: { onEvent: fn<(event: OrderEvent) => void>() },
};

export const NarrowDark: Story = {
  name: '公共包 · 深色窄容器与弹层',
  args: { appearance: 'dark', onEvent: fn<(event: OrderEvent) => void>() },
  render: args => (
    <div data-testid="library-delivery-container" style={{ maxWidth: 392 }}>
      <OrderExample {...args} />
    </div>
  ),
};
