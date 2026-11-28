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
 * `checkManifest` against the table every Manifest reader holds to: a marker
 * it does not understand refuses the whole Manifest, wherever it stands,
 * naming the level, the node and the markers.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { checkManifest, type RoutingManifest } from '../src/manifest.js';

/** The table of `api/manifest`, which the Kotlin and the generator's readers hold to too. */
const CASES = join(
  import.meta.dirname,
  '../../../api/manifest/src/test/resources/manifest-requires-cases.json',
);

interface RequiresCase {
  readonly name: string;
  readonly manifest: RoutingManifest;
  readonly refused: string;
}

const { cases } = JSON.parse(readFileSync(CASES, 'utf8')) as {
  cases: readonly RequiresCase[];
};

describe('checkManifest of a marker it does not understand', () => {
  it.each(cases.map(entry => [entry.name, entry] as const))(
    'refuses one on %s, naming where it stands',
    (_, entry) => {
      expect(() => checkManifest(entry.manifest)).toThrow(
        `${entry.refused}, which this client does not understand: use a newer @ahoo-wang/wow-client.`,
      );
    },
  );

  it('accepts a Manifest whose every level requires nothing', () => {
    const manifest: RoutingManifest = {
      format: 1,
      requires: [],
      contexts: [
        {
          name: 'shop',
          requires: [],
          aggregates: [{ name: 'cart', requires: [] }],
        },
        { name: 'billing' },
      ],
    };
    expect(checkManifest(manifest)).toBe(manifest);
  });
});
