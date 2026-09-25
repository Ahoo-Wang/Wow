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
import { emulateMedia } from './.storybook/media.js';
import { realMouse, realMouseAway } from './.storybook/realMouse.js';
import { DurationShardSequencer } from './scripts/shard-sequencer.mjs';

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));

// Local runs use Chromium; CI sets the full acceptance matrix. Vitest reports
// every instance, so a failure in one browser does not hide the others.
const browsers = (process.env.STORYBOOK_BROWSERS ?? 'chromium')
  .split(',')
  .map(name => name.trim())
  .filter(Boolean);

// A browser in Playwright's Linux container rather than one on this machine
// (scripts/linux-browser.mjs): the one way to reproduce what CI's Linux
// browsers do — fonts, ICU, compositing — from a Mac, and the only browser
// the screenshot baselines are taken in. The container reaches the Vitest
// server on this machine's loopback through Playwright's own proxy.
const remoteBrowser = process.env.STORYBOOK_BROWSER_WS
  ? {
      wsEndpoint: process.env.STORYBOOK_BROWSER_WS,
      exposeNetwork: '<loopback>',
    }
  : undefined;

export default defineConfig({
  optimizeDeps: {
    include: ['dayjs', 'react/compiler-runtime'],
  },
  test: {
    // `--shard` splits the files by their measured duration
    // (test-durations.json) rather than by a hash of their paths.
    sequence: { sequencer: DurationShardSequencer },
    projects: [
      // Plain unit tests of the story data (the retail generator and the fake
      // data source `rowSource`): Node, no browser, no Storybook. Wow's
      // packages resolve to their sources, as in Storybook, so this runs
      // without a package build.
      {
        extends: true,
        resolve: {
          // Exact names: the unit tests import the root entries only, and
          // a prefix match would send `…/ui` under `index.ts`.
          alias: [
            {
              find: /^@ahoo-wang\/wow-client$/,
              replacement: path.join(
                currentDirectory,
                '../wow-client/src/index.ts',
              ),
            },
            // The retail views are opened by a headless engine
            // (`retail/views.test.ts`): no React, no DOM.
            {
              find: /^@ahoo-wang\/wow-view-engine$/,
              replacement: path.join(
                currentDirectory,
                '../wow-view-engine/src/index.ts',
              ),
            },
          ],
        },
        test: {
          name: 'unit',
          environment: 'node',
          include: ['stories/**/*.test.ts'],
        },
      },
      storybookProject('storybook', {
        tags: ['test'],
        instances: browsers.map(browser => ({ browser })),
      }),
      // The screenshot baselines (themes.md 5.5): the stories tagged
      // `visual`, compared picture by picture. Only against the Linux
      // container (`pnpm test:visual`), so no machine compares its own fonts
      // with the baselines; elsewhere the project does not exist.
      ...(remoteBrowser
        ? [
            storybookProject('visual', {
              tags: ['visual'],
              instances: [{ browser: 'chromium' as const }],
              setupFiles: [
                path.join(currentDirectory, '.storybook/vitest.visual.ts'),
              ],
              // One width for every picture, the desktop a board is built
              // for; and no motion, so a picture is never mid-transition.
              viewport: { width: 1280, height: 900 },
              contextOptions: { reducedMotion: 'reduce', deviceScaleFactor: 1 },
            }),
          ]
        : []),
    ],
  },
});

/**
 * The stories as browser tests: the interaction project, and the visual one
 * that runs the `visual` stories again with their pictures compared.
 */
function storybookProject(
  name: string,
  options: {
    tags: string[];
    instances: { browser: 'chromium' | 'firefox' | 'webkit' }[];
    setupFiles?: string[];
    viewport?: { width: number; height: number };
    contextOptions?: { reducedMotion: 'reduce'; deviceScaleFactor: number };
  },
) {
  return {
    extends: true,
    plugins: [
      storybookTest({
        configDir: path.join(currentDirectory, '.storybook'),
        tags: { include: options.tags },
      }),
    ],
    test: {
      name,
      setupFiles: [
        path.join(currentDirectory, '.storybook/vitest.setup.ts'),
        ...(options.setupFiles ?? []),
      ],
      browser: {
        enabled: true,
        // The browser's own mouse, for what a built event cannot stand in
        // for (.storybook/realMouse.ts), and the media the page is laid out
        // for (.storybook/media.ts).
        commands: { realMouse, realMouseAway, emulateMedia },
        fileParallelism: false,
        headless: true,
        provider: playwright({
          launchOptions: {
            channel: process.env.STORYBOOK_BROWSER_CHANNEL || undefined,
          },
          connectOptions: remoteBrowser,
          contextOptions: options.contextOptions,
        }),
        instances: options.instances,
        ...(options.viewport ? { viewport: options.viewport } : {}),
        expect: {
          toMatchScreenshot: {
            comparatorName: 'pixelmatch' as const,
            // The container draws the same page to the same pixel every
            // time; a baseline that moves at all is a change to look at.
            comparatorOptions: { allowedMismatchedPixelRatio: 0 },
            // `baselines/<story file>/<name>-<browser>.png`: no platform in
            // the name — the browser is always the container's — and not
            // `__screenshots__`, where a failing test drops its own picture.
            resolveScreenshotPath: ({
              root,
              testFileName,
              arg,
              browserName,
              ext,
            }: {
              root: string;
              testFileName: string;
              arg: string;
              browserName: string;
              ext: string;
            }) =>
              path.join(
                root,
                'baselines',
                testFileName,
                `${arg}-${browserName}${ext}`,
              ),
          },
        },
      },
    },
  };
}
