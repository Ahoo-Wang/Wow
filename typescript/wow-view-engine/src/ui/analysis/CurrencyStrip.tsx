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

import { groupOfType } from '../../analysis/index.js';
import type { Issue } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { useViewMessages } from '../MessagesProvider.js';
import { dedupeIssues, StatusStrip } from '../StatusStrip.js';
import { freeAlias, usedAliases } from './editing.js';

/**
 * What a result says about its money, beside the rows it is about
 * (`currencyIssues`): some groups hold records in several currencies, or
 * the source could not tell. Both are answered the same way — grouping by
 * the currency field puts each currency in rows of its own — so the line
 * offers exactly that, and runs it: a reader looking at a blank where a
 * total should be wants the totals, not a tray to find them in.
 *
 * `issues` are already named for the screen; the raw currency field rides
 * in `currencyField`. The offer is left out where the field cannot be a
 * dimension here, or already is one.
 */
export function CurrencyStrip({
  issues,
  analysis,
}: {
  issues: readonly Issue[];
  analysis: AnalysisEditorController;
}) {
  const messages = useViewMessages();
  const found = dedupeIssues(issues);
  if (found.length === 0) return null;
  const sentences = found.map(one => messages.issue(one));
  const path = found
    .map(one => one.params?.currencyField)
    .find((value): value is string => typeof value === 'string');
  const field = analysis.fields.find(entry => entry.field === path);
  const offered =
    field !== undefined &&
    field.groups.includes('TERMS') &&
    !analysis.groups.some(group => group.field === field.field);
  return (
    <StatusStrip
      tone="warning"
      title={
        sentences.length === 1
          ? sentences[0]
          : messages.label('label.view.warnings-count', {
              count: sentences.length,
            })
      }
      details={sentences.length === 1 ? undefined : sentences}
      action={
        offered && (
          <Button
            variant="outline"
            size="xs"
            data-slot="group-by-currency"
            onClick={() => {
              analysis.addGroup(
                groupOfType(
                  field,
                  'TERMS',
                  freeAlias(field.field, usedAliases(analysis)),
                ),
              );
              analysis.submit();
            }}
          >
            {messages.label('label.analysis.group-by-currency', {
              field: field.label,
            })}
          </Button>
        )
      }
    />
  );
}
