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
  SYSTEM_INSTANCE_ID_SEPARATOR,
  TEMPORAL_FIELD_KIND_IDS,
  isFieldlessKind,
  type AnalysisCapability,
  type DashboardViewConfig,
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
import { offerIssues } from '../analysis/validateOffers.js';
import {
  validateDashboard,
  type DefinitionLookup,
} from '../dashboard/index.js';
import { dequal } from 'dequal';
import { validateFields } from './validateFields.js';
import { ownedRefusals } from './dashboard/owned.js';

export interface ValidateDefinitionOptions {
  limits?: RuntimeLimits;
  /**
   * The other definitions registered beside this one, by id. A board a
   * definition declares may own an analysis of another definition, and that
   * analysis is judged whole against it. Left out, an owned analysis is
   * judged by its shape alone.
   */
  definitions?: (definitionId: string) => ViewDefinition | undefined;
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
    // What building it from its descriptor found (`defineView`): a path
    // the descriptor lacks, a capability asked beyond what it offers.
    issues.push(...(definition.described?.findings ?? []));
    issues.push(...validateFields(definition.fields, kinds, ['fields']));
    issues.push(...validateFieldGroups(definition));
    issues.push(...validateRecordCapability(definition));
    issues.push(...validateAnalysisCapability(definition));
    issues.push(...validateTimeFields(definition));
  }

  issues.push(
    ...validateSystemViews(definition, kinds, limits, options.definitions),
  );
  return issues;
}

/**
 * Whether a definition may be opened at all: it has no error, bar one a
 * declared board's panel owns (`boardPanelFinding`).
 *
 * A board is opened panel by panel, and its runtime judges each panel again
 * with its references in hand and puts out only the one that cannot run
 * (`blocksBoard`). A definition refused for one bad panel would take every
 * good panel of every board it declares down with it, and say nothing of
 * which panel or why, since nothing opens to say it. So what a board holds
 * apart from its panels — its grid, its filters, its fixed scope, its tabs,
 * how many panels it has — refuses the definition as before, and a panel's
 * own error is said on that panel when the board opens.
 */
export function isUsableDefinition(issues: readonly Issue[]): boolean {
  return !issues.some(
    found => found.severity === 'error' && !boardPanelFinding(found),
  );
}

/**
 * Whether a definition's finding is about one panel of a board it declares:
 * under `['views', n, 'config', 'panels', m]`. Only a dashboard config has
 * `panels`; a record or an analysis one never does.
 */
function boardPanelFinding({ path }: Issue): boolean {
  return (
    path[0] === 'views' &&
    path[2] === 'config' &&
    path[3] === 'panels' &&
    typeof path[4] === 'number'
  );
}

/**
 * The time fields (`DataViewDefinition.timeField`, `SystemView.timeField`):
 * each names a date field of the definition's own, or a board's time
 * filter would be wired to nothing it could narrow by.
 */
function validateTimeFields(definition: DataViewDefinition): Issue[] {
  const judge = (field: unknown, at: IssuePath): Issue[] => {
    if (field === undefined || field === null) return [];
    const declared = definition.fields.find(entry => entry.name === field);
    if (!declared)
      return [
        issue('definition.timeField.unknown', at, {
          field: typeof field === 'string' ? field : '',
        }),
      ];
    return TEMPORAL_FIELD_KIND_IDS.includes(declared.kind)
      ? []
      : [issue('definition.timeField.not-time', at, { field: declared.name })];
  };
  return [
    ...judge(definition.timeField, ['timeField']),
    ...(definition.views ?? []).flatMap((view, index) =>
      judge(view.timeField, ['views', index, 'timeField']),
    ),
  ];
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

  const issues: Issue[] = [
    ...offerIssues(definition, capability),
    ...validateElementChain(definition, capability),
  ];

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
      field.percentile === true ||
      field.firstLast === true,
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
  lookup: ValidateDefinitionOptions['definitions'],
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

    issues.push(
      ...validateSystemConfig(definition, view, path, kinds, limits, lookup),
    );
  });

  return issues;
}

function validateSystemConfig(
  definition: ViewDefinition,
  view: SystemView,
  path: IssuePath,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
  lookup: ValidateDefinitionOptions['definitions'],
): Issue[] {
  const kind = view.config.kind;
  const mismatch = issue('definition.view.kind-mismatch', [...path, 'config'], {
    kind,
    definition: definition.kind,
  });

  if (definition.kind === 'dashboard') {
    if (kind !== 'dashboard') return [mismatch];
    // Saved views this layer cannot load, so a store's id is never
    // "unavailable" here: saying so would be a finding about nothing but
    // this layer, and the workbench shows a definition's findings for as
    // long as it lists the board; `ViewEngine` judges it when the board
    // opens. A view declared in code is another matter: every registered
    // definition is at hand (todo C), so one the board names that is not
    // there is said now, at the panel, when the host registers it.
    const board = validateDashboard(
      view.config,
      'system',
      EMPTY_REFERENCES,
      kinds,
      { limits, definitions: lookup && panelDefinitions(lookup) },
    ).filter(found => found.code !== 'dashboard.panel.unavailable');
    return [
      ...under(path, board),
      ...validateOwnedAnalyses(view.config, path, board, {
        kinds,
        limits,
        lookup,
      }),
    ];
  }

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

/**
 * The analyses a declared board owns, each judged as the analysis it is
 * (`ownedRefusals`): against its own definition when that is registered
 * beside this one, by its shape alone when it is not (the board's own
 * admission says the definition is unknown when it is opened). One that
 * fails is one finding on the panel, and the kernel's own findings under it
 * say why. A panel's finding, so it puts that panel out and leaves the
 * definition usable (`isUsableDefinition`).
 */
function validateOwnedAnalyses(
  config: DashboardViewConfig,
  path: IssuePath,
  said: readonly Issue[],
  {
    kinds,
    limits,
    lookup,
  }: {
    kinds: FieldKindRegistry;
    limits: RuntimeLimits;
    lookup: ValidateDefinitionOptions['definitions'];
  },
): Issue[] {
  const definitionOf = (id: string) => {
    const found = lookup?.(id);
    return found?.kind === 'data' ? found : undefined;
  };
  return ownedRefusals(config, definitionOf, kinds, limits).flatMap(refused => {
    // The board's own admission judged the filter it runs under, merged
    // with the board's: what it already said of the panel is not said twice.
    const errors = refused.errors.filter(
      found =>
        !said.some(
          one =>
            one.path[1] === refused.index &&
            one.code === found.code &&
            dequal(one.params, found.params),
        ),
    );
    return errors.length === 0
      ? []
      : [
          issue(
            'definition.view.owned-invalid',
            [...path, 'config', ...refused.path],
            { panel: refused.panel },
          ),
          ...under(path, errors),
        ];
  });
}

/** The registered definitions as a board's admission reads them. */
function panelDefinitions(
  lookup: (definitionId: string) => ViewDefinition | undefined,
): DefinitionLookup {
  return id => {
    const definition = lookup(id);
    return definition
      ? {
          definition,
          fields: definition.kind === 'data' ? definition.fields : [],
        }
      : null;
  };
}

/** Re-paths a config's findings so they point at the view that holds it. */
function under(path: IssuePath, issues: readonly Issue[]): Issue[] {
  return issues.map(found => ({
    ...found,
    path: [...path, 'config', ...found.path],
  }));
}
