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

import type { FieldDefinition, Issue } from '../../model/index.js';
import { summaryFunctionKey } from '../display.js';
import { LAYOUT_LABEL } from '../ResultToolbar.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/** The families whose findings a record view shows. */
const NAMED_FAMILIES = ['record.', 'filter.'] as const;

/**
 * A record view's finding as a reader is told it, in the words the screen
 * uses — the analysis view's rule (`analysisIssueNamer`) for the other kind.
 * The kernel names what it has: a field's path, a summary and an operator as
 * the protocol spells them, a layout by its key. Only the catalogue knows
 * what those are called, so they are said here:
 *
 * - a field (`field`) by its label, as the column header and the filter's
 *   field picker say it; an element's field by the element's own label;
 * - a summary (`fn`) as the column's summary select says it, the latest of
 *   a moment 「最晚」;
 * - an operator (`operator`) as the condition's operator select says it;
 * - a layout (`layout`) as the layout switch says it.
 *
 * Anything the definition does not have is left as it is: it has no name on
 * screen, and the finding is usually that it is missing. Findings of every
 * other family pass through untouched.
 */
export function recordIssueNamer(
  fields: readonly FieldDefinition[],
  messages: MessageFormatters,
): (found: Issue) => Issue {
  const byName = new Map<string, FieldDefinition>();
  for (const field of fields) {
    byName.set(field.name, field);
    for (const element of field.elements ?? [])
      byName.set(`${field.name}.${element.name}`, element);
  }
  const labelled = (key: string, raw: string): string | undefined => {
    const said = messages.label(key as never, undefined, raw);
    return said === raw ? undefined : said;
  };
  return found => {
    if (!found.params || !NAMED_FAMILIES.some(f => found.code.startsWith(f)))
      return found;
    const params = { ...found.params };
    let named = false;
    const say = (key: string, said: string | undefined) => {
      if (said === undefined) return;
      params[key] = said;
      named = true;
    };
    const text = (key: string): string | undefined => {
      const value = params[key];
      return typeof value === 'string' ? value : undefined;
    };
    const path = text('field');
    const field = path === undefined ? undefined : byName.get(path);
    if (field) say('field', field.label);
    const fn = text('fn');
    if (fn !== undefined)
      say(
        'fn',
        labelled(
          summaryFunctionKey(fn as never, field?.cell ?? field?.kind),
          fn,
        ),
      );
    const operator = text('operator');
    if (operator !== undefined)
      say('operator', labelled(`label.operator.${operator}`, operator));
    const layout = text('layout');
    if (layout === 'table' || layout === 'card')
      say('layout', messages.label(LAYOUT_LABEL[layout]));
    return named ? { ...found, params } : found;
  };
}
