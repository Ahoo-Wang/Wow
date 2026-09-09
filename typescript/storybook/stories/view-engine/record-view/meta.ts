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
import type { Meta } from '@storybook/react-vite';
import type { DemoArgs } from './demoTypes.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

import scenarioSource from './Scenario.tsx?raw';

export const recordViewMeta = {
  args: { appearance: 'light' },
  argTypes: {
    appearance: { control: 'inline-radio', options: ['light', 'dark'] },
  },
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: scenarioSource, language: 'tsx' },
      description: {
        component:
          '以 definitionId 打开订单页。个人、系统与共享视图复用同一 ViewDefinition；每个实例独立保留筛选草稿与已应用配置。所有 Record View 场景共用 fixtures.ts 的 Wow 快照订单模型：aggregateId 标识记录，state.totalAmount/state.paidAmount 表示订单与实付金额，state.items 保存商品明细。开发输出可展开查看初始快照。演示通过公开包入口接入 ViewHost、Wow paged/cursor 查询与自定义操作、单元格。',
      },
    },
  },
} satisfies Meta<DemoArgs>;
