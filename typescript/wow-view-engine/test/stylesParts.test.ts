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

import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import postcss from 'postcss';
import { describe, expect, it } from 'vitest';
import { stylesParts, stylesSource } from '../scripts/styles.mjs';

const SRC = join(import.meta.dirname, '..', 'src');

/**
 * `src/styles.css` is an entry over its parts (`scripts/styles.mjs`): what
 * ships is the parts in the entry's order, so a part the entry leaves out is
 * rules that silently stop shipping, and one read whole must hold no import
 * of a part still to be resolved.
 */
describe('the stylesheet’s parts', () => {
  it('are every file of src/styles, each imported once', () => {
    const imported = stylesParts().map(({ name }) => `${name}.css`);
    expect([...imported].sort()).toEqual(
      readdirSync(join(SRC, 'styles')).sort(),
    );
    expect(new Set(imported).size).toBe(imported.length);
  });

  it('read whole, leave no part’s import behind', () => {
    const imports: string[] = [];
    postcss.parse(stylesSource()).walkAtRules('import', rule => {
      imports.push(rule.params);
    });
    expect(imports.filter(params => params.includes('./styles/'))).toEqual([]);
  });

  it('hold every rule: the entry itself declares none', () => {
    const rules: string[] = [];
    postcss
      .parse(readFileSync(join(SRC, 'styles.css'), 'utf8'))
      .walkRules(rule => {
        rules.push(rule.selector);
      });
    expect(rules).toEqual([]);
  });
});
