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
// The global a story hands its pictures to is declared there.
import type {} from '../stories/view-engine/screenshot.js';

/**
 * The `visual` project's one addition to the interaction setup: a story's
 * `matchScreenshot` compares for real (themes.md 5.5). The comparison, the
 * paths and the tolerance are the project's (`vitest.config.ts`); a missing
 * or different picture fails the story, and `-u` writes the new one.
 */
globalThis.storybookScreenshot = async (element, name) => {
  // Well inside the story's own 15 s: a page that never holds still says
  // so, with its pictures, rather than timing the whole story out.
  await expect
    .element(element as HTMLElement)
    .toMatchScreenshot(name, { timeout: 6_000 });
};
