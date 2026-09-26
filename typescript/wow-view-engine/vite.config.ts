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

import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import react, { reactCompilerPreset } from '@vitejs/plugin-react';
import babel from '@rolldown/plugin-babel';
import tailwindcss from '@tailwindcss/vite';
import dts from 'unplugin-dts/vite';
import { scopeUtilities } from './scripts/scope-utilities.mjs';
import { buildThemes, presetSources } from './scripts/themes.mjs';
import { registryData } from './theme-check/registry';
import { themeSource } from './theme-check/resolve';

/**
 * The optional stylesheets, carried into `dist` beside `styles.css`:
 * `shadcn-bridge.css`, a host's shadcn tokens read into the host variables,
 * as it is written; and the presets, which `scripts/themes.mjs` makes from
 * their sources — `themes.css` with every preset and `themes/<name>.css`
 * with one each, the notes taken out. They are only `--fvp-*` assignments:
 * nothing for Tailwind or the boundary scoping to do, and nothing a library
 * build would emit on its own — an imported stylesheet is merged into
 * `styles.css`, which is exactly what an optional entry must not be.
 * `scripts/verify-package.mjs` checks what lands, by the registry this
 * also writes out as `theme-tokens.json`.
 */
const BRIDGE = fileURLToPath(
  new URL('./src/shadcn-bridge.css', import.meta.url),
);
const SOURCE = fileURLToPath(new URL('./src', import.meta.url));

/**
 * The theme's registry as JSON (`dist/theme-tokens.json`, theme-architecture.md
 * 5.2, D46 Q13): the contract's machine form, shipped beside the stylesheets
 * and outside `exports`. `scripts/verify-package.mjs` checks the built
 * stylesheets by it, and a host's tooling may read it too.
 */
function themeTokens(): string {
  return `${JSON.stringify(registryData(), null, 2)}\n`;
}

function optionalStylesheets(): Plugin {
  return {
    name: 'fve-optional-stylesheets',
    buildStart() {
      this.addWatchFile(BRIDGE);
      this.addWatchFile(`${SOURCE}/themes.css`);
      this.addWatchFile(`${SOURCE}/styles.css`);
      for (const { path } of presetSources(SOURCE)) this.addWatchFile(path);
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'shadcn-bridge.css',
        source: readFileSync(BRIDGE, 'utf8'),
      });
      for (const [fileName, source] of buildThemes(SOURCE))
        this.emitFile({ type: 'asset', fileName, source });
      this.emitFile({
        type: 'asset',
        fileName: 'theme-tokens.json',
        source: themeTokens(),
      });
      // The token rules of `styles.css`, as written, for theme-check to
      // resolve a host's theme by (theme-architecture.md 5.2, S7): the built
      // stylesheet's values are rewritten by the CSS pipeline, so the checker
      // reads the source rules the package's own suites read.
      this.emitFile({
        type: 'asset',
        fileName: 'theme-source.css',
        source: themeSource(readFileSync(`${SOURCE}/styles.css`, 'utf8')),
      });
    },
  };
}

// Rewrite in progress (docs/design/): the root, `/react` and `/ui` entries
// exist, with the theme shipped as a separate `/styles.css` an application
// imports explicitly.
export default defineConfig({
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  plugins: [
    tailwindcss(),
    react(),
    babel({ presets: [reactCompilerPreset()] }),
    dts({ tsconfigPath: './tsconfig.json' }),
    optionalStylesheets(),
  ],
  // Tailwind compiles ahead of PostCSS, so this sees the utilities it emitted
  // and keeps every one of them inside `.fve-root`.
  css: { postcss: { plugins: [scopeUtilities()] } },
  build: {
    cssTarget: 'esnext',
    sourcemap: true,
    lib: {
      entry: {
        index: 'src/index.ts',
        react: 'src/react/index.ts',
        ui: 'src/ui/index.ts',
        styles: 'src/styles.ts',
      },
      formats: ['es'],
      fileName: (_format, entry) => `${entry}.js`,
      cssFileName: 'styles',
    },
    rolldownOptions: {
      external:
        /^(react|react-dom|react-grid-layout|react-markdown|react-day-picker|@base-ui\/react|@ahoo-wang\/wow-client|lucide-react|class-variance-authority|clsx|tailwind-merge|echarts|zrender)(\/|$)/,
    },
  },
});
