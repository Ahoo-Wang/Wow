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
  DEFAULT_RUNTIME_LIMITS,
  SYSTEM_INSTANCE_ID_SEPARATOR,
  isFieldName,
  STRING_COMPARISONS,
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
import { validateAnalysis } from '../analysis/index.js';
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
    issues.push(...validateRecordCapability(definition));
    issues.push(...validateAnalysisCapability(definition, kinds));
  }

  issues.push(...validateSystemViews(definition, kinds, limits));
  return issues;
}

/** Whether a definition may be opened at all. */
export function isUsableDefinition(issues: readonly Issue[]): boolean {
  return !issues.some(found => found.severity === 'error');
}

function validateFields(
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  path: IssuePath,
): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();

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
  });

  return issues;
}

function validateRecordCapability(definition: DataViewDefinition): Issue[] {
  const capability: RecordCapability | undefined = definition.record;
  if (!capability) return [];

  const issues: Issue[] = [];
  const names = new Set(definition.fields.map(field => field.name));

  // `defaultRecordConfig` takes `layouts[0]`, and every config carries a row
  // key, so both have to exist before any view is built from this definition.
  if (capability.layouts.length === 0)
    issues.push(
      issue('definition.record.layouts-empty', ['record', 'layouts']),
    );
  if (!names.has(capability.rowKey))
    issues.push(
      issue('definition.record.row-key-unknown', ['record', 'rowKey'], {
        field: capability.rowKey,
      }),
    );

  return issues;
}

function validateAnalysisCapability(
  definition: DataViewDefinition,
  kinds: FieldKindRegistry,
): Issue[] {
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

  (capability.elements ?? []).forEach((element, index) => {
    const at: IssuePath = ['analysis', 'elements', index];
    if (!isFieldName(element.path))
      issues.push(
        issue('definition.analysis.element-path-invalid', [...at, 'path'], {
          path: element.path,
        }),
      );
    // An element's fields are their own scope: a name may repeat a root one,
    // because a config always writes an element field as `path.field`.
    issues.push(...validateFields(element.fields, kinds, [...at, 'fields']));
  });

  // `defaultAnalysisConfig` walks a fixed priority to find one metric. A
  // capability that offers none cannot produce a starting config at all.
  if (!hasConstructibleMetric(capability))
    issues.push(issue('definition.analysis.no-metric', ['analysis']));

  issues.push(...validateAnalysisLimits(capability));
  return issues;
}

function hasConstructibleMetric(capability: AnalysisCapability): boolean {
  if (capability.count) return true;
  const offers = [
    ...capability.fields,
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
