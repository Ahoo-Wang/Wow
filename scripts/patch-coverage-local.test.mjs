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

// The tests of scripts/patch-coverage-local.mjs: `node --test scripts/patch-coverage-local.test.mjs`.

import assert from 'node:assert/strict';
import { join } from 'node:path';
import { test } from 'node:test';
import { changedPaths, modulePath, nameByRepositoryPath, readRoots } from './patch-coverage-local.mjs';

const repository = '/repo';
const kotlin = join(repository, 'core/kernel/src/main/kotlin');
const java = join(repository, 'core/kernel/src/main/java');

/** A source file element of a JaCoCo report, with one line. */
const source = name => `<sourcefile name="${name}"><line nr="1" mi="0" ci="1" mb="0" cb="0"/></sourcefile>`;

/** A class element of a JaCoCo report, compiled from [file]. */
const type = (name, file) => `<class name="me/ahoo/${name}" sourcefilename="${file}"><method name="m"/></class>`;

/** The packages of [xml], each with its class names and source file names, in order. */
function packagesOf(xml) {
  return [...xml.matchAll(/<package name="([^"]*)">([\s\S]*?)<\/package>/g)].map(([, name, body]) => ({
    name,
    classes: [...body.matchAll(/<class name="([^"]*)"/g)].map(([, n]) => n),
    sources: [...body.matchAll(/<sourcefile name="([^"]*)"/g)].map(([, n]) => n),
  }));
}

test('a source file is named by its source root and package, and its classes go with it', () => {
  const xml = `<report><package name="me/ahoo">${type('A', 'A.kt')}${type('A$1', 'A.kt')}${source('A.kt')}</package></report>`;
  const { text, unmatched } = nameByRepositoryPath(xml, [kotlin], repository, path =>
    path === join(kotlin, 'me/ahoo/A.kt'),
  );
  assert.equal(unmatched, 0);
  assert.deepEqual(packagesOf(text), [
    { name: 'core/kernel/src/main/kotlin/me/ahoo', classes: ['me/ahoo/A', 'me/ahoo/A$1'], sources: ['A.kt'] },
  ]);
});

test('a package whose files are in two source roots becomes one package per root', () => {
  const xml =
    `<report><package name="me/ahoo">${type('A', 'A.kt')}${type('B', 'B.java')}` +
    `${source('A.kt')}${source('B.java')}</package></report>`;
  const files = new Set([join(kotlin, 'me/ahoo/A.kt'), join(java, 'me/ahoo/B.java')]);
  const { text, unmatched } = nameByRepositoryPath(xml, [kotlin, java], repository, path => files.has(path));
  assert.equal(unmatched, 0);
  assert.deepEqual(packagesOf(text), [
    { name: 'core/kernel/src/main/kotlin/me/ahoo', classes: ['me/ahoo/A'], sources: ['A.kt'] },
    { name: 'core/kernel/src/main/java/me/ahoo', classes: ['me/ahoo/B'], sources: ['B.java'] },
  ]);
});

test('a source file in no source root keeps its package path and is counted', () => {
  const xml =
    `<report><package name="me/ahoo">${type('A', 'A.kt')}${type('G', 'Generated.kt')}` +
    `${source('A.kt')}${source('Generated.kt')}</package></report>`;
  const { text, unmatched } = nameByRepositoryPath(xml, [kotlin], repository, path =>
    path === join(kotlin, 'me/ahoo/A.kt'),
  );
  assert.equal(unmatched, 1);
  assert.deepEqual(packagesOf(text), [
    { name: 'core/kernel/src/main/kotlin/me/ahoo', classes: ['me/ahoo/A'], sources: ['A.kt'] },
    { name: 'me/ahoo', classes: ['me/ahoo/G'], sources: ['Generated.kt'] },
  ]);
});

test('a module is named by its Gradle path, with or without the leading colon', () => {
  assert.equal(modulePath('postgres'), ':postgres');
  assert.equal(modulePath(':postgres'), ':postgres');
  assert.equal(modulePath('core:kernel'), ':core:kernel');
});

test("git status's entries keep the first character of every path, and a rename gives its new path", () => {
  // ` M` (changed in the working tree) starts with a space, which a trimmed reading loses with the path's first
  // character; `R ` is followed by the rename's source path.
  const porcelain = ' M core/kernel/src/main/kotlin/A.kt\0M  b.kt\0R  new.kt\0old.kt\0A  c.kt\0';
  assert.deepEqual(changedPaths(porcelain), ['core/kernel/src/main/kotlin/A.kt', 'b.kt', 'new.kt', 'c.kt']);
  assert.deepEqual(changedPaths(''), []);
});

test("a report's source roots are read against the repository, so a cached one names this checkout's", () => {
  const repository = join('/', 'work', 'checkout');
  assert.deepEqual(readRoots('core/runtime/src/main/kotlin\ncore/runtime/src/main/java\n', repository), [
    join(repository, 'core/runtime/src/main/kotlin'),
    join(repository, 'core/runtime/src/main/java'),
  ]);
  // An absolute root stays as it is.
  assert.deepEqual(readRoots(join('/', 'elsewhere', 'src'), repository), [join('/', 'elsewhere', 'src')]);
});
