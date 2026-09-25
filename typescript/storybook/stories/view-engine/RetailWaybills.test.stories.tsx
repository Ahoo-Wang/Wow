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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import displayMeta, {
  WaybillWideTable as DisplayWaybillWideTable,
} from './RetailWaybills.stories.js';
import { findDataTable, readColumn, readHeaders } from './readTable.js';

/**
 * 运单宽表的轻量孪生：20 列都在，包裹数、在途数，台风那一周中通在两广的签收
 * 时长（A2），数字是种子定下的黄金值。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/运单宽表/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

/** A view's title as the start of a pattern: its parentheses mean themselves. */
const escaped = (title: string) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

type Story = StoryObj<typeof displayMeta>;

const total = (canvasElement: HTMLElement) =>
  waitFor(() => {
    // The pager says it, and a live region repeats it for a screen reader.
    const [said] = within(canvasElement).getAllByText(/^共 [\d,]+ 条记录$/);
    return Number(said!.textContent!.replace(/\D/g, ''));
  });

export const WaybillWideTable: Story = {
  ...DisplayWaybillWideTable,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${escaped(title)}`) });

    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readHeaders(table)).toHaveLength(20));
    await waitFor(async () => expect(await total(canvasElement)).toBe(18967));

    await userEvent.click(view('在途包裹'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(47));

    // A2: the typhoon week, the slowest parcel first.
    await userEvent.click(view('台风期间：两广中通'));
    await waitFor(async () => expect(await total(canvasElement)).toBe(12));
    const typhoon = await findDataTable(canvasElement);
    await expect(new Set(readColumn(typhoon, '承运商'))).toEqual(
      new Set(['中通快递']),
    );

    await userEvent.click(view('两广：承运商 × 周的签收时长'));
    await waitFor(() => {
      const reading = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-reading"] table',
      );
      expect(reading).not.toBeNull();
      // A carrier a row, a week a column.
      const carriers = readColumn(reading!, '承运商');
      const week = readColumn(reading!, '2026年7月20日');
      expect(week[carriers.indexOf('中通快递')]).toMatch(/^119\.29/);
    });
  },
};
