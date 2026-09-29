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
  CollapsedSidebar as DisplayCollapsedSidebar,
  NarrowTitleBar as DisplayNarrowTitleBar,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { listItem, tooltipOn } from './recordWorkbenchTest.js';

/**
 * The view list beside the workbench: its colours, how it folds, and its rows.
 * One of the record workbench's regression files, split by concern; they all
 * share one title, so every story keeps its id, and the helpers more than one
 * of them needs are in `recordWorkbenchTest.ts`.
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

/** What one element is actually painted, as the browser resolved it. */
const paintOf = (node: Element) => getComputedStyle(node).backgroundColor;

/**
 * The sidebar is a navigation column, and the three states on it are three
 * colours.
 *
 * This is the measurement the design could not be argued into: four states
 * used to share one 3% grey — a hovered row, the open row, a selected table
 * row and a pressed segment — so the list had no "you are here" at all, and
 * the column itself was the same white as the work area beside it. The
 * ratios are tiny on purpose; what is asserted is that they are not *one*,
 * which is what a token collapse looks like from here.
 *
 * The bar down the open row's leading edge is the other half: a mark of a
 * different kind, which no theming can turn into the fill beside it.
 */
export const SidebarIsANavigationColumn: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const column = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list"]',
    )!;
    // The work area paints nothing of its own: what shows through `main` is
    // the surface's `--background`, which is the colour to compare against.
    const work = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-surface"]',
    )!;
    const ground = paintOf(column);

    // The column has a ground of its own, and the work area is not it.
    await expect(ground).not.toBe('rgba(0, 0, 0, 0)');
    await expect(paintOf(work)).not.toBe('rgba(0, 0, 0, 0)');
    await expect(ground).not.toBe(paintOf(work));
    // And an edge between the two, drawn once.
    await expect(
      parseFloat(getComputedStyle(column).borderRightWidth),
    ).toBeGreaterThan(0);

    // Two columns, two heads, one line under both. The sidebar's header is
    // ruled off at exactly the height the title bar is, so the screen reads
    // as one page in two columns rather than as two pages side by side.
    const listHead = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-list-header"]',
    )!;
    const titleBar = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-header-block"]',
    )!;
    await expect(
      parseFloat(getComputedStyle(listHead).borderBottomWidth),
    ).toBeGreaterThan(0);
    await expect(
      Math.abs(
        listHead.getBoundingClientRect().bottom -
          titleBar.getBoundingClientRect().bottom,
      ),
    ).toBeLessThanOrEqual(1);

    // The open view has a ground of its own, set off the column's: the
    // preset says which — a sheet of the work area's ground lifted off it
    // with an edge and a shadow (the engine's own), or a filled bar a step
    // deeper than a hovered item (porcelain, D59). Never a bar down one edge
    // (an inset bar bent round the corners into a "("), and an edge, where
    // there is one, goes all the way round.
    const current = listItem(canvasElement, '待出库订单');
    await expect(current.getAttribute('aria-current')).toBe('true');
    await expect(paintOf(current)).not.toBe('rgba(0, 0, 0, 0)');
    await expect(paintOf(current)).not.toBe(ground);
    const sheet = getComputedStyle(current);
    await expect(sheet.boxShadow).not.toContain('inset');
    await expect(sheet.borderLeftColor).toBe(sheet.borderRightColor);
    await expect(getComputedStyle(current).fontWeight).toBe('500');

    // A row that is not open carries no fill of its own, so what shows is
    // the column. Hovering it is a third colour: distinguishable from the
    // ground it sits on *and* from the open row beside it, which is the pair
    // that used to be identical.
    const other = listItem(canvasElement, '我盯的大额单');
    await expect(paintOf(other)).toBe('rgba(0, 0, 0, 0)');
    await expect(other.className).toContain('fve:hover:bg-sidebar-accent');
    // Painted rather than hovered: the runner's pointer events do not put a
    // real `:hover` on the element, and what broke before was never the
    // pseudo-class — it was the two tokens resolving to one grey. So the
    // token is put on the page the way the hover would put it, in the
    // column's own cascade, and read back in the same form as the rest.
    const hovered = await waitFor(() => {
      const probe = column.appendChild(document.createElement('div'));
      probe.style.backgroundColor = 'var(--sidebar-accent)';
      const painted = paintOf(probe);
      probe.remove();
      return painted;
    });
    await expect(hovered).not.toBe(ground);
    await expect(hovered).not.toBe(paintOf(current));

    // The kind icon is on every row, because one definition holds record and
    // analysis views together and the name alone does not say which is which.
    await expect(other.querySelector('svg')).not.toBeNull();
    // And where a view came from is a lock at the row's end, beside where
    // the star goes — a mark, not a badge.
    const system = listItem(canvasElement, '全部订单');
    await expect(
      system.querySelector('[data-slot="view-system-tag"]'),
    ).not.toBeNull();
    await expect(system.querySelector('[data-slot="badge"]')).toBeNull();
  },
};

/**
 * The fold the shell decides for itself, and the one filling the screen asks
 * for.
 *
 * A 375px column has no room for a 224px list beside it — below `md` the
 * list is not beside the view at all but stacked over it, and the table
 * started 204px down. Filling the screen is the same argument made by the
 * user: the gesture is about the rows, and navigation is the first thing
 * that is not the rows.
 */
export const TheListFoldsItselfAwayWhereItCannotFit: Story = {
  ...DisplayNarrowTitleBar,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;

    // Nobody passed `defaultSidebarOpen`: the shell measured the column it
    // was given and folded the list for it.
    await expect(host.getBoundingClientRect().width).toBe(375);
    await expect(sidebar()).toBeNull();
    // Which is what the fold buys: the rows start at the top of the column
    // rather than under 204px of navigation.
    await expect(
      table.getBoundingClientRect().top -
        canvasElement
          .querySelector('[data-slot="view-surface"]')!
          .getBoundingClientRect().top,
    ).toBeLessThan(260);

    // The list is still reachable, as one control in the title bar.
    const switcher = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-switcher"]',
    )!;
    await expect(switcher).not.toBeNull();
    // Sized to what it says and reading from its beginning — not a 470px
    // pill with a short name floating in the middle of it.
    await expect(getComputedStyle(switcher).justifyContent).toBe('flex-start');
    const label = switcher.querySelector<HTMLElement>('span')!;
    await expect(
      switcher.getBoundingClientRect().right -
        label.getBoundingClientRect().right,
    ).toBeLessThan(40);
    // And the right-hand group still ends the bar, on whichever line it is.
    const header = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-header"]',
    )!;
    const controls = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    await expect(
      Math.abs(
        controls.getBoundingClientRect().right -
          header.getBoundingClientRect().right,
      ),
    ).toBeLessThanOrEqual(1);
  },
};

/**
 * The same rule after arrival: the fold follows the column it is given, in
 * both directions, and stops following once the user has answered for
 * themselves.
 *
 * jsdom lays nothing out, so the unit test drives a fake observer over a
 * faked width; this is the one place a real `ResizeObserver` on a real box
 * is weighed. The width is the host's, as a host's is — a split pane dragged
 * narrower, a panel opened beside the page, a window resized — and the
 * workbench is told nothing but the box it ends up in.
 */
export const TheListFollowsTheColumnItIsGiven: Story = {
  ...DisplayNarrowTitleBar,
  // Wide enough to open with the list beside the view; the play is what
  // takes the room away and gives it back.
  args: { ...DisplayNarrowTitleBar.args, narrowWidth: 1000 },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    const resizeTo = async (width: number) => {
      host.style.width = `${width}px`;
      await expect(host.getBoundingClientRect().width).toBe(width);
      // A `ResizeObserver` reports on the frame after the box changed, so
      // the answer is never the one on screen at this instant.
      await new Promise(settle => setTimeout(settle, 200));
    };

    await expect(host.getBoundingClientRect().width).toBe(1000);
    await expect(sidebar()).not.toBeNull();

    // Dragged below `md`: the list would no longer be *beside* the view but
    // stacked over it, which is the whole reason a narrow column folds.
    await resizeTo(608);
    await expect(sidebar()).toBeNull();

    // And back, because the room it was folded for is there again.
    await resizeTo(1000);
    await expect(sidebar()).not.toBeNull();

    // Then the user answers, and the measurement stops answering: folded by
    // hand in a column with room to spare, it stays folded through a trip
    // down to 608 and back. Undoing that under their hands, once per drag,
    // is worse than a list folded where it would have fitted.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.collapse-sidebar'],
      }),
    );
    await expect(sidebar()).toBeNull();
    await resizeTo(608);
    await resizeTo(1000);
    await expect(sidebar()).toBeNull();
  },
};

/**
 * Filling the screen gives the rows the room, and the list is the first
 * thing that is not the rows.
 */
export const FillingTheScreenFoldsTheList: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    await expect(sidebar()).not.toBeNull();

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-view'],
      }),
    );
    await waitFor(() => expect(sidebar()).toBeNull());
    // Not lost, only folded: the switcher is the list while it is away.
    await expect(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    ).toBeVisible();

    // And leaving reads the page's own answer again.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.collapse-view'],
      }),
    );
    await waitFor(() => expect(sidebar()).not.toBeNull());
  },
};

/**
 * The sidebar folded away, and the list still reachable.
 *
 * Folding takes the one control that opens another view off the screen, so
 * the title bar has to grow its replacement in the same gesture: the way
 * back, the definition's name, and the list as one dropdown. This walks the
 * whole round trip — fold, switch, unfold — because the failure worth
 * catching is the one where a user folds the list and cannot get back to it.
 */
export const CollapseAndSwitch: Story = {
  ...DisplayCollapsedSidebar,
  // Starts open on purpose: the fold itself is half of what is asserted.
  args: { ...DisplayCollapsedSidebar.args, collapsed: false },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const sidebar = () =>
      canvasElement.querySelector('[data-slot="view-sidebar"]');
    await expect(sidebar()).not.toBeNull();

    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.collapse-sidebar'],
      }),
    );
    await expect(sidebar()).toBeNull();

    // What the sidebar was carrying is now in the title bar, in one group
    // with the commands that save the view.
    const identity = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-identity"]',
    )!;
    await expect(identity).toHaveTextContent('订单');
    await expect(
      within(identity).getByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    ).toBeVisible();
    // The save group moved left, next to the view's name: it changes the
    // config under that name, so it belongs to it rather than to the row's end.
    await expect(
      identity.querySelector('[data-slot="save-actions"]'),
    ).not.toBeNull();

    // The switcher opens the same views the sidebar listed, grouped the same
    // way, and choosing one opens it.
    await userEvent.click(
      within(identity).getByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    );
    const menu = await within(document.body).findByRole('menu');
    await expect(menu).toHaveTextContent(zhCN['label.scope.group.personal']);
    await expect(menu).toHaveTextContent(zhCN['label.scope.tag.system']);
    await userEvent.click(
      within(menu).getByRole('menuitemradio', { name: /我盯的大额单/ }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-title"]'),
      ).toHaveTextContent('我盯的大额单'),
    );

    // And back: the list returns, and the header gives up the switcher.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-sidebar'],
      }),
    );
    await expect(sidebar()).not.toBeNull();
    await expect(
      canvas.queryByRole('button', {
        name: zhCN['label.workbench.switch-view'],
      }),
    ).toBeNull();
    // Nothing left open: Base UI parks focus-guard sentinels beside an open
    // popup, and axe judges the page as the play leaves it.
    await waitFor(() =>
      expect(document.body.querySelector('[role="menu"]')).toBeNull(),
    );
  },
};

/**
 * 侧栏每一行的种类图标，指上去要说得出自己是什么（D-2）。
 *
 * 从前 `TooltipTrigger` 直接挂在那个 `<svg>` 上，而它在 `Button` 里——
 * `[&_svg]:pointer-events-none` 让这个 svg 根本收不到指针，标签永远打不开：
 * 源码里写着的名字，屏幕上谁也拿不到。现在挂在外面那层 `span` 上，指针落在
 * 图标上会穿到父元素，于是它就是触发器。jsdom 量不出这一条——`pointer-events`
 * 要真的做命中测试才算数——所以钉在浏览器里。
 */
export const AViewRowSaysItsKindOnHover: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;

    const row = listItem(canvasElement, '待出库订单');
    const kind = row.querySelector<HTMLElement>('[data-slot="view-kind"]')!;
    // The premise: the glyph itself still refuses the pointer, which is why
    // it cannot be the trigger and the wrapper is.
    await expect(
      getComputedStyle(kind.querySelector('svg')!).pointerEvents,
    ).toBe('none');
    await expect(getComputedStyle(kind).pointerEvents).not.toBe('none');

    await userEvent.hover(kind);
    await waitFor(() =>
      expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.kind.record']),
    );

    // And the row is still called by the view it opens, not by its kind:
    // a list where every name starts with the same two syllables is a list
    // that has stopped distinguishing its items.
    await expect(row).toHaveTextContent('待出库订单');
    await expect(row.textContent).not.toContain(zhCN['label.kind.record']);

    await userEvent.unhover(kind);
    await waitFor(() => expect(tooltipOn(doc)).toBeNull());
  },
};
