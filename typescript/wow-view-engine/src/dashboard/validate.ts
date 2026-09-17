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
} from '../filter/index.js';
import { mergeGlobalFilter } from './merge.js';
import { isSafeContentUrl, isViewPanel } from './panels.js';

/** What a panel refers to, once the engine has loaded it. */
export interface PanelReference {
  instance: ViewInstance;
  definition: ViewDefinition;
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
  if (panel.id.trim().length === 0)
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

  const { instance, definition } = reference;
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

  const bindings = validateBindings(panel, path, config, definition.fields);
  issues.push(...bindings.issues);
  if (bindings.issues.length > 0) return issues;

  // Two trees that each fit the budget can still exceed it once ANDed, and the
  // panel's own definition may refuse an operator the global field allowed.
  const merged = mergeGlobalFilter(
    instance.config.filter,
    config.filter,
    panel.bindings,
  );
  issues.push(
    ...validateFilter(definition.fields, merged, kinds, { limits }).map(
      found => ({
        ...found,
        path: [...path, 'filter', ...found.path],
      }),
    ),
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

  panel.bindings.forEach((binding, index) => {
    const at: IssuePath = [...path, 'bindings', index];
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
  if (!isSafeContentUrl(panel.src))
    issues.push(issue('dashboard.url.unsupported-scheme', [...path, 'src']));
  if (panel.href !== undefined && !isSafeContentUrl(panel.href))
    issues.push(issue('dashboard.url.unsupported-scheme', [...path, 'href']));
  return issues;
}

function validateLinks(
  panel: Extract<DashboardContentPanel, { kind: 'links' }>,
  path: IssuePath,
): Issue[] {
  if (panel.items.length > MAX_PANEL_LINKS)
    return [
      issue('dashboard.links.too-many', [...path, 'items'], {
        max: MAX_PANEL_LINKS,
      }),
    ];

  const issues: Issue[] = [];
  panel.items.forEach((item, index) => {
    const at: IssuePath = [...path, 'items', index];
    if (item.label.trim().length === 0)
      issues.push(issue('dashboard.link.label-empty', [...at, 'label']));
    if (!isSafeContentUrl(item.href))
      issues.push(issue('dashboard.url.unsupported-scheme', [...at, 'href']));
  });
  return issues;
}

/**
 * Whether an instance is visible wherever the dashboard is. A personal
 * dashboard may reference anything its owner can read; a shared or system one
 * is seen by others, so it may only reference views they can read too.
 */
export function coversScope(
  dashboard: ViewScope,
  instance: ViewScope,
): boolean {
  return dashboard === 'personal' || instance !== 'personal';
}

function isNonNegativeInteger(value: number): boolean {
  return Number.isInteger(value) && value >= 0;
}

function isPositiveInteger(value: number): boolean {
  return Number.isInteger(value) && value > 0;
}
