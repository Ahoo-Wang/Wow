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
import { RecordViewExample } from '../docs/RecordViewExample.js';
import source from '../docs/RecordViewExample.tsx?raw';

const meta = {
  id: 'view-engine-扩展接入-最小接入',
  title: 'View Engine/入门与业务流程/最小接入',
  component: RecordViewExample,
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '从三笔订单开始：提供 ViewDefinition、ViewInstanceList 和 ViewHost.resolveSource，由 ViewPage 管理引擎生命周期。金额改为 10000 后按 Enter 查询，只剩 SO-202609-1001；清空已应用条件值后恢复全部记录。此页只提供查询，保存需要接入视图服务。\n\n下一步：[保存我的工作视图](./?path=/docs/view-engine-我的工作视图--docs)，再看[扩展注册与宿主接入](./?path=/docs/view-engine-扩展接入--docs)。完整业务体验位于[销售订单全链路](./?path=/docs/view-engine-全链路体验--docs)。',
      },
    },
  },
} satisfies Meta<typeof RecordViewExample>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Minimal: Story = {
  name: '第一个数据视图 · 查询、排序与分页',
};
