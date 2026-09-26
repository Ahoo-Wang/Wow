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

import type {
  QueryModelDescriptor,
  QuerySemanticType,
} from '@ahoo-wang/wow-client';
import {
  CURRENCY_CODE_PATTERN,
  DEFAULT_SEARCH_MODE,
  isFieldName,
  MAX_NUMERIC_SCALE,
  isFieldlessKind,
  temporalOf,
  TEMPORAL_FIELD_KIND_IDS,
  without,
  type FieldDefinition,
  type FieldNumeric,
  type FieldTemporal,
  type FilterOperatorName,
  type Issue,
  type IssuePath,
  type PagingMode,
  type SummaryFunction,
} from '../model/index.js';
import {
  issue,
  operatorsOf,
  type FieldKind,
  type FieldKindRegistry,
} from '../filter/index.js';
import { describedField, type DescribedField } from './match.js';

/** What narrowing the fields reads, and where it puts what it finds. */
export interface FieldContext {
  descriptor: QueryModelDescriptor;
  kinds: FieldKindRegistry;
  /** The paging the definition's record view declares, which picks the sort. */
  paging: PagingMode | undefined;
  /** Each field named by an alias, by that alias: its canonical path. */
  renamed: Record<string, string>;
  /**
   * The paths whose stored `null` or empty value reads as missing
   * (`NULL_OR_EMPTY_AS_MISSING`, #3515).
   */
  emptyIsMissing: ReadonlySet<string>;
  findings: Issue[];
}

/** The constraint that makes `STARTS_WITH` a case-sensitive prefix only. */
const STARTS_WITH_REQUIRES_PREFIX = 'STARTS_WITH_REQUIRES_PREFIX';

/**
 * The definition's fields as the descriptor admits them (capabilities.md
 * 4.1, 4.2): operators cut to what each path admits, a sort the path does
 * not take turned off, summaries cut to the metrics it feeds, and a search
 * or metadata condition the model does not offer taken away. Nothing is
 * added, and every field stays: a column reads the row, not the
 * descriptor, so a field the descriptor does not list still shows.
 */
export function narrowFields(
  fields: readonly FieldDefinition[],
  context: FieldContext,
): FieldDefinition[] {
  return fields.map((field, index) =>
    narrowField(field, ['fields', index], undefined, context),
  );
}

function narrowField(
  field: FieldDefinition,
  at: IssuePath,
  scope: string | undefined,
  context: FieldContext,
): FieldDefinition {
  if (field.kind === 'search') return narrowSearch(field, at, scope, context);
  const kind = context.kinds.get(field.kind);
  // Admission already refused a kind nobody registered; nothing to narrow.
  if (!kind) return field;
  if (isFieldlessKind(field.kind, kind))
    return narrowOperators(
      field,
      kind,
      new Set(context.descriptor.record.rootOperators),
      at,
      context,
    );

  const named = scope === undefined ? field.name : `${scope}.${field.name}`;
  const described = describedField(context.descriptor, named, scope);
  // Named by an alias: renamed to the path the source answers by, so its
  // column reads what comes back (#3519). A view that saved the alias is
  // read under the path too (`withCanonicalNames`).
  const canonical = described?.canonical;
  const path = canonical ?? named;
  if (canonical !== undefined) {
    context.findings.push(
      issue(
        'capability.field.alias',
        at,
        { field: named, path: canonical },
        'note',
      ),
    );
    context.renamed[named] = canonical;
    field = {
      ...field,
      name: scope === undefined ? canonical : canonical.slice(scope.length + 1),
    };
  }
  if (!described) {
    context.findings.push(
      warn(issue('capability.field.unknown', at, { field: path })),
    );
    return withoutQuery(field);
  }

  // A protected field keeps its column and loses every comparison: said
  // once, as what it is, rather than as an operator list and a sort that
  // went missing (#3519).
  const protectedField = described.comparable === false;
  if (protectedField)
    context.findings.push(
      warn(issue('capability.field.protected', at, { field: path })),
    );
  const quiet: FieldContext = protectedField
    ? { ...context, findings: [] }
    : context;
  let next = narrowOperators(
    field,
    kind,
    withDuration(
      admittedOperators(field, path, described, context),
      scope,
      described,
      context,
    ),
    at,
    quiet,
  );
  if (field.elements)
    next = {
      ...next,
      elements: field.elements.map((element, index) =>
        narrowField(element, [...at, 'elements', index], path, context),
      ),
    };
  // The element whose fields differ by variant, and the field that names
  // each element's variant (#3519): a condition on a variant's field is
  // held to the elements of that variant when it is compiled.
  const variants = context.descriptor.variants;
  if (variants && variants.element === path)
    next = { ...next, variantKey: variants.discriminator };
  if (described.variants) next = { ...next, variants: described.variants };
  next = narrowSort(next, path, described, at, quiet);
  if (described.deprecated) {
    const message = described.deprecated.message;
    context.findings.push(
      warn(
        message === undefined
          ? issue('capability.field.deprecated', at, { field: path })
          : issue('capability.field.deprecated-because', at, {
              field: path,
              reason: message,
            }),
      ),
    );
    next = {
      ...next,
      deprecated: message === undefined ? {} : { message },
    };
  }
  if (context.emptyIsMissing.has(path))
    next = { ...next, emptyIsMissing: true };
  const numeric = numericOf(described.semantic);
  if (numeric && next.numeric === undefined) next = { ...next, numeric };
  if (described.project === false) {
    context.findings.push(
      warn(issue('capability.field.not-projectable', at, { field: path })),
    );
    next = { ...next, projectable: false };
  }
  next = narrowSummary(next, path, described, at, context);
  checkTemporal(field, path, described.semantic, at, context);
  checkOptions(field, path, described, at, context);
  return next;
}

/**
 * A time since another moment (N3) is no operator of the field's own: the
 * entry offers it as a root `EXPRESSION` filter, over two times it can read
 * as moments (`aggregate.groups` lists `DATE_HISTOGRAM`), and lists it only
 * where expensive operators are on. So a root time field keeps it exactly
 * where the entry's root operators name it; an element's never does, Wow
 * keeping it out of `ELEMENT_MATCH`.
 */
function withDuration(
  admitted: ReadonlySet<string>,
  scope: string | undefined,
  described: DescribedField,
  context: FieldContext,
): ReadonlySet<string> {
  if (
    scope !== undefined ||
    !context.descriptor.record.rootOperators.includes('EXPRESSION' as never) ||
    !described.aggregate?.groups.includes('DATE_HISTOGRAM')
  )
    return admitted;
  return new Set([...admitted, 'EXPRESSION']);
}

/**
 * What a path admits, less what a constraint takes back: an element's
 * array whose elements cannot be filtered offers no condition at all, and
 * `STARTS_WITH` under `STARTS_WITH_REQUIRES_PREFIX` only where the field
 * compares case-sensitively.
 *
 * `ELEMENT_MATCH` is never among an array's own operators: the descriptor
 * grants it through the array's `elements[]` entry (`filter`), so an array
 * whose elements can be filtered admits it beside its own.
 */
function admittedOperators(
  field: FieldDefinition,
  path: string,
  described: DescribedField,
  context: FieldContext,
): ReadonlySet<string> {
  if (field.kind === 'elementMatch') {
    const element = context.descriptor.elements.find(
      entry => entry.path === path,
    );
    if (!element?.filter) return new Set();
    return new Set([...described.operators, 'ELEMENT_MATCH']);
  }
  const prefixOnly = context.descriptor.constraints.some(
    constraint => constraint.type === STARTS_WITH_REQUIRES_PREFIX,
  );
  if (!prefixOnly || field.stringComparison === 'CASE_SENSITIVE')
    return described.operators;
  const admitted = new Set(described.operators);
  admitted.delete('STARTS_WITH');
  return admitted;
}

function narrowOperators(
  field: FieldDefinition,
  kind: FieldKind,
  admitted: ReadonlySet<string>,
  at: IssuePath,
  context: FieldContext,
): FieldDefinition {
  const offered = operatorsOf(field, kind);
  const kept = offered.filter(operator => admitted.has(operator));
  if (kept.length === offered.length) return field;
  context.findings.push(dropped(field.name, offered, kept, at));
  return { ...field, operators: kept };
}

function dropped(
  field: string,
  offered: readonly FilterOperatorName[],
  kept: readonly FilterOperatorName[],
  at: IssuePath,
): Issue {
  if (kept.length === 0)
    return warn(issue('capability.field.unfilterable', at, { field }));
  return warn(
    issue('capability.field.operators-narrowed', at, {
      field,
      operators: offered
        .filter(operator => !kept.includes(operator))
        .join(', '),
    }),
  );
}

/** A field the descriptor does not list: shown, never asked about. */
function withoutQuery(field: FieldDefinition): FieldDefinition {
  const next: FieldDefinition = { ...field, operators: [] };
  if (field.sortable) next.sortable = false;
  return field.summary ? without(next, 'summary') : next;
}

function narrowSort(
  field: FieldDefinition,
  path: string,
  described: DescribedField,
  at: IssuePath,
  context: FieldContext,
): FieldDefinition {
  if (field.sortable !== true || context.paging === undefined) return field;
  const sorts =
    context.paging === 'cursor' ? described.sort.cursor : described.sort.paged;
  if (sorts) return field;
  context.findings.push(
    warn(issue('capability.field.unsortable', at, { field: path })),
  );
  return { ...field, sortable: false };
}

/**
 * A column's summaries are metrics of one aggregation query: a count needs
 * `COUNT`, the rest a numeric function the path feeds.
 */
function narrowSummary(
  field: FieldDefinition,
  path: string,
  described: DescribedField,
  at: IssuePath,
  context: FieldContext,
): FieldDefinition {
  const declared = field.summary;
  if (!declared || declared.length === 0) return field;
  const metrics = context.descriptor.analysis.metrics;
  const functions = metrics.includes('NUMERIC')
    ? (described.aggregate?.functions ?? [])
    : [];
  const admits = (fn: SummaryFunction) =>
    fn === 'COUNT' ? metrics.includes('COUNT') : functions.includes(fn);
  const kept = declared.filter(admits);
  if (kept.length === declared.length) return field;
  context.findings.push(
    warn(
      issue('capability.field.summary-narrowed', at, {
        field: path,
        summaries: declared.filter(fn => !admits(fn)).join(', '),
      }),
    ),
  );
  return kept.length > 0
    ? { ...field, summary: kept }
    : without(field, 'summary');
}

/**
 * A time condition is written in the unit the definition declares, so a
 * descriptor that keeps the time another way means every date condition
 * would be sent wrong. That is the definition's mistake, not a capability
 * the deployment lacks, so it is an error.
 */
function checkTemporal(
  field: FieldDefinition,
  path: string,
  semantic: QuerySemanticType | undefined,
  at: IssuePath,
  context: FieldContext,
): void {
  if (!semantic || !TEMPORAL_FIELD_KIND_IDS.includes(field.kind)) return;
  const declared = temporalOf(field);
  if (sameTemporal(declared, semantic)) return;
  context.findings.push(
    issue('capability.field.temporal-mismatch', at, {
      field: path,
      declared: temporalText(declared),
      described: semanticText(semantic),
    }),
  );
}

function sameTemporal(
  declared: FieldTemporal,
  semantic: QuerySemanticType,
): boolean {
  if (semantic.type === 'TEMPORAL_DATE') return declared.type === 'date';
  if (semantic.type !== 'TEMPORAL_EPOCH' || declared.type !== 'epoch')
    return false;
  return (
    (declared.timeUnit ?? 'MILLISECONDS') ===
    (semantic.timeUnit ?? 'MILLISECONDS')
  );
}

function temporalText(temporal: FieldTemporal): string {
  return temporal.type === 'date'
    ? 'date'
    : `epoch ${temporal.timeUnit ?? 'MILLISECONDS'}`;
}

/**
 * The descriptor's semantic as the mismatch names it — every kind of it,
 * so a time field the model keeps as money says `money CNY`, never `date`.
 */
export function semanticText(semantic: QuerySemanticType): string {
  switch (semantic.type) {
    case 'TEMPORAL_DATE':
      return 'date';
    case 'TEMPORAL_EPOCH':
      return `epoch ${semantic.timeUnit ?? 'MILLISECONDS'}`;
    case 'TEMPORAL_FORMATTED':
      return `text ${semantic.pattern}`;
    case 'DECIMAL':
      return `decimal scale ${semantic.scale}`;
    case 'MONEY':
      return semantic.currency !== undefined
        ? `money ${semantic.currency} scale ${semantic.scale}`
        : `money by ${semantic.currencyField} scale ${semantic.scale}`;
    default:
      return (semantic as { type: string }).type;
  }
}

/**
 * A numeric semantic as the definition keeps it (`FieldNumeric`), or
 * `undefined` for a temporal one, and for one whose scale or currency the
 * engine could not write — a descriptor is data from another service, so
 * it is read rather than trusted, and a number left plain is better than a
 * format Intl refuses.
 */
export function numericOf(
  semantic: QuerySemanticType | undefined,
): FieldNumeric | undefined {
  if (!semantic || (semantic.type !== 'DECIMAL' && semantic.type !== 'MONEY'))
    return undefined;
  const scale = semantic.scale;
  if (!Number.isInteger(scale) || scale < 0 || scale > MAX_NUMERIC_SCALE)
    return undefined;
  if (semantic.type === 'DECIMAL') return { type: 'decimal', scale };
  if (typeof semantic.currency === 'string')
    return CURRENCY_CODE_PATTERN.test(semantic.currency)
      ? { type: 'money', scale, currency: semantic.currency.toUpperCase() }
      : undefined;
  return typeof semantic.currencyField === 'string' &&
    isFieldName(semantic.currencyField)
    ? { type: 'money', scale, currencyField: semantic.currencyField }
    : undefined;
}

/**
 * An option the descriptor does not list stays a candidate — records kept
 * from before may still hold it — and the definition's labels win over the
 * descriptor's values, so none is added either. What is said is that the
 * definition names a value the model does not declare.
 */
function checkOptions(
  field: FieldDefinition,
  path: string,
  described: DescribedField,
  at: IssuePath,
  context: FieldContext,
): void {
  const listed = described.enum;
  if (!listed || !field.options) return;
  const values = new Set(listed.map(entry => entry.value));
  const extra = field.options.filter(option => !values.has(option.value));
  if (extra.length === 0) return;
  context.findings.push(
    warn(
      issue('capability.field.options-undescribed', at, {
        field: path,
        values: extra.map(option => String(option.value)).join(', '),
      }),
    ),
  );
}

/**
 * Full-text search (G15, N4). A search field is usable when its scope
 * searches at all — the record's (`record.search`), or, for one an element
 * declares, that element's (`elements[].search`) — in the field's mode, in
 * at least one of its fields. A phrase the source cannot match is searched
 * as words, which is still a search, only a wider one; words the source can
 * only match as a phrase would be a narrower one, so that field is taken
 * away instead.
 *
 * An element's search names the element's fields relative to it, as its
 * query does; the descriptor lists them by full path, so they are compared
 * under `scope` and written back relative.
 */
function narrowSearch(
  field: FieldDefinition,
  at: IssuePath,
  scope: string | undefined,
  context: FieldContext,
): FieldDefinition {
  const search =
    scope === undefined
      ? context.descriptor.record.search
      : context.descriptor.elements.find(element => element.path === scope)
          ?.search;
  const named = scope === undefined ? field.name : `${scope}.${field.name}`;
  const unavailable = (): FieldDefinition => {
    context.findings.push(
      warn(issue('capability.search.unavailable', at, { field: named })),
    );
    return { ...field, operators: [] };
  };
  if (!search) return unavailable();

  let next = field;
  const mode = field.searchMode ?? DEFAULT_SEARCH_MODE;
  // Wow's enum, whose values are the literals a definition writes.
  const modes: readonly string[] = search.modes;
  if (!modes.includes(mode)) {
    if (mode !== 'PHRASE' || !modes.includes('TERMS')) return unavailable();
    next = { ...next, searchMode: 'TERMS' };
    context.findings.push(
      issue('capability.search.as-terms', at, { field: named }, 'note'),
    );
  }

  // A search field names the fields it looks in, and may name one by an
  // alias: read as the path the source's search lists (#3519).
  const declared = field.searchFields?.map(name =>
    canonicalOf(
      context,
      scope === undefined ? name : `${scope}.${name}`,
      scope,
    ),
  );
  // An element's search always names its fields (admission holds it to
  // that); a record's without any looks wherever the model indexes.
  if (!declared) return scope === undefined ? next : unavailable();
  const kept = declared.filter(name => search.fields.includes(name));
  if (kept.length === 0) return unavailable();
  const relative = (path: string) =>
    scope === undefined ? path : path.slice(scope.length + 1);
  if (kept.length === declared.length)
    return sameList(kept.map(relative), field.searchFields ?? [])
      ? next
      : { ...next, searchFields: kept.map(relative) };
  context.findings.push(
    warn(
      issue('capability.search.fields-narrowed', at, {
        field: named,
        fields: declared.filter(name => !kept.includes(name)).join(', '),
      }),
    ),
  );
  return { ...next, searchFields: kept.map(relative) };
}

/**
 * The path the descriptor lists a field under in `scope` (the root's when
 * undefined), an alias read as its path.
 */
function canonicalOf(
  context: FieldContext,
  name: string,
  scope: string | undefined,
): string {
  return (
    context.descriptor.fields.find(
      entry =>
        entry.scope === scope &&
        (entry.path === name || (entry.aliases ?? []).includes(name)),
    )?.path ?? name
  );
}

function sameList(one: readonly string[], other: readonly string[]): boolean {
  return (
    one.length === other.length && one.every((entry, i) => entry === other[i])
  );
}

/** A capability the deployment lacks: said, never blocking. */
export function warn(found: Issue): Issue {
  return { ...found, severity: 'warning' };
}
