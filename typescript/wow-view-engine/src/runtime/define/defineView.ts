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

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type {
  DataViewDefinition,
  FieldDefinition,
  Issue,
  PagingMode,
  RecordCapability,
} from '../../model/index.js';
import { issue } from '../../filter/index.js';
import { analysisPlan, buildAnalysis } from './analysis.js';
import { buildFields, type BuiltField } from './fields.js';
import { narrowDefinition } from '../../capabilities/index.js';
import { constructible } from '../../capabilities/analysis.js';
import { builtinFieldKinds } from '../../filter/index.js';
import type { DefineViewSpec } from './spec.js';

/**
 * A data definition built from its source's descriptor (host-integration.md
 * 3, D67): the descriptor gives the facts — which paths there are, what
 * each is, what it sorts and feeds — as an upper bound, and `spec` picks,
 * names and narrows within it. A field not listed does not appear. A path
 * or a value the descriptor lacks is an error admission reports
 * (`DataViewDefinition.described`), never a throw: a definition wrong in
 * one place still loads, and says where. A capability asked beyond the
 * snapshot is a warning: what a path sorts and aggregates by is the
 * store's, and another store may grant it.
 *
 * Facts — paths, kinds, values, sensitivity, the entries of an array —
 * are the snapshot's. Capabilities are not baked in: where the host
 * narrows nothing the definition takes whatever its source grants, and
 * where it narrows, its subset of that (`DefinitionDescribed.open`,
 * filled by the narrowing from the source's descriptor). A source with
 * no descriptor runs on the snapshot's.
 *
 * `descriptor` is the snapshot committed beside the definition (Q2): the
 * definition is built at once, when the module loads, and a test builds
 * the same one. The descriptor the source answers at run time narrows it
 * again, as any definition is (N5).
 *
 * The result is an ordinary definition: nothing past this function knows
 * how it was written, and one written in full by hand is still one.
 */
export function defineView(
  descriptor: QueryModelDescriptor,
  spec: DefineViewSpec,
): DataViewDefinition {
  const findings: Issue[] = [];
  const record = recordOf(spec, descriptor, findings);
  const fields = buildFields(spec.fields, undefined, {
    descriptor,
    paging: record?.paging ?? 'paged',
    findings,
  });
  const definition: DataViewDefinition = {
    id: spec.id,
    title: spec.title,
    kind: 'data',
    source: spec.source,
    fields: fields.map(entry => entry.field),
  };
  if (spec.recordNoun !== undefined) definition.recordNoun = spec.recordNoun;
  if (spec.fieldGroups) definition.fieldGroups = spec.fieldGroups;
  if (record) definition.record = record;
  const plan = analysisPlan(spec.analysis, fields);
  const analysis = buildAnalysis(plan, fields, descriptor, findings);
  // A snapshot with no metric at all offers no analyses; a source that
  // grants some fills them in (`reopened`) rather than the definition
  // being refused for one store's word.
  if (analysis && constructible(analysis)) definition.analysis = analysis;
  if (spec.timeField !== undefined) definition.timeField = spec.timeField;
  if (spec.views) definition.views = spec.views;
  // What compares, sorts and aggregates is the store's: left to the source
  // where the host did not narrow it, filled from its descriptor when it
  // narrows (`reopened`), the snapshot's written here for a source that
  // has none.
  const sort = fields.flatMap(({ field, spec: given, described }) =>
    described && given.sortable !== false ? [field.name] : [],
  );
  const operators = openComparisons(fields);
  definition.fields = withSnapshotComparisons(
    definition.fields,
    narrowDefinition(definition, descriptor, builtinFieldKinds).definition
      .fields,
    new Set(operators),
  );
  definition.described = {
    version: descriptor.version,
    findings,
    open: { sort, operators, ...(plan ? { analysis: plan } : {}) },
  };
  return definition;
}

/**
 * The fields whose comparisons the host left open, by path: every field
 * but one whose operators the host wrote, one that compares nothing (a
 * confidential value), and a search box.
 */
function openComparisons(
  fields: readonly BuiltField[],
  scope?: string,
): string[] {
  return fields.flatMap(({ field, spec, children }) => {
    const path = scope === undefined ? field.name : `${scope}.${field.name}`;
    // A search box is the host's to ask for, as written operators are: on
    // a source with no descriptor it is offered as declared, and a source
    // that says whether it searches narrows it.
    const own =
      spec.operators === undefined &&
      field.operators === undefined &&
      field.kind !== 'search'
        ? [path]
        : [];
    return [...own, ...openComparisons(children ?? [], path)];
  });
}

/**
 * The fields with the snapshot's comparisons written on each one left
 * open: the operators the narrowing against it keeps on each path, so a
 * source with no descriptor offers what the snapshot's store does, and no
 * more.
 */
function withSnapshotComparisons(
  fields: readonly FieldDefinition[],
  narrowed: readonly FieldDefinition[],
  open: ReadonlySet<string>,
  scope?: string,
): FieldDefinition[] {
  return fields.map((field, index) => {
    const path = scope === undefined ? field.name : `${scope}.${field.name}`;
    const cut = narrowed[index];
    let next = field;
    if (open.has(path) && cut?.operators !== undefined)
      next = { ...next, operators: cut.operators };
    if (field.elements)
      next = {
        ...next,
        elements: withSnapshotComparisons(
          field.elements,
          cut?.elements ?? [],
          open,
          path,
        ),
      };
    return next;
  });
}

/** The descriptor's paging modes, as a definition names them. */
const PAGING: Readonly<Record<string, PagingMode>> = {
  PAGED: 'paged',
  CURSOR: 'cursor',
};

/**
 * Records: the host's choices over the descriptor's identity and paging —
 * paged where the model pages, else by cursor — shown as a table unless the
 * host says otherwise. A paging the model does not offer is asked beyond it.
 */
function recordOf(
  spec: DefineViewSpec,
  descriptor: QueryModelDescriptor,
  findings: Issue[],
): RecordCapability | undefined {
  if (spec.record === false) return undefined;
  const given = spec.record ?? {};
  const offered = descriptor.record.paging.flatMap(mode =>
    PAGING[mode] ? [PAGING[mode]] : [],
  );
  const paging =
    given.paging ?? (offered.includes('paged') ? 'paged' : 'cursor');
  if (!offered.includes(paging))
    findings.push(
      issue(
        'definition.record.paging-wider',
        ['record', 'paging'],
        { paging },
        'warning',
      ),
    );
  return {
    ...given,
    rowKey: given.rowKey ?? descriptor.record.identity,
    paging,
    layouts: given.layouts ?? ['table'],
  };
}
