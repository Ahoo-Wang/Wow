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
  OneThousandRows as DisplayOneThousandRows,
  TenThousandRows as DisplayTenThousandRows,
  TenThousandRowsOnABoard as DisplayTenThousandRowsOnABoard,
} from './LongTables.stories.js';
import { expectBandsHeld } from './stickyBands.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/分析视图/长表/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The line a header sort of ten thousand rows is held to on CI. Drawn whole,
 * the sort took about 1.1s on a developer's machine in all three engines —
 * the table redrew every row — and drawn virtually about 80ms (ui/analysis.md
 * 「长表」). The line sits between the two: a slow runner's jitter stays
 * under it, a table that draws every row again does not.
 */
const SORT_GUARD_MS = 1000;

/** The most rows a virtually drawn table may hold at once. */
const DRAWN_AT_MOST = 200;

const portOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="analysis-table"]')!;

const drawnRows = (root: HTMLElement) => [
  ...portOf(root).querySelectorAll<HTMLTableRowElement>('tbody tr[data-index]'),
];

/** The rows landed: the long source answers at once, the table draws. */
const landed = (root: HTMLElement) =>
  waitFor(() => expect(drawnRows(root).length).toBeGreaterThan(0), {
    timeout: 12_000,
  });

const frame = () =>
  new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)));

/**
 * 一万行（`maxAnalysisRows` 调到 Wow 的上限）只画看得见的那些：表格说自己有一万零两行（表头、
 * 一万组、合计），每一行说自己是第几行；滚到中间，中间就有行。点「客户」的表头
 * 重排三次（升序、降序、回到视图自己的顺序），从按下到第一行换掉、画完，守
 * `SORT_GUARD_MS`。
 */
export const TenThousandRowsDrawWhatIsInView: Story = {
  ...DisplayTenThousandRows,
  play: async ({ canvasElement }) => {
    await landed(canvasElement);
    const port = portOf(canvasElement);
    const table = port.querySelector('table')!;
    await expect(table).toHaveAttribute('aria-rowcount', '10002');
    await expect(drawnRows(canvasElement).length).toBeLessThan(DRAWN_AT_MOST);
    await expect(drawnRows(canvasElement)[0]).toHaveAttribute(
      'aria-rowindex',
      '2',
    );
    await expect(
      port.querySelector('[data-slot="totals-row"]'),
    ).toHaveAttribute('aria-rowindex', '10002');
    // The room the rows not drawn take is nothing a reader meets.
    const gaps = port.querySelectorAll('[data-slot="row-gap"]');
    await expect(gaps.length).toBeGreaterThan(0);
    for (const gap of gaps)
      await expect(gap).toHaveAttribute('aria-hidden', 'true');

    // Scrolled to the middle, the middle is rows.
    port.scrollTop = port.scrollHeight / 2;
    await waitFor(() => {
      const box = port.getBoundingClientRect();
      const middle = document.elementFromPoint(
        box.left + 40,
        box.top + box.height / 2,
      );
      expect(middle?.closest('tr[data-index]')).not.toBeNull();
    });
    await expect(drawnRows(canvasElement).length).toBeLessThan(DRAWN_AT_MOST);
    port.scrollTop = 0;

    // A header press sorts ten thousand groups again: by customer, up, down,
    // then back to the view's own order — a new first row every time.
    const header = within(port.querySelector('thead')!).getByRole('button', {
      name: /客户/,
    });
    const first = () => drawnRows(canvasElement)[0]?.textContent;
    const times: number[] = [];
    for (let round = 0; round < 3; round += 1) {
      const before = first();
      const start = performance.now();
      await userEvent.click(header);
      await waitFor(() => expect(first()).not.toBe(before), {
        timeout: 12_000,
      });
      await frame();
      times.push(performance.now() - start);
    }
    console.info('10k-row header sort (ms):', times.map(Math.round));
    // A regression guard, not the budget (`SORT_GUARD_MS`).
    await expect(Math.min(...times)).toBeLessThan(SORT_GUARD_MS);
    await expect(drawnRows(canvasElement).length).toBeLessThan(DRAWN_AT_MOST);
  },
};

/**
 * 键盘在一万行里走：结果的行仍是一个 Tab 停靠点。End 到最后一组——它原本没
 * 画，滚到眼前、拿到焦点，停靠点跟着它；↑ 回到上一组；Home 回到第一组，落在
 * 粘住的表头下面。滚轮把它滚走，焦点仍在它上面（拿着停靠点的那一行一直画着），
 * Shift+Tab 出去再 Tab 回来，回到的还是它。
 */
export const KeyboardWalksTenThousandRows: Story = {
  ...DisplayTenThousandRows,
  play: async ({ canvasElement }) => {
    await landed(canvasElement);
    const port = portOf(canvasElement);
    const stop = () =>
      port.querySelectorAll('tbody tr[data-pickable][tabindex="0"]');
    const firstRow = drawnRows(canvasElement)[0];
    firstRow.focus();
    await expect(firstRow).toHaveFocus();

    await userEvent.keyboard('{End}');
    await waitFor(() =>
      expect(document.activeElement).toHaveAttribute('aria-rowindex', '10001'),
    );
    const last = document.activeElement as HTMLElement;
    await expect(last).toHaveTextContent('客户 00001');
    await expect(stop()).toHaveLength(1);
    await waitFor(() => {
      const row = last.getBoundingClientRect();
      const totals = port
        .querySelector('[data-slot="totals-row"]')!
        .getBoundingClientRect();
      expect(row.bottom).toBeLessThanOrEqual(totals.top + 1);
    });

    await userEvent.keyboard('{ArrowUp}');
    await expect(document.activeElement).toHaveAttribute(
      'aria-rowindex',
      '10000',
    );

    await userEvent.keyboard('{Home}');
    await waitFor(() =>
      expect(document.activeElement).toHaveAttribute('aria-rowindex', '2'),
    );
    const home = document.activeElement as HTMLElement;
    await waitFor(() => {
      const head = port.querySelector('thead')!.getBoundingClientRect();
      expect(home.getBoundingClientRect().top).toBeGreaterThanOrEqual(
        head.bottom - 1,
      );
    });

    // Scrolled away, the row holding the stop stays drawn and focused.
    port.scrollTop = port.scrollHeight / 2;
    await frame();
    await expect(home).toBeInTheDocument();
    await expect(home).toHaveFocus();

    await userEvent.tab({ shift: true });
    await expect(home).not.toHaveFocus();
    await userEvent.tab();
    await expect(home).toHaveFocus();
  },
};

/**
 * 打印时每一行都画出来：纸上没有滚动位置。浏览器说要打印（`beforeprint`）的那
 * 一刻同步画齐一万行，打印完（`afterprint`）再回到只画看得见的。
 */
export const PrintingDrawsEveryRow: Story = {
  ...DisplayTenThousandRows,
  play: async ({ canvasElement }) => {
    await landed(canvasElement);
    window.dispatchEvent(new Event('beforeprint'));
    await expect(drawnRows(canvasElement)).toHaveLength(10_000);
    await expect(
      portOf(canvasElement).querySelector('table'),
    ).not.toHaveAttribute('aria-rowcount');
    window.dispatchEvent(new Event('afterprint'));
    await waitFor(() =>
      expect(drawnRows(canvasElement).length).toBeLessThan(DRAWN_AT_MOST),
    );
  },
};

/**
 * 仪表盘面板里滚的是面板的内容区，不是表格自己：行照样跟着它画，滚到中间，
 * 中间就有行。
 */
export const BoardPanelDrawsWhatIsInView: Story = {
  ...DisplayTenThousandRowsOnABoard,
  play: async ({ canvasElement }) => {
    await landed(canvasElement);
    await expect(drawnRows(canvasElement).length).toBeLessThan(DRAWN_AT_MOST);
    const scroller = portOf(canvasElement).closest<HTMLElement>(
      '[data-slot="card-content"]',
    )!;
    scroller.scrollTop = scroller.scrollHeight / 2;
    await waitFor(() => {
      const box = scroller.getBoundingClientRect();
      const middle = document.elementFromPoint(
        box.left + 40,
        box.top + box.height / 2,
      );
      expect(middle?.closest('tr[data-index]')).not.toBeNull();
    });
    await expect(drawnRows(canvasElement).length).toBeLessThan(DRAWN_AT_MOST);
  },
};

/**
 * 一千行不到门槛，照旧整张画出：浏览器的页内查找与读屏的浏览模式都读得到每
 * 一行，表格也不必说自己有几行。
 */
export const OneThousandRowsDrawWhole: Story = {
  ...DisplayOneThousandRows,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(drawnRows(canvasElement)).toHaveLength(1_000), {
      timeout: 12_000,
    });
    await expect(
      portOf(canvasElement).querySelector('table'),
    ).not.toHaveAttribute('aria-rowcount');
    await expect(
      portOf(canvasElement).querySelector('[data-slot="row-gap"]'),
    ).toBeNull();
  },
};

/**
 * The header and the totals hold at the two ends of the table's own port in
 * the workbench, drawn whole (a thousand groups) and drawn virtually (ten
 * thousand), and against the panel's body on a board.
 */
export const OneThousandRowsHoldTheirBands: Story = {
  ...DisplayOneThousandRows,
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(drawnRows(canvasElement)).toHaveLength(1_000), {
      timeout: 12_000,
    });
    const port = portOf(canvasElement);
    await expectBandsHeld(port, port);
  },
};

export const TenThousandRowsHoldTheirBands: Story = {
  ...DisplayTenThousandRows,
  play: async ({ canvasElement }) => {
    await landed(canvasElement);
    const port = portOf(canvasElement);
    await expectBandsHeld(port, port);
  },
};

export const BoardPanelHoldsItsBands: Story = {
  ...DisplayTenThousandRowsOnABoard,
  play: async ({ canvasElement }) => {
    await landed(canvasElement);
    const port = portOf(canvasElement);
    await expectBandsHeld(
      port.closest<HTMLElement>('[data-slot="card-content"]')!,
      port,
    );
  },
};
