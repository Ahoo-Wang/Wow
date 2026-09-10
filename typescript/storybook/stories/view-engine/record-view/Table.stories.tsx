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
import type { Story } from './demoTypes.js';
import { Scenario } from './Scenario.js';
import { renderLoadingSummaries } from './ScenarioVariants.js';
import { recordViewMeta } from './meta.js';

const meta = {
  ...recordViewMeta,
  parameters: {
    ...recordViewMeta.parameters,
    docs: {
      ...recordViewMeta.parameters.docs,
      description: {
        component:
          '本页使用简化订单快照验证单项引擎契约；完整销售业务见“全链路体验”。\n\n列配置包含显隐、顺序、宽度、固定位置和多选汇总。列宽通过表头边缘拖动调整；普通列沿相邻固定列扩展固定区域。\n\n订单金额与实付金额分别使用 state.totalAmount、state.paidAmount，商品明细保留在 state.items。默认同时汇总两列，包含未付、部分支付与付清的订单。\n\n本页汇总来自已加载记录，所有汇总来自已应用筛选的 aggregate 查询，均不按选中行计算。商品明细条件筛选整笔订单，汇总计算订单金额；不会仅累加命中的明细。可同时选择 SUM/AVG/MIN/MAX；金额与汇总共用字段 numberFormat。\n\n失败入口紧邻“所有”标签，每个范围只显示一次；展开查看原因并独立重试。加载使用 Spin，空记录和缺失数值保持图标/占位。',
      },
    },
  },
  id: 'view-engine-专项场景-record-view-表格与汇总',
  title: 'View Engine/专项场景/数据展示/表格与汇总',
};

export default meta;

export const Summaries: Story = {
  name: '汇总 · 本页与所有同时展示',
  render: args => <Scenario {...args} summaries />,
};

export const LoadingSummaries: Story = {
  name: '加载状态 · Spin 与汇总',
  render: renderLoadingSummaries,
};

export const SummaryFailure: Story = {
  name: '汇总失败 · 保留本页与独立重试',
  render: args => <Scenario {...args} summaries failFirstSummary />,
};

export const PinnedColumns: Story = {
  name: '固定列 · 主键在左操作在右',
  render: args => (
    <div style={{ maxWidth: 900 }}>
      <Scenario {...args} summaries />
    </div>
  ),
};

export const EmptySummary: Story = {
  name: '空集汇总 · 保留空值',
  render: args => <Scenario {...args} summaries empty />,
};
