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
import {
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;
const require = createRequire(new URL('package.json', packageRoot));

const SURFACE_LISTS = {
  '.': 'test/surface/root.txt',
  './dsl': 'test/surface/dsl.txt',
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

// 4. The DSL entry loads no HTTP code: none of its modules, followed through
//    the chunks it imports, imports a fetcher package or reflect-metadata.
const HTTP_PACKAGES =
  /from\s*["'](@ahoo-wang\/fetcher[^"']*|reflect-metadata)["']|require\(["'](@ahoo-wang\/fetcher[^"']*|reflect-metadata)["']\)/;
for (const path of [
  manifest.exports['./dsl'].import.default,
  manifest.exports['./dsl'].require.default,
]) {
  const seen = new Set();
  const pending = [new URL(path, packageRoot)];
  while (pending.length > 0) {
    const url = pending.pop();
    if (seen.has(url.href)) continue;
    seen.add(url.href);
    const code = readFileSync(url, 'utf8');
    const http = HTTP_PACKAGES.exec(code);
    assert.equal(
      http,
      null,
      `${path} reaches ${http?.[1] ?? http?.[2]} through ${url.pathname}`,
    );
    for (const [, relative] of code.matchAll(
      /(?:from\s*|require\()["'](\.\.?\/[^"']+)["']/g,
    ))
      pending.push(new URL(relative, url));
  }
}

// 5. No declaration map ships: the package holds no `src`, so a map would
//    send "go to definition" to files that are not there.
const declarationMaps = readdirSync(new URL('dist/', packageRoot), {
  recursive: true,
}).filter(file => /\.d\.c?ts\.map$/.test(String(file)));
assert.deepEqual(declarationMaps, [], 'dist holds declaration maps');

// 6. The root entry tree-shakes: an application that imports only the error
//    model and the command headers carries no client, no decorator and no
//    fetcher package. The build keeps one module per source file
//    (`preserveModules`), and `sideEffects: false` lets a bundler drop every
//    module the import does not reach — the decorated client classes with
//    them. Vite's own bundler bundles each probe for real, the way an
//    application's build would; the fetcher packages stay external, so a
//    module that survived would show as an import of one.
const shaken = await shake(['toWowError', 'waitStrategy', 'WowHeaders']);
assert.deepEqual(
  shaken.packages,
  [],
  `importing toWowError, waitStrategy and WowHeaders still loads ${shaken.packages.join(', ')}`,
);
for (const kept of ['toWowError', 'waitStrategy'])
  assert.match(shaken.code, new RegExp(kept), `the probe lost ${kept}`);
// The control: a probe that does use a client must keep fetcher-decorator,
// or the assertion above would pass whatever the build did.
const control = await shake(['CommandClient']);
assert.ok(
  control.packages.includes('@ahoo-wang/fetcher-decorator'),
  'a probe importing CommandClient does not load fetcher-decorator; the tree-shaking check proves nothing',
);

console.log(
  `${entries.length} entries resolve under import and require, and export at run time exactly the ${checked} values their surface lists name, the DSL entry loads no HTTP code, importing only toWowError, waitStrategy and WowHeaders loads no fetcher package, and no declaration map ships.`,
);

/**
 * Bundles a module that imports `names` from the built ES module root entry
 * and re-exports them, and answers the fetcher packages the bundle still
 * imports and its code.
 */
async function shake(names) {
  const { build } = await import('vite');
  const dir = mkdtempSync(join(tmpdir(), 'wow-client-shake-'));
  try {
    const entry = join(dir, 'probe.js');
    const root = fileURLToPath(
      new URL(manifest.exports['.'].import.default, packageRoot),
    );
    writeFileSync(
      entry,
      `export { ${names.join(', ')} } from ${JSON.stringify(root)};\n`,
    );
    const [output] = await build({
      configFile: false,
      logLevel: 'silent',
      root: dir,
      build: {
        write: false,
        minify: false,
        lib: { entry, formats: ['es'], fileName: 'probe' },
        rollupOptions: { external: id => /^@ahoo-wang\/fetcher/.test(id) },
      },
    });
    const chunks = output.output.filter(file => file.type === 'chunk');
    const code = chunks.map(chunk => chunk.code).join('\n');
    const packages = [
      ...new Set(chunks.flatMap(chunk => chunk.imports)),
    ].filter(id => /^@ahoo-wang\/fetcher|^reflect-metadata/.test(id));
    return { packages: packages.sort(), code };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
