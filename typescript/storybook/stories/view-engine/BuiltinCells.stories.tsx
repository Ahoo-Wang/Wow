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
import {
  BuiltinCellsExample,
  StandaloneCellsExample,
} from '../../packages/view-engine/examples/react/BuiltinCellsExample.js';
import source from '../../packages/view-engine/examples/react/BuiltinCellsExample.tsx?raw';
const meta = {
  title: 'View Engine/单元格/内置组件',
  component: BuiltinCellsExample,
  args: { appearance: 'light' },
  argTypes: {
    appearance: {
      control: 'inline-radio',
      options: ['light', 'dark'],
      description: '组件及弹层共用的主题。',
    },
    invalidData: {
      control: 'boolean',
      description: '展示无效数据占位和安全链接边界。',
    },
    persist: {
      control: 'boolean',
      description: '开发用途：保存视图配置到浏览器。',
    },
    scopeKey: {
      control: false,
      description: '访问范围身份；变化时隔离恢复状态。',
    },
  },
  parameters: {
    layout: 'fullscreen',
    docs: {
      source: { code: source, language: 'tsx' },
      description: {
        component:
          '字段默认使用 `cellRenderer`，实例的 `column.renderer` 可覆盖它。内置名称不带 `fve/` 前缀。\n\n| renderer | 常用配置 |\n| --- | --- |\n| text | ellipsis、copyable；复制原始值，成功 2 秒后恢复 |\n| tags | maxVisible，默认 2；多余标签在弹层查看 |\n| status | tones；标签来自字段 options |\n| link | hrefField、newTab；危险 URL 保持文本 |\n| date-time | locale、dateStyle、timeStyle；时区来自定义 |\n| number | 格式设置在字段 numberFormat，可用于金额和比例 |\n\n字段可使用 `customer.name` 或 `items.0.sku` 等记录相对路径。先切换列显隐并保存，再“重新打开已保存视图”，验证 renderer 配置恢复。独立组合、深色窄容器和异常数据场景分别覆盖直接组件使用、布局及占位行为。',
      },
    },
  },
} satisfies Meta<typeof BuiltinCellsExample>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Workbench: Story = { name: '内置单元格 · 视图配置' };
export const Standalone: Story = {
  name: '独立组合 · 常用展示',
  render: args => <StandaloneCellsExample appearance={args.appearance} />,
};
export const DarkNarrow: Story = {
  name: '深色 · 窄容器',
  args: { appearance: 'dark' },
  render: args => (
    <div style={{ maxWidth: 414 }}>
      <StandaloneCellsExample appearance={args.appearance} />
    </div>
  ),
};
export const InvalidData: Story = {
  name: '异常数据 · 占位与安全链接',
  args: { invalidData: true },
};
export const BrowserStorage: Story = {
  name: '开发验证 · 刷新后恢复',
  args: { persist: true, scopeKey: 'builtin-cells-browser' },
};
