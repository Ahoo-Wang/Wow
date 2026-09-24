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

// Run after building: pnpm --filter @ahoo-wang/wow-react test:package
//
// Holds the built package to the public surface its source promises:
//
// 1. Every file `exports` names exists and is not empty, and the entry
//    resolves under `import` to the file it declares.
// 2. The entry exports at run time exactly the values `test/surface/root.txt`
//    names. The list is written from the source by
//    `test/publicSurface.test.ts`; this holds the bundle to it, so a build that
//    drops or adds a binding fails here.
// 3. The bundle imports React's compiler runtime from `react/compiler-runtime`
//    (React 19), not the `react-compiler-runtime` polyfill, which is no
//    dependency of this package.
// 4. No declaration map ships: the package holds no `src`, so a map would send
//    "go to definition" to files that are not there.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';

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
assert.match(
  bundle,
  /from\s*["']react\/compiler-runtime["']/,
  'the bundle is not compiled by the React Compiler against react/compiler-runtime',
);
assert.doesNotMatch(
  bundle,
  /["']react-compiler-runtime["']/,
  'the bundle imports the react-compiler-runtime polyfill',
);

const declarationMaps = readdirSync(new URL('dist/', packageRoot), {
  recursive: true,
}).filter(file => /\.d\.ts\.map$/.test(String(file)));
assert.deepEqual(declarationMaps, [], 'dist holds declaration maps');

console.log(
  `${name} resolves to its declared entry, exports at run time exactly the ${values.length} values test/surface/root.txt names, runs on react/compiler-runtime, and ships no declaration map.`,
);
