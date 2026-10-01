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

// Run after building: pnpm --filter @ahoo-wang/wow-view-store test:package
//
// Holds the built package to the public surface its source promises:
//
// 1. Every file `exports` names exists and is not empty, and the entry
//    resolves under `import` to the file it declares.
// 2. The entry exports at run time exactly the values `test/surface/root.txt`
//    names. The list is written from the source by
//    `test/publicSurface.test.ts`; this holds the bundle to it.
// 3. The bundle bundles none of its peers: the Fetcher, wow-client and the
//    view engine are imported, so the `ViewStoreError` the store rejects with
//    is the host's own engine's.
// 4. No declaration map ships: the package holds no `src`.
// 5. The entry's gzipped size stays under its regression ceiling.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  checkSizes,
  gzippedSize,
  staticClosure,
} from '../../../.github/scripts/size-budget.mjs';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;
const root = manifest.exports['.'];

for (const path of [root.types, root.import, root.default])
  assert.ok(
    statSync(new URL(path, packageRoot)).size > 0,
    `${path} is missing or empty; run the build first`,
  );

const esm = import.meta.resolve(name);
assert.equal(
  esm,
  new URL(root.import, packageRoot).href,
  `${name} does not resolve to its declared import target`,
);

const values = readFileSync(
  new URL('test/surface/root.txt', packageRoot),
  'utf8',
)
  .split('\n')
  .filter(line => line.startsWith('value '))
  .map(line => line.slice('value '.length).trim())
  .sort();
assert.deepEqual(
  Object.keys(await import(esm)).sort(),
  values,
  `${name} does not export the values test/surface/root.txt names`,
);

const bundle = readFileSync(new URL(root.import, packageRoot), 'utf8');
for (const peer of Object.keys(manifest.peerDependencies))
  assert.match(
    bundle,
    new RegExp(`from\\s*["']${peer.replace('/', '\\/')}["']`),
    `the bundle does not import its peer ${peer}`,
  );
assert.doesNotMatch(
  bundle,
  /class ViewStoreError\b/,
  "the bundle carries its own ViewStoreError instead of the engine's",
);

const declarationMaps = readdirSync(new URL('dist/', packageRoot), {
  recursive: true,
}).filter(file => /\.d\.ts\.map$/.test(String(file)));
assert.deepEqual(declarationMaps, [], 'dist holds declaration maps');

const sizes = checkSizes({
  packageName: name,
  budgetFile: fileURLToPath(new URL('scripts/size-budget.json', packageRoot)),
  measured: {
    '.': gzippedSize(
      staticClosure(fileURLToPath(new URL(root.import, packageRoot))),
    ),
  },
});

console.log(
  `${name} resolves to its declared entry, exports at run time exactly the ${values.length} values test/surface/root.txt names, imports its peers rather than bundling them, ships no declaration map, and weighs, gzipped against its ceiling, ${sizes}.`,
);
