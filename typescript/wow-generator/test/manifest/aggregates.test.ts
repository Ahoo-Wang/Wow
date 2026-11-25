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

import { describe, expect, it } from 'vitest';
import { analyzeManifest } from '../../src/manifest/analyze';
import type { ManifestDocument } from '../../src/manifest/document';

const ref = (key: string) => ({ $ref: `#/$defs/${key}` });
const object = (required: string[] = []) => ({
  type: 'object',
  properties: { a: { type: 'string' } },
  required,
});

/** Whether the body `a.Body` among `defs` is defaulted to `{}` in its method. */
function defaulted(defs: Record<string, unknown>): boolean {
  const manifest = {
    format: 1,
    contexts: [
      {
        name: 'probe',
        aggregates: [
          {
            name: 'thing',
            state: { schema: '#/$defs/a.State' },
            commands: [
              {
                name: 'Do',
                schema: '#/$defs/a.Body',
                routes: [{ method: 'POST', path: '/probe/thing/{id}/do' }],
              },
            ],
          },
        ],
      },
    ],
    $defs: { 'a.State': { type: 'object', properties: {} }, ...defs },
  } as unknown as ManifestDocument;
  return analyzeManifest(manifest).model.contexts[0].aggregates[0].commands[0]
    .bodyOptional;
}

describe("whether a command's body is defaulted to {}", () => {
  it('follows the alternatives through their references', () => {
    // A sealed body whose subtypes are definitions of their own.
    expect(
      defaulted({
        'a.Body': { anyOf: [ref('a.Card'), ref('a.Cash')] },
        'a.Card': object(['a']),
        'a.Cash': object(['a']),
      }),
    ).toBe(false);
    expect(
      defaulted({
        'a.Body': { anyOf: [ref('a.Card'), ref('a.Note')] },
        'a.Card': object(['a']),
        'a.Note': object(),
      }),
    ).toBe(true);
  });

  it('takes a reference it cannot follow, or one already on the path, as admitting it', () => {
    expect(defaulted({ 'a.Body': { allOf: [ref('a.Missing')] } })).toBe(true);
    expect(
      defaulted({
        'a.Body': { allOf: [ref('a.Loop')] },
        'a.Loop': { allOf: [ref('a.Loop')] },
      }),
    ).toBe(true);
  });

  it('never admits it for an alternative that is no object, or a constant', () => {
    expect(
      defaulted({
        'a.Body': {
          anyOf: [{ type: 'string' }, { const: 'x' }, { enum: ['y'] }],
        },
      }),
    ).toBe(false);
    expect(
      defaulted({ 'a.Body': { anyOf: [{ type: 'string' }, object()] } }),
    ).toBe(true);
  });
});

describe("a command's former names", () => {
  /** The routing and the model of the command `Do` whose `aliases` is `aliases`. */
  function namesOf(aliases: unknown) {
    const manifest = {
      format: 1,
      contexts: [
        {
          name: 'probe',
          aggregates: [
            {
              name: 'thing',
              state: { schema: '#/$defs/a.State' },
              commands: [
                {
                  name: 'Do',
                  aliases,
                  schema: '#/$defs/a.State',
                  routes: [{ method: 'POST', path: '/probe/thing/{id}/do' }],
                },
              ],
            },
          ],
        },
      ],
      $defs: { 'a.State': { type: 'object', properties: {} } },
    } as unknown as ManifestDocument;
    const aggregate = analyzeManifest(manifest).model.contexts[0].aggregates[0];
    return [aggregate.routing[0].aliases, aggregate.commands[0].aliases];
  }

  it('are the names its aliases lists, and none for anything else', () => {
    expect(namesOf(['Did', 2])).toEqual([['Did'], ['Did']]);
    expect(namesOf('Did')).toEqual([undefined, []]);
    expect(namesOf({ name: 'Did' })).toEqual([undefined, []]);
    expect(namesOf(undefined)).toEqual([undefined, []]);
  });
});
