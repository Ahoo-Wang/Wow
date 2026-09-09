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
/// <reference types="vite/client" />
import type { Meta, StoryObj } from '@storybook/react-vite';
import { BuiltinFiltersExample } from '../../packages/view-engine/examples/react/BuiltinFiltersExample.js';
import source from '../../packages/view-engine/examples/react/BuiltinFiltersExample.tsx?raw';
const meta = {
  title: 'View Engine/过滤器/内置组件',
  component: BuiltinFiltersExample,
  args: { appearance: 'light' },
  argTypes: {
    appearance: {
      control: 'inline-radio',
      options: ['light', 'dark'],
      description: '筛选控件及弹层主题。',
    },
    failNextPage: {
      control: 'boolean',
      description: '模拟后续候选页失败，验证保留与重试。',
    },
    failResolve: {
      control: 'boolean',
      description: '模拟标签回填失败，验证保存标签与重试。',
    },
    persist: {
      control: 'boolean',
      description: '开发用途：验证刷新后的组件配置恢复。',
    },
    scopeKey: {
      control: false,
      description: '访问范围身份；变化时隔离候选与配置。',
    },
  },
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '通过字段 `editor: { name, options }` 使用内置筛选器，名称不带 `fve/` 前缀。\n\n| 名称 | 用途 | 操作符 |\n| --- | --- | --- |\n| select / multi-select | 本地单选、多选 | EQ/NE、IN/NOT_IN |\n| remote-select / remote-multi-select | 远程查询、分页、标签回填 | EQ/NE、IN/NOT_IN |\n| text-values | 多个带类型的值 | IN/NOT_IN |\n| datetime-range | 日期或日期时间区间 | BETWEEN |\n\n先编辑，再查询；保存包含组件属性和未设置控件。远程候选由 `extensions.optionSources` 提供，使用 Fetcher 和 Wow CursorPage。失败场景分别演示后续页和标签回填重试。\n\n日期默认完整自然日、双月日历；设置 `showTime: true` 后在组合弹层确认起止日期与秒级时间。时区统一来自定义，默认本地。开发存储场景会写入浏览器测试数据，可通过“重置示例”恢复。',
      },
    },
  },
} satisfies Meta<typeof BuiltinFiltersExample>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '内置组件 · 查询与恢复' };
export const NextPageFailure: Story = {
  name: '远程分页 · 保留已有候选并重试',
  args: { failNextPage: true },
};
export const ResolveFailure: Story = {
  name: '标签回填 · 保留保存名称并重试',
  args: { failResolve: true },
};
export const DarkNarrow: Story = {
  name: '深色 · 窄容器',
  args: { appearance: 'dark' },
  render: args => (
    <div style={{ maxWidth: 414 }}>
      <BuiltinFiltersExample {...args} />
    </div>
  ),
};
export const BrowserStorage: Story = {
  name: '开发验证 · 刷新后恢复',
  args: { persist: true, scopeKey: 'builtin-browser' },
};
