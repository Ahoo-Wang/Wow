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
 * The media the page is laid out for, as the browser would be told by a
 * print dialog (`media: 'print'`) or the reader's Windows contrast theme
 * (`forcedColors: 'active'`). Playwright's own emulation re-evaluates every
 * media query — the stylesheet's and a `matchMedia` listener's alike — the
 * way the real change would. `null` hands a feature back to the browser.
 */
export interface EmulatedMedia {
  media?: 'screen' | 'print' | null;
  forcedColors?: 'active' | 'none' | null;
}

export const emulateMedia: BrowserCommand<[media: EmulatedMedia]> = async (
  context,
  media,
) => {
  await context.page.emulateMedia(media);
};
