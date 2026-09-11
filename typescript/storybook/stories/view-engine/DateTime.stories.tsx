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
import '@ahoo-wang/fetcher-view-engine/styles.css';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  DatePickerDemo,
  DateRangeDemo,
  DateTimeFilterDemo,
  TimeInputDemo,
  type DemoArgs,
} from './DateTimeExamples.js';

const meta = {
  id: 'view-engine-专项场景-基础组件-日期时间',
  title: 'View Engine/扩展与组件/日期时间',
  args: { appearance: 'light', disabled: false },
  argTypes: {
    appearance: { control: 'inline-radio', options: ['light', 'dark'] },
    disabled: { control: 'boolean' },
  },
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          '独立组件只负责输入，应用负责何时查询。字段组合示例按浏览器本地时区，区间示例使用 Asia/Shanghai。\n\n日期范围默认双月日历；datetime 开启 showTime 后，起止日期与秒级时间在同一弹层编辑，确定后回填，取消/Escape 保留原值。日期精度查询包含完整自然日；生产视图统一使用 ViewDefinition.timeZone，省略时为本地时区。\n\n尝试不完整时间、未设置值、跨月区间、禁用状态和深色弹层，观察有效性及查询按钮。',
      },
    },
  },
} satisfies Meta<DemoArgs>;

export default meta;

type Story = StoryObj<DemoArgs>;

export const DateTimeFilter: Story = {
  name: '字段组合 · 手动查询',
  render: args => <DateTimeFilterDemo {...args} />,
};

export const DatePicker: Story = {
  name: '日期选择',
  render: args => <DatePickerDemo {...args} />,
};

export const DateRange: Story = {
  name: '日期区间 · 双月日历',
  render: args => <DateRangeDemo {...args} />,
};

export const DateTimeRange: Story = {
  name: '日期时间区间 · 弹层确认',
  render: args => <DateRangeDemo {...args} datetime />,
};

export const DarkDateTimeRange: Story = {
  ...DateTimeRange,
  name: '深色 · 日期时间区间',
  args: { appearance: 'dark' },
};

export const TimeInput: Story = {
  name: '时间输入 · 秒精度',
  render: args => <TimeInputDemo {...args} />,
};

export const IncompleteTime: Story = {
  name: '未完成时间输入',
  render: args => <TimeInputDemo {...args} initial="12:" />,
};

export const UnsetValue: Story = {
  name: '未设置值 · 不参与筛选',
  render: args => <DateTimeFilterDemo {...args} unset />,
};

export const DarkDateTimeFilter: Story = {
  ...DateTimeFilter,
  name: '深色 · 字段组合',
  args: { appearance: 'dark' },
};
