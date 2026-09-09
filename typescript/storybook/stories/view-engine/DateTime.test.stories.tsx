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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import displayMeta, {
  DarkDateTimeFilter as DisplayDarkDateTimeFilter,
  DateTimeFilter as DisplayDateTimeFilter,
  DateRange as DisplayDateRange,
  DateTimeRange as DisplayDateTimeRange,
  DarkDateTimeRange as DisplayDarkDateTimeRange,
  UnsetValue as DisplayUnsetValue,
} from './DateTime.stories.js';
import type { DemoArgs } from './DateTimeExamples.js';
import type { StoryObj as RegressionStoryObj } from '@storybook/react-vite';

const meta = {
  ...displayMeta,
  title: 'View Engine/基础组件/日期时间/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = RegressionStoryObj<DemoArgs>;

export const DateRange: Story = {
  ...DisplayDateRange,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement, args }) => {
    if (args.disabled) return;
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await expect(canvas.queryAllByRole('textbox')).toHaveLength(0);
    await userEvent.click(
      canvas.getByRole('button', { name: /创建日期日期范围/ }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月29日 星期二/ }),
    );
    await expect(canvas.getByRole('alert')).toBeVisible();
    await expect(
      page.getByRole('dialog', { name: '创建日期日期范围' }),
    ).toBeVisible();
    await userEvent.click(
      within(page.getByRole('grid', { name: '2026年10月' })).getByRole(
        'button',
        {
          name: /^2026年10月2日 星期五/,
        },
      ),
    );
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument();
    await expect(canvas.getByLabelText('区间输入值')).toHaveTextContent(
      '"lowerBound":"2026-09-29","upperBound":"2026-10-02"',
    );
    await userEvent.click(
      canvas.getByRole('button', { name: /创建日期日期范围/ }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: '清空区间' }),
    );
    await expect(canvas.getByLabelText('区间输入值')).toHaveTextContent('{}');
  },
};

export const DateTimeRange: Story = {
  ...DisplayDateTimeRange,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement, args }) => {
    if (args.disabled) return;
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    const output = canvas.getByLabelText('区间输入值');
    const initial = output.textContent!;
    await expect(canvas.queryAllByRole('textbox')).toHaveLength(0);
    await userEvent.click(
      canvas.getByRole('button', { name: /创建时间日期范围/ }),
    );
    const dialog = await page.findByRole('dialog', {
      name: '创建时间日期范围',
    });
    const popup = within(dialog);
    await expect(popup.getAllByRole('grid')).toHaveLength(1);
    await expect(
      popup.getByRole('textbox', { name: '创建时间开始时间' }),
    ).toHaveValue('09:30:45');
    await expect(
      popup.getByRole('textbox', { name: '创建时间结束时间' }),
    ).toHaveValue('10:40:50');
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月5日 星期六/ }),
    );
    await userEvent.click(
      page.getByRole('button', { name: /^2026年9月5日 星期六/ }),
    );
    await userEvent.clear(
      popup.getByRole('textbox', { name: '创建时间结束时间' }),
    );
    await userEvent.type(
      popup.getByRole('textbox', { name: '创建时间结束时间' }),
      '12:40:50',
    );
    await expect(output).toHaveTextContent(initial);
    await userEvent.click(popup.getByRole('button', { name: '确定' }));
    await waitFor(() =>
      expect(page.queryByRole('dialog')).not.toBeInTheDocument(),
    );
    await expect(output).toHaveTextContent('"date":"2026-09-05"');
    await expect(output).toHaveTextContent('"offsetMinutes":-480');
    await expect(output).toHaveTextContent('"time":"12:40:50"');
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument();
    const confirmed = output.textContent!;
    await userEvent.click(
      canvas.getByRole('button', { name: /创建时间日期范围/ }),
    );
    await page.findByRole('dialog', { name: '创建时间日期范围' });
    await userEvent.clear(
      page.getByRole('textbox', { name: '创建时间结束时间' }),
    );
    await userEvent.type(
      page.getByRole('textbox', { name: '创建时间结束时间' }),
      '12:',
    );
    await userEvent.click(page.getByRole('button', { name: '确定' }));
    const error = page.getByRole('alert');
    await expect(error).toBeVisible();
    await expect(
      page.getByRole('textbox', { name: '创建时间结束时间' }),
    ).toHaveValue('12:');
    await expect(
      page.getByRole('textbox', { name: '创建时间结束时间' }),
    ).toHaveAttribute('aria-describedby', error.id);
    await expect(
      page.getByRole('textbox', { name: '创建时间结束日期' }),
    ).toHaveAttribute('aria-describedby', error.id);
    await expect(output).toHaveTextContent(confirmed);
    await userEvent.click(page.getByRole('button', { name: '取消' }));
    await waitFor(() =>
      expect(page.queryByRole('dialog')).not.toBeInTheDocument(),
    );
    await userEvent.click(
      canvas.getByRole('button', { name: /创建时间日期范围/ }),
    );
    await page.findByRole('dialog', { name: '创建时间日期范围' });
    const time = page.getByRole('textbox', { name: '创建时间结束时间' });
    await expect(time).toHaveValue('12:40:50');
    await userEvent.clear(time);
    await userEvent.type(time, '13:10');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(page.queryByRole('dialog')).not.toBeInTheDocument(),
    );
    await expect(output).toHaveTextContent(confirmed);
  },
};

export const DarkDateTimeRange: Story = {
  ...DisplayDarkDateTimeRange,
  tags: ['!dev', '!autodocs', 'test'],
  play: DateTimeRange.play,
};

export const DateTimeFilter: Story = {
  ...DisplayDateTimeFilter,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement, args }) => {
    if (args.disabled) return;
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      canvas.getByRole('button', { name: '创建日期：2026-09-01' }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月3日 星期四/ }),
    );
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
    await userEvent.type(
      canvas.getByRole('textbox', { name: '创建时刻' }),
      '12:',
    );
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-01 09:00:00',
    );
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
    await userEvent.type(
      canvas.getByRole('textbox', { name: '创建时刻' }),
      '10:30:00',
    );
    await expect(canvas.getByText('待查询')).toHaveTextContent('待查询');
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-01 09:00:00',
    );
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-03 10:30:00',
    );
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-03 10:30:00',
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '创建日期：2026-09-03' }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月3日 星期四/ }),
    );
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: '查询' })).toBeEnabled();
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '不限制创建时间',
    );
    await userEvent.type(
      canvas.getByRole('textbox', { name: '创建时刻' }),
      '09:00',
    );
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await expect(canvas.getByText('待查询')).toBeInTheDocument();
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
  },
};

export const UnsetValue: Story = {
  ...DisplayUnsetValue,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement, args }) => {
    if (args.disabled) return;
    const canvas = within(canvasElement);
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: '查询' })).toBeEnabled();
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '不限制创建时间',
    );
  },
};

export const DarkDateTimeFilter: Story = {
  ...DisplayDarkDateTimeFilter,
  tags: ['!dev', '!autodocs', 'test'],
  play: async ({ canvasElement, args }) => {
    if (args.disabled) return;
    const canvas = within(canvasElement),
      page = within(canvasElement.ownerDocument.body);
    await userEvent.click(
      canvas.getByRole('button', { name: '创建日期：2026-09-01' }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月3日 星期四/ }),
    );
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
    await userEvent.type(
      canvas.getByRole('textbox', { name: '创建时刻' }),
      '12:',
    );
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-01 09:00:00',
    );
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
    await userEvent.type(
      canvas.getByRole('textbox', { name: '创建时刻' }),
      '10:30:00',
    );
    await expect(canvas.getByText('待查询')).toHaveTextContent('待查询');
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-01 09:00:00',
    );
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-03 10:30:00',
    );
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '2026-09-03 10:30:00',
    );
    await userEvent.click(
      canvas.getByRole('button', { name: '创建日期：2026-09-03' }),
    );
    await userEvent.click(
      await page.findByRole('button', { name: /^2026年9月3日 星期四/ }),
    );
    await expect(canvas.queryByRole('alert')).not.toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: '查询' })).toBeEnabled();
    await userEvent.click(canvas.getByRole('button', { name: '查询' }));
    await expect(canvas.getByLabelText('已应用条件')).toHaveTextContent(
      '不限制创建时间',
    );
    await userEvent.type(
      canvas.getByRole('textbox', { name: '创建时刻' }),
      '09:00',
    );
    await expect(canvas.getByRole('button', { name: '查询' })).toBeDisabled();
    await expect(canvas.getByText('待查询')).toBeInTheDocument();
    await userEvent.clear(canvas.getByRole('textbox', { name: '创建时刻' }));
  },
};
