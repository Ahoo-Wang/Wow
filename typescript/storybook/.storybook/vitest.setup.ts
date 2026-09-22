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
