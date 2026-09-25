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
import { formatMessage, zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  InTheWorkbench as DisplayInTheWorkbench,
  OnADashboard as DisplayOnADashboard,
} from './AnalysisBrush.stories.js';
import { brushAggregates } from './brushShipments.js';
import { chartsDrawn, drawnMarks, fadedMarks } from './chartDom.js';
import { findDataTable, readColumn } from './readTable.js';

const meta = {
  ...displayMeta,
  title: 'View Engine/能力/框选与追问/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const plotOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart-plot"]')!;

const frameOf = (root: HTMLElement) =>
  root.querySelector<HTMLElement>('[data-slot="chart"]')!;

/**
 * The middle of each day's column, left to right: the bars of one day stand
 * on one another, so their centres share an x.
 */
function dayCentres(root: HTMLElement): number[] {
  const xs = drawnMarks(root).map(mark => {
    const box = mark.getBoundingClientRect();
    return Math.round(box.left + box.width / 2);
  });
  return [...new Set(xs)].sort((a, b) => a - b);
}

/**
 * A drag along the plot from one day's column to another's, as a mouse
 * makes it: pressed, moved in steps, let go. Sent to the library's own
 * surface, where it listens.
 */
function brush(root: HTMLElement, from: number, to: number) {
  const plot = plotOf(root);
  const surface = plot.querySelector('svg')!.parentElement!;
  const box = plot.getBoundingClientRect();
  const y = box.top + box.height * 0.6;
  const fire = (type: string, x: number) =>
    surface.dispatchEvent(
      new MouseEvent(type, {
        bubbles: true,
        cancelable: true,
        clientX: x,
        clientY: y,
        button: 0,
        buttons: type === 'mouseup' ? 0 : 1,
      }),
    );
  // The pointer first: a mouse going down on the plot takes the drag.
  plot.firstElementChild!.dispatchEvent(
    new PointerEvent('pointerdown', { bubbles: true, pointerType: 'mouse' }),
  );
  fire('mousemove', from);
  fire('mousedown', from);
  for (let step = 1; step <= 10; step += 1)
    fire('mousemove', from + ((to - from) * step) / 10);
  fire('mouseup', to);
}

/** A tap on a point of the plot, by a finger or a mouse. */
function tap(
  root: HTMLElement,
  x: number,
  pointerType: 'touch' | 'mouse',
  at?: number,
) {
  const plot = plotOf(root);
  const surface = plot.querySelector('svg')!.parentElement!;
  const box = plot.getBoundingClientRect();
  const y = at ?? box.top + box.height * 0.75;
  plot.firstElementChild!.dispatchEvent(
    new PointerEvent('pointerdown', { bubbles: true, pointerType }),
  );
  for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])
    surface.dispatchEvent(
      new MouseEvent(type, {
        bubbles: true,
        cancelable: true,
        clientX: x,
        clientY: y,
      }),
    );
}

/** The follow-up menu, once it has faded in, named as asked. */
const drillMenu = (name: string) =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    if (!found) throw new Error('追问菜单没有弹出来');
    expect(found).toHaveAttribute('aria-label', name);
    expect(found).toBeVisible();
    return found;
  });

const noMenu = () =>
  waitFor(() =>
    expect(document.body.querySelector('[data-slot="drill-menu"]')).toBeNull(),
  );

/** The two days a stretch is read as: 「创建时间 介于 A ～ B」. */
function stretchDays(text: string): [number, number] {
  const days = [...text.matchAll(/(\d{4})年(\d{1,2})月(\d{1,2})日/g)].map(
    ([, year, month, day]) =>
      Date.UTC(Number(year), Number(month) - 1, Number(day)),
  );
  expect(days).toHaveLength(2);
  return [days[0]!, days[1]!];
}

const DAY_MS = 86_400_000;

const SPAN = zhCN['label.drill.menu-span'];

/**
 * 框三天，查看这些记录（D33 批 C 判据）：拖过三天的柱，弹出点一组时的同一个
 * 追问菜单，标题是这一段——「创建时间 介于 A ～ B」，A 到 B 正好三天；「查看
 * 这些记录」开出的记录视图条件就是这三天，两个仓库各一单，共六单；返回回到
 * 原来那次结果，不重跑、不变脏。
 */
export const BrushThreeDaysToRecords: Story = {
  ...DisplayInTheWorkbench,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    await expect(frameOf(canvasElement)).toHaveAttribute('data-brush', 'on');
    const days = await waitFor(() => {
      const found = dayCentres(canvasElement);
      expect(found).toHaveLength(30);
      return found;
    });

    brush(canvasElement, days[10]!, days[12]!);
    const menu = await drillMenu(SPAN);
    const heading = menu.querySelector('[data-slot="drill-group"]')!;
    const [first, last] = stretchDays(heading.textContent ?? '');
    await expect(last - first).toBe(2 * DAY_MS);
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([zhCN['label.drill.records'], zhCN['label.drill.focus-span']]);
    // Said aloud: a drag makes no sound.
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="drill-announcement"]'),
      ).toHaveTextContent(/^已选中 创建时间 介于 .+，追问菜单已打开。$/),
    );

    const ran = brushAggregates.current;
    await userEvent.click(
      within(menu).getByRole('menuitem', {
        name: zhCN['label.drill.records'],
      }),
    );
    // The records of those three days: two warehouses, one shipment a day.
    const table = await findDataTable(canvasElement);
    await waitFor(() => expect(readColumn(table, '单号')).toHaveLength(6));
    await expect(
      canvas.getByRole('region', { name: zhCN['label.applied.title'] }),
    ).toHaveTextContent(heading.textContent ?? '');

    await userEvent.click(
      canvas.getByRole('button', {
        name: formatMessage(zhCN, 'label.origin.back', {
          title: '每日发货金额',
        }),
      }),
    );
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(dayCentres(canvasElement)).toHaveLength(30));
    await expect(brushAggregates.current).toBe(ran);
    await expect(
      canvas.getByRole('heading', { level: 2, name: '每日发货金额' }),
    ).not.toHaveAttribute('data-dirty');
    // The cover went with the menu.
    await expect(frameOf(canvasElement)).not.toHaveAttribute('data-brushed');
  },
};

/**
 * 「只看这段时间」：同一个问题只问这一段，开在旁边——图上只剩那三天。
 */
export const BrushThenOnlyThisPeriod: Story = {
  ...DisplayInTheWorkbench,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const days = await waitFor(() => {
      const found = dayCentres(canvasElement);
      expect(found).toHaveLength(30);
      return found;
    });
    brush(canvasElement, days[3]!, days[5]!);
    const menu = await drillMenu(SPAN);
    await userEvent.click(
      within(menu).getByRole('menuitem', {
        name: zhCN['label.drill.focus-span'],
      }),
    );
    await waitFor(() => expect(dayCentres(canvasElement)).toHaveLength(3));
    // A press is still a press: one day's own menu.
    await chartsDrawn(canvasElement);
    tap(canvasElement, dayCentres(canvasElement)[1]!, 'mouse');
    await drillMenu(zhCN['label.drill.menu']);
    await userEvent.keyboard('{Escape}');
    await noMenu();
  },
};

/**
 * 键盘的框选（D33 批 C 判据）：表格布局里一行 Enter 是这一天；按住 Shift 再按
 * 另一行是两行之间的这段时间，菜单一开就说出选中了哪一段。行的描述说了 Shift
 * 这件事。
 */
export const KeyboardPicksAStretch: Story = {
  ...DisplayInTheWorkbench,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.table'] }),
    );
    const rows = await waitFor(() => {
      const found = [
        ...canvasElement.querySelectorAll<HTMLElement>('tr[data-pickable]'),
      ];
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    const hint = rows[0]!.getAttribute('aria-describedby') ?? '';
    await expect(document.getElementById(hint)).toHaveTextContent(
      zhCN['label.drill.span-hint'],
    );
    rows[2]!.focus();
    await userEvent.keyboard('{Enter}');
    await drillMenu(zhCN['label.drill.menu']);
    await userEvent.keyboard('{Escape}');
    await noMenu();
    await waitFor(() => expect(rows[2]).toHaveFocus());
    // Down to another day's row, then Shift+Enter.
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}');
    await userEvent.keyboard('{Shift>}{Enter}{/Shift}');
    const menu = await drillMenu(SPAN);
    const [first, last] = stretchDays(
      menu.querySelector('[data-slot="drill-group"]')?.textContent ?? '',
    );
    await expect(last - first).toBe(2 * DAY_MS);
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="drill-announcement"]'),
      ).toHaveTextContent(/追问菜单已打开/),
    );
    await userEvent.keyboard('{Escape}');
    await noMenu();
  },
};

/**
 * 键盘追问到记录、再返回，键盘都落在新视图的名字上（2026-09-25 纯键盘走查）：
 * 「查看这些记录」把按下的那一行连同分析视图一起换掉，「返回」按钮随记录视图
 * 一起走，从前两下都把键盘丢到 `<body>`，下一次 Tab 从页首重走。
 */
export const KeyboardFollowUpKeepsTheKeyboard: Story = {
  ...DisplayInTheWorkbench,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await chartsDrawn(canvasElement);
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.layout.table'] }),
    );
    const rows = await waitFor(() => {
      const found = [
        ...canvasElement.querySelectorAll<HTMLElement>('tr[data-pickable]'),
      ];
      expect(found.length).toBeGreaterThan(10);
      return found;
    });
    rows[2]!.focus();
    await userEvent.keyboard('{Enter}');
    await drillMenu(zhCN['label.drill.menu']);
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() =>
      expect(document.activeElement).toHaveTextContent(
        zhCN['label.drill.records'],
      ),
    );
    await userEvent.keyboard('{Enter}');
    const table = await findDataTable(canvasElement);
    await waitFor(() =>
      expect(readColumn(table, '单号').length).toBeGreaterThan(0),
    );
    await noMenu();
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-title"]'),
      ).toHaveFocus(),
    );

    const back = canvas.getByRole('button', {
      name: formatMessage(zhCN, 'label.origin.back', {
        title: '每日发货金额',
      }),
    });
    back.focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() =>
      expect(
        canvas.getByRole('heading', { level: 2, name: '每日发货金额' }),
      ).toHaveFocus(),
    );
  },
};

/**
 * 触屏先看、再追问（D33 批 C 判据）：手指第一下点在柱上只出提示框，提示框
 * 底下一行「再点一下追问」；同一根柱再点一下才开菜单。鼠标照旧一按就开。
 */
export const TouchReadsBeforeItAsks: Story = {
  ...DisplayInTheWorkbench,
  play: async ({ canvasElement }) => {
    await chartsDrawn(canvasElement);
    const days = await waitFor(() => {
      const found = dayCentres(canvasElement);
      expect(found).toHaveLength(30);
      return found;
    });
    tap(canvasElement, days[8]!, 'touch');
    const tooltip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="chart-tooltip"]',
      );
      expect(found).toBeVisible();
      return found!;
    });
    await expect(frameOf(canvasElement)).toHaveAttribute(
      'data-tap-armed',
      'true',
    );
    await expect(getComputedStyle(tooltip, '::after').content).toBe(
      `"${zhCN['label.drill.tap-again']}"`,
    );
    await expect(
      document.body.querySelector('[data-slot="drill-menu"]'),
    ).toBeNull();
    tap(canvasElement, days[8]!, 'touch');
    await drillMenu(zhCN['label.drill.menu']);
    await expect(frameOf(canvasElement)).not.toHaveAttribute('data-tap-armed');
    await userEvent.keyboard('{Escape}');
    await noMenu();
  },
};

/** One panel of the board, by its title. */
const panelOf = (root: HTMLElement, title: string) =>
  within(root).findByRole('group', { name: title });

/**
 * Whether the board's filters say a value came from a panel: on the bar, or
 * — where the board is read in one column — in the sheet the bar folds into,
 * opened and closed again to look.
 */
async function saysFrom(root: HTMLElement, panel: string) {
  const words = formatMessage(zhCN, 'label.click.from', { panel });
  const sheet = within(root).queryByRole('button', { name: /^筛选/ });
  if (!sheet) {
    await waitFor(() => expect(root).toHaveTextContent(words));
    return;
  }
  // The sheet's button counts the filters set once the press has landed.
  await waitFor(() => expect(sheet).toHaveAccessibleName(/^筛选（已设/));
  await userEvent.click(sheet);
  await waitFor(() => expect(document.body).toHaveTextContent(words));
  await userEvent.keyboard('{Escape}');
}

/** What a panel's reading table says, as the numbers it holds. */
const readingOf = (panel: HTMLElement) =>
  [...panel.querySelectorAll('[data-slot="chart-reading"] td')].map(
    cell => cell.textContent ?? '',
  );

/**
 * 看板上「设为「日期」」（D33 批 C 判据，同 D23 Q18）：框按日的柱三天，菜单
 * 里多一项「设为「日期」」；按下去，「日期」筛选条写着来自这块面板，按仓库
 * 汇总在它之下重跑、数变了，被框的这块面板不筛自己——三十天都在，框里的三天
 * 六根柱标出、其余变淡。
 */
export const DashboardBrushSetsTheDate: Story = {
  ...DisplayOnADashboard,
  play: async ({ canvasElement }) => {
    const daily = await panelOf(canvasElement, '每日发货金额');
    const summary = await panelOf(canvasElement, '按仓库汇总');
    await chartsDrawn(canvasElement);
    const days = await waitFor(() => {
      const found = dayCentres(daily);
      expect(found).toHaveLength(30);
      return found;
    });
    const before = readingOf(summary);

    brush(daily, days[20]!, days[22]!);
    const menu = await drillMenu(SPAN);
    const set = formatMessage(zhCN, 'label.drill.set-filter', {
      filter: '日期',
    });
    await userEvent.click(within(menu).getByRole('menuitem', { name: set }));
    await noMenu();

    // The bar says where the value came from.
    await saysFrom(canvasElement, '每日发货金额');
    // The others run under it…
    await waitFor(() => expect(readingOf(summary)).not.toEqual(before));
    // …and the panel brushed keeps every day, marking the three inside.
    await chartsDrawn(daily);
    await expect(frameOf(daily)).toHaveAttribute('data-marks', '60');
    await waitFor(() =>
      expect(frameOf(daily)).toHaveAttribute('data-highlighted', '6'),
    );
    await expect(fadedMarks(daily)).toHaveLength(54);
  },
};

/**
 * 按维度分段的漏斗一段可按（D33 批 C 判据）：漏斗的点击设「发货仓」——点华东
 * 仓那一段，筛选条写着华东仓、来自「仓库漏斗」，漏斗自己不筛、另一段变淡。
 */
export const FunnelStageSetsTheWarehouse: Story = {
  ...DisplayOnADashboard,
  play: async ({ canvasElement }) => {
    const funnel = await panelOf(canvasElement, '仓库漏斗');
    await chartsDrawn(canvasElement);
    // Where a reader would press it: on screen.
    funnel.scrollIntoView({ block: 'center' });
    await chartsDrawn(funnel);
    const stages = await waitFor(() => {
      const found = drawnMarks(funnel);
      expect(found).toHaveLength(2);
      return found;
    });
    const east = stages
      .map(stage => stage.getBoundingClientRect())
      .sort((a, b) => a.top - b.top)[0]!;
    tap(
      funnel,
      east.left + east.width / 2,
      'mouse',
      east.top + east.height / 2,
    );
    await saysFrom(canvasElement, '仓库漏斗');
    await waitFor(() => expect(fadedMarks(funnel)).toHaveLength(1));
    await expect(frameOf(funnel)).toHaveAttribute('data-marks', '2');
  },
};
