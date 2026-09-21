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
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import displayMeta, {
  NarrowTitleBar as DisplayNarrowTitleBar,
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
  title: 'View Engine/数据视图/标题栏/回归',
  tags: ['!dev', '!autodocs', 'test'],
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
