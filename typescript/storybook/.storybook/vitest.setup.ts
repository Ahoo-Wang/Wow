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
 * unhandled error. Nothing in the story went wrong, so the event is stopped
 * before the error catcher sees it. Every other error still fails the story.
 */
const BENIGN =
  /ResizeObserver loop (limit exceeded|completed with undelivered notifications)/;

window.addEventListener(
  'error',
  event => {
    if (BENIGN.test(event.message)) event.stopImmediatePropagation();
  },
  // Registered on the capture phase and first, so it runs before the
  // catcher Vitest installs on the same target.
  true,
);
