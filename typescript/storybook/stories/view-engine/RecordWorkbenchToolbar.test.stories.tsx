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
  AutoRefresh as DisplayAutoRefresh,
  WithData as DisplayWithData,
} from './RecordWorkbench.stories.js';
import { inFrontOf, say, tabTo, tooltipOn } from './recordWorkbenchTest.js';

/**
 * The result toolbar: the keyboard, auto refresh, icon buttons and their
 * tooltips. One of the record workbench's regression files, split by concern;
 * they all share one title, so every story keeps its id, and the helpers more
 * than one of them needs are in `recordWorkbenchTest.ts`.
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
 * 键盘上的两件事，只有真浏览器答得出来：原生按钮被 Enter／空格激活是浏览器的
 * 默认动作，漫游焦点是 Base UI 在真实 keydown 上做的事。
 *
 * 一、**结果工具栏是一条 toolbar**：整条栏只有一个 Tab 站，方向键在栏内左右
 * 走并在两端回绕，走过去只移动焦点——布局切换的档位不会被走成按下；离开这条
 * 栏的 Tab 直接落到表上。二、**折叠带的开关是 `CollapsibleTrigger`**：Enter
 * 开、空格关，`aria-expanded` 跟着翻，`aria-controls` 只在带子在页面上时存在；
 * 按开之后键盘落进带子里。
 */
export const ToolbarAndFoldByKeyboard: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // The fold, from the handle in the title bar. A saved view opens folded.
    const toggle = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).not.toHaveAttribute('aria-controls');
    await tabTo(toggle);
    await userEvent.keyboard('{Enter}');
    const band = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="editor-band"]',
      );
      if (!found) throw new Error('the band did not open');
      return found;
    });
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(toggle).toHaveAttribute('aria-controls', band.id);
    // A press takes the keyboard into what it opened (the 2026-09-23 audit,
    // P2-13): the band's first control, not Refresh and Fill on the way.
    await waitFor(() =>
      expect(band.contains(document.activeElement)).toBe(true),
    );
    // Back on the handle, Space closes it again, and the panel leaves the
    // page with it — so the reference the handle was making leaves too.
    toggle.focus();
    await userEvent.keyboard(' ');
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="editor-band"]'),
      ).toBeNull(),
    );
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(toggle).not.toHaveAttribute('aria-controls');

    // The bar: one stop, the arrows inside it.
    const bar = canvas.getByRole('toolbar', {
      name: zhCN['label.toolbar.title'],
    });
    await expect(bar).toHaveAttribute('aria-orientation', 'horizontal');
    const controls = [...bar.querySelectorAll('button')];
    await expect(
      controls.filter(control => control.tabIndex === 0),
    ).toHaveLength(1);

    await tabTo(controls[0]!);
    for (let at = 1; at < controls.length; at += 1) {
      await userEvent.keyboard('{ArrowRight}');
      await expect(document.activeElement).toBe(controls[at]);
    }
    // Both ends wrap.
    await userEvent.keyboard('{ArrowRight}');
    await expect(document.activeElement).toBe(controls[0]);
    await userEvent.keyboard('{ArrowLeft}');
    await expect(document.activeElement).toBe(controls[controls.length - 1]);

    // Walking moves focus and nothing else: the layout switch is still on
    // the layout it was on, and the rows are still a table.
    await expect(
      canvas.getByRole('button', { name: zhCN['label.layout.table'] }),
    ).toHaveAttribute('aria-pressed', 'true');
    await expect(canvas.getByRole('table')).toBeInTheDocument();

    // Tab leaves the whole bar rather than stepping to the next control in
    // it, and comes back to the one the bar was left on.
    await userEvent.tab();
    await expect(bar.contains(document.activeElement)).toBe(false);
    await userEvent.tab({ shift: true });
    await expect(document.activeElement).toBe(controls[controls.length - 1]);

    // An item of the bar is still the popup's trigger.
    await tabTo(
      canvas.getByRole('button', { name: zhCN['label.toolbar.columns'] }),
    );
    await userEvent.keyboard('{Enter}');
    await within(document.body).findByText(zhCN['label.columns.title']);
    // One live region per surface, however many lists the popup holds: the
    // workbench's own sits in the result block and reads its queries back,
    // and the popover carries one for the arrow keys and the pin toggles
    // inside it. They answer different presses — a pinned column says where
    // it landed, the query that press started says it is running — and a
    // polite region queues rather than interrupts, so the two never read
    // over each other. `@dnd-kit` keeps a third beside them
    // (`#dnd-kit-announcement-*`) for what the library itself drives.
    const regions = (root: ParentNode) =>
      root.querySelectorAll('[aria-live]:not([id^="dnd-kit"])');
    await expect(regions(canvasElement)).toHaveLength(1);
    const popup = await waitFor(() => {
      const found = document.body.querySelector(
        '[data-slot="popover-content"]',
      );
      if (!found) throw new Error('the popover did not open');
      return found;
    });
    await expect(regions(popup)).toHaveLength(1);
    // Closed again before the story settles, as every play that opens a
    // popup does: axe judges the page as the play leaves it. Twice, because
    // a control reached by the keyboard is showing its own tooltip and that
    // is the top layer — the first Escape is the tooltip's.
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="tooltip-content"]'),
      ).toBeNull(),
    );
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(
        document.body.querySelector('[data-slot="popover-content"]'),
      ).toBeNull(),
    );
  },
};

/**
 * The way in to auto refresh: the `▾` beside the refresh button.
 *
 * jsdom can say what the menu holds; only a browser can say that it opens in
 * front of the workbench and that a click on a rung lands on the rung — a
 * control added to a toolbar opens a popup portalled out of it, and where
 * that popup paints is decided by the whole page (see
 * `PopupsOverRaisedHostLayer` below).
 */
export const AutoRefresh: Story = {
  ...DisplayAutoRefresh,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const cadence = () =>
      canvasElement.querySelector<HTMLElement>('[data-slot="refresh-cadence"]');
    const seconds = say('label.refresh.seconds', { count: 30 });
    /** The number on the key, whatever second of the count it is. */
    const count = () => Number(cadence()?.textContent?.replace(/\D/g, ''));

    // The saved view already refreshes itself, so the credential is on the
    // button before anything is pressed — and it is counting down to the
    // next refresh rather than repeating the cadence the menu holds.
    await waitFor(() => expect(count()).toBeGreaterThan(0));
    const started = count();
    await expect(started).toBeLessThanOrEqual(30);
    // Real seconds, ticking: this is the one thing jsdom cannot show, since
    // a second there is whatever the test says it is.
    await waitFor(() => expect(count()).toBeLessThan(started), {
      timeout: 4_000,
    });
    // The sentence a screen reader gets is the **cadence**, not the count:
    // a number that changes every second must not be read out every second,
    // which is why the count itself is `aria-hidden`.
    await expect(
      canvasElement.querySelector('[data-slot="refresh-now"]'),
    ).toHaveAccessibleDescription(
      say('label.refresh.on', { interval: seconds }),
    );
    await expect(
      canvasElement.querySelector('[data-slot="refresh-countdown"]'),
    ).toHaveAttribute('aria-hidden', 'true');
    // The box is as wide as the widest reading this interval can produce, so
    // "10s" → "9s" never walks the `▾` beside it across the bar.
    const box = canvasElement.querySelector<HTMLElement>(
      '[data-slot="refresh-countdown"]',
    )!;
    const width = box.getBoundingClientRect().width;
    await waitFor(() => expect(count()).toBeLessThan(started - 1), {
      timeout: 4_000,
    });
    await expect(box.getBoundingClientRect().width).toBe(width);

    const chevron = canvasElement.querySelector<HTMLElement>(
      '[data-slot="refresh-interval"]',
    )!;
    await userEvent.click(chevron);
    const menu = await within(document.body).findByRole('menu');
    // Portalled out of the surface, and still the thing a click at its
    // middle reaches.
    await expect(menu.closest('[data-slot="view-surface"]')).toBeNull();
    await expect(inFrontOf(menu)).toBe(true);

    // Off, then the ladder the limits admit — nothing disabled, because an
    // interval the kernel would refuse is not offered at all.
    await expect(
      within(menu)
        .getAllByRole('menuitemradio')
        .map(item => item.textContent),
    ).toEqual([zhCN['label.refresh.off'], ...LADDER.map(cadenceOf)]);
    // The one in force is the one marked.
    await expect(
      within(menu).getByRole('menuitemradio', { name: seconds }),
    ).toHaveAttribute('aria-checked', 'true');

    // Choosing edits the view's own config and applies it, so the cadence
    // moves and the title bar says the view is now unsaved.
    const minutes = say('label.refresh.minutes', { count: 5 });
    await userEvent.click(
      within(menu).getByRole('menuitemradio', { name: minutes }),
    );
    // Counting down from the new interval — the fifth minute reads "4 min"
    // for all but its first second, so either is the right answer here.
    await waitFor(() =>
      expect([minutes, say('label.refresh.minutes', { count: 4 })]).toContain(
        cadence()?.textContent,
      ),
    );
    await expect(canvas.getByText(zhCN['label.header.unsaved'])).toBeVisible();

    // And off again, from the keyboard: the chevron opens on Enter and hands
    // focus to the options.
    chevron.focus();
    await userEvent.keyboard('{Enter}');
    const reopened = await within(document.body).findByRole('menu');
    await userEvent.click(
      within(reopened).getByRole('menuitemradio', {
        name: zhCN['label.refresh.off'],
      }),
    );
    await waitFor(() => expect(cadence()).toBeNull());
  },
};

/** The intervals the default limits admit, as the menu lists them. */
const LADDER = [30, 60, 300];

/** One interval as the control writes it: "30s", "5 min", "1 h". */
const cadenceOf = (seconds: number): string =>
  seconds >= 3600
    ? say('label.refresh.hours', { count: seconds / 3600 })
    : seconds >= 60
      ? say('label.refresh.minutes', { count: seconds / 60 })
      : say('label.refresh.seconds', { count: seconds });

/**
 * D12 puts every function into an icon button, so hovering one is how a
 * pointer learns what it is. The name the reader hears and the label the
 * pointer sees are one string (`src/ui/kit/IconButton.tsx`), and this asks the
 * question a jsdom suite cannot: is it actually on screen, and does it say
 * what the button says *now* rather than what it said before it was pressed?
 */
export const IconButtonsSayTheirNameOnHover: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;

    const toggle = canvas.getByRole('button', {
      name: zhCN['label.workbench.expand-view'],
    });
    await userEvent.hover(toggle);
    await waitFor(() =>
      // One message, two channels: the tooltip is the accessible name said
      // out loud to a pointer, never a second wording of it.
      expect(tooltipOn(doc)?.textContent).toBe(
        toggle.getAttribute('aria-label'),
      ),
    );
    await expect(tooltipOn(doc)).toHaveTextContent(
      zhCN['label.workbench.expand-view'],
    );

    // The label follows the state. It is the same button — what pressing it
    // does has changed, so what it is called changes with it.
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'true'),
    );
    await userEvent.unhover(toggle);
    await waitFor(() => expect(tooltipOn(doc)).toBeNull());
    await userEvent.hover(toggle);
    await waitFor(() =>
      expect(tooltipOn(doc)?.textContent).toBe(
        zhCN['label.workbench.collapse-view'],
      ),
    );

    // Back out the way it came, so the story leaves the screen as it found
    // it — `FillTheScreen` is where the Escape route is held to account.
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-expanded', 'false'),
    );
  },
};

/** Whether two boxes share any of the screen. */
function overlapping(one: HTMLElement, other: HTMLElement): boolean {
  const a = one.getBoundingClientRect();
  const b = other.getBoundingClientRect();
  return (
    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
  );
}

/**
 * A tooltip round a menu's trigger must not fight the menu.
 *
 * The two hang off one button and the pointer that opened the menu is still
 * sitting on it, which is the one arrangement where a tooltip can end up
 * over the thing it was meant to explain. What is held here is the part the
 * user can see: whether the label is still up or not, every option is the
 * thing a click at its middle reaches, and nothing black is lying over the
 * list. Whether Base UI keeps the tooltip shut after the click or lets the
 * resting pointer bring it back is its own business, and it does both
 * depending on how the press is timed — the label sits above the button,
 * where the menu is not, so neither way costs the user anything.
 */
export const AMenuIsNotCoveredByItsOwnTooltip: Story = {
  ...DisplayWithData,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const doc = canvasElement.ownerDocument;
    const body = within(doc.body);
    const chevron = canvasElement.querySelector<HTMLElement>(
      '[data-slot="refresh-interval"]',
    )!;

    await userEvent.hover(chevron);
    await waitFor(() =>
      expect(tooltipOn(doc)).toHaveTextContent(zhCN['label.refresh.auto']),
    );

    await userEvent.click(chevron);
    const menu = await body.findByRole('menu');
    await userEvent.hover(chevron);
    // A beat for the tooltip to do whatever it is going to do: with the
    // provider's zero delay, anything it has in mind has happened by now.
    await new Promise(settle => setTimeout(settle, 200));

    const tip = tooltipOn(doc);
    if (tip) await expect(overlapping(tip, menu)).toBe(false);
    for (const item of within(menu).getAllByRole('menuitemradio'))
      await expect(inFrontOf(item)).toBe(true);

    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(body.queryByRole('menu')).toBeNull());
  },
};
