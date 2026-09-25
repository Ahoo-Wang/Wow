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
import postcss from 'postcss';

/**
 * The built-in presets' sources and what the build makes of them
 * (themes.md 4.1, 5.6).
 *
 * Each preset is one source file, `src/themes/<name>.css`, holding its one
 * `:where([data-fve-preset='<name>'])` block and the notes on why each value
 * is what it is. `src/themes.css` is the index: its header states the rules
 * every preset keeps, and its `@import` lines name the presets in the order
 * they are listed everywhere — the Storybook toolbar, the gallery, the
 * contrast matrix and `BUILT_IN_PRESETS` (which `test/themeFiles.test.ts`
 * holds to this order). Storybook imports the index as it is and lets Vite
 * inline it; the package build calls `buildThemes` below.
 *
 * What ships is the values, not the notes: `themes.css`, every preset in the
 * index's order, for a host that switches at run time, and
 * `themes/<name>.css`, one preset alone, for a host that wears one — CSS has
 * no tree shaking, so the file is the unit a host can leave out. Both are
 * made from the same sources with the comments taken out, so a single file
 * is, rule for rule, its part of `themes.css`; `scripts/verify-package.mjs`
 * checks that and weighs them.
 */

/** `src/`, found beside this script when a caller does not say. */
const srcDir = () => join(import.meta.dirname, '..', 'src');

const IMPORT = /^@import\s+['"]\.\/themes\/([a-z][a-z0-9-]*)\.css['"];?$/;

/**
 * The presets the index imports, in its order, each with its source text.
 *
 * @param {string} [dir] the `src/` directory
 * @returns {{ name: string, path: string, text: string }[]}
 */
export function presetSources(dir = srcDir()) {
  const index = postcss.parse(readFileSync(`${dir}/themes.css`, 'utf8'));
  const presets = [];
  index.each(node => {
    if (node.type === 'comment') return;
    const line = node.toString().trim();
    const match = node.type === 'atrule' ? IMPORT.exec(`${line};`) : null;
    if (!match)
      throw new Error(
        `src/themes.css may hold only its header and @import './themes/<name>.css' lines, not ${line}`,
      );
    const path = `${dir}/themes/${match[1]}.css`;
    presets.push({ name: match[1], path, text: readFileSync(path, 'utf8') });
  });
  return presets;
}

/** Every preset's source, one after another: what Storybook's Vite inlines. */
export function themesSource(dir = srcDir()) {
  return presetSources(dir)
    .map(({ text }) => text)
    .join('\n');
}

/** A stylesheet with its comments taken out and one blank line between rules. */
function values(text) {
  const root = postcss.parse(text);
  root.walkComments(comment => {
    comment.remove();
  });
  root.each(node => {
    node.raws.before = '\n\n';
  });
  return `${root.toString().trim()}\n`;
}

/**
 * What the package ships: `themes.css` and one `themes/<name>.css` per preset.
 *
 * @param {string} [dir] the `src/` directory
 * @returns {Map<string, string>} file name in `dist` → its text
 */
export function buildThemes(dir = srcDir()) {
  const files = new Map();
  const presets = presetSources(dir);
  for (const { name, text } of presets)
    files.set(`themes/${name}.css`, values(text));
  files.set(
    'themes.css',
    presets.map(({ name }) => files.get(`themes/${name}.css`)).join('\n'),
  );
  return files;
}
