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

import { AGGREGATION_LIMITS } from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  EPOCH_TIME_UNITS,
  FIELD_CELL_IDS,
  FIELD_TONES,
  SYSTEM_INSTANCE_ID_SEPARATOR,
  isFieldlessKind,
  isFieldName,
  SEARCH_MODES,
  STRING_COMPARISONS,
  TEMPORAL_FIELD_KIND_IDS,
  TEMPORAL_TYPES,
  type AnalysisCapability,
  type DataViewDefinition,
  type FieldDefinition,
  type Issue,
  type IssuePath,
  type RecordCapability,
  type RuntimeLimits,
  type SystemView,
  type ViewDefinition,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { validateRecord } from '../record/index.js';
import { analysisScope, validateAnalysis } from '../analysis/index.js';
import { validateDashboard } from '../dashboard/index.js';

export interface ValidateDefinitionOptions {
  limits?: RuntimeLimits;
}

/**
 * Admits a definition.
 *
 * Definitions are code, not data, so this does not run on every keystroke; it
 * runs once, where an application registers them. What it buys is that every
 * invariant the rest of the package assumes becomes a reported finding rather
 * than an exception several layers down: `systemInstanceId` composes ids with
 * `:`, `defaultRecordConfig` reads `layouts[0]`, `defaultAnalysisConfig`
 * assumes some metric is constructible, and the compilers assume field names
 * are Wow query paths. Each of those would otherwise surface as a `TypeError`
 * at the moment a user opened a view.
 *
 * It lives beside the runtime rather than in a kernel because it needs all
 * three of them at once, and a kernel may not import another kernel.
 */
export function validateDefinition(
  definition: ViewDefinition,
  kinds: FieldKindRegistry,
  options: ValidateDefinitionOptions = {},
): Issue[] {
  const limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
  const issues: Issue[] = [];

  // `system:${definitionId}:${viewId}` must be decomposable again, and a
  // separator inside either part makes ('a:b','c') and ('a','b:c') collide.
  if (definition.id.includes(SYSTEM_INSTANCE_ID_SEPARATOR))
    issues.push(
      issue('definition.id.separator', ['id'], {
        separator: SYSTEM_INSTANCE_ID_SEPARATOR,
      }),
    );

  if (definition.kind === 'data') {
    issues.push(...validateFields(definition.fields, kinds, ['fields']));
    issues.push(...validateFieldGroups(definition));
    issues.push(...validateRecordCapability(definition));
    issues.push(...validateAnalysisCapability(definition));
  }

  issues.push(...validateSystemViews(definition, kinds, limits));
  return issues;
}

/** Whether a definition may be opened at all. */
export function isUsableDefinition(issues: readonly Issue[]): boolean {
  return !issues.some(found => found.severity === 'error');
}

/**
 * The picker groups: each declared once with an id and a label, and every
 * field it lists declared by the definition and listed by no other group. A
 * group listing a field nobody declared would otherwise show nothing for it,
 * and a typo would look like a design.
 */
function validateFieldGroups(definition: DataViewDefinition): Issue[] {
  const issues: Issue[] = [];
  const ids = new Set<string>();
  const declared = new Set(definition.fields.map(field => field.name));
  const listed = new Set<string>();
  (definition.fieldGroups ?? []).forEach((group, index) => {
    const at: IssuePath = ['fieldGroups', index];
    if (group.id.trim().length === 0 || group.label.trim().length === 0) {
      issues.push(issue('definition.fieldGroup.invalid', at));
      return;
    }
    if (ids.has(group.id))
      issues.push(
        issue('definition.fieldGroup.duplicate', [...at, 'id'], {
          group: group.id,
        }),
      );
    ids.add(group.id);
    group.fields.forEach((field, position) => {
      const where: IssuePath = [...at, 'fields', position];
      if (!declared.has(field))
        issues.push(
          issue('definition.fieldGroup.field-unknown', where, {
            group: group.id,
            field,
          }),
        );
      else if (listed.has(field))
        issues.push(
          issue('definition.fieldGroup.field-duplicate', where, {
            group: group.id,
            field,
          }),
        );
      listed.add(field);
    });
  });
  return issues;
}

function validateFields(
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  path: IssuePath,
): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();
  // Every document field at this level, not the ones seen so far: a search
  // may name a field declared after it, and the order of a list is nobody's
  // contract.
  const documentFields = new Set(
    fields
      .filter(field => !isFieldlessKind(field.kind))
      .map(field => field.name),
  );

  fields.forEach((field, index) => {
    const at: IssuePath = [...path, index];
    if (!isFieldName(field.name))
      issues.push(
        issue('definition.field.name-invalid', [...at, 'name'], {
          field: field.name,
        }),
      );
    else if (seen.has(field.name))
      issues.push(
        issue('definition.field.duplicate', [...at, 'name'], {
          field: field.name,
        }),
      );
    seen.add(field.name);

    // Without a registered kind a filter on this field can be neither
    // admitted nor compiled, so the field is unusable rather than degraded.
    if (!kinds.get(field.kind))
      issues.push(
        issue('definition.field.kind-unregistered', [...at, 'kind'], {
          field: field.name,
          kind: field.kind,
        }),
      );

    // Wow refuses an unknown comparison by throwing, and it would throw while
    // compiling a query rather than while reading the definition.
    if (
      field.stringComparison !== undefined &&
      !STRING_COMPARISONS.includes(field.stringComparison)
    )
      issues.push(
        issue(
          'definition.field.string-comparison-invalid',
          [...at, 'stringComparison'],
          { field: field.name, value: String(field.stringComparison) },
        ),
      );

    // `filter.search` refuses an unknown mode by throwing, and a field it
    // cannot read by throwing too — both while compiling a query rather than
    // while reading the definition.
    if (
      field.searchMode !== undefined &&
      !SEARCH_MODES.includes(field.searchMode)
    )
      issues.push(
        issue('definition.field.search-mode-invalid', [...at, 'searchMode'], {
          field: field.name,
          value: String(field.searchMode),
        }),
      );

    issues.push(...validateTemporal(field, [...at, 'temporal']));

    // The renderers are a closed set `RecordTable` switches over, so a key
    // nothing switches on would not fail — it would quietly render the
    // default, and a column declared as a link would stay a string of
    // characters nobody can click.
    if (field.cell !== undefined && !FIELD_CELL_IDS.includes(field.cell))
      issues.push(
        issue('definition.field.cell-invalid', [...at, 'cell'], {
          field: field.name,
          value: String(field.cell),
        }),
      );

    // `FieldDefinition.editor` is gone (D17-11). It was declared and never
    // read: `FilterValueEditor` picks its input from the kind, the operator
    // and the value shape, so a field that named an editor got exactly the
    // control it would have got anyway — a member that looks like a
    // contract and keeps none. A definition that still writes it is a
    // warning rather than a refusal: nothing about that release stops
    // working, and refusing would take an application down over a line it
    // only has to delete.
    if ('editor' in field)
      issues.push(
        issue(
          'definition.field.editor-removed',
          [...at, 'editor'],
          { field: field.name },
          'warning',
        ),
      );

    // A tone names a theme token; one nothing maps to would leave the badge
    // neutral, which is the one thing declaring a tone was meant to change.
    (field.options ?? []).forEach((option, position) => {
      if (option.tone !== undefined && !FIELD_TONES.includes(option.tone))
        issues.push(
          issue(
            'definition.field.tone-invalid',
            [...at, 'options', position, 'tone'],
            { field: field.name, value: String(option.tone) },
          ),
        );
    });

    // A handle is not a path, so searching one would ask the backend for a
    // document field that does not exist. Naming it is as wrong as naming
    // nothing, and for the same reason.
    const missing = (field.searchFields ?? []).filter(
      name => !documentFields.has(name),
    );
    if (missing.length > 0)
      issues.push(
        issue(
          'definition.field.search-fields-unknown',
          [...at, 'searchFields'],
          { field: field.name, missing: missing.join(', ') },
        ),
      );

    // An element's names are its own scope: they may repeat a root field's,
    // because every reference to one is written `field.element`. Checking
    // them here is what makes a nested declaration self-contained — there is
    // no path to dangle and no second place to keep in step.
    if (field.elements !== undefined)
      issues.push(
        ...validateFields(field.elements, kinds, [...at, 'elements']),
      );

    issues.push(...validateElementTitle(field, kinds, [...at, 'elementTitle']));
  });

  return issues;
}

/**
 * `elementTitle` is what a cell reads each element of the array by, so a
 * name the elements do not declare would not fail — every element would
 * read as untitled, and the column would say nothing where the definition
 * meant it to say which step each event was. A title has to be a value an
 * element holds: a handle names no member of the element, and a further
 * array of objects is a list, not a name.
 */
function validateElementTitle(
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  at: IssuePath,
): Issue[] {
  const title = field.elementTitle;
  if (title === undefined) return [];
  const found = (field.elements ?? []).find(element => element.name === title);
  if (!found)
    return [
      issue('definition.field.element-title-unknown', at, {
        field: field.name,
        title,
      }),
    ];
  if (
    isFieldlessKind(found.kind, kinds.get(found.kind)) ||
    found.elements !== undefined
  )
    return [
      issue('definition.field.element-title-not-a-value', at, {
        field: field.name,
        title: found.name,
      }),
    ];
  return [];
}

/**
 * `temporal` decides what every bound of a date condition is sent as, so a
 * declaration the compiler cannot read would not fail — it would send the
 * default and be refused by the service on every query, or match nothing.
 * On a field whose conditions write no time it is a declaration that does
 * nothing, which is a mistake in code rather than a preference.
 */
function validateTemporal(field: FieldDefinition, at: IssuePath): Issue[] {
  const temporal: unknown = field.temporal;
  if (temporal === undefined) return [];
  if (!TEMPORAL_FIELD_KIND_IDS.includes(field.kind))
    return [
      issue('definition.field.temporal-misplaced', at, {
        field: field.name,
        kind: field.kind,
      }),
    ];
  // A definition is code, but a `temporal` may be built from a fetched schema,
  // so its shape is read rather than trusted.
  const declared: { type?: unknown; timeUnit?: unknown } | null =
    typeof temporal === 'object' ? temporal : null;
  const known =
    declared !== null &&
    TEMPORAL_TYPES.includes(declared.type as never) &&
    (declared.type !== 'epoch' ||
      declared.timeUnit === undefined ||
      EPOCH_TIME_UNITS.includes(declared.timeUnit as never));
  return known
    ? []
    : [
        issue('definition.field.temporal-invalid', at, {
          field: field.name,
          value: JSON.stringify(temporal),
        }),
      ];
}

function validateRecordCapability(definition: DataViewDefinition): Issue[] {
  const capability: RecordCapability | undefined = definition.record;
  if (!capability) return [];

  const issues: Issue[] = [];

  // `defaultRecordConfig` takes `layouts[0]`, and every config carries a row
  // key, so both have to exist before any view is built from this definition.
  if (capability.layouts.length === 0)
    issues.push(
      issue('definition.record.layouts-empty', ['record', 'layouts']),
    );
  const rowKey = definition.fields.find(
    field => field.name === capability.rowKey,
  );
  if (!rowKey)
    issues.push(
      issue('definition.record.row-key-unknown', ['record', 'rowKey'], {
        field: capability.rowKey,
      }),
    );
  // Every record query ends on the row key (`compileRecord`), which is what
  // keeps a row from showing on two pages when the sort ties. A backend
  // asked to order by a field it cannot sort on refuses the query, so the
  // definition has to say the row key can be.
  else if (rowKey.sortable !== true)
    issues.push(
      issue('definition.record.row-key-unsortable', ['record', 'rowKey'], {
        field: capability.rowKey,
      }),
    );

  issues.push(...validateMaxWindow(capability));
  issues.push(...validateMaxSortFields(capability));

  // What the host's code reads off a row is fetched on every page, so a
  // name that is no path into a row would be asked for and never arrive —
  // and the action reading it would quietly see `undefined` forever.
  const byName = new Map(definition.fields.map(field => [field.name, field]));
  (capability.rowFields ?? []).forEach((name, index) => {
    const field = byName.get(name);
    const at: IssuePath = ['record', 'rowFields', index];
    if (!field)
      issues.push(
        issue('definition.record.row-field-unknown', at, { field: name }),
      );
    else if (isFieldlessKind(field.kind))
      issues.push(
        issue('definition.record.row-field-not-a-path', at, { field: name }),
      );
  });

  return issues;
}

/**
 * The paging window: a whole number of rows, and only where there are pages.
 *
 * The pager divides it by a page size to find its last page, so a zero, a
 * fraction or a string would stop it on a page the source never bounded —
 * or on none at all. A cursor is a position rather than a page, so a window
 * declared on one bounds nothing, and says the declaration misread its
 * source.
 */
function validateMaxWindow(capability: RecordCapability): Issue[] {
  const bound: unknown = capability.maxWindow;
  if (bound === undefined) return [];
  if (typeof bound !== 'number' || !Number.isInteger(bound) || bound < 1)
    return [
      issue('definition.record.max-window-invalid', ['record', 'maxWindow'], {
        value:
          typeof bound === 'number' || typeof bound === 'string'
            ? String(bound)
            : typeof bound,
      }),
    ];
  if (capability.paging !== 'paged')
    return [
      issue('definition.record.max-window-cursor', ['record', 'maxWindow']),
    ];
  return [];
}

/** The sort bound: a whole number of fields, none at all included. */
function validateMaxSortFields(capability: RecordCapability): Issue[] {
  const bound: unknown = capability.maxSortFields;
  if (bound === undefined) return [];
  if (typeof bound === 'number' && Number.isInteger(bound) && bound >= 0)
    return [];
  return [
    issue(
      'definition.record.max-sort-fields-invalid',
      ['record', 'maxSortFields'],
      {
        value:
          typeof bound === 'number' || typeof bound === 'string'
            ? String(bound)
            : typeof bound,
      },
    ),
  ];
}

function validateAnalysisCapability(definition: DataViewDefinition): Issue[] {
  const capability: AnalysisCapability | undefined = definition.analysis;
  if (!capability) return [];

  const issues: Issue[] = [];
  const names = new Set(definition.fields.map(field => field.name));

  capability.fields.forEach((entry, index) => {
    if (!names.has(entry.field))
      issues.push(
        issue(
          'definition.analysis.field-unknown',
          ['analysis', 'fields', index],
          {
            field: entry.field,
          },
        ),
      );
  });

  issues.push(...validateElementChain(definition, capability));

  // `defaultAnalysisConfig` walks a fixed priority to find one metric. A
  // capability that offers none cannot produce a starting config at all.
  if (!hasConstructibleMetric(definition, capability))
    issues.push(issue('definition.analysis.no-metric', ['analysis']));

  issues.push(...validateAnalysisLimits(capability));
  return issues;
}

/**
 * The expansion chain, walked from the root down.
 *
 * Wow's `elements` is one ordered parent-to-child chain and not a list of
 * sibling arrays: the first path is a root field that holds elements, and
 * each later one names an array declared *inside* the level above it. A
 * capability that lists two root arrays is therefore one broken chain rather
 * than two usable ones, and saying so here is what keeps a view from being
 * built on an expansion the server refuses.
 */
function validateElementChain(
  definition: DataViewDefinition,
  capability: AnalysisCapability,
): Issue[] {
  const issues: Issue[] = [];
  const declared = capability.elements ?? [];

  // Beyond Wow's ceiling a level can never be expanded, so declaring it
  // offers a capability no config may use.
  if (declared.length > AGGREGATION_LIMITS.MAX_ELEMENTS)
    issues.push(
      issue('definition.analysis.elements-too-many', ['analysis', 'elements'], {
        max: AGGREGATION_LIMITS.MAX_ELEMENTS,
      }),
    );

  let held: readonly FieldDefinition[] = definition.fields;
  for (const [index, element] of declared.entries()) {
    const at: IssuePath = ['analysis', 'elements', index];
    const holder = held.find(field => field.name === element.path);
    // The path names a declared element of the level above; its fields are
    // checked where they are declared, so all this has to establish is that
    // it names one, and that it continues the chain.
    if (!holder || holder.elements === undefined) {
      issues.push(
        issue('definition.analysis.element-undeclared', [...at, 'path'], {
          path: element.path,
        }),
      );
      break;
    }
    held = holder.elements;

    // The same check the root fields get, which they had and these did not:
    // an aggregation over a name the element never declared resolves to
    // nothing, and a view built on it offers a metric with no field behind it.
    const names = new Set(held.map(field => field.name));
    element.aggregations.forEach((entry, position) => {
      if (!names.has(entry.field))
        issues.push(
          issue(
            'definition.analysis.element-field-unknown',
            [...at, 'aggregations', position, 'field'],
            { path: element.path, field: entry.field },
          ),
        );
    });
  }

  return issues;
}

function hasConstructibleMetric(
  definition: DataViewDefinition,
  capability: AnalysisCapability,
): boolean {
  if (capability.count) return true;
  // The root's offers as the scope reads them — a date field's declared sum
  // is no metric at all (`aggregationFunctionsOf`) — so what is admitted
  // here is what `defaultAnalysisConfig` can start from.
  const offers = [
    ...analysisScope(definition, capability).aggregations.values(),
    ...(capability.elements ?? []).flatMap(element => element.aggregations),
  ];
  return offers.some(
    field =>
      field.functions.length > 0 ||
      field.any === true ||
      field.distinctCount === true ||
      field.percentile === true,
  );
}

function validateAnalysisLimits(capability: AnalysisCapability): Issue[] {
  const limits = capability.limits;
  if (!limits) return [];

  const issues: Issue[] = [];
  const path: IssuePath = ['analysis', 'limits'];
  for (const key of [
    'maxGroups',
    'maxMetrics',
    'maxElements',
    'maxLimit',
    'defaultLimit',
  ] as const) {
    const value = limits[key];
    if (value !== undefined && !(Number.isInteger(value) && value > 0))
      issues.push(issue('definition.analysis.limit-invalid', [...path, key]));
  }

  const { defaultLimit, maxLimit } = limits;
  if (
    defaultLimit !== undefined &&
    maxLimit !== undefined &&
    defaultLimit > maxLimit
  )
    issues.push(
      issue('definition.analysis.default-limit-too-large', [
        ...path,
        'defaultLimit',
      ]),
    );

  return issues;
}

/**
 * A system view ships with the definition and nobody can overwrite it, so one
 * that needs fixing is a read-only view that can never be fixed. Both its
 * identity and its config are checked here.
 */
function validateSystemViews(
  definition: ViewDefinition,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();

  (definition.views ?? []).forEach((view, index) => {
    const path: IssuePath = ['views', index];
    if (view.id.includes(SYSTEM_INSTANCE_ID_SEPARATOR))
      issues.push(
        issue('definition.view.id-separator', [...path, 'id'], {
          separator: SYSTEM_INSTANCE_ID_SEPARATOR,
        }),
      );
    // Capability lookup goes by id; two views under one id have no answer.
    if (seen.has(view.id))
      issues.push(
        issue('definition.view.id-duplicate', [...path, 'id'], { id: view.id }),
      );
    seen.add(view.id);

    issues.push(...validateSystemConfig(definition, view, path, kinds, limits));
  });

  return issues;
}

function validateSystemConfig(
  definition: ViewDefinition,
  view: SystemView,
  path: IssuePath,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const kind = view.config.kind;
  const mismatch = issue('definition.view.kind-mismatch', [...path, 'config'], {
    kind,
    definition: definition.kind,
  });

  if (definition.kind === 'dashboard')
    return kind === 'dashboard'
      ? // Panels reference instances this layer cannot load, so only the
        // local structure is judged here; `ViewEngine` re-checks the rest
        // with the references in hand when the view is opened.
        under(
          path,
          validateDashboard(view.config, 'system', EMPTY_REFERENCES, kinds, {
            limits,
          }),
        )
      : [mismatch];

  if (kind === 'record')
    return definition.record
      ? under(path, validateRecord(definition, view.config, kinds, { limits }))
      : [mismatch];
  if (kind === 'analysis')
    return definition.analysis
      ? under(
          path,
          validateAnalysis(definition, view.config, kinds, { limits }),
        )
      : [mismatch];
  return [mismatch];
}

const EMPTY_REFERENCES = new Map();

/** Re-paths a config's findings so they point at the view that holds it. */
function under(path: IssuePath, issues: readonly Issue[]): Issue[] {
  return issues.map(found => ({
    ...found,
    path: [...path, 'config', ...found.path],
  }));
}
