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
 * The analyses of a `defineView` definition: which fields analyse and how
 * the host narrows each is the host's (the plan, `OpenCapabilities`); what
 * each path offers is its source's. Built here over the snapshot — what a
 * source with no descriptor runs on — and read again over a source's own
 * descriptor when it narrows (`reopened`). A narrowing beyond what the
 * snapshot grants is a warning, not an error: another store may grant it.
 */

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type {
  AnalysisCapability,
  AnalysisSpec,
  FieldAnalysisSpec,
  Issue,
  OpenCapabilities,
} from '../../model/index.js';
import { issue } from '../../filter/index.js';
import { describedAnalysis } from '../../capabilities/open.js';
import type { BuiltField } from './fields.js';

/** Which fields analyse, and the host's narrowing of each; `undefined` for none. */
export function analysisPlan(
  spec: AnalysisSpec | false | undefined,
  fields: readonly BuiltField[],
): OpenCapabilities['analysis'] {
  if (spec === false) return undefined;
  const narrowed = (entry: BuiltField): FieldAnalysisSpec | null =>
    entry.spec.analysis === false ? null : (entry.spec.analysis ?? {});
  const plan: NonNullable<OpenCapabilities['analysis']> = {
    spec: spec ?? {},
    fields: {},
    elements: {},
  };
  for (const entry of fields) {
    const own = entry.described ? narrowed(entry) : null;
    if (!own) continue;
    if (entry.children) {
      const entries: Record<string, FieldAnalysisSpec> = {};
      for (const child of entry.children) {
        const one = narrowed(child);
        if (one) entries[child.field.name] = one;
      }
      (plan.elements as Record<string, typeof entries>)[entry.field.name] =
        entries;
    } else
      (plan.fields as Record<string, FieldAnalysisSpec>)[entry.field.name] =
        own;
  }
  return plan;
}

/** The plan's analyses over the snapshot, what it asks beyond said as warnings. */
export function buildAnalysis(
  plan: OpenCapabilities['analysis'],
  fields: readonly BuiltField[],
  descriptor: QueryModelDescriptor,
  findings: Issue[],
): AnalysisCapability | undefined {
  if (!plan) return undefined;
  return describedAnalysis(
    plan,
    fields.map(entry => entry.field),
    descriptor,
    (field, what) =>
      findings.push(
        field === null
          ? issue(
              'definition.analysis.wider',
              ['analysis'],
              { what },
              'warning',
            )
          : issue(
              'definition.field.analysis-wider',
              ['analysis'],
              { field, what },
              'warning',
            ),
      ),
  );
}
