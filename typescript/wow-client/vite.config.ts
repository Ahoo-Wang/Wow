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

import { defineConfig } from 'vite';
import dts from 'unplugin-dts/vite';

export default defineConfig({
  build: {
    sourcemap: true,
    lib: {
      entry: {
        index: 'src/index.ts',
        'query/locale/zh_CN': 'src/query/locale/zh_CN.ts',
        'query/locale/en_US': 'src/query/locale/en_US.ts',
      },
      name: 'WowClient',
      fileName: (format, entryName) => {
        return format === 'es'
          ? `${entryName}.es.js`
          : `${entryName}.${format}`;
      },
    },
    rollupOptions: {
      external: [
        '@ahoo-wang/fetcher',
        '@ahoo-wang/fetcher-eventstream',
        '@ahoo-wang/fetcher-decorator',
      ],
      output: {
        globals: {
          '@ahoo-wang/fetcher': 'Fetcher',
          '@ahoo-wang/fetcher-eventstream': 'FetcherEventStream',
          '@ahoo-wang/fetcher-decorator': 'FetcherDecorator',
        },
      },
    },
  },
  plugins: [
    dts({
      // `.d.cts` for the `require` condition and `.d.ts` for `import`, so
      // node16/nodenext consumers see CommonJS and ES module types for the
      // matching build. A primary out dir with a module format makes the plugin
      // resolve every relative specifier and write it with its runtime
      // extension (`./types.js`, `./aggregate/index.cjs`), whatever the source
      // wrote.
      outDirs: [{ dir: 'dist', moduleFormat: 'cjs' }, 'dist'],
      tsconfigPath: './tsconfig.json',
    }),
  ],
});
