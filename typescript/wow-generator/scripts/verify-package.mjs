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

// Run after building: pnpm --filter @ahoo-wang/wow-generator test:package
//
// Holds the built package to the public surface its source promises:
//
// 1. Every file `exports` and `bin` name exists and is not empty.
// 2. The entry resolves, under both `import` and `require`, to the file it
//    declares.
// 3. The entry exports at run time, as ES module and as CommonJS, exactly the
//    values `test/surface/root.txt` names. The list is written from the source
//    by `test/publicSurface.test.ts`; this holds the bundle to it, so a build
//    that drops or adds a binding fails here.
// 4. No declaration map ships: the package holds no `src`, so a map would send
//    "go to definition" to files that are not there.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;
const require = createRequire(new URL('package.json', packageRoot));
const root = manifest.exports['.'];

for (const path of [
  root.import.types,
  root.import.default,
  root.require.types,
  root.require.default,
  ...new Set(Object.values(manifest.bin)),
])
  assert.ok(
    statSync(new URL(path, packageRoot)).size > 0,
    `${path} is missing or empty; run the build first`,
  );

const esm = import.meta.resolve(name);
assert.equal(
  esm,
  new URL(root.import.default, packageRoot).href,
  `${name} does not resolve to its declared import target`,
);
const cjs = require.resolve(name);
assert.equal(
  cjs,
  new URL(root.require.default, packageRoot).pathname,
  `${name} does not resolve to its declared require target`,
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
  `${name} (ES module) does not export the values test/surface/root.txt names`,
);
assert.deepEqual(
  Object.keys(require(cjs)).sort(),
  values,
  `${name} (CommonJS) does not export the values test/surface/root.txt names`,
);

const declarationMaps = readdirSync(new URL('dist/', packageRoot), {
  recursive: true,
}).filter(file => /\.d\.c?ts\.map$/.test(String(file)));
assert.deepEqual(declarationMaps, [], 'dist holds declaration maps');

console.log(
  `${name} resolves under import and require, exports at run time exactly the ${values.length} values test/surface/root.txt names, and ships no declaration map.`,
);
