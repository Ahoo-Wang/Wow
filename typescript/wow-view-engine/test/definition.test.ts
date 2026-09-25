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
  AGGREGATION_LIMITS,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  emptyDashboardConfig,
  FIELD_CELL_IDS,
  FIELD_TONES,
  isViewCommandError,
  MemoryViewStore,
  validateDefinition,
  ViewEngine,
  type AnalysisCapability,
  type FieldDefinition,
  type Issue,
  type ViewDefinition,
} from '../src/index.js';
import { isUsableDefinition } from '../src/runtime/validateDefinition.js';
import {
  analysisConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

describe('field groups', () => {
  it('refuses a group listing a field the definition does not declare', () => {
    const definition = ordersDefinition({
      fieldGroups: [{ id: 'money', label: 'Money', fields: ['ammount'] }],
    });
    expect(codes(definition)).toEqual(['definition.fieldGroup.field-unknown']);
  });

  it('refuses a field listed twice, in one group or across two', () => {
    const definition = ordersDefinition({
      fieldGroups: [
        { id: 'money', label: 'Money', fields: ['amount', 'amount'] },
        { id: 'state', label: 'State', fields: ['status', 'amount'] },
      ],
    });
    expect(issues(definition).map(found => [found.code, found.path])).toEqual([
      [
        'definition.fieldGroup.field-duplicate',
        ['fieldGroups', 0, 'fields', 1],
      ],
      [
        'definition.fieldGroup.field-duplicate',
        ['fieldGroups', 1, 'fields', 1],
      ],
    ]);
  });

  it('refuses a group declared twice, or without an id or a label', () => {
    expect(
      codes(
        ordersDefinition({
          fieldGroups: [
            { id: 'money', label: 'Money', fields: ['amount'] },
            { id: 'money', label: 'Again', fields: [] },
            { id: '', label: 'Nameless', fields: ['nobody'] },
          ],
        }),
      ),
    ).toEqual([
      'definition.fieldGroup.duplicate',
      'definition.fieldGroup.invalid',
    ]);
  });

  it('accepts groups of declared fields, and fields outside any group', () => {
    const definition = ordersDefinition({
      fieldGroups: [
        { id: 'money', label: 'Money', fields: ['amount'] },
        { id: 'state', label: 'State', fields: ['status', 'warehouse'] },
      ],
    });
    expect(codes(definition)).toEqual([]);
  });
});

function issues(definition: ViewDefinition): Issue[] {
  return validateDefinition(definition, builtinFieldKinds);
}

function codes(definition: ViewDefinition): string[] {
  return validateDefinition(definition, builtinFieldKinds).map(
    found => found.code,
  );
}

describe('validateDefinition accepts what the package assumes', () => {
  it('admits the shared fixtures', () => {
    expect(codes(ordersDefinition())).toEqual([]);
    expect(codes(overviewDefinition())).toEqual([]);
  });

  it('answers on a definition with neither capability nor views', () => {
    expect(
      codes({
        id: 'bare',
        title: 'Bare',
        kind: 'data',
        source: 'bare',
        fields: [],
      }),
    ).toEqual([]);
  });
});

describe('validateDefinition identity', () => {
  it('refuses a separator in the definition id, which would alias views', () => {
    // `('a:b', 'c')` and `('a', 'b:c')` compose the same instance id.
    expect(codes(ordersDefinition({ id: 'orders:eu' }))).toEqual([
      'definition.id.separator',
    ]);
  });

  it('refuses a separator in a view id, and a duplicate view id', () => {
    const found = codes(
      ordersDefinition({
        views: [
          { id: 'a:b', title: 'One', config: recordConfig() },
          { id: 'all', title: 'Two', config: recordConfig() },
          { id: 'all', title: 'Three', config: recordConfig() },
        ],
      }),
    );

    expect(found).toEqual([
      'definition.view.id-separator',
      'definition.view.id-duplicate',
    ]);
  });
});

describe('validateDefinition fields', () => {
  it('refuses a name outside Wow query syntax, which the compiler would throw on', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [{ name: 'order id', label: 'Order', kind: 'string' }],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual(['definition.field.name-invalid']);
  });

  it('accepts a nested path and an `@` prefix', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [
            { name: 'address.city', label: 'City', kind: 'string' },
            { name: '@timestamp', label: 'At', kind: 'datetime' },
            { name: 'lines.0.sku', label: 'First SKU', kind: 'string' },
          ],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual([]);
  });

  it('refuses a duplicate name and an unregistered kind', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [
            { name: 'id', label: 'Order', kind: 'string' },
            { name: 'id', label: 'Again', kind: 'string' },
            { name: 'weird', label: 'Weird', kind: 'quaternion' },
          ],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual([
      'definition.field.duplicate',
      'definition.field.kind-unregistered',
    ]);
  });

  /**
   * D17-11. `FieldDefinition.editor` is gone — it was declared and never
   * read — so a definition written against an older release still carries
   * it. That is a line to delete, not a release to take down: nothing about
   * it stops working, the field gets the same control it always got, and
   * refusing would close an application over a member that did nothing.
   */
  it('warns about a field that still declares the removed editor key', () => {
    const found = issues(
      ordersDefinition({
        fields: [
          { name: 'id', label: 'Order', kind: 'string' },
          // Written the way an older definition wrote it; the member is no
          // longer on the type, so it arrives here as data.
          {
            name: 'amount',
            label: 'Amount',
            kind: 'number',
            editor: 'number-range',
          } as FieldDefinition,
        ],
        record: undefined,
        analysis: undefined,
        views: [],
      }),
    );

    expect(found.map(issue => issue.code)).toEqual([
      'definition.field.editor-removed',
    ]);
    expect(found[0].severity).toBe('warning');
    expect(found[0].path).toEqual(['fields', 1, 'editor']);
    // A warning does not close the definition: it still opens.
    expect(isUsableDefinition(found)).toBe(true);
  });
});

describe('validateDefinition capabilities', () => {
  it('refuses a record capability `defaultRecordConfig` could not build from', () => {
    expect(
      codes(
        ordersDefinition({
          record: { rowKey: 'gone', paging: 'paged', layouts: [] },
          views: [],
        }),
      ),
    ).toEqual([
      'definition.record.layouts-empty',
      'definition.record.row-key-unknown',
    ]);
  });

  /**
   * The pager divides the window by a page size to find its last page, so
   * anything but a whole number of rows above zero would stop it on a page
   * the source never bounded. A cursor has no pages for a window to bound.
   */
  it('takes a paging window only as whole rows, and only for a paged source', () => {
    const windowed = (maxWindow: unknown, paging: 'paged' | 'cursor') =>
      codes(
        ordersDefinition({
          record: {
            rowKey: 'id',
            paging,
            layouts: ['table'],
            maxWindow: maxWindow as number,
          },
          views: [],
        }),
      );

    expect(windowed(10_000, 'paged')).toEqual([]);
    for (const invalid of [0, -1, 1.5, Number.NaN, '10000'])
      expect(windowed(invalid, 'paged')).toEqual([
        'definition.record.max-window-invalid',
      ]);
    expect(windowed(10_000, 'cursor')).toEqual([
      'definition.record.max-window-cursor',
    ]);
  });

  /**
   * Every record query ends on the row key, so a row cannot show on two
   * pages when the user's sort ties (`compileRecord`). A backend asked to
   * order by a field it cannot sort on refuses the whole query, so the
   * definition has to say the row key can be — and a release that does not
   * is refused before any view of it opens.
   */
  it('refuses a row key the definition does not declare sortable', () => {
    const base = ordersDefinition();
    const unsortable = (sortable: boolean | undefined) =>
      ordersDefinition({
        fields: base.fields.map(field =>
          field.name === 'id' ? { ...field, sortable } : field,
        ),
      });

    for (const sortable of [false, undefined]) {
      const found = issues(unsortable(sortable));
      expect(found.map(issue => issue.code)).toEqual([
        'definition.record.row-key-unsortable',
      ]);
      expect(found[0]).toMatchObject({
        severity: 'error',
        path: ['record', 'rowKey'],
        params: { field: 'id' },
      });
      expect(isUsableDefinition(found)).toBe(false);
    }
    expect(codes(unsortable(true))).toEqual([]);
  });

  it('refuses an analysis capability over a field that is not declared', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: true,
            fields: [
              {
                field: 'gone',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.field-unknown']);
  });

  it('refuses an analysis capability that can construct no metric at all', () => {
    // `defaultAnalysisConfig` walks COUNT → functions → distinctCount →
    // percentile → any; a capability offering none has no starting config.
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: false,
            fields: [
              {
                field: 'warehouse',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.no-metric']);
  });

  it('accepts one built from a distinct count alone', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: false,
            fields: [
              {
                field: 'warehouse',
                groups: [AggregationGroupType.TERMS],
                functions: [],
                distinctCount: true,
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual([]);
  });

  it('checks an element field scope where it is declared', () => {
    expect(
      codes(
        ordersDefinition({
          fields: [
            {
              name: 'lines',
              label: 'Lines',
              kind: 'array',
              // A name that repeats a root field is fine: every reference to
              // an element field is `field.element`, so they cannot collide.
              elements: [
                { name: 'id', label: 'Line', kind: 'string' },
                { name: 'id', label: 'Again', kind: 'string' },
                { name: 'not a name', label: 'Bad', kind: 'string' },
              ],
            },
          ],
          record: undefined,
          analysis: undefined,
          views: [],
        }),
      ),
    ).toEqual(['definition.field.duplicate', 'definition.field.name-invalid']);
  });

  it('cannot express an array that does not exist', () => {
    // Declaring elements on the field is what removes the dangling path: a
    // separate list keyed by path would admit one naming no field at all.
    const found = codes(
      ordersDefinition({
        analysis: {
          count: true,
          fields: [],
          elements: [{ path: 'ghost', aggregations: [] }],
        },
        views: [],
      }),
    );

    expect(found).toEqual(['definition.analysis.element-undeclared']);
  });

  it('refuses an aggregation over a name the element never declared', () => {
    // The root fields always had this check and the element ones did not, so
    // an aggregation could name nothing and reach a view as a metric with no
    // field behind it.
    expect(
      codes(
        ordersDefinition({
          fields: [
            {
              name: 'items',
              label: 'Items',
              kind: 'array',
              elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
            },
          ],
          record: undefined,
          analysis: {
            count: true,
            fields: [],
            elements: [
              {
                path: 'items',
                aggregations: [{ field: 'gone', groups: [], functions: [] }],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.element-field-unknown']);
  });

  it('reports the qualified spelling as the mistake it now is', () => {
    // `qualify` no longer accepts both spellings, so writing the full path
    // where a relative name belongs is caught here rather than composing
    // `items.items.sku` and matching nothing.
    expect(
      codes(
        ordersDefinition({
          fields: [
            {
              name: 'items',
              label: 'Items',
              kind: 'array',
              elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
            },
          ],
          record: undefined,
          analysis: {
            count: true,
            fields: [],
            elements: [
              {
                path: 'items',
                aggregations: [
                  { field: 'items.sku', groups: [], functions: [] },
                ],
              },
            ],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.element-field-unknown']);
  });

  it('walks the declared elements as one chain, not a list of arrays', () => {
    // Wow's `elements` is one parent-to-child expansion: each later path is
    // relative to the element above it, and the counting unit is the
    // innermost one. Two root arrays are therefore a broken chain rather
    // than two usable ones, whichever order they are written in.
    const chained = (analysis: AnalysisCapability) =>
      ordersDefinition({
        analysis,
        record: undefined,
        views: [],
        fields: [
          {
            name: 'items',
            label: 'Items',
            kind: 'array',
            elements: [
              { name: 'sku', label: 'SKU', kind: 'string' },
              {
                name: 'tags',
                label: 'Tags',
                kind: 'array',
                elements: [{ name: 'name', label: 'Name', kind: 'string' }],
              },
            ],
          },
          {
            name: 'notes',
            label: 'Notes',
            kind: 'array',
            elements: [{ name: 'text', label: 'Text', kind: 'string' }],
          },
        ],
      });

    expect(
      codes(
        chained({
          count: true,
          fields: [],
          elements: [
            { path: 'items', aggregations: [] },
            { path: 'tags', aggregations: [] },
          ],
        }),
      ),
    ).toEqual([]);
    expect(
      codes(
        chained({
          count: true,
          fields: [],
          // `notes` is a root array, so it does not continue `items`.
          elements: [
            { path: 'items', aggregations: [] },
            { path: 'notes', aggregations: [] },
          ],
        }),
      ),
    ).toEqual(['definition.analysis.element-undeclared']);
    // A deeper level's aggregations are checked against that level.
    expect(
      codes(
        chained({
          count: true,
          fields: [],
          elements: [
            { path: 'items', aggregations: [] },
            {
              path: 'tags',
              aggregations: [{ field: 'sku', groups: [], functions: [] }],
            },
          ],
        }),
      ),
    ).toEqual(['definition.analysis.element-field-unknown']);
  });

  it('refuses a chain deeper than Wow expands', () => {
    // Beyond the ceiling a level can never be expanded, so declaring it
    // offers a capability no config may use.
    const nest = (depth: number): FieldDefinition =>
      depth === 0
        ? { name: 'leaf', label: 'Leaf', kind: 'string' }
        : {
            name: `level${depth}`,
            label: `Level ${depth}`,
            kind: 'array',
            elements: [nest(depth - 1)],
          };
    const depth = AGGREGATION_LIMITS.MAX_ELEMENTS + 1;

    expect(
      codes(
        ordersDefinition({
          fields: [nest(depth)],
          record: undefined,
          analysis: {
            count: true,
            fields: [],
            elements: Array.from({ length: depth }, (_, index) => ({
              path: `level${depth - index}`,
              aggregations: [],
            })),
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.elements-too-many']);
  });

  it('refuses an analysis expanding a field that holds no elements', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: true,
            fields: [],
            // `warehouse` is a plain string; there is nothing to expand.
            elements: [{ path: 'warehouse', aggregations: [] }],
          },
          views: [],
        }),
      ),
    ).toEqual(['definition.analysis.element-undeclared']);
  });

  it('refuses limits that are not positive integers, or a default above the max', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: {
            count: true,
            fields: [],
            limits: { maxGroups: 0, maxLimit: 10, defaultLimit: 50 },
          },
          views: [],
        }),
      ),
    ).toEqual([
      'definition.analysis.limit-invalid',
      'definition.analysis.default-limit-too-large',
    ]);
  });
});

describe('validateDefinition system views', () => {
  it('refuses a config the definition declares no capability for', () => {
    expect(
      codes(
        ordersDefinition({
          analysis: undefined,
          views: [{ id: 'chart', title: 'Chart', config: analysisConfig() }],
        }),
      ),
    ).toEqual(['definition.view.kind-mismatch']);
  });

  it('refuses a dashboard config under a data definition, and the reverse', () => {
    expect(
      codes(
        ordersDefinition({
          views: [
            { id: 'board', title: 'Board', config: emptyDashboardConfig() },
          ],
        }),
      ),
    ).toEqual(['definition.view.kind-mismatch']);
    expect(
      codes(
        overviewDefinition({
          views: [{ id: 'rows', title: 'Rows', config: recordConfig() }],
        }),
      ),
    ).toEqual(['definition.view.kind-mismatch']);
  });

  it('runs the config through its own kernel, pointing at the view that holds it', () => {
    const found = validateDefinition(
      ordersDefinition({
        views: [
          {
            id: 'broken',
            title: 'Broken',
            config: recordConfig({ pageSize: 0 }),
          },
        ],
      }),
      builtinFieldKinds,
    );

    expect(found.map(entry => entry.code)).toEqual([
      'record.pageSize.not-positive',
    ]);
    expect(found[0].path).toEqual(['views', 0, 'config', 'pageSize']);
  });

  it('judges a dashboard system view locally, leaving panel references to the engine', () => {
    // The panel's instance cannot be loaded here, so nothing is said of it:
    // the engine judges the reference when the board is opened.
    const found = validateDefinition(
      overviewDefinition({
        views: [
          {
            id: 'ops',
            title: 'Ops',
            config: {
              ...emptyDashboardConfig(),
              panels: [
                {
                  id: 'rows',
                  kind: 'view',
                  instanceId: 'orders-1',
                  bindings: [],
                  layout: { x: 0, y: 0, w: 6, h: 4 },
                },
              ],
            },
          },
        ],
      }),
      builtinFieldKinds,
    );

    expect(found).toEqual([]);
    expect(isUsableDefinition(found)).toBe(true);
  });
});

describe('ViewEngine and an unusable definition', () => {
  function engineWith(definition: ViewDefinition, issues: Issue[]) {
    return new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => testSource(),
      onIssue: found => issues.push(found),
    });
  }

  it('reports what it found, once, at registration', () => {
    const issues: Issue[] = [];
    const engine = engineWith(ordersDefinition({ id: 'a:b' }), issues);

    expect(issues.map(found => found.code)).toEqual([
      'definition.id.separator',
    ]);
    expect(engine.definitionIssues('a:b').map(found => found.code)).toEqual([
      'definition.id.separator',
    ]);
    expect(engine.definitionIssues('unknown')).toEqual([]);
  });

  it('refuses to open or create against it, rather than failing deeper', async () => {
    const issues: Issue[] = [];
    const engine = engineWith(
      ordersDefinition({
        record: { rowKey: 'gone', paging: 'paged', layouts: [] },
        views: [],
      }),
      issues,
    );

    const refused = await engine
      .list('orders')
      .catch((error: unknown) => error);
    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.definition.invalid',
    );
    expect(() =>
      engine.create('orders', {
        title: 'New',
        scope: 'personal',
        config: recordConfig(),
      }),
    ).toThrow();
  });

  it('leaves a definition with only warnings usable', async () => {
    const issues: Issue[] = [];
    const engine = engineWith(
      overviewDefinition({
        views: [
          {
            id: 'ops',
            title: 'Ops',
            config: {
              ...emptyDashboardConfig(),
              panels: [
                {
                  id: 'rows',
                  kind: 'view',
                  instanceId: 'orders-1',
                  bindings: [],
                  layout: { x: 0, y: 0, w: 6, h: 4 },
                },
              ],
            },
          },
        ],
      }),
      issues,
    );

    expect(issues.every(found => found.severity === 'warning')).toBe(true);
    await expect(engine.list('overview')).resolves.toMatchObject({
      items: [expect.anything()],
      failed: null,
    });
  });

  it('says nothing about a definition that is fine', () => {
    const onIssue = vi.fn();
    new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => testSource(),
      onIssue,
    });

    expect(onIssue).not.toHaveBeenCalled();
  });
  it('refuses a text comparison Wow does not know', () => {
    // `filter.contains` throws on an unknown comparison, and it would throw
    // while compiling a query rather than while reading the definition.
    const found = validateDefinition(
      ordersDefinition({
        fields: [
          {
            name: 'sku',
            label: 'SKU',
            kind: 'string',
            stringComparison: 'LOOSE' as never,
          },
        ],
        record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
        analysis: undefined,
        views: [],
      }),
      builtinFieldKinds,
    );

    expect(found.map(entry => entry.code)).toContain(
      'definition.field.string-comparison-invalid',
    );
  });

  it('admits the two comparisons it does know', () => {
    for (const stringComparison of [
      'CASE_SENSITIVE',
      'CASE_INSENSITIVE',
    ] as const) {
      expect(
        validateDefinition(
          ordersDefinition({
            fields: [
              {
                name: 'sku',
                label: 'SKU',
                kind: 'string',
                sortable: true,
                stringComparison,
              },
            ],
            record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
            analysis: undefined,
            views: [],
          }),
          builtinFieldKinds,
        ),
      ).toEqual([]);
    }
  });

  /**
   * The renderers and the tones are closed sets, for the same reason as every
   * other capability: `/ui` switches over them, so a key nothing switches on
   * would not fail — it would quietly draw the default, and a column declared
   * as a link would stay a string of characters nobody can click.
   */
  it('refuses a cell renderer and a tone nothing draws', () => {
    const found = validateDefinition(
      ordersDefinition({
        fields: [
          {
            name: 'sku',
            label: 'SKU',
            kind: 'string',
            sortable: true,
            cell: 'chip' as never,
          },
          {
            name: 'status',
            label: 'Status',
            kind: 'enum',
            options: [
              { value: 'PENDING', label: 'Pending', tone: 'neutral' },
              { value: 'LOST', label: 'Lost', tone: 'fuchsia' as never },
            ],
          },
        ],
        record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
        analysis: undefined,
        views: [],
      }),
      builtinFieldKinds,
    );

    expect(found.map(entry => [entry.code, entry.path])).toEqual([
      ['definition.field.cell-invalid', ['fields', 0, 'cell']],
      // At the option that declared it, not at the field: a definition with
      // eight statuses needs to be told which one to go and fix.
      ['definition.field.tone-invalid', ['fields', 1, 'options', 1, 'tone']],
    ]);
  });

  /**
   * `elementTitle` is what a cell reads each element by, so a name the
   * elements do not declare would not fail — every element would read as
   * untitled. And a title has to hold a value: a handle names no member of
   * the element, and a further array of objects is a list, not a name.
   */
  it('admits an element title only where the elements declare a value by it', () => {
    const body = (elementTitle: string, elements?: FieldDefinition[]) => ({
      name: 'body',
      label: 'Events',
      kind: 'elementMatch',
      elementTitle,
      ...(elements ? { elements } : {}),
    });
    const events: FieldDefinition[] = [
      { name: 'name', label: 'Event', kind: 'string' },
      { name: 'text', label: 'Text', kind: 'search' },
      {
        name: 'lines',
        label: 'Lines',
        kind: 'array',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ];
    const found = (field: FieldDefinition) =>
      validateDefinition(
        ordersDefinition({
          fields: [
            { name: 'sku', label: 'SKU', kind: 'string', sortable: true },
            field,
          ],
          record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
          analysis: undefined,
          views: [],
        }),
        builtinFieldKinds,
      ).map(entry => [entry.code, entry.path, entry.params]);

    expect(found(body('name', events))).toEqual([]);
    expect(found(body('missing', events))).toEqual([
      [
        'definition.field.element-title-unknown',
        ['fields', 1, 'elementTitle'],
        { field: 'body', title: 'missing' },
      ],
    ]);
    // A field holding no elements has nothing to title.
    expect(found(body('name'))).toEqual([
      [
        'definition.field.element-title-unknown',
        ['fields', 1, 'elementTitle'],
        { field: 'body', title: 'name' },
      ],
    ]);
    for (const title of ['text', 'lines'])
      expect(found(body(title, events))).toEqual([
        [
          'definition.field.element-title-not-a-value',
          ['fields', 1, 'elementTitle'],
          { field: 'body', title },
        ],
      ]);
  });

  it('admits every reading and every tone it can draw', () => {
    for (const cell of FIELD_CELL_IDS) {
      expect(
        validateDefinition(
          ordersDefinition({
            fields: [
              {
                name: 'sku',
                label: 'SKU',
                kind: 'string',
                sortable: true,
                cell,
                options: FIELD_TONES.map(tone => ({
                  value: tone,
                  label: tone,
                  tone,
                })),
              },
            ],
            record: { rowKey: 'sku', paging: 'paged', layouts: ['table'] },
            analysis: undefined,
            views: [],
          }),
          builtinFieldKinds,
        ),
      ).toEqual([]);
    }
  });
});
