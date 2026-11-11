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

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The stylesheet's sources (ui/theme.md「三层变量」).
 *
 * `src/styles.css` is the entry: the overview, the layers, the libraries'
 * imports, one `@import './styles/<name>.css'` a part, and what Tailwind
 * scans. Each part is one responsibility — the tokens, the brand colour's
 * derivation, the roles on the vendored components, paper, forced colours,
 * the layouts — and the parts are in the order their rules were written as
 * one file, which is what decides between two rules of one weight. Tailwind
 * inlines them where it compiles the entry, so what ships is one
 * `styles.css`, as before.
 *
 * Whatever reads the stylesheet whole — the theme's resolver, the suites
 * that hold it to the registry, the token rules the build ships for
 * `theme-check` — reads `stylesSource()`: the entry with each part in the
 * place of its import.
 */

/** `src/`, found beside this script when a caller does not say. */
const srcDir = () => join(import.meta.dirname, '..', 'src');

/** One part's import, a line of its own in the entry. */
const partImport = () =>
  /^@import\s+['"]\.\/styles\/([a-z][a-z0-9-]*)\.css['"];$/gm;

/**
 * The parts the entry imports, in its order, each with its source text.
 *
 * @param {string} [dir] the `src/` directory
 * @returns {{ name: string, path: string, text: string }[]}
 */
export function stylesParts(dir = srcDir()) {
  const entry = readFileSync(`${dir}/styles.css`, 'utf8');
  return [...entry.matchAll(partImport())].map(([, name]) => {
    const path = `${dir}/styles/${name}.css`;
    return { name, path, text: readFileSync(path, 'utf8') };
  });
}

/**
 * The stylesheet as one text: the entry, each part in the place of its
 * import.
 *
 * @param {string} [dir] the `src/` directory
 * @returns {string}
 */
export function stylesSource(dir = srcDir()) {
  const entry = readFileSync(`${dir}/styles.css`, 'utf8');
  return entry.replace(partImport(), (_, name) =>
    readFileSync(`${dir}/styles/${name}.css`, 'utf8'),
  );
}
