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

import { expect } from 'vitest';
import { page } from 'vitest/browser';
// The global a story hands its pictures to is declared there.
import type {} from '../stories/view-engine/screenshot.js';

/**
 * The `visual` project's one addition to the interaction setup: a story's
 * `matchScreenshot` compares for real (themes.md 5.5). The comparison, the
 * paths and the tolerance are the project's (`vitest.config.ts`); a missing
 * or different picture fails the story, and `-u` writes the new one.
 */
globalThis.storybookScreenshot = async (element, name) => {
  // A locator, not the element: `expect.element` compares a locator once,
  // but an element it polls — a different picture was taken, compared and
  // written again every 50 ms until the story's 15 s ran out (vitest
  // 4.1.11 marks the matcher "assert once" only on the locator path), so a
  // mismatch read as a timeout. With the locator it fails on its first
  // comparison, its reference, actual and diff written next to the
  // baseline (`.vitest-attachments`). The 6 s is the matcher's own wait for
  // a page that holds still: one that never does says so, with its
  // pictures, rather than timing the whole story out.
  await expect
    .element(page.elementLocator(element))
    .toMatchScreenshot(name, { timeout: 6_000 });
};
