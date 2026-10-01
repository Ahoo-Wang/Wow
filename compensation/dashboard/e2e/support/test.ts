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

import { test as base } from "@playwright/test";
import { stubViewStore, type ViewStoreService } from "./viewStoreService.ts";

/**
 * Playwright's `test`, with the compensation service's view store stubbed
 * for every page: the console keeps its views there, so every page asks it
 * for the list and the preferences of the definition it opens. A test reads
 * what was saved through the `viewStore` fixture.
 */
export const test = base.extend<{ viewStore: ViewStoreService }>({
  viewStore: [
    async ({ page }, use) => {
      await use(await stubViewStore(page));
    },
    { auto: true },
  ],
});

export { expect } from "@playwright/test";
