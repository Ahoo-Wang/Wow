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
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  AllPanels as DisplayAllPanels,
  Filters as DisplayFilters,
} from './Dashboard.stories.js';
import { aggregateCalls } from './fixtures.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/仪表盘/筛选',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, as in `Dashboard.test.stories.tsx`: a file's own
  // description would otherwise replace the display meta's parameters.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** A desk: below `md` the board is one column that nothing is wired in. */
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

/** The filter bar, once the board has drawn it. */
async function filterBar(canvasElement: HTMLElement): Promise<HTMLElement> {
  return within(canvasElement).findByRole('region', {
    name: zhCN['label.filters.bar'],
  });
}

/** The 「不受…影响」 badge on the panel titled so, if it wears one. */
function badgeOn(canvasElement: HTMLElement, title: string): string | null {
  const heading = [
    ...canvasElement.querySelectorAll<HTMLElement>('[data-slot="panel-title"]'),
  ].find(entry => entry.textContent === title);
  return (
    heading
      ?.closest('[data-slot="dashboard-panel"]')
      ?.querySelector('[data-slot="panel-not-reached"]')?.textContent ?? null
  );
}

/** 仓库 set to 华南 from its chip, as a reader picks it. */
async function pickSouth(bar: HTMLElement) {
  await userEvent.click(within(bar).getByRole('combobox', { name: '仓库' }));
  await userEvent.click(await screen.findByRole('option', { name: '华南' }));
}

/**
 * Screen G: 「添加筛选」 → 日期 opens its settings; 「接线」 puts a strip on
 * every panel; picking 创建时间 on one wires the other panel with that field
 * on its own, and the toast says so with 「只接刚选的面板」 — which unwires it again.
 */
export const AddTimeFilterAutoConnects: Story = {
  ...DisplayAllPanels,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('heading', { level: 3, name: '按仓库汇总' });
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    await userEvent.click(
      await canvas.findByRole('button', {
        name: zhCN['label.filters.add'],
      }),
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: zhCN['label.filters.type.date'],
      }),
    );
    const settings = await screen.findByRole('dialog', {
      name: label('label.filters.settings-of', {
        filter: zhCN['label.filters.type.date'],
      }),
    });
    await userEvent.click(
      within(settings).getByRole('button', {
        name: zhCN['label.filters.wire'],
      }),
    );
    await canvas.findByRole('region', {
      name: label('label.filters.wiring', {
        filter: zhCN['label.filters.type.date'],
      }),
    });
    await waitFor(() =>
      expect(
        canvasElement.querySelectorAll('[data-slot="panel-wiring"]'),
      ).toHaveLength(2),
    );

    await userEvent.click(
      canvas.getByRole('combobox', {
        name: label('label.filters.wire-field-of', {
          panel: '待出库明细',
          filter: zhCN['label.filters.type.date'],
        }),
      }),
    );
    await userEvent.click(
      await screen.findByRole('option', { name: '创建时间' }),
    );
    await expect(
      await canvas.findByText(
        label('label.filters.auto-wired-one', { field: '创建时间' }),
      ),
    ).toBeVisible();
    // The other panel follows on its own; the one chosen by hand is 「手动」.
    const summary = () =>
      canvas.getByRole('combobox', {
        name: label('label.filters.wire-field-of', {
          panel: '按仓库汇总',
          filter: zhCN['label.filters.type.date'],
        }),
      });
    await waitFor(() => expect(summary()).toHaveTextContent('创建时间'));
    await expect(
      canvasElement.querySelectorAll('[data-slot="panel-wiring-manual"]'),
    ).toHaveLength(1);

    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filters.only-picked'] }),
    );
    await waitFor(() =>
      expect(summary()).toHaveTextContent(zhCN['label.filters.unwired']),
    );
  },
};

/**
 * Screen F: 创建时间 is required — starred, and never empty: at its default
 * it offers no way back, and 「清空」 clears 仓库 and leaves it holding.
 */
export const RequiredNeverEmpty: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const bar = await filterBar(canvasElement);
    const created = within(bar).getByRole('group', {
      name: `创建时间 ${zhCN['label.filters.required']}`,
    });
    await expect(created).toHaveTextContent('创建时间*');
    await expect(
      within(created).queryByRole('button', {
        name: label('label.filters.back-to-default', { filter: '创建时间' }),
      }),
    ).toBeNull();

    await pickSouth(bar);
    const clear = within(bar).getByRole('button', {
      name: zhCN['label.filters.clear'],
    });
    await waitFor(() => expect(clear).toBeEnabled());
    await userEvent.click(clear);
    await waitFor(() => expect(clear).toBeDisabled());
    await expect(
      within(bar).getByRole('combobox', { name: '仓库' }),
    ).toHaveTextContent(zhCN['label.filter.not-set']);
    // Still a window: the required filter went back to its default.
    await expect(
      created.querySelector('[data-slot="filter-value"]')?.textContent,
    ).toContain('2026');
  },
};

/**
 * Screen F: 按日｜按月 regroups every panel whose time dimension can take
 * it — the trend asks again, and the bar says which is in force.
 */
export const TimeGroupingSwitches: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const bar = await filterBar(canvasElement);
    const grouping = within(bar).getByRole('group', {
      name: zhCN['label.filters.grouping'],
    });
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: '每日订单',
    });
    await waitFor(() =>
      expect(
        within(grouping).getByRole('button', { name: '按日' }),
      ).toHaveAttribute('aria-pressed', 'true'),
    );
    const asked = aggregateCalls.current;
    await userEvent.click(
      within(grouping).getByRole('button', { name: '按月' }),
    );
    await waitFor(() => expect(aggregateCalls.current).toBeGreaterThan(asked));
    await expect(
      within(grouping).getByRole('button', { name: '按月' }),
    ).toHaveAttribute('aria-pressed', 'true');
  },
};

/**
 * Screen F: a panel a filter does not reach says so once the filter holds a
 * value — the trend is not wired to 仓库.
 */
export const UnwiredPanelSaysSo: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const bar = await filterBar(canvasElement);
    await within(canvasElement).findByRole('heading', {
      level: 3,
      name: '每日订单',
    });
    await expect(badgeOn(canvasElement, '每日订单')).toBeNull();
    await pickSouth(bar);
    await waitFor(() =>
      expect(badgeOn(canvasElement, '每日订单')).toBe(
        label('label.filters.not-reached', {
          filters: label('label.filters.name-quoted', { name: '仓库' }),
        }),
      ),
    );
    await expect(badgeOn(canvasElement, '按仓库汇总')).toBeNull();
  },
};

/**
 * Screen G, a category: 状态 is a text filter wired to the orders' status,
 * an enum — so it picks from the enum's labels and holds its codes, rather
 * than asking the reader to type 「PENDING」.
 */
export const CategoryPicksFromLabels: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const bar = await filterBar(canvasElement);
    const chip = within(bar).getByRole('group', { name: '状态' });
    const select = await within(chip).findByRole('combobox', { name: '状态' });
    await expect(within(chip).queryByRole('textbox')).toBeNull();
    await userEvent.click(select);
    await expect(
      await screen.findByRole('option', { name: '已发运' }),
    ).toBeVisible();
    await userEvent.click(
      await screen.findByRole('option', { name: '待出库' }),
    );
    await waitFor(() => expect(select).toHaveTextContent('待出库'));
    // Still the pending orders on the list: the code went out, not the label.
    const [table] = await within(canvasElement).findAllByRole('table');
    await waitFor(() =>
      expect(within(table).getAllByRole('row').length).toBeGreaterThan(1),
    );
  },
};

/**
 * Screen G, values from the data: 订单号 is wired to a plain text field
 * the data can be counted by, so it offers the order numbers there are,
 * each with how many records hold it.
 */
export const TextOffersCountedValues: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const bar = await filterBar(canvasElement);
    const chip = within(bar).getByRole('group', { name: '订单号' });
    await userEvent.click(
      within(chip).getByRole('combobox', { name: '订单号' }),
    );
    await waitFor(() =>
      expect(
        document.querySelectorAll('[data-slot="candidate-count"]').length,
      ).toBeGreaterThan(0),
    );
    // Each with its count, as a text condition's values are listed.
    await expect(
      await screen.findByRole('option', { name: 'SO-1001（1 条记录）' }),
    ).toBeInTheDocument();
    // Closed again, so the page is read as a whole once more.
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
  },
};

/**
 * Screen G, the order: while the board is built each chip wears a handle,
 * and ← on 订单号's moves it a place along the bar — twice, past 状态 and
 * 仓库, then → once back — the keyboard staying on the handle, each landing
 * said, and the time grouping still after the filters.
 */
export const FiltersReordered: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const bar = await filterBar(canvasElement);
    const order = () =>
      [...bar.querySelectorAll<HTMLElement>('[data-filter]')].map(
        chip => chip.dataset.filter,
      );
    await expect(order()).toEqual(['created', 'region', 'phase', 'order']);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.dashboard.edit'] }),
    );
    const handle = await within(bar).findByRole('button', {
      name: label('label.filters.reorder', { filter: '订单号' }),
    });
    handle.focus();
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() =>
      expect(order()).toEqual(['created', 'region', 'order', 'phase']),
    );
    // Put back in the DOM at its place, the chip keeps the keyboard.
    await waitFor(() => expect(handle).toHaveFocus());
    await userEvent.keyboard('{ArrowLeft}');
    await waitFor(() =>
      expect(order()).toEqual(['created', 'order', 'region', 'phase']),
    );
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-announcement"]'),
    ).toHaveTextContent('「订单号」现在是第 2 个筛选，共 4 个');
    // And back a place: going right is the chip itself put back in the DOM.
    await waitFor(() => expect(handle).toHaveFocus());
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(order()).toEqual(['created', 'region', 'order', 'phase']),
    );
    await waitFor(() => expect(handle).toHaveFocus());
    await expect(
      canvasElement.querySelector('[data-slot="dashboard-announcement"]'),
    ).toHaveTextContent('「订单号」现在是第 3 个筛选，共 4 个');
    // The time grouping is not in this order: it stays after the filters.
    const grouping = within(bar).getByRole('group', {
      name: zhCN['label.filters.grouping'],
    });
    const last = bar.querySelector('[data-filter="phase"]');
    await expect(
      (last?.compareDocumentPosition(grouping) ?? 0) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  },
};

/**
 * What the filters hold is the host's to keep in its address
 * (`onFiltersChange`): told as the board opens, and again on every pick.
 */
export const ValuesReachTheHost: Story = {
  ...DisplayFilters,
  decorators: [DESK],
  args: { ...DisplayFilters.args, onFiltersChange: fn() },
  play: async ({ canvasElement, args }) => {
    const told = args.onFiltersChange as ReturnType<typeof fn>;
    await waitFor(() =>
      expect(told).toHaveBeenCalledWith({
        values: {
          created: { type: 'absolute', from: '2026-09-01', to: '2026-09-30' },
        },
        unit: 'DAY',
      }),
    );
    await pickSouth(await filterBar(canvasElement));
    await waitFor(() =>
      expect(told).toHaveBeenLastCalledWith(
        expect.objectContaining({
          values: expect.objectContaining({ region: ['CN-SOUTH'] }),
        }),
      ),
    );
  },
};

/** A phone: below `md` the board is one column, its filters one button. */
const PHONE = (Story: ComponentType) => (
  <div style={{ width: 390 }}>
    <Story />
  </div>
);

/**
 * On a phone (D26 Q38): the bar is one button, 「筛选（已设 1 个）」 —
 * 创建时间 is required and holds its default — and pressing it opens every
 * filter in a sheet from the bottom edge. 华南 picked there runs the panels
 * behind it and the count follows; the sheet is left open, so axe judges it.
 */
export const FiltersInASheetOnAPhone: Story = {
  ...DisplayFilters,
  decorators: [PHONE],
  play: async ({ canvasElement }) => {
    const bar = await filterBar(canvasElement);
    await waitFor(() => expect(bar).toHaveAttribute('data-narrow'));
    // Nothing is changed on the bar itself: its controls are in the sheet.
    await expect(within(bar).queryByRole('combobox')).toBeNull();
    const open = within(bar).getByRole('button', {
      name: label('label.filters.sheet-set', { count: '1' }),
    });
    await userEvent.click(open);
    const sheet = await screen.findByRole('dialog', {
      name: zhCN['label.filters.bar'],
    });
    // Every filter the bar holds, and the time grouping after them.
    await expect(
      [
        ...sheet.querySelectorAll<HTMLElement>(
          '[data-slot="dashboard-filter"]',
        ),
      ].map(chip => chip.dataset.filter),
    ).toEqual(['created', 'region', 'phase', 'order']);
    // Once it has slid in: it starts transparent.
    await waitFor(() =>
      expect(
        within(sheet).getByRole('group', {
          name: zhCN['label.filters.grouping'],
        }),
      ).toBeVisible(),
    );
    // The sheet stands on the bottom edge, the board's top still in view.
    await waitFor(() => {
      const box = sheet.getBoundingClientRect();
      expect(Math.round(box.bottom)).toBe(window.innerHeight);
      expect(box.top).toBeGreaterThan(0);
    });

    await pickSouth(sheet);
    await waitFor(() =>
      expect(open).toHaveTextContent(
        label('label.filters.sheet-set', { count: '2' }),
      ),
    );
    await expect(
      within(sheet).getByRole('combobox', { name: '仓库' }),
    ).toHaveTextContent('华南');
  },
};

/**
 * Every board filter chip's height, once the bar has drawn them and they
 * have stopped moving: a control's `transition-all` carries its height from
 * the last story's preset to this one's, so two reads 50ms apart agree.
 */
async function chipHeights(canvasElement: HTMLElement): Promise<number[]> {
  const bar = await filterBar(canvasElement);
  const read = () => {
    const chips = [
      ...bar.querySelectorAll<HTMLElement>('[data-control-frame]'),
    ].filter(chip => chip.getClientRects().length > 0);
    if (chips.length < 2) throw new Error('the chips are not drawn yet');
    return chips.map(chip => chip.getBoundingClientRect().height);
  };
  return waitFor(async () => {
    const before = read().join();
    await new Promise(resolve => setTimeout(resolve, 50));
    const after = read();
    if (after.join() !== before) throw new Error('The chips are still moving.');
    return after;
  });
}

/**
 * A chip's height is the theme's (`filter-height`, theme-architecture.md
 * 9.3): unset, its controls and its padding — 34px round a select, 38px
 * round a text box in neutral, 34px round porcelain's 28px controls; azure
 * sets 32px and the controls in it fill it.
 */
const chipsIn = (
  preset: 'neutral' | 'azure' | 'porcelain',
  heights: readonly number[],
): Story => ({
  ...DisplayFilters,
  decorators: [DESK],
  globals: { fvePreset: preset },
  play: async ({ canvasElement }) => {
    // The preset reaches `<html>` in an effect: measured before it lands,
    // the chips are the last story's.
    const html = document.documentElement;
    await waitFor(() =>
      preset === 'neutral'
        ? expect(html).not.toHaveAttribute('data-fve-preset')
        : expect(html).toHaveAttribute('data-fve-preset', preset),
    );
    const measured = [...new Set(await chipHeights(canvasElement))];
    await expect(measured.sort()).toEqual([...heights].sort());
  },
});

export const ChipsUnsetInNeutral: Story = chipsIn('neutral', [34, 38]);
export const ChipsAt32InAzure: Story = chipsIn('azure', [32]);
export const ChipsUnsetInPorcelain: Story = chipsIn('porcelain', [34]);
