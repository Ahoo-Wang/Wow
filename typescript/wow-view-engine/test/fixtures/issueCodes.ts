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

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { QueryErrorCodes } from '@ahoo-wang/wow-client';
import { VIEW_STORE_ERROR_CODES } from '../../src/index.js';
import { INSTANCE_ACTIONS } from '../../src/runtime/permissions.js';

const src = join(import.meta.dirname, '../../src');

/**
 * What a substitution inside a code template stands for.
 *
 * A code is not always written out: `view.${action}.forbidden` is three
 * codes, and each of them reaches the screen as a key unless the catalogue
 * names it — which is exactly how `view.save.forbidden`,
 * `view.rename.forbidden` and `view.delete.forbidden` came to be missing
 * while `view.create.forbidden` sat beside them (B1). So every value a
 * substitution can take is listed here and spliced into the template's
 * literal parts, the way `askedFor()` collects the prefix half.
 *
 * The sets are the source's own, imported rather than retyped: a member
 * added to either fails this suite before its code reaches anyone.
 * `label.*` templates are left to `askedFor()` — those are keys the source
 * asks the catalogue for, not codes it raises.
 */
const TEMPLATED: Record<string, readonly string[]> = {
  action: INSTANCE_ACTIONS,
  'error.code.toLowerCase()': VIEW_STORE_ERROR_CODES.map(code =>
    code.toLowerCase(),
  ),
  // The rules a Wow service names a rejected query by (D40); an open list,
  // so the ones this package words are wow-client's, and a newer one falls
  // back along the dots.
  'violation.code.toLowerCase()': Object.values(QueryErrorCodes).map(code =>
    code.toLowerCase(),
  ),
};

/**
 * Every issue code raised anywhere in the package: written out, or built
 * from a template over one of the closed sets above. `unresolved` names a
 * template this file has no set for — a code nobody can check, which is
 * the same gap in a newer place.
 */
export function raisedCodes(): { codes: string[]; unresolved: string[] } {
  const codes = new Set<string>();
  const unresolved = new Set<string>();
  // Codes carry camelCase segments (chart.splitBy, analysis.distinctCount),
  // so the class must not stop at lowercase.
  const pattern = /issue\(\s*['`]([A-Za-z][A-Za-z0-9.-]*)['`]/g;
  // A dotted head, one substitution, and whatever literal follows it. The
  // head may not be `label.`, which is the other direction's business.
  const template = /`(?!label\.)([a-z][\w.-]*\.)\$\{([^}`]+)\}([\w.-]*)`/g;

  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory)) {
      const path = join(directory, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
        continue;
      }
      if (!path.endsWith('.ts') && !path.endsWith('.tsx')) continue;
      const source = readFileSync(path, 'utf8');
      for (const match of source.matchAll(pattern)) codes.add(match[1]);
      for (const [whole, prefix, substitution, suffix] of source.matchAll(
        template,
      )) {
        const values = TEMPLATED[substitution.trim()];
        if (!values) {
          unresolved.add(whole);
          continue;
        }
        for (const value of values) codes.add(`${prefix}${value}${suffix}`);
      }
    }
  };

  walk(src);
  return { codes: [...codes].sort(), unresolved: [...unresolved].sort() };
}
