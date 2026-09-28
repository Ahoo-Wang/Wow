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
 * The capabilities a `defineView` definition leaves to its source
 * (host-integration.md 3, D67): what a path sorts and aggregates by is the
 * store's — the same model on MongoDB and on Elasticsearch offers different
 * ones — so where the host narrows nothing a definition takes whatever the
 * source's descriptor grants, and where it narrows, its subset of that.
 *
 * `describedAnalysis` is that reading, from one descriptor: `defineView`
 * runs it over the committed snapshot, which is what a source with no
 * descriptor runs on; `reopened` runs it over a source's own descriptor
 * before narrowing (`narrowDefinition`), so this file and the narrowing
 * stay the one place a descriptor is read.
 */

import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  type FieldDescriptor,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import {
  TEMPORAL_FIELD_KIND_IDS,
  without,
  type AggregationFieldCapability,
  type AnalysisCapability,
  type AnalysisDateDiffUnit,
  type AnalysisElementCapability,
  type DataViewDefinition,
  type FieldAnalysisSpec,
  type FieldDefinition,
  type OpenCapabilities,
} from '../model/index.js';
import { describedField } from './match.js';

const GROUPS: readonly string[] = Object.values(AggregationGroupType);
const FUNCTIONS: readonly string[] = Object.values(AggregationFunction);
const UNITS: readonly string[] = Object.values(AggregationDateUnit);
const PARTS: readonly string[] = Object.values(AggregationDatePart);
const MOMENT_GROUPS: readonly string[] = [
  AggregationGroupType.DATE_HISTOGRAM,
  AggregationGroupType.DATE_PART,
];
const MOMENT_FUNCTIONS: readonly string[] = [
  AggregationFunction.MIN,
  AggregationFunction.MAX,
];
const DIFF_UNITS: readonly AnalysisDateDiffUnit[] = [
  'SECOND',
  'MINUTE',
  'HOUR',
  'DAY',
];

/** The flags a path's aggregate offers, and what leaving one out means. */
const FLAGS = [
  ['any', 'optIn'],
  ['distinctCount', 'optIn'],
  ['percentile', 'optIn'],
  ['firstLast', 'optIn'],
  ['expressionInput', 'optOut'],
  ['missingKey', 'optOut'],
  ['inMetricFilter', 'optOut'],
] as const;

/**
 * Where a narrowing asks for more than the descriptor grants: the field
 * (`null` for the analyses as a whole) and what.
 */
export type Wider = (field: string | null, what: string) => void;

/** The analyses the plan offers over `descriptor`; `undefined` for none. */
export function describedAnalysis(
  plan: NonNullable<OpenCapabilities['analysis']>,
  fields: readonly FieldDefinition[],
  descriptor: QueryModelDescriptor,
  wider: Wider = () => {},
): AnalysisCapability {
  const { spec } = plan;
  const offered = descriptor.analysis;
  const choose = (wanted: boolean | undefined, has: boolean, what: string) => {
    if (wanted === true && !has) wider(null, what);
    return has && wanted !== false;
  };
  const kindOf = (name: string, within = fields) =>
    within.find(field => field.name === name)?.kind ?? 'string';

  const capability: AnalysisCapability = {
    count: choose(spec.count, offered.metrics.includes('COUNT'), 'count'),
    fields: Object.entries(plan.fields).flatMap(([path, narrowed]) => {
      const one = aggregation(path, kindOf(path), narrowed, descriptor, wider);
      return one ? [one] : [];
    }),
  };
  const elements = Object.entries(plan.elements).flatMap(
    ([path, entries]): AnalysisElementCapability[] => {
      const element = descriptor.elements.find(one => one.path === path);
      if (!element?.aggregate) return [];
      const within = fields.find(field => field.name === path)?.elements ?? [];
      const aggregations = Object.entries(entries).flatMap(
        ([name, narrowed]) => {
          const one = aggregation(
            name,
            kindOf(name, within),
            narrowed,
            descriptor,
            wider,
            path,
          );
          return one ? [one] : [];
        },
      );
      return aggregations.length > 0 ? [{ path, aggregations }] : [];
    },
  );
  if (elements.length > 0) capability.elements = elements;
  if (choose(spec.expressions, offered.expressions, 'expressions')) {
    capability.expressions = true;
    const units = DIFF_UNITS.filter(unit =>
      (offered.dateDiffUnits as readonly string[]).includes(unit),
    );
    for (const unit of spec.dateDiffUnits ?? [])
      if (!units.includes(unit)) wider(null, unit);
    capability.dateDiffUnits = (spec.dateDiffUnits ?? units).filter(unit =>
      units.includes(unit),
    );
  }
  if (choose(spec.having, offered.having.metrics.length > 0, 'having'))
    capability.having = true;
  if (spec.limits) capability.limits = spec.limits;
  return capability;
}

/**
 * One field's analysis over a descriptor: its path's aggregate cut to what
 * the engine knows, narrowed where the host says. `null` for a path that
 * feeds none or a sensitive one (a masked value never groups).
 */
function aggregation(
  name: string,
  kind: string,
  narrowed: FieldAnalysisSpec,
  descriptor: QueryModelDescriptor,
  wider: Wider,
  scope?: string,
): AggregationFieldCapability | null {
  const path = scope === undefined ? name : `${scope}.${name}`;
  const described = describedField(descriptor, path, scope);
  const aggregate = described?.aggregate;
  if (!aggregate || sensitiveAt(descriptor, path, scope)) return null;
  const pick = <T extends string>(
    wanted: readonly T[] | undefined,
    has: readonly string[],
  ): T[] => {
    for (const one of wanted ?? []) if (!has.includes(one)) wider(path, one);
    return (wanted ?? (has as readonly T[])).filter(one => has.includes(one));
  };

  // A moment is kept as a number, so its path offers what a number's does;
  // what it means is a time, which buckets by the calendar and has an
  // earliest and a latest, and no sum. Nothing else buckets by the
  // calendar.
  const moment = TEMPORAL_FIELD_KIND_IDS.includes(kind);
  const groups = pick(
    narrowed.groups,
    aggregate.groups.filter(
      one => GROUPS.includes(one) && moment === MOMENT_GROUPS.includes(one),
    ),
  );
  const functions = pick(
    narrowed.functions,
    aggregate.functions.filter(
      one =>
        FUNCTIONS.includes(one) && (!moment || MOMENT_FUNCTIONS.includes(one)),
    ),
  );
  const capability: AggregationFieldCapability = {
    field: name,
    groups,
    functions,
  };
  if (groups.includes(AggregationGroupType.DATE_HISTOGRAM))
    capability.dateUnits = pick(
      narrowed.dateUnits,
      descriptor.analysis.dateUnits.filter(one => UNITS.includes(one)),
    );
  if (groups.includes(AggregationGroupType.DATE_PART) && narrowed.dateParts)
    capability.dateParts = pick(
      narrowed.dateParts,
      descriptor.analysis.dateParts.filter(one => PARTS.includes(one)),
    );
  for (const [flag, sense] of FLAGS) {
    const has = aggregate[flag];
    const wanted = narrowed[flag];
    if (wanted === true && !has) wider(path, flag);
    const on = has && wanted !== false;
    if (sense === 'optIn' && on) capability[flag] = true;
    if (sense === 'optOut' && !on) capability[flag] = false;
  }
  if (narrowed.steps) capability.steps = true;
  const metrics =
    groups.length > 0 ||
    functions.length > 0 ||
    FLAGS.some(([flag, sense]) => sense === 'optIn' && capability[flag]);
  return metrics ? capability : null;
}

/** Whether a path's value is masked at the source (`sensitivity`). */
export function sensitiveAt(
  descriptor: QueryModelDescriptor,
  path: string,
  scope?: string,
): boolean {
  const own: FieldDescriptor | undefined = descriptor.fields.find(
    entry => entry.path === path && entry.scope === scope,
  );
  return own?.sensitivity !== undefined;
}

/** Whether a root path sorts at the source, by the paging in force. */
export function sortsAt(
  descriptor: QueryModelDescriptor,
  path: string,
  paging: 'paged' | 'cursor',
): boolean {
  const described = describedField(descriptor, path);
  if (!described || described.comparable === false) return false;
  return paging === 'cursor' ? described.sort.cursor : described.sort.paged;
}

/**
 * A `defineView` definition with what it leaves to its source read from
 * `descriptor` rather than the snapshot it was built from: each open sort
 * as that source sorts, the analyses as it aggregates within the host's
 * narrowing. Anything else — and a definition written by hand — as it is.
 */
export function reopened(
  definition: DataViewDefinition,
  descriptor: QueryModelDescriptor,
): DataViewDefinition {
  const open = definition.described?.open;
  if (!open) return definition;
  const paging = definition.record?.paging ?? 'paged';
  const sorted = new Set(open.sort);
  const fields = definition.fields.map(field => {
    if (!sorted.has(field.name)) return field;
    return sortsAt(descriptor, field.name, paging)
      ? { ...field, sortable: true }
      : without(field, 'sortable');
  });
  const next: DataViewDefinition = { ...definition, fields };
  if (open.analysis)
    next.analysis = describedAnalysis(open.analysis, fields, descriptor);
  return next;
}
