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

import { readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { scanWireLiteralsApart } from '../../../wow-view-engine/scripts/wire-literals.mjs';

const here = dirname(fileURLToPath(import.meta.url));

function stories(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return stories(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

describe('the view engine stories', () => {
  // One type-checked scan of the stories, apart from coverage: ~3 s here, ~10 s on a CI runner.
  it(
    'read every Wow wire value off wow-client, as a host does (D353)',
    { timeout: 30_000 },
    () => {
      // A story is a host's code: it writes `FilterOperator.EQ`, not 'EQ', so
      // when Wow respells its values (D352) the stories follow wow-client. A
      // story about the JSON shape itself says so with the marker.
      // A literal is judged by the type it is checked against: Wow's values
      // are lower-kebab, the words a story uses for its own things too.
      const literals = scanWireLiteralsApart({
        tsconfig: join(here, '../tsconfig.json'),
        scopes: { stories: { files: stories(here), honourMarker: true } },
      }).stories.map(
        ({ path, value, line }) => `${relative(here, path)}:${line} '${value}'`,
      );
      expect(literals).toEqual([]);
    },
  );
});
