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
import { defaultMessages } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  CannotOpen as DisplayCannotOpen,
  EmptyResult as DisplayEmptyResult,
  Loading as DisplayLoading,
  NeedsFixing as DisplayNeedsFixing,
  QueryFailed as DisplayQueryFailed,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { amountOf, readColumn, readTotal } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/数据视图/Record 工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** The shared view's condition and sort, as the source answered them. */
const PENDING_BY_AMOUNT = ['SO-1003', 'SO-1005', 'SO-1001', 'SO-1006'];

export const WithData: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // The total covers what the conditions select, not every order.
    await expect(amountOf(readTotal(table, '金额'))).toBe(6470);
  },
};

export const EmptyResult: Story = {
  ...DisplayEmptyResult,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByText(
        defaultMessages['label.record.empty-hint'],
      ),
    ).toBeVisible();
  },
};

export const Loading: Story = {
  ...DisplayLoading,
  play: async ({ canvasElement }) => {
    const table = await within(canvasElement).findByRole('table');
    await expect(
      table.querySelectorAll('[data-slot=skeleton]').length,
    ).toBeGreaterThan(0);
    await waitFor(
      () => expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
      { timeout: 5_000 },
    );
  },
};

export const QueryFailed: Story = {
  ...DisplayQueryFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent(
      defaultMessages['label.query.failed'],
    );
    await expect(alert).toHaveTextContent('仓储服务暂时不可用');
    // Only the data is gone: the view stays open under its conditions.
    await expect(
      canvas.getByRole('button', { name: /^待出库订单/ }),
    ).toHaveAttribute('aria-current', 'true');
  },
};

export const NeedsFixing: Story = {
  ...DisplayNeedsFixing,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const alert = await canvas.findByRole('alert');
    await expect(alert).toHaveTextContent(
      defaultMessages['label.view.needs-fixing'],
    );
    await expect(alert).toHaveTextContent('removedColumn');
    // A config the definition refuses is never run.
    await expect(
      canvas.getByRole('table').querySelectorAll('tbody tr'),
    ).toHaveLength(0);
  },
};

export const CannotOpen: Story = {
  ...DisplayCannotOpen,
  play: async ({ canvasElement }) => {
    await expect(
      await within(canvasElement).findByRole('alert'),
    ).toHaveTextContent(defaultMessages['label.view.unopenable']);
  },
};
