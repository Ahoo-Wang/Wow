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

/**
 * Carries `src/themes.css` into `dist` as it is written. The presets are only
 * `--fve-*` assignments on `:where([data-fve-preset])` — nothing for Tailwind
 * or the boundary scoping to do, and nothing a library build would emit on
 * its own: an imported stylesheet is merged into `styles.css`, which is
 * exactly what an optional entry must not be. `scripts/verify-package.mjs`
 * checks what lands.
 */
function presets(): Plugin {
  const source = fileURLToPath(new URL('./src/themes.css', import.meta.url));
  return {
    name: 'fve-presets',
    buildStart() {
      this.addWatchFile(source);
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'themes.css',
        source: readFileSync(source, 'utf8'),
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
    presets(),
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
