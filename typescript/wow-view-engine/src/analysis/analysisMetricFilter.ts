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
  FilterOperator as Op,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import { compileProtocolNode } from '../filter/filterBuiltinCompiler.js';
import { definition } from '../filter/filterOperators.js';
import { parseFilterOutput } from '../filter/filterProtocol.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import type { AnalysisCompileContext } from './analysisModel.js';
export function analysisMetricFilterContext(
  context: AnalysisCompileContext,
  element = false,
): AnalysisCompileContext {
  const allowed = Object.values(Op).filter(
    op =>
      op !== Op.SEARCH &&
      op !== Op.ELEMENT_MATCH &&
      (!element ||
        definition(op).category !== 'root' ||
        op === Op.MATCH_ALL ||
        op === Op.MATCH_NONE),
  );
  return {
    ...context,
    fields: context.fields.filter(f =>
      ['string', 'number', 'boolean', 'date', 'datetime'].includes(
        f.type ?? '',
      ),
    ),
    allowedOperators: allowed.filter(
      op => !context.allowedOperators || context.allowedOperators.includes(op),
    ),
  };
}
export function compileAnalysisMetricFilter(
  expression: FilterExpression,
  context: AnalysisCompileContext,
  element = false,
): FilterExpression {
  validateFilterJson(expression);
  const scoped = analysisMetricFilterContext(context, element);
  const draft = parseFilterOutput(expression);
  const compiled = compileProtocolNode(
    draft,
    scoped.fields,
    scoped.allowedOperators,
    scoped.timeZone,
  );
  if (compiled.errors.length)
    throw new TypeError(compiled.errors.map(error => error.message).join('；'));
  return compiled.expression!;
}
