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

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { storybookTest } from '@storybook/addon-vitest/vitest-plugin';
import { playwright } from '@vitest/browser-playwright';
import { defineConfig } from 'vitest/config';

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));

// Local runs use Chromium; CI sets the full acceptance matrix. Vitest reports
// every instance, so a failure in one browser does not hide the others.
const browsers = (process.env.STORYBOOK_BROWSERS ?? 'chromium')
  .split(',')
  .map(name => name.trim())
  .filter(Boolean);

export default defineConfig({
  optimizeDeps: {
    include: ['dayjs', 'react/compiler-runtime'],
  },
  test: {
    projects: [
      // Plain unit tests of the story data (the retail generator and the fake
      // data source `rowSource`): Node, no browser, no Storybook. Wow's
      // packages resolve to their sources, as in Storybook, so this runs
      // without a package build.
      {
        extends: true,
        resolve: {
          alias: {
            '@ahoo-wang/wow-client': path.join(
              currentDirectory,
              '../wow-client/src/index.ts',
            ),
          },
        },
        test: {
          name: 'unit',
          environment: 'node',
          include: ['stories/**/*.test.ts'],
        },
      },
      {
        extends: true,
        plugins: [
          storybookTest({
            configDir: path.join(currentDirectory, '.storybook'),
          }),
        ],
        test: {
          name: 'storybook',
          setupFiles: [
            path.join(currentDirectory, '.storybook/vitest.setup.ts'),
          ],
          browser: {
            enabled: true,
            fileParallelism: false,
            headless: true,
            provider: playwright({
              launchOptions: {
                channel: process.env.STORYBOOK_BROWSER_CHANNEL || undefined,
              },
            }),
            instances: browsers.map(browser => ({ browser })),
          },
        },
      },
    ],
  },
});
