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

// Builds the package's command, `wow-view-engine theme-check`
// (theme-architecture.md 5.2, S7), into `dist/theme-check.mjs`: one file for
// Node, beside the registry, the token rules and the presets it reads.
//
// PostCSS, which parses the stylesheets, is bundled in: it is a build tool
// of this package, not something a host installs to render a view. culori,
// already a dependency, stays external. Nothing in `exports` reaches the
// command, so no runtime entry and no size ceiling carries it.
//
//   node scripts/build-theme-check.mjs      (after `vite build`)

import { chmodSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));

await build({
  root,
  configFile: false,
  logLevel: 'warn',
  ssr: { noExternal: ['postcss'], external: ['culori'] },
  build: {
    ssr: 'theme-check/bin.ts',
    outDir: 'dist',
    emptyOutDir: false,
    copyPublicDir: false,
    sourcemap: false,
    target: 'node22',
    rolldownOptions: {
      output: {
        entryFileNames: 'theme-check.mjs',
        banner: '#!/usr/bin/env node',
      },
    },
  },
});

chmodSync(new URL('../dist/theme-check.mjs', import.meta.url), 0o755);
