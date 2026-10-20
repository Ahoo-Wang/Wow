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

import { defineConfig } from 'vitest/config';

// The contract of `@ahoo-wang/wow-view-store` and the view engine on it
// against the V10 view store service (`pnpm test:v10`): only
// test/view-store-v10/, whose global setup starts the service in a JVM on
// ports bound to 0 (or takes WOW_V10_VIEW_STORE_URL).
export default defineConfig({
  test: {
    include: ['test/view-store-v10/**/*.test.ts'],
    globalSetup: ['test/view-store-v10/globalSetup.ts'],
    // Each request is a JVM round trip; a case writes a few dozen.
    testTimeout: 30_000,
  },
});
