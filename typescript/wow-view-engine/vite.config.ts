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

import { fileURLToPath, URL } from 'node:url';
import { readFileSync, readdirSync } from 'node:fs';
import { defineConfig } from 'vite';
import react, { reactCompilerPreset } from '@vitejs/plugin-react';
import babel from '@rolldown/plugin-babel';
import tailwindcss from '@tailwindcss/vite';
import dts from 'unplugin-dts/vite';

export default defineConfig({
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] }),
    tailwindcss(),
    dts({ tsconfigPath: './tsconfig.json' }),
    {
      name: 'view-engine-css-namespace',
      enforce: 'post',
      generateBundle: {
        order: 'post',
        handler(_options, bundle) {
          const themes = new URL('./src/themes/', import.meta.url);
          for (const name of readdirSync(themes).filter(name =>
            name.endsWith('.css'),
          )) {
            this.emitFile({
              type: 'asset',
              fileName: `themes/${name}`,
              source: readFileSync(new URL(name, themes), 'utf8'),
            });
          }
          for (const asset of Object.values(bundle)) {
            if (asset.type === 'asset' && asset.fileName.endsWith('.css')) {
              const css =
                typeof asset.source === 'string'
                  ? asset.source
                  : new TextDecoder().decode(asset.source);
              asset.source = css.replaceAll('--tw-', '--fve-tw-');
            }
          }
        },
      },
    },
  ],
  build: {
    cssTarget: 'esnext',
    sourcemap: true,
    lib: {
      entry: { index: 'src/index.ts', react: 'src/react.ts' },
      formats: ['es'],
      fileName: (_format, entry) => `${entry}.js`,
      cssFileName: 'styles',
    },
    rolldownOptions: {
      external:
        /^(react-error-boundary|@dnd-kit\/react|@dnd-kit\/dom|react|react-dom|react-grid-layout|react-markdown|react-day-picker|@base-ui\/react|@ahoo-wang\/fetcher-wow|@ahoo-wang\/fetcher-react|@date-fns\/tz|@tanstack\/react-table|lucide-react|class-variance-authority|clsx|tailwind-merge|recharts)(\/|$)/,
    },
  },
});
