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
  MAX_HEADING_LENGTH,
  MAX_MARKDOWN_LENGTH,
  MAX_PANEL_LINKS,
  PANEL_PRESENTATION_MEMBERS,
  audienceOf,
  sameFilterType,
  type DashboardContentPanel,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
  type Issue,
  type IssuePath,
  type PanelLayout,
  type RuntimeLimits,
  type ViewConfig,
  type ViewDefinition,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import {
  filterFields,
  issue,
  mergeFilters,
  validateFilter,
  validateViewConfigBase,
  type FieldKindRegistry,
  isPlainObject,
} from '../filter/index.js';
import { defaultFilters, panelFilterTree } from './filters.js';
import { mergeGlobalFilter } from './merge.js';
import {
  validateFilterFields,
  validateTimeGrouping,
} from './validateFilters.js';
import { isSafeContentUrl, isViewPanel } from './panels.js';
import { validateTabs } from './tabs.js';

/** The definition a panel's view is of, and what its bindings may name. */
export interface PanelDefinition {
  definition: ViewDefinition;
  /**
   * The fields the view's filter and bindings are judged against: the
   * definition's own. The resolver computes this, because what a view can
   * reach is the other kernels' to say, and this one may not import them.
   */
  fields: readonly FieldDefinition[];
}

/** What a panel refers to, once the engine has loaded it. */
export interface PanelReference extends PanelDefinition {
  instance: ViewInstance;
}

/**
 * References by instance id. A `null` entry is an answer rather than a gap:
 * the instance was deleted or this user may not read it, and the panel is
 * reported as unavailable instead of holding up the dashboard.
 */
export type PanelReferences = ReadonlyMap<string, PanelReference | null>;

export interface ValidateDashboardOptions {
  limits?: RuntimeLimits;
  /**
   * The definition a view the board owns is of (`OwnedView.definitionId`),
   * `null` for one this release does not declare. Definitions are code, so
   * this is a lookup rather than a load. Left out, an owned view is judged
   * by its shape alone — what a definition's own declared dashboard gets,
   * where nothing can be looked up yet.
   */
  definitions?: (definitionId: string) => PanelDefinition | null;
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
  const columns = DASHBOARD_GRID_COLUMNS;
  // A config arrives from a store. When its skeleton is not a dashboard's,
  // nothing below can be judged, and saying so is the kernel's job rather
  // than a `TypeError`'s.
  const skeleton = validateSkeleton(config);
  if (skeleton.length > 0) return skeleton;
  const fields = config.fields as readonly FieldDefinition[];
  const issues = validateViewConfigBase(fields, config, kinds, limits);

  issues.push(...validateFilterFields(config, kinds, limits));
  issues.push(...validateTimeGrouping(config));

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

  issues.push(...validateTabs(config.tabs));
  const tabs = new Set(
    config.tabs.flatMap((tab: unknown) =>
      isPlainObject(tab) && typeof tab.id === 'string' ? [tab.id] : [],
    ),
  );

  const ids = new Set<string>();
  const context: ViewPanelContext = { config, scope, refs, kinds, limits };
  const lookup = options.definitions;
  config.panels.forEach((panel, index) => {
    const path: IssuePath = ['panels', index];
    if (!isPlainObject(panel)) {
      issues.push(shape(path, 'object'));
      return;
    }
    issues.push(...validateIdentity(panel, path, ids));
    issues.push(...validateLayout(panel.layout, [...path, 'layout'], columns));
    issues.push(...validatePanelTab(panel, path, tabs));
    issues.push(
      ...(isViewPanel(panel)
        ? validateViewPanel(panel, path, context, lookup)
        : validateContentPanel(panel, path)),
    );
  });

  return issues;
}

/**
 * The parts every later check reads without asking: the grid the layouts
 * are written in, the three arrays, and each field entry, which the shared
 * config check maps by name before this kernel's own rules get to look at
 * it.
 *
 * A grid other than this engine's is refused rather than drawn: its numbers
 * mean other cells, and the one grid a config may be in without saying so
 * was read into this one before it got here (`migrateDashboardConfig`).
 */
function validateSkeleton(config: DashboardViewConfig): Issue[] {
  const issues: Issue[] = [];
  if (config.columns !== DASHBOARD_GRID_COLUMNS)
    issues.push(
      issue('dashboard.grid.unsupported', ['columns'], {
        columns: DASHBOARD_GRID_COLUMNS,
      }),
    );
  if (!Array.isArray(config.tabs)) issues.push(shape(['tabs'], 'array'));
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
  expected: 'array' | 'object' | 'string' | 'true',
): Issue {
  return issue('dashboard.shape.invalid', path, { expected });
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

/**
 * What is wrong with one panel's geometry. `fitsGrid` asks the same thing,
 * so a placement the runtime makes is never one this reports.
 */
export function validateLayout(
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

/** What every data panel of one config is judged with. */
interface ViewPanelContext {
  config: DashboardViewConfig;
  scope: ViewScope;
  refs: PanelReferences;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
}

/**
 * The view a data panel shows, as far as this kernel can know it: its
 * definition, the config it runs, and — for a saved view — who may read it.
 * The issues are why there is none to judge the rest against; `view` is
 * `null` then, and also for an owned view with no lookup to judge it by.
 */
function panelView(
  panel: DashboardViewPanel,
  path: IssuePath,
  refs: PanelReferences,
  lookup: ValidateDashboardOptions['definitions'],
): {
  view: (PanelDefinition & { config: ViewConfig; scope?: ViewScope }) | null;
  issues: Issue[];
} {
  const owned: unknown = panel.owned;
  if ((owned === undefined) === (panel.instanceId === undefined))
    return {
      view: null,
      issues: [issue('dashboard.panel.source-invalid', path)],
    };

  if (owned !== undefined) {
    const at: IssuePath = [...path, 'owned'];
    if (
      !isPlainObject(owned) ||
      typeof owned.definitionId !== 'string' ||
      !isPlainObject(owned.config) ||
      owned.config.kind !== 'analysis'
    )
      return {
        view: null,
        issues: [issue('dashboard.panel.owned-invalid', at)],
      };
    if (!lookup) return { view: null, issues: [] };
    const found = lookup(owned.definitionId);
    if (!found)
      return {
        view: null,
        issues: [
          issue('dashboard.panel.definition-unknown', [...at, 'definitionId'], {
            definition: owned.definitionId,
          }),
        ],
      };
    return {
      view: { ...found, config: owned.config as unknown as ViewConfig },
      issues: [],
    };
  }

  const reference = refs.get(panel.instanceId as string);
  // A warning, not an error: the referenced view may be deleted or out of
  // this user's reach, which is not something the dashboard's editor can fix
  // and must not stop the other panels from running or the layout from being
  // saved. The panel itself reports that it is unavailable.
  if (!reference)
    return {
      view: null,
      issues: [
        issue(
          'dashboard.panel.unavailable',
          [...path, 'instanceId'],
          { instance: String(panel.instanceId) },
          'warning',
        ),
      ],
    };
  const { instance, ...definition } = reference;
  return {
    view: { ...definition, config: instance.config, scope: instance.scope },
    issues: [],
  };
}

function validateViewPanel(
  panel: DashboardViewPanel,
  path: IssuePath,
  { config, scope, refs, kinds, limits }: ViewPanelContext,
  lookup: ValidateDashboardOptions['definitions'],
): Issue[] {
  const { view, issues } = panelView(panel, path, refs, lookup);
  issues.push(...validatePresentation(panel, path));
  if (!view) return issues;

  const { definition, fields } = view;
  const source: IssuePath = [
    ...path,
    panel.owned === undefined ? 'instanceId' : 'owned',
  ];
  // A board owns only analyses (D22 C, first version), and a saved panel
  // shows a record or an analysis view — never another dashboard.
  const supported =
    definition.kind === 'data' &&
    (panel.owned === undefined
      ? view.config.kind !== 'dashboard'
      : definition.analysis !== undefined);
  if (!supported)
    return [
      ...issues,
      issue('dashboard.panel.kind-unsupported', source, {
        instance: panel.instanceId ?? '',
      }),
    ];

  // A shared dashboard built on a personal view is blank for everyone who
  // cannot read that view. It is allowed (D22 B) — the author sees the panel
  // and is told who does not — so this is a warning, and the board saves.
  if (view.scope !== undefined && !coversScope(scope, view.scope))
    issues.push(
      issue(
        'dashboard.panel.scope-too-narrow',
        source,
        { scope, instance: view.scope },
        'warning',
      ),
    );

  const bindings = validateBindings(panel, path, config, fields);
  issues.push(...bindings.issues);
  if (bindings.issues.length > 0) return issues;

  // Two trees that each fit the budget can still exceed it once ANDed, and the
  // panel's own definition may refuse an operator the global field allowed.
  // The view is judged against what it can reach, not the root fields alone:
  // an analysis standing on an element field opens fine on its own and must
  // not be refused the moment it is placed on a dashboard.
  // What the panel starts under: the board's standing condition, and each
  // wired filter at its default — a default the panel's field cannot take
  // is said here, while the board is built, rather than when it runs.
  const merged = mergeFilters(
    mergeGlobalFilter(view.config.filter, config.filter, panel.bindings),
    panelFilterTree(config, defaultFilters(config), panel.bindings, kinds),
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

    if (binding.auto !== undefined && binding.auto !== true)
      issues.push(shape([...at, 'auto'], 'true'));

    // A filter reaches any field of its type (D22 F): a date one a `date`
    // or a `datetime`, a text one a `string` or an `enum`.
    if (global && target && !sameFilterType(global.kind, target.kind))
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
    case 'heading':
      return validateText(
        panel.content,
        [...path, 'content'],
        MAX_HEADING_LENGTH,
        'dashboard.heading.too-long',
      );
    case 'markdown':
      return validateText(
        panel.content,
        [...path, 'content'],
        MAX_MARKDOWN_LENGTH,
        'dashboard.markdown.too-long',
      );
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

/** A content panel's text: a string no longer than its kind holds. */
function validateText(
  content: unknown,
  path: IssuePath,
  max: number,
  code: string,
): Issue[] {
  if (typeof content !== 'string') return [shape(path, 'string')];
  return content.length > max ? [issue(code, path, { max })] : [];
}

/**
 * An override of how the panel looks (D22 D). Only its shape is this
 * kernel's to judge: whether a chart fits the view's result is the analysis
 * kernel's, and the runtime asks it — an override that does not fit is
 * dropped there with the same note, never refused (`presentation.ts`).
 */
function validatePresentation(
  panel: DashboardViewPanel,
  path: IssuePath,
): Issue[] {
  const presentation: unknown = panel.presentation;
  if (presentation === undefined) return [];
  const fits =
    isPlainObject(presentation) &&
    Object.keys(presentation).every(key =>
      (PANEL_PRESENTATION_MEMBERS as readonly string[]).includes(key),
    );
  return fits
    ? []
    : [
        issue(
          'dashboard.panel.presentation-dropped',
          [...path, 'presentation'],
          {},
          'warning',
        ),
      ];
}

/**
 * The tab a panel names. On a board without tabs a panel names none; on one
 * with tabs it names one of them. A panel that does not is shown on the
 * first tab (`panelTab`) and said to be — a warning, since it still shows.
 */
function validatePanelTab(
  panel: DashboardPanel,
  path: IssuePath,
  tabs: ReadonlySet<string>,
): Issue[] {
  const tab: unknown = panel.tab;
  const fine =
    tabs.size === 0
      ? tab === undefined
      : typeof tab === 'string' && tabs.has(tab);
  return fine
    ? []
    : [
        issue(
          'dashboard.panel.tab-unknown',
          [...path, 'tab'],
          { tab: typeof tab === 'string' ? tab : '' },
          'warning',
        ),
      ];
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
