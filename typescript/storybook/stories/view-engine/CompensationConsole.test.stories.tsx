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
import type { StoryObj } from '@storybook/react-vite';
import { expect, waitFor, within } from 'storybook/test';
import displayMeta, {
  Analysis as DisplayAnalysis,
  Records as DisplayRecords,
} from './CompensationConsole.stories.js';
import {
  RECORDED_COMPENSATION_HOST,
  installRecordedCompensationService,
} from './compensationService.js';
import { readColumn, readTotal } from './readTable.js';

/**
 * The console against a recorded service instead of a live one.
 *
 * The display stories stay off CI because a live service answers differently
 * every time. Its data is what varies; the definitions, the system views and
 * the way the console reads a snapshot do not, and a rule change in View
 * Engine can break them without any service involved. These stories run the
 * same console over a fixed set of executions so that such a change fails
 * here instead of in front of someone opening the catalog.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/补偿控制台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  args: { host: RECORDED_COMPENSATION_HOST },
  beforeEach: installRecordedCompensationService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

export const Records: Story = {
  ...DisplayRecords,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // The definition is admitted: every system view lists, and the first opens.
    for (const title of ['活动中', '不可重试', '不可恢复', '已成功', '全部'])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: /^活动中/ }),
    ).toHaveAttribute('aria-current', 'true');

    // Active executions, newest first, read through the nested snapshot paths.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );
    await expect(readColumn(table, '状态')).toEqual([
      '已准备重试',
      '失败',
      '失败',
      '失败',
    ]);
    await expect(readTotal(table, '已重试次数').replace(/\D/g, '')).toBe('10');
  },
};

export const Analysis: Story = {
  ...DisplayAnalysis,
  play: async ({ canvasElement }) => {
    // One bar per status the recorded executions hold.
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('.recharts-bar-rectangle'),
      ).toHaveLength(3),
    );
  },
};
