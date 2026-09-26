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
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  ByMarket as DisplayByMarket,
  Records as DisplayRecords,
} from './MoneyFields.stories.js';
import { findDataTable, readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/金额与小数/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out: a file's own description would otherwise replace the
  // display meta's parameters, and the host application with them.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const MIXED = zhCN['label.value.mixed-currencies'];

/** The footer's cells under a header, as one line each. */
function footer(table: HTMLElement, header: string): string[] {
  const heads = [
    ...((table as HTMLTableElement).tHead?.rows[0]?.cells ?? []),
  ].map(cell => cell.textContent?.trim() ?? '');
  const index = heads.findIndex(text => text.startsWith(header));
  return [...((table as HTMLTableElement).tFoot?.rows ?? [])].map(
    row => row.cells[index]?.textContent?.trim() ?? '',
  );
}

/**
 * Each amount in its own order's currency, the yen shipping with no
 * decimals and the weight to three; the amounts of a page in three
 * currencies add up to no amount, and the footer says so.
 */
export const EachInItsCurrency: Story = {
  ...DisplayRecords,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '金额')).toContain('JP¥12,800.00'),
    );
    await expect(readColumn(table, '金额')).toEqual(
      expect.arrayContaining(['¥1,299.50', 'US$129.99', 'US$45.00']),
    );
    await expect(readColumn(table, '运费')).toContain('JP¥1,200');
    await expect(readColumn(table, '重量（kg）')).toContain('1.250');
    await waitFor(() =>
      expect(footer(table, '金额').every(text => text.includes(MIXED))).toBe(
        true,
      ),
    );
    await expect(
      footer(table, '运费').some(text => text.includes('JP¥10,500')),
    ).toBe(true);
  },
};

/**
 * A market holding two currencies has no total and says why; one press
 * groups by currency, and every row is then in one.
 */
export const GroupsByCurrency: Story = {
  ...DisplayByMarket,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '金额的总和')).toEqual(
        expect.arrayContaining(['JP¥17,300.00', MIXED, 'US$399.49']),
      ),
    );
    await expect(readColumn(table, '运费的总和')).toEqual(
      expect.arrayContaining(['JP¥2,000', 'JP¥2,100', 'JP¥6,400']),
    );
    await expect(footer(table, '金额的总和')).toContain(MIXED);

    const offer = await canvas.findByRole('button', {
      name: '按「币种」分组',
    });
    await expect(
      canvasElement.querySelector('[data-slot="analysis-currency"]'),
    ).toHaveTextContent('有的组的记录分属几种货币');
    await userEvent.click(offer);

    await waitFor(async () => {
      const grouped = await findDataTable(canvasElement);
      const amounts = readColumn(grouped, '金额的总和');
      await expect(amounts).toHaveLength(4);
      await expect(amounts).not.toContain(MIXED);
      await expect(amounts).toEqual(
        expect.arrayContaining(['¥1,389.40', 'US$45.00']),
      );
    });
    await expect(
      canvas.queryByRole('button', { name: '按「币种」分组' }),
    ).toBeNull();
  },
};
