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
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { formatMessage, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  TableWithTotals as DisplayTableWithTotals,
} from './AnalysisWorkbench.stories.js';
import { findDataTable, readColumn, readHeaders } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/指标与保留/回归',
  tags: ['!dev', '!autodocs', 'test'],
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 写出来的指标、只保留的分组、按两个别名排的顺序，在真浏览器里走一遍
 * （D20 屏 B）。
 *
 * 这三件事在 jsdom 里钉的是「按下去写了什么」
 * （`packages/view-engine/test/havingRows.test.tsx` 与
 * `test/formulaCard.test.tsx`）；这里钉的是走完一遍之后**屏幕上的那张表
 * 变了**——少了两行、多了一列、行序换了。一份假答案会让前两件事照样通过，
 * 所以故事的数据源真的按查询分组、筛选、排序（`rowSource.ts`）。
 */

/** The tray's handle in the title bar; the one button of its group. */
function trayToggle(canvasElement: HTMLElement): HTMLElement {
  return within(
    canvasElement.querySelector<HTMLElement>('[data-slot="editor-toggle"]')!,
  ).getByRole('button');
}

const tray = (canvasElement: HTMLElement) =>
  canvasElement.querySelector<HTMLElement>('[data-slot="analysis-tray"]');

async function openTray(canvasElement: HTMLElement): Promise<HTMLElement> {
  await userEvent.click(trayToggle(canvasElement));
  await waitFor(() => expect(tray(canvasElement)).not.toBeNull());
  return tray(canvasElement)!;
}

/** The one button that runs the draft. */
function applyButton(canvasElement: HTMLElement): HTMLElement {
  return within(
    canvasElement.querySelector<HTMLElement>(
      '[data-slot="analysis-tray-actions"]',
    )!,
  ).getByRole('button', { name: zhCN['label.filter.apply'] });
}

/** How many groups the result drew. */
const groupRows = (table: HTMLElement) =>
  (table as HTMLTableElement).tBodies[0]?.rows.length ?? 0;

/**
 * 指标在托盘与排序编辑器里就叫它测量的那个字段——「金额」——汇总方式是
 * 它旁边那个控件说的；「金额 的 合计」是结果表的**列头**，两个部分在那里
 * 才合成一句（`columnTitle`）。
 */
const AMOUNT_METRIC = '金额';

/** 「金额 − 成本 的 合计」: a formula's own words, then how it was summarised. */
const MARGIN_HEADER = formatMessage(zhCN, 'label.summary.of', {
  field: '金额 − 成本',
  fn: zhCN['label.summary.fn.SUM'],
});

/**
 * 「只保留」：一行一条比较，跑完之后表上真的少了两组。
 *
 * 四个仓库的金额合计是 1920／2450／4880／980，「金额 的 合计 大于 2000」
 * 之后只剩华北与华南。它是聚合之后、排序与截断之前的一道筛选，所以它减少
 * 的是**组**，不是记录——一条画在条件面板里的筛选做不到这件事，这也是它
 * 为什么不在范围里。
 */
export const KeepOnly: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));

    const opened = await openTray(canvasElement);
    await userEvent.click(
      opened.querySelector<HTMLElement>('[data-slot="add-having"]')!,
    );
    const row = await waitFor(() => {
      const found = opened.querySelector<HTMLElement>(
        '[data-slot="having-row"]',
      );
      if (!found) throw new Error('「只保留」那一行没有出来');
      return found;
    });

    // Which metric keeps a group: the sample values are not offered, because
    // Wow refuses a having over one.
    await userEvent.click(
      within(row).getByLabelText(zhCN['label.analysis.having-metric']),
    );
    await userEvent.click(
      await screen.findByRole('option', { name: AMOUNT_METRIC }),
    );

    await userEvent.type(
      within(row).getByLabelText(zhCN['label.analysis.having-value']),
      '2000',
    );
    await userEvent.click(applyButton(canvasElement));

    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(after)).toBe(2));
    await expect(readColumn(after, '仓库')).toEqual(['华北', '华南']);
  },
};

/**
 * 公式：两个字段一次运算，逐条算完再汇总，屏幕上多出一列。
 *
 * 「金额 − 成本」在**每一条记录上**算一次、再在组里合计，这与「金额合计
 * 减 成本合计」在合计上碰巧相等、在平均上并不相等——数据源真的按表达式
 * 算，所以这一列的数是查询答的，不是故事写死的。
 */
export const Formula: Story = {
  ...DisplayTableWithTotals,
  args: { ...DisplayTableWithTotals.args, allColumns: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));
    await expect(readHeaders(table)).not.toContain(MARGIN_HEADER);

    await openTray(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.analysis.add-metric'] }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.analysis.add-formula'],
      }),
    );

    // The card is named by what it says, because no field stands behind it.
    await waitFor(() =>
      expect(
        [...canvasElement.querySelectorAll('[data-slot="card-name"]')].map(
          name => name.textContent,
        ),
      ).toContain('金额 − 成本'),
    );

    await userEvent.click(applyButton(canvasElement));

    const after = await findDataTable(canvasElement);
    await waitFor(() => expect(readHeaders(after)).toContain(MARGIN_HEADER));
    // 华东 1920 − 1400 = 520；这一列的数由数据源逐条算出来。
    await expect(readColumn(after, MARGIN_HEADER)[0]).toContain('520');
  },
};

/**
 * 排序：与记录视图同一个控件，所以「先按哪个、再按哪个」说得出来。
 *
 * 先按记录数、再按金额：记录数 1 的两组（华北 2450、西南 980）排在前面，
 * 组内按金额升序，于是西南在华北之前。一个只装得下一条排序的控件说不出
 * 这句话——它只能在两组并列时听天由命。
 */
export const SortedByTwo: Story = {
  ...DisplayTableWithTotals,
  play: async ({ canvasElement }) => {
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(groupRows(table)).toBe(4));

    const opened = await openTray(canvasElement);
    await userEvent.click(
      opened.querySelector<HTMLElement>(
        '[data-slot="analysis-sort"] [data-control="sort"]',
      )!,
    );
    const editor = await screen.findByRole('dialog');

    for (const name of [zhCN['label.analysis.row-count'], AMOUNT_METRIC]) {
      await userEvent.click(
        within(editor).getByRole('button', { name: zhCN['label.sort.add'] }),
      );
      await userEvent.click(await screen.findByRole('menuitem', { name }));
    }

    await expect(
      [...editor.querySelectorAll('[data-slot="sort-entry"]')].map(entry =>
        entry.getAttribute('data-field'),
      ),
    ).toEqual(['orders', 'amount']);

    await userEvent.keyboard('{Escape}');
    await userEvent.click(applyButton(canvasElement));

    const after = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(after, '仓库')).toEqual([
        '西南',
        '华北',
        '华东',
        '华南',
      ]),
    );
  },
};
