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
  CellFamily as DisplayCellFamily,
  ManageViews as DisplayManageViews,
  NeedsFixing as DisplayNeedsFixing,
  PopupsOverRaisedHostLayer as DisplayPopupsOverRaisedHostLayer,
  TotalCoversThisPageOnly as DisplayTotalCoversThisPageOnly,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import {
  measureBorderContrast,
  measureFillContrast,
  measureLayerSeparation,
  measureTextContrast,
} from './contrast.js';
import { readColumn } from './readTable.js';
import {
  PENDING_BY_AMOUNT,
  deleteDialog,
  inFrontOf,
  listItem,
  openManager,
  paginationBar,
  say,
  settled,
  tabTo,
} from './recordWorkbenchTest.js';

/**
 * Theme and contrast: the type scale, contrast in both themes, the popup layer
 * and reduced motion. One of the record workbench's regression files, split by
 * concern; they all share one title, so every story keeps its id, and the
 * helpers more than one of them needs are in `recordWorkbenchTest.ts`.
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
 * 正文对比度的下限：WCAG 1.4.3 的 4.5:1。
 *
 * 状态条的句子、表头的列名、徽章上的状态词，都是正文大小或更小的文字——不是
 * 大字号，也不是装饰，所以三处守的是同一个数，写在一处。
 */
const TEXT_CONTRAST = 4.5;

/** What one element's text is actually set in, as the browser resolved it. */
const sizeOf = (node: Element) => getComputedStyle(node).fontSize;

/**
 * Three rungs of type, not four.
 *
 * One screen used to carry 12, 12.8, 14 and 16px. The middle pair is the
 * problem: 0.8px is not a rank, so a sidebar view item (12.8, the registry's
 * `sm` control size) and the group label right above it (12, `text-xs`) read
 * as one size drawn badly rather than as two; and 12.8px lands off the pixel
 * grid, which is what made 中文 at that size look blurry. Both are now the
 * one step under the body size — `--_fve-text-ui`, 13px — so the scale is
 * 13 / 14 / 16 and every gap in it is one the eye can name.
 *
 * Measured rather than asserted in a class name, because the whole point is
 * what the cascade resolves: the sidebar item takes its size from a vendored
 * `Button`, which the theme reaches through the utility it names.
 */
export const TypeScaleIsThreeRungs: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');

    // The pair that was 0.8px apart, now one rung.
    const item = listItem(canvasElement, '待出库订单');
    const heading = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-group-heading"]',
    )!;
    await expect(sizeOf(item)).toBe('13px');
    await expect(sizeOf(heading)).toBe('13px');

    // And everything else on that rung: the column headers, the enum
    // badges in the cells, the pagination line.
    const head = table.querySelector<HTMLElement>('thead th')!;
    const badge = canvasElement.querySelector<HTMLElement>(
      '[data-slot="badge"]',
    )!;
    const pagination = canvasElement.querySelector<HTMLElement>(
      '[data-slot="record-pagination"]',
    )!;
    for (const node of [head, badge, pagination])
      await expect(sizeOf(node)).toBe('13px');

    // The two rungs above it, so what is asserted is a scale and not one
    // number: the rows are the body size, the definition's name is the h1.
    const cell = table.querySelector<HTMLElement>('tbody td')!;
    const title = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list-title"]',
    )!;
    await expect(sizeOf(cell)).toBe('14px');
    await expect(sizeOf(title)).toBe('16px');

    // Nothing anywhere on the screen is still set in the step that went.
    const stray = [...canvasElement.querySelectorAll('*')].filter(
      node => sizeOf(node) === '12.8px',
    );
    await expect(stray).toHaveLength(0);
  },
};

/**
 * Every popup kind, as the workbench opens it.
 *
 * One entry per wrapper in `ui/kit/popups.tsx` that a user of this workbench can
 * reach: a popover, a menu, a select's list, a tooltip and a dialog. The
 * combobox is the sixth wrapper and no surface here opens one, so it is held
 * to the same rule in `test/popups.test.tsx` instead.
 */
const POPUP_KINDS: readonly {
  name: string;
  slot: string;
  trigger: string;
  /** Opened by pointing at the trigger rather than by pressing it. */
  hover?: boolean;
}[] = [
  {
    name: 'popover',
    slot: 'popover-content',
    // The column-settings popover, which is where this first went wrong.
    trigger: '[data-slot="result-toolbar"] [data-slot="popover-trigger"]',
  },
  {
    name: 'menu',
    slot: 'dropdown-menu-content',
    // Found by what it does rather than by its slot: every icon-only trigger
    // on this bar is wrapped in a tooltip now, and the outer trigger's
    // `data-slot` replaces the menu's own on the one element they share.
    trigger: '[aria-haspopup="menu"]',
  },
  {
    name: 'select',
    slot: 'select-content',
    // The page-size control, which is why this story pages its rows.
    trigger: '[data-slot="select-trigger"]',
  },
  {
    name: 'tooltip',
    slot: 'tooltip-content',
    trigger: '[data-slot="tooltip-trigger"]',
    hover: true,
  },
  {
    name: 'dialog',
    slot: 'dialog-content',
    // The manager, behind the sidebar's gear: a dialog portals a backdrop of
    // its own and is centred on the viewport rather than on the workbench.
    trigger: `[aria-label="${zhCN['label.manage.open']}"]`,
  },
];

/**
 * The layer this package's popups paint on, and the one a host can move.
 *
 * Whatever the host raises, a popup has to come out in front of it — the fix
 * is a `z-index` on the *positioner*, written as a style in `ui/kit/popups.tsx`
 * because the positioner is no `.fve-root` and every rule of the stylesheet
 * is pinned inside one. Before it, a positioner stayed at `z-index: auto` and
 * every popup here painted at level 0, in front of the page only because its
 * portal is last in the body: a host layer at `z-index: 1` covered the lot.
 *
 * The premise is checked as carefully as the claim. A raised layer proves
 * nothing if some ancestor trapped it in a stacking context of its own, since
 * it would then rank by document order and the popups would win without any
 * of this — so the layer is read for its level, for the absence of such an
 * ancestor, and for actually covering the view before a single popup opens.
 */
export const PopupsOverRaisedHostLayer: Story = {
  ...DisplayPopupsOverRaisedHostLayer,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const raised = document.querySelector<HTMLElement>('[data-raised-host]')!;

    // The premise, in the three parts it has.
    await expect(getComputedStyle(raised).zIndex).toBe('10');
    await expect(trappedIn(raised)).toBeNull();
    await expect(inFrontOf(raised)).toBe(true);

    /** The popup of that kind, open and placed, and what opened it. */
    async function open(kind: (typeof POPUP_KINDS)[number]) {
      // The first trigger a pointer can reach: an icon inside a button is
      // `pointer-events: none` by the button's own rule, and a tooltip on one
      // is no more reachable for the user than it is here.
      const trigger = [
        ...canvasElement.querySelectorAll<HTMLElement>(kind.trigger),
      ].find(candidate => getComputedStyle(candidate).pointerEvents !== 'none');
      await expect(trigger, `no ${kind.name} to open`).toBeDefined();
      // The raised layer covers the trigger as it covers everything else, so
      // the event goes to the element rather than to a point on the screen.
      if (kind.hover) await userEvent.hover(trigger!);
      else await userEvent.click(trigger!);

      // Open, and laid out: a popup is in the document before it is placed,
      // and a hit test on a box of no size answers about the page behind it.
      const popup = await waitFor(() => {
        const found = document.body.querySelector<HTMLElement>(
          `[data-slot="${kind.slot}"]`,
        );
        if (!found || found.hasAttribute('data-closed'))
          throw new Error(`no open ${kind.name}`);
        const box = found.getBoundingClientRect();
        if (box.width === 0 || box.height === 0)
          throw new Error(`the ${kind.name} has no box yet`);
        return found;
      });
      // Portalled out of the surface, which is exactly why this can go wrong.
      await expect(popup.closest('[data-slot="view-surface"]')).toBeNull();
      return { popup, trigger: trigger! };
    }

    /**
     * Shut again before the next one opens. A popup on its way out stays in
     * the document for the length of its animation, so what is waited for is
     * that it is no longer open.
     */
    async function close(
      kind: (typeof POPUP_KINDS)[number],
      trigger: HTMLElement,
    ) {
      if (kind.hover) await userEvent.unhover(trigger);
      else await userEvent.keyboard('{Escape}');
      await waitFor(() => {
        const leaving = document.body.querySelector(
          `[data-slot="${kind.slot}"]`,
        );
        expect(leaving === null || leaving.hasAttribute('data-closed')).toBe(
          true,
        );
      });
    }

    /** The element the level is written on: a dialog has no positioner. */
    const layerOf = (kind: (typeof POPUP_KINDS)[number], popup: HTMLElement) =>
      kind.slot === 'dialog-content' ? popup : popup.parentElement!;

    for (const kind of POPUP_KINDS) {
      const { popup, trigger } = await open(kind);
      // The cause, and then the effect the user sees.
      await expect(getComputedStyle(layerOf(kind, popup)).zIndex).toBe('50');
      await expect(inFrontOf(popup), `the ${kind.name} is buried`).toBe(true);
      await close(kind, trigger);
    }

    // And the number really is what decides, which is what `--fve-popup-z-index`
    // offers a host whose own chrome stacks above 50. Turned *below* what this
    // host raised, the same popover goes behind it — the variable is read from
    // `:root`, where a host sets it beside the colour tokens.
    const root = document.documentElement;
    try {
      root.style.setProperty('--fve-popup-z-index', '3');
      const { popup, trigger } = await open(POPUP_KINDS[0]);
      await expect(
        getComputedStyle(layerOf(POPUP_KINDS[0], popup)).zIndex,
      ).toBe('3');
      await expect(inFrontOf(popup)).toBe(false);
      await close(POPUP_KINDS[0], trigger);
    } finally {
      root.style.removeProperty('--fve-popup-z-index');
    }
  },
};

/** What WCAG 1.4.11 asks of the visual information a control is known by. */
const NON_TEXT_CONTRAST = 3;

/**
 * The edge every unticked control is made of, measured in the browser.
 *
 * A checkbox nobody has ticked is *only* this ring — there is nothing else on
 * screen to say a control is there — and so are the outlines of an Input and
 * of a Select trigger. All three draw it with `border-input`, the theme's
 * `--input`, which is why this is measured rather than argued: a stylesheet
 * says `oklch(…)` and Tailwind's opacity modifiers say `color-mix(…)`, while
 * what reaches the eye is the cascaded colour composited over whatever is
 * behind it. jsdom paints none of that, so this regression lives here.
 *
 * The three the theme has to carry are on one screen: the table's select-all
 * Checkbox, the page-size Select under the rows, and — once a number field is
 * ticked into the conditions, the way `PickSeveralFields` does it — an Input
 * with no value in it yet.
 */
const controlBorders = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    // Two stories measuring the same theme would both pass and prove half of
    // this, so the mode is read off the surface before anything else.
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '金额' }),
    );
    // A text input that stands on its own: the picker's search field. The
    // one inside a condition pill is borderless by design (D12 — the pill
    // is the field, and draws the one border), so it is not what 1.4.11
    // asks about; its edge is the pill's.
    // The picker's search field is an `InputGroupInput`, and the
    // `InputGroup` around it is what draws the border; measure that box.
    const input = measureBorderContrast(
      within(picker)
        .getByRole('textbox', { name: zhCN['label.field.search'] })
        .closest<HTMLElement>('[data-slot="input-group"]')!,
    );
    // Shut behind itself, so nothing is measured through a popup and axe
    // judges the page as a user would leave it.
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );
    // Ticked, a checkbox is a filled square and this stops being the whole
    // of it; the measurement is about the state that has nothing else.
    const checkbox = canvas.getByRole('checkbox', {
      name: zhCN['label.record.select-all'],
    });
    await expect(checkbox).not.toBeChecked();

    const controls = {
      checkbox,
      select: within(paginationBar(canvasElement)).getByRole('combobox', {
        name: zhCN['label.pagination.page-size'],
      }),
    };

    // All three are measured before anything is asserted, so a failure says
    // what every control came to rather than stopping at the first one.
    const measured = [
      { name: 'input', ...input },
      ...Object.entries(controls).map(([name, control]) => ({
        name,
        ...measureBorderContrast(control),
      })),
    ];
    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 (${colors.border} on ${colors.fill} over ${colors.surface})`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
  },
});

/**
 * 焦点指示在两个主题里都 ≥3:1，而且一屏只有一种画法。
 *
 * vendored 的 `Button` 以 1px `border-ring` 加 3px 半透明光晕表示焦点；表头的排序
 * 按钮与已应用条上的 ✕ 从前是裸 `<button>` 各抄一份同款配方，现在就是那个
 * `Button`（`variant="ghost"`），所以一屏只有一种画法。评审量到 `--ring` 在 `0.708` 时边线只有 2.59:1、光晕
 * 1.54:1，三处又各画各的（一处还是 UA 的 `outline: auto`）。焦点由 Tab 键送到
 * 目标上：脚本调 `focus()` 不一定算 `:focus-visible`，键盘一定算。
 */
const focusIndicators = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    // A vendored `Button` that is always enabled here — the editor's toggle
    // is a Base UI toggle with an `input` border of its own, not this.
    const columnsButton = canvas.getByRole('button', {
      name: zhCN['label.toolbar.columns'],
    });
    const sortButton = table.querySelector<HTMLElement>('thead button')!;
    const unset = canvas.getAllByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.unset-of'].split(' ')[0]}`),
    })[0];

    // Each mark is read the moment Tab lands, before a frame is drawn: the
    // mark is owed then, not after a fade. The vendored button's
    // `transition-all` used to fade it in over 150ms, and a transition moves
    // only on a frame — so on a slow one (nightly WebKit) the ✕ measured
    // its own transparent edge, 1.00:1, while waiting for the fade.
    const measured: { name: string; ratio: number; colors: object }[] = [];
    await tabTo(columnsButton);
    measured.push({ name: 'button', ...measureBorderContrast(columnsButton) });
    await tabTo(unset);
    measured.push({ name: 'unset', ...measureBorderContrast(unset) });
    await tabTo(sortButton);
    measured.push({ name: 'sort', ...measureBorderContrast(sortButton) });

    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 ${JSON.stringify(colors)}`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(NON_TEXT_CONTRAST);
    // The same recipe everywhere: a focused bare button wears the colour the
    // vendored button puts on its border, and the same halo.
    const border = getComputedStyle(sortButton).borderTopColor;
    const halo = getComputedStyle(sortButton).boxShadow;
    await tabTo(columnsButton);
    await expect(getComputedStyle(columnsButton).borderTopColor).toBe(border);
    await expect(getComputedStyle(columnsButton).boxShadow).toBe(halo);
  },
});

export const FocusIndicatorsInLightTheme: Story = focusIndicators('light');
export const FocusIndicatorsInDarkTheme: Story = focusIndicators('dark');

/**
 * 暗色下的行线看得见。
 *
 * 分隔线不是控件，不欠 3:1，但 10% 白在暗色卡片上量到 1.32:1——一张没有行的
 * 表。这里量的是 `tbody` 行的下边线压在它自己的底色与卡片之上的层叠色。
 */
export const DarkHairlines: Story = {
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme: 'dark' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const [row, next] = table.querySelectorAll<HTMLElement>('tbody tr');
    const { ratio, colors } = measureBorderContrast(row!, 'bottom');
    // A preset that stripes its rows may clear the line between them (D59:
    // a desktop list is striped or ruled, not both); then the stripe is
    // what tells two rows apart.
    if (colors.border === colors.fill) {
      const stripe = measureFillContrast(next!);
      await expect(
        stripe.ratio,
        `stripe ${stripe.colors.fill} over ${stripe.colors.surface}`,
      ).toBeGreaterThanOrEqual(1.05);
      return;
    }
    await expect(
      ratio,
      `${colors.border} on ${colors.fill} over ${colors.surface}`,
    ).toBeGreaterThanOrEqual(1.5);
  },
};

/**
 * 一枚徽章在它所在的行上还看得见——静息、悬停、选中都算。
 *
 * 行底是会动的：选中走 `bg-muted`，悬停走同一档灰的不透明 `color-mix`，而
 * `secondary` 徽章的底色正是那一档——量到 **1.00:1**，徽章直接归零成一个词。
 * 这里量的是徽章最外那 1px 压在行底上的层叠色（`onSurface`）：没有语气的徽章
 * 靠 `border-input` 的边说话，有语气的那几枚靠一圈 30% 的同色边——10% 的淡底
 * 单独离行底只有 1.16–1.22:1——所以同一个数对两族都成立。jsdom 不套样式表，
 * 这个数只有真浏览器给得出。
 */
const badgesOnRows = (theme: 'light' | 'dark'): Story => ({
  ...DisplayCellFamily,
  args: { ...DisplayCellFamily.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    const rows = [...(table as HTMLTableElement).tBodies[0].rows];

    /**
     * Every badge of one row, against the ground that row is on. A badge
     * with a ring is told by its ring; one a preset draws with none (D59:
     * a wash and a word) by its word on the wash over that ground, which is
     * what must still read.
     */
    const onRow = (row: HTMLTableRowElement, state: string) =>
      [...row.querySelectorAll<HTMLElement>('[data-slot="badge"]')].map(
        badge => {
          const name = `${state} ${badge.dataset.tone} "${badge.textContent}"`;
          const ring = measureBorderContrast(badge);
          if (
            !/(\/\s*0\)|,\s*0\))$/.test(getComputedStyle(badge).borderTopColor)
          )
            return {
              name,
              value: ring.onSurface,
              floor: BADGE_ON_ROW_CONTRAST,
              seen: `${ring.colors.border} over ${ring.colors.surface}`,
            };
          const word = measureTextContrast(badge);
          return {
            name,
            value: word.ratio,
            floor: 4.5,
            seen: `${word.colors.text} on ${word.colors.background}`,
          };
        },
      );

    // Selected first: the row takes `--muted`, which is the untoned badge's
    // own fill, so this is the ground that used to erase it.
    await userEvent.click(
      within(rows[0]).getByRole('checkbox', {
        name: say('label.record.select', { key: 'SO-1001' }),
      }),
    );
    await waitFor(() =>
      expect(rows[0]).toHaveAttribute('data-state', 'selected'),
    );
    await settled(() => getComputedStyle(rows[0]).backgroundColor);
    const measured = onRow(rows[0], 'selected');

    // Hovered, which is the same grey mixed halfway into the page.
    await userEvent.hover(rows[1]);
    await settled(() => getComputedStyle(rows[1]).backgroundColor);
    measured.push(...onRow(rows[1], 'hovered'));
    await userEvent.unhover(rows[1]);

    // And at rest, so the two above are read against the one they moved from.
    measured.push(...onRow(rows[2], 'resting'));

    const short = measured.filter(({ value, floor }) => value < floor);
    await expect(
      short.map(
        ({ name, value, floor, seen }) =>
          `${name} ${value.toFixed(2)}:1 < ${floor} (${seen})`,
      ),
      theme,
    ).toEqual([]);
  },
});

/**
 * 徽章与行底的下限。三档行底共用一档 3% 灰是设计，徽章因此只欠"还看得出是
 * 一枚徽章"，而不是 1.4.11 对控件要的 3:1。
 */
const BADGE_ON_ROW_CONTRAST = 1.5;

export const BadgesOnRowsInLightTheme: Story = badgesOnRows('light');
export const BadgesOnRowsInDarkTheme: Story = badgesOnRows('dark');

/**
 * 首尾两条灰带把数据行夹在中间，带上的列名是正文的墨色。
 *
 * 表头从前和数据行同为 `bg-background`，中间只有一根发丝线和一行灰字——用户
 * 2026-09-22 的评审说第一行读起来像表头的一部分。现在表头与汇总层是**同一档
 * 灰**（`ui/record/columns.ts` 的 `BAND`），所以这条故事量的第一件事是两条带
 * 子的层叠色**逐字节相等**，第二件事是它们都不等于行底——一档谁也夹不住的灰
 * 不是带子。
 *
 * 列名因此换回 registry 自己的 `text-foreground`：`text-muted-foreground`
 * 压在这一档灰上量到 4.34:1，跌破 1.4.3 的 4.5，而它是整个表面上最小的一号
 * 字。两条带子之间只有格子自己那 1px 发丝线——`<thead>` 上从前那条
 * `border-b-2` 写在 `<tr>` 上，而分开的边框模型里行没有自己的边，屏幕上从来
 * 就是 1px；带子的底色接手了分隔这件事。jsdom 不套样式表，这些数只有真浏览
 * 器给得出。
 *
 * 这是 neutral 的设计：表头与汇总层不设角色时同为 `muted`。预设可以用
 * `table-header` 把两者分开（porcelain 的表头无底，S9），所以故事钉在
 * neutral 上，而不是跟 Storybook 的默认预设走。
 */
const headerBand = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme, preset: 'neutral' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = (await canvas.findByRole('table')) as HTMLTableElement;
    await waitFor(() =>
      expect(readColumn(table, '订单号')).toEqual(PENDING_BY_AMOUNT),
    );
    // 两条量同一主题的故事都会通过并各证一半，所以先把模式读出来。
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    const head = table.tHead!.rows[0];
    const foot = table.tFoot!.rows[0];
    const row = table.tBodies[0].rows[0];
    const band = measureFillContrast(head).colors.fill;
    const footBand = measureFillContrast(foot).colors.fill;
    const rowFill = measureFillContrast(row).colors.fill;
    await expect(band, `${theme} — 表头 ${band}，汇总 ${footBand}`).toBe(
      footBand,
    );
    await expect(band, `${theme} — 带子与行底同色 ${band}`).not.toBe(rowFill);

    // 分隔只有格子自己那一根发丝线，而不是写在行上的那 2px。
    await expect(getComputedStyle(head.cells[0]).borderBottomWidth).toBe('1px');

    // 带上的每一个列名，压在带子上。字在排序按钮里，按钮静息时没有底色，所以
    // 量的还是带子。
    const measured = [...head.cells]
      .filter(cell => (cell.textContent ?? '').trim().length > 0)
      .map(cell => ({
        name: cell.textContent!.trim(),
        ...measureTextContrast(cell.querySelector('button') ?? cell),
      }));
    const report = measured
      .map(
        ({ name, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 (${colors.text} on ${colors.background})`,
      )
      .join('; ');
    await expect(measured.length).toBeGreaterThan(0);
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);
  },
});

export const HeaderBandInLightTheme: Story = headerBand('light');
export const HeaderBandInDarkTheme: Story = headerBand('dark');

/**
 * 每一档语气的字压在它自己的底色上都读得出来，而且语气从来不是唯一的区别。
 *
 * 徽章是软配方（`ui/kit/variants.tsx`，2026-09-23 v16 视觉稿）：10% 淡底、语气色
 * 写字。淡底是半透明的，字对的是「淡底叠在行底上」的层叠色，所以行底动了这个
 * 数就跟着动——这里在三档行底上各量一遍：静息、悬停、选中（选中时行底是
 * `--muted`，淡底落在灰上，是最紧的一档）。暗色 danger 曾在选中行上只有
 * 3.92:1、靠向前景色混五分之一补上；暗色状态色降饱和、`--destructive` 提亮之后
 * （阶段 5，Q44）它直接用自己的 token，补丁已删。
 *
 * 颜色之外还要有字（WCAG 1.4.1）：四档语气都在场，每一枚都有非空的标签，而且
 * 没有两档共用同一个词。
 */
const toneBadgeInk = (theme: 'light' | 'dark'): Story => ({
  ...DisplayCellFamily,
  args: { ...DisplayCellFamily.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() => expect(readColumn(table, '订单号')).toHaveLength(6));
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    const badges = [
      ...table.querySelectorAll<HTMLElement>('[data-slot="badge"][data-tone]'),
    ];
    const words = new Map<string, Set<string>>();
    for (const badge of badges) {
      const label = (badge.textContent ?? '').trim();
      await expect(label, '一枚没有字的徽章只剩颜色').not.toBe('');
      const tone = badge.dataset.tone!;
      words.set(tone, (words.get(tone) ?? new Set()).add(label));
    }
    await expect([...words.keys()].sort()).toEqual([
      'danger',
      'neutral',
      'success',
      'warning',
    ]);
    // 一个词只属于一档语气，否则读者就只能靠颜色分辨这两档。
    const all = [...words.values()].flatMap(set => [...set]);
    await expect(all.length).toBe(new Set(all).size);

    /** Every toned badge of one row, against the ground that row is on. */
    const inkOf = (row: HTMLTableRowElement, state: string) =>
      [
        ...row.querySelectorAll<HTMLElement>('[data-slot="badge"][data-tone]'),
      ].map(badge => ({
        name: `${state} ${badge.dataset.tone} "${(badge.textContent ?? '').trim()}"`,
        size: getComputedStyle(badge).fontSize,
        ...measureTextContrast(badge),
      }));
    const rows = [...(table as HTMLTableElement).tBodies[0].rows];

    // At rest first, every row: all four tones are on the page.
    const measured = rows.flatMap(row => inkOf(row, 'resting'));

    // Selected, where the tint lands on `--muted`: the tightest ground.
    await userEvent.click(
      within(rows[0]).getByRole('checkbox', {
        name: say('label.record.select', { key: 'SO-1001' }),
      }),
    );
    await waitFor(() =>
      expect(rows[0]).toHaveAttribute('data-state', 'selected'),
    );
    await settled(() => getComputedStyle(rows[0]).backgroundColor);
    measured.push(...inkOf(rows[0], 'selected'));

    // Hovered, the same grey mixed halfway into the page.
    await userEvent.hover(rows[1]);
    await settled(() => getComputedStyle(rows[1]).backgroundColor);
    measured.push(...inkOf(rows[1], 'hovered'));
    await userEvent.unhover(rows[1]);

    const report = measured
      .map(
        ({ name, size, ratio, colors }) =>
          `${name} ${ratio.toFixed(2)}:1 @${size} (${colors.text} on ${colors.background})`,
      )
      .join('; ');
    await expect(
      Math.min(...measured.map(({ ratio }) => ratio)),
      `${theme} — ${report}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);
  },
});

export const ToneBadgeInkInLightTheme: Story = toneBadgeInk('light');
export const ToneBadgeInkInDarkTheme: Story = toneBadgeInk('dark');

/** The light theme's `--input`, over the card and the header it sits on. */
export const ControlBordersInLightTheme: Story = controlBorders('light');

/**
 * The same controls with the surface pinned dark, where the token is white at
 * an opacity and carries the `bg-input/30` fill with it.
 */
export const ControlBordersInDarkTheme: Story = controlBorders('dark');

/**
 * 状态条在两种主题下都读得出来，而且还是一行高。
 *
 * 它是 registry `Alert` 的紧凑变体（`ui/kit/alerts.tsx`），tone 只改文字与边的
 * 颜色，底色是 `bg-card`——所以"读不读得出来"这一问在两种主题下是两道题：
 * 亮色下 `--warning` 压在白卡片上，暗色下同一个 token 压在 0.205 的卡片上。
 * jsdom 不套样式表，这两个数只有真浏览器给得出。一行高是 D1 的约束，这里
 * 按实测高度守着：一句话、没展开的发现、行尾的按钮，都不该把结果顶下去。
 */
const calloutTone = (
  theme: 'light' | 'dark',
  base: Story,
  tone: 'error' | 'warning',
): Story => ({
  ...base,
  args: { ...base.args, theme },
  play: async ({ canvasElement }) => {
    // Two stories measuring the same theme would both pass and prove half of
    // this, so the mode is read off the surface before anything else.
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-surface"]'),
      ).toHaveAttribute('data-theme', theme),
    );

    // A config that will not run has no table to wait for — the strip saying
    // so is the thing that arrives.
    const strip = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        `[data-slot="status-strip"][data-tone="${tone}"]`,
      );
      if (!found) throw new Error(`No ${tone} status strip on screen.`);
      return found;
    });

    // The sentence, which is the element that carries the tone's colour.
    const sentence = strip.querySelector<HTMLElement>(
      '[data-slot="alert-title"]',
    )!;
    const { ratio, colors } = measureTextContrast(sentence);
    await expect(
      ratio,
      `${theme} ${tone} — ${colors.text} on ${colors.background}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);

    // D1: one line high. The icon is 16px and the toggle beside it is the
    // `xs` button (24px), so a line of it clears 24 and nothing above 40 is
    // still one line.
    await expect(
      strip.getBoundingClientRect().height,
      `${theme} ${tone} — the strip is not one line high`,
    ).toBeLessThanOrEqual(ONE_LINE_HIGH);
  },
});

/** 一行的上限（px）：16px 的图标、24px 的 `xs` 按钮，加上 4px 的上下内边距。 */
const ONE_LINE_HIGH = 40;

export const ErrorCalloutInLightTheme: Story = calloutTone(
  'light',
  DisplayNeedsFixing,
  'error',
);
export const ErrorCalloutInDarkTheme: Story = calloutTone(
  'dark',
  DisplayNeedsFixing,
  'error',
);
export const WarningCalloutInLightTheme: Story = calloutTone(
  'light',
  DisplayTotalCoversThisPageOnly,
  'warning',
);
export const WarningCalloutInDarkTheme: Story = calloutTone(
  'dark',
  DisplayTotalCoversThisPageOnly,
  'warning',
);

/**
 * 删除确认上那颗「删除」读得出来，而且它盖住的那张列表看得出被盖住了。
 *
 * registry 的 `destructive` 按钮是一抹 10% 淡彩（`bg-destructive/10` 配
 * `text-destructive`），跟 `ui/record.md` 记过的徽章是同一个陷阱：淡彩把底色
 * 朝字的那个色相挪过去，字于是压在一个已经被自己染过的底上——这颗按钮在亮色
 * 下量到 **3.97:1**（14px），够不着 1.4.3 的 4.5。修法也是同一个：拿 token 填
 * 色、拿 token 自己的 `-foreground` 写字（`ui/kit/variants.tsx` 的
 * `DestructiveAction`）。
 *
 * 顺带量第二件事：这个对话框是从**视图管理器**（一个 `Dialog`）的某一行上抬起
 * 来的，而 Base UI 默认**根本不画**嵌套弹层的遮罩——下面那张列表一点没被压暗，
 * 确认框读起来像是掉进列表里的又一张白卡片（两张 `bg-popover` 互量正好
 * 1.00:1）。所以 `ui/kit/popups.tsx` 的遮罩改成 `forceRender` 并夹到
 * `ALERT_DIALOG_BACKDROP_DIM`。暗色下这半还是不够：两张卡片都是
 * `oklch(0.205)`，黑纱再厚也压不出差，靠的是卡片自己那圈
 * `ALERT_DIALOG_RAISED` 的边——所以这里取「底色差」与「边线差」里大的那个。
 * jsdom 不套样式表，这些数只有真浏览器给得出。
 */
const deleteActionContrast = (theme: 'light' | 'dark'): Story => ({
  ...DisplayManageViews,
  args: { ...DisplayManageViews.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-surface"]'),
      ).toHaveAttribute('data-theme', theme),
    );
    const row = await openManager(canvas, '待出库订单');
    const manager = within(document.body).getByRole('dialog');

    await userEvent.click(
      within(row).getByRole('button', { name: zhCN['label.manage.delete'] }),
    );
    const dialog = await deleteDialog();

    // Named, so the question survives covering the row it is about.
    await expect(within(dialog).getByRole('heading').textContent).toBe(
      formatMessage(zhCN, 'label.delete.confirm', { title: '待出库订单' }),
    );

    const confirm = within(dialog).getByRole('button', {
      name: zhCN['label.manage.delete'],
    });
    await settled(() => getComputedStyle(confirm).backgroundColor);
    const { ratio, colors } = measureTextContrast(confirm);
    await expect(
      ratio,
      `${theme} — ${colors.text} on ${colors.background}`,
    ).toBeGreaterThanOrEqual(TEXT_CONTRAST);

    // And the list under it reads as being *under* it: two `bg-popover`
    // cards on their own measure 1.00:1 against each other, which is what
    // "a white card dropped into the list" is as a number.
    const backdrop = document.querySelector<HTMLElement>(
      '[data-slot="alert-dialog-overlay"]',
    )!;
    const apart = measureLayerSeparation(dialog, backdrop, manager);
    await expect(
      apart.ratio,
      `${theme} — fill ${apart.colors.front} ${apart.onFill.toFixed(2)}:1, ring ${apart.colors.ring} ${apart.onRing.toFixed(2)}:1, over ${apart.colors.behind}`,
    ).toBeGreaterThanOrEqual(STACKED_DIALOG_SEPARATION);

    // Called off rather than carried out: this story measures, it does not
    // delete, and the row is left where the next play expects it.
    await userEvent.click(
      within(dialog).getByRole('button', { name: zhCN['label.delete.keep'] }),
    );
  },
});

/**
 * 叠起来的两张卡片之间的下限：3:1，按 1.4.11 对非文字内容那一档读——这圈边
 * 界是"这是一个盖住下面那块的问题"唯一的视觉凭据。
 */
const STACKED_DIALOG_SEPARATION = 3;

export const DeleteActionContrastInLightTheme: Story =
  deleteActionContrast('light');
export const DeleteActionContrastInDarkTheme: Story =
  deleteActionContrast('dark');

/**
 * The nearest ancestor that would trap this element in a stacking context of
 * its own, or `null` when it ranks against the page directly.
 *
 * Not every property that makes one is listed — this is the handful a story
 * frame or a Storybook wrapper plausibly sets, which is all it has to catch
 * to keep the premise above honest.
 */
function trappedIn(element: HTMLElement): HTMLElement | null {
  for (
    let parent = element.parentElement;
    parent && parent !== element.ownerDocument.documentElement;
    parent = parent.parentElement
  ) {
    const style = getComputedStyle(parent);
    if (
      (style.position !== 'static' && style.zIndex !== 'auto') ||
      style.transform !== 'none' ||
      style.filter !== 'none' ||
      style.perspective !== 'none' ||
      style.isolation === 'isolate' ||
      style.mixBlendMode !== 'normal' ||
      style.contain
        .split(' ')
        .some(
          part =>
            part === 'paint' ||
            part === 'layout' ||
            part === 'strict' ||
            part === 'content',
        ) ||
      Number.parseFloat(style.opacity) < 1
    )
      return parent;
  }
  return null;
}

/**
 * 这块面的动效让给 `prefers-reduced-motion: reduce`，而会说话的那两种动画不让。
 *
 * 屏幕上会动的东西没有一件是调用处写的：弹层由 vendored 组件带着
 * `data-open:animate-in zoom-in-95 slide-in-from-top-2` 进场，对话框带着遮罩
 * 淡入，按钮、徽章与行普遍带 `transition-all`——评审当时量到菜单弹层
 * `animation-name: enter`、`animation-duration: 0.1s`，中途 `transform` 缩在
 * 0.986、`opacity` 0.727。所以让步只能在主题里做一次（`styles.css`，与两处
 * vendored 字号钉在同一个地方、同一个理由），而不是去改三百处 class。
 *
 * **reduce 不等于 remove**：spinner 说的是「还在写／还在查」，skeleton 的脉动
 * 说的是「屏幕上这些还不是数据」；停掉它们是把「进行中」画成「卡住了」，所以
 * 这两个 slot 被排除在外。
 *
 * 媒体查询本身在故事里开不动（Playwright 的 `reducedMotion` 只在测试进程里有，
 * 浏览器矩阵也不是每种都给得出），所以这里量的是**规则本身**：它在不在、作用
 * 域有没有同时点到两个边界、屏幕上真实的弹层匹不匹配它、被排除的那两个 slot
 * 匹不匹配，以及它此刻要削掉的是多长的一段动画。
 */
/** A selector list split at its top-level commas, not inside `:is()`. */
function branches(selectors: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let at = 0; at < selectors.length; at += 1) {
    const char = selectors[at];
    if (char === '(') depth += 1;
    else if (char === ')') depth -= 1;
    else if (char === ',' && depth === 0) {
      parts.push(selectors.slice(start, at));
      start = at + 1;
    }
  }
  return [...parts, selectors.slice(start)];
}

export const ReducedMotionIsHonoured: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const surface = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;

    // 本包发的那一条：条件是 reduce，作用域点到两个边界。宿主页面与 vendored
    // 的 `.shimmer` 各有各的一条，按边界认出自己这条。
    const reduced = [...doc.styleSheets]
      .flatMap(sheet => {
        try {
          return [...sheet.cssRules];
        } catch {
          return [];
        }
      })
      .filter(
        (rule): rule is CSSMediaRule =>
          rule instanceof CSSMediaRule &&
          rule.conditionText.includes('prefers-reduced-motion'),
      )
      .flatMap(media => [...media.cssRules])
      .filter(
        (rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule &&
          // 两个边界**本身**各是它的一个选择器分支，而不是某个类恰好落在边界
          // 里——vendored 的 `.shimmer` 也有一条 reduce 规则，作用域同样点到
          // 两个边界，说的却只是它自己那一个类。每个分支都带着构建加的作用域
          // （`:where(…)`，D66），比的是它前面的部分。
          ['.fve-root', '.fve-tokens'].every(boundary =>
            branches(rule.selectorText).some(
              part => part.split(':where(')[0].trim() === boundary,
            ),
          ),
      );
    // 一条，或者同一条被加载了不止一次（dev 的 HMR 与测试进程各挂一份），所以
    // 数的是「有」而不是「恰好一份」，而每一份都得说同一件事。
    await expect(reduced.length, '包级的 reduce 规则').toBeGreaterThan(0);
    const rule = reduced[0];

    // 削到察觉不到，而不是削到零：Base UI 的弹层靠自己退场动画结束的那一下
    // 卸载，时长拿掉就没有那一下了。
    const declared = (one: CSSStyleRule, property: string) => [
      one.style.getPropertyValue(property),
      one.style.getPropertyPriority(property),
    ];
    for (const one of reduced) {
      await expect(declared(one, 'animation-duration')).toEqual([
        '0.01ms',
        'important',
      ]);
      await expect(declared(one, 'transition-duration')).toEqual([
        '0.01ms',
        'important',
      ]);
    }

    // 真实的弹层：它匹配这条规则，而它此刻的动画正是规则要削的那 100ms。
    await userEvent.click(
      canvasElement.querySelector<HTMLElement>(
        '[data-slot="refresh-interval"]',
      )!,
    );
    const menu = await within(doc.body).findByRole('menu');
    const popup = menu.closest<HTMLElement>(
      '[data-slot="dropdown-menu-content"]',
    )!;
    await expect(popup.matches(rule.selectorText)).toBe(true);
    await expect(getComputedStyle(popup).animationDuration).toBe('0.1s');
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(doc.body).queryByRole('menu')).toBeNull(),
    );

    // 会说话的那两种不让。这一屏已经查完、也没在写，spinner 与 skeleton 都不
    // 在场（它们分别是 `RefreshControl` 与 `ViewList` 的在途状态），所以问的是
    // 选择器本身：探针戴上上游给它们的 slot，放进这块面里问一句再拿走。
    const excludes = (slot: string) => {
      const probe = doc.createElement('div');
      probe.dataset.slot = slot;
      surface.append(probe);
      const matched = probe.matches(rule.selectorText);
      probe.remove();
      return matched;
    };
    await expect({
      spinner: excludes('spinner'),
      skeleton: excludes('skeleton'),
      badge: excludes('badge'),
    }).toEqual({ spinner: false, skeleton: false, badge: true });
  },
};

/**
 * 没有底色的徽章靠边活着，所以那圈边是 `--input`。
 *
 * 主题把两个 token 分在这条线上：`--border` 是**东西之间**的线，可以淡；
 * `--input` 是**边就是那个东西**的那一档，两个主题都钉在 ≥3:1，因为一个没勾
 * 的复选框除了那圈边什么都没有。没有底色的徽章是同一种情形——把边拿掉就没有
 * 徽章了，只剩一个词——而 registry 给它的是 `border-border`：量在标题栏上是
 * **1.26:1**（暗色 1.77:1）。表内那几枚有语气的徽章早就换过了
 * （`ui/kit/variants.tsx` 的 `neutral`），这一条是剩下的那些。
 *
 * 钉的那一枚正是最难够着的一枚：「已修改」这颗徽章上的 `data-slot` 被调用处
 * 写成了 `view-unsaved`（`useRender` 的 state 拗不过调用处的 prop），所以
 * `styles.css` 里那条规则问的是 `group/badge` 与 `data-variant`——两样都是
 * registry 自己写在每一枚徽章上的。
 */
const outlineBadgeEdges = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithData,
  args: { ...DisplayWithData.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    // 改一下，好让「已修改」那枚徽章上台。
    await userEvent.click(canvas.getAllByRole('button', { name: /订单号/ })[0]);
    const mark = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="view-unsaved"]',
      );
      if (!found) throw new Error('the "edited" mark did not appear');
      return found;
    });

    // registry 自己的两样东西：徽章的组名与它的 variant。
    await expect(mark.getAttribute('data-variant')).toBe('outline');
    await expect(mark.getAttribute('class')).toContain('fve:group/badge');

    const { ratio, colors } = measureBorderContrast(mark);
    await expect(
      ratio,
      `${theme} — ${colors.border} on ${colors.surface}`,
    ).toBeGreaterThanOrEqual(3);
  },
});

export const OutlineBadgeEdgesInLightTheme: Story = outlineBadgeEdges('light');
export const OutlineBadgeEdgesInDarkTheme: Story = outlineBadgeEdges('dark');

/**
 * 工具栏上以文字或图标自明的按钮（列设置、排序、导出）穿主题的 `control` 与
 * `control-edge`，和看板的筛选胶囊、分段控件同一组（D59 第 7 项，D43 留给下一
 * 步的一项）：porcelain 填灰、没有边，像 macOS 的工具栏；neutral 不设这一组，
 * 仍是注册表的描边按钮——白底、`border` 的边，像素不变。量的是按钮在它自己的
 * 层叠里解析出的颜色，和同一处放一块探针量出的 token 比。
 */
const toolbarButtonsIn = (preset: 'porcelain' | 'neutral'): Story => ({
  ...DisplayWithData,
  globals: { fvePreset: preset },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const columns = canvas.getByRole('button', {
      name: zhCN['label.toolbar.columns'],
    });
    const toolbar = columns.closest<HTMLElement>('[role="toolbar"]')!;
    const token = (fill: string, edge: string) => {
      const probe = toolbar.appendChild(document.createElement('div'));
      probe.style.backgroundColor = fill;
      probe.style.borderTopColor = edge;
      const style = getComputedStyle(probe);
      const read = { fill: style.backgroundColor, edge: style.borderTopColor };
      probe.remove();
      return read;
    };
    const want =
      preset === 'porcelain'
        ? token('var(--_fve-control)', 'var(--_fve-control-edge)')
        : token('var(--background)', 'var(--border)');
    await waitFor(() => {
      const style = getComputedStyle(columns);
      expect({
        fill: style.backgroundColor,
        edge: style.borderTopColor,
      }).toEqual(want);
    });
    if (preset === 'porcelain') {
      // Filled, and its edge kept (D63).
      await expect(want.fill).not.toBe(token('var(--background)', '').fill);
      await expect(want.edge).not.toMatch(/\/ 0\)$|, 0\)$/);
    }
  },
});

export const ToolbarButtonsFillInPorcelain: Story =
  toolbarButtonsIn('porcelain');
export const ToolbarButtonsOutlinedInNeutral: Story =
  toolbarButtonsIn('neutral');
