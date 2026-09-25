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
  WithActions as DisplayWithActions,
} from './RecordWorkbench.stories.js';

/**
 * How the title bar narrows.
 *
 * It lives in the browser project and nowhere else: what it asks is what
 * *width* a flex item resolved to and what the engine would hand a click at
 * a given point, and jsdom — where the rest of this package's UI suites run —
 * lays nothing out and hit-tests nothing. Every assertion below reads
 * `getBoundingClientRect()` or `elementFromPoint()`, so in jsdom they would
 * all be zeroes agreeing with each other.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/组件状态/记录工作台/标题栏/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread: Storybook writes a file's own
  // description into a `parameters` of its meta, which would replace the
  // display meta's — and with it the full-screen host application the
  // workbench is meant to be exercised in.
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/** Rectangle `a` lies inside rectangle `b`, give or take a rounding pixel. */
const inside = (a: DOMRect, b: DOMRect) =>
  a.left >= b.left - 1 && a.right <= b.right + 1;

/** The two boxes share a pixel — on one line or across two. */
const overlaps = (a: DOMRect, b: DOMRect) =>
  a.left < b.right - 1 &&
  b.left < a.right - 1 &&
  a.top < b.bottom - 1 &&
  b.top < a.bottom - 1;

const box = (element: Element) => element.getBoundingClientRect();

/**
 * Whether the browser would hand a click at this point to the save commands.
 *
 * The point is asked of the engine rather than reasoned about from the
 * rectangles, because that is the question the user asks with their finger:
 * a group painted over another still reports its own box, and the box was
 * never what decided who gets the tap.
 */
function saveOwns(save: HTMLElement, x: number, y: number): boolean {
  const found = save.ownerDocument.elementFromPoint(
    Math.round(x),
    Math.round(y),
  );
  return found !== null && found.closest('[data-slot="save-actions"]') !== null;
}

/**
 * The name on the bar right now.
 *
 * With the list beside it that is the heading; with the list folded away the
 * heading is `sr-only` and the name is the switcher's label. Two places, one
 * thing — and the floor is under whichever of them is on screen.
 */
function nameOn(host: HTMLElement): HTMLElement {
  const switcher = host.querySelector<HTMLElement>(
    '[data-slot="view-switcher"]',
  );
  return (
    switcher?.querySelector<HTMLElement>('span') ??
    host.querySelector<HTMLElement>('[data-slot="view-title"]')!
  );
}

/**
 * The floor the name keeps, in pixels, read off the element that keeps it.
 *
 * 6em rather than 96px: the two places the name can be are set at different
 * sizes, and the floor is a number of characters — enough of a name to tell
 * two views apart — not a number of pixels.
 */
const floorOf = (name: HTMLElement) =>
  6 * parseFloat(getComputedStyle(name).fontSize);

/** How wide that element is once the column is this wide. */
/**
 * The elements laid out as this group's flex items: its children, except
 * that a `display: contents` child stands aside and its own children take
 * its place, and a `display: none` child is not laid out at all.
 */
function items(group: Element): Element[] {
  return [...group.children].flatMap(child => {
    const display = getComputedStyle(child).display;
    if (display === 'none') return [];
    if (display === 'contents') return items(child);
    return [child];
  });
}

function widthAt(host: HTMLElement, width: number, element: Element): number {
  host.style.width = `${width}px`;
  return box(element).width;
}

/**
 * Walks the column down from `from` to `to` and reports every width at which
 * the bar stops telling the truth.
 *
 * The findings are collected rather than asserted one by one, so a failure
 * names the whole range that is broken instead of stopping at its first
 * pixel — "overlapping from 344px down" is a description of the bug, and
 * "344px" alone is a description of one screenshot.
 */
function walk(host: HTMLElement, from: number, to: number, step = 4): string[] {
  const found: string[] = [];
  for (let width = from; width >= to; width -= step) {
    host.style.width = `${width}px`;
    const header = host.querySelector<HTMLElement>(
      '[data-slot="view-header"]',
    )!;
    const identity = host.querySelector<HTMLElement>(
      '[data-slot="view-identity"]',
    )!;
    const controls = host.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    const save = host.querySelector<HTMLElement>('[data-slot="save-actions"]')!;
    // The hit tests below are in viewport coordinates, so the bar has to be
    // in the viewport for the engine to have anything to answer about.
    header.scrollIntoView({ block: 'center' });

    const say = (what: string) => found.push(`${width}px: ${what}`);

    // Which view this is, and how it is being looked at: two groups, and at
    // no width may one be painted over the other.
    if (overlaps(box(identity), box(controls)))
      say('the identity group and the view controls overlap');

    // A group that reports it fits while its contents do not is the whole of
    // the bug: the row above it believes the report and never wraps.
    // The folded group is `display: contents` — no box of its own, its
    // items are the group's — so what is walked is what has a box.
    for (const child of items(identity)) {
      if (!inside(box(child), box(identity)))
        say(`${child.getAttribute('data-slot') ?? child.tagName} spills out`);
    }
    if (!inside(box(identity), box(header)))
      say(
        `the identity group is ${Math.round(box(identity).width)}px in a ` +
          `${Math.round(box(header).width)}px bar`,
      );

    // The name is what gives, and it gives down to a floor. Without one it
    // gave to 40px — "待出…" — beside a 74px audience tag and 93px of save
    // commands: the one thing the bar exists to say was the one thing not
    // on it. The floor is also what makes the bar wrap instead of squeeze,
    // since `min-width` is what a flex item reports to the row above it.
    const name = nameOn(host);
    if (box(name).width < floorOf(name) - 1)
      say(`the name is down to ${Math.round(box(name).width)}px`);

    // The right-hand group ends the bar on whichever line it lands on. On
    // one line the identity group's `flex-1` does it; on two it needs
    // `ml-auto`, or the controls start hard left under the kind icon and
    // read as a second row of the identity group.
    if (
      inside(box(identity), box(header)) &&
      Math.abs(box(controls).right - box(header).right) > 1
    )
      say('the view controls do not end the bar');

    // Save is the command a narrow screen most needs and the one that was
    // lost: reachable by keyboard the whole time, and by nothing else.
    const command = box(save.querySelector('button')!);
    const middle = command.top + command.height / 2;
    if (!saveOwns(save, command.left + 1, middle))
      say('Save loses its left edge');
    if (!saveOwns(save, command.left + command.width / 2, middle))
      say('Save loses its middle');
    if (!saveOwns(save, command.right - 1, middle))
      say('Save loses its right edge');
  }
  return found;
}

/**
 * The bar in a column that keeps getting narrower, with a name too long for
 * any of those widths.
 *
 * On `main` the identity group carried `min-w-0`, which let it be squeezed
 * below what its own contents need — 96.8px of box around 206.9px of
 * content — so the row above it went on believing everything fitted and
 * never wrapped, and the save commands were painted over the view controls.
 * At a phone's width every point of Save answered "filter toggle": the one
 * command a narrow screen needs was reachable by keyboard and by nothing
 * else.
 *
 * Three sets of contents share that one line at different times, and each is
 * walked on its own: the view as it was saved, the same view once it has
 * been edited (which adds a badge and turns Save into a live command), and
 * the bar with the view list beside it rather than folded into a switcher.
 *
 * Each walk stops where the bar's own irreducible contents stop fitting the
 * column — icons, badges and the save commands, none of which can be made
 * smaller, since a truncated "Shared" is worse than an honest overflow.
 * Below that the bar is wider than the column it is in and is clipped there;
 * what it must never do, and what the walk is for, is fit by painting one of
 * its own groups over another.
 *
 * The walk runs on the Chinese catalogue like every other story here: its
 * widths are the fixture, and the Chinese set once reached a 520–528px window
 * where the row kept both groups on one line and squeezed the identity
 * group — closed when that group took an `auto` flex basis (D12 shell PR),
 * so the bar wraps instead of squeezing.
 */
export const NarrowColumn: Story = {
  ...DisplayNarrowTitleBar,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const host =
      canvasElement.querySelector<HTMLElement>('[data-narrow-host]')!;
    const title = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-title"]',
    )!;

    // As saved, with the list folded away: a phone, a side panel, a split
    // pane. 300px of column is below every phone there is.
    await expect(walk(host, 760, 300)).toEqual([]);

    // The switcher is what gives here, because with the list folded away it
    // is the switcher that carries the view's name.
    const switcher = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-switcher"]',
    )!;
    await expect(
      widthAt(host, 760, switcher),
      'switcher shrinks',
    ).toBeGreaterThan(widthAt(host, 320, switcher));

    // And it is a button, not the room around it: `grow` used to hand it the
    // whole collapsed group, which at 470px was a pill with a short name
    // centred in it. Sized to what it says, the label starts where the icon
    // ends and the group ends where the label does.
    host.style.width = '760px';
    await expect(getComputedStyle(switcher).justifyContent).toBe('flex-start');
    const label = switcher.querySelector<HTMLElement>('span')!;
    await expect(
      box(switcher).right - box(label).right,
      'trigger ends at its label',
    ).toBeLessThan(40);

    // The definition's title is measured against this bar, not the page it is
    // on: the window here is far wider than the old viewport `sm:`, and the
    // 360px column is what decides.
    const definition = canvasElement.querySelector<HTMLElement>(
      '[data-slot="definition-title"]',
    )!;
    host.style.width = '360px';
    await expect(getComputedStyle(definition).display).toBe('none');
    host.style.width = '760px';
    await expect(getComputedStyle(definition).display).not.toBe('none');

    // Edited, which is the state a narrow screen most needs the bar in: Save
    // is a live command rather than a disabled one, and the "Edited" badge
    // has joined the line, so the same walk is over wider contents — four
    // pixels' worth, which is why this rung stops at 364 and the one above
    // reaches 300. The badge is a word with no icon to fall back to, so it
    // is one of the irreducible things the bar would rather overflow than
    // clip.
    await userEvent.click(canvas.getAllByRole('button', { name: /订单号/ })[0]);
    await waitFor(() =>
      expect(
        canvas.getByRole('button', {
          name: zhCN['label.save.save'],
        }),
      ).toBeEnabled(),
    );
    await expect(walk(host, 760, 364)).toEqual([]);

    // And the same bar with the view list beside it, which is the third set
    // of contents: the heading is on the line, and the switcher is not. The
    // list takes 224px of the column before the bar sees any of it.
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-sidebar'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-sidebar"]'),
      ).not.toBeNull(),
    );
    await expect(walk(host, 1000, 600)).toEqual([]);

    // The name is what gives here too, and it is *clipped* rather than laid
    // out at its full width: the group is sized to its contents, so a title
    // that went on asking for its whole string would push the save commands
    // out of the column instead of truncating. A `max-w-fit` on the heading
    // could not have delivered this half — it caps growth, it does not stop
    // a nowrap heading asking for its whole text.
    // Two widths on which the bar is still one line: with the freshness
    // control in the title bar (D12) the controls wrap to a line of their
    // own below ~700px here, and on that line the name has the whole width
    // back — so the comparison is made above the wrap, where the name is
    // the one thing giving way.
    await expect(widthAt(host, 1000, title), 'title shrinks').toBeGreaterThan(
      widthAt(host, 900, title),
    );
    await expect(title.scrollWidth, 'title clipped').toBeGreaterThan(
      title.clientWidth,
    );
    host.style.width = '375px';
  },
};

/** 一个元素的字号与字重，按浏览器层叠之后的结果读。 */
const typeOf = (element: Element) => {
  const style = getComputedStyle(element);
  return `${parseFloat(style.fontSize)}/${style.fontWeight}`;
};

/**
 * 侧栏收起之后，路径仍是两级标题，而不是一级半。
 *
 * 收起时定义名从侧栏标题搬到标题栏的 `leading` 槽里，它**换了地方而不是降了
 * 级**：两边都是这一屏的 `h1`。此前搬过去就成了 14/600，而切换器里的视图名是
 * 13/500（registry `sm` 按钮的 `text-[0.8rem]`，被主题钉到 13px 那一档——侧栏
 * 行与分组标签的那一档），于是「订单 / 待出库订单」两半只差了一级的一半，
 * 这一屏的名字反而比它装着的那个视图名还小。现在是 16/600 与 14/500：正是列表
 * 展开时 `view-list-title` 与 `h2` 的那一对，也仍然落在 13/14/16 三档上。
 *
 * 量的是层叠之后的值而不是类名：切换器的字号来自 vendored 的 `Button`，`cn`
 * 把调用处的 `text-sm` 与它自带的 `text-[0.8rem]` 合并掉，`styles.css` 里把
 * 那个 utility 钉到 13px 的规则因此不再匹配这颗按钮——这一串只有真浏览器算
 * 得出来。
 */
export const CollapsedPathIsTwoLevels: Story = {
  ...DisplayCollapsedSidebar,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    const definition = canvasElement.querySelector<HTMLElement>(
      '[data-slot="definition-title"]',
    )!;
    const label = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-switcher"] span',
    )!;

    await expect(typeOf(definition), '定义名').toBe('16/600');
    await expect(typeOf(label), '切换器里的视图名').toBe('14/500');
    // 一级之差，不是半级。
    await expect(
      parseFloat(getComputedStyle(definition).fontSize) -
        parseFloat(getComputedStyle(label).fontSize),
    ).toBe(2);

    // 列表叫回来之后是同一对数——收起只是把 `h1` 挪了个地方。
    await userEvent.click(
      canvas.getByRole('button', {
        name: zhCN['label.workbench.expand-sidebar'],
      }),
    );
    await waitFor(() =>
      expect(
        canvasElement.querySelector('[data-slot="view-list-title"]'),
      ).not.toBeNull(),
    );
    await expect(
      typeOf(
        canvasElement.querySelector<HTMLElement>(
          '[data-slot="view-list-title"]',
        )!,
      ),
      '侧栏标题',
    ).toBe('16/600');
  },
};

/**
 * 标题栏右端那根竖线是这一排的一员，两个主题都算。
 *
 * 它分的是两种作者：本包的视图级控件在左，宿主自己的全局动作在右。它曾被
 * 画成 `--input`（本包的 `--input` 是中灰，不是 shadcn 的近白），为的是量到
 * ≥3:1——结果是一排浅边按钮中间一根黑杠，整行唯一不属于这一行的线（用户
 * 2026-09-22 指出）。分隔线靠**比组内间距高**被读出来（28px 的行里 20px，
 * 对着组内 8px 的步进），不靠比旁边的边更黑；非文本对比度那条下限是给控件
 * 的边的，它不是控件。
 *
 * 所以量的是两件事：它的颜色与旁边按钮的边是同一个 token（`--border`，注册
 * 表 `Separator` 自己的颜色，没有调用处写的色），以及它站在这一行的正中——
 * 注册表竖向分隔线带 `self-stretch`，而一条自带高度的项被 stretch 只会贴顶。
 */
const titleBarDivider = (theme: 'light' | 'dark'): Story => ({
  ...DisplayWithActions,
  args: { ...DisplayWithActions.args, theme },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    // 两个故事量同一个主题会双双通过而只证明一半，所以先读这块面钉的是哪种。
    await expect(
      canvasElement.querySelector('[data-slot="view-surface"]'),
    ).toHaveAttribute('data-theme', theme);

    const controls = canvasElement.querySelector<HTMLElement>(
      '[data-slot="view-controls"]',
    )!;
    const divider = controls.querySelector<HTMLElement>(
      '[data-slot="separator"]',
    );
    await expect(divider, '两端都有东西时才画这根线').not.toBeNull();
    await expect(divider!.getBoundingClientRect().height).toBe(20);

    // 与它右边那颗宿主按钮的边同色。
    const host = within(controls).getByRole('button', { name: '新建订单' });
    await expect(getComputedStyle(divider!).backgroundColor).toBe(
      getComputedStyle(host).borderTopColor,
    );

    // 站在行的正中，误差一像素以内。
    const middle = (rect: DOMRect) => rect.top + rect.height / 2;
    await expect(
      Math.abs(
        middle(divider!.getBoundingClientRect()) -
          middle(controls.getBoundingClientRect()),
      ),
    ).toBeLessThanOrEqual(1);
  },
});

export const TitleBarDividerInLightTheme: Story = titleBarDivider('light');
export const TitleBarDividerInDarkTheme: Story = titleBarDivider('dark');
