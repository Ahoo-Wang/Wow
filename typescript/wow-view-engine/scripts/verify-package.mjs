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

// Run after building: pnpm --filter @ahoo-wang/fetcher-view-engine test:package
//
// Three properties of the built package, which no unit test can see because
// each one is about the artifact rather than the source (docs/design.md §12):
//
// 1. Every declared entry resolves and imports.
// 2. The root entry's types need no DOM lib, so a Node or worker consumer can
//    use the kernels and the runtime.
// 3. No JavaScript entry pulls in the stylesheet, so importing the package
//    never puts CSS in a host page that did not ask for it.
import assert from 'node:assert/strict';
import {
  readFileSync,
  statSync,
  mkdtempSync,
  writeFileSync,
  rmSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const packageRoot = new URL('../', import.meta.url);
const manifest = JSON.parse(
  readFileSync(new URL('package.json', packageRoot), 'utf8'),
);
const name = manifest.name;

/** Every file `exports` promises, by the specifier that reaches it. */
const targets = new Map();
for (const [specifier, entry] of Object.entries(manifest.exports)) {
  const paths = typeof entry === 'string' ? { default: entry } : entry;
  for (const path of Object.values(paths)) {
    assert.ok(
      statSync(new URL(path, packageRoot)).size > 0,
      `${path} is missing or empty; run the build first`,
    );
  }
  targets.set(specifier, paths);
}

// 1. Entries resolve where they promise to.
const jsEntries = [];
for (const [specifier, paths] of targets) {
  const subpath = specifier === '.' ? '' : specifier.slice(1);
  if (!paths.import) continue;
  const resolved = import.meta.resolve(name + subpath);
  assert.equal(
    resolved,
    new URL(paths.import, packageRoot).href,
    `${name}${subpath} does not resolve to its declared target`,
  );
  jsEntries.push({ specifier: name + subpath, resolved });
}
assert.ok(jsEntries.length >= 3, 'Expected the root, /react and /ui entries');

// The stylesheet ships as its own entry, which a host imports deliberately.
const styles = manifest.exports['./styles.css'];
assert.equal(typeof styles, 'string', './styles.css must be a single target');
assert.ok(
  readFileSync(new URL(styles, packageRoot), 'utf8').includes('.fve-root'),
  'The theme must hang off the .fve-root boundary',
);

// 2. The root entry's types compile without the DOM lib.
const typeProbe = mkdtempSync(new URL('.package-types-', packageRoot));
try {
  const file = `${typeProbe}/consumer.ts`;
  writeFileSync(
    file,
    [
      `import { ViewEngine, MemoryViewStore, validateDashboard } from '${name}';`,
      `import type { ViewStore, RecordViewConfig } from '${name}';`,
      `declare const engine: ViewEngine;`,
      `declare const store: ViewStore;`,
      `declare const config: RecordViewConfig;`,
      `void [engine, store, config, MemoryViewStore, validateDashboard];`,
      '',
    ].join('\n'),
  );
  const program = ts.createProgram([file], {
    noEmit: true,
    strict: true,
    // The package's own declarations are exactly what this checks, so they
    // cannot be skipped; `skipDefaultLibCheck` still skips TypeScript's own.
    skipLibCheck: false,
    skipDefaultLibCheck: true,
    target: ts.ScriptTarget.ES2022,
    // The point of the check: ES2022 and Node, never DOM.
    lib: ['lib.es2022.d.ts'],
    types: ['node'],
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
  });
  const diagnostics = ts.getPreEmitDiagnostics(program);
  assert.equal(
    diagnostics.length,
    0,
    `The root entry's types need the DOM lib:\n${ts.formatDiagnostics(
      diagnostics,
      {
        getCanonicalFileName: path => path,
        getCurrentDirectory: () => process.cwd(),
        getNewLine: () => '\n',
      },
    )}`,
  );
} finally {
  rmSync(typeProbe, { recursive: true, force: true });
}

// 3. No JavaScript entry imports the stylesheet, at any depth.
//
// This runs before the entries are imported, because Node refuses a `.css`
// specifier with an error about file extensions that says nothing about why
// the rule exists.
const visited = new Set();
for (const entry of jsEntries) visited.add(entry.resolved);
for (const file of visited) {
  const code = readFileSync(new URL(file), 'utf8');
  for (const { fileName } of ts.preProcessFile(code, true, true)
    .importedFiles) {
    assert.ok(
      !fileName.endsWith('.css'),
      `${fileURLToPath(file)} imports ${fileName}; the theme is an explicit entry`,
    );
    if (fileName.startsWith('.')) visited.add(new URL(fileName, file).href);
  }
}

// 4. And, that settled, every entry actually imports.
for (const { specifier, resolved } of jsEntries) {
  const module = await import(resolved);
  assert.ok(Object.keys(module).length > 0, `${specifier} exports nothing`);
}

console.log(
  `${targets.size} entries resolve and import, the root entry's types need no DOM lib, and ${visited.size} runtime modules import no CSS.`,
);
