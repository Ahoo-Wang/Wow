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
      // The 9.x store at the root, the V10 one at `/v10`.
      entry: { index: 'src/index.ts', v10: 'src/v10/index.ts' },
      formats: ['es'],
      fileName: (_format, name) => `${name}.es.js`,
    },
    rolldownOptions: {
      // Every peer stays external: the Fetcher, wow-client and the view
      // engine, whose ViewStoreError the store rejects with.
      external: [/^@ahoo-wang\//],
      output: {
        keepNames: true,
        // What the two entries share (the paths, the wire, the memory of
        // writes), under a name that does not change from build to build.
        chunkFileNames: '[name].es.js',
      },
    },
  },
  plugins: [
    dts({
      outDirs: 'dist',
      tsconfigPath: './tsconfig.json',
    }),
  ],
});
