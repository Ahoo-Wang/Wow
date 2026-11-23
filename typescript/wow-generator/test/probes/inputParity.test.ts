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

/**
 * Which types a caller's input is declared for, and their names, from the
 * Manifest `edge/openapi`'s `InputComponentsTest` reads too: the TypeScript
 * clients and the OpenAPI document are two projections of one Manifest, so
 * they name the same types as inputs, the service's own type of an input's
 * name keeping it in both. The names differ by projection: `ProductRefInput2`
 * here, `me.ahoo.probe.ProductRefInput_2` there.
 */

import { readFileSync } from 'node:fs';
import { afterAll, describe, expect, it } from 'vitest';
import { removeDirectories } from '../support/generation';
import { generateCompiling } from '../support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const FIXTURE = new URL(
  '../../../../edge/openapi/src/test/resources/computed-inputs/manifest.json',
  import.meta.url,
);

describe('the types declared as inputs', () => {
  it('are those the OpenAPI document gives an input component', async () => {
    const { read } = await generateCompiling(
      JSON.parse(readFileSync(FIXTURE, 'utf8')),
    );
    const inputs = [
      ...read('probe/types.ts').matchAll(
        /What a caller sends for `\w+`[^]*?- key: `([^`]+)`\n \*\/\nexport interface (\w+)/g,
      ),
    ].map(([, key, name]) => [key, name]);
    expect(inputs).toEqual([
      ['me.ahoo.probe.Card', 'CardInput'],
      ['me.ahoo.probe.CreateOrder', 'CreateOrderInput'],
      ['me.ahoo.probe.ProductRef', 'ProductRefInput2'],
      ['me.ahoo.probe.Search', 'SearchInput'],
    ]);
    expect(read('probe/types.ts')).toContain(
      'export interface ProductRefInput {\n  raw: string;\n}',
    );
  });
});
