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
import { expect, fn, screen, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  Clicks as DisplayClicks,
  CrossFilter as DisplayCrossFilter,
  ToAnotherBoard as DisplayToAnotherBoard,
  ToAnotherBoardStale as DisplayToAnotherBoardStale,
} from './Dashboard.stories.js';
import { chartsDrawn, drawnMarks, pressMark } from './chartDom.js';
import { readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/仪表盘视图/Dashboard/点击',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, as in `Dashboard.test.stories.tsx`: a file's own
  // description would otherwise replace the display meta's parameters.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** A desk: below `md` the board is one column. */
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

/** A panel's frame, by its title. */
async function panelNamed(
  canvasElement: HTMLElement,
  title: string,
): Promise<HTMLElement> {
  const heading = await within(canvasElement).findByRole('heading', {
    level: 3,
    name: title,
  });
  const frame = heading.closest<HTMLElement>('[data-slot="dashboard-panel"]');
  if (!frame) throw new Error(`no panel ${title}`);
  return frame;
}

/** The bars of 「按仓库汇总」, once drawn. */
async function bars(canvasElement: HTMLElement): Promise<SVGPathElement[]> {
  const panel = await panelNamed(canvasElement, '按仓库汇总');
  await chartsDrawn(panel);
  return waitFor(
    () => {
      const marks = drawnMarks(panel);
      expect(marks.length).toBeGreaterThan(1);
      return marks;
    },
    { timeout: 4_000 },
  );
}

/**
 * Screen H: a bar of 「按仓库汇总」 opens the analysis view's follow-up menu,
 * headed by the group and the board's filters over it, each item marked as
 * opening in the workbench; 「查看这些记录」 goes through the host's route,
 * which opens the records in the workbench under 仓库 and the board's value,
 * its editor folded.
 */
export const BarOpensFollowUps: Story = {
  ...DisplayClicks,
  decorators: [DESK],
  args: { ...DisplayClicks.args, onNavigate: fn() },
  play: async ({ canvasElement, args }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    const menu = await screen.findByRole('menu');
    await expect(
      menu.querySelector('[data-slot="drill-group"]')?.textContent,
    ).toMatch(/^仓库 是 /);
    await expect(
      menu.querySelectorAll('[data-slot="drill-away"]').length,
    ).toBeGreaterThan(0);
    await userEvent.click(
      within(menu).getByRole('menuitem', {
        name: new RegExp(zhCN['label.drill.records']),
      }),
    );
    await waitFor(() => expect(args.onNavigate).toHaveBeenCalledTimes(1));
    await expect(args.onNavigate).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'unsaved', definitionId: 'orders' }),
    );
    // The host opened it: the records, named for the group, and a way back.
    await within(canvasElement).findByRole('heading', {
      level: 2,
      name: /^订单 · 仓库 是 /,
    });
    // Opened with its conditions in hand, as a view opened from another's
    // group is: the editor stays folded, and the group is said once, on
    // 「正在显示」, not again in an unfolded band.
    const toggle = await waitFor(() => {
      const found = canvasElement.querySelector('[data-slot="editor-toggle"]');
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(toggle.querySelector('[aria-expanded="true"]')).toBeNull();
    await expect(
      canvasElement.querySelector('[data-slot="editor-band"]'),
    ).toBeNull();
    await userEvent.click(
      within(canvasElement).getByRole('button', { name: /回到出库概览/ }),
    );
    await panelNamed(canvasElement, '按仓库汇总');
  },
};

/**
 * Screen I: with 「按仓库汇总」 set to update 「仓库」, a bar pressed sets the
 * filter bar from it (「来自「按仓库汇总」」), the list runs under it, and
 * the chart keeps every bar with the pressed one marked; the same bar again
 * clears it.
 */
export const CrossFilterFromABar: Story = {
  ...DisplayCrossFilter,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const chart = await panelNamed(canvasElement, '按仓库汇总');
    await expect(
      within(chart).getByText(label('label.click.badge', { filter: '仓库' })),
    ).toBeVisible();
    const before = await bars(canvasElement);
    const count = before.length;
    pressMark(before[0]!);

    const bar = await within(canvasElement).findByRole('region', {
      name: zhCN['label.filters.bar'],
    });
    const from = await within(bar).findByText(
      label('label.click.from', { panel: '按仓库汇总' }),
    );
    await expect(from).toBeVisible();
    const chip = within(bar).getByRole('group', { name: '仓库' });
    const picked = within(chip).getByRole('combobox', { name: '仓库' });
    // The value it shows, not the trigger's chevron.
    const shown = () =>
      picked.querySelector('[data-slot="select-value"]')?.textContent?.trim();
    await waitFor(() => expect(shown()).toBeTruthy());
    const region = shown() ?? '';

    // The list runs under it: every row it shows is of that warehouse.
    const list = await panelNamed(canvasElement, '待出库明细');
    await waitFor(() => {
      const table = list.querySelector<HTMLElement>('table');
      expect(table).not.toBeNull();
      const cells = readColumn(table!, '仓库');
      expect(cells.length).toBeGreaterThan(0);
      expect(cells.every(cell => cell === region)).toBe(true);
    });
    // The panel pressed is unchanged: every bar still drawn, the others
    // faint beside the one marked.
    const frame = chart.querySelector<HTMLElement>('[data-slot="chart"]');
    await waitFor(() => expect(frame?.dataset.highlighted).toBe('1'));
    await expect(Number(frame?.dataset.marks)).toBe(count);
    // Only the one pressed is drawn at full strength.
    await waitFor(() => expect(drawnMarks(chart)).toHaveLength(1));

    // Pressed again, it clears, and every bar is drawn as it was.
    pressMark(drawnMarks(chart)[0]!);
    await waitFor(() =>
      expect(
        within(bar).queryByText(
          label('label.click.from', { panel: '按仓库汇总' }),
        ),
      ).toBeNull(),
    );
    await waitFor(() => expect(frame?.dataset.highlighted).toBe('0'));
    await waitFor(() => expect(drawnMarks(chart).length).toBe(count));
  },
};

/**
 * 「点击时…」: while the board is built, a panel's 「⋯」 sets what a press
 * does. Choosing 「更新仪表盘筛选」 marks the panel 「点击筛选「仓库」」.
 */
export const SetsCrossFilterWhenClicked: Story = {
  ...DisplayClicks,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '按仓库汇总');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await userEvent.click(
      await canvas.findByRole('button', {
        name: label('label.panel.menu', { title: '按仓库汇总' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.click.menu-item'],
      }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: label('label.click.title', { panel: '按仓库汇总' }),
    });
    await userEvent.click(
      within(dialog).getByRole('radio', { name: zhCN['label.click.filter'] }),
    );
    await userEvent.click(
      within(dialog).getByRole('button', { name: zhCN['label.click.save'] }),
    );
    const chart = await panelNamed(canvasElement, '按仓库汇总');
    await expect(
      await within(chart).findByText(
        label('label.click.badge', { filter: '仓库' }),
      ),
    ).toBeVisible();
    // Back where it was opened from.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(chart).getByRole('button', {
          name: label('label.panel.menu', { title: '按仓库汇总' }),
        }),
      ),
    );
  },
};

/** The item a select shows, not its chevron. */
const chosen = (select: HTMLElement) =>
  select.querySelector('[data-slot="select-value"]')?.textContent?.trim();

/**
 * The board a press opened, read off the host's page: its 仓库 on the
 * filter bar, and the list under it all of that warehouse.
 */
async function openedWithRegion(canvasElement: HTMLElement): Promise<void> {
  const canvas = within(canvasElement);
  await canvas.findByRole('button', { name: /回到出库概览/ });
  const bar = await canvas.findByRole('region', {
    name: zhCN['label.filters.bar'],
  });
  const chip = within(bar).getByRole('group', { name: '仓库' });
  const picked = within(chip).getByRole('combobox', { name: '仓库' });
  await waitFor(() => expect(chosen(picked)).toBeTruthy());
  const region = chosen(picked) ?? '';
  // Nobody pressed anything on this board: the value is the reader's.
  await expect(
    within(bar).queryByText(label('label.click.from', { panel: '按仓库汇总' })),
  ).toBeNull();
  const list = await panelNamed(canvasElement, '待出库明细');
  await waitFor(() => {
    const table = list.querySelector<HTMLElement>('table');
    expect(table).not.toBeNull();
    const cells = readColumn(table!, '仓库');
    expect(cells.length).toBeGreaterThan(0);
    expect(cells.every(cell => cell === region)).toBe(true);
  });
}

/**
 * D23 Q17: 「按仓库汇总」 opens 「区域明细」 with its 仓库 mapped from the
 * bar. The host is handed the board and the value, and opens it with the
 * value as its reader's — on the filter bar, the list under it.
 */
export const PressOpensAnotherBoard: Story = {
  ...DisplayToAnotherBoard,
  decorators: [DESK],
  args: { ...DisplayToAnotherBoard.args, onNavigate: fn() },
  play: async ({ canvasElement, args }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    await waitFor(() => expect(args.onNavigate).toHaveBeenCalledTimes(1));
    await expect(args.onNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'dashboard',
        instanceId: 'overview-regional',
        filters: { values: { region: [expect.any(String)] } },
      }),
    );
    await expect(screen.queryByRole('menu')).toBeNull();
    await openedWithRegion(canvasElement);
  },
};

/**
 * The same click mapped to a filter 「区域明细」 no longer has: the press
 * opens the follow-up menu instead, nothing is handed to the host, and the
 * panel warns from then on.
 */
export const StaleMappingFallsBack: Story = {
  ...DisplayToAnotherBoardStale,
  decorators: [DESK],
  args: { ...DisplayToAnotherBoardStale.args, onNavigate: fn() },
  play: async ({ canvasElement, args }) => {
    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    const menu = await screen.findByRole('menu');
    await expect(
      menu.querySelector('[data-slot="drill-group"]')?.textContent,
    ).toMatch(/^仓库 是 /);
    await expect(args.onNavigate).not.toHaveBeenCalled();
    await userEvent.keyboard('{Escape}');
    // The panel warns from then on, in the words of the finding.
    const chart = await panelNamed(canvasElement, '按仓库汇总');
    const warning = await waitFor(() => {
      const found = chart.querySelector<HTMLElement>(
        '[data-slot="panel-warning"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(warning.getAttribute('aria-label') ?? '').toContain(
      label('dashboard.click.board-filter-unknown', { filter: 'zone' }),
    );
  },
};

/**
 * 「点击时…」 → 「另一块仪表盘」, by keyboard where it counts: the board
 * picked, its filters listed one per row — 仓库 mapped to 「这一组的仓库」
 * with the arrow keys, 下单时间 offering nothing this panel can give — then
 * the board saved, and a bar pressed opens 「区域明细」 under that value.
 */
export const SetsAnotherBoardWhenClicked: Story = {
  ...DisplayClicks,
  decorators: [DESK],
  args: { ...DisplayClicks.args, onNavigate: fn() },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '按仓库汇总');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await userEvent.click(
      await canvas.findByRole('button', {
        name: label('label.panel.menu', { title: '按仓库汇总' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.click.menu-item'],
      }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: label('label.click.title', { panel: '按仓库汇总' }),
    });
    await userEvent.click(
      within(dialog).getByRole('radio', { name: zhCN['label.click.go'] }),
    );
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.click.go-board'],
      }),
    );
    await userEvent.click(
      within(dialog).getByRole('button', {
        name: zhCN['label.click.board-pick'],
      }),
    );
    const picker = await screen.findByRole('dialog', {
      name: label('label.click.board-heading', { panel: '按仓库汇总' }),
    });
    await userEvent.click(await within(picker).findByText('区域明细'));

    const rows = await within(dialog).findByRole('group', {
      name: zhCN['label.click.board-values'],
    });
    const region = within(rows).getByRole('combobox', { name: '仓库' });
    await expect(chosen(region)).toBe(zhCN['label.click.board-skip']);
    const placed = within(rows).getByRole('combobox', { name: '下单时间' });
    await expect(placed).toHaveAttribute('data-disabled');
    await expect(
      within(rows).getByText(zhCN['label.click.board-no-dimension']),
    ).toBeVisible();
    // The keyboard reaches the row and picks from it.
    region.focus();
    await userEvent.keyboard('{Enter}');
    const option = await screen.findByRole('option', {
      name: label('label.click.board-value', { dimension: '仓库' }),
    });
    await userEvent.click(option);
    await waitFor(() =>
      expect(chosen(region)).toBe(
        label('label.click.board-value', { dimension: '仓库' }),
      ),
    );
    await userEvent.click(
      within(dialog).getByRole('button', { name: zhCN['label.click.save'] }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.done'] }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="dashboard-edit-bar"]'),
      ).toBeNull(),
    );

    const [bar] = await bars(canvasElement);
    pressMark(bar!);
    await waitFor(() =>
      expect(args.onNavigate).toHaveBeenCalledWith(
        expect.objectContaining({
          kind: 'dashboard',
          instanceId: 'overview-regional',
        }),
      ),
    );
    await openedWithRegion(canvasElement);
  },
};

/**
 * A stored mapping read back in 「点击时…」, left open so axe judges the
 * rows: 仪表盘 chosen, 「区域明细」 named, 仓库 on 「这一组的仓库」, and
 * the keyboard going from 「换一块…」 straight to that row.
 */
export const MappingRowsReadBack: Story = {
  ...DisplayToAnotherBoard,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await panelNamed(canvasElement, '按仓库汇总');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await userEvent.click(
      await canvas.findByRole('button', {
        name: label('label.panel.menu', { title: '按仓库汇总' }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.click.menu-item'],
      }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: label('label.click.title', { panel: '按仓库汇总' }),
    });
    await expect(
      within(dialog).getByRole('button', {
        name: zhCN['label.click.go-board'],
      }),
    ).toHaveAttribute('aria-pressed', 'true');
    await within(dialog).findByText('区域明细');
    const rows = await within(dialog).findByRole('group', {
      name: zhCN['label.click.board-values'],
    });
    const region = within(rows).getByRole('combobox', { name: '仓库' });
    await expect(chosen(region)).toBe(
      label('label.click.board-value', { dimension: '仓库' }),
    );
    within(dialog)
      .getByRole('button', { name: zhCN['label.click.board-change'] })
      .focus();
    await userEvent.tab();
    await expect(region).toHaveFocus();
  },
};
