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
  DASHBOARD_GRID_COLUMNS,
  DEFAULT_RUNTIME_LIMITS,
  MAX_MARKDOWN_LENGTH,
  MAX_PANEL_LINKS,
  audienceOf,
  isFieldName,
  type DashboardContentPanel,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
  type Issue,
  type IssuePath,
  type PanelLayout,
  type RuntimeLimits,
  type ViewDefinition,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import {
  filterFields,
  issue,
  validateFilter,
  validateViewConfigBase,
  type FieldKindRegistry,
  isPlainObject,
} from '../filter/index.js';
import { mergeGlobalFilter } from './merge.js';
import { isSafeContentUrl, isViewPanel } from './panels.js';

/** What a panel refers to, once the engine has loaded it. */
export interface PanelReference {
  instance: ViewInstance;
  definition: ViewDefinition;
  /**
   * The fields the referenced view's filter and bindings are judged against.
   * A record view sees its definition's own; an analysis view also reaches
   * the element fields its config expands, and its filter may already stand
   * on one. The resolver computes this, because the kernel that knows how an
   * analysis expands is not one this kernel may import.
   */
  fields: readonly FieldDefinition[];
}

/**
 * References by instance id. A `null` entry is an answer rather than a gap:
 * the instance was deleted or this user may not read it, and the panel is
 * reported as unavailable instead of holding up the dashboard.
 */
export type PanelReferences = ReadonlyMap<string, PanelReference | null>;

export interface ValidateDashboardOptions {
  limits?: RuntimeLimits;
  /** Columns a layout must fit within; the grid adapter uses the same number. */
  columns?: number;
}

/**
 * Admits a dashboard config.
 *
 * Unlike the other two kernels this one takes no definition of its own: a
 * dashboard owns no data and spans several of them, so what it is judged
 * against is the instances its panels reference, which the caller has already
 * loaded. A panel whose instance is missing from `refs` is reported as
 * unavailable and the remaining panels are still judged, because one deleted
 * view must not close the whole dashboard.
 */
export function validateDashboard(
  config: DashboardViewConfig,
  scope: ViewScope,
  refs: PanelReferences,
  kinds: FieldKindRegistry,
  options: ValidateDashboardOptions = {},
): Issue[] {
  const limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
  const columns = options.columns ?? DASHBOARD_GRID_COLUMNS;
  // A config arrives from a store. When its skeleton is not a dashboard's,
  // nothing below can be judged, and saying so is the kernel's job rather
  // than a `TypeError`'s.
  const skeleton = validateSkeleton(config);
  if (skeleton.length > 0) return skeleton;
  const fields = config.fields as readonly FieldDefinition[];
  const issues = validateViewConfigBase(fields, config, kinds, limits);

  issues.push(...validateFields(config));

  // Before anything else about the panels, and so before the runtime creates
  // a single child for them.
  if (config.panels.length > limits.maxDashboardPanels) {
    issues.push(
      issue('dashboard.panels.too-many', ['panels'], {
        max: limits.maxDashboardPanels,
      }),
    );
    return issues;
  }

  const ids = new Set<string>();
  config.panels.forEach((panel, index) => {
    const path: IssuePath = ['panels', index];
    if (!isPlainObject(panel)) {
      issues.push(shape(path, 'object'));
      return;
    }
    issues.push(...validateIdentity(panel, path, ids));
    issues.push(...validateLayout(panel.layout, [...path, 'layout'], columns));
    issues.push(
      ...(isViewPanel(panel)
        ? validateViewPanel(panel, path, config, scope, refs, kinds, limits)
        : validateContentPanel(panel, path)),
    );
  });

  return issues;
}

/**
 * The parts every later check reads without asking: the two arrays, and
 * each field entry, which the shared config check maps by name before this
 * kernel's own rules get to look at it.
 */
function validateSkeleton(config: DashboardViewConfig): Issue[] {
  const issues: Issue[] = [];
  if (!Array.isArray(config.fields)) issues.push(shape(['fields'], 'array'));
  else
    config.fields.forEach((field, index) => {
      if (!isPlainObject(field) || typeof field.name !== 'string')
        issues.push(shape(['fields', index], 'object'));
    });
  if (!Array.isArray(config.panels)) issues.push(shape(['panels'], 'array'));
  return issues;
}

function shape(
  path: IssuePath,
  expected: 'array' | 'object' | 'string',
): Issue {
  return issue('dashboard.shape.invalid', path, { expected });
}

function validateFields(config: DashboardViewConfig): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();

  config.fields.forEach((field, index) => {
    const path: IssuePath = ['fields', index, 'name'];
    if (field.name.trim().length === 0) {
      issues.push(issue('dashboard.field.name-empty', path));
      return;
    }
    // A name outside Wow's query syntax would reach the compiler and throw.
    if (!isFieldName(field.name)) {
      issues.push(
        issue('dashboard.field.name-invalid', path, { field: field.name }),
      );
      return;
    }
    if (seen.has(field.name))
      issues.push(
        issue('dashboard.field.duplicate', path, { field: field.name }),
      );
    seen.add(field.name);
  });

  return issues;
}

/** The id is the runtime's lookup key, the grid's key and where errors land. */
function validateIdentity(
  panel: DashboardPanel,
  path: IssuePath,
  ids: Set<string>,
): Issue[] {
  const at: IssuePath = [...path, 'id'];
  if (typeof panel.id !== 'string' || panel.id.trim().length === 0)
    return [issue('dashboard.panel.id-empty', at)];
  if (ids.has(panel.id))
    return [issue('dashboard.panel.id-duplicate', at, { id: panel.id })];
  ids.add(panel.id);
  return [];
}

function validateLayout(
  layout: PanelLayout | undefined,
  path: IssuePath,
  columns: number,
): Issue[] {
  if (!layout) return [issue('dashboard.layout.missing', path)];

  const issues: Issue[] = [];
  for (const axis of ['x', 'y'] as const)
    if (!isNonNegativeInteger(layout[axis]))
      issues.push(issue('dashboard.layout.invalid', [...path, axis]));
  for (const size of ['w', 'h'] as const)
    if (!isPositiveInteger(layout[size]))
      issues.push(issue('dashboard.layout.invalid', [...path, size]));

  // A panel reaching past the last column wraps or disappears in the adapter.
  if (issues.length === 0 && layout.x + layout.w > columns)
    issues.push(issue('dashboard.layout.out-of-grid', path, { columns }));
  return issues;
}

function validateViewPanel(
  panel: DashboardViewPanel,
  path: IssuePath,
  config: DashboardViewConfig,
  scope: ViewScope,
  refs: PanelReferences,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const reference = refs.get(panel.instanceId);
  // A warning, not an error: the referenced view may be deleted or out of
  // this user's reach, which is not something the dashboard's editor can fix
  // and must not stop the other panels from running or the layout from being
  // saved. The panel itself reports that it is unavailable.
  if (!reference)
    return [
      issue(
        'dashboard.panel.unavailable',
        [...path, 'instanceId'],
        { instance: panel.instanceId },
        'warning',
      ),
    ];

  const { instance, definition, fields } = reference;
  if (definition.kind !== 'data' || instance.config.kind === 'dashboard')
    return [
      issue('dashboard.panel.kind-unsupported', [...path, 'instanceId'], {
        instance: panel.instanceId,
      }),
    ];

  const issues: Issue[] = [];
  // A shared dashboard built on a personal view would be blank for everyone
  // else, so the reference is refused rather than silently dropped later.
  if (!coversScope(scope, instance.scope))
    issues.push(
      issue('dashboard.panel.scope-too-narrow', [...path, 'instanceId'], {
        scope,
        instance: instance.scope,
      }),
    );

  const bindings = validateBindings(panel, path, config, fields);
  issues.push(...bindings.issues);
  if (bindings.issues.length > 0) return issues;

  // Two trees that each fit the budget can still exceed it once ANDed, and the
  // panel's own definition may refuse an operator the global field allowed.
  // The view is judged against what it can reach, not the root fields alone:
  // an analysis standing on an element field opens fine on its own and must
  // not be refused the moment it is placed on a dashboard.
  const merged = mergeGlobalFilter(
    instance.config.filter,
    config.filter,
    panel.bindings,
  );
  issues.push(
    ...validateFilter(fields, merged, kinds, { limits }).map(found => ({
      ...found,
      path: [...path, 'filter', ...found.path],
    })),
  );
  return issues;
}

function validateBindings(
  panel: DashboardViewPanel,
  path: IssuePath,
  config: DashboardViewConfig,
  panelFields: readonly FieldDefinition[],
): { issues: Issue[] } {
  const issues: Issue[] = [];
  const globals = new Map(config.fields.map(field => [field.name, field]));
  const targets = new Map(panelFields.map(field => [field.name, field]));
  const bound = new Set<string>();

  if (!Array.isArray(panel.bindings))
    return { issues: [shape([...path, 'bindings'], 'array')] };
  panel.bindings.forEach((binding, index) => {
    const at: IssuePath = [...path, 'bindings', index];
    if (
      !isPlainObject(binding) ||
      typeof binding.globalField !== 'string' ||
      typeof binding.panelField !== 'string'
    ) {
      issues.push(shape(at, 'object'));
      return;
    }
    const global = globals.get(binding.globalField);
    const target = targets.get(binding.panelField);

    if (!global)
      issues.push(
        issue('dashboard.binding.global-unknown', [...at, 'globalField'], {
          field: binding.globalField,
        }),
      );
    // One leaf can only become one condition: a global field mapped twice has
    // no defined boolean meaning.
    else if (bound.has(binding.globalField))
      issues.push(
        issue('dashboard.binding.global-duplicate', [...at, 'globalField'], {
          field: binding.globalField,
        }),
      );
    if (global) bound.add(binding.globalField);

    if (!target)
      issues.push(
        issue('dashboard.binding.panel-unknown', [...at, 'panelField'], {
          field: binding.panelField,
        }),
      );

    if (global && target && global.kind !== target.kind)
      issues.push(
        issue('dashboard.binding.kind-mismatch', at, {
          global: global.kind,
          panel: target.kind,
        }),
      );
  });

  // Partial mapping cannot preserve the tree's boolean meaning, so a panel
  // either carries the whole global filter or is reported as unbound.
  for (const field of filterFields(config.filter))
    if (!bound.has(field))
      issues.push(
        issue('dashboard.binding.missing', [...path, 'bindings'], { field }),
      );

  return { issues };
}

function validateContentPanel(
  panel: DashboardContentPanel,
  path: IssuePath,
): Issue[] {
  switch (panel.kind) {
    case 'markdown':
      if (typeof panel.content !== 'string')
        return [shape([...path, 'content'], 'string')];
      return panel.content.length > MAX_MARKDOWN_LENGTH
        ? [
            issue('dashboard.markdown.too-long', [...path, 'content'], {
              max: MAX_MARKDOWN_LENGTH,
            }),
          ]
        : [];
    case 'image':
      return validateImage(panel, path);
    case 'links':
      return validateLinks(panel, path);
    default:
      return [
        issue('dashboard.panel.unknown-kind', path, {
          kind: (panel as DashboardPanel).kind,
        }),
      ];
  }
}

function validateImage(
  panel: Extract<DashboardContentPanel, { kind: 'image' }>,
  path: IssuePath,
): Issue[] {
  const issues: Issue[] = [];
  if (typeof panel.src !== 'string' || !isSafeContentUrl(panel.src))
    issues.push(issue('dashboard.url.unsupported-scheme', [...path, 'src']));
  if (
    panel.href !== undefined &&
    (typeof panel.href !== 'string' || !isSafeContentUrl(panel.href))
  )
    issues.push(issue('dashboard.url.unsupported-scheme', [...path, 'href']));
  return issues;
}

function validateLinks(
  panel: Extract<DashboardContentPanel, { kind: 'links' }>,
  path: IssuePath,
): Issue[] {
  if (!Array.isArray(panel.items)) return [shape([...path, 'items'], 'array')];
  if (panel.items.length > MAX_PANEL_LINKS)
    return [
      issue('dashboard.links.too-many', [...path, 'items'], {
        max: MAX_PANEL_LINKS,
      }),
    ];

  const issues: Issue[] = [];
  panel.items.forEach((item, index) => {
    const at: IssuePath = [...path, 'items', index];
    if (!isPlainObject(item)) {
      issues.push(shape(at, 'object'));
      return;
    }
    if (typeof item.label !== 'string' || item.label.trim().length === 0)
      issues.push(issue('dashboard.link.label-empty', [...at, 'label']));
    if (typeof item.href !== 'string' || !isSafeContentUrl(item.href))
      issues.push(issue('dashboard.url.unsupported-scheme', [...at, 'href']));
  });
  return issues;
}

/**
 * Whether an instance is visible wherever the dashboard is. A personal
 * dashboard may reference anything its owner can read; a shared or system one
 * is seen by others, so it may only reference views they can read too.
 *
 * The question is one of audience alone — that a system view passes it is
 * `audienceOf` answering, not a case spelled out here.
 */
export function coversScope(
  dashboard: ViewScope,
  instance: ViewScope,
): boolean {
  return (
    audienceOf(dashboard) === 'personal' || audienceOf(instance) === 'shared'
  );
}

function isNonNegativeInteger(value: number): boolean {
  return Number.isInteger(value) && value >= 0;
}

function isPositiveInteger(value: number): boolean {
  return Number.isInteger(value) && value > 0;
}
