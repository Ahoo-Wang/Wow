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

import type { BrowserCommand } from 'vitest/node';

/**
 * The browser's own mouse, driven through Playwright — the input a person
 * gives, not an event a script builds.
 *
 * A story reaches for it where a built event is not what the browser would
 * send. The case that made it: `@dnd-kit/dom` captures the pointer that
 * started a drag (`setPointerCapture`), and a browser captures only a
 * pointer it knows. `user-event` numbers its mouse 1, which is Chromium's and
 * WebKit's mouse; Firefox's is 0, and exists only once a real mouse has
 * moved — so there every built drag was cancelled at its first step, while
 * a person's drag works (checked by hand in all three browsers).
 *
 * Points are the story frame's client coordinates, as a story measures them
 * with `getBoundingClientRect`; they are carried onto the page the frame sits
 * in, through the frame's box and any scale the runner puts on it.
 */
export type RealMouseAction = 'move' | 'down' | 'up';

type Context = Parameters<BrowserCommand<[]>>[0];

async function pagePoint(
  context: Context,
  x: number,
  y: number,
): Promise<{ x: number; y: number }> {
  const frame = await (await context.frame()).frameElement();
  const box = await frame.boundingBox();
  if (!box) throw new Error('The story frame is not on the page.');
  const inner = await frame.evaluate(element => ({
    width: (element as HTMLElement).clientWidth,
    left: (element as HTMLElement).clientLeft,
    top: (element as HTMLElement).clientTop,
  }));
  const border = inner.left * 2;
  const scale = box.width / (inner.width + border);
  return {
    x: box.x + (inner.left + x) * scale,
    y: box.y + (inner.top + y) * scale,
  };
}

export const realMouse: BrowserCommand<
  [action: RealMouseAction, x: number, y: number]
> = async (context, action, x, y) => {
  const { mouse } = context.page;
  const point = await pagePoint(context, x, y);
  await mouse.move(point.x, point.y);
  if (action === 'down') await mouse.down();
  if (action === 'up') await mouse.up();
};

/**
 * Takes the mouse off the story altogether, to the page's corner outside the
 * frame. A mouse left resting over the story is not neutral: a browser
 * re-hovers whatever is laid out under it next (Firefox sends a mouse move
 * for it), and a menu that opens under a resting mouse opens its submenu.
 */
export const realMouseAway: BrowserCommand<[]> = async context => {
  const frame = await (await context.frame()).frameElement();
  const box = await frame.boundingBox();
  const viewport = context.page.viewportSize();
  const right = viewport ? viewport.width - 1 : 0;
  const bottom = viewport ? viewport.height - 1 : 0;
  // Beside the frame if there is room, else below it, else the corner.
  if (box && box.x + box.width < right) await context.page.mouse.move(right, 0);
  else if (box && box.y + box.height < bottom)
    await context.page.mouse.move(0, bottom);
  else await context.page.mouse.move(0, 0);
};
