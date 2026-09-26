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
import type { ComponentType } from 'react';
import type { StoryObj } from '@storybook/react-vite';
import { expect, screen, userEvent, waitFor, within } from 'storybook/test';
import { BUILT_IN_PRESETS, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  AllPanels as DisplayAllPanels,
  Building as DisplayBuilding,
  EmptySharedBoard as DisplayEmptySharedBoard,
  OwnedAnalysis as DisplayOwnedAnalysis,
  PersonalViewOnSharedBoard as DisplayPersonalViewOnSharedBoard,
  SystemDashboard as DisplaySystemDashboard,
  Tabs as DisplayTabs,
} from './Dashboard.stories.js';
import { aggregateCalls } from './fixtures.js';
import { chartsDrawn } from './chartDom.js';
import { dragHandleOnto } from './pointerDrag.js';
import { converter, formatRgb, parse } from 'culori';

const toRgb = converter('rgb');

const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/仪表盘/搭建',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, as in `Dashboard.test.stories.tsx`: a file's own
  // description would otherwise replace the display meta's parameters.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * A desk: the test browser is a phone's width, and below `md` the board is
 * one derived column in which building is renaming, removing and reordering
 * alone.
 */
const DESK = (Story: ComponentType) => (
  <div style={{ width: 1280 }}>
    <Story />
  </div>
);

const label = (key: keyof typeof zhCN, params: Record<string, string> = {}) =>
  Object.entries(params).reduce<string>(
    (text, [name, value]) => text.replace(`{${name}}`, value),
    zhCN[key],
  );

/** The panel titles on the board, top to bottom and left to right. */
function titles(canvasElement: HTMLElement): string[] {
  return [...canvasElement.querySelectorAll<HTMLElement>('.react-grid-item')]
    .map(item => ({
      box: item.getBoundingClientRect(),
      title: item.querySelector('[data-slot="panel-title"]')?.textContent ?? '',
    }))
    .sort((a, b) => a.box.top - b.box.top || a.box.left - b.box.left)
    .map(item => item.title);
}

/** 「＋ 添加」 on the edit bar, then one of its entries. */
async function addFromBar(canvasElement: HTMLElement, entry: string) {
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: zhCN['label.dashboard.add'],
    }),
  );
  await userEvent.click(await screen.findByRole('menuitem', { name: entry }));
}

/** A saved view picked from the picker, which then closes. */
async function pick(title: string): Promise<HTMLElement> {
  const picker = await screen.findByRole('dialog', {
    name: zhCN['label.picker.add-heading'],
  });
  const row = await within(picker).findByRole('button', {
    name: new RegExp(`^${title}`),
  });
  await userEvent.click(row);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  return row;
}

/**
 * Whether the title bar commits and rolls back the board itself — its Save
 * group on screen — or leaves both to the edit bar, with no 「已修改 ↺」
 * either (D22 A: one way to do one thing).
 */
async function expectTitleBarCommits(
  canvasElement: HTMLElement,
  commits: boolean,
) {
  const bar = canvasElement.querySelector<HTMLElement>(
    '[data-slot="view-header"]',
  )!;
  await expect(bar.querySelector('[data-slot="save-actions"]') !== null).toBe(
    commits,
  );
  if (!commits) {
    await expect(bar.querySelector('[data-slot="view-unsaved"]')).toBeNull();
    await expect(bar.querySelector('[data-slot="view-revert"]')).toBeNull();
  }
}

/** One panel's 「⋯」, then one of its entries. */
async function fromPanelMenu(
  canvasElement: HTMLElement,
  panel: string,
  entry: string,
) {
  // The menu chosen from last may still be on its way out — a busy
  // browser draws its exit slowly — and an item found in it answers
  // nothing: wait for it to be gone before opening the next.
  await waitFor(() =>
    expect(
      document.querySelector('[data-slot="dropdown-menu-content"]'),
    ).toBeNull(),
  );
  await userEvent.click(
    within(canvasElement).getByRole('button', {
      name: label('label.panel.menu', { title: panel }),
    }),
  );
  await userEvent.click(await screen.findByRole('menuitem', { name: entry }));
}

/**
 * The board built from nothing with the screen alone (D22 A, B, D): three
 * saved views from the picker — one of them the author's own, which a
 * shared board marks 「只有你看得到」 before it goes on — and a heading named
 * in place; one panel renamed, one removed; 保存 asks, as Save does over a
 * shared view, and saves.
 */
export const BuildFromEmpty: Story = {
  ...DisplayEmptySharedBoard,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', {
        name: zhCN['label.dashboard.empty.add-view'],
      }),
    );
    // The first step starts the building with it (read off the page: the
    // picker is modal, and what is under it is out of the reading order).
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
    ).not.toBeNull();
    await pick('待出库订单');
    await waitFor(() => expect(titles(canvasElement)).toEqual(['待出库订单']));
    // A table takes the whole width of the board.
    const grid = canvasElement
      .querySelector('.react-grid-layout')!
      .getBoundingClientRect();
    const first = canvasElement
      .querySelector('.react-grid-item')!
      .getBoundingClientRect();
    await expect(first.width).toBeGreaterThan(grid.width * 0.9);

    await addFromBar(canvasElement, zhCN['label.dashboard.add.saved-view']);
    await pick('仓库金额分布');
    await addFromBar(canvasElement, zhCN['label.dashboard.add.saved-view']);
    const picker = await screen.findByRole('dialog');
    const own = await within(picker).findByRole('button', {
      name: /^我盯的大额单/,
    });
    await expect(own).toHaveTextContent(zhCN['label.picker.private']);
    // Already on the board, and still offered.
    await expect(
      within(picker).getByRole('button', { name: /^待出库订单/ }),
    ).toHaveTextContent(zhCN['label.picker.on-board']);
    await pick('我盯的大额单');
    await waitFor(() => expect(titles(canvasElement)).toHaveLength(3));

    await addFromBar(canvasElement, zhCN['label.dashboard.add.heading']);
    const heading = await canvas.findByRole('textbox', {
      name: zhCN['label.panel.heading-input'],
    });
    await expect(heading).toHaveFocus();
    await userEvent.clear(heading);
    await userEvent.type(heading, '出库{Enter}');
    await waitFor(() => expect(titles(canvasElement)).toContain('出库'));
    // Changed, and the title bar neither says so nor undoes it: 保存 and
    // 取消 on the edit bar are the one way to commit or roll back.
    await expectTitleBarCommits(canvasElement, false);

    await fromPanelMenu(
      canvasElement,
      '仓库金额分布',
      zhCN['label.panel.rename'],
    );
    const title = await canvas.findByRole('textbox', {
      name: zhCN['label.panel.title-input'],
    });
    await userEvent.clear(title);
    await userEvent.type(title, '仓库分布{Enter}');

    // Removed at once — 「撤销」 is the way back, and the keyboard is on it.
    await fromPanelMenu(
      canvasElement,
      '我盯的大额单',
      zhCN['label.panel.remove'],
    );
    await expect(screen.queryByRole('alertdialog')).toBeNull();
    await waitFor(() =>
      expect([...titles(canvasElement)].sort()).toEqual(
        ['仓库分布', '出库', '待出库订单'].sort(),
      ),
    );

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.save'] }),
    );
    const confirm = await screen.findByRole('alertdialog');
    await userEvent.click(
      within(confirm).getByRole('button', {
        name: zhCN['label.save.shared-confirm'],
      }),
    );
    // Saved and read again: no bar, no handles, nothing left unsaved.
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
      ).toBeNull(),
    );
    await expect(
      canvasElement.querySelector('[data-slot="panel-grip"]'),
    ).toBeNull();
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
    await expect(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    ).toHaveFocus();
    await expectTitleBarCommits(canvasElement, true);
  },
};

/** 取消 asks, then puts back the board as it was saved. */
export const CancelReverts: Story = {
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await addFromBar(canvasElement, zhCN['label.dashboard.add.heading']);
    await userEvent.keyboard('{Enter}');
    await waitFor(() =>
      expect(titles(canvasElement)).toContain(
        zhCN['label.dashboard.new-heading'],
      ),
    );
    // One way to do one thing: the edit bar holds 保存 and 取消, so the
    // title bar has neither its Save nor its 「已修改 ↺」 beside them.
    await expectTitleBarCommits(canvasElement, false);

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dialog.cancel'] }),
    );
    const question = await screen.findByRole('alertdialog');
    await userEvent.click(
      within(question).getByRole('button', { name: zhCN['label.save.revert'] }),
    );
    await waitFor(() =>
      expect(titles(canvasElement)).not.toContain(
        zhCN['label.dashboard.new-heading'],
      ),
    );
    await expect(titles(canvasElement)).toHaveLength(3);
    await expect(canvas.queryByText(zhCN['label.header.unsaved'])).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
    ).toBeNull();
    // Read again: the title bar saves as it did before 编辑.
    await expectTitleBarCommits(canvasElement, true);
  },
};

/**
 * A desk shorter than the board: the workbench's main area scrolls it, as
 * a host page with a fixed header would.
 */
const SHORT_DESK = (Story: ComponentType) => (
  <div style={{ width: 1280, height: 520, display: 'flex' }}>
    <Story />
  </div>
);

/** The nearest ancestor that scrolls an element. */
function scrollerOf(element: HTMLElement): HTMLElement {
  for (let at = element.parentElement; at; at = at.parentElement) {
    const { overflowY } = getComputedStyle(at);
    if (
      (overflowY === 'auto' || overflowY === 'scroll') &&
      at.scrollHeight > at.clientHeight
    )
      return at;
  }
  return document.scrollingElement as HTMLElement;
}

/**
 * Whether an element is inside the window and drawn over everything else
 * at its middle — in view, and not under a panel scrolled over it.
 */
function onTop(element: HTMLElement): boolean {
  const box = element.getBoundingClientRect();
  if (
    box.top < 0 ||
    box.left < 0 ||
    box.bottom > window.innerHeight ||
    box.right > window.innerWidth
  )
    return false;
  const hit = document.elementFromPoint(
    box.left + box.width / 2,
    box.top + box.height / 2,
  );
  return hit !== null && element.contains(hit);
}

/**
 * The edit bar stays in view while the board is built (R3b): scrolled past
 * where it sat, it sticks to the top of what scrolls the board, over the
 * panels, so 「取消」 and 「保存」 never scroll away with the building. It
 * was an embed's story until embeds stopped building (D36); the board is
 * built in the workbench alone.
 */
export const EditBarStaysInView: Story = {
  ...DisplayBuilding,
  decorators: [SHORT_DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    const bar = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="dashboard-edit-bar"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    const scroller = scrollerOf(bar);
    const sat = bar.getBoundingClientRect().top;
    const last = [
      ...canvasElement.querySelectorAll<HTMLElement>('.react-grid-item'),
    ].at(-1)!;
    last.scrollIntoView({ block: 'end' });
    await waitFor(() =>
      expect(scroller.scrollTop).toBeGreaterThan(
        sat - scroller.getBoundingClientRect().top,
      ),
    );
    // At the top of the box, both ways out drawn over the panels beneath.
    await waitFor(() =>
      expect(
        Math.abs(
          bar.getBoundingClientRect().top -
            Math.max(scroller.getBoundingClientRect().top, 0),
        ),
      ).toBeLessThan(20),
    );
    for (const way of ['label.dialog.cancel', 'label.dashboard.save'] as const)
      await expect(
        onTop(within(bar).getByRole('button', { name: zhCN[way] })),
      ).toBe(true);
  },
};

/**
 * Between the title bar and the edit bar there is only the board's ground
 * (docs/design/ui/dashboard.md, the user on 2026-09-25): the rem the edit bar
 * covers above itself, so panels do not show through a scroller's padding
 * while it is stuck, is the canvas a board stands on. It was `background`,
 * a white strip under porcelain's grey ground that read as an empty row.
 * Every preset, light and dark: each point of the gap is transparent or the
 * canvas, whichever element — or the bar's own `::before` — is there.
 */
export const EditBarSitsOnTheGround: Story = {
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    const bar = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="dashboard-edit-bar"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    const surface = bar.closest<HTMLElement>('.fve-root')!;
    // One spelling for a colour, whichever the cascade kept (`oklch(…)`
    // from a token, `rgb(…)` from elsewhere), and none for a clear one.
    const rgb = (color: string) => {
      const found = toRgb(parse(color));
      return !found || (found.alpha ?? 1) === 0
        ? null
        : formatRgb({ ...found, alpha: 1 });
    };
    /** What paints at a point: each element there, down to the surface. */
    /**
     * What paints at a point, from the top down to the surface: the point
     * must be on the board, or the check would read the host's page.
     */
    const paints = (x: number, y: number) => {
      const stack = document.elementsFromPoint(x, y);
      const at = stack.indexOf(surface);
      expect(at, `${x},${y} is on the board`).toBeGreaterThanOrEqual(0);
      return stack
        .slice(0, at + 1)
        .map(element =>
          rgb(
            element === bar
              ? // The bar itself starts below; above it, only its mask.
                getComputedStyle(bar, '::before').backgroundColor
              : getComputedStyle(element).backgroundColor,
          ),
        )
        .filter(color => color !== null);
    };
    const html = document.documentElement;
    const preset = html.getAttribute('data-fve-preset');
    const dark = html.classList.contains('dark');
    try {
      for (const name of BUILT_IN_PRESETS)
        for (const mode of [false, true]) {
          html.setAttribute('data-fve-preset', name);
          html.classList.toggle('dark', mode);
          const ground = rgb(getComputedStyle(surface).backgroundColor);
          bar.scrollIntoView({ block: 'center', inline: 'start' });
          const box = bar.getBoundingClientRect();
          const tag = `${name} ${mode ? 'dark' : 'light'}`;
          // The mask, and every point of the gap above the bar that it
          // covers: nothing but the ground.
          await expect(
            rgb(getComputedStyle(bar, '::before').backgroundColor),
            tag,
          ).toBe(ground);
          for (const x of [box.left + 8, box.left + box.width / 2])
            for (const y of [box.top - 4, box.top - 12])
              for (const color of paints(x, y))
                await expect(color, `${tag} at ${x},${y}`).toBe(ground);
        }
    } finally {
      if (preset === null) html.removeAttribute('data-fve-preset');
      else html.setAttribute('data-fve-preset', preset);
      html.classList.toggle('dark', dark);
    }
  },
};

/** The board the definition ships is read-only: 另存为, and no 编辑 (D4). */
export const SystemDashboardHasNoEdit: Story = {
  ...DisplaySystemDashboard,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '待出库明细' });
    await expect(
      canvas.queryByRole('button', { name: zhCN['label.dashboard.edit'] }),
    ).toBeNull();
    await expect(
      canvas.getByRole('button', { name: zhCN['label.save.save-as'] }),
    ).toBeVisible();
  },
};

/** The title bar's 另存为, the one save command a board read offers. */
const saveAsOf = (canvasElement: HTMLElement) =>
  within(
    canvasElement.querySelector<HTMLElement>('[data-slot="save-actions"]')!,
  ).getByRole('button', { name: zhCN['label.save.save-as'] });

/**
 * One primary a state, last on its line (D32, the user's 2026-09-24
 * walk-through: 「编辑作为主按钮，放到最右侧」). Read, 「编辑」 is filled and
 * ends the title bar — to the right of every other control on it — and the
 * save commands beside the name are 另存为 alone. Built, the edit bar's
 * 「保存」 is the filled one, at that bar's end; 取消 hands the keyboard back
 * to 「编辑」.
 */
export const EditIsThePrimaryAtTheEnd: Story = {
  ...DisplayAllPanels,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '待出库明细' });
    const header = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-header"]',
    )!;
    const edit = canvas.getByRole('button', {
      name: zhCN['label.dashboard.edit'],
    });
    const fill = (element: Element) =>
      getComputedStyle(element).backgroundColor;
    const buttons = [...header.querySelectorAll('button')];
    const right = edit.getBoundingClientRect().right;
    for (const button of buttons)
      await expect(button.getBoundingClientRect().right).toBeLessThanOrEqual(
        right,
      );
    // Filled, and nothing else on the line is filled the same.
    const primary = fill(edit);
    await expect(fill(saveAsOf(canvasElement))).not.toBe(primary);
    await expect(buttons.filter(button => fill(button) === primary)).toEqual([
      edit,
    ]);
    const saves = within(
      canvasElement.querySelector<HTMLElement>('[data-slot="save-actions"]')!,
    ).getAllByRole('button');
    await expect(saves).toEqual([saveAsOf(canvasElement)]);

    await userEvent.click(edit);
    const bar = await canvas.findByRole('region', {
      name: zhCN['label.dashboard.editing'],
    });
    const save = within(bar).getByRole('button', {
      name: zhCN['label.dashboard.save'],
    });
    await expect(within(bar).getAllByRole('button').at(-1)).toBe(save);
    await expect(fill(save)).toBe(primary);
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-edit"]'),
    ).toBeNull();

    await userEvent.click(
      within(bar).getByRole('button', { name: zhCN['label.dialog.cancel'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.ownerDocument.activeElement?.getAttribute('data-slot'),
      ).toBe('dashboard-edit'),
    );
  },
};

/**
 * A board being read moves under nothing (D22 A): no grip, no corner, no
 * arrange menu, and the panel's 「⋯」 holds 「看」 alone — until 编辑.
 */
export const NoGripsUntilBuilding: Story = {
  ...DisplayAllPanels,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '待出库明细' });
    // Its rows on screen, so 「导出数据…」 is one of the things to do.
    await canvas.findAllByRole('table');
    for (const handle of ['panel-grip', 'panel-arrange', 'panel-resize'])
      await expect(
        canvasElement.querySelector(`[data-slot="${handle}"]`),
      ).toBeNull();
    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title: '待出库明细' }),
      }),
    );
    const menu = await screen.findByRole('menu');
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([zhCN['label.panel.refresh'], zhCN['label.panel.export']]);
    await userEvent.keyboard('{Escape}');

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="panel-grip"]').length,
      ).toBe(3),
    );
  },
};

/** 「撤销」 as the edit bar names it for one step (「撤销移除「按仓库汇总」」). */
function undoOf(what: string): string {
  return label('label.history.undo-step', { what });
}

/**
 * A panel removed at once, nothing asked, and brought back (D22 D, batch
 * B2): the keyboard lands on 「撤销」, which is named after the step it takes
 * back; Enter brings the panel back drawing again, and the keys the board
 * answers — ⇧⌘Z／Ctrl+Shift+Z, ⌘Z／Ctrl+Z — take the same step again and
 * back while the focus is on a panel.
 */
export const RemoveThenUndo: Story = {
  ...DisplayAllPanels,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await startBuilding(canvasElement);
    const removal = label('label.history.remove-panel', {
      title: '按仓库汇总',
    });

    await fromPanelMenu(
      canvasElement,
      '按仓库汇总',
      zhCN['label.panel.remove'],
    );
    await expect(screen.queryByRole('alertdialog')).toBeNull();
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual(['待出库明细', '值班手册']),
    );
    const undo = canvas.getByRole('button', { name: undoOf(removal) });
    await waitFor(() => expect(undo).toHaveFocus());
    await expect(
      canvas.getByText(
        label('label.dashboard.removed', { title: '按仓库汇总' }),
      ),
    ).toBeInTheDocument();

    await userEvent.keyboard('{Enter}');
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual([
        '待出库明细',
        '按仓库汇总',
        '值班手册',
      ]),
    );
    await chartsDrawn(canvasElement);
    await expect(
      canvas.getByText(label('label.history.undone', { what: removal })),
    ).toBeInTheDocument();
    // Nothing left to undo: the keyboard is on 「重做」 rather than nowhere.
    await expect(
      canvas.getByRole('button', {
        name: label('label.history.redo-step', { what: removal }),
      }),
    ).toHaveFocus();

    // The board's keys, from a panel.
    canvas
      .getByRole('button', {
        name: label('label.panel.menu', { title: '待出库明细' }),
      })
      .focus();
    await userEvent.keyboard('{Control>}{Shift>}z{/Shift}{/Control}');
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual(['待出库明细', '值班手册']),
    );
    await userEvent.keyboard('{Control>}z{/Control}');
    await waitFor(() => expect(titles(canvasElement)).toHaveLength(3));
  },
};

/**
 * The one-column reading built by its handles (D22 J): a phone's width,
 * where each panel is carried by the handle every ordered list is (「可排序
 * 的列表一律拖拽排序」) and nothing places — no grip, no 上移／下移. ↓ on the
 * handle moves a panel one place down the column and says where it came to,
 * the keyboard staying on the handle; the pointer carries it onto another
 * panel's place; a click opens the menu of the four places (WCAG 2.5.7);
 * and 「撤销」 takes each move back as one step.
 */
export const NarrowReorderByHandle: Story = {
  ...DisplayAllPanels,
  decorators: [
    Story => (
      <div style={{ width: 414 }}>
        <Story />
      </div>
    ),
  ],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="dashboard-grid"]'),
      ).toHaveAttribute('data-narrow'),
    );
    await startBuilding(canvasElement);
    await expect(
      canvasElement.querySelector('[data-slot="panel-grip"]'),
    ).toBeNull();
    const handle = canvas.getByRole('button', {
      name: label('label.panel.reorder', { title: '待出库明细' }),
    });
    const box = handle.getBoundingClientRect();
    await expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(24);

    handle.focus();
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual([
        '按仓库汇总',
        '待出库明细',
        '值班手册',
      ]),
    );
    await expect(
      canvas.getByText(
        label('label.panel.reordered', {
          title: '待出库明细',
          index: '2',
          total: '3',
        }),
      ),
    ).toBeInTheDocument();
    await expect(handle).toHaveFocus();

    // The pointer: carried onto the last panel's place.
    const card = (title: string) =>
      [
        ...canvasElement.querySelectorAll<HTMLElement>(
          '[data-slot="dashboard-panel"]',
        ),
      ].find(panel => panel.textContent?.includes(title))!;
    // Onto the header of the panel below — the pointer's own point is what
    // the drop is read at, and a whole panel on a phone is taller than the
    // screen the mouse can reach.
    card('待出库明细').scrollIntoView({ block: 'start' });
    await dragHandleOnto(
      handle,
      card('值班手册').querySelector<HTMLElement>('h3') ?? card('值班手册'),
    );
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual([
        '按仓库汇总',
        '值班手册',
        '待出库明细',
      ]),
    );

    // One press: the handle's menu, 「移到最前」 from it.
    await userEvent.click(handle);
    const menu = await screen.findByRole('menu', {
      name: label('label.panel.reorder', { title: '待出库明细' }),
    });
    await expect(
      within(menu).getByRole('menuitem', { name: zhCN['label.reorder.last'] }),
    ).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(
      within(menu).getByRole('menuitem', { name: zhCN['label.reorder.first'] }),
    );
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual([
        '待出库明细',
        '按仓库汇总',
        '值班手册',
      ]),
    );
    await waitFor(() => expect(handle).toHaveFocus());

    // Each move one step of the history.
    const moving = label('label.history.move-panel', { title: '待出库明细' });
    await userEvent.click(canvas.getByRole('button', { name: undoOf(moving) }));
    await waitFor(() =>
      expect(titles(canvasElement)).toEqual([
        '按仓库汇总',
        '值班手册',
        '待出库明细',
      ]),
    );
  },
};

/** 「编辑」: the board's building state, entered as its author enters it. */
async function startBuilding(canvasElement: HTMLElement) {
  const canvas = within(canvasElement);
  await userEvent.click(
    await canvas.findByRole('button', { name: zhCN['label.dashboard.edit'] }),
  );
  await waitFor(() =>
    expect(
      canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
    ).not.toBeNull(),
  );
}

/**
 * Screen C: a new analysis made inside the dashboard, from 「＋ 添加 ▾」. The
 * dialog is the analysis view — the tray and the result, running as it is
 * edited — named by what it shows, and 「放进仪表盘」 puts it on the board.
 * The keyboard is held inside while it is open and handed back to 「添加」.
 */
export const CreateOwnedAnalysis: Story = {
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: '按仓库汇总',
    });
    await startBuilding(canvasElement);
    await addFromBar(canvasElement, zhCN['label.dashboard.add.new-analysis']);

    const dialog = await screen.findByRole('dialog', {
      name: label('label.panel.new-analysis.heading-of', {
        definition: '订单',
      }),
    });
    const inside = within(dialog);
    // The same tray as the workbench: dimensions and metrics.
    await expect(
      dialog.querySelector('[data-slot="new-analysis-tray"]'),
    ).not.toBeNull();
    const title = inside.getByRole('textbox', {
      name: zhCN['label.panel.new-analysis.title'],
    });
    await waitFor(() => expect(title).toHaveValue('按仓库 · 记录数'));
    // Held inside while it is open: the keyboard starts on the first
    // question, which data.
    await waitFor(() =>
      expect(dialog.contains(document.activeElement)).toBe(true),
    );

    // Typed once the dialog has settled its own focus, and read back before
    // it is put on the board: the name its author gave it.
    await userEvent.clear(title);
    await userEvent.type(title, '各仓订单数', { skipClick: true });
    await waitFor(() => expect(title).toHaveValue('各仓订单数'));
    await userEvent.click(
      inside.getByRole('button', {
        name: zhCN['label.panel.new-analysis.add'],
      }),
    );

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // On the board, under the name its author gave it.
    await waitFor(() => expect(titles(canvasElement)).toContain('各仓订单数'));
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(canvasElement).getByRole('button', {
          name: zhCN['label.dashboard.add'],
        }),
      ),
    );
  },
};

/**
 * Screen C, the other half: an analysis the board owns saved as a view of
 * its own, from its 「⋯」. The dialog asks for a title and an audience —
 * the board's, a shared one, first — and the panel then shows that view:
 * its menu no longer offers to save it.
 */
export const PromoteOwnedAnalysis: Story = {
  ...DisplayOwnedAnalysis,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const owned = '本板自建：订单数按仓库';
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: owned,
    });
    await startBuilding(canvasElement);
    await fromPanelMenu(canvasElement, owned, zhCN['label.panel.save-as-view']);
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.panel.save-owned.heading'],
    });
    const inside = within(dialog);
    await expect(dialog).toHaveTextContent('它会成为「订单」的一个视图');
    await expect(
      inside.getByRole('radio', { name: zhCN['label.scope.everyone'] }),
    ).toBeChecked();
    const title = inside.getByRole('textbox', {
      name: zhCN['label.save.title'],
    });
    await expect(title).toHaveValue(owned);
    await userEvent.click(
      inside.getByRole('button', {
        name: zhCN['label.panel.save-owned.submit'],
      }),
    );

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expect(
      canvasElement.ownerDocument.querySelector(
        '[data-slot="dashboard-announcement"]',
      ),
    ).toHaveTextContent(`已另存为视图「${owned}」`);
    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: label('label.panel.menu', { title: owned }),
      }),
    );
    const menu = await screen.findByRole('menu');
    await expect(
      within(menu).queryByRole('menuitem', {
        name: zhCN['label.panel.save-as-view'],
      }),
    ).toBeNull();
    await userEvent.keyboard('{Escape}');
  },
};

/**
 * Screen D: a panel's own look, from its 「⋯」. The visualization panel
 * picks a pie for this panel alone; the panel says 「此处改为饼图」 and is
 * drawn as a pie from the rows it had — no query. 「恢复为视图的样子」 on the
 * same menu puts the view's own bars back, and 取消 in the dialog puts back
 * what the panel had when it opened.
 */
export const OverrideToPieAndReset: Story = {
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const name = '按仓库汇总';
    await canvas.findByRole('heading', { level: 3, name });
    await chartsDrawn(canvasElement);
    await startBuilding(canvasElement);
    const asked = aggregateCalls.current;
    const look = async () => {
      await fromPanelMenu(
        canvasElement,
        name,
        zhCN['label.panel.edit-presentation'],
      );
      return screen.findByRole('dialog', {
        name: label('label.panel.presentation.heading', { title: name }),
      });
    };
    let dialog = await look();
    await userEvent.click(within(dialog).getByRole('radio', { name: '饼图' }));
    // Beside the options, the panel as it will look: a pie.
    await expect(
      await within(dialog).findByRole('img', { name: /^饼图/ }),
    ).toBeVisible();
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.panel.presentation.done'],
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    // On the board: marked, and drawn as a pie from the rows it had.
    await expect(canvas.getByText('此处改为饼图')).toBeVisible();
    await expect(
      await canvas.findByRole('img', { name: /^饼图/ }),
    ).toBeVisible();
    // Presentation never asks the source (D20).
    await expect(aggregateCalls.current).toBe(asked);

    // Put back from the menu: the view's own bars, and no mark.
    await fromPanelMenu(
      canvasElement,
      name,
      zhCN['label.panel.presentation.reset'],
    );
    await waitFor(() => expect(canvas.queryByText('此处改为饼图')).toBeNull());
    await expect(
      await canvas.findByRole('img', { name: /^柱状图/ }),
    ).toBeVisible();

    // 取消 in the dialog puts back what the panel had when it opened.
    dialog = await look();
    await userEvent.click(within(dialog).getByRole('radio', { name: '饼图' }));
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.dialog.cancel'],
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await expect(canvas.queryByText('此处改为饼图')).toBeNull();
    await expect(aggregateCalls.current).toBe(asked);
  },
};

/**
 * Screen E: tabs. Only the tab on screen runs — switching to the second
 * asks for its one panel and nothing else, switching back asks nothing —
 * and a board opened again, from the list, lands on the tab its reader
 * last read.
 */
export const TabsRunOnlyTheTabShown: Story = {
  ...DisplayTabs,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const tabs = await canvas.findByRole('tablist', {
      name: zhCN['label.tabs.name'],
    });
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await expect(
      canvas.queryByRole('heading', { level: 3, name: '按状态看金额' }),
    ).toBeNull();
    await chartsDrawn(canvasElement);
    const asked = aggregateCalls.current;

    await userEvent.click(within(tabs).getByRole('tab', { name: '状态' }));
    await canvas.findByRole('heading', { level: 3, name: '按状态看金额' });
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(asked));
    const second = aggregateCalls.current;

    await userEvent.click(within(tabs).getByRole('tab', { name: '出库' }));
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await expect(aggregateCalls.current).toBe(second);

    // The reader's last tab is theirs: back on 状态, another board, and
    // this one again — on 状态.
    await userEvent.click(within(tabs).getByRole('tab', { name: '状态' }));
    await canvas.findByRole('heading', { level: 3, name: '按状态看金额' });
    await userEvent.click(await canvas.findByText('异常概览'));
    await waitFor(() => expect(canvas.queryByRole('tablist')).toBeNull());
    await userEvent.click(await canvas.findByText('出库概览'));
    await waitFor(() =>
      expect(canvas.getByRole('tab', { name: '状态' })).toHaveAttribute(
        'aria-selected',
        'true',
      ),
    );
    await expect(
      canvas.queryByRole('heading', { level: 3, name: '待出库明细' }),
    ).toBeNull();
  },
};

/**
 * Screen E while building: a tab added under the edit bar and named in
 * place, a panel moved to it from its 「⋯」, and the tab reordered from the
 * keyboard.
 */
export const TabsBuilt: Story = {
  ...DisplayTabs,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await startBuilding(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.tabs.add'] }),
    );
    const name = await canvas.findByRole('textbox', {
      name: '标签页「标签页 3」的名字',
    });
    // Focused with its name selected: typing replaces it.
    await waitFor(() => expect(name).toHaveFocus());
    await userEvent.keyboard('异常{Enter}');
    // Being built, the bar is the tabs to arrange; the one on screen is
    // the one pressed in.
    const added = await canvas.findByRole('button', { name: '异常' });
    await expect(added).toHaveAttribute('aria-current', 'true');
    await expect(canvas.getByText('这个标签页还没有面板')).toBeVisible();

    // Back to the first tab, and a panel moved to the new one.
    await userEvent.click(canvas.getByRole('button', { name: '出库' }));
    await fromPanelMenu(
      canvasElement,
      '按仓库汇总',
      zhCN['label.panel.move-to-tab'],
    );
    await screen.findByRole('menuitem', { name: '异常' });
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(document.activeElement?.textContent).toBe('状态'),
    );
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() =>
      expect(document.activeElement?.textContent).toBe('异常'),
    );
    await userEvent.keyboard('{Enter}');
    await waitFor(() =>
      expect(
        canvas.queryByRole('heading', { level: 3, name: '按仓库汇总' }),
      ).toBeNull(),
    );
    await userEvent.click(canvas.getByRole('button', { name: '异常' }));
    await expect(
      await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' }),
    ).toBeVisible();

    // Reordered from the keyboard: the handle answers the arrows.
    const handle = canvas.getByRole('button', { name: '调整「异常」的顺序' });
    handle.focus();
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() =>
      expect(
        within(canvas.getByRole('list', { name: zhCN['label.tabs.name'] }))
          .getAllByRole('listitem')
          .map(
            item =>
              item.querySelector('[data-slot="dashboard-tab"]')?.textContent,
          ),
      ).toEqual(['出库', '异常', '状态']),
    );

    // Renamed from its menu, by keyboard: the menu closing leaves the
    // keyboard in the name's field, where the edit goes on until Enter
    // (Q-10).
    canvas
      .getByRole('button', {
        name: label('label.tabs.actions', { title: '异常' }),
      })
      .focus();
    await userEvent.keyboard('{Enter}');
    const item = await screen.findByRole('menuitem', {
      name: zhCN['label.tabs.rename'],
    });
    await waitFor(() => expect(item).toHaveFocus());
    await userEvent.keyboard('{Enter}');
    const renamed = await canvas.findByRole('textbox', {
      name: '标签页「异常」的名字',
    });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    await expect(renamed).toHaveFocus();
    await userEvent.keyboard('告警{Enter}');
    await waitFor(() =>
      expect(canvas.getByRole('button', { name: '告警' })).toHaveFocus(),
    );
  },
};

/**
 * 「导出数据…」 from a record panel's 「⋯」, by keyboard alone (D22 运维): the
 * workbench's own export window over the panel's rows — named after the
 * panel, every row, no 「选中」 — and, as it closes, the keyboard back on
 * the 「⋯」 it was asked from. An analysis panel's 「⋯」 holds the same item
 * over its groups (D25 Q28): the same window, no scope, what the file holds
 * said in groups. Nothing is exported: the file itself is asserted in jsdom
 * (test/dashboardPanelMenu.test.tsx) and in the analysis workbench's
 * `ExportReadsTheTable`.
 */
export const PanelExportWindow: Story = {
  ...DisplayPersonalViewOnSharedBoard,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findAllByRole('table');
    const trigger = canvas.getByRole('button', {
      name: label('label.panel.menu', { title: '待出库明细' }),
    });
    trigger.focus();
    await userEvent.keyboard('{Enter}');
    const menu = await screen.findByRole('menu');
    const item = within(menu).getByRole('menuitem', {
      name: zhCN['label.panel.export'],
    });
    // Down the menu to it, the arrows being all a keyboard has here.
    for (let step = 0; step < 4 && document.activeElement !== item; step += 1)
      await userEvent.keyboard('{ArrowDown}');
    await expect(item).toHaveFocus();
    await userEvent.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.export.title'],
    });
    await expect(within(dialog).queryByRole('radio')).toBeNull();
    await expect(dialog.textContent).toMatch(
      /文件：待出库明细-\d{4}-\d{2}-\d{2}\.csv/,
    );
    await waitFor(() =>
      expect(
        within(dialog).getByRole('button', {
          name: zhCN['label.export.confirm'],
        }),
      ).toHaveFocus(),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());

    await userEvent.click(
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title: '按仓库汇总' }),
      }),
    );
    await userEvent.click(
      within(await screen.findByRole('menu')).getByRole('menuitem', {
        name: zhCN['label.panel.export'],
      }),
    );
    const groups = await screen.findByRole('dialog', {
      name: zhCN['label.export.title'],
    });
    await expect(within(groups).queryByRole('radio')).toBeNull();
    await expect(groups.textContent).toMatch(/\d+ 组/);
    await expect(groups.textContent).toMatch(
      /文件：按仓库汇总-\d{4}-\d{2}-\d{2}\.csv/,
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  },
};

/**
 * 「复制为共享视图并替换…」 (D22 B): a shared board's panel on the author's
 * personal view wears the warning that not every reader can open it; while
 * the board is built its 「⋯」 offers the copy, the dialog says what happens
 * — no audience to pick, the view's own name to start from — and pressing
 * it points the panel at a shared copy: the warning goes, the name stays,
 * and the keyboard is back on the 「⋯」.
 */
export const CopyPersonalViewAsShared: Story = {
  ...DisplayPersonalViewOnSharedBoard,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const heading = await canvas.findByRole('heading', {
      level: 3,
      name: '我盯的大额单',
    });
    const panel = heading.closest<HTMLElement>(
      '[data-slot="dashboard-panel"]',
    )!;
    await waitFor(() =>
      expect(panel.querySelector('[data-slot="panel-warning"]')).not.toBeNull(),
    );

    await startBuilding(canvasElement);
    const trigger = canvas.getByRole('button', {
      name: label('label.panel.menu', { title: '我盯的大额单' }),
    });
    await fromPanelMenu(
      canvasElement,
      '我盯的大额单',
      zhCN['label.panel.copy-shared'],
    );
    const dialog = await screen.findByRole('dialog', {
      name: zhCN['label.panel.copy-shared.heading'],
    });
    await expect(within(dialog).queryByRole('radio')).toBeNull();
    await expect(
      within(dialog).getByRole('textbox', { name: zhCN['label.save.title'] }),
    ).toHaveValue('我盯的大额单');
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.panel.copy-shared.submit'],
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() =>
      expect(panel.querySelector('[data-slot="panel-warning"]')).toBeNull(),
    );
    await expect(
      canvas.getByRole('heading', { level: 3, name: '我盯的大额单' }),
    ).toBeVisible();
    await waitFor(() => expect(trigger).toHaveFocus());
  },
};

/**
 * Every menu item that opens a dialog hands it the keyboard (U-01): the
 * menu closes *after* the dialog has opened, and it used to take the
 * keyboard back to its trigger then — behind the modal, on the board it
 * covers, so what was typed went nowhere. Each item is chosen by keyboard,
 * the menu is let go all the way, and the keyboard is still in the dialog
 * and typing reaches its first box; Escape brings it back to the trigger.
 */
export const MenuItemsHandTheKeyboardToTheirDialog: Story = {
  ...DisplayBuilding,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await startBuilding(canvasElement);
    const add = canvas.getByRole('button', {
      name: zhCN['label.dashboard.add'],
    });
    const menuOf = (title: string) =>
      canvas.getByRole('button', {
        name: label('label.panel.menu', { title }),
      });
    const items: [HTMLElement, keyof typeof zhCN][] = [
      [add, 'label.dashboard.add.saved-view'],
      [add, 'label.dashboard.add.markdown'],
      [add, 'label.dashboard.add.image'],
      [add, 'label.dashboard.add.links'],
      [menuOf('按仓库汇总'), 'label.panel.replace'],
      [menuOf('值班手册'), 'label.panel.edit-content'],
    ];
    for (const [trigger, key] of items) {
      trigger.focus();
      await userEvent.keyboard('{Enter}');
      const menu = await screen.findByRole('menu');
      const item = within(menu).getByRole('menuitem', { name: zhCN[key] });
      item.focus();
      await userEvent.keyboard('{Enter}');
      const dialog = await screen.findByRole('dialog');
      // The menu gone for good, out of the document and not only out of the
      // accessibility tree: the end of its exit is when it used to take the
      // keyboard back.
      await waitFor(() =>
        expect(
          document.querySelector('[data-slot="dropdown-menu-content"]'),
        ).toBeNull(),
      );
      await waitFor(() =>
        expect(
          dialog.contains(document.activeElement) &&
            document.activeElement?.matches('input, textarea'),
          key,
        ).toBe(true),
      );
      const box = document.activeElement as HTMLInputElement;
      await userEvent.keyboard('abc');
      await expect(box.value, key).toContain('abc');
      await userEvent.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      await waitFor(() => expect(trigger, key).toHaveFocus());
    }
  },
};
