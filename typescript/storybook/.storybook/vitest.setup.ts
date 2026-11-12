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

import { composeStory } from 'storybook/preview-api';
import { afterAll } from 'vitest';
import { commands } from 'vitest/browser';
// The globals the stories read the real mouse and the media from are
// declared there.
import type {} from '../stories/view-engine/pointerDrag.js';
import type {} from '../stories/view-engine/media.js';

/**
 * The one browser error the story tests may ignore.
 *
 * "ResizeObserver loop completed with undelivered notifications" is the
 * browser saying an observer's callback resized what it observes and the
 * next round was deferred to the following frame — by specification, not a
 * fault; Chrome reports it as an `error` event anyway. A chart that lays
 * itself out inside a column that is itself being laid out (the sidebar
 * panel opening, a held view replacing the result) trips it once in a
 * while, and Vitest's browser mode fails whichever story is running on any
 * unhandled error.
 *
 * How this silences it is not the `stopImmediatePropagation` call, whatever
 * the order below suggests. Vitest's `error-catcher` registers its own
 * listener as the browser tester boots — before any setup file runs, so
 * before this one — and then wraps `window.addEventListener` to count the
 * listeners a test adds. Its own handler reports an unhandled error only
 * while that count is zero, and falls back to `console.error` otherwise.
 * Registering this listener at all is therefore what quiets the catcher;
 * the capture phase and the `stopImmediatePropagation` only keep the event
 * from reaching anything else on `window`.
 *
 * The consequence is worth saying out loud: with this file loaded, no
 * unhandled `error` event fails a story — the benign one and a real one
 * alike land in the console. Errors thrown inside a story's own play
 * function, and unhandled promise rejections, are unaffected: they are not
 * `error` events on `window`, so the catcher still counts zero listeners
 * for them and still reports.
 */
const BENIGN =
  /ResizeObserver loop (limit exceeded|completed with undelivered notifications)/;

window.addEventListener(
  'error',
  event => {
    if (BENIGN.test(event.message)) event.stopImmediatePropagation();
  },
  // Capture phase, so nothing else on `window` sees the benign event.
  true,
);

/**
 * The browser's own mouse, for the gestures a built event cannot stand in
 * for (`realMouse.ts`). Handed to the stories as a global rather than
 * imported: the stories are Storybook's too, and in Storybook's own panel
 * there is no test runner to import it from — `pointerDrag.ts` falls back to
 * a built pointer there.
 */
globalThis.storybookRealMouse = {
  move: (x, y) => commands.realMouse('move', x, y),
  down: (x, y) => commands.realMouse('down', x, y),
  up: (x, y) => commands.realMouse('up', x, y),
  away: () => commands.realMouseAway(),
};

/**
 * The media the page is laid out for — paper, a Windows contrast theme —
 * handed over the same way (`.storybook/media.ts`).
 */
globalThis.storybookMedia = {
  emulate: media => commands.emulateMedia(media),
};

/**
 * The emulation is the browser page's, and every story file runs in a frame
 * of that one page: a file that asked for less motion
 * (`StrictCsp.test.stories.tsx`) hands it back as each next story begins,
 * which its last story never sees. So each file begins with motion as a
 * reader who asked for nothing has it, whatever the file before it left —
 * said outright rather than left to the browser: WebKit in Playwright's
 * Linux image (`scripts/linux-browser.mjs`) reports reduced motion on its
 * own, and its 0.01ms transitions then stood at their first frame, so a
 * focus mark read 3px and a highlight transparent there and nowhere else
 * (2026-09-30).
 */
await commands.emulateMedia({ reducedMotion: 'no-preference' });

/**
 * A file's last story is unmounted before its frame goes, and the file
 * fails if it leaves a listener on the page.
 *
 * A popup open in a story listens outside the story's frame: Base UI
 * positions it with Floating UI's `autoUpdate`, which listens for `scroll`
 * and `resize` on every ancestor that can scroll — across a same-origin
 * frame's edge too, so on this page's own `window` and `visualViewport` —
 * and takes those listeners off when the popup closes or unmounts. A file's
 * last story did neither: it was still mounted when Vitest removed the
 * file's frame, so the page held a function of the removed frame, and
 * through it the frame's document, modules and data. Focus resting on a
 * button that has a tooltip was enough. One renderer process draws every
 * file of a run, and the frames it kept grew it until it died — "Browser
 * connection was closed while running tests", with no test failed (#6075).
 *
 * The unmount leans on how Storybook works, not on a call it offers:
 * Storybook runs a story's cleanups when the next one loads, and registers
 * no unmount at a file's end, so loading a story that renders nothing is
 * what unmounts the last one. The count below is what says so if an update
 * changes that: the listeners on the page's `window` and `visualViewport`
 * (`pageListeners.ts`) as the file begins and again after the unmount. The
 * page has listeners of its own, so the two are compared; a file that ends
 * with more than it began with fails, by its own name.
 */
const listenersAtStart = await commands.pageListeners();

afterAll(async () => {
  await composeStory({ render: () => null }, { title: 'Teardown' }).load();
  const listenersAtEnd = await commands.pageListeners();
  if (listenersAtStart === null || listenersAtEnd === null) return;
  const left = listenersAtEnd - listenersAtStart;
  if (left > 0)
    throw new Error(
      `This file left ${left} event listener(s) on the page outside its ` +
        'frame (window or visualViewport): the removed frame stays in ' +
        'memory. Something it mounted was not unmounted, or listens on ' +
        'the parent page and never stops (.storybook/vitest.setup.ts).',
    );
});

declare module 'vitest/browser' {
  interface BrowserCommands {
    emulateMedia: (media: {
      media?: 'screen' | 'print' | null;
      forcedColors?: 'active' | 'none' | null;
      reducedMotion?: 'reduce' | 'no-preference' | null;
    }) => Promise<void>;
    realMouse: (
      action: 'move' | 'down' | 'up',
      x: number,
      y: number,
    ) => Promise<void>;
    realMouseAway: () => Promise<void>;
    pageListeners: () => Promise<number | null>;
  }
}
