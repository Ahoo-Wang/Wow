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

import {
  currencyPathOf,
  type DataViewDefinition,
  type SummaryFunction,
} from '../model/index.js';
import { summaryAlias } from './compile.js';

/**
 * For a summary of money whose currency each record holds, what tells the
 * currency of its total: the currency field, and the aliases of its distinct
 * count and one of its values, which the total query asks beside it
 * (`compileSummaries`). Asked only where the source can answer both — the
 * analysis capability admits them on that field — else `undefined`, and the
 * total reads in no currency. A count is in none.
 */
export function summaryCurrency(
  definition: DataViewDefinition,
  summary: { field: string; fn: SummaryFunction },
): { field: string; code: string; count: string } | undefined {
  if (summary.fn === 'COUNT') return undefined;
  const field = definition.fields.find(entry => entry.name === summary.field);
  const currency = field && currencyPathOf(field);
  if (currency === undefined) return undefined;
  const aggregation = definition.analysis?.fields.find(
    entry => entry.field === currency,
  );
  if (aggregation?.any !== true || aggregation.distinctCount !== true)
    return undefined;
  const alias = summaryAlias(summary.field, summary.fn);
  return {
    field: currency,
    code: `${alias}__currency`,
    count: `${alias}__currencies`,
  };
}
