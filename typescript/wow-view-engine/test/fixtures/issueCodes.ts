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
import { en } from '../../src/ui/messages/en.js';

const src = join(import.meta.dirname, '../../src');
const messages = join(src, 'ui/messages');

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
 * Catalogue keys a source file names that are not codes: the reasons a
 * chart cannot draw (`ChartUnfit`), which travel as the `reason` param of
 * `chart.as-table` and are said through `label()`, never as an issue's
 * `code`.
 */
const NOT_CODES = /^chart\.fit\./;

/** A plural form (`…-one`), which the count picks and no caller raises. */
const PLURAL = /^(.+)-(?:zero|one|two|few|many)$/;

/**
 * Whether a key of the English catalogue can be an issue's `code`: every
 * key outside `label.*` is one, bar a plural form of another key and the
 * reasons above (`ViewMessages`: "Two namespaces share one flat map").
 */
function codeKey(key: string): boolean {
  if (key.startsWith('label.') || NOT_CODES.test(key)) return false;
  const general = PLURAL.exec(key)?.[1];
  return !(general !== undefined && general in en);
}

/**
 * Every issue code raised anywhere in the package, in three ways:
 *
 * - written out in an `issue('…')` call;
 * - built from a template over one of the closed sets above;
 * - handed to a helper that raises it — `found` in `defineView`, a
 *   dashboard click's `warn`, a table of codes (`BUDGET_ISSUE_CODES`,
 *   a chart's `codes`), the base a failed command passes `toIssue` — which
 *   no call-site pattern can follow, so these are read off the other side:
 *   a string the source writes that the catalogue words as a code
 *   (`codeKey`). A failed command's code may carry what the store or the
 *   write said after a dot (`view.open.failed.not_found`, `issues.ts`'s
 *   `commandIssue`), so a catalogue key that extends a code so is one too.
 *
 * `unresolved` names a template this file has no set for — a code nobody
 * can check, which is the same gap in a newer place.
 */
export function raisedCodes(): { codes: string[]; unresolved: string[] } {
  const codes = new Set<string>();
  const unresolved = new Set<string>();
  // Any dotted string a source file writes, for the third way.
  const written = /['`]([A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z0-9_-]+)+)['`]/g;
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
        // A catalogue naming its own keys is not a use.
        if (path !== messages) walk(path);
        continue;
      }
      if (!path.endsWith('.ts') && !path.endsWith('.tsx')) continue;
      const source = readFileSync(path, 'utf8');
      for (const match of source.matchAll(pattern)) codes.add(match[1]);
      for (const [, key] of source.matchAll(written))
        if (key in en && codeKey(key)) codes.add(key);
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
  const raised = [...codes];
  for (const key of Object.keys(en))
    if (codeKey(key) && raised.some(code => key.startsWith(`${code}.`)))
      codes.add(key);
  return { codes: [...codes].sort(), unresolved: [...unresolved].sort() };
}
