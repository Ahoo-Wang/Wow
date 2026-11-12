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

/** What on the page a story's frame can reach and listen on from inside. */
const TARGETS = ['window', 'window.visualViewport'];

const GROUP = 'page-listeners';

/** The pages Playwright's own listeners are already on. */
const prepared = new WeakSet<object>();

/**
 * How many event listeners the page that holds the story frames has on its
 * own `window` and `visualViewport`.
 *
 * A story file runs in a frame of that page, and code in the frame can
 * listen on the page outside it: Floating UI does, for every open popup. A
 * listener left there outlives the frame and keeps all of it alive, which
 * the frame itself cannot see — a page does not list its listeners to its
 * scripts. The browser's debugger does, so the count is read through it
 * (`DOMDebugger.getEventListeners`), and only Chromium has one: `null`
 * elsewhere, where the caller has nothing to compare
 * (`vitest.setup.ts`, #6075).
 *
 * Playwright listens on the page too, from the first time it handles an
 * element there — thirteen listeners, which arrived in the middle of the
 * first file that dragged with the real mouse (`realMouse.ts`) and read as
 * that file's. They are the tool's and stay for the run, so the frame's
 * element is handled once before the first count, and they are in every
 * count.
 */
export const pageListeners: BrowserCommand<[]> = async context => {
  const { page } = context;
  if (page.context().browser()?.browserType().name() !== 'chromium')
    return null;
  if (!prepared.has(page)) {
    const frame = await (await context.frame()).frameElement();
    await frame.boundingBox();
    await frame.dispose();
    prepared.add(page);
  }
  const session = await page.context().newCDPSession(page);
  try {
    let count = 0;
    for (const expression of TARGETS) {
      const { result } = await session.send('Runtime.evaluate', {
        expression,
        objectGroup: GROUP,
      });
      if (!result.objectId) continue;
      const { listeners } = await session.send(
        'DOMDebugger.getEventListeners',
        { objectId: result.objectId },
      );
      count += listeners.length;
    }
    await session.send('Runtime.releaseObjectGroup', { objectGroup: GROUP });
    return count;
  } finally {
    await session.detach();
  }
};
