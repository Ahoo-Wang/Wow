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
  SnapshotConsole as DisplaySnapshotConsole,
} from './ProductPricingSnapshot.stories.js';
import {
  RECORDED_PRICING_HOST,
  installRecordedPricingService,
} from './productPricingService.js';
import { readColumn } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';

/**
 * The product pricing snapshot console against a recorded service instead
 * of a live one.
 *
 * The display story stays off CI because a live service answers differently
 * every time. Its data is what varies; the definition, the system views and
 * the way the console reads a pricing — a nested product, money, a deadline
 * kept as epoch milliseconds — do not, and a rule change in View Engine can
 * break them without any service involved.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/真实后端/商品定价/快照控制台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes this file's own
  // description into a `parameters` of its meta, which would replace the
  // display story's — and with it the full-screen layout the console is
  // meant to be exercised in.
  parameters: { ...displayMeta.parameters },
  args: { host: RECORDED_PRICING_HOST },
  beforeEach: installRecordedPricingService,
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const bars = (canvasElement: HTMLElement) => drawnMarks(canvasElement);

export const SnapshotConsole: Story = {
  ...DisplaySnapshotConsole,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '生效中',
      '半年内到期',
      '已停用或过期',
      '全部定价',
      '按状态分布',
      '价格区间分布',
      '各品牌价格',
      '货期类型分布',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('生效中')).toHaveAttribute('aria-current', 'true');

    // The prices in force, a product's tiers together: by product, then the
    // soonest delivery, then the smallest quantity — the price falling as
    // the quantity rises. The stopped and the lapsed ones are not here.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '定价 ID')).toEqual([
        'PP-SK-QSH6-20000-10',
        'PP-SK-1000J-40812-1',
        'PP-SK-1000J-40812-10',
        'PP-SK-1000J-40812-100',
        'PP-SK-1000J-40812-1000',
        'PP-SK-1000T-20000-1',
        'PP-SK-1000T-20000-50',
        'PP-SK-1000T-20000-500',
      ]),
    );
    // Nested members read in words and money, not codes and bare numbers.
    await expect(readColumn(table, '商品编码').slice(0, 2)).toEqual([
      'IGYX 12N17B3/L',
      'LA423PF-10R',
    ]);
    await expect(readColumn(table, '货期类型').slice(0, 2)).toEqual([
      '代理现货',
      '期货',
    ]);
    const prices = readColumn(table, '单价');
    await expect(prices[1]).toMatch(/4,200\.00/);
    await expect(prices[1]).toMatch(/¥/);
    await expect(readColumn(table, '状态')[0]).toBe('生效中');
    // The deadline is epoch milliseconds, read as a date.
    const [deadline] = readColumn(table, '有效期至');
    await expect(deadline).toMatch(/^\d{4}年\d{1,2}月\d{1,2}日$/);

    // Lapsing within six months: the one whose deadline is two months off.
    await userEvent.click(view('半年内到期'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '定价 ID')).toEqual([
        'PP-SK-QSH6-20000-10',
      ]),
    );

    // Stopped or lapsed, each in its own words.
    await userEvent.click(view('已停用或过期'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '状态')).toEqual([
        '已过期',
        '已停用',
      ]),
    );

    await userEvent.click(view('全部定价'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '定价 ID')).toHaveLength(10),
    );

    // The analyses: one bar per status the pricings hold.
    await userEvent.click(view('按状态分布'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(bars(canvasElement)).toHaveLength(3));

    // Where the prices sit, in bands of five hundred: each band reads as
    // the band it is, not as its lower bound — 「¥0.00」「¥3,000.00」 on
    // this service's own data said nothing about which band a bar was
    // (2026-09-23). The reading table names each bar as the axis does.
    await userEvent.click(view('价格区间分布'));
    await waitFor(() => {
      const reading = canvasElement.querySelector<HTMLTableElement>(
        '[data-slot="chart-reading"] table',
      );
      expect(
        [...(reading?.tBodies[0]?.rows ?? [])].map(
          row => row.cells[0]?.textContent,
        ),
      ).toEqual(['¥0～500', '¥3,000～3,500', '¥3,500～4,000', '¥4,000～4,500']);
    });

    // Each brand's range, and its earliest deadline as a date. The two
    // brands of one pricing each tie, in no order the count decides.
    await userEvent.click(view('各品牌价格'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '品牌')).toHaveLength(3),
    );
    const analysis = canvas.getByRole('table');
    await expect(readColumn(analysis, '品牌')[0]).toBe('上海天逸电器');
    await expect(readColumn(analysis, '定价数')).toEqual(['8', '1', '1']);
    await expect(readColumn(analysis, '最低单价')[0]).toMatch(/79\.80/);
    const [earliest] = readColumn(analysis, '最早到期');
    await expect(earliest).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);
    await expect(earliest).not.toMatch(/^\d{12,}$/);

    await userEvent.click(view('货期类型分布'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '货期类型')).toEqual([
        '代理现货',
        '期货',
        '现货',
      ]),
    );
  },
};
