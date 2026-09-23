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
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  AllPanels as DisplayAllPanels,
  Building as DisplayBuilding,
  EmptySharedBoard as DisplayEmptySharedBoard,
  SystemDashboard as DisplaySystemDashboard,
} from './Dashboard.stories.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/搭建',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, as in `Dashboard.test.stories.tsx`: a file's own
  // description would otherwise replace the display meta's parameters.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * A desk: the test browser is a phone's width, and below `md` the board is
 * one derived column in which building is renaming and removing alone.
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
 * in place; one panel renamed, one removed; 完成 asks, as Save does over a
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
    // Changed, and the title bar neither says so nor undoes it: 完成 and
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

    await fromPanelMenu(
      canvasElement,
      '我盯的大额单',
      zhCN['label.panel.remove'],
    );
    const question = await screen.findByRole('alertdialog');
    await userEvent.click(
      within(question).getByRole('button', {
        name: zhCN['label.panel.remove'],
      }),
    );
    await waitFor(() =>
      expect([...titles(canvasElement)].sort()).toEqual(
        ['仓库分布', '出库', '待出库订单'].sort(),
      ),
    );

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.done'] }),
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
    // One way to do one thing: the edit bar holds 完成 and 取消, so the
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
    ).toEqual([zhCN['label.panel.refresh']]);
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
