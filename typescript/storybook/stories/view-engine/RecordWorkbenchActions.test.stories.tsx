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
  ExportCapped as DisplayExportCapped,
  ExportFailed as DisplayExportFailed,
  ExportResult as DisplayExportResult,
  ExportRunning as DisplayExportRunning,
  WithActions as DisplayWithActions,
} from './RecordWorkbench.stories.js';
import { readColumn } from './readTable.js';
import { PENDING_BY_AMOUNT, say, settled } from './recordWorkbenchTest.js';

/**
 * Bulk and host actions: the slots, a bulk command, range selection, the one
 * primary, and the export window. One of the record workbench's regression
 * files, split by concern; they all share one title, so every story keeps its
 * id, and the helpers more than one of them needs are in
 * `recordWorkbenchTest.ts`.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * The host's three slots, each where it belongs: over the view, over a
 * selection, and on one row. The middle one exists only while rows are picked.
 */
export const WithActions: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    await expect(
      within(
        canvasElement.querySelector<HTMLElement>('[data-slot="view-header"]')!,
      ).getByRole('button', { name: '新建订单' }),
    ).toBeVisible();
    await expect(canvas.queryByRole('button', { name: '导出所选' })).toBeNull();

    // The line between this package's controls and the host's own action
    // stands centred on the row it parts — not parked at its top, which is
    // where a stretched item with a height of its own ends up.
    const controls = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    const divider = controls.querySelector<HTMLElement>(
      '[data-slot="separator"]',
    )!;
    const middle = (rect: DOMRect) => rect.top + rect.height / 2;
    await expect(
      Math.abs(
        middle(divider.getBoundingClientRect()) -
          middle(controls.getBoundingClientRect()),
      ),
    ).toBeLessThanOrEqual(1);

    // One row action per row, in a column pinned to the end of the table.
    await expect(canvas.getAllByRole('button', { name: '打开' })).toHaveLength(
      PENDING_BY_AMOUNT.length,
    );
    await expect(
      canvas
        .getByRole('columnheader', {
          name: zhCN['label.toolbar.actions'],
        })
        .classList.contains('fve:sticky'),
    ).toBe(true);

    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await expect(
      await canvas.findByRole('button', { name: '导出所选' }),
    ).toBeVisible();
  },
};

/**
 * 批量命令从按下到落定，工作台在行的上方说清它：跑到第几条、结局是什么、为什么
 * 有的没做成，而没做成的那几行仍选着——它们就是还要处理的行。
 *
 * 宿主只写了「导出一单」这一件事，其余（几条一起跑、进度、逐条原因、选择怎么办、
 * 刷新）都来自工作台自己的执行器（插槽上下文的 `run`），那一条由工作台画在结果区。先在
 * 待出库订单里全选导出，全都做成：选择放开，那一条还在；再到「全部订单」全选导出，
 * 已取消的 SO-1002 被拒：那一条说出拒绝的原因与条数，SO-1002 仍选着。
 */
export const BulkOutcomeOutlivesTheSelection: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    const line = (state: 'running' | 'settled') =>
      waitFor(() => {
        const found = canvasElement.querySelector<HTMLElement>(
          `[data-slot="bulk-status"][data-state="${state}"]`,
        );
        expect(found).not.toBeNull();
        return found!;
      });

    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: '导出所选' }),
    );

    // While it runs the button is disabled, so a second press cannot send a
    // second write over the same rows, and the line counts as it goes.
    await expect(
      canvas.getByRole('button', { name: '导出所选' }),
    ).toBeDisabled();
    const running = await line('running');
    await expect(
      within(running).getByRole('button', { name: zhCN['label.bulk.stop'] }),
    ).toBeVisible();

    const done = await line('settled');
    // A run everything took is a note, not an interruption.
    await expect(done).toHaveAttribute('role', 'status');
    await expect(done).toHaveAttribute('data-tone', 'info');
    await expect(done).toHaveTextContent(
      say('label.bulk.done', { done: PENDING_BY_AMOUNT.length }),
    );
    // Everything took it, so the selection is let go — and the line is not.
    await expect(canvas.queryByRole('button', { name: '导出所选' })).toBeNull();
    await userEvent.click(
      within(done).getByRole('button', { name: zhCN['label.bulk.dismiss'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="bulk-status"]'),
      ).toBeNull(),
    );

    // Over every order, the cancelled one is refused: its reason is said
    // with how many gave it, and it is the one row left selected.
    await userEvent.click(canvas.getByRole('button', { name: /^全部订单/ }));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '订单号')).toContain(
        'SO-1002',
      ),
    );
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: '导出所选' }),
    );
    const partial = await line('settled');
    await expect(partial).toHaveAttribute('data-tone', 'warning');
    await expect(partial).toHaveTextContent(
      say('label.bulk.reason', { reason: '已取消的订单不能导出。', count: 1 }),
    );
    await expect(partial).toHaveTextContent(zhCN['label.bulk.left']);
    await waitFor(() =>
      expect(
        canvas.getByLabelText(say('label.record.select', { key: 'SO-1002' })),
      ).toBeChecked(),
    );
    await expect(
      canvas.getByLabelText(say('label.record.select', { key: 'SO-1001' })),
    ).not.toBeChecked();
  },
};

/**
 * 按住 Shift 勾选一段：从上一次平点的那一行到按下的这一行，按结果顺序整段选中
 * 或取消（`RecordTableController.toggle(key, { range })`）。
 *
 * 真浏览器里走一遍 jsdom 走不到的那条链：Base UI 的勾选框把根上的点击连同修饰
 * 键转发给它藏着的 `<input>`，`onCheckedChange` 读到的原生事件才带着 Shift；
 * 键盘那一路是根上 keyup 的空格被转成同样带修饰键的点击。两条路任何一环丢了
 * Shift，这里读到的就只剩一行。工具栏的计数与每行的 `data-state` 是用户眼里的
 * 两处读数，一并核对。
 */
export const ShiftSelectsARange: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    const boxes = () => [
      ...table.querySelectorAll<HTMLElement>('tbody [role="checkbox"]'),
    ];
    const picked = () =>
      boxes().map(box => box.getAttribute('aria-checked') === 'true');
    // The rows' own body: in a host that gives the workbench a height, the
    // room the rows leave is a second, hidden body with a row of its own.
    const selectedRows = () =>
      [...(table as HTMLTableElement).tBodies[0].rows].map(
        row => row.getAttribute('data-state') === 'selected',
      );
    // One instance for the whole gesture, so the Shift held down is still
    // held when the press lands.
    const user = userEvent.setup();

    // A plain press sets the anchor; a Shift+press two rows down takes the
    // rows between with it.
    await user.click(boxes()[0]);
    await user.keyboard('{Shift>}');
    await user.click(boxes()[2]);
    await user.keyboard('{/Shift}');
    await waitFor(() => expect(picked()).toEqual([true, true, true, false]));
    await expect(selectedRows()).toEqual([true, true, true, false]);
    await expect(
      await canvas.findByText(say('label.toolbar.selected', { count: 3 })),
    ).toBeVisible();

    // Shift+Space on a focused checkbox is the same gesture, and the anchor
    // is still the first row: the range did not move it.
    boxes()[3].focus();
    await user.keyboard('{Shift>}[Space]{/Shift}');
    await waitFor(() => expect(picked()).toEqual([true, true, true, true]));

    // Shift on a picked row clears the range, the way that row goes.
    await user.keyboard('{Shift>}');
    await user.click(boxes()[1]);
    await user.keyboard('{/Shift}');
    await waitFor(() => expect(picked()).toEqual([false, false, true, true]));
    await expect(
      await canvas.findByText(say('label.toolbar.selected', { count: 2 })),
    ).toBeVisible();

    // What Shift does is said once, and every row checkbox points at it.
    const hints = new Set(
      boxes().map(box => box.getAttribute('aria-describedby')),
    );
    await expect(hints.size).toBe(1);
    await expect(document.getElementById([...hints][0]!)).toHaveTextContent(
      zhCN['label.record.select.hint'],
    );
  },
};

/**
 * 一屏只有一个 primary，它是跑查询的那个 Apply——宿主的全局动作不是（D12 Ⅰ）。
 *
 * D12 Ⅰ 原本写的是「宿主的主功能按钮，同屏唯一 primary」，而[动作槽位](
 * typescript/wow-view-engine/docs/design/ui/README.md)一直写着相反的规矩：编辑带一
 * 展开，屏幕上就有两个 primary。2026-09-21 用户裁定了后者——排在最右说的是
 * 「这是业务的去处」，不是「这是这一屏最该按的东西」。
 *
 * 这里不认类名认颜色：把编辑带打开，量遍这一屏上每一颗按钮的实际底色，与
 * Apply 同色的应当只有 Apply 自己。jsdom 不套样式表，这个数只有真浏览器给得
 * 出（`test/analysisUi.test.tsx` 按 variant 的类名钉的是结构那一半）。
 */
export const OnlyApplyIsPrimary: Story = {
  ...DisplayWithActions,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const host = canvas.getByRole('button', { name: '新建订单' });
    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    const apply = await canvas.findByRole('button', {
      name: zhCN['label.filter.apply'],
    });
    // The vendored button has `transition-all`, so the steady colour is what
    // is read — and the band has just opened.
    await settled(() => getComputedStyle(apply).backgroundColor);
    const primary = getComputedStyle(apply).backgroundColor;

    const alike = [...canvasElement.querySelectorAll<HTMLElement>('button')]
      .filter(button => getComputedStyle(button).backgroundColor === primary)
      .map(button => button.textContent?.trim());

    await expect(alike, `painted ${primary}`).toEqual([
      zhCN['label.filter.apply'],
    ]);
    // And the host's own action is drawn as an outline button: a fill it
    // shares with the surface behind it, and an edge of its own.
    const drawn = getComputedStyle(host);
    await expect(drawn.backgroundColor).not.toBe(primary);
    await expect(drawn.borderTopWidth).not.toBe('0px');
  },
};

/**
 * The export window: one button, one window, the whole journey (D14).
 *
 * Nothing is actually exported here. The file itself — its name, its header
 * and every value in it — is asserted in jsdom, where the browser's half is
 * a stub (`test/recordExportUi.test.tsx`); a real click would hand this
 * browser a download for nothing. What only a browser can answer is what the
 * window says at each step, and that it says it in the language the data is
 * in.
 */
export const ExportWindow: Story = {
  ...DisplayExportResult,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );

    // No menu anywhere: the toolbar's part in this is the one button.
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const unpicked = await within(document.body).findByRole('dialog');
    await expect(within(document.body).queryByRole('menu')).toBeNull();
    // Nothing picked, so there is no choice to draw — only what the file
    // will hold.
    await expect(within(unpicked).queryByRole('radio')).toBeNull();
    await expect(unpicked.textContent).toContain(
      say('label.export.rows', { count: 4 }),
    );
    await expect(unpicked.textContent).toContain(
      say('label.export.columns', {
        count: 4,
        names: ['订单号', '仓库', '状态', '金额'].join(
          zhCN['label.filter.join'],
        ),
      }),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );

    // The picked scope exists only once something is picked (D4), and it is
    // the one the window opens on.
    await userEvent.click(
      canvas.getByLabelText(zhCN['label.record.select-all']),
    );
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const picked = await within(document.body).findByRole('dialog');
    await expect(
      within(picked)
        .getAllByRole('radio')
        .map(radio => radio.getAttribute('aria-checked')),
    ).toEqual(['true', 'false']);
    await expect(picked.textContent).toContain(
      say('label.export.selected', { count: 4 }),
    );
    // The one scope whose rows are not the ones on screen says so.
    await expect(picked.textContent).toContain(
      say('label.export.all', { count: 4 }),
    );
    await userEvent.keyboard('{Escape}');
  },
};

/**
 * The window while the pages come in, and Escape as the answer it is.
 *
 * The menu this replaced had to refuse both Escape and a click outside,
 * because the cancel lived inside it; a window may be dismissed, and being
 * dismissed *is* the cancel.
 */
export const ExportRunningWindow: Story = {
  ...DisplayExportRunning,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.export.confirm'],
      }),
    );

    const bar = await within(dialog).findByRole('progressbar', {
      name: zhCN['label.export.running'],
    });
    await expect(bar.getAttribute('aria-valuemax')).toBe('4');
    await expect(within(dialog).getByRole('status').textContent).toBe(
      say('label.export.progress', { fetched: 0, total: 4 }),
    );

    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );
    // A cancel says nothing: it is the answer the user gave.
    await expect(
      canvasElement.querySelector('[data-slot="status-strip"]'),
    ).toBeNull();
  },
};

/**
 * The ceiling, said before the button and again after the file: the four
 * orders the saved condition matches, against a ceiling of two.
 */
export const ExportCappedWindow: Story = {
  ...DisplayExportCapped,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog');

    await expect(dialog.textContent).toContain(
      say('label.export.over-limit', { max: 2 }),
    );
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.export.confirm'],
      }),
    );

    await within(dialog).findByText(say('label.export.done', { count: 2 }));
    await expect(dialog.textContent).toContain(
      say('label.export.done-capped', { max: 2, total: 4 }),
    );
  },
};

/**
 * A failed export, reported where it happened and offered again from there.
 */
export const ExportFailedWindow: Story = {
  ...DisplayExportFailed,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>('[data-control="export"]')!,
    );
    const dialog = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.export.confirm'],
      }),
    );

    await within(dialog).findByText(/导出失败/);
    // The way out is in the window, not back through the toolbar.
    await expect(
      within(dialog).getByRole('button', { name: zhCN['label.export.retry'] }),
    ).toBeTruthy();
    // And nothing was said above the rows about it.
    await expect(
      canvasElement.querySelector('[data-slot="status-strip"]'),
    ).toBeNull();
  },
};
