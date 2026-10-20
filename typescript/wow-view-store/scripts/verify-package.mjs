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
// 2. Each entry exports at run time exactly the values its list under
//    `test/surface/` names. The lists are written from the source by
//    `test/publicSurface.test.ts`; this holds the bundle to them.
// 3. The bundle bundles none of its peers: wow-client and the view engine
//    are imported, so the `ViewStoreError` the store rejects with is the
//    host's own engine's, and the Fetcher's type is referenced by the
//    declarations.
// 4. No declaration map ships: the package holds no `src`.
// 5. Each entry's gzipped size stays under its regression ceiling.
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

/** Each code entry: its `exports` key, and the surface list it is held to. */
const ENTRIES = {
  '.': 'test/surface/root.txt',
};

const measured = {};
const counts = [];
for (const [key, list] of Object.entries(ENTRIES)) {
  const entry = manifest.exports[key];
  const specifier = key === '.' ? name : `${name}/${key.slice(2)}`;
  for (const path of [entry.types, entry.import, entry.default])
    assert.ok(
      statSync(new URL(path, packageRoot)).size > 0,
      `${path} is missing or empty; run the build first`,
    );

  const esm = import.meta.resolve(specifier);
  assert.equal(
    esm,
    new URL(entry.import, packageRoot).href,
    `${specifier} does not resolve to its declared import target`,
  );

  const values = readFileSync(new URL(list, packageRoot), 'utf8')
    .split('\n')
    .filter(line => line.startsWith('value '))
    .map(line => line.slice('value '.length).trim())
    .sort();
  assert.deepEqual(
    Object.keys(await import(esm)).sort(),
    values,
    `${specifier} does not export the values ${list} names`,
  );
  counts.push(`${values.length} in ${list}`);

  const file = fileURLToPath(new URL(entry.import, packageRoot));
  const closure = staticClosure(file);
  const bundle = closure.map(path => readFileSync(path, 'utf8')).join('\n');
  assert.doesNotMatch(
    bundle,
    /class ViewStoreError\b/,
    `${specifier} carries its own ViewStoreError instead of the engine's`,
  );
  measured[key] = gzippedSize(closure);
}

// Every peer is imported by the code or referenced by the declarations,
// never bundled.
const shipped = readdirSync(new URL('dist/', packageRoot), { recursive: true })
  .map(String)
  .filter(file => file.endsWith('.js') || file.endsWith('.d.ts'))
  .map(file => readFileSync(new URL(`dist/${file}`, packageRoot), 'utf8'))
  .join('\n');
for (const peer of Object.keys(manifest.peerDependencies))
  assert.match(
    shipped,
    new RegExp(`from\\s*["']${peer.replace('/', '\\/')}(?:/[\\w-]+)?["']`),
    `neither the bundle nor the declarations import its peer ${peer}`,
  );

const declarationMaps = readdirSync(new URL('dist/', packageRoot), {
  recursive: true,
}).filter(file => /\.d\.ts\.map$/.test(String(file)));
assert.deepEqual(declarationMaps, [], 'dist holds declaration maps');

const sizes = checkSizes({
  packageName: name,
  budgetFile: fileURLToPath(new URL('scripts/size-budget.json', packageRoot)),
  measured,
});

console.log(
  `${name} resolves to its declared entries, exports at run time exactly the values its lists name (${counts.join(', ')}), imports its peers rather than bundling them, ships no declaration map, and weighs, gzipped against its ceilings, ${sizes}.`,
);
