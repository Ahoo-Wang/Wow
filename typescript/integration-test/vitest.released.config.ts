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

// The runtime smoke against a released example server image (the legacy
// contract of typescript-contract.yml, one job per version). Only
// test/released/ runs: the rest of the suite drives aggregates and generated
// clients of the server built from this commit. The same-source contract runs
// test/released/ too, through vitest.config.ts.
export default defineConfig({
  test: {
    include: ['test/released/**/*.test.ts'],
  },
});
