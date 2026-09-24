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

// Run after building: pnpm --filter @ahoo-wang/wow-client test:package
//
// Holds the built package to the public surface its source promises:
//
// 1. Every file `exports` names exists and is not empty.
// 2. Every entry resolves, under both `import` and `require`, to the file it
//    declares.
// 3. Every entry exports at run time, as ES module and as CommonJS, exactly
//    the values its list under `test/surface/` names. The lists are written
//    from the source by `test/publicSurface.test.ts`; this holds the bundle
//    to them, so a build that drops or adds a binding fails here.
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;
const require = createRequire(new URL('package.json', packageRoot));

const SURFACE_LISTS = {
  '.': 'test/surface/root.txt',
  './legacy': 'test/surface/legacy.txt',
};

/** The code entries: every `exports` key but `./package.json`. */
const entries = Object.entries(manifest.exports).filter(
  ([subpath]) => subpath !== './package.json',
);
assert.deepEqual(
  entries.map(([subpath]) => subpath).sort(),
  Object.keys(SURFACE_LISTS).sort(),
  'Every entry in package.json exports needs a surface list, and every list an entry',
);

let checked = 0;
for (const [subpath, conditions] of entries) {
  const specifier = name + subpath.slice(1);
  for (const { types, default: target } of [
    conditions.import,
    conditions.require,
  ]) {
    for (const path of [types, target])
      assert.ok(
        statSync(new URL(path, packageRoot)).size > 0,
        `${path} is missing or empty; run the build first`,
      );
  }

  const esm = import.meta.resolve(specifier);
  assert.equal(
    esm,
    new URL(conditions.import.default, packageRoot).href,
    `${specifier} does not resolve to its declared import target`,
  );
  const cjs = require.resolve(specifier);
  assert.equal(
    cjs,
    new URL(conditions.require.default, packageRoot).pathname,
    `${specifier} does not resolve to its declared require target`,
  );

  const values = readFileSync(
    new URL(SURFACE_LISTS[subpath], packageRoot),
    'utf8',
  )
    .split('\n')
    .filter(line => line.startsWith('value '))
    .map(line => line.slice('value '.length).trim())
    .sort();
  assert.deepEqual(
    Object.keys(await import(esm)).sort(),
    values,
    `${specifier} (ES module) does not export the values ${SURFACE_LISTS[subpath]} names`,
  );
  assert.deepEqual(
    Object.keys(require(cjs)).sort(),
    values,
    `${specifier} (CommonJS) does not export the values ${SURFACE_LISTS[subpath]} names`,
  );
  checked += values.length;
}

console.log(
  `${entries.length} entries resolve under import and require, and export at run time exactly the ${checked} values their surface lists name.`,
);
