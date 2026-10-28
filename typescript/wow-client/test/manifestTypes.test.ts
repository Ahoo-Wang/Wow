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
import { describe, expect, it } from 'vitest';
// @ts-expect-error the generator script is plain JavaScript, without declarations
import { manifestTypes, OUTPUT, SCHEMA } from '../scripts/manifest-types.mjs';

/**
 * The Manifest types are generated from the Manifest's own JSON Schema
 * (ManifestSchema of :manifest, published as META-INF/wow/manifest.schema.json),
 * the format's one description: a change to the format that leaves them behind
 * fails here until `node typescript/wow-client/scripts/manifest-types.mjs` runs.
 */
describe('the Manifest types', () => {
  it('should be what the Manifest schema generates', async () => {
    const schema = JSON.parse(readFileSync(SCHEMA, 'utf8'));
    expect(readFileSync(OUTPUT, 'utf8')).toBe(await manifestTypes(schema));
  });

  it('should open every enumeration, as a newer writer may add a value', async () => {
    const generated = await manifestTypes({
      title: 'T',
      type: 'object',
      required: ['kind'],
      properties: { kind: { type: 'string', enum: ['A'] } },
      $defs: {},
    });
    expect(generated).toContain("readonly kind: 'A' | (string & {});");
  });
});
