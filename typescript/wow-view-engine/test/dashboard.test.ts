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

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  coversScope,
  DEFAULT_RUNTIME_LIMITS,
  emptyDashboardConfig,
  isContentPanel,
  isSafeContentUrl,
  isPresentationMember,
  isViewPanel,
  mapGlobalFilter,
  mergeGlobalFilter,
  overlaid,
  panelsOf,
  presentationMembersOf,
  tabsOf,
  validateDashboard,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterTree,
  type Issue,
  type PanelReference,
  type ViewScope,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  panelReference,
  recordConfig,
} from './fixtures.js';

const kinds = builtinFieldKinds;

function codes(issues: readonly Issue[]): string[] {
  return issues.map(found => found.code);
}

function errors(issues: readonly Issue[]): Issue[] {
  return issues.filter(found => found.severity === 'error');
}

function viewPanel(overrides: Partial<DashboardPanel> = {}): DashboardPanel {
  return {
    id: 'orders',
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...overrides,
  } as DashboardPanel;
}

function refs(
  entries: Record<string, PanelReference> = { pending: panelReference() },
): ReadonlyMap<string, PanelReference> {
  return new Map(Object.entries(entries));
}

function validate(
  config: DashboardViewConfig,
  scope: ViewScope = 'personal',
  references = refs(),
): Issue[] {
  return validateDashboard(config, scope, references, kinds);
}

/** A global filter on `region`, bound onto the panel's `warehouse`. */
const REGION_FILTER: FilterTree = {
  op: 'and',
  children: [{ field: 'region', operator: 'EQ', value: 'CN' }],
};

const REGION_FIELD = { name: 'region', label: 'Region', kind: 'string' };

describe('emptyDashboardConfig', () => {
  it('is complete and valid without a definition', () => {
    const config = emptyDashboardConfig();

    expect(config.panels).toEqual([]);
    expect(config.fields).toEqual([]);
    expect(validate(config)).toEqual([]);
  });
});

describe('panel predicates', () => {
  it('separate data panels from static ones', () => {
    const view = viewPanel();
    const markdown = viewPanel({ kind: 'markdown', content: 'hi' });

    expect(isViewPanel(view)).toBe(true);
    expect(isContentPanel(view)).toBe(false);
    expect(isViewPanel(markdown)).toBe(false);
    expect(isContentPanel(markdown)).toBe(true);
  });

  /**
   * A stored config is untrusted, and every layer reads its panels and its
   * tabs through the kernel's one reading (A-17) rather than an
   * `Array.isArray` of its own.
   */
  it('read the panels and the well-formed tabs of an untrusted config', () => {
    const config = dashboardConfig({ panels: [viewPanel()] });

    expect(panelsOf(config)).toBe(config.panels);
    expect(panelsOf({ panels: 'x' as never })).toEqual([]);
    expect(
      tabsOf({
        tabs: [
          { id: 'a', title: 'A' },
          { id: 'b' },
          'c',
          { id: 1, title: 'D' },
        ] as never,
      }),
    ).toEqual([{ id: 'a', title: 'A' }]);
    expect(tabsOf({ tabs: null as never })).toEqual([]);
  });

  /** Q-07: which members a panel's own look sets, asked in one place. */
  it('name the members a data panel overrides how it looks by', () => {
    expect(isPresentationMember('chart')).toBe(true);
    expect(isPresentationMember('filter')).toBe(false);
    expect(
      presentationMembersOf(
        viewPanel({ presentation: { layout: 'chart', nothing: 1 } as never }),
      ),
    ).toEqual(['layout']);
    expect(presentationMembersOf(viewPanel())).toEqual([]);
    expect(
      presentationMembersOf(viewPanel({ presentation: 'x' as never })),
    ).toEqual([]);
    expect(
      presentationMembersOf(viewPanel({ kind: 'markdown', content: '' })),
    ).toEqual([]);
  });
});

describe('overlaid', () => {
  it('lays a patch over a config, a member given as undefined taken out', () => {
    const before = { a: 1, b: 2, c: 3 } as { a: number; b?: number; c: number };
    const after = overlaid(before, { a: 5, b: undefined });

    expect(after).toEqual({ a: 5, c: 3 });
    expect('b' in after).toBe(false);
    expect(before).toEqual({ a: 1, b: 2, c: 3 });
  });
});

describe('mapGlobalFilter', () => {
  it('renames bound leaves and keeps operator, value and shape', () => {
    const tree: FilterTree = {
      op: 'or',
      children: [
        { field: 'region', operator: 'EQ', value: 'CN' },
        {
          op: 'and',
          children: [{ field: 'region', operator: 'IN', value: ['EU'] }],
        },
      ],
    };

    expect(
      mapGlobalFilter(tree, [
        { globalField: 'region', panelField: 'warehouse' },
      ]),
    ).toEqual({
      op: 'or',
      children: [
        { field: 'warehouse', operator: 'EQ', value: 'CN' },
        {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'IN', value: ['EU'] }],
        },
      ],
    });
  });

  it('leaves an unbound field under its global name for the panel to reject', () => {
    const mapped = mapGlobalFilter(REGION_FILTER, []);

    expect(mapped).toEqual(REGION_FILTER);
  });
});

describe('mergeGlobalFilter', () => {
  it('ANDs the mapped global filter onto the panel filter as a nested group', () => {
    const panelFilter: FilterTree = {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
    };

    expect(
      mergeGlobalFilter(panelFilter, REGION_FILTER, [
        { globalField: 'region', panelField: 'warehouse' },
      ]),
    ).toEqual({
      op: 'and',
      children: [
        { field: 'status', operator: 'EQ', value: 'PENDING' },
        // Nested, not flattened: the panel may already ask about warehouse.
        {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
      ],
    });
  });

  it('is the panel filter alone when the dashboard has no condition', () => {
    const panelFilter: FilterTree = {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
    };

    expect(
      mergeGlobalFilter(panelFilter, { op: 'and', children: [] }, []),
    ).toEqual(panelFilter);
  });
});

describe('validateDashboard global fields', () => {
  it('rejects an empty, malformed or duplicated field name', () => {
    const config = dashboardConfig({
      fields: [
        { name: ' ', label: 'Blank', kind: 'string' },
        { name: 'a b', label: 'Spaced', kind: 'string' },
        REGION_FIELD,
        { name: 'region', label: 'Region again', kind: 'string' },
      ],
    });

    expect(codes(validate(config))).toEqual([
      'dashboard.field.name-empty',
      'dashboard.field.name-invalid',
      'dashboard.field.duplicate',
    ]);
  });

  it('accepts a nested field path', () => {
    const config = dashboardConfig({
      fields: [{ name: 'address.city', label: 'City', kind: 'string' }],
    });

    expect(validate(config)).toEqual([]);
  });

  it('judges the fixed scope against its own fields', () => {
    const config = dashboardConfig({ fixed: REGION_FILTER });

    expect(codes(validate(config))).toContain('filter.field.unknown');
  });
});

describe('validateDashboard panels', () => {
  it('reports too many panels before looking at any of them', () => {
    const panels = Array.from(
      { length: DEFAULT_RUNTIME_LIMITS.maxDashboardPanels + 1 },
      () => viewPanel({ id: 'same', instanceId: 'missing' }),
    );

    // One issue, not one per panel: nothing past the budget is inspected.
    expect(codes(validate(dashboardConfig({ panels })))).toEqual([
      'dashboard.panels.too-many',
    ]);
  });

  it('rejects an empty or duplicated panel id', () => {
    const config = dashboardConfig({
      panels: [
        viewPanel({ id: '' }),
        viewPanel(),
        viewPanel({ layout: { x: 6, y: 0, w: 6, h: 4 } }),
      ],
    });

    expect(codes(validate(config))).toEqual([
      'dashboard.panel.id-empty',
      'dashboard.panel.id-duplicate',
    ]);
  });

  it('rejects a layout that is not whole, positive and inside the grid', () => {
    const config = dashboardConfig({
      panels: [
        viewPanel({ id: 'a', layout: { x: -1, y: 0.5, w: 6, h: 4 } }),
        viewPanel({ id: 'b', layout: { x: 0, y: 0, w: 0, h: Infinity } }),
        viewPanel({ id: 'c', layout: { x: 20, y: 0, w: 6, h: 4 } }),
      ],
    });

    expect(codes(validate(config))).toEqual([
      'dashboard.layout.invalid',
      'dashboard.layout.invalid',
      'dashboard.layout.invalid',
      'dashboard.layout.invalid',
      'dashboard.layout.out-of-grid',
    ]);
  });

  it('places panels on the 24 columns the config says it is written in', () => {
    const inside = dashboardConfig({
      panels: [viewPanel({ layout: { x: 12, y: 0, w: 12, h: 4 } })],
    });
    const past = dashboardConfig({
      panels: [viewPanel({ layout: { x: 13, y: 0, w: 12, h: 4 } })],
    });

    expect(codes(validate(inside))).toEqual([]);
    expect(codes(validate(past))).toEqual(['dashboard.layout.out-of-grid']);
  });

  it('refuses a config that names a grid other than its own', () => {
    // Only a config that says nothing is read as the old 12 columns; one
    // that names a grid is taken at its word, and this engine draws one.
    const config = { ...dashboardConfig(), columns: 12 } as never;

    expect(validate(config)).toEqual([
      expect.objectContaining({
        code: 'dashboard.grid.unsupported',
        path: ['columns'],
        severity: 'error',
      }),
    ]);
  });

  it('reports a missing layout rather than reading through it', () => {
    const config = dashboardConfig({
      panels: [viewPanel({ layout: undefined as never })],
    });

    expect(codes(validate(config))).toEqual(['dashboard.layout.missing']);
  });

  it('reports an unknown panel kind', () => {
    const config = dashboardConfig({
      panels: [viewPanel({ kind: 'iframe' } as never)],
    });

    expect(codes(validate(config))).toEqual(['dashboard.panel.unknown-kind']);
  });
});

describe('validateDashboard references', () => {
  it('reports only the unavailable panel and keeps judging the rest', () => {
    const config = dashboardConfig({
      panels: [
        viewPanel({ id: 'gone', instanceId: 'deleted' }),
        viewPanel({ id: 'here', layout: { x: 6, y: 0, w: 6, h: 4 } }),
      ],
    });

    const issues = validate(config);

    expect(codes(issues)).toEqual(['dashboard.panel.unavailable']);
    expect(issues[0].path).toEqual(['panels', 0, 'instanceId']);
  });

  it('refuses a reference that is not a record or an analysis', () => {
    const config = dashboardConfig({ panels: [viewPanel()] });
    const nested = panelReference({ config: emptyDashboardConfig() });

    expect(
      codes(validate(config, 'personal', refs({ pending: nested }))),
    ).toEqual(['dashboard.panel.kind-unsupported']);
  });

  it('accepts an analysis reference', () => {
    const config = dashboardConfig({ panels: [viewPanel()] });
    const analysis = panelReference({ config: analysisConfig() });

    expect(validate(config, 'personal', refs({ pending: analysis }))).toEqual(
      [],
    );
  });

  it('lets a personal dashboard reference a personal view', () => {
    const config = dashboardConfig({ panels: [viewPanel()] });
    const personal = panelReference({ scope: 'personal' });

    expect(validate(config, 'personal', refs({ pending: personal }))).toEqual(
      [],
    );
  });

  it('warns of a personal reference from a shared dashboard, and allows it (D22 B)', () => {
    const config = dashboardConfig({ panels: [viewPanel()] });
    const personal = panelReference({ scope: 'personal' });

    expect(validate(config, 'shared', refs({ pending: personal }))).toEqual([
      expect.objectContaining({
        code: 'dashboard.panel.scope-too-narrow',
        path: ['panels', 0, 'instanceId'],
        severity: 'warning',
      }),
    ]);
  });

  it("judges an analysis reference against the definition's own fields", () => {
    // An analysis that expands `items` counts items rather than orders, but
    // its filter is still the query root and still stands on root fields —
    // an element field is reachable from there only through an
    // `elementMatch` condition on the array itself, which is a root field.
    // So a panel's bindings point at exactly what the definition declares.
    const items = {
      name: 'items',
      label: 'Items',
      kind: 'array' as const,
      elements: [{ name: 'sku', label: 'SKU', kind: 'string' as const }],
    };
    const reference = panelReference(
      {
        config: analysisConfig({
          elements: [{ path: 'items' }],
          filter: {
            op: 'and',
            children: [{ field: 'warehouse', operator: 'EQ', value: 'WH-1' }],
          },
        }),
      },
      {
        fields: [...ordersDefinition().fields, items],
        analysis: {
          ...ordersDefinition().analysis!,
          elements: [
            {
              path: 'items',
              aggregations: [
                {
                  field: 'sku',
                  groups: [AggregationGroupType.TERMS],
                  functions: [],
                },
              ],
            },
          ],
        },
      },
    );
    const config = dashboardConfig({ panels: [viewPanel()] });

    expect(reference.fields.map(field => field.name)).not.toContain(
      'items.sku',
    );
    expect(validate(config, 'personal', refs({ pending: reference }))).toEqual(
      [],
    );
  });

  it('answers scope coverage directly', () => {
    expect(coversScope('personal', 'personal')).toBe(true);
    expect(coversScope('shared', 'shared')).toBe(true);
    expect(coversScope('system', 'personal')).toBe(false);
  });
});

describe('validateDashboard bindings', () => {
  function bound(config: Partial<DashboardViewConfig> = {}) {
    return dashboardConfig({
      fields: [REGION_FIELD],
      fixed: REGION_FILTER,
      panels: [
        viewPanel({
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        }),
      ],
      ...config,
    });
  }

  it('accepts a complete binding of every field the fixed scope mentions', () => {
    expect(validate(bound())).toEqual([]);
  });

  it('rejects a binding whose global or panel field does not exist', () => {
    const config = bound({
      panels: [
        viewPanel({
          bindings: [
            { globalField: 'nope', panelField: 'warehouse' },
            { globalField: 'region', panelField: 'nope' },
          ],
        }),
      ],
    });

    expect(codes(validate(config))).toEqual([
      'dashboard.binding.global-unknown',
      'dashboard.binding.panel-unknown',
    ]);
  });

  it('rejects two bindings of the same global field', () => {
    const config = bound({
      panels: [
        viewPanel({
          bindings: [
            { globalField: 'region', panelField: 'warehouse' },
            { globalField: 'region', panelField: 'status' },
          ],
        }),
      ],
    });

    expect(codes(validate(config))).toEqual([
      'dashboard.binding.global-duplicate',
    ]);
  });

  it('rejects a binding onto a field of another kind', () => {
    const config = bound({
      panels: [
        viewPanel({
          bindings: [{ globalField: 'region', panelField: 'amount' }],
        }),
      ],
    });

    expect(codes(validate(config))).toEqual([
      'dashboard.binding.kind-mismatch',
    ]);
  });

  it('refuses a panel that binds only part of the fixed scope', () => {
    const config = bound({
      fields: [
        REGION_FIELD,
        { name: 'product', label: 'Product', kind: 'string' },
      ],
      fixed: {
        op: 'or',
        children: [
          { field: 'region', operator: 'EQ', value: 'CN' },
          { field: 'product', operator: 'EQ', value: 'X' },
        ],
      },
    });

    // Dropping one branch of an OR would narrow the condition instead of
    // translating it, so a partial mapping is not accepted at all.
    expect(codes(validate(config))).toEqual(['dashboard.binding.missing']);
  });

  it('judges the mapped tree against the panel definition', () => {
    const config = bound({
      fields: [{ name: 'region', label: 'Region', kind: 'number' }],
      panels: [
        viewPanel({
          bindings: [{ globalField: 'region', panelField: 'amount' }],
        }),
      ],
      fixed: {
        op: 'and',
        children: [{ field: 'region', operator: 'EQ', value: 7 }],
      },
    });

    expect(validate(config)).toEqual([]);
  });

  it('re-checks the budget on the merged tree', () => {
    const panelFilter: FilterTree = {
      op: 'and',
      children: [
        { field: 'status', operator: 'EQ', value: 'A' },
        { field: 'status', operator: 'EQ', value: 'B' },
      ],
    };
    const reference = panelReference({
      config: recordConfig({ filter: panelFilter }),
    });
    const config = bound();

    // Two trees that each fit can still exceed the budget once ANDed.
    const issues = validateDashboard(
      config,
      'personal',
      refs({ pending: reference }),
      kinds,
      { limits: { ...DEFAULT_RUNTIME_LIMITS, maxFilterNodes: 3 } },
    );

    expect(codes(issues)).toEqual(['filter.tree.too-many-nodes']);
    expect(issues[0].path).toEqual(['panels', 0, 'filter']);
  });

  it('does not judge the merged tree while a binding is broken', () => {
    const config = bound({
      panels: [viewPanel({ bindings: [] })],
    });

    expect(codes(validate(config))).toEqual(['dashboard.binding.missing']);
  });
});

describe('validateDashboard malformed configs', () => {
  // A config arrives from a store. Whatever shape it is in, admission
  // answers with an Issue at the place that is wrong, never a TypeError.
  const malformed = (config: unknown) =>
    validate(config as DashboardViewConfig);

  it('reports a skeleton that is not a dashboard', () => {
    expect(malformed({ ...dashboardConfig(), panels: 'x' })).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels'] },
    ]);
    expect(malformed({ ...dashboardConfig(), fields: null })).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['fields'] },
    ]);
  });

  it('reports a field or a panel that is not an object', () => {
    expect(
      codes(malformed(dashboardConfig({ fields: ['region' as never] }))),
    ).toContain('dashboard.shape.invalid');
    // Judged before the shared check maps fields by name, which would throw.
    expect(
      malformed(dashboardConfig({ fields: [null as never, REGION_FIELD] })),
    ).toMatchObject([{ code: 'dashboard.shape.invalid', path: ['fields', 0] }]);
    expect(
      malformed(dashboardConfig({ panels: [null as never, viewPanel()] })),
    ).toMatchObject([{ code: 'dashboard.shape.invalid', path: ['panels', 0] }]);
  });

  it('reports a panel whose id is not a string', () => {
    expect(
      codes(
        malformed(dashboardConfig({ panels: [viewPanel({ id: 3 as never })] })),
      ),
    ).toContain('dashboard.panel.id-empty');
  });

  it('reports bindings that are not a list of pairs', () => {
    expect(
      malformed(
        dashboardConfig({ panels: [viewPanel({ bindings: 'x' as never })] }),
      ),
    ).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0, 'bindings'] },
    ]);
    expect(
      malformed(
        dashboardConfig({
          panels: [viewPanel({ bindings: [{ globalField: 1 } as never] })],
        }),
      ),
    ).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0, 'bindings', 0] },
    ]);
  });

  it('reports content that is not what its kind holds', () => {
    const at = (panel: unknown) =>
      malformed(dashboardConfig({ panels: [panel as DashboardPanel] }));
    const base = { id: 'c', layout: { x: 0, y: 0, w: 6, h: 4 } };

    expect(at({ ...base, kind: 'markdown', content: 7 })).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0, 'content'] },
    ]);
    expect(codes(at({ ...base, kind: 'image', src: 7, href: 8 }))).toEqual([
      'dashboard.url.unsupported-scheme',
      'dashboard.url.unsupported-scheme',
    ]);
    expect(at({ ...base, kind: 'links', items: 'x' })).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0, 'items'] },
    ]);
    expect(
      at({ ...base, kind: 'links', items: [null, { label: 1, href: 2 }] }),
    ).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0, 'items', 0] },
      { code: 'dashboard.link.label-empty' },
      { code: 'dashboard.url.unsupported-scheme' },
    ]);
  });
});

describe('validateDashboard content panels', () => {
  function content(panel: DashboardPanel): Issue[] {
    return validate(dashboardConfig({ panels: [panel] }));
  }

  it('accepts markdown within the length limit', () => {
    expect(
      content(viewPanel({ kind: 'markdown', content: '# Weekly review' })),
    ).toEqual([]);
  });

  it('rejects markdown beyond it', () => {
    expect(
      codes(
        content(viewPanel({ kind: 'markdown', content: 'x'.repeat(20_001) })),
      ),
    ).toEqual(['dashboard.markdown.too-long']);
  });

  it('accepts http, https, mailto and relative URLs', () => {
    for (const src of [
      'https://example.com/a.png',
      'http://example.com/a.png',
      '/assets/a.png',
      'assets/a.png',
    ])
      expect(content(viewPanel({ kind: 'image', src }))).toEqual([]);

    expect(
      content(
        viewPanel({
          kind: 'links',
          items: [{ label: 'Mail', href: 'mailto:ops@example.com' }],
        }),
      ),
    ).toEqual([]);
  });

  it('rejects any other scheme, for src and href alike', () => {
    const panel = viewPanel({
      kind: 'image',
      src: 'javascript:alert(1)',
      href: 'data:text/html,<script></script>',
    });

    expect(codes(content(panel))).toEqual([
      'dashboard.url.unsupported-scheme',
      'dashboard.url.unsupported-scheme',
    ]);
  });

  it('rejects a link without a label and one with an unusable href', () => {
    const panel = viewPanel({
      kind: 'links',
      items: [{ label: ' ', href: 'vbscript:x' }],
    });

    expect(codes(content(panel))).toEqual([
      'dashboard.link.label-empty',
      'dashboard.url.unsupported-scheme',
    ]);
  });

  it('rejects more links than one panel may hold', () => {
    const items = Array.from({ length: 51 }, (_unused, index) => ({
      label: `Link ${index}`,
      href: '/a',
    }));

    expect(codes(content(viewPanel({ kind: 'links', items })))).toEqual([
      'dashboard.links.too-many',
    ]);
  });

  it('never creates a child runtime concern: content panels take no bindings', () => {
    const config = dashboardConfig({
      fields: [REGION_FIELD],
      fixed: REGION_FILTER,
      panels: [viewPanel({ kind: 'markdown', content: 'note' })],
    });

    // The fixed scope is unbound here and that is fine: nothing queries.
    expect(errors(validate(config))).toEqual([]);
  });
});

describe('isSafeContentUrl', () => {
  it('refuses blank, scheme-relative and control-character URLs', () => {
    expect(isSafeContentUrl('  ')).toBe(false);
    expect(isSafeContentUrl('//evil.example.com/a.png')).toBe(false);
    expect(isSafeContentUrl('java\nscript:alert(1)')).toBe(false);
  });

  it('is case-insensitive about the scheme', () => {
    expect(isSafeContentUrl('HTTPS://example.com')).toBe(true);
    expect(isSafeContentUrl('JavaScript:alert(1)')).toBe(false);
  });
});
